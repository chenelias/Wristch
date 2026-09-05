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
