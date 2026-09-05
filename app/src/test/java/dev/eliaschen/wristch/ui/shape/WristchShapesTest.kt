package dev.eliaschen.wristch.ui.shape

import androidx.compose.ui.graphics.Shape
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.hypot
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one way these shapes fail silently.
 *
 * `CornerRounding` is an absolute radius in the polygon's own units, so asking for more
 * rounding than a feature is deep rounds that feature away entirely and leaves a circle -
 * which still draws, still fills, and looks like nothing is wrong. These measure the
 * outline instead of trusting the parameters.
 */
class WristchShapesTest {

    @Test
    fun busyIsACookieAndNotACircle() {
        assertTrue("Busy has been rounded into a circle", relief(WristchShapes.Busy) > 0.08f)
    }

    /**
     * The badges carry an icon in the middle, so their outline has to stay clear of it. A
     * 14dp glyph in a 26dp node reaches about 0.64 of the way to the edge.
     */
    @Test
    fun badgesLeaveRoomForTheirIcon() {
        assertTrue(clearance(WristchShapes.Alert) > 0.9f)
        assertTrue(clearance(WristchShapes.Held) > 0.9f)
        assertTrue(clearance(WristchShapes.Busy) > 0.7f)
    }

    /** How far the outline swings between its dips and its peaks, against its peak. */
    private fun relief(shape: Shape): Float {
        val radii = radii(shape.polygon())
        return (radii.max() - radii.min()) / radii.max()
    }

    /**
     * The closest the outline comes to the centre, against the box the shape is drawn in -
     * 1.0 for a square, whose sides sit at exactly half its width.
     */
    private fun clearance(shape: Shape): Float {
        val polygon = shape.polygon()
        val bounds = polygon.calculateBounds()
        val halfBox = min(bounds[2] - bounds[0], bounds[3] - bounds[1]) / 2f
        return radii(polygon).min() / halfBox
    }

    private fun Shape.polygon(): RoundedPolygon = (this as PolygonShape).polygon

    /** Distance from the centre to every anchor on the outline. */
    private fun radii(polygon: RoundedPolygon): List<Float> {
        val bounds = polygon.calculateBounds()
        val centerX = (bounds[0] + bounds[2]) / 2f
        val centerY = (bounds[1] + bounds[3]) / 2f
        return polygon.cubics.flatMap { cubic ->
            listOf(
                hypot(cubic.anchor0X - centerX, cubic.anchor0Y - centerY),
                hypot(cubic.anchor1X - centerX, cubic.anchor1Y - centerY),
            )
        }
    }
}
