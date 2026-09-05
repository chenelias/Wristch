package dev.eliaschen.wristch.chat

import android.util.Log
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GoogleSearch
import com.google.genai.types.Part
import com.google.genai.types.Tool
import dev.eliaschen.wristch.history.MessageAuthor
import dev.eliaschen.wristch.history.RunRecord
import dev.eliaschen.wristch.history.RunStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Answers questions about a run that has already happened.
 *
 * Deliberately blind to the phone. The run is over, the screens it moved through are gone,
 * and the only honest source left is the record it wrote - so this holds no accessibility
 * service, takes no screenshot, and cannot act. That is also what lets it answer while the
 * service is off, which is the state the phone is in whenever the user is only reading.
 *
 * Web search is the one tool it gets, because half of what people ask after a run is not
 * about the run at all - what a refused payment code means, whether a shop is open, what
 * the thing it just booked actually is.
 */
class TaskChat(apiKey: String, private val model: String = DEFAULT_MODEL) {

    private val client = Client.builder().apiKey(apiKey).build()

    private val config: GenerateContentConfig = GenerateContentConfig.builder()
        .systemInstruction(Content.fromParts(Part.fromText(INSTRUCTION)))
        .tools(listOf(Tool.builder().googleSearch(GoogleSearch.builder().build()).build()))
        .build()

    /**
     * What to say back to [question], given everything [run] recorded.
     *
     * The whole thread is re-sent every time rather than kept as a live chat session: the
     * screen this is asked from can be closed and reopened days apart, and a session held
     * in memory would not survive that. The record already is the conversation.
     */
    suspend fun answer(run: RunRecord, question: String): Result<String> = runCatching {
        val history = buildList {
            add(Content.builder().role("user").parts(Part.fromText(brief(run))).build())
            add(
                Content.builder()
                    .role("model")
                    .parts(Part.fromText(READY))
                    .build(),
            )
            // Everything already said about this run, in order. The question being asked
            // now is dropped if it is already the last line: the screen records what was
            // typed before it asks, so that the person sees their own words go up
            // immediately, and it must not then be sent twice.
            run.messages
                .dropLastWhile { it.author == MessageAuthor.USER && it.text == question.trim() }
                .forEach { message ->
                    val role = if (message.author == MessageAuthor.USER) "user" else "model"
                    add(Content.builder().role(role).parts(Part.fromText(message.text)).build())
                }
            add(Content.builder().role("user").parts(Part.fromText(question)).build())
        }

        withContext(Dispatchers.IO) {
            client.models.generateContent(model, history, config)
        }.text()?.trim()?.takeIf { it.isNotEmpty() } ?: error("the model said nothing")
    }.onFailure { Log.w(TAG, "answer failed: ${it.message}") }

    /**
     * The run, written out for a reader who was not there.
     *
     * Steps are trimmed from the front rather than the back: a long run's last actions are
     * the ones its outcome came from, and the ones a question will be about.
     */
    private fun brief(run: RunRecord): String = buildString {
        append("A phone-automation task has just been carried out on the user's phone. ")
        append("Here is its complete record.\n\n")
        append("What the user asked for: ").append(run.goal).append('\n')
        if (run.title.isNotBlank()) append("Named: ").append(run.title).append('\n')
        append("Started: ").append(STAMP.format(Instant.ofEpochMilli(run.startedAt))).append('\n')
        append("Status: ").append(
            when (run.status) {
                RunStatus.RUNNING -> "still running"
                RunStatus.DONE -> "finished"
                RunStatus.FAILED -> "failed or was stopped"
            },
        ).append('\n')
        if (run.outcome.isNotBlank()) append("How it ended: ").append(run.outcome).append('\n')

        if (run.steps.isEmpty()) {
            append("\nNo actions were recorded.")
            return@buildString
        }
        append("\nWhat it did, step by step:\n")
        run.steps.takeLast(MAX_STEPS).forEach { step ->
            append("- ").append(step.intent ?: step.action)
            append(" [").append(step.action).append(']')
            if (step.result.isNotBlank()) append(" -> ").append(step.result.take(MAX_RESULT_CHARS))
            append('\n')
        }
        if (run.steps.size > MAX_STEPS) {
            append("(the first ").append(run.steps.size - MAX_STEPS)
            append(" steps are not shown)\n")
        }
    }

    private companion object {
        const val TAG = "WristchTaskChat"

        /** Enough of the run to answer from, without paying for a forty-step log each turn. */
        const val MAX_STEPS = 40
        const val MAX_RESULT_CHARS = 300

        val STAMP: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())

        /**
         * The model's own first turn, so the record above reads as something it was handed
         * rather than as the user's opening line - which is what it would otherwise be
         * answering when the real question arrives.
         */
        const val READY = "I have read the record of that task and I am ready for questions about it."

        val INSTRUCTION = """
            You are Wristch, and you have just finished carrying out a task on someone's
            phone for them. Now they want to talk about it.

            You are given the full record of that run: what they asked for, every action
            taken, what the phone answered, and how it ended. Answer from that record.

            - Answer the question actually asked, in a sentence or two. This is a chat on a
              phone screen, not a report.
            - Be straight about what happened, especially when it went wrong. If a step
              failed, say which one and what the phone said. Never claim something was done
              that the record does not show being done.
            - When the answer is not in the record, say so plainly rather than guessing.
              "The record does not show that" is a complete answer.
            - Search the web when the question is about the world rather than about the run
              - an error message's meaning, a place, a price, a term that came up.
            - You cannot touch the phone from here. If they want something else done, say
              what you would do and tell them to send it as a new task from this screen;
              do not pretend to have started it.
            - No preamble, no restating the question, no sign-off.

            Language: reply in the same language as their latest message, whatever language
            the record itself happens to be written in. They wrote to you in English, you
            answer in English. Wristch is built for Taiwan first, so when they write
            Chinese, answer in Taiwanese Mandarin in Traditional characters (繁體中文) -
            never Simplified.
        """.trimIndent()

        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    }
}
