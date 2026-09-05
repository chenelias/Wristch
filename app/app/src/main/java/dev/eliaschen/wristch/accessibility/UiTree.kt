package dev.eliaschen.wristch.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The screen as a short list of the things that can be acted on.
 *
 * A screenshot is the only thing that shows layout, but it makes the model *read* labels
 * out of pixels - which is where wrong taps come from, and every wrong tap costs a whole
 * round trip. The accessibility tree already holds those labels exactly, and as text it
 * weighs a few kilobytes against a JPEG's tens.
 *
 * Coordinates are emitted on the same 0-999 grid the model answers in (see
 * `ActionDispatcher.NORMALIZED_SPAN`), so a centre read from this list can be handed
 * straight back as a click without any conversion in between.
 */
object UiTree {

    /** One line per actionable node, most useful attributes first. */
    fun summarize(root: AccessibilityNodeInfo?, width: Int, height: Int): String {
        val node = root ?: return ""
        if (width <= 0 || height <= 0) return ""

        val lines = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        walk(node, width, height, depth = 0, labelledAncestor = false, lines = lines, seen = seen)
        if (lines.isEmpty()) return ""

        val header = node.packageName?.toString()?.let { "screen: $it" } ?: "screen:"
        val body = if (lines.size > MAX_LINES) {
            lines.take(MAX_LINES) + "(${lines.size - MAX_LINES} more elements not listed)"
        } else {
            lines
        }
        return (listOf(header) + body).joinToString("\n")
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        width: Int,
        height: Int,
        depth: Int,
        labelledAncestor: Boolean,
        lines: MutableList<String>,
        seen: MutableSet<String>,
    ) {
        if (depth > MAX_DEPTH || lines.size > MAX_LINES * 2) return

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        // A node drawn off-screen or scrolled out of view is not something to tap; it is
        // also where most of the noise in a long list lives.
        val onScreen = !bounds.isEmpty && bounds.right > 0 && bounds.bottom > 0 &&
            bounds.left < width && bounds.top < height
        if (!onScreen || !node.isVisibleToUser) return

        val interactive = node.isClickable || node.isEditable || node.isCheckable ||
            node.isScrollable || node.isLongClickable
        val own = label(node)
        // A clickable row usually carries no text of its own - the text sits on a child.
        // Borrowing it keeps the row addressable by the name a person would call it.
        val text = own ?: if (interactive) descendantLabel(node, 0) else null

        var emitted = false
        if (text != null && (interactive || !labelledAncestor)) {
            val x = (bounds.exactCenterX() / width * SPAN).toInt().coerceIn(0, SPAN.toInt() - 1)
            val y = (bounds.exactCenterY() / height * SPAN).toInt().coerceIn(0, SPAN.toInt() - 1)
            val line = "($x,$y) ${role(node)} \"$text\"${flags(node)}"
            if (seen.add("$text@$x,$y")) {
                lines += line
                emitted = true
            }
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            walk(child, width, height, depth + 1, labelledAncestor || emitted, lines, seen)
        }
    }

    private fun label(node: AccessibilityNodeInfo): String? {
        val raw = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: return null
        val flat = raw.replace('\n', ' ').trim()
        return if (flat.length > MAX_LABEL) flat.take(MAX_LABEL) + "..." else flat
    }

    private fun descendantLabel(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > BORROW_DEPTH) return null
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            label(child)?.let { return it }
            descendantLabel(child, depth + 1)?.let { return it }
        }
        return null
    }

    /** The class name without its package - "Button", not "android.widget.Button". */
    private fun role(node: AccessibilityNodeInfo): String =
        node.className?.toString()?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: "View"

    private fun flags(node: AccessibilityNodeInfo): String {
        val flags = buildList {
            if (node.isEditable) add("editable")
            if (node.isClickable) add("clickable")
            if (node.isScrollable) add("scrollable")
            if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
            if (node.isFocused) add("focused")
            if (!node.isEnabled) add("disabled")
        }
        return if (flags.isEmpty()) "" else " [${flags.joinToString(",")}]"
    }

    private const val SPAN = 1000f
    private const val MAX_LINES = 60
    private const val MAX_DEPTH = 40
    private const val BORROW_DEPTH = 3
    private const val MAX_LABEL = 60
}
