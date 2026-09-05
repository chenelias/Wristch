package dev.eliaschen.wristch.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The handful of switches that apply to the whole app rather than to any one vibe.
 *
 * A pair of booleans today does not need [dev.eliaschen.wristch.vibe.VibeStore]'s file and
 * write queue - `SharedPreferences` already persists a flag like this synchronously and
 * process-wide, which is all a setting toggled a few times a year needs.
 */
object SettingsStore {

    private const val FILE_NAME = "settings"
    private const val KEY_SPEAK_OUTCOME = "speak_outcome"
    private const val KEY_RETURN_ON_FINISH = "return_on_finish"

    private var prefs: SharedPreferences? = null

    private val _speakOutcome = MutableStateFlow(true)

    /** Whether a finished run should be read aloud - on by default, off is the exception. */
    val speakOutcome: StateFlow<Boolean> = _speakOutcome.asStateFlow()

    private val _returnOnFinish = MutableStateFlow(true)

    /**
     * Whether the app comes back to a run's own screen when that run finishes.
     *
     * On by default: a run ends inside whatever app it was driving, and the task it just
     * did is the thing most likely to be questioned or carried on. Off is for people who
     * would rather be left where the run put them.
     */
    val returnOnFinish: StateFlow<Boolean> = _returnOnFinish.asStateFlow()

    fun attach(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs = store
        _speakOutcome.value = store.getBoolean(KEY_SPEAK_OUTCOME, true)
        _returnOnFinish.value = store.getBoolean(KEY_RETURN_ON_FINISH, true)
    }

    fun setSpeakOutcome(enabled: Boolean) {
        _speakOutcome.value = enabled
        prefs?.edit { putBoolean(KEY_SPEAK_OUTCOME, enabled) }
    }

    fun setReturnOnFinish(enabled: Boolean) {
        _returnOnFinish.value = enabled
        prefs?.edit { putBoolean(KEY_RETURN_ON_FINISH, enabled) }
    }
}
