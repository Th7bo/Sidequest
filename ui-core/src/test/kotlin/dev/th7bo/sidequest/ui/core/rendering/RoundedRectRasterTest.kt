package dev.th7bo.sidequest.ui.core.rendering

import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.dp
import dev.th7bo.sidequest.ui.rendering.Corners
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Slicing a rounded rectangle into rows a renderer can fill.
 *
 * The reason this is worth testing at all is that its output is the difference between a panel that looks
 * rounded and one that looks like pixel art. A staircase and a smooth curve are the same *rows* — they
 * differ only in whether the edge pixels carry partial coverage, which is exactly the sort of thing that is
 * easy to get subtly wrong and impossible to notice in code review.
 */
class RoundedRectRasterTest {

    private fun rect(width: Float, height: Float) = Rect(0f, 0f, width, height)

    private fun rows(width: Float = 40f, height: Float = 20f, radius: Float = 6f) =
        RoundedRectRaster.rows(rect(width, height), Corners.all(radius.dp))

    // -- the shape it covers --------------------------------------------------

    @Test
    fun `an empty rectangle produces nothing`() {
        assertTrue(RoundedRectRaster.rows(Rect(0f, 0f, 0f, 0f), Corners.all(4.dp)).isEmpty())
    }

    /** No radius is a plain rectangle, and should cost exactly one row. */
    @Test
    fun `a square rectangle is a single row`() {
        val rows = RoundedRectRaster.rows(rect(40f, 20f), Corners.all(0.dp))

        assertEquals(1, rows.size)
        assertEquals(0, rows.single().solidLeft)
        assertEquals(40, rows.single().solidRight)
        assertEquals(0, rows.single().top)
        assertEquals(20, rows.single().bottom)
    }

    /** The rows together cover the full height, with no gap and no overlap. */
    @Test
    fun `the rows tile the shape exactly`() {
        val rows = rows()

        assertEquals(0, rows.first().top)
        assertEquals(20, rows.last().bottom)
        for (index in 1 until rows.size) {
            assertEquals(rows[index - 1].bottom, rows[index].top, "gap or overlap before row $index")
        }
    }

    /** The straight middle is one tall row, not one per pixel — nothing there needs softening. */
    @Test
    fun `the middle is a single row`() {
        val rows = rows(height = 40f, radius = 6f)

        val tall = rows.filter { it.bottom - it.top > 1 }
        assertEquals(1, tall.size)
        assertEquals(6, tall.single().top)
        assertEquals(34, tall.single().bottom)
    }

    // -- the curve ------------------------------------------------------------

    /**
     * The corner narrows towards the top.
     *
     * A curve, not a bevel: the inset has to shrink row by row, and the first row has to be inset by most
     * of the radius rather than by all of it — the arc has already started curving away by the middle of
     * that pixel.
     */
    @Test
    fun `the corner is inset most at the edge and least at the band's end`() {
        val insets = (0 until 6).map { RoundedRectRaster.arcInset(6f, it) }

        assertTrue(insets.zipWithNext().all { (outer, inner) -> outer > inner }, "not monotonic: $insets")
        assertTrue(insets.first() > 2f, "the outermost row should be well inside: ${insets.first()}")
        assertTrue(insets.last() < 0.5f, "the innermost row should be nearly flush: ${insets.last()}")
    }

    /** Measured at the row's centre. Half a pixel of bias here reads as a corner that is subtly wrong. */
    @Test
    fun `the arc is sampled at the middle of a row`() {
        // At radius 4, row 3 has its centre 0.5 above the circle's centre line, so the inset is
        // 4 - sqrt(16 - 0.25) ~= 0.031. Anything sampling the row's edge would answer 0.
        val inset = RoundedRectRaster.arcInset(4f, 3)

        assertTrue(inset > 0.01f && inset < 0.1f, "expected a small non-zero inset, got $inset")
    }

    @Test
    fun `no radius means no inset`() {
        assertEquals(0f, RoundedRectRaster.arcInset(0f, 0))
    }

    // -- the anti-aliasing ----------------------------------------------------

