package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.geometry.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * Where the profile window's pieces land.
 *
 * Worth testing rather than eyeballing because the failures are quiet: a search box that creeps under the
 * buttons still draws, a content area a pixel outside the frame still blits, and both look almost right at
 * the one window size somebody happened to try.
 */
class ProfileWindowLayoutTest {

    private val wide = Rect(0f, 0f, 800f, 450f)

    @Test
    fun `the window is inset from the screen`() {
        val layout = ProfileWindowLayout.of(wide, isMaximised = false)

        assertEquals(ProfileWindowLayout.MARGIN, layout.window.x)
        assertEquals(ProfileWindowLayout.MARGIN, layout.window.y)
        assertEquals(wide.width - ProfileWindowLayout.MARGIN * 2, layout.window.width)
        assertEquals(wide.height - ProfileWindowLayout.MARGIN * 2, layout.window.height)
    }

    @Test
    fun `maximised fills the screen exactly`() {
        val layout = ProfileWindowLayout.of(wide, isMaximised = true)

        assertEquals(wide, layout.window)
        // And the content still leaves room for the bar, or the chrome would draw over the page.
        assertEquals(layout.window.y + ProfileWindowLayout.BAR_HEIGHT, layout.content.y)
    }

    /**
     * The one that motivated splitting this out.
     *
     * The page is a rectangle with square corners drawn inside a frame with round ones. If the inset is
     * smaller than the arc, the page's corner pokes out through the curve — visible, ugly, and exactly the
     * sort of thing that is obvious in a screenshot and invisible in the code.
     */
    @Test
    fun `the content clears the frame's rounded corners`() {
        val layout = ProfileWindowLayout.of(wide, isMaximised = false)

        // The largest radius any shipped theme uses for a panel.
        val radius = 12f
        val minimum = radius * (1f - 1f / sqrt(2f))

        val inset = layout.content.x - layout.window.x
        assertTrue(inset >= minimum, "inset $inset does not clear a $radius radius (needs $minimum)")

        val bottomInset = layout.window.bottom - layout.content.bottom
        assertTrue(bottomInset >= minimum, "bottom inset $bottomInset does not clear a $radius radius")
    }

    @Test
    fun `the content sits inside the window`() {
        for (width in listOf(320f, 480f, 800f, 1920f)) {
            val layout = ProfileWindowLayout.of(Rect(0f, 0f, width, 450f), isMaximised = false)
            val content = layout.content
            val window = layout.window

            assertTrue(content.x >= window.x, "content escapes left at $width")
            assertTrue(content.right <= window.right + 0.01f, "content escapes right at $width")
            assertTrue(content.bottom <= window.bottom + 0.01f, "content escapes the bottom at $width")
            assertTrue(content.y >= layout.bar.bottom - 0.01f, "content overlaps the bar at $width")
        }
    }

    @Test
    fun `the buttons sit inside the bar and do not overlap`() {
        for (width in listOf(320f, 480f, 800f, 1920f)) {
            val layout = ProfileWindowLayout.of(Rect(0f, 0f, width, 450f), isMaximised = false)

            assertTrue(layout.close.right <= layout.bar.right, "close escapes the bar at $width")
            assertTrue(layout.close.y >= layout.bar.y, "close escapes the bar top at $width")
            assertTrue(layout.close.bottom <= layout.bar.bottom, "close escapes the bar bottom at $width")

            assertTrue(layout.expand.right <= layout.close.x, "expand overlaps close at $width")
            assertTrue(layout.search.right <= layout.expand.x, "search overlaps expand at $width")
        }
    }

    /**
     * The search box gives way rather than swallowing the bar.
     *
     * On a narrow window a fixed-width box would leave no room for the title, so it is capped as a fraction
     * of the bar. Checked at a genuinely small size because that is where it matters.
     */
    @Test
    fun `the search box is capped on a narrow window`() {
        val narrow = ProfileWindowLayout.of(Rect(0f, 0f, 320f, 450f), isMaximised = false)
        assertTrue(
            narrow.search.width <= narrow.bar.width * ProfileWindowLayout.SEARCH_MAX_FRACTION + 0.01f,
            "search took ${narrow.search.width} of a ${narrow.bar.width} bar",
        )
        assertTrue(narrow.search.x > narrow.bar.x, "search reached the left edge of the bar")
    }

    /** A window smaller than its own chrome must not produce negative sizes. */
    @Test
    fun `a tiny viewport does not produce negative rectangles`() {
        val tiny = ProfileWindowLayout.of(Rect(0f, 0f, 40f, 30f), isMaximised = false)

        assertTrue(tiny.content.width > 0f, "content width was ${tiny.content.width}")
        assertTrue(tiny.content.height > 0f, "content height was ${tiny.content.height}")
        assertFalse(tiny.window.width.isNaN())
    }
}
