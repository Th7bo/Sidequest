package dev.th7bo.sidequest.platform.core.afk

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A sequence of camera shots, cut together.
 *
 * The reel is the part that makes an AFK camera feel like a game's attract mode rather than like a mod
 * spinning the view: shots are held, then **cut** to a different angle, and the same one never runs twice in
 * a row. A cut is free and reads as an edit; a continuous sweep round the player reads as a bug.
 *
 * The two ends are the exception, and they are the only part that is not a cut. Coming in, the camera eases
 * out from exactly where the player left it; going out, it eases back to it. That is what makes it safe to
 * hand the view back the instant somebody touches anything — the player never returns to a camera that has
 * to jump home.
 *
 * Time is passed in rather than read, so the whole thing is a pure function of the clock and a test can run
 * an hour of footage in a millisecond.
 */
public class ShotReel(
    private val shots: List<CameraShot>,
    /** How long the ease in at the start and the ease out at the end take. */
    private val transition: Duration = DEFAULT_TRANSITION,
    private val random: Random = Random.Default,
) {
    init {
        require(shots.isNotEmpty()) { "A reel needs at least one shot" }
        require(transition.isPositive()) { "A transition needs a length" }
    }

    public enum class Phase {
        OFF,

        /** Easing out from where the player left the camera into the first shot. */
        ENTERING,

        /** Cutting between shots. */
        RUNNING,

        /** Easing back to where the player left it. */
        LEAVING,
    }

    public var phase: Phase = Phase.OFF
        private set

    /** Where the camera is now. Read every frame; only [advance] changes it. */
    public var frame: ShotFrame = ShotFrame.Centred
        private set

    /**
     * How far the presentation is in, from 0 to 1.
     *
     * Drives the letterbox, and it is deliberately the *same* ramp as the camera move: bars that slide in
     * while the camera is still swinging out read as one gesture, and bars on their own timer read as two
     * things happening at once.
     */
    public var presence: Float = 0f
        private set

    private var index: Int = -1
    private var shotStartedAt: Duration = Duration.ZERO
    private var phaseStartedAt: Duration = Duration.ZERO

    /** Where the camera was when the exit started, so it eases home from wherever it had got to. */
    private var releasedFrom: ShotFrame = ShotFrame.Centred

    public val isRunning: Boolean get() = phase != Phase.OFF

    /** The shot on screen, or null when nothing is. For the debug command. */
    public val shot: CameraShot? get() = if (isRunning) shots.getOrNull(index) else null

    /** Starts the reel. Does nothing when it is already coming in or running. */
    public fun start(now: Duration) {
        if (phase == Phase.ENTERING || phase == Phase.RUNNING) return
        index = -1
        cutTo(now)
        phase = Phase.ENTERING
        phaseStartedAt = now
        frame = ShotFrame.Centred
        presence = 0f
    }

    /**
     * Hands the view back, over the transition.
     *
     * Separate from [stop] because the two mean different things. This is the reel finishing politely, which
     * is what a player who moved should get; [stop] is the feature being switched off underneath it.
     */
    public fun release(now: Duration) {
        if (phase == Phase.OFF || phase == Phase.LEAVING) return
        releasedFrom = frame
        phase = Phase.LEAVING
        phaseStartedAt = now
    }

    /** Ends it now, with the camera back where it started. For a shutdown or a feature being disabled. */
    public fun stop() {
        phase = Phase.OFF
        frame = ShotFrame.Centred
        presence = 0f
        index = -1
    }

    /** Where the camera should be at [now]. Called once a frame. */
    public fun advance(now: Duration): ShotFrame {
        when (phase) {
            Phase.OFF -> {
                frame = ShotFrame.Centred
                presence = 0f
            }

            Phase.ENTERING -> {
                val t = ramp(now)
                presence = t
                // Blended against the *live* shot rather than its first frame: the shot's clock runs from the
                // moment the reel started, so the move is already under way when the blend hands over. Easing
                // into a frozen frame and only then starting the move reads as two separate motions.
                frame = ShotFrame.lerp(ShotFrame.Centred, liveFrame(now), smooth(t))
                if (t >= 1f) phase = Phase.RUNNING
            }

            Phase.RUNNING -> {
                presence = 1f
                val current = shots[index]
                if (now - shotStartedAt >= current.duration) cutTo(now)
                frame = liveFrame(now)
            }

            Phase.LEAVING -> {
                val t = ramp(now)
                presence = 1f - t
                frame = ShotFrame.lerp(releasedFrom, ShotFrame.Centred, smooth(t))
                if (t >= 1f) stop()
            }
        }
        return frame
    }

    private fun liveFrame(now: Duration): ShotFrame {
        val current = shots[index]
        return current.frameAt(((now - shotStartedAt) / current.duration).toFloat())
    }

    /**
     * Picks the next shot, never the one just shown.
     *
     * Drawing uniformly and rejecting the repeat would be the obvious way and is the one that occasionally
     * stalls; choosing from the other shots directly cannot.
     */
    private fun cutTo(now: Duration) {
        shotStartedAt = now
        if (shots.size == 1) {
            index = 0
            return
        }
        if (index < 0) {
            index = random.nextInt(shots.size)
            return
        }
        val drawn = random.nextInt(shots.size - 1)
        index = if (drawn >= index) drawn + 1 else drawn
    }

    private fun ramp(now: Duration): Float =
        ((now - phaseStartedAt) / transition).toFloat().coerceIn(0f, 1f)

    public companion object {

        /** Slow at both ends, so neither handing the view over nor taking it back has a corner in it. */
        private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

        /**
         * How long the camera takes to leave and to come back.
         *
         * Short enough that somebody who moved is looking through their own eyes before they have registered
         * that they were not, and long enough that neither end is a jump.
         */
        public val DEFAULT_TRANSITION: Duration = 700.milliseconds
    }
}
