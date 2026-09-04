package dev.elias.assistivetouchpeeker.storage

import android.content.Context
import android.util.Log
import dev.elias.assistivetouchpeeker.detection.GestureHead
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class CustomGesture(
    val id: String,
    val name: String,
    val head: GestureHead,
    val enrolledAtEpochMs: Long,
)

/**
 * Persists user-enrolled custom gestures as JSON in [Context.filesDir] - one trained
 * [GestureHead] per gesture.
 *
 * [gestures] is the single reactive source of truth: every mutation updates it, so any
 * observer (the gesture list, the detection engine) sees the change without needing to
 * remember to refresh. An earlier version handed out an immutable snapshot via a plain
 * `all()` call and relied on callers re-reading it, which is how a newly enrolled gesture
 * could persist to disk yet never appear on screen until the app restarted.
 */
class CustomGestureStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)

    private val _gestures = MutableStateFlow(load())
    val gestures: StateFlow<List<CustomGesture>> = _gestures.asStateFlow()

    fun nextAutoName(): String = "Custom Gesture ${_gestures.value.size + 1}"

    fun enroll(name: String, head: GestureHead): CustomGesture {
        val gesture = CustomGesture(
            id = UUID.randomUUID().toString(),
            name = name,
            head = head,
            enrolledAtEpochMs = System.currentTimeMillis(),
        )
        _gestures.value = _gestures.value + gesture
        persist()
        Log.d(TAG, "enrolled '$name', store now holds ${_gestures.value.size} gesture(s)")
        return gesture
    }

    fun delete(id: String) {
        _gestures.value = _gestures.value.filterNot { it.id == id }
        persist()
    }

    fun rename(id: String, name: String) {
        _gestures.value = _gestures.value.map { if (it.id == id) it.copy(name = name) else it }
        persist()
    }

    private fun load(): List<CustomGesture> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONArray(file.readText())
            List(root.length()) { index ->
                val entry = root.getJSONObject(index)
                CustomGesture(
                    id = entry.getString("id"),
                    name = entry.getString("name"),
                    head = GestureHead.fromJson(entry.getJSONObject("head")),
                    enrolledAtEpochMs = entry.getLong("enrolledAtEpochMs"),
                )
            }
        }.getOrElse { error ->
            // A stale file from an older storage format shouldn't brick the app.
            Log.w(TAG, "could not read $FILE_NAME, starting empty: ${error.message}")
            emptyList()
        }
    }

    private fun persist() {
        val root = JSONArray()
        for (gesture in _gestures.value) {
            val entry = JSONObject()
            entry.put("id", gesture.id)
            entry.put("name", gesture.name)
            entry.put("head", gesture.head.toJson())
            entry.put("enrolledAtEpochMs", gesture.enrolledAtEpochMs)
            root.put(entry)
        }
        file.writeText(root.toString())
    }

    companion object {
        private const val FILE_NAME = "custom_gestures.json"
        private const val TAG = "GestureDetection"
    }
}
