package dev.th7bo.sidequest.ui.components.background

import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.testkit.DrawCommand
import dev.th7bo.sidequest.ui.testkit.RecordingRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The title screen's sky.
 *
 * Nothing here can tell whether it *looks* like a nebula — that needs eyes on a screenshot. What it can tell
 * is everything that would make it look wrong for a structural reason: gaps in the coverage, a field that
 * reshuffles between visits, motion that a reduced-motion setting failed to stop, and colours outside the
 * palette somebody chose.
 */
class NebulaPainterTest {

    private val bounds = Rect(0f, 0f, 480f, 270f)

    private fun paint(time: Float, palette: NebulaPalette = NebulaPalette.Default): RecordingRenderer {
        val renderer = RecordingRenderer(Size(bounds.width, bounds.height))
        renderer.beginFrame()
        NebulaPainter.paint(renderer, bounds, time, palette)
        renderer.endFrame()
        return renderer
    }

    private fun RecordingRenderer.fills() = commandsOfType<DrawCommand.FillRect>()

    /**
     * The cloud cells, without the backdrop or the stars.
     *
     * Told apart by size, which is the only thing that reliably separates them: a star is a couple of units
     * across and a cell is most of ten. Filtering by colour would work today and break the moment somebody
     * picks a pale palette.
     */
    private fun RecordingRenderer.cells() = fills().drop(1).filter { it.bounds.width > CELL_FLOOR }

    // -- coverage ------------------------------------------------------------

    /**
     * The backdrop goes down first and covers everything.
     *
     * The one thing that must hold whatever the noise does: a cell the field decided was empty draws nothing,
     * and without a base underneath it that is a hole through to whatever the game drew before.
     */
    @Test
    fun `the whole area is covered before anything else`() {
        val first = paint(time = 0f).fills().first()

        assertEquals(bounds, first.bounds)
        assertEquals(NebulaPalette.Default.deep, first.color)
    }

    /** Cells overlap by a fraction, so there is no hairline of backdrop showing through between them. */
    @Test
    fun `neighbouring cells overlap rather than abut`() {
        val cells = paint(time = 0f).cells()
        val widths = cells.map { it.bounds.width }.distinct()

        assertTrue(cells.isNotEmpty(), "the field drew nothing at all")
        // Every cell is one unit wider than the grid spacing it sits on, which is what closes the seam.
        val columns = cells.map { it.bounds.x }.distinct().size
        assertTrue(
            widths.all { it > bounds.width / columns },
            "a cell should be wider than its share of $columns columns, widths were $widths",
        )
    }

    // -- determinism ---------------------------------------------------------

    /**
     * The same sky every time.
     *
     * A field seeded from a random source would reshuffle on every visit to the title screen, which anybody
     * would notice and read as a fault rather than as stars.
     */
    @Test
    fun `the same time paints the same picture`() {
        val once = paint(time = 12.5f).fills()
        val again = paint(time = 12.5f).fills()

        assertEquals(once, again)
    }

    /**
     * Passing the same time freezes it, which is the whole of reduced-motion support.
     *
     * Deliberately not a second code path. A `if (reducedMotion)` branch inside the painter would be a
     * branch nobody exercises and one that quietly stops matching the animated one.
     */
    @Test
    fun `holding the time still holds the picture still`() {
        val frame = paint(time = 3f).fills()
        val later = paint(time = 3f).fills()

        assertEquals(frame, later, "nothing may move when the clock does not")
    }

    @Test
    fun `advancing the time changes the picture`() {
        val early = paint(time = 0f).fills()
        val late = paint(time = 30f).fills()

        assertNotEquals(early, late)
    }

    // -- the palette ---------------------------------------------------------

    /**
     * Every cloud colour lies between the two palette colours it is mixed from.
     *
     * The check that a chosen palette is actually honoured. A painter that reached for a hard-coded purple
     * anywhere would pass every other test here and ignore the setting.
     */
    @Test
    fun `cloud colours stay between the palette's own`() {
        val palette = NebulaPalette(
            deep = Color(0xFF001000.toInt()),
            cloud = Color(0xFF0A6020.toInt()),
            highlight = Color(0xFF30FF80.toInt()),
        )

        val clouds = paint(time = 4f, palette).cells()

        assertTrue(clouds.isNotEmpty())
        for (cell in clouds) {
            val red = (cell.color.argb ushr 16) and 0xFF
            val green = (cell.color.argb ushr 8) and 0xFF
            val blue = cell.color.argb and 0xFF
            assertTrue(red <= 0x30, "red should never exceed the palette's, was ${cell.color.toHexString()}")
            assertTrue(green in 0x60..0xFF, "green out of range: ${cell.color.toHexString()}")
            assertTrue(blue in 0x20..0x80, "blue out of range: ${cell.color.toHexString()}")
        }
    }

    // -- the field itself ----------------------------------------------------

    /** Density is a fraction. Anything outside it would map to a colour outside the palette. */
    @Test
    fun `density stays within its range`() {
        for (step in 0..40) {
            val u = step / 40f
            for (vStep in 0..40) {
                val value = NebulaPainter.density(u, vStep / 40f, time = step.toFloat())
                assertTrue(value in 0f..1f, "density $value at ($u, ${vStep / 40f})")
            }
        }
    }

    /**
     * There is empty sky as well as cloud.
     *
     * A field that came out uniformly dense would technically pass everything above and look like a flat
     * wash. The floor in the painter is what stops that, and this is the assertion that it is doing anything.
     */
    @Test
    fun `the field has both empty and dense regions`() {
        val samples = (0..60).flatMap { x ->
            (0..40).map { y -> NebulaPainter.density(x / 60f, y / 40f, time = 7f) }
        }

        assertTrue(samples.any { it == 0f }, "nothing was empty sky")
        assertTrue(samples.any { it > 0.5f }, "nothing was dense cloud, max was ${samples.max()}")
    }

    private companion object {
        /** Wider than any star, narrower than any cell. See [cells]. */
        const val CELL_FLOOR = 5f
    }
}
