package dev.th7bo.sidequest.platform.core.afk

import kotlin.math.abs
import kotlin.time.Duration

/**
 * Where the player is and which way they are facing, sampled.
 *
 * Position *and* rotation, because either alone misses half of what somebody at the keyboard does: reading
 * chat without moving still turns the view, and standing on a piston still moves without anybody touching
 * anything. Both together are the closest honest reading of "there is a person here" the client can take
 * without a mixin on every input path.
 */
public data class CameraPose(
    public val x: Double,
    public val y: Double,
    public val z: Double,
    public val yaw: Float,
    public val pitch: Float,
)

/**
 * How long it has been since anybody did anything.
 *
 * The half of an AFK feature that has to be right. Starting a camera is easy; the failure everybody has met
 * is the one that decides somebody is away while they are reading a trade window, or that never notices they
 * came back. So this answers exactly one question — when was the last sign of life — and the thresholds are
 * deliberately generous in the direction of "still here".
 *
 * Time is passed in rather than read, so three minutes of idling costs a test nothing.
 */
public class IdleWatch(
    /** How far the player must move to count as having moved. */
    private val moved: Double = DEFAULT_MOVED,
    /** How far the view must turn to count as having turned, in degrees. */
    private val turned: Float = DEFAULT_TURNED,
) {

    private var last: CameraPose? = null

    private var lastActive: Duration = Duration.ZERO

    /**
     * Records a sample, and says whether it was a sign of life.
     *
     * A null pose — no player, so a loading screen or the title screen — counts as activity rather than as
     * idling. Time spent with no world is not time spent away from the keyboard, and counting it would start
     * a camera the moment somebody finished loading in.
     */
    public fun observe(pose: CameraPose?, now: Duration): Boolean {
        val previous = last
        last = pose

        if (pose == null || previous == null) {
            stir(now)
            return true
        }

        val dx = pose.x - previous.x
        val dy = pose.y - previous.y
        val dz = pose.z - previous.z
        val active = dx * dx + dy * dy + dz * dz > moved * moved ||
            abs(difference(pose.yaw, previous.yaw)) > turned ||
            abs(pose.pitch - previous.pitch) > turned

        if (active) stir(now)
        return active
    }

    /** Records a sign of life that is not a pose — a broken block, a message sent, a screen opened. */
    public fun stir(now: Duration) {
        lastActive = now
    }

    public fun idleFor(now: Duration): Duration = now - lastActive

    public fun hasBeenIdleFor(threshold: Duration, now: Duration): Boolean = idleFor(now) >= threshold

    /** The shorter way round between two yaws, so 359 to 1 is two degrees and not three hundred and fifty. */
    private fun difference(a: Float, b: Float): Float {
        var delta = (a - b) % 360f
        if (delta >= 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    public companion object {
        /**
         * How far counts as moving.
         *
         * Small, but not zero: a standing player's position wobbles by fractions of a block from collision
         * resolution alone, and a zero threshold would report constant movement and never call anybody away.
         */
        public const val DEFAULT_MOVED: Double = 0.05

        /**
         * How far the view must turn, in degrees.
         *
         * A fifth of a degree. Below what a hand resting on a mouse produces and far below anything
         * deliberate, which is the balance that matters — a threshold too tight never goes idle at all, and
         * one too loose lets a slow drag through.
         */
        public const val DEFAULT_TURNED: Float = 0.2f
    }
}
