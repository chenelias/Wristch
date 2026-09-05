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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/** Result of one interaction attempt. */
sealed interface ActionResult {
    data object Success : ActionResult
    data class Failed(val reason: String) : ActionResult
}

class ScreenExecutor(private val service: AccessibilityService) {

    /**
     * Depth-first search for the first node matching [predicate].
     *
     * Hand-rolled rather than using `AccessibilityNodeInfo.findAccessibilityNodeInfosByText`,
     * which returns nothing at all on Compose UI: Compose draws into a single
     * AndroidComposeView and exposes its semantics as *virtual* nodes, which that search
     * never reaches. Measured on this very screen - the built-in search returned 0 nodes
     * for text this walk finds twice.
     */
    fun findNode(
        root: AccessibilityNodeInfo? = service.rootInActiveWindow,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val node = root ?: return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            findNode(node.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    /**
     * The node the system currently holds input focus on - the one handle on a text field
     * that survives an app with no ids, no text and no content descriptions. Pair it with
     * a coordinate [tap] to focus the field first, then [setText] into what comes back.
     */
    fun focusInput(): AccessibilityNodeInfo? =
        service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    fun findByText(text: String): AccessibilityNodeInfo? = findNode {
        it.text?.toString() == text || it.contentDescription?.toString() == text
    }

    suspend fun click(node: AccessibilityNodeInfo): ActionResult {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) return ActionResult.Failed("Node has no on-screen bounds.")
        return tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    /**
     * Find-then-click in one call - the pair every caller was writing by hand, minus the
     * `!!` that came with it. A missing node is a [ActionResult.Failed], not a crash.
     */
    suspend fun clickByName(name: String): ActionResult {
        val node = findByText(name)
            ?: return ActionResult.Failed("No node with text or description \"$name\".")
        return click(node)
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

    fun pressHome(): ActionResult =
        globalAction(AccessibilityService.GLOBAL_ACTION_HOME, "home")

    fun goBack(): ActionResult =
        globalAction(AccessibilityService.GLOBAL_ACTION_BACK, "back")

    fun openNotifications(): ActionResult =
        globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "notifications")

    fun openQuickSettings(): ActionResult =
        globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "quick settings")

    private fun globalAction(action: Int, name: String): ActionResult =
        if (service.performGlobalAction(action)) {
            ActionResult.Success
        } else {
            ActionResult.Failed("System refused the $name action.")
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

    /**
     * Taps [x], [y] and returns whatever ends up holding input focus - the field a finger
     * would have focused, found without any id, text or description to match on. Focus is
     * handed over on the app's own main thread after the gesture ends, so the wait is not
     * optional; without it [focusInput] is asked before the field has taken focus.
     */
    suspend fun focusAt(x: Float, y: Float): AccessibilityNodeInfo? {
        if (tap(x, y) is ActionResult.Failed) return null
        // How long focus takes is the target app's business, not ours - a field behind a
        // launch animation or a slow recomposition can be several hundred ms late. Poll
        // instead of guessing one delay that has to be right for every app.
        repeat(FOCUS_ATTEMPTS) {
            delay(FOCUS_SETTLE_MS)
            focusInput()?.let { return it }
        }
        return null
    }

    /** Submits the field, for editors that act on the IME action rather than a button. */
    fun pressEnter(node: AccessibilityNodeInfo): ActionResult =
        if (node.tryAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
            ActionResult.Success
        } else {
            ActionResult.Failed("Node refused the IME enter action.")
        }

    /**
     * Re-reads [node] from the live tree. `ACTION_SET_TEXT` returning true only means the
     * action was accepted, not that the field kept the text, so this is how a caller finds
     * out what actually landed.
     */
    fun textOf(node: AccessibilityNodeInfo): String? =
        runCatching {
            node.refresh()
            node.text?.toString()
        }.getOrNull()

    /** The whole realistic path: touch the field, then write into whatever took focus. */
    suspend fun typeAt(x: Float, y: Float, text: String): ActionResult {
        val node = focusAt(x, y)
            ?: return ActionResult.Failed("Tap at ($x, $y) focused no input field.")
        return setText(node, text)
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
        // paste trick works against any editor that honours the standard paste action -
        // but it leaves the text on the system clipboard and pops the platform clipboard
        // chip, so it is only worth that side effect on a field that could take the text.
        if (!node.isEditable) {
            return ActionResult.Failed("Node is not an editable field.")
        }
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
        private const val FOCUS_SETTLE_MS = 150L
        private const val FOCUS_ATTEMPTS = 6
        private const val CLIP_LABEL = "wristch"
    }
}
