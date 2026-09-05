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
        // The polygon is defined around its own centre in arbitrary units; this maps its
        // bounding box onto the box Compose is asking about. Scale is applied before the
        // translate, so the shape lands at the origin at the right size.
        val bounds = polygon.calculateBounds()
        val width = bounds[2] - bounds[0]
        val height = bounds[3] - bounds[1]
        if (width <= 0f || height <= 0f) return Outline.Rectangle(size.toRect())

        path.transform(
            Matrix().apply {
                scale(size.width / width, size.height / height)
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

    /** In flight - a many-lobed cookie, the busiest shape here. */
    val Busy: Shape = PolygonShape(
        RoundedPolygon.star(
            numVerticesPerRadius = 12,
            innerRadius = 0.80f,
            rounding = CornerRounding(LOBE_ROUNDING),
            innerRounding = CornerRounding(LOBE_ROUNDING),
        ),
    )

    /** Settled - a softly rounded hexagon, near enough to a circle to read as calm. */
    val Settled: Shape = PolygonShape(
        RoundedPolygon(numVertices = 6, rounding = CornerRounding(0.5f)),
    )

    /**
     * Something went wrong - a cookie with half as many lobes as [Busy] and deeper dips,
     * so the two do not read as the same shape at a glance.
     *
     * Not the obvious triangle: a triangle's inscribed circle is half its width, so an
     * icon centred in one loses its corners to the edges. The dips here sit at 75% of the
     * radius, which still leaves a 14dp glyph room inside a 26dp node.
     */
    val Alert: Shape = PolygonShape(
        RoundedPolygon.star(
            numVerticesPerRadius = 6,
            innerRadius = 0.75f,
            rounding = CornerRounding(0.14f),
            innerRounding = CornerRounding(0.14f),
        ),
    )

    /**
     * Held - a soft square. Flat sides and four corners, so it carries no relation to the
     * cookies; nothing about it suggests motion.
     */
    val Held: Shape = PolygonShape(
        RoundedPolygon.rectangle(width = 1f, height = 1f, rounding = CornerRounding(0.3f)),
    )
}
