package dev.eliaschen.wristch.computer

import android.graphics.Bitmap
import android.util.Log
import com.google.genai.Client
import com.google.genai.types.ComputerUse
import com.google.genai.types.Content
import com.google.genai.types.Environment
import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.FunctionResponse
import com.google.genai.types.FunctionResponsePart
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GoogleSearch
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.Tool
import com.google.genai.types.ToolConfig
import com.google.genai.types.Type
import dev.eliaschen.wristch.accessibility.WristchAccessibilityService
import dev.eliaschen.wristch.vibe.VibeConfirmation
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

private class Shot(val bytes: ByteArray, val size: ScreenSize, val tree: String)

class ComputerUseAgent(
    private val service: WristchAccessibilityService,
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
) {

    private val client = Client.builder().apiKey(apiKey).build()

    private val json = Json { ignoreUnknownKeys = true }

    private val dispatcher = ActionDispatcher(service.executor, service)

    private val overlay = ConfirmationOverlay(service)

    /** Pause, resume and stop, driven from the overlay's own buttons. */
    val control = RunControl()

    /**
     * Things the person said after the run started, waiting to be handed to the model.
     *
     * A queue rather than a field: a note is written while the run is held, and the run
     * reads it at the next point it asks the model anything. Concurrent because it is
     * written from the overlay's window and drained by the run's own coroutine.
     */
    private val notes = ConcurrentLinkedQueue<String>()

    private val statusOverlay = StatusOverlay(service, control, apiKey, onNote = ::note)

    private val config: GenerateContentConfig = GenerateContentConfig.builder()
        .systemInstruction(Content.fromParts(Part.fromText(SYSTEM_INSTRUCTION)))
        .toolConfig(ToolConfig.builder().includeServerSideToolInvocations(true).build())
        .tools(
            listOf(
                Tool.builder()
                    .computerUse(
                        ComputerUse.builder()
                            .environment(Environment(Environment.Known.ENVIRONMENT_MOBILE))
                            .build(),
                    )
                    .build(),
                // Asking is offered as an action rather than as a sentence the model is
                // asked to write in a particular shape. A model holding a set of tools
                // reaches for a tool; told to reply with a magic prefix instead, it does
                // what it can already do - it taps something and hopes - which is exactly
                // the guess that asking exists to prevent.
                Tool.builder().functionDeclarations(listOf(ASK_USER)).build(),
            ),
        )
        .build()

    /**
     * A question-answering pass that never touches the phone.
     *
     * `computer_use` and `google_search` are rejected together - "cannot be combined in the
     * same request" - so the two capabilities cannot live in one call. Asking first is the
     * cheaper half of that trade: this request carries no screenshot, and it saves a
     * question from being answered by opening a browser and typing into it.
     */
    private val triageConfig: GenerateContentConfig = GenerateContentConfig.builder()
        .systemInstruction(Content.fromParts(Part.fromText(TRIAGE_INSTRUCTION)))
        .tools(listOf(Tool.builder().googleSearch(GoogleSearch.builder().build()).build()))
        .responseMimeType(MIME_JSON)
        .responseSchema(TriageResponse.SCHEMA)
        .build()

    /**
     * Names a run in a few words. No tools and no screenshot: it reads one sentence and
     * writes a shorter one.
     */
    private val titleConfig: GenerateContentConfig = GenerateContentConfig.builder()
        .systemInstruction(Content.fromParts(Part.fromText(TITLE_INSTRUCTION)))
        .build()

    /**
     * Reads a finished run and picks out what is worth keeping. No tools and no
     * screenshot: everything it needs already happened, and is in the transcript.
     */
    private val memoryConfig: GenerateContentConfig = GenerateContentConfig.builder()
        .systemInstruction(Content.fromParts(Part.fromText(MEMORY_INSTRUCTION)))
        .responseMimeType(MIME_JSON)
        .responseSchema(MemoryResponse.SCHEMA)
        .build()

    /**
     * Takes something the person added mid-run, to be given to the model on its next turn.
     *
     * Never injected into the conversation from here: the history list belongs to the
     * run's own coroutine, and a second thread appending to it between a request and its
     * reply is how a conversation ends up out of order.
     */
    fun note(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        Log.i(TAG, "note added: $cleaned")
        notes.add(cleaned)
    }

    /**
     * A short name for [goal], or null if the model could not be reached.
     *
     * Null rather than a fallback string: the caller already has the goal, and a run left
     * untitled reads as one titled with what was typed, which is the old behaviour and a
     * perfectly good one to fail back to.
     */
    suspend fun title(goal: String): String? = runCatching {
        withContext(Dispatchers.IO) {
            client.models.generateContent(model, goal, titleConfig)
        }.text()?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }?.take(MAX_TITLE_CHARS)
    }.getOrElse { error ->
        // A run that cannot be named is still a run; this never touches its outcome.
        Log.w(TAG, "title failed: ${error.message}")
        null
    }

    /**
     * What [goal] taught, as lines to keep between runs - empty when it taught nothing.
     *
     * Read from the transcript rather than asked of the agent mid-run: a model deciding
     * what to remember while it is still deciding what to tap writes down the tapping.
     * By the time this runs the outcome is known, which is the only vantage point from
     * which "was that worth keeping" has an answer.
     *
     * Failure is silence. A run's value is what it did to the phone, and no extraction
     * problem should be able to reach back and change that.
     */
    suspend fun remember(goal: String, outcome: String, steps: List<String>): List<String> =
        runCatching {
            val transcript = buildString {
                append("The task: ")
                append(goal)
                append("\n\nWhat the agent did:\n")
                // The tail, not the head: a run that wandered before it found the right
                // screen learned what it learned at the end of that, not at the start.
                steps.takeLast(MAX_MEMORY_STEPS).forEach { append("- ").append(it).append('\n') }
                append("\nHow it ended: ")
                append(outcome)
            }
            val text = withContext(Dispatchers.IO) {
                client.models.generateContent(model, transcript, memoryConfig)
            }.text() ?: error("memory returned no text")
            json.decodeFromString<MemoryResponse>(text).remember
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_MEMORIES_PER_RUN)
        }.getOrElse { error ->
            Log.w(TAG, "memory failed: ${error.message}")
            emptyList()
        }

    /**
     * Runs [goal] to completion, or until [maxSteps] actions have been taken. [onStep] is
     * called with a one-line summary of every action, so a caller can show progress without
     * waiting for the whole run.
     *
     * [confirmation] is the vibe's own answer to "before it acts" - a run with no vibe
     * behind it gets the same default a fresh vibe does, which is the most cautious of the
     * three rather than the most convenient.
     */
    suspend fun run(
        goal: String,
        maxSteps: Int = DEFAULT_MAX_STEPS,
        confirmation: VibeConfirmation = VibeConfirmation.ALWAYS,
        onStep: (String) -> Unit = {},
    ): String = coroutineScope {
        control.reset()
        statusOverlay.show(goal)
        try {
            // Triage is a whole round trip that the device path does not depend on, so it
            // runs while the phone is going home and settling. On a slow link that hides
            // the entire preparation inside a request that was going to be paid for
            // anyway, instead of adding to it.
            val verdict = async { triage(goal) }
            val start = prepare()
            val answer = withTimeoutOrNull(TRIAGE_BUDGET_MS) { verdict.await() }
            if (answer != null) {
                verdict.cancel()
                done(answer)
            } else {
                // A triage that has not answered by now has already lost its race with
                // the device; nothing downstream reads it.
                verdict.cancel()
                drive(goal, start, maxSteps, confirmation, onStep)
            }
        } finally {
            statusOverlay.hide()
        }
    }

    /**
     * Sends the phone home and takes the first look at it.
     *
     * The run is started from inside Wristch, so without this the first thing the model
     * ever sees is our own UI - and it will reason about it, tap our tabs and try to
     * complete the task inside the app that launched it.
     */
    private suspend fun prepare(): Shot? {
        service.executor.pressHome()
        delay(HOME_SETTLE_MS)
        return capture()
    }

    /** The answer when the goal was only a question, or null when the device is needed. */
    private suspend fun triage(goal: String): String? {
        val verdict = runCatching {
            val text = withContext(Dispatchers.IO) {
                client.models.generateContent(model, goal, triageConfig)
            }.text() ?: error("triage returned no text")
            json.decodeFromString<TriageResponse>(text)
        }.getOrElse { error ->
            // Neither a failed request nor a malformed reply should sink the run: the
            // device path is the safe default, since it can still do the work.
            Log.w(TAG, "triage failed: ${error.message}")
            return null
        }

        if (verdict.needsDevice || verdict.answer.isBlank()) return null
        Log.i(TAG, "answered without the device")
        return verdict.answer
    }

    private suspend fun drive(
        goal: String,
        start: Shot?,
        maxSteps: Int,
        confirmation: VibeConfirmation,
        onStep: (String) -> Unit,
    ): String {
        var shot = start ?: return "Screenshot failed; is the service connected?"

        val history = mutableListOf(
            Content.builder()
                .role("user")
                .parts(
                    listOfNotNull(
                        Part.fromText(goal),
                        shot.tree.takeIf { it.isNotEmpty() }?.let { Part.fromText(it) },
                        Part.fromBytes(shot.bytes, MIME_JPEG),
                    ),
                )
                .build(),
        )

        repeat(maxSteps) {
            // Asking the model costs seconds and money, so the pause is honoured before
            // the request rather than after it comes back.
            if (!control.awaitGo()) return done(STOPPED)

            // After the hold, not before it: adding a note holds the run, so anything
            // typed is in hand by the time the wait above ends, and goes into the very
            // next request rather than the one after it.
            drainNotes()?.let { added ->
                history += Content.builder()
                    .role("user")
                    .parts(Part.fromText(added.prompt))
                    .build()
                added.lines.forEach { line ->
                    val step = "note ($line) -> Added by the user while the run was going."
                    Log.i(TAG, step)
                    onStep(step)
                }
            }

            val response = withContext(Dispatchers.IO) {
                client.models.generateContent(model, history, config)
            }

            val reply = response.candidates().orElse(emptyList())
                .firstOrNull()?.content()?.orElse(null)
                ?: return "Model returned no content."
            history += reply

            val calls = response.functionCalls()
            if (calls.isNullOrEmpty()) {
                val said = response.text() ?: "Finished."
                // A turn with no action is usually the run ending. The one exception is a
                // question the model cannot answer for itself, which it marks so that it
                // can be told apart from an outcome without a second round trip.
                val wanted = questionIn(said) ?: return done(said)

                statusOverlay.update(wanted)
                val answer = statusOverlay.ask(wanted)
                val line = if (answer == null) {
                    "ask ($wanted) -> No answer given."
                } else {
                    "ask ($wanted) -> $answer"
                }
                Log.i(TAG, line)
                onStep(line)
                if (answer == null) return done(unanswered(wanted))

                history += Content.builder()
                    .role("user")
                    .parts(Part.fromText("The user answers: $answer"))
                    .build()
                return@repeat
            }

            val results = mutableListOf<Part>()
            for (call in calls) {
                val name = call.name().orElse("")
                val args = call.args().orElse(emptyMap())

                // Asking is not a gesture: nothing is dispatched, nothing is approved -
                // the person is being spoken to directly - so it is handled before the
                // machinery that exists for touching the screen.
                if (name == ASK_USER_NAME) {
                    val asked = (args["question"] as? String)?.trim().orEmpty()
                    if (asked.isEmpty()) {
                        results += Part.fromFunctionResponse(
                            name,
                            mapOf("result" to "No question was given, so nothing was asked."),
                        )
                        continue
                    }
                    statusOverlay.update(asked)
                    val answer = statusOverlay.ask(asked)
                    val line = if (answer == null) {
                        "ask ($asked) -> No answer given."
                    } else {
                        "ask ($asked) -> $answer"
                    }
                    Log.i(TAG, line)
                    onStep(line)
                    if (answer == null) return done(unanswered(asked))
                    results += Part.fromFunctionResponse(
                        name,
                        mapOf("result" to "The user answers: $answer"),
                    )
                    continue
                }

                // Every call carries the model's own one-line reason; it is the only
                // window into why a step happened when a run goes sideways.
                val reason = args["intent"] as? String

                // The model flags steps it will not take unsupervised - consent dialogs,
                // payments, messages to other people. Refusing here has to mean the
                // gesture never happens, so the check sits ahead of the dispatcher.
                // A single reply can carry several actions; each one is a place the run
                // can be held or ended, so the gate sits inside the loop, not outside it.
                if (!control.awaitGo()) return done(STOPPED)

                val before = shot.tree
                val outcome = if (needsApproval(args, confirmation) && !approve(args, reason ?: name)) {
                    DECLINED
                } else {
                    // The strip is touchable now that it carries buttons, which means it
                    // would swallow a tap the agent aimed at the app beneath it. Taking it
                    // off screen for the gesture is the same bracket the screenshot needs.
                    statusOverlay.hiddenDuring { dispatcher.execute(call, shot.size) }
                }
                val line = if (reason == null) "$name -> $outcome" else "$name ($reason) -> $outcome"
                Log.i(TAG, line)
                onStep(line)
                statusOverlay.update(reason ?: name)

                // Re-shoot after every action: the next decision must see what this one did.
                shot = capture() ?: return "Screenshot failed mid-run after $name."

                // A scroll that moved nothing is the only reliable proof a page has no
                // more to it. Told plainly, it is what stops the model both from scrolling
                // a finished list forever and from concluding after one screen that what
                // it wants is not there.
                val settled = endOfPage(name, before, shot.tree)

                // The tree goes in the response map rather than beside the image, so it
                // survives trimming: once the screenshot is dropped, this line is all the
                // history keeps about what that step left on screen.
                val payload = buildMap {
                    put("result", outcome + settled)
                    if (shot.tree.isNotEmpty()) put("screen", shot.tree)
                }
                results += Part.fromFunctionResponse(
                    name,
                    payload,
                    FunctionResponsePart.fromBytes(shot.bytes, MIME_JPEG),
                )
            }
            history += Content.builder().role("user").parts(results).build()
            trimScreenshots(history)
        }

        return done("Stopped after $maxSteps steps without finishing.")
    }

    /**
     * The question inside a turn that asked one, or null if the model was finishing.
     *
     * A marked prefix rather than a guess at whether a sentence is a question: an outcome
     * can end in a question mark, questions can be asked in any language this app is used
     * in, and mistaking one for the other either ends a run that had more to do or hangs a
     * finished one on a box nobody expected.
     */
    private fun questionIn(text: String): String? {
        val trimmed = text.trim()
        val marker = ASK_MARKERS.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
            ?: return null
        return trimmed.removeRange(0, marker.length).trim().takeIf { it.isNotEmpty() }
    }

    /** How a run reads when the one thing it needed was not given. */
    private fun unanswered(question: String): String =
        "Stopped: this needed an answer to \"$question\" and did not get one."

    /** What was added mid-run, as one block for the model and one line each for the log. */
    private class Notes(val lines: List<String>, val prompt: String)

    /**
     * Everything typed since the last turn, or null if nothing was.
     *
     * Sent as a plain user turn rather than folded into the goal: the goal is what was
     * asked for at the start, and a correction arriving at step nine is not a rewrite of
     * it. It reads to the model the way it read to the person - as someone speaking up
     * part-way through.
     */
    private fun drainNotes(): Notes? {
        val lines = generateSequence { notes.poll() }.toList()
        if (lines.isEmpty()) return null
        val prompt = buildString {
            append("The user is watching you work and has just added this. It is newer ")
            append("than anything above, and it wins where the two disagree:\n")
            lines.forEach { append("- ").append(it).append('\n') }
            append("\nCarry on with the task, taking it into account.")
        }
        return Notes(lines, prompt)
    }

    /**
     * The sentence to append to a scroll's result: whether the screen actually moved.
     *
     * Compared on the element list rather than the image, because a JPEG differs on
     * animation and antialiasing alone - two screenshots of the same settled page are
     * rarely identical bytes, while its element list is.
     */
    private fun endOfPage(action: String, before: String, after: String): String = when {
        !action.startsWith("scroll") -> ""
        before.isEmpty() || after.isEmpty() -> ""
        before != after -> ""
        else -> END_OF_PAGE
    }

    /** Parks the outcome on the overlay until it is acknowledged, then returns it. */
    private suspend fun done(outcome: String): String {
        Log.i(TAG, "done: $outcome")
        statusOverlay.finish(outcome)
        return outcome
    }

    /**
     * Whether this step stops for a human before it happens.
     *
     * [VibeConfirmation.ALWAYS] and [VibeConfirmation.NEVER] are decided here, not by the
     * model - a vibe set to ask every time should not depend on Gemini's own judgment of
     * what counts as risky agreeing with it, and a vibe set to never ask should not be
     * overridden by the model deciding otherwise either. Only [VibeConfirmation.RISKY_ONLY]
     * actually defers to the model's `safety_decision`, which is the one case where "risky"
     * is a judgment call rather than a blanket rule.
     *
     * The text comparison in the risky-only branch is deliberate: the value's runtime type
     * is the SDK's business, and a decision that silently reads as "no approval needed"
     * because it arrived as a node instead of a String would disable the gate without a
     * trace.
     */
    private fun needsApproval(args: Map<String, Any?>, confirmation: VibeConfirmation): Boolean =
        when (confirmation) {
            VibeConfirmation.ALWAYS -> true
            VibeConfirmation.NEVER -> false
            VibeConfirmation.RISKY_ONLY -> safetyField(args, "decision") == REQUIRE_CONFIRMATION
        }

    private suspend fun approve(args: Map<String, Any?>, action: String): Boolean {
        val explanation = safetyField(args, "explanation")
        // Approvals and refusals both get logged: a gate whose decisions leave no trace
        // cannot be told apart from a gate that never ran.
        Log.i(TAG, "approval requested: $action")
        val allowed = overlay.confirm(action, explanation ?: "This step needs your approval.")
        Log.i(TAG, "approval ${if (allowed) "granted" else "denied"}: $action")
        return allowed
    }

    private fun safetyField(args: Map<String, Any?>, field: String): String? =
        when (val raw = args["safety_decision"]) {
            null -> null
            is Map<*, *> -> raw[field]?.toString()
            else -> {
                Log.w(TAG, "safety_decision is ${raw::class.java.name}, not a map")
                null
            }
        }

    private suspend fun capture(): Shot? {
        // Never photograph a screen that is still moving - see awaitIdle.
        service.awaitIdle()
        val bitmap = statusOverlay.hiddenDuring { service.captureScreenshot() } ?: return null
        val size = ScreenSize(bitmap.width, bitmap.height)

        val scale = min(1f, MAX_UPLOAD_EDGE / maxOf(bitmap.width, bitmap.height).toFloat())
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt(),
                (bitmap.height * scale).roundToInt(),
                true,
            )
        } else {
            bitmap
        }

        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()

        val bytes = stream.toByteArray()
        val tree = service.describeScreen(size.width, size.height)
        Log.i(TAG, "screen ${size.width}x${size.height} -> ${bytes.size / 1024}KB, ${tree.lines().size} elements")
        return Shot(bytes, size, tree)
    }

    /**
     * Every request re-sends the whole conversation, so a screenshot from step 2 is paid
     * for again on every step after it - the per-step cost climbs with the step count.
     * Only the most recent views of the screen inform the next decision, so older images
     * are replaced by a placeholder while their text and function results stay put.
     */
    private fun trimScreenshots(history: MutableList<Content>) {
        val keepFrom = history.size - KEEP_SCREENSHOTS
        for (index in 0 until keepFrom.coerceAtLeast(0)) {
            val content = history[index]
            val parts = content.parts().orElse(emptyList())
            if (parts.none { it.carriesImage() }) continue
            history[index] = Content.builder()
                .role(content.role().orElse("user"))
                .parts(parts.map { it.withoutImage() })
                .build()
        }
    }

    private fun Part.carriesImage(): Boolean =
        inlineData().isPresent ||
            functionResponse().map { it.parts().orElse(emptyList()).isNotEmpty() }.orElse(false)

    private fun Part.withoutImage(): Part {
        if (inlineData().isPresent) return Part.fromText(OMITTED)

        val response = functionResponse().orElse(null) ?: return this
        if (response.parts().orElse(emptyList()).isEmpty()) return this
        return Part.builder()
            .functionResponse(
                FunctionResponse.builder()
                    .name(response.name().orElse(""))
                    .response(response.response().orElse(emptyMap()))
                    .build(),
            )
            .build()
    }

    companion object {
        private const val TAG = "WristchAgent"

        /**
         * What this device cannot do, in the model's own vocabulary.
         *
         * Every line here is something a run actually got wrong: typing before focusing a
         * field, asking for key events that no accessibility service can inject, repeating
         * an action that silently did nothing. The model cannot discover these from a
         * screenshot, and a failure string only reaches it after the step is wasted.
         */
        private val SYSTEM_INSTRUCTION = """
            You are driving a real Android phone through an accessibility service.

            The task and the vibe above it may be written in any language, most often
            Traditional Chinese. Follow them exactly as written, whichever language they
            are in, and never treat an instruction as weaker because of the language it
            arrived in.

            Typing:
            - The `type` action writes into whichever field currently holds focus.
              Always `click` the text field first, then `type`.
            - Key events cannot be injected. There is no way to press individual keys,
              key combinations, or Enter on its own. Use `type` with `press_enter` instead.
            - Never enter text by tapping keys on the on-screen keyboard - not letters,
              not digits, not symbols. One `type` call writes the whole value at once.
              Tapping keys one at a time is how a run exhausts its steps without
              finishing.

            Writing what you type:
            - Never type the task's own wording verbatim into a search box, a message, a
              note, or any other field a person will read. Rewrite it as the thing that
              actually belongs there - a search query is not a sentence, a message is not
              the instruction that asked for it.
            - Always check your own grammar, spelling and punctuation before typing.
              Nothing you write on someone else's behalf should read like a first draft.
            - If a vibe's instructions set a tone, wording or standing rules, every piece
              of written content this run produces follows them - not only which actions
              get taken. A vibe that asks for warmth or formality applies to the message
              itself, not just to whether the run stops to ask first.

            Asking the person:
            - If you cannot go on without something only this person can tell you, reply
              with `NEED: ` followed by your question, and take no action that turn. The
              question is put on their screen and their answer comes back as the next
              message.
            - This is for what is genuinely not on the phone or not decidable from it:
              which of the three Chens in Contacts is the "Mum" they meant, which of two
              numbers to call, whether "Friday" is this week's or next week's, a passcode
              or a name nothing on screen supplies.
            - It is not for what looking would answer. Scroll the list, open the contact,
              read the next screen first. A question costs them their attention, and the
              wrong reflex is to ask before looking.
            - One question, one sentence, in the language the task was written in. Say what
              you will do with the answer if it is not obvious.
            - Never guess at a person, a number, an account or an amount instead of asking.
              A message sent to the wrong Chen cannot be taken back.

            Reaching the right person:
            - Before sending anything to anyone, be certain who they are. Certain means
              the recipient matches something given to you - an exact username, phone
              number or email in the task, the vibe's background, or a remembered fact -
              or is an existing conversation or saved contact whose name matches exactly.
            - A name typed into an app's search box is not identification. Search results
              are full of strangers with the same or similar names, and a message sent to
              one of them is the worst outcome a run can have - worse than not sending at
              all. Never pick a search result on similarity alone.
            - When the person cannot be pinned down - no exact match, several candidates,
              or only a bare name with nothing to check it against - stop and ask for
              something that identifies them: their exact username or handle, their phone
              number, or their email. Ask before opening a stranger's profile, not after
              the message is half-typed.
            - An existing chat thread with matching history, or a contact saved on the
              phone, is identification enough; do not interrogate the user about people
              they clearly already talk to.

            Navigation:
            - Use `open_app` to launch an app by name; it is far more reliable than
              navigating the home screen and app drawer.
            - `list_apps` returns every installed app, including ones with similar names.
              Prefer the platform app for a task - Clock, Contacts, Settings, Messages -
              over a third-party app whose name merely contains the same word.
            - When an app is named - in the task, or in the vibe's standing instructions
              or background above - that is the one to open, not whichever messenger or
              social app seems like the equivalent choice. "I talk to my classmates on
              Instagram" is an instruction to open Instagram, not a remark about the
              user's habits. Two messaging apps are never interchangeable: the person you
              are asked to reach may only read one of them, and a message sent through the
              wrong one has not reached them at all. If the named app is not installed,
              say so in the result rather than substituting another.
            - Never pick a messaging app by what is popular where the user lives, or by
              what is already open. If nothing has named one and more than one would do,
              ask (see below) rather than choosing for them.
            - Wristch is the app you run inside. Never open it or interact with its
              screens; it is not part of any task.
            - Use `go_back` rather than swiping from a screen edge.

            Reading the screen:
            - Each screenshot comes with a text list of the elements on it, one per line:
              `(x,y) Role "label" [flags]`. Those coordinates are already in the coordinate
              system your actions use - click one as it is written, without converting it.
            - Trust that list for labels, ids and exact hit points; trust the screenshot
              for layout and for anything the list does not mention. When the two disagree,
              the screenshot is right - the list can miss elements drawn on a canvas, in a
              WebView or in a game.

            Looking for something on a page:
            - The element list describes only what is on screen right now. Anything below
              the fold is not in it and not in the screenshot; it is not missing, it is
              simply further down.
            - Before deciding that something is not on a page, scroll to the bottom of it.
              Keep scrolling until a scroll tells you the screen did not move - that, and
              only that, means you have seen the whole page. One or two screens is not a
              search.
            - Read each new screen as it arrives. What you are looking for often sits in a
              section, an attachment list or a table part-way down, not at the top.
            - Do not `go_back` from a page that could still hold the answer. Leaving is the
              step that is expensive to undo: the page has to be found again from scratch.

            Waiting:
            - A screen that is still loading is not a screen that failed. Spinners,
              progress bars, blank or half-drawn pages, placeholder boxes, a web page with
              no content yet - all mean the answer has not arrived. Call `wait` with one
              second and look again; repeat if it is still loading.
            - Never act on a half-drawn screen. Anything you tap there is likely to move
              out from under you as the rest of it lands, and the tap goes to whatever
              takes its place.

            Checking your work:
            - Compare the screenshot after each action with the one before it. If nothing
              changed and nothing is loading, the action did not do what you expected. Do
              not repeat it - the same action will fail the same way. Try a different
              element or a different route.
            - A result string describing a failure is telling you what to do next. Read it
              and follow it rather than retrying the step that produced it.

            Be decisive. Prefer the shortest route to the goal, and stop as soon as the
            goal is met rather than continuing to explore.

            Finishing:
            - The moment the goal is met, stop acting - do not press home, open the app
              switcher, or open any other app, Wristch included, to "wrap up" or signal
              that you are done. The task is reported through your final reply, not
              through where the phone ends up. Leave the screen exactly where the last
              action left it - whichever app that is is where the person will look first.
        """.trimIndent()
        private const val MIME_JPEG = "image/jpeg"
        private const val JPEG_QUALITY = 80
        private const val MAX_UPLOAD_EDGE = 768f
        // Counted in history entries, and a step appends two (the model's reply, then
        // our results) - so this keeps the last two screens, enough to see what changed.
        private const val KEEP_SCREENSHOTS = 4
        private const val OMITTED = "[earlier screenshot omitted]"
        private const val HOME_SETTLE_MS = 700L
        private const val MIME_JSON = "application/json"
        private const val TRIAGE_BUDGET_MS = 4000L

        private val TRIAGE_INSTRUCTION = """
            You answer questions. A separate system drives the user's Android phone.

            If the request is a question you can answer - a fact, a definition, the
            weather, a calculation, anything you can look up - put the complete answer in
            `answer` and set `needs_device` to false. Search the web when the answer
            depends on current information.

            If the request is an instruction to do something on the phone, or asks for
            something that exists only on the phone (its settings, its messages, its
            installed apps), set `needs_device` to true and leave `answer` empty.
        """.trimIndent()

        private val TITLE_INSTRUCTION = """
            You name tasks. You are given what someone asked their phone to do, and you
            reply with a name for it - nothing else.

            A name is read weeks later, next to forty others, by someone who no longer
            remembers asking. So it has to say enough to be told apart from the runs
            either side of it, not merely what kind of task it was.

            Rules:
            - Roughly six to twelve words. Long enough to carry the specifics, short
              enough to read in one glance.
            - Keep the concrete details from the request - who, what, where, which app,
              which amount, which date. They are what makes one run recognisable among
              forty: "Text Mum that I am home by seven", not "Send a message".
            - Name the task, do not narrate it. No "The user wants to", no "Ask the
              phone to" - start with the verb: "Book a table at Ho Lee for Friday".
            - Do not invent detail the request does not contain, and do not guess at what
              the outcome will be. Only what was asked for is known yet.
            - Sentence case. No quotes, no trailing full stop, no emoji.
            - Reply in the language the request was written in.
        """.trimIndent()

        /**
         * What separates a memory from a log line.
         *
         * The failure this guards against is the obvious one: a model asked what it
         * learned will happily report that it tapped Send, that the list took a moment to
         * load, that the button was near the bottom. All true, none of it worth the
         * tokens it costs to re-read on every future run - and the run's own history
         * already has it. So the test is stated as a date, not as an adjective.
         */
        private val MEMORY_INSTRUCTION = """
            You read the transcript of a task someone's phone just carried out for them,
            and you pick out what is still worth knowing next week.

            Keep a fact only if all of these hold:
            - It would still be true in a week. Standing preferences, how a person likes
              to be addressed, which app they actually use for something, a recurring
              place or time, a relationship between two names.
            - It is about the user, the people they deal with, or how their world is
              arranged - not about this run.
            - Someone reading it cold, with no memory of this task, would understand it.
              Write it as a whole sentence with its subject in it: "Mum's clinic
              appointments are on Tuesday mornings", never "it is on Tuesday".

            Always keep, when the user themselves supplied it during the run:
            - How to reach a person: their exact username or handle, phone number, email,
              and which app they are reached in. The user was asked precisely because the
              phone did not know; written down, they are never asked again. Tie the
              identifier to the person and the app in one sentence: "哥哥 on Instagram is
              @chen_wei_2008", "Mr. Lin's email is lin@school.edu.tw".

            Never keep:
            - What happened during the run. Which button was tapped, what was on screen,
              which step failed, how long something took. That is already recorded
              elsewhere, in full.
            - Anything you inferred but did not see. A guess written down as a fact is
              worse than nothing, because later runs will act on it.
            - Anything already obvious from the task itself.
            - Passwords, verification codes, card or account numbers. Never, whatever the
              transcript contains.

            Most runs teach nothing that passes this. Returning an empty list is the
            normal, correct answer, and is always better than filling it.

            Write each entry in the language the task was written in.
        """.trimIndent()

        /** A run can teach a thing or two; past that it is writing down the run itself. */
        private const val MAX_MEMORIES_PER_RUN = 3

        /** Enough tail to see how the run resolved, without re-sending the whole log. */
        private const val MAX_MEMORY_STEPS = 40

        /** Two lines in a history row; past that the row stops being scannable. */
        private const val MAX_TITLE_CHARS = 100

        /** How a turn says it is a question rather than an outcome. See `questionIn`. */
        private val ASK_MARKERS = listOf("NEED:", "NEED：")

        const val ASK_USER_NAME = "ask_user"

        /**
         * The one thing the model can do that is not to the phone.
         *
         * Described in terms of what makes an answer necessary rather than as "ask when
         * unsure": a model is unsure constantly, and a tool sold on uncertainty gets
         * called at every screen. What earns a question is that the phone cannot settle
         * it and that acting on a guess would be expensive to undo.
         */
        private val ASK_USER: FunctionDeclaration = FunctionDeclaration.builder()
            .name(ASK_USER_NAME)
            .description(
                "Ask the person who started this task a question, and wait for their " +
                    "written answer. Use it only when the task cannot go on without " +
                    "something that is not on the phone and cannot be worked out from " +
                    "it: which of several people or accounts is meant, an address, a " +
                    "phone number or handle nothing on screen supplies, which of two " +
                    "readings of a date or a name is right, or which app to use when " +
                    "nothing has said. Look first - scroll the list, open the contact, " +
                    "read the next screen - and ask only when looking has not answered " +
                    "it. Never guess a person, a number, an account or an amount when " +
                    "you could ask instead. Before messaging someone who is not an " +
                    "existing conversation or a saved contact, use this to ask for the " +
                    "exact username, phone number or email that identifies them - a " +
                    "name matched against search results is a stranger until they " +
                    "confirm otherwise.",
            )
            .parameters(
                Schema.builder()
                    .type(Type(Type.Known.OBJECT))
                    .properties(
                        mapOf(
                            "question" to Schema.builder()
                                .type(Type(Type.Known.STRING))
                                .description(
                                    "One question, in one sentence, in the language the " +
                                        "task was written in. Say what you will do with " +
                                        "the answer if that is not obvious.",
                                )
                                .build(),
                            "intent" to Schema.builder()
                                .type(Type(Type.Known.STRING))
                                .description("Why this cannot be settled from the screen.")
                                .build(),
                        ),
                    )
                    .required(listOf("question"))
                    .build(),
            )
            .build()

        private const val REQUIRE_CONFIRMATION = "require_confirmation"
        private const val STOPPED = "Stopped by the user."
        private const val END_OF_PAGE =
            " The screen did not move, so this is the bottom of the page - everything " +
                "it contains has now been shown to you."
        private const val DECLINED = "The user declined this action; do not retry it."
        private const val DEFAULT_MAX_STEPS = 50
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    }
}
