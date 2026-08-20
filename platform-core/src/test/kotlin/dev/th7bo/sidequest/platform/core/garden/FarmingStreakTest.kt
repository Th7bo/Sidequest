package dev.th7bo.sidequest.platform.core.garden

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Telling a farming run from somebody breaking a block.
 *
 * The counting is trivial; the resetting is not, and it is the whole reason this is a class rather than an
 * integer. A count that never lapses turns a day of stray blocks into a "run" eventually, which is the one
 * way this feature could switch a camera on for somebody who is not farming.
 */
class FarmingStreakTest {

    private fun streak(idle: Duration = 3.seconds) = FarmingStreak(idle)

    @Test
    fun `blocks broken in a row are counted`() {
        val streak = streak()
        var now = Duration.ZERO
        repeat(5) { streak.record(now); now += 200.milliseconds }

        assertEquals(5, streak.blocks(now))
        assertTrue(streak.hasReached(5, now))
        assertFalse(streak.hasReached(6, now))
    }

    /** Turning at the end of a row is a pause, not the end of the run. */
    @Test
    fun `a short gap does not end the run`() {
        val streak = streak(idle = 3.seconds)
        streak.record(Duration.ZERO)
        streak.record(2.seconds)

        assertEquals(2, streak.blocks(2.seconds))
    }

    @Test
    fun `a long gap ends it`() {
        val streak = streak(idle = 3.seconds)
        streak.record(Duration.ZERO)

        assertEquals(0, streak.blocks(3.seconds), "the gap is inclusive: three seconds is over")
        assertEquals(0, streak.blocks(10.seconds))
    }

    /**
     * The next block starts a new run rather than extending the old one.
     *
     * Without this, a hundred blocks yesterday and one today is a hundred and one, and the threshold means
     * nothing.
     */
    @Test
    fun `breaking a block after a gap starts again from one`() {
        val streak = streak(idle = 3.seconds)
        repeat(50) { streak.record(Duration.ZERO) }

        streak.record(1.minutes)
        assertEquals(1, streak.blocks(1.minutes))
    }

    @Test
    fun `a run can be ended outright`() {
        val streak = streak()
        repeat(10) { streak.record(Duration.ZERO) }
        streak.reset()

        assertEquals(0, streak.blocks(Duration.ZERO))
    }

    @Test
    fun `nothing broken is not a run`() {
        assertEquals(0, streak().blocks(1.minutes))
        assertFalse(streak().hasReached(1, 1.minutes))
    }

    private val Int.minutes get() = (this * 60).seconds
}
