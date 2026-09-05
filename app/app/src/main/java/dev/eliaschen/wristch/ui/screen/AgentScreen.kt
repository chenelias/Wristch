package dev.eliaschen.wristch.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.BuildConfig
import dev.eliaschen.wristch.accessibility.WristchAccessibilityService
import dev.eliaschen.wristch.computer.AgentSession
import dev.eliaschen.wristch.computer.ComputerUseAgent
import dev.eliaschen.wristch.computer.RunControl
import dev.eliaschen.wristch.history.RunStep
import dev.eliaschen.wristch.history.parseStepLine
import dev.eliaschen.wristch.ui.icon.WristchIcons
import dev.eliaschen.wristch.ui.shape.WristchShapes
import dev.eliaschen.wristch.vibe.Vibe
import dev.eliaschen.wristch.vibe.VibeStore

/**
 * Where a run is started and watched.
 *
 * Two halves: the composer at the top - a vibe, a sentence, a send - and the log below it,
 * which is what the composer turns into once a run is going. The run itself belongs to
 * [AgentSession] rather than to this screen, so walking away while the agent works on
 * another app leaves it working; coming back finds the same log still filling in.
 */
@Composable
fun AgentScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialGoal: String? = null,
) {
    val isConnected by WristchAccessibilityService.isConnected.collectAsState()
    val service = WristchAccessibilityService.current()
    // The run reads what the vibe is allowed to pull off this phone, and a content
    // provider needs a Context to be asked.
    val context = LocalContext.current

    val vibes by VibeStore.vibes.collectAsState()
    val defaultId by VibeStore.defaultId.collectAsState()
    val runId by AgentSession.runId.collectAsState()
    val steps by AgentSession.steps.collectAsState()
    val control by AgentSession.control.collectAsState()
    val activeGoal by AgentSession.goal.collectAsState()

    val running = runId != null
    var goal by remember { mutableStateOf(initialGoal.orEmpty()) }

    // Null means "no vibe" - a run with nothing prepended. It starts on the default, and
    // only follows the default while the user has not picked for themselves.
    var chosenVibeId by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(false) }
    LaunchedEffect(defaultId) {
        if (!picked) chosenVibeId = defaultId
    }
    val offered = vibes.filter { it.enabled }
    val vibe = offered.firstOrNull { it.id == chosenVibeId }

    // The agent is rebuilt whenever the service reconnects, since it holds a reference to it.
    val agent = remember(service) {
        service?.takeIf { BuildConfig.GEMINI_API_KEY.isNotBlank() }
            ?.let { ComputerUseAgent(it, BuildConfig.GEMINI_API_KEY) }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(steps.size) {
        if (steps.isNotEmpty()) listState.animateScrollToItem(steps.lastIndex)
    }

    // Arriving with a goal already written means the run was asked for on the screen
    // before this one, so it starts itself rather than waiting for the same send to be
    // pressed twice. Anything that stops it - accessibility off, no key, a run already in
    // flight - leaves the sentence sitting in the composer, which is where it can be sent
    // by hand once the way is clear.
    var sent by remember(initialGoal) { mutableStateOf(false) }

    val blocker = when {
        !isConnected ->
            "Accessibility is off. Wristch cannot see or touch the screen until it is on " +
                "in Android's accessibility settings."

        BuildConfig.GEMINI_API_KEY.isBlank() ->
            "No API key. Add geminiApiKey=... to local.properties and rebuild."

        else -> null
    }

    LaunchedEffect(initialGoal, agent, running, defaultId) {
        val text = initialGoal?.trim().orEmpty()
        if (sent || text.isEmpty() || running) return@LaunchedEffect
        val target = agent ?: return@LaunchedEffect
        // Read through defaultId rather than through `vibe`: on the frame this screen is
        // first composed the default has not been copied into the choice yet, and the
        // resent run would go out under no vibe at all.
        val under = offered.firstOrNull { it.id == (chosenVibeId ?: defaultId) }
        sent = true
        if (AgentSession.start(context, target, text, under)) goal = ""
    }

    // No Scaffold here: the nav graph already owns one, and nesting a second would apply
    // the window insets twice.
    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader(title = "Agent", onBack = onBack)

        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (blocker != null) BlockerCard(blocker)

            if (running) {
                RunningCard(
                    goal = activeGoal,
                    steps = steps.size,
                    control = control,
                    onPause = { AgentSession.pause() },
                    onResume = { AgentSession.resume() },
                    onStop = { AgentSession.stop() },
                )
            } else {
                Composer(
                    goal = goal,
                    onGoal = { goal = it },
                    vibes = offered,
                    chosenVibeId = chosenVibeId,
                    onVibe = {
                        picked = true
                        chosenVibeId = it
                    },
                    canSend = agent != null && goal.isNotBlank(),
                    onSend = {
                        val target = agent ?: return@Composer
                        if (AgentSession.start(context, target, goal.trim(), vibe)) goal = ""
                    },
                )
            }
        }

        StepLog(
            steps = steps,
            listState = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
    }
}

