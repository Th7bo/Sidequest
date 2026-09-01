package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.core.afk.ShotFrame
import dev.th7bo.sidequest.platform.core.afk.ShotReel
import dev.th7bo.sidequest.platform.core.afk.Shots
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * What the camera mixin asks while the player is away.
 *
 * The same shape as [OrbitalCameraState] and for the same reason: the mixin is a few lines in the middle of
 * the game's own camera code and must not know that a feature, a setting or a reel of shots exists. This is
 * the one object between them.
 *
 * The clock is here rather than in the feature because the reel has to advance **per frame**, not per poll. A
 * feature checking four times a second is right for deciding whether somebody is away and hopeless for moving
 * a camera; running the reel off a monotonic clock read at draw time makes the motion as smooth as the frame
 * rate and costs nothing.
 *
 * Read and written from the render thread. The volatile flag is the only thing the feature's scheduler
 * touches from elsewhere.
 */
public object AfkCameraState {

    /** A fixed origin, so the reel's arithmetic is on plain durations. */
    private val since = TimeSource.Monotonic.markNow()

    private var reel: ShotReel = ShotReel(Shots.catalogue(Shots.DEFAULT_LENGTH))

    @Volatile
    private var running: Boolean = false

    private var showBars: Boolean = true

    private var frame: ShotFrame = ShotFrame.Centred

    private var bars: Float = 0f

    /** True while the mixin should be pointing the camera. */
    public val isActive: Boolean get() = running

    /** True once the camera is back where the player left it and the feature may hand the view back. */
    public val isSettled: Boolean get() = !running

    /** The shot on screen, for the debug command. */
    public val shotName: String? get() = reel.shot?.name

    /**
     * Starts the reel.
     *
     * A new reel each time rather than a retuned one: the shot lengths come from a setting the player can
     * change between stints, and a reel part way through a shot of the old length would run one odd shot
     * before the change took.
     */
    public fun start(shotLength: Duration, letterbox: Boolean) {
        showBars = letterbox
        reel = ShotReel(Shots.catalogue(shotLength))
        reel.start(since.elapsedNow())
        frame = ShotFrame.Centred
        bars = 0f
        running = true
    }

    /** Hands the view back over the reel's transition. What somebody who moved should get. */
    public fun release() {
        if (!running) return
        reel.release(since.elapsedNow())
    }

    /** Ends it now, camera back where it started. For the feature being disabled or the game shutting down. */
    public fun stop() {
        reel.stop()
        frame = ShotFrame.Centred
        bars = 0f
        running = false
    }

    /**
     * Moves the reel on one frame.
     *
     * Called from the mixin before it reads the angles, so both come from one advance rather than from two
     * reads either side of a cut.
     */
    @JvmStatic
    public fun advance() {
        if (!running) return
        frame = reel.advance(since.elapsedNow())
        bars = if (showBars) reel.presence else 0f
        if (!reel.isRunning) {
            running = false
            bars = 0f
        }
    }

    /** Where the camera looks. Relative to the player's own facing — see [ShotFrame]. */
    @JvmStatic
    public fun cameraYaw(playerYaw: Float): Float = playerYaw + frame.yaw

    /**
     * Where the camera looks vertically.
     *
     * Absolute, and takes no argument for that reason: a high shot has to be a high shot whether the player
     * went away staring at the sky or at their feet. See [ShotFrame].
     */
    @JvmStatic
    public fun cameraPitch(): Float = frame.pitch

    /**
     * How far the bars are in, or null when there are none to draw.
     *
     * Read by the HUD layer once a frame. Null rather than zero so that "switched off" and "on its way in"
     * are different answers — the layer only builds the stage's contents when there is something to build.
     */
    public fun letterbox(): Float? = if (running && showBars) bars else null
}
