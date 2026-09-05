package dev.eliaschen.wristch.computer

import android.graphics.Bitmap
import android.util.Log
import com.google.genai.Client
import com.google.genai.types.ComputerUse
import com.google.genai.types.Content
import com.google.genai.types.Environment
import com.google.genai.types.FunctionResponse
import com.google.genai.types.FunctionResponsePart
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GoogleSearch
import com.google.genai.types.Part
import com.google.genai.types.Tool
import com.google.genai.types.ToolConfig
import dev.eliaschen.wristch.accessibility.WristchAccessibilityService
import java.io.ByteArrayOutputStream
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

    private val statusOverlay = StatusOverlay(service, control)

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
     * Runs [goal] to completion, or until [maxSteps] actions have been taken. [onStep] is
     * called with a one-line summary of every action, so a caller can show progress without
     * waiting for the whole run.
     */
    suspend fun run(
        goal: String,
        maxSteps: Int = DEFAULT_MAX_STEPS,
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
                drive(goal, start, maxSteps, onStep)
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

            val response = withContext(Dispatchers.IO) {
                client.models.generateContent(model, history, config)
            }

            val reply = response.candidates().orElse(emptyList())
                .firstOrNull()?.content()?.orElse(null)
                ?: return "Model returned no content."
            history += reply

            val calls = response.functionCalls()
            if (calls.isNullOrEmpty()) return done(response.text() ?: "Finished.")

            val results = mutableListOf<Part>()
            for (call in calls) {
                val name = call.name().orElse("")
                val args = call.args().orElse(emptyMap())
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
                val outcome = if (needsApproval(args) && !approve(args, reason ?: name)) {
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
     * Whether the model wants a human to decide this step.
     *
     * Compared as text rather than by identity: the value's runtime type is the SDK's
     * business, and a decision that silently reads as "no approval needed" because it
     * arrived as a node instead of a String would disable the gate without a trace.
     */
    private fun needsApproval(args: Map<String, Any?>): Boolean =
        safetyField(args, "decision") == REQUIRE_CONFIRMATION

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

            Typing:
            - The `type` action writes into whichever field currently holds focus.
              Always `click` the text field first, then `type`.
            - Key events cannot be injected. There is no way to press individual keys,
              key combinations, or Enter on its own. Use `type` with `press_enter` instead.
            - Never enter text by tapping keys on the on-screen keyboard - not letters,
              not digits, not symbols. One `type` call writes the whole value at once.
              Tapping keys one at a time is how a run exhausts its steps without
              finishing.

            Navigation:
            - Use `open_app` to launch an app by name; it is far more reliable than
              navigating the home screen and app drawer.
            - `list_apps` returns every installed app, including ones with similar names.
              Prefer the platform app for a task - Clock, Contacts, Settings, Messages -
              over a third-party app whose name merely contains the same word.
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

        /** Two lines in a history row; past that the row stops being scannable. */
        private const val MAX_TITLE_CHARS = 100

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
