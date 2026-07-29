package dev.th7bo.sidequest.ui.world

import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.constantState
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * A point in the world.
 *
 * Deliberately **not** a [Vec2] or a [dev.th7bo.sidequest.ui.geometry.Vec3]-style shared
 * type: a world position and a screen position must never be assignable to one another.
 * A world overlay's screen position is *derived* every frame by projection and is never
 * stored, which is the invariant that keeps the two placement models from bleeding into
 * each other.
 */
@Serializable
public data class WorldPosition(
    public val x: Double,
    public val y: Double,
    public val z: Double,
) {

    public fun distanceTo(other: WorldPosition): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    public operator fun plus(other: WorldPosition): WorldPosition =
        WorldPosition(x + other.x, y + other.y, z + other.z)

    public fun offset(dx: Double = 0.0, dy: Double = 0.0, dz: Double = 0.0): WorldPosition =
        WorldPosition(x + dx, y + dy, z + dz)

    override fun toString(): String = "($x, $y, $z)"

    public companion object {
        public val Origin: WorldPosition = WorldPosition(0.0, 0.0, 0.0)
    }
}

/**
 * The result of projecting a [WorldPosition] onto the screen.
 *
 * [isBehind] is separate from "outside the viewport" on purpose. A point behind the
 * camera still projects to *some* coordinate, and treating that coordinate as valid is
 * how waypoints end up mirrored on the wrong side of the screen — the classic bug this
 * type exists to make impossible to write.
 */
public class WorldProjection(
    /** Screen-space position. Meaningless when [isBehind] is true. */
    public val screenPosition: Vec2,
    /** Distance from the camera, in blocks. */
    public val distance: Double,
    public val isBehind: Boolean,
) {
    /** True when the point is in front of the camera and inside [viewport]. */
    public fun isOnScreen(viewport: Size): Boolean = !isBehind &&
        screenPosition.x >= 0f && screenPosition.x <= viewport.width &&
        screenPosition.y >= 0f && screenPosition.y <= viewport.height

    override fun toString(): String =
        "WorldProjection($screenPosition, distance=$distance, behind=$isBehind)"
}

/**
 * Projects world coordinates to screen coordinates.
 *
 * The only part of world overlays that needs a game. Everything else — fading, edge
 * indicators, culling, ordering — is arithmetic over the projection, so all of it is
 * testable against a fake.
 */
public fun interface WorldProjector {
    public fun project(position: WorldPosition): WorldProjection
}

/** How an overlay fades out with distance. */
@Serializable
public data class DistanceFade(
    /** Fully opaque at or below this distance, in blocks. */
    public val nearDistance: Double = DEFAULT_NEAR,
    /** Fully transparent at or beyond this distance. */
    public val farDistance: Double = DEFAULT_FAR,
    /** Below this the overlay hides entirely, so it cannot cover what you are standing on. */
    public val minimumDistance: Double = 0.0,
) {

    init {
        require(farDistance > nearDistance) {
            "farDistance ($farDistance) must exceed nearDistance ($nearDistance)"
        }
    }

    /** Opacity in `0..1` at [distance]. */
    public fun opacityAt(distance: Double): Float {
        if (distance < minimumDistance) return 0f
        if (distance <= nearDistance) return 1f
        if (distance >= farDistance) return 0f
        val span = farDistance - nearDistance
        return (1.0 - (distance - nearDistance) / span).toFloat()
    }

    /** True when [distance] leaves nothing to draw. */
    public fun isCulled(distance: Double): Boolean = opacityAt(distance) <= 0f

    public companion object {
        public const val DEFAULT_NEAR: Double = 32.0
        public const val DEFAULT_FAR: Double = 256.0

        /** Never fades. For overlays that must stay legible at any range. */
        public val None: DistanceFade = DistanceFade(nearDistance = Double.MAX_VALUE / 4, farDistance = Double.MAX_VALUE / 2)
    }
}

/** Where an off-screen overlay's indicator is drawn. */
@Serializable
public data class EdgeIndicator(
    public val isEnabled: Boolean = true,
    /** Inset from the viewport edge, so the arrow is not half off the screen. */
    public val margin: Float = DEFAULT_MARGIN,
    /** Drawn size of the arrow. */
    public val size: Float = DEFAULT_SIZE,
) {
    public companion object {
        public const val DEFAULT_MARGIN: Float = 12f
        public const val DEFAULT_SIZE: Float = 8f

        public val Disabled: EdgeIndicator = EdgeIndicator(isEnabled = false)
    }
}

/**
 * The durable description of something drawn at a world position.
 *
 * The counterpart to [dev.th7bo.sidequest.ui.hud.HudDefinition] for world space. The two
 * deliberately share no placement type: a HUD is anchored to the screen and a world
 * overlay is anchored to a block, and the moment those become the same type someone will
 * persist a projected screen coordinate.
 */
public class WorldOverlayDefinition(
    public val id: UiId,
    public val position: UiState<WorldPosition>,
    public val label: UiState<String>,
    public val icon: Icon? = null,
    /** Null takes the theme's accent. */
    public val color: Color? = null,
    public val fade: DistanceFade = DistanceFade(),
    public val edgeIndicator: EdgeIndicator = EdgeIndicator(),
    /** Whether the distance in blocks is shown under the label. */
    public val showsDistance: Boolean = true,
    public val visibleWhen: UiState<Boolean> = constantState(true),
    /** Higher draws in front when two overlays are the same distance away. */
    public val priority: Int = 0,
) {
    override fun toString(): String = "WorldOverlayDefinition($id)"
}
