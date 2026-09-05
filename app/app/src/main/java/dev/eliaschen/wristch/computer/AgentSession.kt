package dev.eliaschen.wristch.computer

import android.content.Context
import dev.eliaschen.wristch.context.VibeContext
import dev.eliaschen.wristch.history.RunHistory
import dev.eliaschen.wristch.vibe.Vibe
import dev.eliaschen.wristch.vibe.VibeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The run that is happening now, held outside any screen.
 *
 * A run moves the real phone for minutes at a time, and the person who started it is
 * expected to leave: that is the point of the thing. So it cannot live in the Agent
 * screen's composition scope - walking back to home, or letting the screen go while the
 * agent drives another app, would cancel the very run being watched. Process-wide, like
 * [RunHistory], which it writes to as it goes.
 *
 * Only one run at a time. Two agents typing into the same phone would fight over it, and
 * neither would finish.
 */
object AgentSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _goal = MutableStateFlow("")

    /** What the run in flight was asked for - or the last one, once it is over. */
    val goal: StateFlow<String> = _goal.asStateFlow()

    private val _steps = MutableStateFlow<List<String>>(emptyList())

    /** Progress lines as they arrive, in the agent's own `action (intent) -> result` form. */
    val steps: StateFlow<List<String>> = _steps.asStateFlow()

    private val _runId = MutableStateFlow<String?>(null)

    /** The history record this run is writing into, or null when nothing is running. */
    val runId: StateFlow<String?> = _runId.asStateFlow()

    private val _control = MutableStateFlow(RunControl.State.RUNNING)

    /** Whether the run is going or being held, mirrored from the agent's own control. */
    val control: StateFlow<RunControl.State> = _control.asStateFlow()

    private var job: Job? = null

    private var running: RunControl? = null

    /**
     * The one source a run does not carry.
     *
     * The notebook is the vibe's own memory rather than something read off the phone, and
     * it is deliberately left out of what a run is prefaced with - a note is written and
     * corrected in Wristch, and goes to the model through its own path, not folded into
     * every goal by default.
     */
    private val WITHHELD = setOf(VibeSource.NOTES)

    /**
     * Starts [goal] under [vibe], and returns whether it started at all.
     *
     * The vibe is folded into the prompt here rather than inside the agent: it is a way of
     * working chosen on the screen, and the agent's job is to carry out whatever text it
     * is handed. That includes what the vibe is allowed to read off the phone - where it
     * is, what is on the calendar, who the names in the goal refer to - which is the half
     * of a vibe nobody can type. History records the plain goal, because that is the
     * sentence the person typed and the one they will look for later.
     */
    fun start(
        context: Context,
        agent: ComputerUseAgent,
        goal: String,
        vibe: Vibe? = null,
    ): Boolean {
        if (_runId.value != null) return false
        val app = context.applicationContext

        _goal.value = goal
        _steps.value = emptyList()
        _control.value = RunControl.State.RUNNING
        running = agent.control

        val id = RunHistory.start(goal)
        _runId.value = id

        job = scope.launch {
            // Started before the run so the first pause is reflected however early it
            // comes, and cancelled with it - the control object outlives any single run.
            val mirror = launch { agent.control.state.collect { _control.value = it } }
            // The name is written beside the run rather than before it: it is a label on
            // something already happening, and the phone should be moving while the model
            // thinks of one. Its own coroutine, so a slow or failed naming never holds up
            // the first action.
            val naming = launch {
                agent.title(goal)?.let { RunHistory.retitle(id, it) }
            }
            try {
                // Gathered here rather than before the record is opened: a location fix
                // can take seconds, and the run is already visible as running by then -
                // waiting for the phone to answer before showing anything would look like
                // a button that did nothing.
                val preface = vibe?.let { VibeContext.preface(app, it, goal, WITHHELD) }
                val prompt = if (preface == null) goal else "$preface\n\nThe task:\n$goal"

                val outcome = agent.run(prompt) { step ->
                    _steps.value = _steps.value + step
                    RunHistory.step(id, step)
                }
                _steps.value = _steps.value + outcome
                RunHistory.finish(id, outcome)
            } catch (cancelled: CancellationException) {
                // The record has to be closed even here, or it stays "running" forever -
                // and NonCancellable is what lets that write happen in a cancelled scope.
                withContext(NonCancellable) {
                    RunHistory.fail(id, "Cancelled before it finished.")
                }
                throw cancelled
            } catch (error: Exception) {
                // A failed request should read as the last line of the log, not as a crash
                // in the middle of a run that has already moved the real screen.
                val message = "error: ${error.message ?: error::class.simpleName}"
                _steps.value = _steps.value + message
                RunHistory.fail(id, message)
            } finally {
                naming.cancel()
                mirror.cancel()
                running = null
                _runId.value = null
            }
        }
        return true
    }

    /** Holds the run at its next safe point; the agent decides where that is. */
    fun pause() = running?.pause() ?: Unit

    fun resume() = running?.resume() ?: Unit

    /**
     * Asks the run to end.
     *
     * Asks, rather than cancelling the job: a half-dispatched gesture has to finish, and
     * the agent's loop already checks for this between steps. The record closes when the
     * loop notices, which is how a stopped run keeps the steps it did take.
     */
    fun stop() = running?.stop() ?: Unit
}
