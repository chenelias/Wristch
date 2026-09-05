package dev.eliaschen.wristch.voice

import android.util.Log
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns speech into text worth sending, using the same Gemini account [ComputerUseAgent]
 * talks to - a second key would only mean a second place to configure.
 */
class VoiceTranscriber(apiKey: String, private val model: String = DEFAULT_MODEL) {

    private val client = Client.builder().apiKey(apiKey).build()

    private val transcribeConfig: GenerateContentConfig = GenerateContentConfig.builder()
        .systemInstruction(Content.fromParts(Part.fromText(TRANSCRIBE_INSTRUCTION)))
        .build()

    private val rewriteConfig: GenerateContentConfig = GenerateContentConfig.builder()
        .systemInstruction(Content.fromParts(Part.fromText(REWRITE_INSTRUCTION)))
        .build()

    /** The words in [clip], or null if the clip could not be read back. */
    suspend fun transcribe(clip: File): String? = runCatching {
        val bytes = clip.readBytes()
        val request = Content.fromParts(Part.fromBytes(bytes, MIME_AUDIO), Part.fromText(TRANSCRIBE_PROMPT))
        withContext(Dispatchers.IO) {
            client.models.generateContent(model, request, transcribeConfig)
        }.text()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrElse { error ->
        Log.w(TAG, "transcribe failed: ${error.message}")
        null
    }

    /**
     * [heard] as an on-device recognizer wrote it down, turned into something worth typing
     * into a field: grammar fixed, filler dropped, meaning kept.
     *
     * Falls back to [heard] itself on any failure - a rewrite that could not be reached is
     * not a reason to lose what was already understood.
     */
    suspend fun rewrite(heard: String): String {
        if (heard.isBlank()) return heard
        return runCatching {
            withContext(Dispatchers.IO) {
                client.models.generateContent(model, heard, rewriteConfig)
            }.text()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrElse { error ->
            Log.w(TAG, "rewrite failed: ${error.message}")
            null
        } ?: heard
    }

    companion object {
        private const val TAG = "VoiceTranscriber"
        private const val MIME_AUDIO = "audio/mp4"
        private const val TRANSCRIBE_PROMPT = "Transcribe this clip."

        private val TRANSCRIBE_INSTRUCTION = """
            You transcribe short voice memos into text.

            Write only what was said, as plain sentences a person could type themselves -
            no timestamps, no speaker labels, no description of tone or background noise,
            and no quotation marks around the whole thing.

            Reply in the language the speaker used. Wristch is built for Taiwan first: if
            that language is Chinese, it is Taiwanese Mandarin, written in Traditional
            characters (繁體中文) - never Simplified.
        """.trimIndent()

        private val REWRITE_INSTRUCTION = """
            You are given a rough, on-device speech-to-text guess of something someone
            just said out loud, meant as an instruction. Clean it up into what they meant
            to type.

            - Fix grammar, punctuation and obviously misheard words.
            - Drop filler - "um", "uh", false starts, a word repeated by accident.
            - Keep the meaning and the tone exactly as spoken. Do not add anything that
              was not said, and do not soften or formalise wording that was not that way.
            - Reply with the cleaned sentence alone - no preamble, no quotation marks.
            - Reply in the language the guess was written in. Wristch is built for Taiwan
              first: if that language is Chinese, write it in Traditional characters
              (繁體中文) - never Simplified, even if the recognizer's own guess slipped
              into Simplified.
        """.trimIndent()

        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    }
}
