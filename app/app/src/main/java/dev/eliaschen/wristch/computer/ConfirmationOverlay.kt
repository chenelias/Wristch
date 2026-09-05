package dev.eliaschen.wristch.computer

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.ui.theme.WristchTheme
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * An approval prompt that outlives the app it is asking about.
 *
 * The agent drives other apps, so by the time a step needs consent this app is nowhere on
 * screen - a dialog inside it would never be seen. `TYPE_ACCESSIBILITY_OVERLAY` draws above
 * everything and, unlike `TYPE_APPLICATION_OVERLAY`, is granted by being an accessibility
 * service: no SYSTEM_ALERT_WINDOW, no settings trip for the user.
 */
class ConfirmationOverlay(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)

    /** Suspends until the person taps one of the two buttons. */
    suspend fun confirm(action: String, explanation: String): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val owner = OverlayOwner()
                var settled = false
                lateinit var view: ComposeView

                fun finish(approved: Boolean) {
                    if (settled) return
                    settled = true
                    runCatching { windowManager.removeViewImmediate(view) }
                    owner.destroy()
                    if (continuation.isActive) continuation.resume(approved)
                }

                view = ComposeView(service).apply {
                    setTreeOwners(owner)
                    setContent {
                        WristchTheme {
                            ConfirmationCard(
                                action = action,
                                explanation = explanation,
                                onAllow = { finish(true) },
                                onDeny = { finish(false) },
                            )
                        }
                    }
                }

                owner.start()
                windowManager.addView(view, layoutParams())

                continuation.invokeOnCancellation {
                    // Cancellation arrives on whatever thread cancelled us; windows may
                    // only be touched from the main thread.
                    Handler(Looper.getMainLooper()).post { finish(false) }
                }
            }
        }

    /**
     * Not focusable on purpose: the agent may be mid-typing, and a focusable overlay would
     * steal input focus and the keyboard out from under it. Touches still land.
     */
    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP }
}

@androidx.compose.runtime.Composable
private fun ConfirmationCard(
    action: String,
    explanation: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Box(modifier = Modifier.padding(12.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Wristch needs approval", style = MaterialTheme.typography.titleMedium)
                Text(action, style = MaterialTheme.typography.bodyMedium)
                Text(explanation, style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDeny) { Text("Deny") }
                    Button(onClick = onAllow) { Text("Allow") }
                }
            }
        }
    }
}
