package dev.eliaschen.wristch.notes

import android.content.Context
import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * The shared notebook, newest first.
 *
 * Process-wide for the same reason [dev.eliaschen.wristch.history.RunHistory] is: notes
 * are written from a screen and from a run, and neither owns the other's lifetime. Written
 * on every change, because a note the agent took during a run has no save button to hang
 * a write off.
 */
object NoteStore {

    private const val TAG = "WristchNotes"
    private const val FILE_NAME = "notes.json"

    /** A notebook, not an archive - the oldest fall off so the file stays readable. */
    private const val MAX_NOTES = 200

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val writeLock = Mutex()

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private var file: File? = null

    /** Points the store at app storage and loads what is already there. */
    fun attach(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.filesDir, FILE_NAME)
        file = target
        scope.launch {
            _notes.value = runCatching {
                if (target.exists()) json.decodeFromString<List<Note>>(target.readText())
                else emptyList()
            }.getOrElse { error ->
                // A notebook that cannot be read is not worth taking the app down for.
                Log.w(TAG, "could not read notes: ${error.message}")
                emptyList()
            }
        }
    }

    /** Adds a note and returns its id, for an editor to open on. */
    fun add(
        text: String = "",
        author: NoteAuthor = NoteAuthor.USER,
        vibeId: String? = null,
    ): String {
        val now = System.currentTimeMillis()
        val note = Note(
            id = UUID.randomUUID().toString(),
            text = text,
            author = author,
            vibeId = vibeId,
            createdAt = now,
            updatedAt = now,
        )
        update { listOf(note) + it }
        return note.id
    }

    /**
     * What the agent learned during a run.
     *
     * Repeats are dropped rather than appended: a run that opens the same screen twice
     * should not leave the same sentence in the notebook twice, and the user is the one
     * who would have to clean it up.
     */
    fun record(text: String, vibeId: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (_notes.value.any { it.text.trim() == trimmed && it.vibeId == vibeId }) return
        add(trimmed, NoteAuthor.AGENT, vibeId)
    }

    fun find(id: String): Note? = _notes.value.firstOrNull { it.id == id }

    /**
     * Rewrites a note's text. The author is left as it was - a note the agent took stays
     * the agent's, edited, which is the honest reading of what happened to it.
     */
    fun edit(id: String, text: String) = update { notes ->
        notes.map {
            if (it.id == id) it.copy(text = text, updatedAt = System.currentTimeMillis()) else it
        }
    }

    /** Moves a note to one vibe, or to every vibe when [vibeId] is null. */
    fun scope(id: String, vibeId: String?) = update { notes ->
        notes.map { if (it.id == id) it.copy(vibeId = vibeId) else it }
    }

    fun delete(id: String) = update { notes -> notes.filterNot { it.id == id } }

    fun clear() = update { emptyList() }

    /**
     * The notes a run under [vibeId] should be able to see: that vibe's own, plus the ones
     * that belong to no vibe in particular.
     */
    fun forVibe(vibeId: String?): List<Note> =
        _notes.value.filter { it.text.isNotBlank() && (it.vibeId == null || it.vibeId == vibeId) }

    /** Drops the notes of a vibe that no longer exists; they would be unreachable anyway. */
    fun forgetVibe(vibeId: String) = update { notes -> notes.filterNot { it.vibeId == vibeId } }

    private fun update(change: (List<Note>) -> List<Note>) {
        _notes.value = change(_notes.value).take(MAX_NOTES)
        scope.launch { persist() }
    }

    private suspend fun persist() {
        val target = file ?: return
        val snapshot = _notes.value
        writeLock.withLock {
            runCatching { target.writeText(json.encodeToString(snapshot)) }
                .onFailure { Log.w(TAG, "could not write notes: ${it.message}") }
        }
    }
}