    /**
     * The corner rows carry partial coverage, which is the whole point.
     *
     * Without this every row would land on a whole pixel and the corner would be a staircase. A test that
     * only checked the insets would pass against exactly that.
     */
    @Test
    fun `corner rows have partially covered edge pixels`() {
        val corner = rows().take(6)

        assertTrue(
            corner.any { it.leftCoverage > 0.05f && it.leftCoverage < 0.95f },
            "no row was partially covered: ${corner.map { it.leftCoverage }}",
        )
    }

    /** Coverage is a fraction of one pixel and can never exceed it. */
    @Test
    fun `coverage stays within a single pixel`() {
        for (row in rows(radius = 9f)) {
            assertTrue(row.leftCoverage in 0f..1f, "left ${row.leftCoverage}")
            assertTrue(row.rightCoverage in 0f..1f, "right ${row.rightCoverage}")
        }
    }

    /** A rectangle on whole coordinates has nothing to soften along its straight edges. */
    @Test
    fun `a pixel aligned middle needs no coverage`() {
        val middle = rows(height = 40f).single { it.bottom - it.top > 1 }

        assertEquals(0f, middle.leftCoverage)
        assertEquals(0f, middle.rightCoverage)
    }

    /** A shape at a fractional position softens its straight edges too, rather than snapping. */
    @Test
    fun `a fractional edge is softened rather than snapped`() {
        val rows = RoundedRectRaster.rows(Rect(10.5f, 0f, 40f, 20f), Corners.all(0.dp))

        assertEquals(0.5f, rows.single().leftCoverage, 0.001f)
        assertEquals(11, rows.single().solidLeft, "the solid part starts at the first whole pixel inside")
    }

    // -- what it costs to draw ------------------------------------------------

    /**
     * A tall shape does not cost more than a short one.
     *
     * The property that the frame rate depends on. These rows are *display* pixels — three times as many as
     * they look — so anything proportional to a panel's height becomes thousands of draws for one shape. A
     * full-height panel and a small button must slice into the same number of rows, because the only thing
     * that varies is the corners.
     */
    @Test
    fun `row count follows the radius, not the height`() {
        val short = RoundedRectRaster.rows(rect(200f, 30f), Corners.all(9.dp))
        val tall = RoundedRectRaster.rows(rect(200f, 900f), Corners.all(9.dp))

        assertEquals(short.size, tall.size, "a taller shape produced more rows")
    }

    // -- the cheap decomposition ----------------------------------------------

    /**
     * The pieces cover the shape exactly: no pixel twice, no pixel missed.
     *
     * The whole risk of cutting a shape up by hand. A gap is a visible seam; an overlap is a darker line
     * wherever the fill is translucent, which every panel here is. Both survive code review easily, and both
     * are obvious the moment something counts the pixels — so this counts them.
     */
    @Test
    fun `the pieces tile the shape with no gap and no overlap`() {
        for (corners in listOf(
            Corners.all(9.dp),
            Corners.top(14.dp),
            Corners.bottom(14.dp),
            Corners(topLeft = 12.dp, topRight = 4.dp, bottomRight = 0.dp, bottomLeft = 8.dp),
            Corners.None,
        )) {
            val bounds = rect(64f, 48f)
            val decomposition = RoundedRectRaster.decompose(bounds, corners)

            val painted = Array(48) { IntArray(64) }
            for (piece in decomposition.fills) {
                for (y in piece.top until piece.bottom) for (x in piece.left until piece.right) painted[y][x]++
            }
            for (arc in decomposition.arcs) {
                for (y in arc.top until arc.top + arc.radius) {
                    for (x in arc.left until arc.left + arc.radius) painted[y][x]++
                }
            }

            for (y in 0 until 48) {
                for (x in 0 until 64) {
                    assertEquals(1, painted[y][x], "pixel ($x,$y) painted ${painted[y][x]} times for $corners")
                }
            }
        }
    }

