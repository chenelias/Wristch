package dev.eliaschen.wristch.computer

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.ui.icon.WristchIcons
import dev.eliaschen.wristch.ui.shape.WristchShapes
import dev.eliaschen.wristch.ui.theme.WristchTheme
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** What the status bar is currently saying, and whether the agent is still going. */
private data class Status(
    val text: String,
    val busy: Boolean,
    val onAcknowledge: (() -> Unit)? = null,
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
) {

    private val windowManager = service.getSystemService(WindowManager::class.java)

    private val status: MutableState<Status> = mutableStateOf(Status("", true))

    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    suspend fun show(text: String) = withContext(Dispatchers.Main) {
        status.value = Status(text, busy = true)
        if (view != null) return@withContext

        val newOwner = OverlayOwner()
        val newView = ComposeView(service).apply {
            setTreeOwners(newOwner)
            setContent {
                WristchTheme { StatusBar(status.value, control) }
            }
        }
        newOwner.start()
        windowManager.addView(newView, layoutParams())
        owner = newOwner
        view = newView
    }

    suspend fun update(text: String) = withContext(Dispatchers.Main) {
        status.value = Status(text, busy = true)
    }

    /**
     * The outcome is the one thing worth reading, so it stops being a strip at the top and
     * becomes a card in the middle that waits to be dismissed. Gravity lives on the window,
     * not the composable, so the change needs the layout params updating as well as the
     * state.
     */
    suspend fun finish(text: String) {
        val current = view ?: return
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                var settled = false
                status.value = Status(text, busy = false) {
                    if (settled) return@Status
                    settled = true
                    if (continuation.isActive) continuation.resume(Unit)
                }
                runCatching {
                    windowManager.updateViewLayout(current, layoutParams(centered = true))
                }
            }
        }
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
                runCatching { windowManager.updateViewLayout(current, layoutParams()) }
            }
        }
    }

    private fun layoutParams(
        centered: Boolean = false,
        touchable: Boolean = true,
    ) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Never focusable: the agent may be mid-typing, and taking focus would pull the
        // keyboard out from under it.
        if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        },
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = if (centered) Gravity.CENTER else Gravity.TOP }

    private companion object {
        const val FRAME_SETTLE_MS = 48L
    }
}

@Composable
private fun StatusBar(status: Status, control: RunControl) {
    val done = !status.busy
    val runState by control.state.collectAsState()
    val paused = runState == RunControl.State.PAUSED

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
                RunningStrip(status = status, paused = paused, control = control)
            }
        }
    }
}

@Composable
private fun OutcomeCard(status: Status) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = status.text,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            Button(onClick = { status.onAcknowledge?.invoke() }) { Text("OK") }
        }
    }
}

@Composable
private fun RunningStrip(status: Status, paused: Boolean, control: RunControl) {
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
