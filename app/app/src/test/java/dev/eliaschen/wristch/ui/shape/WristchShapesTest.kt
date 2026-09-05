package dev.eliaschen.wristch.ui.shape

import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.hypot
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one way these shapes fail silently.
 *
 * `CornerRounding` is an absolute radius in the polygon's own units, so asking for more
 * rounding than a lobe is deep rounds the lobes away entirely and leaves a circle - which
 * still draws, still fills, and looks like nothing is wrong. These measure the outline
 * instead of trusting the parameters.
 */
class WristchShapesTest {

    @Test
    fun cookiesHaveVisibleLobes() {
        assertTrue("Busy is round, not a cookie", relief(WristchShapes.Busy) > 0.08f)
        assertTrue("Alert is round, not a cookie", relief(WristchShapes.Alert) > 0.12f)
    }

    @Test
    fun alertIsChunkierThanBusy() {
        assertTrue(relief(WristchShapes.Alert) > relief(WristchShapes.Busy))
    }

    /** A glyph centred in the node needs the dips to stay clear of it. */
    @Test
    fun alertStillFitsAnIcon() {
        // 14dp icon art in a 26dp node: the corners of the glyph sit at ~0.64 of the
        // radius, so the dips have to stay outside that.
        assertTrue(minRadius(WristchShapes.Alert) > 0.7f)
    }

    /** How far the outline swings between its dips and its peaks, as a fraction of size. */
    private fun relief(shape: androidx.compose.ui.graphics.Shape): Float {
        val radii = radii((shape as PolygonShape).polygon)
        return radii.max() - radii.min()
    }

    private fun minRadius(shape: androidx.compose.ui.graphics.Shape): Float =
        radii((shape as PolygonShape).polygon).min()

    /** Distance from the centre to every anchor on the outline, normalised to the peak. */
    private fun radii(polygon: RoundedPolygon): List<Float> {
        val bounds = polygon.calculateBounds()
        val centerX = (bounds[0] + bounds[2]) / 2f
        val centerY = (bounds[1] + bounds[3]) / 2f
        val raw = polygon.cubics.flatMap { cubic ->
            listOf(
                hypot(cubic.anchor0X - centerX, cubic.anchor0Y - centerY),
                hypot(cubic.anchor1X - centerX, cubic.anchor1Y - centerY),
            )
        }
        val peak = raw.max()
        return raw.map { it / peak }
    }
}
