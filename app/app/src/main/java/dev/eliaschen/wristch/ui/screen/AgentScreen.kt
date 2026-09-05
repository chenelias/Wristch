package dev.eliaschen.wristch.ui.screen

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.BuildConfig
import dev.eliaschen.wristch.accessibility.WristchAccessibilityService
import dev.eliaschen.wristch.computer.AgentSession
import dev.eliaschen.wristch.computer.ComputerUseAgent
import dev.eliaschen.wristch.context.rememberAudioRecordRequest
import dev.eliaschen.wristch.ui.icon.WristchIcons
import dev.eliaschen.wristch.ui.shape.WristchShapes
import dev.eliaschen.wristch.vibe.VibeStore
import dev.eliaschen.wristch.voice.SpeechListener
import dev.eliaschen.wristch.voice.VoiceTranscriber
import kotlinx.coroutines.launch

/**
 * Where a run is started: a vibe, a sentence - typed or spoken - and nothing else.
 *
 * The screen's only job is getting a run going as fast as possible; the moment one exists
 * it belongs to [AgentSession] and is watched from [RunDetailScreen], not from here. That
 * split is what keeps this screen down to one quick decision and one quick sentence instead
 * of also being where a forty-step log has to be read.
 */
@Composable
fun AgentScreen(
    onBack: () -> Unit,
    onRunStarted: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialGoal: String? = null,
) {
    val isConnected by WristchAccessibilityService.isConnected.collectAsState()
    val service = WristchAccessibilityService.current()
    // The run reads what the vibe is allowed to pull off this phone, and a content
    // provider needs a Context to be asked.
    val context = LocalContext.current

    val vibesLoaded by VibeStore.loaded.collectAsState()
    val vibes by VibeStore.vibes.collectAsState()
    val defaultId by VibeStore.defaultId.collectAsState()
    val runId by AgentSession.runId.collectAsState()

    var goal by remember { mutableStateOf(initialGoal.orEmpty()) }

    // Null means "no vibe" - a run with nothing prepended. It starts on the default, and
    // only follows the default while the user has not picked for themselves.
    var chosenVibeId by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(false) }
    LaunchedEffect(defaultId, vibesLoaded) {
        if (!picked && vibesLoaded) chosenVibeId = defaultId
    }
    val offered = vibes.filter { it.enabled }
    val vibe = offered.firstOrNull { it.id == chosenVibeId }

    // The agent is rebuilt whenever the service reconnects, since it holds a reference to it.
    val agent = remember(service) {
        service?.takeIf { BuildConfig.GEMINI_API_KEY.isNotBlank() }
            ?.let { ComputerUseAgent(it, BuildConfig.GEMINI_API_KEY) }
    }

    // The moment a run exists - however it came to - this screen has nothing left to do
    // with it. That covers all three ways one can start: the composer's own button, a
    // spoken goal sending itself, and arriving here with a goal already queued below.
    LaunchedEffect(runId) {
        runId?.let(onRunStarted)
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val target = agent ?: return
        if (trimmed.isEmpty()) return
        // Resolved here, at the moment of sending, straight from the store. The composed
        // `vibe` above is a plain val captured by this closure, and a send fired from a
        // callback created in an earlier composition would carry that composition's value
        // - null, if the store had not loaded yet - even though the screen has long since
        // caught up. Reading the live state cannot be stale.
        val chosen = (chosenVibeId ?: VibeStore.defaultId.value)?.let { id ->
            VibeStore.vibes.value.firstOrNull { it.id == id && it.enabled }
        }
        if (AgentSession.start(context, target, trimmed, chosen)) goal = ""
    }

    // Arriving with a goal already written means the run was asked for on the screen
    // before this one, so it starts itself rather than waiting for the same send to be
    // pressed twice. Anything that stops it - accessibility off, no key, a run already in
    // flight - leaves the sentence sitting in the composer, which is where it can be sent
    // by hand once the way is clear. The vibe check ensures the vibe's confirmation setting
    // and context are available before starting - without it the run defaults to ALWAYS and
    // has no vibe instructions.
    var sent by remember(initialGoal) { mutableStateOf(false) }
    LaunchedEffect(initialGoal, agent, runId, vibe) {
        val text = initialGoal?.trim().orEmpty()
        if (sent || text.isEmpty() || runId != null || agent == null || vibe == null) return@LaunchedEffect
        sent = true
        send(text)
    }

    val blocker = when {
        !isConnected ->
            "Accessibility is off. Wristch cannot see or touch the screen until it is on " +
                    "in Android's accessibility settings."

        BuildConfig.GEMINI_API_KEY.isBlank() ->
            "No API key. Add geminiApiKey=... to local.properties and rebuild."

        else -> null
    }

    // Voice input: an on-device recognizer supplies a live caption while the person is
    // still talking, and once it settles on a final guess Gemini cleans that guess up -
    // grammar, filler, obviously misheard words - before it goes out. Nothing is parked
    // for review; a spoken goal is sent the moment it has been tidied.
    val transcriber = remember {
        BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }?.let { VoiceTranscriber(it) }
    }
    var voiceState by remember { mutableStateOf(VoiceUiState.Idle) }
    var liveText by remember { mutableStateOf("") }
    var speechAvailable by remember { mutableStateOf(true) }
    var listener by remember { mutableStateOf<SpeechListener?>(null) }
    val scope = rememberCoroutineScope()

    fun endListening() {
        listener?.release()
        listener = null
    }

    DisposableEffect(Unit) {
        onDispose {
            listener?.cancel()
            endListening()
        }
    }

    fun finishWithSpeech(text: String) {
        voiceState = VoiceUiState.Fixing
        scope.launch {
            val rewritten = transcriber?.rewrite(text) ?: text
            voiceState = VoiceUiState.Idle
            send(rewritten)
        }
    }

    fun beginListening() {
        liveText = ""
        val fresh = SpeechListener(
            context = context,
            onPartial = { liveText = it },
            onFinal = { finishWithSpeech(it) },
            onDone = {
                // Only relevant when onFinal never fired - no speech heard, or an error.
                // A successful result has already moved voiceState on to Fixing by now.
                if (voiceState == VoiceUiState.Listening) voiceState = VoiceUiState.Idle
                endListening()
            },
        )
        speechAvailable = fresh.available
        if (!fresh.available) {
            voiceState = VoiceUiState.Listening
            return
        }
        listener = fresh
        voiceState = VoiceUiState.Listening
        fresh.start()
    }

    val requestMic = rememberAudioRecordRequest(onGranted = ::beginListening)

    fun cancelVoice() {
        listener?.cancel()
        endListening()
        voiceState = VoiceUiState.Idle
    }

    fun finishVoice() {
        listener?.stop()
    }

    // No Scaffold here: the nav graph already owns one, and nesting a second would apply
    // the window insets twice.
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()) {
            ScreenHeader(title = "Agent", onBack = onBack)

            if (blocker != null) {
                Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                    BlockerCard(blocker)
                }
            }

            val listState = rememberLazyListState()
            LaunchedEffect(offered, defaultId) {
                // Index 0 is the "Plain" card; every vibe sits one after where it appears
                // in the list, which is why the vibe's own position needs a plus one here.
                val index =
                    if (defaultId == null) 0 else offered.indexOfFirst { it.id == defaultId } + 1
                if (index >= 0) listState.animateScrollToItem(index)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "no-vibe") {
                    VibeCard(
                        name = "Plain",
                        subtitle = "Just the sentence - no vibe",
                        selected = chosenVibeId == null,
                        onClick = {
                            picked = true
                            chosenVibeId = null
                        },
                    )
                }
                items(offered, key = { it.id }) { candidate ->
                    VibeCard(
                        name = candidate.name.ifBlank { "(unnamed)" },
                        subtitle = candidate.subtitle,
                        selected = candidate.id == chosenVibeId,
                        onClick = {
                            picked = true
                            chosenVibeId = candidate.id
                        },
                    )
                }
            }

            ComposerBar(
                goal = goal,
                onGoal = { goal = it },
                onSend = { send(goal) },
                onMic = requestMic,
            )
        }

        if (voiceState != VoiceUiState.Idle) {
            VoiceOverlay(
                state = voiceState,
                liveText = liveText,
                available = speechAvailable,
                onCancel = ::cancelVoice,
                onDone = ::finishVoice,
            )
        }
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

