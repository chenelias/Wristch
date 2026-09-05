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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.ui.theme.WristchTheme
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** What the status bar is currently saying, and whether the agent is still going. */
private data class Status(
    val text: String,
    val busy: Boolean,
    val onAcknowledge: (() -> Unit)? = null,
)

/**
 * A slim always-on-top strip that says what the agent is doing right now.
 *
 * Untouchable on purpose: the agent taps real coordinates, and a strip that swallowed
 * touches would eat the ones meant for the app underneath it.
 */
class StatusOverlay(private val service: AccessibilityService) {

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
                WristchTheme { StatusBar(status.value) }
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
     * becomes a card in the middle that waits to be dismissed. Gravity and touchability
     * live on the window, not the composable, so the change needs the layout params
     * updating as well as the state - until now the strip has been untouchable.
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
     * Takes the strip off screen for the duration of [block].
     *
     * `takeScreenshot` captures the whole display, overlays included - leave this visible
     * and every screenshot the model sees has a floating box in it that belongs to no app.
     * It would reason about it, and might try to tap it.
     */
    suspend fun <T> hiddenDuring(block: suspend () -> T): T {
        val current = view ?: return block()
        withContext(Dispatchers.Main) { current.visibility = View.INVISIBLE }
        try {
            // The window has to actually be composited away before the capture; a couple
            // of frames is cheap next to the seconds each model round trip costs.
            delay(FRAME_SETTLE_MS)
            return block()
        } finally {
            withContext(Dispatchers.Main) { current.visibility = View.VISIBLE }
        }
    }

    private fun layoutParams(centered: Boolean = false) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        if (centered) {
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
private fun StatusBar(status: Status) {
    val done = !status.busy
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
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = status.text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
