package dev.eliaschen.wristch.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The two glyphs Material's *core* icon set leaves out.
 *
 * `material-icons-extended` has both, and costs several thousand vectors and a large dex
 * to get them. Two paths, drawn to the same 24dp / 24-unit grid as the core icons so they
 * sit next to `Icons.Default.PlayArrow` without looking off-weight, are the cheaper trade.
 */
object WristchIcons {

    val Pause: ImageVector by lazy {
        icon("Pause") {
            moveTo(6f, 19f)
            horizontalLineTo(10f)
            verticalLineTo(5f)
            horizontalLineTo(6f)
            close()
            moveTo(14f, 5f)
            verticalLineTo(19f)
            horizontalLineTo(18f)
            verticalLineTo(5f)
            close()
        }
    }

    val Stop: ImageVector by lazy {
        icon("Stop") {
            moveTo(6f, 6f)
            horizontalLineTo(18f)
            verticalLineTo(18f)
            horizontalLineTo(6f)
            close()
        }
    }

    /** A mouse pointer, for the taps the agent makes on the screen. */
    val Cursor: ImageVector by lazy {
        icon("Cursor") {
            moveTo(6f, 3f)
            lineTo(18f, 13.2f)
            lineTo(12.4f, 13.6f)
            lineTo(15.4f, 20.2f)
            lineTo(12.9f, 21.2f)
            lineTo(10f, 14.6f)
            lineTo(6f, 18.4f)
            close()
        }
    }

    /** Arrows both ways, for scrolls and drags - the screen moving under a finger. */
    val Swipe: ImageVector by lazy {
        icon("Swipe") {
            moveTo(12f, 2f)
            lineTo(7f, 8f)
            horizontalLineTo(17f)
            close()
            moveTo(10.5f, 9f)
            horizontalLineTo(13.5f)
            verticalLineTo(15f)
            horizontalLineTo(10.5f)
            close()
            moveTo(12f, 22f)
            lineTo(7f, 16f)
            horizontalLineTo(17f)
            close()
        }
    }

    /** An hourglass, for the steps that are only waiting for something to finish. */
    val Hourglass: ImageVector by lazy {
        icon("Hourglass") {
            moveTo(5f, 2f)
            horizontalLineTo(19f)
            verticalLineTo(4f)
            horizontalLineTo(5f)
            close()
            moveTo(6f, 4f)
            horizontalLineTo(18f)
            lineTo(12f, 12f)
            close()
            moveTo(12f, 12f)
            lineTo(18f, 20f)
            horizontalLineTo(6f)
            close()
            moveTo(5f, 20f)
            horizontalLineTo(19f)
            verticalLineTo(22f)
            horizontalLineTo(5f)
            close()
        }
    }

    /**
     * A four-pointed sparkle, for the agent itself.
     *
     * Concave sides rather than a plain diamond: the pinch is the whole difference between
     * a star that reads as "something is thinking here" and a rotated square.
     */
    val Sparkle: ImageVector by lazy {
        icon("Sparkle") {
            moveTo(12f, 2f)
            curveTo(13.2f, 7.2f, 16.8f, 10.8f, 22f, 12f)
            curveTo(16.8f, 13.2f, 13.2f, 16.8f, 12f, 22f)
            curveTo(10.8f, 16.8f, 7.2f, 13.2f, 2f, 12f)
            curveTo(7.2f, 10.8f, 10.8f, 7.2f, 12f, 2f)
            close()
        }
    }

    /**
     * A notepad with three lines of text, for the notebook - distinct from a plain list
     * or menu glyph, which read as navigation rather than as something written down.
     *
     * Drawn as three contours in one path: the outer page and an inner rect wound the
     * opposite way cancel out under the nonzero fill rule, leaving a hollow border: the
     * three short bars inside it are separate clockwise contours, so they fill solid
     * where the border does not.
     */
    val Memory: ImageVector by lazy {
        icon("Memory") {
            // Outer page, clockwise.
            moveTo(4f, 3f)
            horizontalLineTo(20f)
            verticalLineTo(21f)
            horizontalLineTo(4f)
            close()
            // Inner hole, counter-clockwise - cancels the outer fill, leaving a border.
            moveTo(5.5f, 4.5f)
            verticalLineTo(19.5f)
            horizontalLineTo(18.5f)
            verticalLineTo(4.5f)
            close()
            // Three lines of text, clockwise, sitting inside the hole.
            moveTo(7f, 7.5f)
            horizontalLineTo(16f)
            verticalLineTo(8.7f)
            horizontalLineTo(7f)
            close()
            moveTo(7f, 11.5f)
            horizontalLineTo(16f)
            verticalLineTo(12.7f)
            horizontalLineTo(7f)
            close()
            moveTo(7f, 15.5f)
            horizontalLineTo(16f)
            verticalLineTo(16.7f)
            horizontalLineTo(7f)
            close()
        }
    }

    /** A microphone, for voice input. */
    val Mic: ImageVector by lazy {
        icon("Mic") {
            moveTo(12f, 14f)
            curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
            verticalLineTo(5f)
            curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
            curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
            verticalLineTo(11f)
            curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
            close()
            moveTo(17f, 11f)
            curveTo(17f, 13.76f, 14.76f, 16f, 12f, 16f)
            curveTo(9.24f, 16f, 7f, 13.76f, 7f, 11f)
            horizontalLineTo(5f)
            curveTo(5f, 14.53f, 7.61f, 17.43f, 11f, 17.92f)
            verticalLineTo(21f)
            horizontalLineTo(13f)
            verticalLineTo(17.92f)
            curveTo(16.39f, 17.43f, 19f, 14.53f, 19f, 11f)
            close()
        }
    }

    private fun icon(
        name: String,
        pathData: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Black, then tinted by whatever Icon() is given - the same contract the core
        // icons follow, which is what lets these take the theme's colour like the rest.
        path(fill = SolidColor(Color.Black), pathBuilder = pathData)
    }.build()
}
