package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.core.garden.OrbitalAim
import dev.th7bo.sidequest.platform.core.garden.OrbitalCamera

/**
 * What the camera mixins ask.
 *
 * The mixins are two small pieces of glue in different parts of the game — one where the mouse would turn
 * the player, one where the camera takes its rotation — and this is the only thing they share. Keeping the
 * state here rather than in either of them means neither has to know the other exists, and the feature that
 * owns the toggle never has to touch a Minecraft class.
 *
 * Read from the render thread and written from it too, so nothing here is synchronised. The volatile flag is
 * for the mouse callback, which arrives on the same thread but through a native boundary.
 */
public object OrbitalCameraState {

    @Volatile
    private var enabled: Boolean = false

    private var aim: OrbitalAim = OrbitalAim.Centred

    /** Set by the feature. A single object, so the mixins never read a setting or a config. */
    public var settings: Settings = Settings()

    public data class Settings(
        public val sensitivity: Double = 1.0,
        public val invertY: Boolean = false,
    )

    public val isActive: Boolean get() = enabled

    public val current: OrbitalAim get() = aim

    /**
     * Turns the orbit on or off.
     *
     * Recentring on the way in is deliberate: entering the mode with the camera already swung round from
     * last time would be a jump, and the one thing a camera must never do is move when nobody asked it to.
     */
    public fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        aim = OrbitalAim.Centred
    }

    /** Puts the camera back behind the player without leaving the mode. */
    public fun recentre() {
        aim = OrbitalAim.Centred
    }

    /**
     * Mouse movement, taken instead of the player's own turning.
     *
     * Called from the mixin with the raw accumulated pixels, before Minecraft has applied its sensitivity —
     * so the sensitivity here is the mod's own and the game's setting does not double up.
     */
    @JvmStatic
    public fun onMouseMoved(deltaX: Double, deltaY: Double, playerPitch: Float) {
        if (!enabled) return
        aim = OrbitalCamera.advance(
            aim,
            deltaX = deltaX,
            deltaY = deltaY,
            playerPitch = playerPitch,
            sensitivity = settings.sensitivity,
            invertY = settings.invertY,
        )
    }

    /** The yaw the camera should use, given the player's. */
    @JvmStatic
    public fun cameraYaw(playerYaw: Float): Float = OrbitalCamera.cameraYaw(playerYaw, aim)

    /** The pitch the camera should use, given the player's. */
    @JvmStatic
    public fun cameraPitch(playerPitch: Float): Float = OrbitalCamera.cameraPitch(playerPitch, aim)
}
