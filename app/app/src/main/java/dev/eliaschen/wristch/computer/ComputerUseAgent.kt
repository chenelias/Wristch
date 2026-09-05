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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** An encoded screenshot plus the device size its coordinates are expressed in. */
private class Shot(val bytes: ByteArray, val size: ScreenSize)

/**
 * The computer-use loop: show the screen, take back one action, perform it, show the screen
 * again. The model plans; [ActionDispatcher] is the only thing that touches the device.
 *
 * The screenshot rides along as a *function response part*, not as a plain image message -
 * that is what ties each new view of the screen to the action that caused it.
 */
class ComputerUseAgent(
    private val service: WristchAccessibilityService,
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
) {

    private val client = Client.builder().apiKey(apiKey).build()

    private val json = Json { ignoreUnknownKeys = true }

    private val dispatcher = ActionDispatcher(service.executor, service)

    private val overlay = ConfirmationOverlay(service)

    private val statusOverlay = StatusOverlay(service)

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
     * Runs [goal] to completion, or until [maxSteps] actions have been taken. [onStep] is
     * called with a one-line summary of every action, so a caller can show progress without
     * waiting for the whole run.
     */
    suspend fun run(
        goal: String,
        maxSteps: Int = DEFAULT_MAX_STEPS,
        onStep: (String) -> Unit = {},
    ): String {
        statusOverlay.show(goal)
        return try {
            val answer = triage(goal)
            if (answer != null) done(answer) else drive(goal, maxSteps, onStep)
        } finally {
            statusOverlay.hide()
        }
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
        maxSteps: Int,
        onStep: (String) -> Unit,
    ): String {
        // The run is started from inside Wristch, so without this the first thing the
        // model ever sees is our own UI - and it will reason about it, tap our tabs and
        // try to complete the task inside the app that launched it.
        service.executor.pressHome()
        delay(HOME_SETTLE_MS)

        var shot = capture() ?: return "Screenshot failed; is the service connected?"

        val history = mutableListOf(
            Content.builder()
                .role("user")
                .parts(listOf(Part.fromText(goal), Part.fromBytes(shot.bytes, MIME_JPEG)))
                .build(),
        )

        repeat(maxSteps) {
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
                val outcome = if (needsApproval(args) && !approve(args, reason ?: name)) {
                    DECLINED
                } else {
                    dispatcher.execute(call, shot.size)
                }
                val line = if (reason == null) "$name -> $outcome" else "$name ($reason) -> $outcome"
                Log.i(TAG, line)
                onStep(line)
                statusOverlay.update(reason ?: name)

                // Re-shoot after every action: the next decision must see what this one did.
                shot = capture() ?: return "Screenshot failed mid-run after $name."
                results += Part.fromFunctionResponse(
                    name,
                    mapOf("result" to outcome),
                    FunctionResponsePart.fromBytes(shot.bytes, MIME_JPEG),
                )
            }
            history += Content.builder().role("user").parts(results).build()
            trimScreenshots(history)
        }

        return done("Stopped after $maxSteps steps without finishing.")
    }

    /** Parks the outcome on the overlay until it is acknowledged, then returns it. */
    private suspend fun done(outcome: String): String {
        Log.i(TAG, "done: $outcome")
        statusOverlay.finish(outcome)
        return outcome
    }

    private fun needsApproval(args: Map<String, Any?>): Boolean =
        (args["safety_decision"] as? Map<*, *>)?.get("decision") == REQUIRE_CONFIRMATION

    private suspend fun approve(args: Map<String, Any?>, action: String): Boolean {
        val explanation = (args["safety_decision"] as? Map<*, *>)?.get("explanation") as? String
        return overlay.confirm(action, explanation ?: "This step needs your approval.")
    }

    /**
     * The screen goes up as a downscaled JPEG, but [ScreenSize] stays the *device* size.
     * Model coordinates are normalised to 0-999, so they are independent of the pixels we
     * upload - shrinking the image costs upload time and tokens, never accuracy of aim.
     */
    private suspend fun capture(): Shot? {
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
        Log.i(TAG, "screen ${size.width}x${size.height} -> ${bytes.size / 1024}KB")
        return Shot(bytes, size)
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

            Checking your work:
            - Compare the screenshot after each action with the one before it. If nothing
              changed, the action did not do what you expected. Do not repeat it - the same
              action will fail the same way. Try a different element or a different route.
            - A result string describing a failure is telling you what to do next. Read it
              and follow it rather than retrying the step that produced it.

            Be decisive. Prefer the shortest route to the goal, and stop as soon as the
            goal is met rather than continuing to explore.
        """.trimIndent()
        private const val MIME_JPEG = "image/jpeg"
        private const val JPEG_QUALITY = 80
        private const val MAX_UPLOAD_EDGE = 1280f
        private const val KEEP_SCREENSHOTS = 6
        private const val OMITTED = "[earlier screenshot omitted]"
        private const val HOME_SETTLE_MS = 700L
        private const val MIME_JSON = "application/json"

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

        private const val REQUIRE_CONFIRMATION = "require_confirmation"
        private const val DECLINED = "The user declined this action; do not retry it."
        private const val DEFAULT_MAX_STEPS = 50
        const val DEFAULT_MODEL = "gemini-3.8-flash"
    }
}
