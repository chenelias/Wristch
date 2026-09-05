package dev.eliaschen.wristch.computer

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.google.genai.types.FunctionCall
import dev.eliaschen.wristch.accessibility.ActionResult
import dev.eliaschen.wristch.accessibility.ScreenExecutor
import kotlinx.coroutines.delay

/** Pixel size of the screenshot the model was shown - what its coordinates are relative to. */
data class ScreenSize(val width: Int, val height: Int)

/**
 * Turns one model function call into a real gesture.
 *
 * Action names and argument shapes belong to the model and shift between model versions,
 * so nothing here throws: an unknown name or a missing argument comes back as a readable
 * sentence. That sentence is sent back as the function response, which is what lets the
 * model notice the failure and try another way - a crash would just end the run.
 */
class ActionDispatcher(
    private val executor: ScreenExecutor,
    private val context: Context,
) {

    suspend fun execute(call: FunctionCall, screen: ScreenSize): String {
        val name = call.name().orElse("")
        val args = call.args().orElse(emptyMap())
        // The raw call is the only place the real argument schema is visible; keep it in
        // logcat so a model update that renames a field shows up as a diff, not a mystery.
        Log.i(TAG, "call $name $args")

        return when (name) {
            "click", "click_at", "left_click" -> click(args, screen)
            "double_click" -> doubleClick(args, screen)
            "type", "type_text", "type_text_at" -> type(args, screen)
            "scroll", "scroll_at", "scroll_document" -> scroll(args, screen)
            "drag_and_drop", "drag" -> drag(args, screen)
            "go_back", "navigate_back" -> executor.goBack().toString()
            "go_home", "home" -> executor.pressHome().toString()
            "open_app" -> openApp(args)
            "list_apps" -> listApps()
            "wait", "wait_5_seconds" -> waitFor(args)
            "take_screenshot", "screenshot" -> "Screenshot attached to this response."
            "key_combination", "press_key" -> UNSUPPORTED_KEYS
            else -> "Unsupported action \"$name\"."
        }
    }

    private suspend fun click(args: Map<String, Any?>, screen: ScreenSize): String {
        val x = args.x(screen) ?: return MISSING_COORDINATES
        val y = args.y(screen) ?: return MISSING_COORDINATES
        return executor.tap(x, y).toString()
    }

    private suspend fun doubleClick(args: Map<String, Any?>, screen: ScreenSize): String {
        val x = args.x(screen) ?: return MISSING_COORDINATES
        val y = args.y(screen) ?: return MISSING_COORDINATES
        executor.tap(x, y)
        delay(DOUBLE_TAP_GAP_MS)
        return executor.tap(x, y).toString()
    }

    /**
     * With coordinates this is the full touch-then-write path; without them it writes into
     * whatever already holds focus, which is what the model means by a bare `type`.
     */
    private suspend fun type(args: Map<String, Any?>, screen: ScreenSize): String {
        val text = args.string("text", "value", "content", "input_text")
            ?: return "Missing text to type."
        val x = args.x(screen)
        val y = args.y(screen)

        val node = if (x != null && y != null) {
            executor.focusAt(x, y) ?: return "Tap at ($x, $y) focused no input field."
        } else {
            focusedEditable() ?: return NO_FOCUSED_FIELD
        }

        val written = executor.setText(node, text)
        if (written is ActionResult.Failed) return written.reason

        val submitted = if (args.flag("press_enter", "submit", "enter")) {
            " " + executor.pressEnter(node)
        } else {
            ""
        }

        // ACTION_SET_TEXT reporting true is not proof the field kept the text - some
        // editors accept the action and ignore it. Reading the field back gives the model
        // the ground truth instead of a Success it cannot check.
        val actual = executor.textOf(node)
        return when {
            actual == null -> "Typed, but the field could not be read back.$submitted"
            actual.contains(text) -> "Field now reads \"$actual\".$submitted"
            else -> "Text was not accepted; the field still reads \"$actual\"."
        }
    }

    /**
     * The field the text should go into.
     *
     * `findFocus(FOCUS_INPUT)` is the direct answer and usually right, but some fields -
     * the phone row in Contacts among them - never come back from it even while the tree
     * marks them focused. Walking for the focused editable node catches those.
     */
    private fun focusedEditable(): AccessibilityNodeInfo? {
        executor.focusInput()?.takeIf { it.isEditable }?.let { return it }
        return executor.findNode { it.isEditable && it.isFocused }
    }

    /**
     * Coordinate scrolling has to be a swipe - the node action needs a node, and the model
     * only ever names a point. Content follows the finger, so scrolling down drags upward.
     */
    private suspend fun scroll(args: Map<String, Any?>, screen: ScreenSize): String {
        val direction = args.string("direction")?.lowercase() ?: "down"
        val x = args.x(screen) ?: (screen.width / 2f)
        val y = args.y(screen) ?: (screen.height / 2f)
        val distance = args.number("magnitude", "amount", "distance")
            ?.let { it / NORMALIZED_SPAN * screen.height }
            ?: (screen.height * DEFAULT_SCROLL_FRACTION)

        val (endX, endY) = when (direction) {
            "down" -> x to y - distance
            "up" -> x to y + distance
            "left" -> x + distance to y
            "right" -> x - distance to y
            else -> return "Unknown scroll direction \"$direction\"."
        }
        return executor.swipe(x, y, endX.coerceIn(0f, screen.width - 1f), endY.coerceIn(0f, screen.height - 1f)).toString()
    }

    private suspend fun drag(args: Map<String, Any?>, screen: ScreenSize): String {
        val startX = args.number("start_x", "x")
            ?.let { it / NORMALIZED_SPAN * screen.width } ?: return MISSING_COORDINATES
        val startY = args.number("start_y", "y")
            ?.let { it / NORMALIZED_SPAN * screen.height } ?: return MISSING_COORDINATES
        val endX = args.number("end_x", "destination_x", "to_x")
            ?.let { it / NORMALIZED_SPAN * screen.width } ?: return MISSING_COORDINATES
        val endY = args.number("end_y", "destination_y", "to_y")
            ?.let { it / NORMALIZED_SPAN * screen.height } ?: return MISSING_COORDINATES

        homeGesture(startY, endY, screen)?.let { return it }
        return executor.swipe(startX, startY, endX, endY, DRAG_DURATION_MS).toString()
    }

    /**
     * The mobile action set has no "go home", so the model asks for the swipe-up-from-the-
     * bottom-edge gesture instead. That gesture belongs to SystemUI, which ignores injected
     * touches - the swipe would report success and nothing would move. Recognise the shape
     * and use the global action, which is what the model actually meant.
     */
    private fun homeGesture(startY: Float, endY: Float, screen: ScreenSize): String? {
        val fromBottomEdge = startY >= screen.height * (1f - EDGE_FRACTION)
        val travelsUp = startY - endY >= screen.height * MIN_HOME_TRAVEL
        return if (fromBottomEdge && travelsUp) executor.pressHome().toString() else null
    }

    private suspend fun openApp(args: Map<String, Any?>): String {
        val wanted = args.string("app_name", "package_name", "name")
            ?: return "Missing app name."
        val manager = context.packageManager
        val match = launchableApps().firstOrNull {
            it.first.equals(wanted, ignoreCase = true) || it.second.equals(wanted, ignoreCase = true)
        } ?: return "No launchable app matches \"$wanted\"."

        val intent = manager.getLaunchIntentForPackage(match.second)
            ?: return "\"${match.first}\" has no launch intent."
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // The screenshot taken straight after this would still show the old screen, or a
        // half-drawn launch animation. Costing a second here is cheaper than spending a
        // whole model round trip on a picture of nothing.
        delay(APP_LAUNCH_MS)
        return "Launched ${match.first}."
    }

    private fun listApps(): String =
        launchableApps().joinToString("\n") { "${it.first} (${it.second})" }

    /** Label to package, for every app with a launcher entry. */
    private fun launchableApps(): List<Pair<String, String>> {
        val manager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return manager.queryIntentActivities(launcher, 0).map {
            it.loadLabel(manager).toString() to it.activityInfo.packageName
        }.distinctBy { it.second }.sortedBy { it.first }
    }

    private suspend fun waitFor(args: Map<String, Any?>): String {
        val millis = args.number("milliseconds", "duration_ms")?.toLong()
            ?: args.number("seconds")?.let { (it * 1000).toLong() }
            ?: DEFAULT_WAIT_MS
        delay(millis.coerceAtMost(MAX_WAIT_MS))
        return "Waited ${millis}ms."
    }

    /** Model coordinates arrive on a 0-999 grid, so every one is scaled by the real screen. */
    private fun Map<String, Any?>.x(screen: ScreenSize): Float? =
        number("x")?.let { it / NORMALIZED_SPAN * screen.width }

    private fun Map<String, Any?>.y(screen: ScreenSize): Float? =
        number("y")?.let { it / NORMALIZED_SPAN * screen.height }

    private fun Map<String, Any?>.number(vararg keys: String): Float? =
        keys.firstNotNullOfOrNull { this[it] as? Number }?.toFloat()

    private fun Map<String, Any?>.string(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { this[it] as? String }

    private fun Map<String, Any?>.flag(vararg keys: String): Boolean =
        keys.any { this[it] == true || this[it] == "true" }

    companion object {
        private const val TAG = "WristchDispatch"
        private const val NORMALIZED_SPAN = 1000f
        private const val DEFAULT_SCROLL_FRACTION = 0.4f
        private const val DOUBLE_TAP_GAP_MS = 80L
        private const val DRAG_DURATION_MS = 600L
        private const val EDGE_FRACTION = 0.05f
        private const val MIN_HOME_TRAVEL = 0.2f
        private const val DEFAULT_WAIT_MS = 1000L
        private const val APP_LAUNCH_MS = 1000L
        private const val MAX_WAIT_MS = 10_000L
        private const val MISSING_COORDINATES = "Missing x/y coordinates."
        private const val NO_FOCUSED_FIELD =
            "No editable field has focus. Click the input field first, then type again."
        private const val UNSUPPORTED_KEYS =
            "Key events cannot be injected by an accessibility service. Use typing, " +
                "go_back or go_home instead."
    }
}
