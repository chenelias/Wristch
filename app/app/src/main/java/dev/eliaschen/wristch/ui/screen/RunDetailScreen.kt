package dev.eliaschen.wristch.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.BuildConfig
import dev.eliaschen.wristch.accessibility.WristchAccessibilityService
import dev.eliaschen.wristch.chat.TaskChat
import dev.eliaschen.wristch.computer.AgentSession
import dev.eliaschen.wristch.computer.ComputerUseAgent
import dev.eliaschen.wristch.context.rememberAudioRecordRequest
import dev.eliaschen.wristch.history.MessageAuthor
import dev.eliaschen.wristch.history.RunHistory
import dev.eliaschen.wristch.history.RunMessage
import dev.eliaschen.wristch.history.RunRecord
import dev.eliaschen.wristch.history.RunStatus
import dev.eliaschen.wristch.history.RunStep
import dev.eliaschen.wristch.ui.icon.WristchIcons
import dev.eliaschen.wristch.vibe.VibeStore
import dev.eliaschen.wristch.ui.shape.WristchShapes
import dev.eliaschen.wristch.voice.VoiceRecorder
import dev.eliaschen.wristch.voice.VoiceTranscriber
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")
private val STEP_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * One task: what was asked, what the phone did about it, and everything said since.
 *
 * This is where a run ends up. The agent spends the whole run inside other apps, and when
 * it finishes the app comes back here rather than to a list - see
 * [dev.eliaschen.wristch.TaskRoute] - because the task that just happened is the one thing
 * a person has anything left to say about. So the screen is not only a log: the same
 * sentence box can ask about the run, which is answered from its record without touching
 * the phone, or send it on as the next task, which carries this one's context with it.
 *
 * Read from the store by id rather than passed in whole, so a run that is still going
 * keeps filling this screen in as its steps arrive.
 */
