package dev.eliaschen.wristch.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Result of one interaction attempt. */
sealed interface ActionResult {
    data object Success : ActionResult
    data class Failed(val reason: String) : ActionResult
}

class ScreenExecutor(private val service: AccessibilityService) {

    suspend fun click(node: AccessibilityNodeInfo): ActionResult {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) return ActionResult.Failed("Node has no on-screen bounds.")
        return tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    suspend fun tap(x: Float, y: Float): ActionResult {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        return if (dispatch(stroke)) ActionResult.Success else ActionResult.Failed("Gesture dispatch was rejected.")
    }

    suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = SWIPE_DURATION_MS,
    ): ActionResult {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return if (dispatch(stroke)) ActionResult.Success else ActionResult.Failed("Gesture dispatch was rejected.")
    }

    /**
     * Scrolls via the node's own action rather than a gesture - deliberately unlike
     * [click]. A scrollable container honours `ACTION_SCROLL_*` reliably, and the node
     * action can move content a synthetic swipe would not reach.
     */
    fun scroll(node: AccessibilityNodeInfo, forward: Boolean): ActionResult {
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return if (node.tryAction(action)) ActionResult.Success else ActionResult.Failed("Node refused the scroll action.")
    }

    /** No coordinate equivalent exists for typing, so this stays node-based. */
    fun setText(node: AccessibilityNodeInfo, text: String): ActionResult {
        node.tryAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.tryAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            return ActionResult.Success
        }

        // Some custom message composers never implement ACTION_SET_TEXT. The clipboard
        // paste trick works against any editor that honours the standard paste action.
        return pasteViaClipboard(node, text)
    }

    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): ActionResult {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))

        val selectAll = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text?.length ?: 0)
        }
        node.tryAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAll)

        return if (node.tryAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            ActionResult.Success
        } else {
            ActionResult.Failed("Neither ACTION_SET_TEXT nor clipboard paste was accepted.")
        }
    }

    /**
     * `dispatchGesture` returns false outright when the service may not inject gestures,
     * and in that case the callback never fires - hence resuming on the return value too,
     * without which the coroutine would hang forever.
     */
    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val dispatched = service.dispatchGesture(gesture, callback, null)
            if (!dispatched && continuation.isActive) continuation.resume(false)
        }

    /** A stale node (the real screen moved on) can make `performAction` return false or throw; both fold into `false`. */
    private fun AccessibilityNodeInfo.tryAction(action: Int, arguments: Bundle? = null): Boolean =
        runCatching { if (arguments != null) performAction(action, arguments) else performAction(action) }
            .getOrDefault(false)

    companion object {
        private const val TAP_DURATION_MS = 50L
        private const val SWIPE_DURATION_MS = 300L
        private const val CLIP_LABEL = "wristch"
    }
}
