package dev.th7bo.sidequest.platform.core.afk

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where the camera is pointing during a shot.
 *
 * The two angles mean different things on purpose:
 *
 * - [yaw] is **relative to the player's facing**, so a shot named "front" is in front of them whichever way
 *   they happen to have parked. Absolute yaw would make every shot a lottery.
 * - [pitch] is **absolute**, because a high shot has to be a high shot. Somebody who went away staring at the
 *   sky would otherwise get every shot pointed thirty degrees further up than it was drawn.
 *
 * With the camera in third person, pointing it *down* puts it *above* the player — Minecraft places the
 * third-person camera opposite the view direction — so this reads the way a storyboard would.
 */
public data class ShotFrame(
    /** Degrees added to the player's yaw. Zero is directly behind them. */
    public val yaw: Float = 0f,
    /** Where the camera looks, in degrees. Positive is down, which puts the camera up. */
    public val pitch: Float = 0f,
) {
    public companion object {
        /** Behind the player, level. What the game would have drawn anyway. */
        public val Centred: ShotFrame = ShotFrame()

        public fun lerp(from: ShotFrame, to: ShotFrame, fraction: Float): ShotFrame {
            val t = fraction.coerceIn(0f, 1f)
            return ShotFrame(
                yaw = from.yaw + (to.yaw - from.yaw) * t,
                pitch = (from.pitch + (to.pitch - from.pitch) * t).coerceIn(MIN_PITCH, MAX_PITCH),
            )
        }

        /**
         * How far up and down a shot may look.
         *
         * Well short of the poles, and tighter than the orbital camera's limits. Straight down is a
         * singularity that rolls the view as it passes through, and a camera underground looking straight up
         * is a shot of the inside of a block.
         */
        public const val MIN_PITCH: Float = -45f
        public const val MAX_PITCH: Float = 80f
    }
}

/**
 * One camera move, from one angle to another.
 *
 * Data, so the whole reel can be read, tested and tuned without a game. A shot is deliberately *one* move
 * between two frames rather than a path: what makes footage read as filmed is the cut between shots, not
 * complexity within them, and a slow move that starts and ends almost still is what a camera on a crane
 * actually does.
 */
public data class CameraShot(
    /** For the debug command, and for a caption if one is ever wanted. */
    public val name: String,
    public val from: ShotFrame,
    public val to: ShotFrame,
    public val duration: Duration,
) {
    init {
        require(duration.isPositive()) { "Shot $name has no duration" }
    }

    /** Where the camera is, [fraction] of the way through. */
    public fun frameAt(fraction: Float): ShotFrame =
        ShotFrame.lerp(from, to, ease(fraction.coerceIn(0f, 1f)))

    private companion object {
        /**
         * Slow in, slow out.
         *
         * The whole difference between a camera move and a rotation. A linear sweep starts and stops at full
         * speed, which reads as the view being dragged; easing both ends reads as something being operated.
         */
        fun ease(t: Float): Float = t * t * (3f - 2f * t)
    }
}

/**
 * The shots the AFK camera has to choose from.
 *
 * Written out rather than generated, because a random angle is not a shot: half of what makes a camera look
 * deliberate is that somebody picked the height and the side. Each one is scaled from a single base length so
 * that the player's "seconds per shot" setting moves all of them together and the reel keeps its rhythm — a
 * long orbit stays longer than a quick profile at every setting.
 */
public object Shots {

    /**
     * Every shot, scaled to [base].
     *
     * @param base how long an average shot runs. The weights below are multiples of it.
     */
    public fun catalogue(base: Duration): List<CameraShot> = listOf(
        // Over the shoulder and high, drifting round. The one that says where you are.
        shot("establishing", ShotFrame(-35f, 40f), ShotFrame(10f, 33f), base, 1.3f),
        // The long one. A full sweep past both sides at close to eye level.
        shot("orbit", ShotFrame(-85f, 16f), ShotFrame(85f, 12f), base, 1.8f),
        // In front, looking back at the player.
        shot("front", ShotFrame(163f, 8f), ShotFrame(196f, 11f), base, 1.0f),
        // Below and in front, looking up. The angle every game uses to make somebody look worth watching.
        shot("low angle", ShotFrame(148f, -22f), ShotFrame(119f, -13f), base, 0.9f),
        // Almost straight down, turning slowly. Reads as a map of wherever you are standing.
        shot("overhead", ShotFrame(0f, 74f), ShotFrame(62f, 69f), base, 1.2f),
        // A short side profile. The beat between two longer moves.
        shot("profile", ShotFrame(96f, 5f), ShotFrame(78f, 13f), base, 0.7f),
        // Low, looking up past the player at whatever the sky is doing.
        shot("skyline", ShotFrame(30f, -33f), ShotFrame(-12f, -27f), base, 1.1f),
    )

    /** What a shot lasts when nobody has said. */
    public val DEFAULT_LENGTH: Duration = 8.seconds

    private fun shot(name: String, from: ShotFrame, to: ShotFrame, base: Duration, weight: Float): CameraShot =
        CameraShot(name, from, to, base * weight.toDouble())
}
