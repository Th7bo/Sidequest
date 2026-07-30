package dev.th7bo.sidequest.ui.minecraft.world

import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.world.PerspectiveProjector
import dev.th7bo.sidequest.ui.world.ViewBasis
import dev.th7bo.sidequest.ui.world.WorldDirection
import dev.th7bo.sidequest.ui.world.WorldPosition
import dev.th7bo.sidequest.ui.world.WorldProjector
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import org.joml.Vector3fc

/**
 * Reads the camera, and hands the arithmetic to [PerspectiveProjector].
 *
 * This class used to do the projection too, and built the camera basis itself out of yaw and pitch. That was
 * the bug: the vector it called `right` was Minecraft's *left*, and deriving up from it flipped that as well,
 * so the whole picture came out rotated 180° about the view axis. Anything dead ahead still looked correct,
 * which is why it survived a screenshot test.
 *
 * So the basis is no longer computed. [Camera] already holds one — `forwardVector` and `upVector` are the
 * rotation quaternion applied to constants, exactly what the world is rendered with — and taking it from there
 * cannot disagree with what the player is looking at. It also survives a rolled camera, which yaw and pitch
 * cannot express at all.
 *
 * What is left here is version-sensitive and nothing else, which is the reason the file is inside the
 * stonecutter tree.
 */
public class MinecraftWorldProjector private constructor(
    private val delegate: PerspectiveProjector,
) : WorldProjector by delegate {

    public companion object {

        /**
         * Builds a projector for the current frame, or null when there is no camera.
         *
         * Rebuilt every frame on purpose: the camera moves, and caching a projector is caching a stale basis.
         */
        public fun forCurrentFrame(client: Minecraft, viewport: Size): MinecraftWorldProjector? {
            val camera = activeCamera(client) ?: return null
            if (!camera.isInitialized) return null

            // The world's field of view rather than the HUD's. This projects world positions to where they are
            // actually drawn, so it has to match the matrix the world was rendered with — including the
            // modifiers that sprinting and fluids apply to it, which is what `getFov` already carries.
            val fov = camera.fov.takeIf { it > 0f } ?: Camera.BASE_HUD_FOV

            val eye = camera.position()
            return MinecraftWorldProjector(
                PerspectiveProjector(
                    eye = WorldPosition(eye.x, eye.y, eye.z),
                    basis = ViewBasis(
                        forward = camera.forwardVector().toDirection(),
                        up = camera.upVector().toDirection(),
                    ),
                    fovDegrees = fov,
                    viewport = viewport,
                ),
            )
        }

        private fun Vector3fc.toDirection() = WorldDirection(x().toDouble(), y().toDouble(), z().toDouble())

        /**
         * The active camera.
         *
         * The project's only version conditional, and the reason the adapter lives inside the stonecutter tree:
         * 26.1.2 names this accessor `getMainCamera()` and 26.2 renamed it to `mainCamera()`. Everything on
         * [Camera] itself — `position`, `forwardVector`, `upVector`, `getFov` — is identical between the two,
         * so the difference is contained to this one expression rather than spreading through the projection.
         */
        private fun activeCamera(client: Minecraft): Camera? {
            //? if >=26.2 {
            return client.gameRenderer.mainCamera()
            //?} else
            /*return client.gameRenderer.getMainCamera()*/
        }
    }
}
