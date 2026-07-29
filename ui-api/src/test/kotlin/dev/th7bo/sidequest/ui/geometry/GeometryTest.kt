package dev.th7bo.sidequest.ui.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeometryTest {

    @Test
    fun `contains is half-open so adjacent rects never both claim a point`() {
        val left = Rect(0f, 0f, 10f, 10f)
        val right = Rect(10f, 0f, 10f, 10f)
        val boundary = Vec2(10f, 5f)

        assertFalse(boundary in left, "the right edge is exclusive")
        assertTrue(boundary in right, "the left edge is inclusive")
    }

    @Test
    fun `intersect of disjoint rects is empty rather than negative`() {
        val a = Rect(0f, 0f, 10f, 10f)
        val b = Rect(50f, 50f, 10f, 10f)

        val result = a.intersect(b)
        assertTrue(result.isEmpty)
        assertEquals(0f, result.width)
        assertEquals(0f, result.height)
    }

    @Test
    fun `intersect keeps the overlapping region`() {
        val a = Rect(0f, 0f, 20f, 20f)
        val b = Rect(10f, 10f, 20f, 20f)

        assertEquals(Rect(10f, 10f, 10f, 10f), a.intersect(b))
    }

    @Test
    fun `union of an empty rect returns the other`() {
        val real = Rect(5f, 5f, 10f, 10f)
        assertEquals(real, Rect.Zero.union(real))
        assertEquals(real, real.union(Rect.Zero))
    }

    @Test
    fun `overlaps excludes merely touching rects`() {
        val a = Rect(0f, 0f, 10f, 10f)
        assertFalse(a.overlaps(Rect(10f, 0f, 10f, 10f)))
        assertTrue(a.overlaps(Rect(9f, 0f, 10f, 10f)))
    }

    @Test
    fun `inset clamps at zero instead of inverting`() {
        val small = Rect(0f, 0f, 4f, 4f)
        val result = small.inset(Insets(10f, 10f, 10f, 10f))

        assertEquals(0f, result.width)
        assertEquals(0f, result.height)
    }

    @Test
    fun `constraints reject an inverted range`() {
        assertThrows(IllegalArgumentException::class.java) {
            Constraints(minWidth = 100f, maxWidth = 50f)
        }
    }

    @Test
    fun `constrain clamps into the allowed range`() {
        val constraints = Constraints(10f, 100f, 20f, 200f)

        assertEquals(Size(10f, 20f), constraints.constrain(Size(0f, 0f)))
        assertEquals(Size(100f, 200f), constraints.constrain(Size(500f, 500f)))
        assertEquals(Size(50f, 50f), constraints.constrain(Size(50f, 50f)))
    }

    @Test
    fun `deflate leaves an unbounded axis unbounded`() {
        val constraints = Constraints(maxWidth = 100f)
        val deflated = constraints.deflate(Insets(10f, 10f, 10f, 10f))

        assertEquals(80f, deflated.maxWidth)
        assertTrue(deflated.hasBoundedWidth)
        assertFalse(deflated.hasBoundedHeight, "an unbounded height must stay unbounded")
    }

    @Test
    fun `deflate never produces a negative maximum`() {
        val constraints = Constraints(maxWidth = 5f, maxHeight = 5f)
        val deflated = constraints.deflate(Insets(20f, 20f, 20f, 20f))

        assertEquals(0f, deflated.maxWidth)
        assertEquals(0f, deflated.maxHeight)
    }

    @Test
    fun `size rejects negative dimensions`() {
        assertThrows(IllegalArgumentException::class.java) { Size(-1f, 0f) }
    }

    @Test
    fun `alignment places a child inside spare space`() {
        val child = Size(20f, 10f)
        val container = Size(100f, 50f)

        assertEquals(Vec2(0f, 0f), Alignment.TopStart.align(child, container))
        assertEquals(Vec2(40f, 20f), Alignment.Center.align(child, container))
        assertEquals(Vec2(80f, 40f), Alignment.BottomEnd.align(child, container))
    }

    @Test
    fun `dp arithmetic stays in dp`() {
        assertEquals(12.dp, 8.dp + 4.dp)
        assertEquals(4.dp, 8.dp - 4.dp)
        assertEquals(16.dp, 8.dp * 2f)
        assertTrue(4.dp < 8.dp)
    }

    @Test
    fun `insets helpers build the expected edges`() {
        assertEquals(Insets(8f, 8f, 8f, 8f), Insets.all(8.dp))
        assertEquals(Insets(8f, 4f, 8f, 4f), Insets.symmetric(horizontal = 8.dp, vertical = 4.dp))
        assertEquals(16f, Insets.all(8.dp).horizontal)
    }
}
