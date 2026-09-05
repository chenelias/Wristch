package dev.eliaschen.wristch.history

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
 * Every run the agent has done, newest first.
 *
 * A process-wide object rather than something scoped to a screen: runs are started from
 * the Agent tab and read from the History tab, and they keep going while neither is
 * composed. The file is written on every change so a run that dies with the process is
 * still there - as a run that stopped mid-step, which is exactly what happened.
 */
object RunHistory {

    private const val TAG = "WristchHistory"
    private const val FILE_NAME = "run-history.json"

    /** Old runs are for glancing back at, not an archive; the file stays small on purpose. */
    private const val MAX_RUNS = 100

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val writeLock = Mutex()

    private val _runs = MutableStateFlow<List<RunRecord>>(emptyList())
    val runs: StateFlow<List<RunRecord>> = _runs.asStateFlow()

    private var file: File? = null

    /** Points the store at app storage and loads what is already there. */
    fun attach(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.filesDir, FILE_NAME)
        file = target
        scope.launch {
            val loaded = runCatching {
                if (target.exists()) json.decodeFromString<List<RunRecord>>(target.readText())
                else emptyList()
            }.getOrElse { error ->
                // A history that cannot be read is not worth taking the app down for.
                Log.w(TAG, "could not read history: ${error.message}")
                emptyList()
            }
            // Anything left RUNNING was killed with the process it was running in; it will
            // never report an outcome, so it is closed here instead of hanging forever.
            _runs.value = loaded.map { run ->
                if (run.status != RunStatus.RUNNING) run
                else run.copy(
                    status = RunStatus.FAILED,
                    endedAt = run.steps.lastOrNull()?.at ?: run.startedAt,
                    outcome = "Interrupted - Wristch closed while this run was going.",
                )
            }
            persist()
        }
    }

    /**
     * Opens a record for a run that is starting now and returns its id.
     *
     * [parentId] is the run this one was asked for from, when it was: a follow-up typed on
     * a finished run's own screen is a new run, but it is not a new subject, and the chain
     * is what lets either end of it be read with the other in view.
     */
    fun start(goal: String, parentId: String? = null, vibeId: String? = null): String {
        val record = RunRecord(
            id = UUID.randomUUID().toString(),
            goal = goal,
            startedAt = System.currentTimeMillis(),
            parentId = parentId,
            vibeId = vibeId,
        )
        update { listOf(record) + it }
        return record.id
    }

    /** Appends one progress line, in the `action (intent) -> result` shape the agent emits. */
    fun step(id: String, line: String) {
        val step = parseStepLine(line, System.currentTimeMillis())
        edit(id) { it.copy(steps = it.steps + step) }
    }

    /**
     * Names a run, once the model has written a name for it.
     *
     * Separate from [start] because the title is asked for over the network and the record
     * has to exist the moment the button is pressed - waiting for a name before showing
     * the run would be waiting for a round trip to admit that anything is happening. A
     * blank title is dropped rather than stored, so a failed naming leaves the run listed
     * under its goal instead of under nothing.
     */
    fun retitle(id: String, title: String) {
        val cleaned = title.trim()
        if (cleaned.isEmpty()) return
        edit(id) { it.copy(title = cleaned) }
    }

    /**
     * Appends one line of the conversation held about a run.
     *
     * Written straight through to the file like everything else here: the thread is the
     * only record of a question that was asked and answered, and there is no save button
     * on a chat.
     */
    fun say(id: String, author: MessageAuthor, text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        val message = RunMessage(author, cleaned, System.currentTimeMillis())
        edit(id) { it.copy(messages = it.messages + message) }
    }

    fun finish(id: String, outcome: String) = close(id, RunStatus.DONE, outcome)

    fun fail(id: String, outcome: String) = close(id, RunStatus.FAILED, outcome)

    fun find(id: String): RunRecord? = _runs.value.firstOrNull { it.id == id }

    /** Forgets one run. A run still going is left alone - it has nowhere else to report to. */
    fun delete(id: String) = update { runs ->
        runs.filterNot { it.id == id && it.status != RunStatus.RUNNING }
    }

    /**
     * Forgets every run that has finished.
     *
     * Anything still going survives: it is not history yet, and dropping its record would
     * leave the agent writing steps into a run that no longer exists.
     */
    fun clearFinished() = update { runs -> runs.filter { it.status == RunStatus.RUNNING } }

    fun clear() = update { emptyList() }

    private fun close(id: String, status: RunStatus, outcome: String) = edit(id) {
        it.copy(status = status, outcome = outcome, endedAt = System.currentTimeMillis())
    }

    private fun edit(id: String, change: (RunRecord) -> RunRecord) = update { runs ->
        runs.map { if (it.id == id) change(it) else it }
    }

    private fun update(change: (List<RunRecord>) -> List<RunRecord>) {
        _runs.value = change(_runs.value).take(MAX_RUNS)
        scope.launch { persist() }
    }

    private suspend fun persist() {
        val target = file ?: return
        val snapshot = _runs.value
        writeLock.withLock {
            runCatching { target.writeText(json.encodeToString(snapshot)) }
                .onFailure { Log.w(TAG, "could not write history: ${it.message}") }
        }
    }
}
