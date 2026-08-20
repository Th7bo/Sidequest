package dev.th7bo.sidequest.platform.core.garden

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The camera's arithmetic.
 *
 * Small, and worth testing precisely because it is: every failure here is one somebody notices as "the
 * camera feels wrong" a week later rather than as anything that looks like a bug.
 */
class OrbitalCameraTest {

    @Test
    fun `moving the mouse turns the camera and nothing else`() {
        val aim = OrbitalCamera.advance(OrbitalAim.Centred, deltaX = 100.0, deltaY = 0.0, playerPitch = 0f)

        assertEquals(15f, aim.yaw, 1e-4f, "a hundred pixels at Minecraft's own 0.15 degrees each")
        assertEquals(0f, aim.pitch)
    }

    @Test
    fun `sensitivity scales the movement`() {
        val slow = OrbitalCamera.advance(OrbitalAim.Centred, 100.0, 0.0, 0f, sensitivity = 0.5)
        val fast = OrbitalCamera.advance(OrbitalAim.Centred, 100.0, 0.0, 0f, sensitivity = 2.0)

        assertEquals(7.5f, slow.yaw, 1e-4f)
        assertEquals(30f, fast.yaw, 1e-4f)
    }

    @Test
    fun `inverting flips only the vertical`() {
        val normal = OrbitalCamera.advance(OrbitalAim.Centred, 100.0, 100.0, 0f)
        val inverted = OrbitalCamera.advance(OrbitalAim.Centred, 100.0, 100.0, 0f, invertY = true)

        assertEquals(normal.yaw, inverted.yaw, "yaw is not affected by the vertical setting")
        assertEquals(-normal.pitch, inverted.pitch, 1e-4f)
    }

    /**
     * The camera stops just short of straight up.
     *
     * Vertical is a singularity for a yaw-and-pitch camera: passing through it rolls the view, which reads
     * as the world spinning rather than as the camera reaching a limit.
     */
    @Test
    fun `the camera cannot pass the poles`() {
        var aim = OrbitalAim.Centred
        repeat(50) { aim = OrbitalCamera.advance(aim, 0.0, -1000.0, playerPitch = 0f) }
        assertEquals(OrbitalCamera.MIN_PITCH, OrbitalCamera.cameraPitch(0f, aim), 1e-4f)

        aim = OrbitalAim.Centred
        repeat(50) { aim = OrbitalCamera.advance(aim, 0.0, 1000.0, playerPitch = 0f) }
        assertEquals(OrbitalCamera.MAX_PITCH, OrbitalCamera.cameraPitch(0f, aim), 1e-4f)
    }

    /**
     * The limit is on where the camera ends up, not on the offset by itself.
     *
     * A player already looking down at 45 degrees may only push the camera another 45 before it is vertical.
     * Clamping the offset alone would let the total go past, and would let the offset grow into a number
     * that takes a second of dragging to undo.
     */
    @Test
    fun `the limit accounts for where the player is already looking`() {
        var aim = OrbitalAim.Centred
        repeat(20) { aim = OrbitalCamera.advance(aim, 0.0, 1000.0, playerPitch = 45f) }

        assertEquals(OrbitalCamera.MAX_PITCH - 45f, aim.pitch, 1e-4f, "only the remaining half is available")
        assertEquals(OrbitalCamera.MAX_PITCH, OrbitalCamera.cameraPitch(45f, aim), 1e-4f)
    }

    /** Spinning round for an hour leaves a small number rather than one that has lost its precision. */
    @Test
    fun `yaw wraps rather than growing`() {
        var aim = OrbitalAim.Centred
        repeat(1000) { aim = OrbitalCamera.advance(aim, 500.0, 0.0, 0f) }

        assertTrue(aim.yaw >= -180f && aim.yaw < 180f, "yaw ran away to ${aim.yaw}")
    }

    @Test
    fun `wrapping keeps the range half open`() {
        assertEquals(0f, OrbitalCamera.wrapDegrees(360f))
        assertEquals(-179f, OrbitalCamera.wrapDegrees(181f))
        assertEquals(179f, OrbitalCamera.wrapDegrees(-181f))
        assertEquals(-180f, OrbitalCamera.wrapDegrees(180f), "180 belongs to the negative end")
    }

    @Test
    fun `the camera follows the player's own facing`() {
        val aim = OrbitalAim(yaw = 90f)

        assertEquals(-90f, OrbitalCamera.cameraYaw(180f, aim), 1e-4f, "wrapped, not 270")
        assertEquals(90f, OrbitalCamera.cameraYaw(0f, aim), 1e-4f)
    }
}