/**
 * One way of working, as a full-width row rather than a chip: big enough to read and hit
 * without lining the choice up against the others first. The check sits on the trailing
 * edge, the same side a person's eye already lands on after reading the name.
 */
@Composable
private fun VibeCard(name: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(22.dp),
                )
            }
        }
    }
}

/**
 * The one row this screen actually needs to work: a big round field to write in, and a
 * round button beside it that is the microphone until there is something to send, and the
 * send button from the moment there is.
 */
@Composable
private fun ComposerBar(
    goal: String,
    onGoal: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
) {
    val typing = goal.isNotBlank()
    Row(
        modifier = Modifier
            // The larger of the two, never the sum: with the keyboard up its inset
            // already covers the navigation bar, and padding for both would leave a
            // band of background the height of the bar between the two.
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .fillMaxWidth()
            .padding(15.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.height(55.dp).weight(1f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (goal.isEmpty()) {
                    Text(
                        text = "Talk to the agent",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = goal,
                    onValueChange = onGoal,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = 1,
                    maxLines = 5,
                )
            }
        }

        Surface(
            onClick = if (typing) onSend else onMic,
            modifier = Modifier.size(55.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (typing) Icons.AutoMirrored.Filled.Send else WristchIcons.Mic,
                    contentDescription = if (typing) "Send" else "Say it",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** What the voice overlay is doing right now. */
private enum class VoiceUiState { Idle, Listening, Fixing }

/**
 * The full-screen listen-and-confirm flow behind the mic button.
 *
 * The caption shown here is the live, unfixed guess - it is allowed to be rough, since it
 * only has to prove the phone is hearing something. What actually gets typed into the goal
 * is whatever [VoiceTranscriber.rewrite] makes of the final guess, which is why there is no
 * "send" step here beyond ending the clip: once fixing starts, the run is already on its
 * way to [RunDetailScreen].
 */
@Composable
private fun VoiceOverlay(
    state: VoiceUiState,
    liveText: String,
    available: Boolean,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                }
                Text(
                    text = when {
                        !available -> "Not available"
                        state == VoiceUiState.Fixing -> "Sending"
                        else -> "Listening"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val transition = rememberInfiniteTransition(label = "voice-pulse")
                val pulse by transition.animateFloat(
                    initialValue = 0.92f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(700, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "voice-pulse-scale",
                )
                Surface(
                    modifier = Modifier
                        .size(140.dp)
                        .scale(if (state == VoiceUiState.Listening && available) pulse else 1f),
                    shape = WristchShapes.Busy,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (state == VoiceUiState.Fixing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else {
                            Icon(
                                imageVector = WristchIcons.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
                Spacer(Modifier.size(28.dp))
                Text(
                    text = when {
                        !available -> "This device has no speech recognizer installed."
                        liveText.isNotBlank() -> liveText
                        state == VoiceUiState.Fixing -> "Cleaning that up"
                        else -> "Say what the agent should do"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onDone,
                    modifier = Modifier.weight(1f),
                    enabled = state == VoiceUiState.Listening && available,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Done")
                }
            }
        }
    }
}
