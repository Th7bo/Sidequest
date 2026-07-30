package dev.th7bo.sidequest.ui.world

import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A unit direction in world space.
 *
 * Deliberately not a [WorldPosition]. A place and a heading are not the same thing, and the one bug this whole
 * file exists to prevent came from a heading that was silently the wrong way round — so the type that carries
 * headings is its own, and the only way to build a view basis is through [ViewBasis], which derives what it can
 * rather than trusting three vectors to agree.
 */
public data class WorldDirection(
    public val x: Double,
    public val y: Double,
    public val z: Double,
) {

    public fun dot(other: WorldDirection): Double = x * other.x + y * other.y + z * other.z

    public fun cross(other: WorldDirection): WorldDirection = WorldDirection(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    /** Unit length, or [Zero] for a zero vector — which cannot be normalised and must not become a NaN. */
    public fun normalised(): WorldDirection {
        val length = sqrt(x * x + y * y + z * z)
        if (length == 0.0) return Zero
        return WorldDirection(x / length, y / length, z / length)
    }

    override fun toString(): String = "($x, $y, $z)"

    public companion object {
        public val Zero: WorldDirection = WorldDirection(0.0, 0.0, 0.0)
    }
}

/**
 * The camera's orientation: where it looks, and which way is up from there.
 *
 * **Right is derived, never supplied.** It is `forward × up`, and that is the whole point of the type. The
 * projector previously built its own basis from yaw and pitch and got the handedness backwards, which put the
 * horizontal axis along the camera's *left* and — because up was then a cross product of that wrong vector —
 * flipped the vertical axis too. The result was a picture rotated 180° about the view axis: every waypoint
 * appeared point-reflected through the centre of the screen, which is invisible for anything dead ahead and
 * increasingly wrong for everything else.
 *
 * A basis with one derived axis cannot be inconsistent with itself. That is worth more here than the handful of
 * multiplications it costs.
 */
public class ViewBasis(
    forward: WorldDirection,
    up: WorldDirection,
) {
    public val forward: WorldDirection = forward.normalised()
    public val up: WorldDirection = up.normalised()

    /**
     * The camera's right hand.
     *
     * With Minecraft's axes — +X east, +Z south — a camera facing south has `forward = (0, 0, 1)` and
     * `up = (0, 1, 0)`, and this gives `(-1, 0, 0)`: west. Which is the right hand of somebody facing south.
     */
    public val right: WorldDirection = this.forward.cross(this.up).normalised()

    override fun toString(): String = "ViewBasis(forward=$forward, up=$up)"
}

/**
 * The pinhole projection, with no game anywhere near it.
 *
 * Split out of the Minecraft adapter so it can be tested against known geometry, because it could not be
 * before: the adapter needed a live camera, so every headless test used a fake projector instead — and a fake
 * projector agrees with whatever the real one does, including being upside down. The adapter is now only the
 * part that reads the camera.
 *
 * Coordinates come out in whatever units [viewport] is given in, which for this framework is GUI units rather
 * than pixels, so they compose with everything else it measures.
 */
public class PerspectiveProjector(
    /** The camera's eye, not the player's feet. */
    private val eye: WorldPosition,
    private val basis: ViewBasis,
    /** Vertical field of view in degrees, matching what the world is rendered with. */
    fovDegrees: Float,
    private val viewport: Size,
) : WorldProjector {

    private val halfFovTangent = tan(Math.toRadians(fovDegrees.toDouble()) / 2.0)

    /**
     * Width over height.
     *
     * The vertical field of view is the given one and the horizontal follows from the aspect, which is the
     * convention Minecraft's own projection matrix uses — it passes `fov` as JOML's `fovy` and `width / height`
     * as the aspect.
     */
    private val aspect = if (viewport.height > 0f) viewport.width / viewport.height else 1f

    override fun project(position: WorldPosition): WorldProjection {
        val dx = position.x - eye.x
        val dy = position.y - eye.y
        val dz = position.z - eye.z
        val offset = WorldDirection(dx, dy, dz)

        val distance = sqrt(dx * dx + dy * dy + dz * dz)

        val depth = offset.dot(basis.forward)
        val horizontal = offset.dot(basis.right)
        val vertical = offset.dot(basis.up)

        // Behind the camera, or so close to the plane that dividing would explode. A coordinate is still
        // produced — mirrored, so the edge indicator can negate it back into a direction — but the flag is what
        // stops anybody treating it as a position.
        if (depth <= NEAR_PLANE) {
            return WorldProjection(
                screenPosition = Vec2(
                    viewport.width / 2f - horizontal.toFloat(),
                    viewport.height / 2f + vertical.toFloat(),
                ),
                distance = distance,
                isBehind = true,
            )
        }

        val ndcX = horizontal / (depth * halfFovTangent * aspect)
        val ndcY = vertical / (depth * halfFovTangent)

        return WorldProjection(
            // +X is right and +Y is *down* on screen, so the vertical term is subtracted while the horizontal
            // one is added. The asymmetry is the screen's, not the camera's.
            screenPosition = Vec2(
                (viewport.width / 2f) * (1f + ndcX.toFloat()),
                (viewport.height / 2f) * (1f - ndcY.toFloat()),
            ),
            distance = distance,
            isBehind = false,
        )
    }

    public companion object {
        /** Matches the near plane the world itself is rendered with. */
        public const val NEAR_PLANE: Double = 0.05
    }
}
