package dev.th7bo.sidequest.ui.minecraft.world

import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.world.WorldPosition
import dev.th7bo.sidequest.ui.world.WorldProjection
import dev.th7bo.sidequest.ui.world.WorldProjector
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Projects world coordinates onto the GUI using the camera's own basis.
 *
 * Built from the camera's position, rotation and field of view rather than from the
 * render matrices. The matrices are the more obvious route and the wrong one here: they
 * are not reliably available during HUD extraction, and the ones that are get rebound
 * between versions — whereas `position`, `xRot`, `yRot` and `getFov` are stable and mean
 * exactly what they say. Keeping the arithmetic explicit also means the projection is
 * the same one the headless tests exercise through a fake.
 *
 * Coordinates are produced in **GUI units**, not pixels, so they compose with everything
 * else the framework measures.
 */
public class MinecraftWorldProjector(
    private val camera: Camera,
    private val viewport: Size,
    /** Vertical field of view in degrees. */
    fovDegrees: Float,
) : WorldProjector {

    private val eyeX = camera.position().x
    private val eyeY = camera.position().y
    private val eyeZ = camera.position().z

    // The camera basis. Minecraft's yaw is measured so that 0 faces -Z and it increases
    // clockwise, which is why the signs here do not match the textbook right-handed form.
    private val yaw = Math.toRadians(camera.yRot().toDouble())
    private val pitch = Math.toRadians(camera.xRot().toDouble())

    private val cosPitch = cos(pitch)
    private val sinPitch = sin(pitch)
    private val cosYaw = cos(yaw)
    private val sinYaw = sin(yaw)

    private val forwardX = -sinYaw * cosPitch
    private val forwardY = -sinPitch
    private val forwardZ = cosYaw * cosPitch

    private val rightX = cosYaw
    private val rightY = 0.0
    private val rightZ = sinYaw

    // up = right x forward, which stays correct through the poles unlike deriving it
    // from yaw and pitch a second time.
    private val upX = rightY * forwardZ - rightZ * forwardY
    private val upY = rightZ * forwardX - rightX * forwardZ
    private val upZ = rightX * forwardY - rightY * forwardX

    private val halfFovTangent = tan(Math.toRadians(fovDegrees.toDouble()) / 2.0)
    private val aspect = if (viewport.height > 0f) viewport.width / viewport.height else 1f

    override fun project(position: WorldPosition): WorldProjection {
        val dx = position.x - eyeX
        val dy = position.y - eyeY
        val dz = position.z - eyeZ

        val distance = sqrt(dx * dx + dy * dy + dz * dz)

        val depth = dx * forwardX + dy * forwardY + dz * forwardZ
        val horizontal = dx * rightX + dy * rightY + dz * rightZ
        val vertical = dx * upX + dy * upY + dz * upZ

        // Behind the camera, or so close to the plane that dividing would explode. The
        // coordinate is still produced, mirrored, because the edge indicator needs a
        // direction — but the flag is what stops anyone treating it as a position.
        if (depth <= NEAR_PLANE) {
            return WorldProjection(
                screenPosition = Vec2(
                    (viewport.width / 2f - horizontal.toFloat()),
                    (viewport.height / 2f + vertical.toFloat()),
                ),
                distance = distance,
                isBehind = true,
            )
        }

        val ndcX = horizontal / (depth * halfFovTangent * aspect)
        val ndcY = vertical / (depth * halfFovTangent)

        return WorldProjection(
            screenPosition = Vec2(
                (viewport.width / 2f) * (1f + ndcX.toFloat()),
                (viewport.height / 2f) * (1f - ndcY.toFloat()),
            ),
            distance = distance,
            isBehind = false,
        )
    }

    public companion object {
        private const val NEAR_PLANE = 0.05

        /**
         * Builds a projector for the current frame, or null when there is no camera.
         *
         * Rebuilt every frame on purpose: the camera moves, and caching a projector is
         * caching a stale basis.
         */
        public fun forCurrentFrame(client: Minecraft, viewport: Size): MinecraftWorldProjector? {
            val camera = activeCamera(client) ?: return null
            val fov = camera.fov.takeIf { it > 0f } ?: Camera.BASE_HUD_FOV
            return MinecraftWorldProjector(camera, viewport, fov)
        }

        /**
         * The active camera.
         *
         * The project's only version conditional, and the reason the adapter lives inside
         * the stonecutter tree: 26.1.2 names this accessor `getMainCamera()` and 26.2
         * renamed it to `mainCamera()`. Everything on `Camera` itself — `position`,
         * `xRot`, `yRot`, `getFov` — is identical between the two, so the difference is
         * contained to this one expression rather than spreading through the projection.
         */
        private fun activeCamera(client: Minecraft): Camera? {
            //? if >=26.2 {
            return client.gameRenderer.mainCamera()
            //?} else
            /*return client.gameRenderer.getMainCamera()*/
        }
    }
}
