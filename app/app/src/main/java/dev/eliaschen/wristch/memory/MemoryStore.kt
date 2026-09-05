package dev.eliaschen.wristch.memory

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
 * What the agent and the user both remember, newest first.
 *
 * Process-wide for the same reason [dev.eliaschen.wristch.history.RunHistory] is: memories
 * are written from a screen and from a run, and neither owns the other's lifetime. Written
 * on every change, because a memory the agent took during a run has no save button to hang
 * a write off.
 */
object MemoryStore {

    private const val TAG = "WristchMemory"
    private const val FILE_NAME = "memory.json"

    /** What the store was called when memories were still notes. */
    private const val LEGACY_FILE_NAME = "notes.json"

    /** A memory, not an archive - the oldest fall off so the file stays readable. */
    private const val MAX_MEMORIES = 200

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val writeLock = Mutex()

    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    private var file: File? = null

    /**
     * Points the store at app storage and loads what is already there.
     *
     * A phone that has run an older build has its memories under the old name. The two
     * files hold the same shape, so the old one is read where the new one is missing and
     * written back under the new name by the first change - no separate migration step,
     * and nothing is lost by an install that never writes again.
     */
    fun attach(context: Context) {
        if (file != null) return
        val dir = context.applicationContext.filesDir
        val target = File(dir, FILE_NAME)
        file = target
        scope.launch {
            val source = if (target.exists()) target else File(dir, LEGACY_FILE_NAME)
            _memories.value = runCatching {
                if (source.exists()) json.decodeFromString<List<Memory>>(source.readText())
                else emptyList()
            }.getOrElse { error ->
                // Memory that cannot be read is not worth taking the app down for.
                Log.w(TAG, "could not read memory: ${error.message}")
                emptyList()
            }
        }
    }

    /** Adds a memory and returns its id, for an editor to open on. */
    fun add(
        text: String = "",
        author: MemoryAuthor = MemoryAuthor.USER,
        vibeId: String? = null,
    ): String {
        val now = System.currentTimeMillis()
        val memory = Memory(
            id = UUID.randomUUID().toString(),
            text = text,
            author = author,
            vibeId = vibeId,
            createdAt = now,
            updatedAt = now,
        )
        update { listOf(memory) + it }
        return memory.id
    }

    /**
     * What the agent learned during a run.
     *
     * Repeats are dropped rather than appended: a run that opens the same screen twice
     * should not leave the same sentence in memory twice, and the user is the one who
     * would have to clean it up.
     */
    fun record(text: String, vibeId: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (_memories.value.any { it.text.trim() == trimmed && it.vibeId == vibeId }) return
        add(trimmed, MemoryAuthor.AGENT, vibeId)
    }

    fun find(id: String): Memory? = _memories.value.firstOrNull { it.id == id }

    /**
     * Rewrites a memory's text. The author is left as it was - one the agent took stays
     * the agent's, edited, which is the honest reading of what happened to it.
     */
    fun edit(id: String, text: String) = update { memories ->
        memories.map {
            if (it.id == id) it.copy(text = text, updatedAt = System.currentTimeMillis()) else it
        }
    }

    /** Moves a memory to one vibe, or to every vibe when [vibeId] is null. */
    fun scope(id: String, vibeId: String?) = update { memories ->
        memories.map { if (it.id == id) it.copy(vibeId = vibeId) else it }
    }

    fun delete(id: String) = update { memories -> memories.filterNot { it.id == id } }

    fun clear() = update { emptyList() }

    /**
     * The memories a run under [vibeId] should be able to see: that vibe's own, plus the
     * ones that belong to no vibe in particular.
     */
    fun forVibe(vibeId: String?): List<Memory> =
        _memories.value.filter { it.text.isNotBlank() && (it.vibeId == null || it.vibeId == vibeId) }

    /** Drops the memories of a vibe that no longer exists; they would be unreachable anyway. */
    fun forgetVibe(vibeId: String) =
        update { memories -> memories.filterNot { it.vibeId == vibeId } }

    private fun update(change: (List<Memory>) -> List<Memory>) {
        _memories.value = change(_memories.value).take(MAX_MEMORIES)
        scope.launch { persist() }
    }

    private suspend fun persist() {
        val target = file ?: return
        val snapshot = _memories.value
        writeLock.withLock {
            runCatching { target.writeText(json.encodeToString(snapshot)) }
                .onFailure { Log.w(TAG, "could not write memory: ${it.message}") }
        }
    }
}