@Composable
fun RunDetailScreen(
    runId: String,
    onBack: () -> Unit,
    onRunAgain: (String) -> Unit,
    onContinue: (String) -> Unit,
    onOpenRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val runs by RunHistory.runs.collectAsState()
    val run = runs.firstOrNull { it.id == runId }
    val vibes by VibeStore.vibes.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val service = WristchAccessibilityService.current()
    val agent = remember(service) {
        service?.takeIf { BuildConfig.GEMINI_API_KEY.isNotBlank() }
            ?.let { ComputerUseAgent(it, BuildConfig.GEMINI_API_KEY) }
    }
    // Asking about a run needs no accessibility service - the run is over, and the record
    // is the only thing being read. So the conversation still works with the service off,
    // which is the state the phone is in whenever someone is only reading.
    val chat = remember {
        BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }?.let { TaskChat(it) }
    }

    var asking by remember(runId) { mutableStateOf(false) }
    var trouble by remember(runId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Set only by this screen's own follow-up - a run started elsewhere while this one
    // happens to be on screen (unlikely, but the session is process-wide) must not hijack
    // navigation the way any change in AgentSession.runId otherwise would.
    var followUpStarted by remember(runId) { mutableStateOf(false) }
    val sessionRunId by AgentSession.runId.collectAsState()
    val sessionBusy = sessionRunId != null

    // A follow-up sent from here becomes AgentSession's new run; the moment it exists,
    // this screen hands off to that run's own page - the same handoff the Agent screen
    // makes when it starts one.
    LaunchedEffect(sessionRunId) {
        val id = sessionRunId
        if (followUpStarted && id != null && id != runId) onContinue(id)
    }

    if (run == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("This run is no longer in the history.")
            BackToHistory(onBack)
        }
        return
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this run?",
            body = "\"${run.label.ifBlank { "(no goal)" }}\" and its " +
                "${run.steps.size} recorded actions will be removed. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                // Back first: the screen reads the run out of the store by id, and the
                // moment it is gone this composition has nothing left to show.
                onBack()
                RunHistory.delete(runId)
            },
            onDismiss = { confirmDelete = false },
        )
    }

    /** Puts a question to the model and writes both halves of it into the record. */
    fun ask(text: String) {
        val target = chat ?: return
        RunHistory.say(runId, MessageAuthor.USER, text)
        trouble = null
        asking = true
        scope.launch {
            // Re-read rather than closing over the composed snapshot: the question was
            // just written into the record, and a run still going has moved on since.
            val current = RunHistory.find(runId)
            val answer = if (current == null) {
                Result.failure(IllegalStateException("this run is gone"))
            } else {
                target.answer(current, text)
            }
            asking = false
            answer
                .onSuccess { RunHistory.say(runId, MessageAuthor.AGENT, it) }
                .onFailure { trouble = "Could not answer that. Check the connection and try again." }
        }
    }

    /**
     * Sends [text] as the next task, with this one behind it.
     *
     * A new run rather than a resumption: the phone has moved on, whatever was on screen
     * is gone, and the agent starts every run by going home. What carries over is the
     * context - this run's goal, its outcome and what has been said about it since - which
     * is what turns "try the other one" back into an instruction.
     */
    fun carryOn(text: String) {
        val target = agent ?: return
        val parent = RunHistory.find(runId) ?: return
        // The same vibe as the run being carried on, not none: a vibe carries standing
        // rules about who the people are and which app to reach them in, and a follow-up
        // that dropped it would be answered through some other app for no visible reason.
        val vibe = parent.vibeId?.let { id -> vibes.firstOrNull { it.id == id } }
        followUpStarted = true
        if (!AgentSession.start(context, target, text, vibe = vibe, parent = parent)) {
            followUpStarted = false
            trouble = "Another run is already going. Wait for it to finish."
        }
    }

    // A finished run can be asked for again; one still going cannot, because there is
    // only ever one run at a time and it is this one.
    val finished = run.status != RunStatus.RUNNING
    val canRepeat = finished && run.goal.isNotBlank()
    val listState = rememberLazyListState()

    // New words - the question going up, the answer coming back - are the reason to be
    // looking at the bottom of this screen, so the screen goes there.
    LaunchedEffect(run.messages.size, asking) {
        if (run.messages.isNotEmpty()) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackToHistory(onBack)
            Spacer(Modifier.weight(1f))
            if (canRepeat) {
                IconButton(onClick = { onRunAgain(run.goal) }) {
                    Icon(
                        imageVector = WristchIcons.Sparkle,
                        contentDescription = "Run this task again",
                    )
                }
            }
            // A run still going has nowhere else to report to, so it cannot be deleted
            // out from under itself.
            if (finished) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete this run",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 12.dp),
            // No arrangement spacing: the steps have to touch, or the rail running down the
            // timeline would break into pieces at every gap. Each item carries its own.
        ) {
            item {
                Box(Modifier.padding(bottom = 16.dp)) {
                    Header(run = run, onOpenParent = onOpenRun)
                }
            }
            item {
                Text(
                    text = if (run.steps.isEmpty()) "No actions recorded" else "Actions",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
            }
            itemsIndexed(run.steps) { index, step ->
                TimelineStep(
                    step = step,
                    isFirst = index == 0,
                    isLast = index == run.steps.lastIndex,
                )
            }
            if (run.messages.isNotEmpty()) {
                item {
                    Text(
                        text = "About this task",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                }
                items(run.messages) { message -> MessageBubble(message) }
            }
            if (asking) {
                item { Thinking() }
            }
            trouble?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }

        TaskComposer(
            canAsk = chat != null && !asking,
            canRun = agent != null && !sessionBusy && finished,
            running = !finished,
            onAsk = ::ask,
            onRun = ::carryOn,
        )
    }
}

/**
 * The one row this screen ends in: a sentence, and the two things that can be done with it.
 *
 * Both are the same box on purpose. After a run, "why did it not send?" and "send it to
 * her other number instead" arrive in the same breath and in the same words, and making
 * someone choose which kind of thing they are about to say before they say it is the wrong
 * order. So the sentence is typed first, and the two buttons are the choice: the arrow
 * asks about the task, the sparkle sends it on as the next one.
 *
 * With nothing typed, the arrow becomes the microphone - a spoken question is still the
 * common case on a phone that was just being driven for you.
 */
