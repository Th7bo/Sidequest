package dev.th7bo.sidequest.ui.core.rendering

import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.rendering.Corners
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
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

    /** A plain rectangle to fill, in whole pixels. */
    public data class Piece(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        public val isEmpty: Boolean get() = right <= left || bottom <= top
    }

    /** One corner's arc: where it goes, how big, and which quadrant of the disc it is. */
    public data class Arc(
        val left: Int,
        val top: Int,
        val radius: Int,
        /** 0 for the left half of the disc, 1 for the right. */
        val quadrantX: Int,
        /** 0 for the top half, 1 for the bottom. */
        val quadrantY: Int,
    )

    /** A rounded rectangle as flat rectangles plus corner arcs. */
    public data class Decomposition(val fills: List<Piece>, val arcs: List<Arc>)

    /**
     * Cuts a rounded rectangle into pieces a renderer can draw in one call each.
     *
     * The fast path. [rows] is honest and costs a quad per display pixel of curve, which measured at about
     * two hundred and fifty for one panel and cost the frame rate; this is at most eleven pieces, because a
     * curve that does not change between frames has no business being recomputed in one.
     *
     * The rectangles and the arc squares **tile the shape exactly** — no gap, no overlap. That is the whole
     * risk of decomposing a shape by hand: a one-pixel gap is a visible seam and an overlap is a darker line
     * wherever the fill is translucent, and both are the sort of thing that survives code review.
     */
    public fun decompose(bounds: Rect, corners: Corners): Decomposition {
        if (bounds.isEmpty) return Decomposition(emptyList(), emptyList())

        val left = bounds.left.roundToInt()
        val top = bounds.top.roundToInt()
        val right = bounds.right.roundToInt()
        val bottom = bounds.bottom.roundToInt()

        val limit = min(bounds.width, bounds.height) / 2f
        val topLeft = whole(corners.topLeft.value, limit)
        val topRight = whole(corners.topRight.value, limit)
        val bottomLeft = whole(corners.bottomLeft.value, limit)
        val bottomRight = whole(corners.bottomRight.value, limit)

        val topBand = max(topLeft, topRight)
        val bottomBand = max(bottomLeft, bottomRight)

        val fills = ArrayList<Piece>(PIECE_CAPACITY)

        // The middle, full width, whatever the height.
        fills += Piece(left, top + topBand, right, bottom - bottomBand)

        // Each band is the strip between its two corners, plus whatever sits below a corner shorter than the
        // band. Two different radii on one edge is not unusual — it is every card that rounds only its top.
        fills += Piece(left + topLeft, top, right - topRight, top + topBand)
        fills += Piece(left, top + topLeft, left + topLeft, top + topBand)
        fills += Piece(right - topRight, top + topRight, right, top + topBand)

        fills += Piece(left + bottomLeft, bottom - bottomBand, right - bottomRight, bottom)
        fills += Piece(left, bottom - bottomBand, left + bottomLeft, bottom - bottomLeft)
        fills += Piece(right - bottomRight, bottom - bottomBand, right, bottom - bottomRight)

        val arcs = ArrayList<Arc>(CORNERS)
        if (topLeft > 0) arcs += Arc(left, top, topLeft, 0, 0)
        if (topRight > 0) arcs += Arc(right - topRight, top, topRight, 1, 0)
        if (bottomLeft > 0) arcs += Arc(left, bottom - bottomLeft, bottomLeft, 0, 1)
        if (bottomRight > 0) arcs += Arc(right - bottomRight, bottom - bottomRight, bottomRight, 1, 1)

        return Decomposition(fills.filterNot { it.isEmpty }, arcs)
    }

    /**
     * Cuts an *outline* into pieces: four straight bars and the corner arcs between them.
     *
     * Eight draws instead of several per display-pixel row of curve. Borders were the last thing still
     * rasterising every frame and had become the most expensive shape on the screen — an outline cost more
     * than the panel it surrounded, and every glyph drawn as a ring paid it too.
     *
     * The bars stop where the corner squares begin, so nothing is drawn twice. That matters more here than
     * for a fill: a stroke is usually translucent, and overlapping it produces a visibly darker patch at
     * each corner rather than a harmless double-paint.
     */
    public fun decomposeStroke(bounds: Rect, corners: Corners, strokeWidth: Float): Decomposition {
        if (bounds.isEmpty) return Decomposition(emptyList(), emptyList())

        val thickness = max(1, strokeWidth.roundToInt())
        val left = bounds.left.roundToInt()
        val top = bounds.top.roundToInt()
        val right = bounds.right.roundToInt()
        val bottom = bounds.bottom.roundToInt()

        val limit = min(bounds.width, bounds.height) / 2f
        val topLeft = whole(corners.topLeft.value, limit)
        val topRight = whole(corners.topRight.value, limit)
        val bottomLeft = whole(corners.bottomLeft.value, limit)
        val bottomRight = whole(corners.bottomRight.value, limit)

        // The horizontal bars own the full span between their corners; the vertical ones stop where those
        // bars begin. Without the `max`, a *square* corner has the vertical bar running under the horizontal
        // one — and since a stroke is translucent, that overlap is a visibly darker patch at each corner.
        val verticalTop = { radius: Int -> top + max(radius, thickness) }
        val verticalBottom = { radius: Int -> bottom - max(radius, thickness) }

        val fills = listOf(
            Piece(left + topLeft, top, right - topRight, top + thickness),
            Piece(left + bottomLeft, bottom - thickness, right - bottomRight, bottom),
            Piece(left, verticalTop(topLeft), left + thickness, verticalBottom(bottomLeft)),
            Piece(right - thickness, verticalTop(topRight), right, verticalBottom(bottomRight)),
        ).filterNot { it.isEmpty }

        val arcs = ArrayList<Arc>(CORNERS)
        if (topLeft > 0) arcs += Arc(left, top, topLeft, 0, 0)
        if (topRight > 0) arcs += Arc(right - topRight, top, topRight, 1, 0)
        if (bottomLeft > 0) arcs += Arc(left, bottom - bottomLeft, bottomLeft, 0, 1)
        if (bottomRight > 0) arcs += Arc(right - bottomRight, bottom - bottomRight, bottomRight, 1, 1)

        return Decomposition(fills, arcs)
    }

    private fun whole(radius: Float, limit: Float): Int = min(radius, limit).coerceAtLeast(0f).roundToInt()

    /**
     * The rows indexed by every scanline they cover.
     *
     * For anything that has to ask "what is the shape doing at height *y*" — an outline, which is one shape
     * minus another. Keying rows by their own `top` is the obvious thing and it is wrong: the straight middle
     * is deliberately returned as a *single tall row*, so a lookup by `top` finds it at one y and misses it
     * at every other. An outline built that way fills solid down its whole middle and stops being an
     * outline, which is exactly what happened.
     */
    public fun byScanline(rows: List<Row>): Map<Int, Row> {
        val byY = HashMap<Int, Row>()
        for (row in rows) for (y in row.top until row.bottom) byY[y] = row
        return byY
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

    /** One middle, three across the top, three across the bottom. */
    private const val PIECE_CAPACITY = 7

    private const val CORNERS = 4

}
