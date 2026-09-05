package dev.eliaschen.wristch.ui.shape

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import kotlin.math.min

/**
 * A rounded polygon as a Compose [Shape].
 *
 * Material 3's own expressive shape set (`MaterialShapes`) is still alpha-only, so these
 * are built directly on `androidx.graphics.shapes` - the same library those shapes are
 * made from - and stay on stable dependencies.
 */
class PolygonShape(val polygon: RoundedPolygon) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = polygon.toPath().asComposePath()
        // The polygon is defined around its own centre in arbitrary units; this fits its
        // bounding box inside the box Compose is asking about. One scale for both axes,
        // not one each: a hexagon is wider than it is tall, and stretching it to fill a
        // square is what turns the badge into a squashed hexagon rather than a hexagon.
        // Whatever is left over becomes even margin, so the shape stays centred.
        val bounds = polygon.calculateBounds()
        val width = bounds[2] - bounds[0]
        val height = bounds[3] - bounds[1]
        if (width <= 0f || height <= 0f) return Outline.Rectangle(size.toRect())

        val scale = min(size.width / width, size.height / height)
        path.transform(
            Matrix().apply {
                // Read bottom-up: the polygon is moved onto the origin, scaled, then
                // nudged by half of whatever the fit left over on each axis.
                translate((size.width - width * scale) / 2f, (size.height - height * scale) / 2f)
                scale(scale, scale)
                translate(-bounds[0], -bounds[1])
            },
        )
        return Outline.Generic(path)
    }

    private fun Size.toRect() = androidx.compose.ui.geometry.Rect(0f, 0f, width, height)
}

/**
 * The app's shape vocabulary, where the shape itself carries the state: a run in flight is
 * restless, a finished one has settled, a failed one has corners.
 */
object WristchShapes {

    /**
     * The app's shapes are stars whose lobes are shallow, and rounding is an absolute
     * radius in the polygon's own units - not a fraction of the lobe. Ask for more
     * rounding than an edge is long and the library rounds the lobes clean away, leaving a
     * circle: with a lobe 0.18 deep, a rounding of 0.45 has nothing left to describe. Every
     * value below is kept well under the depth of the feature it is rounding.
     */
    private const val LOBE_ROUNDING = 0.10f

    /**
     * A twelve-lobed cookie: the app's one decorative shape.
     *
     * The lobes are shallow - an inner radius of 0.80, not the library's default 0.5 -
     * because at badge size a deep star reads as a spiky asterisk rather than as a cookie,
     * and the notches are rounded as well as the points so nothing on the outline is sharp.
     */
    val CookieShape: Shape = PolygonShape(
        RoundedPolygon.star(
            numVerticesPerRadius = 12,
            innerRadius = 0.80f,
            rounding = CornerRounding(LOBE_ROUNDING),
            innerRounding = CornerRounding(LOBE_ROUNDING),
        ),
    )

    /** In flight - the cookie, which is the restless shape in the state vocabulary. */
    val Busy: Shape = CookieShape

    /** Settled - a softly rounded hexagon, near enough to a circle to read as calm. */
    val Settled: Shape = PolygonShape(
        RoundedPolygon(numVertices = 6, rounding = CornerRounding(0.5f)),
    )

    /**
     * Something went wrong - a rounded square. Flat sides and four corners set it apart
     * from the cookies at a glance, and its inscribed circle is the full half-width, so
     * the glyph inside it has more room than any other shape here gives.
     */
    val Alert: Shape = PolygonShape(
        RoundedPolygon.rectangle(width = 1f, height = 1f, rounding = CornerRounding(0.22f)),
    )

    /**
     * Held - a squircle: the same four sides as [Alert] but rounded far enough to read as
     * soft rather than sharp. Nothing about it suggests motion.
     */
    val Held: Shape = PolygonShape(
        RoundedPolygon.rectangle(width = 1f, height = 1f, rounding = CornerRounding(0.42f)),
    )
}
