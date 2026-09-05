package dev.eliaschen.wristch.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * On-device speech recognition, for a live caption of what is being said.
 *
 * This is the *provisional* half of voice input - a running guess that updates as someone
 * talks, good enough to show but not to send. [VoiceTranscriber.rewrite] is what turns the
 * final guess into something worth typing into a field; this class only ever reports what
 * the recognizer heard.
 */
class SpeechListener(
    context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onDone: () -> Unit,
) {
    /** False on a device with no recognizer installed - some emulators, mainly. */
    val available: Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    private val recognizer: SpeechRecognizer? =
        if (available) SpeechRecognizer.createSpeechRecognizer(context) else null

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Wristch is built for Taiwan first, and the on-device recognizer does not infer
        // that from the phone's own locale the way Gemini's text replies do - left
        // unset, a device whose system language is English hears Mandarin as noise.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, RECOGNITION_LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, RECOGNITION_LANGUAGE)
    }

    fun start() {
        recognizer?.setRecognitionListener(listener)
        recognizer?.startListening(intent)
    }

    /** Asks the recognizer to wrap up now, which is what turns its last partial into onFinal. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Ends the clip without asking for a result - the cancel path, not the send path. */
    fun cancel() {
        recognizer?.setRecognitionListener(null)
        recognizer?.cancel()
    }

    fun release() {
        recognizer?.destroy()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            // A "no match" or "no speech" error is what a silent recording ends in, not a
            // failure worth logging loudly; anything else is worth a note.
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                Log.w(TAG, "recognition error: $error")
            }
            onDone()
        }

        override fun onResults(results: Bundle?) {
            text(results)?.let(onFinal)
            onDone()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            text(partialResults)?.let(onPartial)
        }

        private fun text(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "SpeechListener"

        /** BCP-47 for Traditional Chinese as spoken in Taiwan. */
        const val RECOGNITION_LANGUAGE = "zh-TW"
    }
}
