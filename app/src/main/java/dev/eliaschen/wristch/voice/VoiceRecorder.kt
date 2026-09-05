package dev.eliaschen.wristch.voice

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import java.io.File

/**
 * Records one clip of microphone audio to a temp file in the cache dir.
 *
 * One instance per clip: [MediaRecorder] cannot be restarted once stopped, so [start] builds
 * a fresh recorder every time rather than reusing one across clips.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var file: File? = null

    /** True once the clip is actually recording; false if the device refused to start. */
    fun start(): Boolean {
        val target = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        return runCatching {
            MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(target.absolutePath)
                prepare()
                start()
            }
        }.onSuccess {
            recorder = it
            file = target
        }.onFailure { error ->
            Log.w(TAG, "start failed: ${error.message}")
            target.delete()
        }.isSuccess
    }

    /** Stops the clip and hands back the file it was written to, or null if nothing was recording. */
    fun stop(): File? {
        val active = recorder ?: return null
        val clip = file
        recorder = null
        file = null
        return runCatching { active.stop() }
            .onFailure { error -> Log.w(TAG, "stop failed: ${error.message}") }
            .also { active.release() }
            .fold(onSuccess = { clip }, onFailure = { clip?.delete(); null })
    }

    /** Stops (if needed) and discards the clip without returning it. */
    fun cancel() {
        val active = recorder
        recorder = null
        runCatching { active?.stop() }
        active?.release()
        file?.delete()
        file = null
    }

    private companion object {
        const val TAG = "VoiceRecorder"
    }
}
