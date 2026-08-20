package dev.th7bo.sidequest.platform.core.garden

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long the player has been farming without stopping.
 *
 * A count of blocks rather than a stopwatch, because that is what distinguishes farming from everything else
 * somebody does with a block: breaking one is clearing a path, breaking four hundred is a run. The count only
 * means anything while it is *continuous*, so a gap resets it — otherwise a session's worth of stray blocks
 * eventually adds up to a threshold that was meant to describe a farming run.
 *
 * Time is passed in rather than read, so the reset can be tested without waiting for it.
 */
public class FarmingStreak(
    /**
     * How long a gap has to be before the streak is considered over.
     *
     * Generous next to the gap between two crops, which at farming speed is a fraction of a second, and
     * short next to anything that is not farming. It has to survive turning at the end of a row.
     */
    private val idle: Duration = DEFAULT_IDLE,
) {
    private var count: Int = 0
    private var lastBreak: Duration? = null

    /** Blocks broken in the current run. Zero once the run is over. */
    public fun blocks(now: Duration): Int {
        if (hasLapsed(now)) reset()
        return count
    }

    /** Whether the run has reached [threshold] blocks and is still going. */
    public fun hasReached(threshold: Int, now: Duration): Boolean = blocks(now) >= threshold

    /** Records a broken block. */
    public fun record(now: Duration) {
        if (hasLapsed(now)) reset()
        count++
        lastBreak = now
    }

    /** Ends the run, whatever it was. For leaving the island, or for the feature standing down. */
    public fun reset() {
        count = 0
        lastBreak = null
    }

    private fun hasLapsed(now: Duration): Boolean {
        val last = lastBreak ?: return false
        return now - last >= idle
    }

    public companion object {
        public val DEFAULT_IDLE: Duration = 3.seconds
    }
}
