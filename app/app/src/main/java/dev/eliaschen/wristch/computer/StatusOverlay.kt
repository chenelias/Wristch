package dev.eliaschen.wristch.computer

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.settings.SettingsStore
import dev.eliaschen.wristch.ui.icon.WristchIcons
import dev.eliaschen.wristch.ui.shape.WristchShapes
import dev.eliaschen.wristch.ui.theme.WristchTheme
import dev.eliaschen.wristch.voice.VoiceSpeaker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the status bar is currently saying, and whether the agent is still going. */
private data class Status(
    val text: String,
    val busy: Boolean,
)

/**
 * A slim always-on-top strip that says what the agent is doing right now, and carries the
 * controls for holding or ending the run.
 *
 * The strip takes touches, which puts it in direct competition with the agent: a tap it
 * catches is a tap the app underneath never sees. [hiddenDuring] is what keeps the two
 * apart - the window goes away, touches and all, for as long as the agent is acting.
 */
class StatusOverlay(
    private val service: AccessibilityService,
    private val control: RunControl,
    apiKey: String,
    private val onNote: (String) -> Unit = {},
) {

    private val windowManager = service.getSystemService(WindowManager::class.java)

    private val status: MutableState<Status> = mutableStateOf(Status("", true))

    /** Whether the strip has opened out into the box for adding something to the run. */
    private val composing: MutableState<Boolean> = mutableStateOf(false)

    /**
     * The agent's own question, when the box was opened by the run rather than by the
     * person. Null means they opened it themselves and are volunteering something.
     */
    private val question: MutableState<String?> = mutableStateOf(null)

    /** Where an answer to [question] is handed back to the waiting run. */
    private var pending: CompletableDeferred<String?>? = null

    /**
     * Whether the hold on the run is this overlay's doing.
     *
     * A run that was already being held by hand must not be let go of just because a note
     * was typed and sent: the person paused it for their own reason, and that reason
     * outlives the note.
     */
    private var pausedForNote = false

    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    private val speaker = apiKey.takeIf { it.isNotBlank() }?.let { VoiceSpeaker(it) }

    // A run's own coroutine waits on `finish` only long enough for the outcome card to be
    // acknowledged; the voice line is launched here instead so it keeps playing regardless
    // of how quickly that OK gets tapped, or whether it gets tapped before the clip ends.
    private val speechScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    suspend fun show(text: String) = withContext(Dispatchers.Main) {
        status.value = Status(text, busy = true)
        if (view != null) return@withContext

        val newOwner = OverlayOwner()
        val newView = ComposeView(service).apply {
            setTreeOwners(newOwner)
            setContent {
                WristchTheme {
                    StatusBar(
                        status = status.value,
                        control = control,
                        composing = composing.value,
                        question = question.value,
                        onCompose = ::openNote,
                        onCancel = ::closeNote,
                        onSend = ::sendNote,
                    )
                }
            }
        }
        newOwner.start()
        windowManager.addView(newView, activeParams())
        owner = newOwner
        view = newView
    }

    suspend fun update(text: String) = withContext(Dispatchers.Main) {
        status.value = Status(text, busy = true)
    }

    /**
     * The outcome is the one thing worth reading, so it stops being a strip at the top and
     * becomes a card in the middle for a few seconds before it goes away on its own.
     *
     * Not a tap-to-dismiss button: interacting with anything in this window - a window
     * that belongs to Wristch's own process, even though it is drawn over whatever app the
     * agent was just driving - appears to be enough for the platform to raise Wristch's own
     * task to the foreground on some devices. A card nobody has to touch never gives that
     * a chance to happen, so the phone stays on whatever app the run actually left it in.
     */
    suspend fun finish(text: String) {
        val current = view ?: return
        if (speaker != null && SettingsStore.speakOutcome.value) {
            speechScope.launch { speaker.speak(text) }
        }
        withContext(Dispatchers.Main) {
            // A run cannot end while a note is being typed, since typing holds it - but a
            // stop pressed from underneath the box can, and the outcome card must not
            // inherit a focusable, full-screen window.
            composing.value = false
            pausedForNote = false
            pending?.complete(null)
            pending = null
            question.value = null
            status.value = Status(text, busy = false)
            runCatching {
                windowManager.updateViewLayout(current, layoutParams(centered = true, touchable = false))
            }
        }
        delay(OUTCOME_DISPLAY_MS)
        hide()
    }

    /**
     * Runs uncancellable: this is what callers put in a `finally`, and a cancelled scope
     * would otherwise skip it and leave the window on top of everything forever.
     */
    suspend fun hide() = withContext(NonCancellable + Dispatchers.Main) {
        view?.let { runCatching { windowManager.removeViewImmediate(it) } }
        owner?.destroy()
        view = null
        owner = null
    }

    /**
     * Takes the strip out of the agent's way for the duration of [block] - invisible, and
     * transparent to touch.
     *
     * Both halves matter, for different reasons. `takeScreenshot` captures the whole
     * display, overlays included: leave this visible and every screenshot the model sees
     * has a floating box in it that belongs to no app, which it will reason about and may
     * try to tap. And a touchable window keeps catching injected gestures even while it is
     * invisible, because touchability is a property of the window rather than of the view
     * inside it - so the flag has to come off too.
     */
    suspend fun <T> hiddenDuring(block: suspend () -> T): T {
        val current = view ?: return block()
        withContext(Dispatchers.Main) {
            current.visibility = View.INVISIBLE
            runCatching {
                windowManager.updateViewLayout(current, layoutParams(touchable = false))
            }
        }
        try {
            // The window has to actually be composited away before the capture; a couple
            // of frames is cheap next to the seconds each model round trip costs.
            delay(FRAME_SETTLE_MS)
            return block()
        } finally {
            // Uncancellable, or a cancelled run leaves the strip invisible on screen for
            // as long as the window lives.
            withContext(NonCancellable + Dispatchers.Main) {
                current.visibility = View.VISIBLE
                runCatching { windowManager.updateViewLayout(current, activeParams()) }
            }
        }
    }

    /**
     * Opens the box for adding to a run that is already going, and holds the run to do it.
     *
     * The hold is not a nicety. Typing needs the keyboard, the keyboard needs this window
     * to take focus, and an agent that is mid-`type` would have the field pulled out from
     * under it at exactly that moment. Holding first means the phone is standing still by
     * the time the box appears, and the note is read by the model on the step after the
     * run is let go of again.
     */
    private fun openNote() {
        if (control.state.value == RunControl.State.RUNNING) {
            pausedForNote = true
            control.pause()
        }
        question.value = null
        setComposing(true)
    }

    /**
     * Puts the agent's own question on screen and suspends until it is answered.
     *
     * Null when the person closed the box without answering, which the run reads as "I am
     * not telling you" and ends on - carrying on without the one thing it said it needed
     * would only produce a confident wrong answer.
     *
     * No hold is taken here, unlike [openNote]: the run is already standing still, waiting
     * on this call to return.
     */
    suspend fun ask(text: String): String? {
        if (view == null) return null
        val waiter = CompletableDeferred<String?>()
        withContext(Dispatchers.Main) {
            pending?.complete(null)
            pending = waiter
            question.value = text
            setComposing(true)
        }
        return try {
            waiter.await()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                if (pending === waiter) pending = null
                question.value = null
                setComposing(false)
            }
        }
    }

    /**
     * Closes the box, whichever way it was opened.
     *
     * An unanswered question is completed with null rather than left hanging, or the run
     * waits on a window that is no longer there.
     */
    private fun closeNote() {
        val waiting = pending
        if (waiting != null) {
            // ask() puts the window back itself once it wakes; doing it here as well would
            // race with that.
            waiting.complete(null)
            return
        }
        setComposing(false)
        if (pausedForNote) {
            pausedForNote = false
            control.resume()
        }
    }

    private fun sendNote(text: String) {
        val cleaned = text.trim()
        val waiting = pending
        if (waiting != null) {
            waiting.complete(cleaned.takeIf { it.isNotEmpty() })
            return
        }
        if (cleaned.isNotEmpty()) onNote(cleaned)
        closeNote()
    }

    private fun setComposing(open: Boolean) {
        composing.value = open
        val current = view ?: return
        runCatching { windowManager.updateViewLayout(current, activeParams()) }
    }

    /**
     * The window as it should be right now, which depends only on whether the box is open.
     *
     * Everything that puts the window back - the end of a hidden step, the box closing -
     * goes through this rather than rebuilding the flags at each call site, because the two
     * shapes differ in three ways at once and a restore that guessed would silently take
     * the keyboard away.
     */
    private fun activeParams() = if (composing.value) {
        layoutParams(centered = true, focusable = true, fullScreen = true)
    } else {
        layoutParams()
    }

    /**
     * [focusable] is the exception, not the rule: a focusable overlay takes input focus and
     * the keyboard from whatever is underneath, which is ruinous while the agent is typing
     * into someone else's app. It is granted only for the note box, and only because
     * opening that box holds the run first.
     */
    private fun layoutParams(
        centered: Boolean = false,
        touchable: Boolean = true,
        focusable: Boolean = false,
        fullScreen: Boolean = false,
    ) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        if (fullScreen) {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        },
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        when {
            focusable -> 0
            touchable -> WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            else -> WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        },
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = if (centered) Gravity.CENTER else Gravity.TOP
        if (focusable) {
            // The box sits in the middle of the screen and the keyboard comes up under it,
            // so the window has to give way rather than be covered.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
    }

    private companion object {
        const val FRAME_SETTLE_MS = 48L

        /** Long enough to read a sentence, short enough not to sit on someone's screen. */
        const val OUTCOME_DISPLAY_MS = 4_000L
    }
}