/** A back arrow and a centred title - the same head every screen below home wears. */
@Composable
internal fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** Why nothing can be sent yet, in the words of the thing that has to be fixed. */
@Composable
private fun BlockerCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** The vibe, the sentence and the send button - everything a run needs before it exists. */
@Composable
private fun Composer(
    goal: String,
    onGoal: (String) -> Unit,
    vibes: List<Vibe>,
    chosenVibeId: String?,
    onVibe: (String?) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
) {
    if (vibes.isNotEmpty()) {
        // Horizontal and scrollable rather than wrapped: the row is a single line of the
        // composer, and a list of vibes that reflows would push the text field around as
        // vibes are added.
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "no-vibe") {
                FilterChip(
                    selected = chosenVibeId == null,
                    onClick = { onVibe(null) },
                    label = { Text("Plain") },
                )
            }
            items(vibes, key = { it.id }) { vibe ->
                FilterChip(
                    selected = vibe.id == chosenVibeId,
                    onClick = { onVibe(vibe.id) },
                    label = { Text(vibe.name.ifBlank { "(unnamed)" }) },
                )
            }
        }
    }

    OutlinedTextField(
        value = goal,
        onValueChange = onGoal,
        label = { Text("What should the agent do?") },
        placeholder = { Text("Text Mum that I am running late") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        trailingIcon = {
            // Only worth the space once there is something to clear.
            if (goal.isNotEmpty()) {
                IconButton(onClick = { onGoal("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear the goal")
                }
            }
        },
    )

    Button(
        onClick = onSend,
        enabled = canSend,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text("Send")
    }
}

/**
 * The run in flight: what it is doing, and the two things that can be done to it.
 *
 * Pause and stop are the whole point of showing this - a run is moving the real phone, and
 * the screen it is moving is usually not this one. Stop asks rather than kills, so the
 * steps already taken stay in history.
 */
@Composable
private fun RunningCard(
    goal: String,
    steps: Int,
    control: RunControl.State,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val paused = control == RunControl.State.PAUSED
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The spinner stops when the run does: a progress ring that keeps turning
                // beside the word "Paused" says the opposite of what the run is doing.
                if (paused) {
                    Surface(
                        modifier = Modifier.size(24.dp),
                        shape = WristchShapes.Held,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {}
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = goal.ifBlank { "(no goal)" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            paused -> "Paused after ${stepCount(steps)}"
                            control == RunControl.State.STOPPED -> "Stopping..."
                            else -> "Running - ${stepCount(steps)} so far"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = if (paused) onResume else onPause,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (paused) Icons.Default.PlayArrow else WristchIcons.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(if (paused) "Resume" else "Pause")
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = WristchIcons.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Stop")
                }
            }
        }
    }
}

/**
 * Every step the run has reported, oldest first.
 *
 * The lines arrive as one string each; they are split back into action, reason and result
 * so the reason - the only part written for a person - can be the line that is actually
 * read, with the machinery underneath it.
 */
@Composable
private fun StepLog(
    steps: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) return
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = WindowInsets.safeDrawing
                .add(WindowInsets(left = 24.dp, right = 24.dp, bottom = 24.dp))
                .asPaddingValues(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(steps) { index, line ->
            StepRow(step = parseStepLine(line, at = 0L), index = index + 1)
        }
    }
}

@Composable
private fun StepRow(step: RunStep, index: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "$index",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.intent?.takeIf { it.isNotBlank() } ?: step.action,
                style = MaterialTheme.typography.bodyMedium,
            )
            val detail = listOfNotNull(
                step.action.takeIf { step.intent != null && it.isNotBlank() },
                step.result.takeIf { it.isNotBlank() },
            ).joinToString(" - ")
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun stepCount(steps: Int): String = if (steps == 1) "1 step" else "$steps steps"
