package dev.th7bo.sidequest.ui.core.rendering

import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.rendering.Corners
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns a rounded rectangle into rows a renderer can fill, with the edges anti-aliased.
 *
 * **Why this exists.** Minecraft's GUI renderer can only fill axis-aligned rectangles at whole-pixel
 * coordinates, so a rounded corner drawn the obvious way is a staircase: each row is inset by a *rounded*
 * number of pixels, and at the radii an interface actually uses the steps are large enough to count. That is
 * what makes a panel look like pixel art rather than like a rounded panel.
 *
 * The fix is not more rows — it is admitting that a pixel on the edge is *partly* covered. Each row here
 * yields a solid span plus up to two edge pixels carrying the fraction of themselves the shape actually
 * covers, which the renderer draws at proportional alpha. A staircase becomes a gradient, and at any GUI
 * scale that reads as a smooth curve.
 *
 * Pure geometry on purpose: the renderer that uses it cannot be tested, and this can.
 */
public object RoundedRectRaster {

    /**
     * One horizontal slice of the shape.
     *
     * [solidLeft] until [solidRight] is fully covered. The two edge pixels are the ones the curve passes
     * through, and their coverage is how much of that single pixel column is inside the shape.
     */
    public data class Row(
        public val top: Int,
        public val bottom: Int,
        public val solidLeft: Int,
        public val solidRight: Int,
        /** Coverage of the pixel immediately left of [solidLeft], in `0f..1f`. Zero means draw nothing. */
        public val leftCoverage: Float = 0f,
        /** Coverage of the pixel immediately right of [solidRight] — that is, at [solidRight]. */
        public val rightCoverage: Float = 0f,
    ) {
        /** Whether the solid part is worth drawing. A row can be all edge and no middle. */
        public val hasSolid: Boolean get() = solidRight > solidLeft
    }

    /**
     * Slices [bounds] into fillable rows.
     *
     * Rows are returned top to bottom. The straight middle of the shape is one tall row rather than many
     * one-pixel ones, because nothing there needs anti-aliasing and a renderer should not pay per pixel for
     * a flat edge.
     */
    public fun rows(bounds: Rect, corners: Corners): List<Row> {
        if (bounds.isEmpty) return emptyList()

        val left = bounds.left
        val right = bounds.right
        val top = bounds.top.roundToInt()
        val bottom = bounds.bottom.roundToInt()

        // A radius cannot exceed half the shorter side, or opposite corners would cross and the shape would
        // turn inside out.
        val limit = min(bounds.width, bounds.height) / 2f
        val topLeft = clamp(corners.topLeft.value, limit)
        val topRight = clamp(corners.topRight.value, limit)
        val bottomLeft = clamp(corners.bottomLeft.value, limit)
        val bottomRight = clamp(corners.bottomRight.value, limit)

        val topBand = ceil(maxOf(topLeft, topRight)).toInt()
        val bottomBand = ceil(maxOf(bottomLeft, bottomRight)).toInt()

        val rows = ArrayList<Row>(topBand + bottomBand + 1)

        for (row in 0 until topBand) {
            rows += curvedRow(
                top = top + row,
                left = left,
                right = right,
                leftInset = arcInset(topLeft, row),
                rightInset = arcInset(topRight, row),
            )
        }

        // The middle, in one piece. Clamped so a shape shorter than its own corner bands does not produce a
        // negative-height row.
        val middleTop = top + topBand
        val middleBottom = bottom - bottomBand
        if (middleBottom > middleTop) {
            rows += Row(
                top = middleTop,
                bottom = middleBottom,
                solidLeft = ceil(left).toInt(),
                solidRight = floor(right).toInt(),
                leftCoverage = ceil(left) - left,
                rightCoverage = right - floor(right),
            )
        }

        // Walked inwards-out, so the rows still come out top to bottom. Counting from the bottom edge is
        // what mirrors the arc exactly; emitting in that order would hand back a list that runs backwards,
        // which nothing checking "do these tile" would survive.
        for (row in bottomBand - 1 downTo 0) {
            rows += curvedRow(
                top = bottom - row - 1,
                left = left,
                right = right,
                leftInset = arcInset(bottomLeft, row),
                rightInset = arcInset(bottomRight, row),
            )
        }

        return rows
    }

    private fun curvedRow(top: Int, left: Float, right: Float, leftInset: Float, rightInset: Float): Row {
        val boundaryLeft = left + leftInset
        val boundaryRight = right - rightInset

        val solidLeft = ceil(boundaryLeft).toInt()
        val solidRight = floor(boundaryRight).toInt()

        return Row(
            top = top,
            bottom = top + 1,
            solidLeft = solidLeft,
            solidRight = solidRight,
            // How much of the pixel the boundary cuts through lies inside the shape. Zero when the boundary
            // happens to land exactly on a pixel edge, which is the case where there is nothing to soften.
            leftCoverage = solidLeft - boundaryLeft,
            rightCoverage = boundaryRight - solidRight,
        )
    }

    /**
     * How far the arc is inset from the edge, at the vertical middle of a row.
     *
     * Measured at the row's *centre* rather than at either of its edges. Using an edge biases every curve by
     * half a pixel in one direction, which is visible as a corner that looks slightly too full or too flat —
     * the sort of thing that reads as "not quite right" without being identifiable.
     */
    public fun arcInset(radius: Float, rowFromEdge: Int): Float {
        if (radius <= 0f) return 0f
        val dy = radius - (rowFromEdge + HALF)
        if (dy <= -radius) return 0f
        val dx = sqrt((radius * radius - dy * dy).coerceAtLeast(0f))
        return (radius - dx).coerceAtLeast(0f)
    }

    private fun clamp(radius: Float, limit: Float): Float = min(radius, limit).coerceAtLeast(0f)

    private const val HALF = 0.5f
}