    /** Every corner with a radius gets exactly one arc, and a square corner gets none. */
    @Test
    fun `each rounded corner produces one arc`() {
        val decomposition = RoundedRectRaster.decompose(
            rect(64f, 48f),
            Corners(topLeft = 10.dp, topRight = 0.dp, bottomRight = 6.dp, bottomLeft = 0.dp),
        )

        assertEquals(2, decomposition.arcs.size)
        assertEquals(setOf(10, 6), decomposition.arcs.map { it.radius }.toSet())
        // The quadrant says which part of the disc to sample; getting it wrong mirrors a corner.
        val topLeftArc = decomposition.arcs.single { it.radius == 10 }
        assertEquals(0, topLeftArc.quadrantX)
        assertEquals(0, topLeftArc.quadrantY)
    }

    /** A square rectangle needs no arcs at all, and should not pay for any. */
    @Test
    fun `a square rectangle decomposes to one piece`() {
        val decomposition = RoundedRectRaster.decompose(rect(64f, 48f), Corners.None)

        assertTrue(decomposition.arcs.isEmpty())
        assertEquals(1, decomposition.fills.size)
    }

    /** The piece count stays small, which is the entire reason this path exists. */
    @Test
    fun `a rounded rectangle is a handful of pieces`() {
        val decomposition = RoundedRectRaster.decompose(rect(300f, 900f), Corners.all(42.dp))

        assertTrue(decomposition.fills.size + decomposition.arcs.size <= 11, "too many pieces: $decomposition")
    }

    // -- looking a shape up by height -----------------------------------------

    /**
     * Every scanline finds a row, including the ones inside the tall middle.
     *
     * The bug this exists for: keying rows by their own `top` is the obvious thing, and it silently loses
     * the entire middle of the shape, because that is returned as one tall row on purpose. An outline built
     * on such a lookup fills solid all the way down and stops being an outline — which is what shipped, and
     * what a rendered preview caught in about a second.
     */
    @Test
    fun `every scanline of the shape can be looked up`() {
        val rows = rows(height = 40f, radius = 6f)

        val byY = RoundedRectRaster.byScanline(rows)

        for (y in 0 until 40) {
            assertTrue(byY.containsKey(y), "no row covers scanline $y")
        }
        assertEquals(40, byY.size)
    }

    /** A scanline inside the middle band resolves to that band, not to nothing. */
    @Test
    fun `the middle band is found from anywhere inside it`() {
        val byY = RoundedRectRaster.byScanline(rows(height = 40f, radius = 6f))

        val middle = byY.getValue(20)
        assertTrue(middle.bottom - middle.top > 1, "expected the tall middle row, got $middle")
        assertEquals(middle, byY.getValue(21), "adjacent scanlines share the band")
    }

    // -- the awkward shapes ---------------------------------------------------

    /**
     * A radius larger than the shape is clamped rather than inverting it.
     *
     * Asking for a pill by passing a huge radius is a thing callers do — `RadiusTokens.pill` is 999 — and
     * it must produce a capsule, not a shape whose corners have crossed over each other.
     */
    @Test
    fun `an oversized radius is clamped to a capsule`() {
        val rows = RoundedRectRaster.rows(rect(40f, 20f), Corners.all(999.dp))

        assertEquals(20, rows.sumOf { it.bottom - it.top }, "the shape still fills its own height")
        for (row in rows) {
            assertTrue(row.solidRight >= row.solidLeft, "row inverted: $row")
        }
    }

    /** Corners can differ, and a row spanning two different radii uses each on its own side. */
    @Test
    fun `each corner keeps its own radius`() {
        val rows = RoundedRectRaster.rows(
            rect(40f, 20f),
            Corners(topLeft = 8.dp, topRight = 0.dp, bottomLeft = 0.dp, bottomRight = 8.dp),
        )

        val first = rows.first()
        assertTrue(first.solidLeft > 3, "the rounded side should be well inset: $first")
        assertEquals(40, first.solidRight, "the square side should be flush")
    }

    /** A shape shorter than its corner bands still tiles, rather than producing a negative middle. */
    @Test
    fun `a shape smaller than its radius still tiles`() {
        val rows = RoundedRectRaster.rows(rect(6f, 4f), Corners.all(10.dp))

        assertEquals(4, rows.sumOf { it.bottom - it.top })
        assertFalse(rows.any { it.bottom < it.top })
    }
}
