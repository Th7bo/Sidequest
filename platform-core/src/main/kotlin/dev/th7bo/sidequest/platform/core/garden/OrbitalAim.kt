package dev.th7bo.sidequest.platform.core.garden

/**
 * Where the camera is looking from, relative to the player.
 *
 * An *offset* rather than an absolute direction, and that is the decision worth stating. Absolute would go
 * stale the moment the player's own facing changed — a warp, a teleport, a moment with the mouse unlocked —
 * and the camera would be left pointing at wherever they used to be. An offset follows them and stays put
 * when they do, which is what "orbit around the player" means.
 */
public data class OrbitalAim(
    /** Degrees added to the player's yaw. Wrapped, so spinning forever costs nothing. */
    public val yaw: Float = 0f,
    /** Degrees added to the player's pitch, already limited so the total cannot pass the poles. */
    public val pitch: Float = 0f,
) {
    public val isCentred: Boolean get() = yaw == 0f && pitch == 0f

    public companion object {
        public val Centred: OrbitalAim = OrbitalAim()
    }
}

/**
 * Turning mouse movement into a camera orbit.
 *
 * The whole of the feature's arithmetic, kept here because it is the part that can be wrong in ways nobody
 * notices for a while — a pitch that creeps past vertical and rolls the view, a yaw that grows without bound
 * until the float loses precision, an inversion setting that quietly does nothing.
 */
public object OrbitalCamera {

    /**
     * How far up and down the camera may look.
     *
     * Just short of the poles rather than at them. Straight up is a singularity for a yaw-and-pitch camera:
     * the view rolls as it passes through, which reads as the world spinning.
     */
    public const val MIN_PITCH: Float = -89.9f
    public const val MAX_PITCH: Float = 89.9f

    /** What Minecraft multiplies raw mouse movement by at sensitivity one. Its number, not one chosen here. */
    private const val DEGREES_PER_PIXEL: Double = 0.15

    /**
     * The aim after a mouse movement.
     *
     * [playerPitch] is needed because the limit applies to where the camera ends up, not to the offset on
     * its own: an offset clamped by itself lets the total pass vertical whenever the player is already
     * looking up, and lets the offset run away to a number it takes a second of dragging to undo.
     */
    public fun advance(
        aim: OrbitalAim,
        deltaX: Double,
        deltaY: Double,
        playerPitch: Float,
        sensitivity: Double = 1.0,
        invertY: Boolean = false,
    ): OrbitalAim {
        val scale = DEGREES_PER_PIXEL * sensitivity
        val yaw = wrapDegrees(aim.yaw + (deltaX * scale).toFloat())
        val vertical = (deltaY * scale * if (invertY) -1.0 else 1.0).toFloat()
        val pitch = (aim.pitch + vertical).coerceIn(MIN_PITCH - playerPitch, MAX_PITCH - playerPitch)
        return OrbitalAim(yaw, pitch)
    }

    /** Where the camera should point, given where the player is pointing. */
    public fun cameraYaw(playerYaw: Float, aim: OrbitalAim): Float = wrapDegrees(playerYaw + aim.yaw)

    public fun cameraPitch(playerPitch: Float, aim: OrbitalAim): Float =
        (playerPitch + aim.pitch).coerceIn(MIN_PITCH, MAX_PITCH)

    /** Into `[-180, 180)`, so a yaw spun round for an hour is still a small number. */
    public fun wrapDegrees(degrees: Float): Float {
        var wrapped = degrees % 360f
        if (wrapped >= 180f) wrapped -= 360f
        if (wrapped < -180f) wrapped += 360f
        return wrapped
    }
}