@Composable
private fun StatusBar(
    status: Status,
    control: RunControl,
    composing: Boolean,
    question: String?,
    onCompose: () -> Unit,
    onCancel: () -> Unit,
    onSend: (String) -> Unit,
) {
    val done = !status.busy
    val runState by control.state.collectAsState()
    val paused = runState == RunControl.State.PAUSED

    if (composing) {
        NoteBox(
            doing = status.text,
            question = question,
            onCancel = onCancel,
            onSend = onSend,
        )
        return
    }

    // The window is full width and translucent, so this outer padding is what turns the
    // strip into a floating card rather than a bar welded to the top of the screen.
    Box(modifier = Modifier.padding(if (done) 24.dp else 12.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(if (done) 28.dp else 16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = if (done) 8.dp else 4.dp,
            shadowElevation = if (done) 8.dp else 4.dp,
        ) {
            if (done) {
                OutcomeCard(status)
            } else {
                RunningStrip(
                    status = status,
                    paused = paused,
                    control = control,
                    onCompose = onCompose,
                )
            }
        }
    }
}

/**
 * The box for telling a run something it did not start with.
 *
 * It sits in the middle of the screen rather than under the strip, because it is the only
 * thing being done at that moment: the run is held while it is open, and what is behind it
 * is some other app's screen that has nothing to do with the typing. The scrim says as
 * much, and takes the taps that would otherwise reach that app.
 *
 * The same box serves both directions. With no [question] it is the person volunteering
 * something and the run is being held for them; with one, the agent has stopped because it
 * cannot go on - which of the two Chens is Mum, which number to use - and is waiting on the
 * answer. Only the wording and what a stray tap does differ: a question is dismissed on
 * purpose or not at all, because closing it ends the run.
 *
 * What the agent is in the middle of is repeated at the top, since by now the person may
 * have been watching it work for a minute and is answering something it did.
 */
@Composable
private fun NoteBox(
    doing: String,
    question: String?,
    onCancel: () -> Unit,
    onSend: (String) -> Unit,
) {
    val asked = question != null
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // The window only becomes focusable as this appears, so the field cannot take focus in
    // the same frame it is drawn in - it asks again once the window has caught up.
    LaunchedEffect(Unit) {
        delay(FOCUS_DELAY_MS)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .clickable(enabled = !asked, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                // The card is not the scrim: a tap that lands on it must not be read as a
                // tap outside, which would close the box mid-sentence.
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (asked) "Wristch needs to know" else "Add to this task",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (question != null) {
                    Text(text = question, style = MaterialTheme.typography.bodyLarge)
                }
                if (doing.isNotBlank() && !asked) {
                    Text(
                        text = "Held at: $doing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (text.isEmpty()) {
                            Text(
                                text = if (asked) {
                                    "Type the answer"
                                } else {
                                    "The bottom one is her work number"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                }

                Text(
                    text = if (asked) {
                        "The run is waiting on this. Closing the box ends it."
                    } else {
                        "The run is held while this is open, and picks this up on its " +
                            "next step."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onCancel) {
                        Text(if (asked) "I cannot say" else "Cancel")
                    }
                    Button(onClick = { onSend(text) }, enabled = text.isNotBlank()) {
                        Text(if (asked) "Answer" else "Add")
                    }
                }
            }
        }
    }
}

@Composable
private fun OutcomeCard(status: Status) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Text(
            text = status.text,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RunningStrip(
    status: Status,
    paused: Boolean,
    control: RunControl,
    onCompose: () -> Unit,
) {
    // Remembered here rather than passed in: the strip is the one composable that outlives
    // a single step (its parent recomposes on every `update`), so the choice to have it
    // expanded survives from one action's text to the next instead of resetting each time.
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateBadge(paused)
        Text(
            text = if (paused) "Paused - ${status.text}" else status.text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable { expanded = !expanded },
        )
        // Adding to the run is offered whether it is going or held: a person watching it
        // take a wrong turn reaches for this before they reach for pause.
        FilledTonalIconButton(
            onClick = onCompose,
            modifier = Modifier.size(CONTROL_SIZE),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add to this task")
        }
        if (paused) {
            // Two ways out of a hold, and they are not equals: carrying on is the
            // expected one and gets the filled button, ending the run is the quiet one.
            FilledIconButton(
                onClick = control::resume,
                modifier = Modifier.size(CONTROL_SIZE),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
            }
            FilledTonalIconButton(
                onClick = control::stop,
                modifier = Modifier.size(CONTROL_SIZE),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(WristchIcons.Stop, contentDescription = "Stop")
            }
        } else {
            FilledTonalIconButton(
                onClick = control::pause,
                modifier = Modifier.size(CONTROL_SIZE),
            ) {
                Icon(WristchIcons.Pause, contentDescription = "Pause")
            }
        }
    }
}

/**
 * The badge says which of the two states the run is in before any label is read: a
 * restless cookie while it works, a flat pill while it is being held.
 */
@Composable
private fun StateBadge(paused: Boolean) {
    if (!paused) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        return
    }
    Surface(
        modifier = Modifier.size(28.dp),
        shape = WristchShapes.Held,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = WristchIcons.Pause,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private val CONTROL_SIZE = 40.dp

/** Long enough for the window to become focusable before the field asks for focus. */
private const val FOCUS_DELAY_MS = 80L

private const val SCRIM_ALPHA = 0.55f
