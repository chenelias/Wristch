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
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

/**
 * A rounded polygon as a Compose [Shape].
 *
 * Material 3's own expressive shape set (`MaterialShapes`) is still alpha-only, so these
 * are built directly on `androidx.graphics.shapes` - the same library those shapes are
 * made from - and stay on stable dependencies.
 */
class PolygonShape(private val polygon: RoundedPolygon) : Shape {

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

    /** In flight - a many-pointed cookie, the busiest shape here. */
    val Busy: Shape = PolygonShape(
        RoundedPolygon.star(
            numVerticesPerRadius = 12,
            innerRadius = 0.85f,
            rounding = CornerRounding(0.35f),
            innerRounding = CornerRounding(0.35f),
        ),
    )

    /** Settled - a softly rounded hexagon, near enough to a circle to read as calm. */
    val Settled: Shape = PolygonShape(
        RoundedPolygon(numVertices = 6, rounding = CornerRounding(0.5f)),
    )

    /**
     * Something went wrong - flat-sided and sharper than [Settled], and it reads as a sign.
     *
     * An octagon rather than the obvious triangle: a triangle's inscribed circle is half
     * its width, so any icon centred in one loses its corners to the edges. This keeps
     * about 92% of the width usable, which is enough for a glyph to sit inside it.
     */
    val Alert: Shape = PolygonShape(
        RoundedPolygon(numVertices = 8, rounding = CornerRounding(0.15f)),
    )

    /** Held - a wide, flat pill; nothing is moving. */
    val Held: Shape = PolygonShape(
        RoundedPolygon(numVertices = 8, rounding = CornerRounding(0.7f)),
    )
}