@Composable
private fun TaskComposer(
    canAsk: Boolean,
    canRun: Boolean,
    running: Boolean,
    onAsk: (String) -> Unit,
    onRun: (String) -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val recorder = remember { VoiceRecorder(context) }
    val transcriber = remember {
        BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }?.let { VoiceTranscriber(it) }
    }
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }

    val requestMic = rememberAudioRecordRequest(onGranted = { recording = recorder.start() })

    // A spoken line lands in the box rather than sending itself: from here it could be
    // either a question or the next task, and only the person knows which.
    fun finishRecording() {
        val clip = recorder.stop()
        recording = false
        val target = transcriber
        if (clip == null || target == null) return
        transcribing = true
        scope.launch {
            val spoken = target.transcribe(clip)
            clip.delete()
            transcribing = false
            if (spoken != null) text = if (text.isBlank()) spoken else "${text.trim()} $spoken"
        }
    }

    val typed = text.isNotBlank()
    val busy = recording || transcribing

    Row(
        modifier = Modifier
            // The larger of the two rather than both: see the same row on the Agent
            // screen. Padding here rather than on the whole screen keeps the timeline
            // full height, so the keyboard slides over the log instead of squashing it.
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = when {
                            running -> "Running - ask when it is done"
                            recording -> "Listening..."
                            transcribing -> "Writing it down..."
                            else -> "Ask about this task, or say what is next"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (recording || transcribing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = 1,
                    maxLines = 4,
                )
            }
        }

        // Ask, or - with nothing written yet - listen.
        ComposerButton(
            icon = when {
                transcribing -> null
                typed -> Icons.AutoMirrored.Filled.Send
                recording -> WristchIcons.Stop
                else -> WristchIcons.Mic
            },
            label = when {
                typed -> "Ask about this task"
                recording -> "Stop and write it down"
                else -> "Say it"
            },
            enabled = if (typed) canAsk && !busy else !transcribing,
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = {
                when {
                    typed -> {
                        onAsk(text.trim())
                        text = ""
                    }
                    recording -> finishRecording()
                    else -> requestMic()
                }
            },
        )

        // Do it: the sentence becomes the next task, with this one behind it.
        ComposerButton(
            icon = WristchIcons.Sparkle,
            label = "Run this as the next task",
            enabled = typed && canRun && !busy,
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            onClick = {
                onRun(text.trim())
                text = ""
            },
        )
    }
}

/** One round button in the composer; a null [icon] means it is waiting on something. */
@Composable
private fun ComposerButton(
    icon: ImageVector?,
    label: String,
    enabled: Boolean,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(50.dp),
        shape = CircleShape,
        color = if (enabled) container else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon == null) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(20.dp),
                    tint = if (enabled) content else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One turn of the conversation: the user's on the right, the agent's on the left. */
