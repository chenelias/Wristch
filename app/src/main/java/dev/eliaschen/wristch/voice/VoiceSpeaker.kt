package dev.eliaschen.wristch.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.PrebuiltVoiceConfig
import com.google.genai.types.SpeechConfig
import com.google.genai.types.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a sentence aloud.
 *
 * A run moves the phone without anyone necessarily watching the screen it moved, so its
 * outcome is worth the same voice a person would get a phone call answered in - not just a
 * line of text waiting to be read.
 */
class VoiceSpeaker(apiKey: String, private val model: String = DEFAULT_MODEL) {

    private val client = Client.builder().apiKey(apiKey).build()

    private val config: GenerateContentConfig = GenerateContentConfig.builder()
        .responseModalities(MODALITY_AUDIO)
        .speechConfig(
            SpeechConfig.builder().voiceConfig(
                VoiceConfig.builder().prebuiltVoiceConfig(
                    PrebuiltVoiceConfig.builder().voiceName(VOICE_NAME),
                ),
            ),
        )
        .build()

    /**
     * Speaks [text], suspending until playback has actually finished.
     *
     * Suspends rather than fires-and-forgets so a caller that wants to show "speaking..."
     * only for as long as it lasts can just wrap the call, instead of inventing its own
     * clock for a clip whose length it does not know in advance.
     */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        val pcm = synthesize(text) ?: return
        withContext(Dispatchers.IO) { play(pcm) }
    }

    private suspend fun synthesize(text: String): ByteArray? = runCatching {
        withContext(Dispatchers.IO) {
            client.models.generateContent(model, Content.fromParts(Part.fromText(text)), config)
        }.candidates().orElse(emptyList())
            .firstOrNull()
            ?.content()?.orElse(null)
            ?.parts()?.orElse(emptyList())
            ?.firstNotNullOfOrNull { it.inlineData().orElse(null)?.data()?.orElse(null) }
    }.getOrElse { error ->
        Log.w(TAG, "synthesize failed: ${error.message}")
        null
    }

    /**
     * Blocks the calling (IO) thread until the clip has played out.
     *
     * The API hands back raw 16-bit PCM with no header, so this is the one shape
     * [AudioTrack] can play without a decoder in front of it: build the track at the rate
     * the model actually renders at, write the whole clip in one call, and hold the thread
     * open for as long as that much audio takes to leave the speaker.
     */
    private fun play(pcm: ByteArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        runCatching {
            track.write(pcm, 0, pcm.size)
            track.play()
            val millis = pcm.size * 1000L / (SAMPLE_RATE_HZ * BYTES_PER_SAMPLE)
            Thread.sleep(millis)
        }.onFailure { error -> Log.w(TAG, "play failed: ${error.message}") }
        track.release()
    }

    companion object {
        private const val TAG = "VoiceSpeaker"
        private const val MODALITY_AUDIO = "AUDIO"

        /** A level, unhurried voice - the run is reporting back, not performing. */
        private const val VOICE_NAME = "Kore"
        private const val SAMPLE_RATE_HZ = 24000
        private const val BYTES_PER_SAMPLE = 2

        const val DEFAULT_MODEL = "gemini-2.5-flash-preview-tts"
    }
}
