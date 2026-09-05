package dev.eliaschen.wristch.computer

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.eliaschen.wristch.MainActivity
import dev.eliaschen.wristch.TaskRoute
import dev.eliaschen.wristch.context.VibeContext
import dev.eliaschen.wristch.history.MessageAuthor
import dev.eliaschen.wristch.history.RunHistory
import dev.eliaschen.wristch.history.RunRecord
import dev.eliaschen.wristch.memory.MemoryStore
import dev.eliaschen.wristch.settings.SettingsStore
import dev.eliaschen.wristch.vibe.Vibe
import dev.eliaschen.wristch.vibe.VibeConfirmation
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
     * Starts [goal] under [vibe], and returns whether it started at all.
     *
     * The vibe is folded into the prompt here rather than inside the agent: it is a way of
     * working chosen on the screen, and the agent's job is to carry out whatever text it
     * is handed. That includes everything the vibe is allowed to draw on - where the
     * phone is, what is on the calendar, who the names in the goal refer to, and what
     * has been remembered from before - which is the half of a vibe nobody can type.
     * History records the plain goal, because that is the sentence the person typed and
     * the one they will look for later.
     */
    fun start(
        context: Context,
        agent: ComputerUseAgent,
        goal: String,
        vibe: Vibe? = null,
        parent: RunRecord? = null,
    ): Boolean {
        if (_runId.value != null) return false
        val app = context.applicationContext

        Log.i(TAG, "start: vibe=${vibe?.name}, confirmation=${vibe?.confirmation}")

        _goal.value = goal
        _steps.value = emptyList()
        _control.value = RunControl.State.RUNNING
        running = agent.control

        val id = RunHistory.start(goal, parentId = parent?.id, vibeId = vibe?.id)
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
                val preface = listOfNotNull(
                    vibe?.let { VibeContext.preface(app, it, goal) },
                    parent?.let(::carryOver),
                ).joinToString("\n\n")
                val prompt = if (preface.isEmpty()) goal else "$preface\n\nThe task:\n$goal"
                // The one line that settles "did my vibe reach it?" from a logcat, which
                // is otherwise only answerable by watching how the model behaves and
                // reasoning backwards from it.
                Log.i(
                    TAG,
                    "run $id under vibe=${vibe?.name ?: "none"} " +
                        "confirmation=${vibe?.confirmation ?: VibeConfirmation.ALWAYS} " +
                        "preface=${preface.length} chars",
                )

                val outcome = agent.run(
                    prompt,
                    confirmation = vibe?.confirmation ?: VibeConfirmation.ALWAYS,
                ) { step ->
                    _steps.value = _steps.value + step
                    RunHistory.step(id, step)
                }
                _steps.value = _steps.value + outcome
                RunHistory.finish(id, outcome)
                learn(agent, vibe, goal, outcome, _steps.value)
            } catch (cancelled: CancellationException) {
                // The record has to be closed even here, or it stays "running" forever -
                // and NonCancellable is what lets that write happen in a cancelled scope.
                withContext(NonCancellable) {
                    RunHistory.fail(id, "還沒完成就被取消了。")
                }
                throw cancelled
            } catch (error: Exception) {
                // A failed request should read as the last line of the log, not as a crash
                // in the middle of a run that has already moved the real screen.
                val message = "發生錯誤：${error.message ?: error::class.simpleName}"
                _steps.value = _steps.value + message
                RunHistory.fail(id, message)
            } finally {
                naming.cancel()
                mirror.cancel()
                running = null
                _runId.value = null
                // Last, and after the record is closed either way: whatever the app lands
                // on has to be able to read a finished run, not one still marked running.
                if (SettingsStore.returnOnFinish.value) show(app, id)
            }
        }
        return true
    }

    /**
     * Asks the model what this run taught, and keeps it under [vibe].
     *
     * Launched on the session's own scope rather than awaited: the run is over by the
     * time this is called, and holding [runId] open for one more round trip would leave
     * the screen saying "running" while nothing is happening to the phone.
     *
     * Only ever scoped to a vibe. A memory the agent wrote is an inference, and one
     * written with no vibe in charge would be visible to every vibe at once - the school
     * agent reading what the family one concluded. A run started without a vibe therefore
     * teaches nothing, which is the quiet option and the right default until the user has
     * said where a thing belongs.
     */
    private fun learn(
        agent: ComputerUseAgent,
        vibe: Vibe?,
        goal: String,
        outcome: String,
        steps: List<String>,
    ) {
        if (vibe == null) return
        scope.launch {
            agent.remember(goal, outcome, steps).forEach { MemoryStore.record(it, vibe.id) }
        }
    }

    /**
     * The run [parent] left behind, written for the agent that is picking it up.
     *
     * A follow-up is typed with the last run on screen - "try the other one", "now send
     * her the address" - so it reads as a fragment on its own. The previous task, how it
     * ended and what was said about it since are what make it a whole instruction again.
     *
     * The conversation is included because that is where the correction usually is: the
     * person asked what went wrong, was told, and is now answering that.
     */
    private fun carryOver(parent: RunRecord): String = buildString {
        append("This continues a task that was already carried out on this phone.\n")
        append("What was asked for then: ").append(parent.goal).append('\n')
        if (parent.outcome.isNotBlank()) {
            append("How it ended: ").append(parent.outcome).append('\n')
        }
        val said = parent.messages.takeLast(MAX_CARRIED_MESSAGES)
        if (said.isNotEmpty()) {
            append("What was said about it afterwards:\n")
            said.forEach { message ->
                append("- ")
                append(if (message.author == MessageAuthor.USER) "user: " else "you: ")
                append(message.text)
                append('\n')
            }
        }
        append(
            "\nThe new task below was written with all of that in view. Do not repeat what " +
                "already worked; carry on from it.",
        )
    }

    /**
     * Brings Wristch forward on the run that just ended.
     *
     * A run finishes in whatever app it was driving, with the phone in the user's hand and
     * nothing to act on but a card that fades. The task it just did is the one thing they
     * might want to question or carry on, so the app comes back to it rather than making
     * them find it. Both halves are needed: the route for a graph that is already composed
     * and would otherwise sit on whatever screen it was left on, and the intent for a task
     * that is in the background - which, after a run, it always is.
     *
     * Background activity starts are blocked for ordinary apps; Wristch is exempt while
     * its accessibility service is bound, which is the only state a run can happen in.
     */
    private fun show(context: Context, runId: String) {
        TaskRoute.open(runId)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_RUN_ID, runId)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "could not come back to the task: ${it.message}") }
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

    private const val TAG = "WristchSession"

    /** Enough of the conversation to carry the correction, not the whole thread. */
    private const val MAX_CARRIED_MESSAGES = 6
}