@Composable
private fun MessageBubble(message: RunMessage) {
    val mine = message.author == MessageAuthor.USER
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (mine) 18.dp else 4.dp,
                bottomEnd = if (mine) 4.dp else 18.dp,
            ),
            color = if (mine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (mine) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** The answer being written, in the place the answer will appear. */
@Composable
private fun Thinking() {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            text = "Reading the run...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The way back to the list. The arrow carries the meaning and the label repeats it in
 * words, so the icon is not described twice to a screen reader.
 */
@Composable
private fun BackToHistory(onBack: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = "Back to history",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Header(run: RunRecord, onOpenParent: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(run.label.ifBlank { "(no goal)" }, style = MaterialTheme.typography.headlineSmall)

        // The sentence that was typed, kept under the name written for it: the title is
        // what the run is recognised by, and this is what running it again will send.
        if (run.title.isNotBlank() && run.goal.isNotBlank()) {
            Text(
                text = run.goal,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val statusText = when (run.status) {
            RunStatus.RUNNING -> "Running"
            RunStatus.DONE -> "Done"
            RunStatus.FAILED -> "Failed"
        }
        val duration = run.durationMs?.let { " - took ${formatDuration(it)}" } ?: ""
        Text(
            text = "$statusText - ${STAMP_FORMAT.format(atZone(run.startedAt))}$duration",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Where this task came from, when it was carried on from another. A link rather
        // than a copy of it: the run before this one is a whole page of its own.
        run.parentId?.let { parent ->
            Surface(
                onClick = { onOpenParent(parent) },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Carried on from an earlier task",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (run.outcome.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (run.status == RunStatus.FAILED) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Outcome", style = MaterialTheme.typography.labelMedium)
                    Text(run.outcome, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * One step, as a stop on a line rather than a box of its own.
 *
 * The rail is drawn per row and runs the full height of it, so consecutive rows join into
 * one continuous line - which is why the list above sets no arrangement spacing. The first
 * and last rows stop their segment at the node, so the line begins and ends at a step
 * instead of trailing off into the padding.
 */
@Composable
private fun TimelineStep(step: RunStep, isFirst: Boolean, isLast: Boolean) {
    val rail = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Intrinsic height is what lets the gutter match the text beside it: without
            // it, fillMaxHeight in a Row that sizes itself to its content measures zero.
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(GUTTER)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2f
                    val node = NODE_CENTER.toPx()
                    drawLine(
                        color = rail,
                        start = Offset(x, if (isFirst) node else 0f),
                        end = Offset(x, if (isLast) node else size.height),
                        strokeWidth = RAIL_WIDTH.toPx(),
                        cap = StrokeCap.Round,
                    )
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            val failed = step.failed
            Surface(
                modifier = Modifier
                    .padding(top = NODE_TOP)
                    .size(NODE_SIZE),
                // A step the device pushed back on gets the alert shape as well as the
                // alert colour, the same pairing the history list uses.
                shape = if (failed) WristchShapes.Alert else WristchShapes.Settled,
                color = if (failed) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = iconFor(step),
                        contentDescription = null,
                        modifier = Modifier.size(NODE_ICON),
                        tint = if (failed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // The model's own reason leads, because it is the part written for a
                // person; the tool name is the supporting detail, not the headline.
                Text(
                    text = step.intent ?: step.action,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (step.at > 0L) {
                    Text(
                        text = STEP_TIME_FORMAT.format(atZone(step.at)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (step.intent != null) {
                Text(
                    text = step.action,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (step.result.isNotBlank()) {
                Text(
                    text = step.result,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The glyph for a step, chosen from the tool the model called.
 *
 * The list is deliberately about what the *device* did rather than what the tool was
 * named: several names map to one gesture, and the names change between model versions
 * (see `ActionDispatcher`, which accepts each set of aliases). Anything unrecognised
 * falls back rather than going blank.
 */
private fun iconFor(step: RunStep): ImageVector = when {
    // A failure outranks the action: what matters at a glance is that this step did not
    // land, not which gesture it was.
    step.failed -> Icons.Default.Warning
    else -> when (step.action) {
        "click", "click_at", "left_click", "double_click" -> WristchIcons.Cursor
        "type", "type_text", "type_text_at" -> Icons.Default.Edit
        "scroll", "scroll_at", "scroll_document", "drag", "drag_and_drop" -> WristchIcons.Swipe
        "wait", "wait_5_seconds" -> WristchIcons.Hourglass
        "go_back", "navigate_back" -> Icons.AutoMirrored.Filled.ArrowBack
        "go_home", "home" -> Icons.Default.Home
        "open_app" -> Icons.AutoMirrored.Filled.ExitToApp
        "list_apps" -> Icons.AutoMirrored.Filled.List
        "take_screenshot", "screenshot" -> Icons.Default.Search
        // The two steps that are not the phone at all, but the person: something they
        // added while it worked, and something it stopped to ask them.
        "note" -> Icons.Default.Add
        "ask" -> Icons.Default.Person
        else -> Icons.Default.Info
    }
}

/**
 * Whether the device pushed back on this step.
 *
 * Read out of the result text, because that is all there is: the result is whatever
 * sentence the dispatcher handed the model, and it was written to be read rather than
 * matched on. Matching is therefore best-effort - it decides an icon, nothing more.
 */
private val RunStep.failed: Boolean
    get() = FAILURE_MARKERS.any { result.contains(it, ignoreCase = true) }

private val FAILURE_MARKERS = listOf(
    "Failed",
    "declined",
    "Unsupported",
    "Missing",
    "was not accepted",
    "No launchable app",
    "refused",
    "focused no input field",
)

private val GUTTER = 36.dp
private val NODE_SIZE = 26.dp
private val NODE_TOP = 1.dp
private val NODE_CENTER = NODE_TOP + NODE_SIZE / 2
private val NODE_ICON = 14.dp
private val RAIL_WIDTH = 2.dp
