package dev.th7bo.sidequest.ui.core.world

import dev.th7bo.sidequest.ui.extension.OwnedRegistry
import dev.th7bo.sidequest.ui.extension.Registration
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.world.WorldOverlayDefinition
import dev.th7bo.sidequest.ui.world.WorldProjection
import dev.th7bo.sidequest.ui.world.WorldProjector
import kotlin.math.abs
import kotlin.math.atan2

/**
 * One overlay resolved for the current frame.
 *
 * Everything here is derived from the projection and thrown away afterwards. Nothing is
 * stored back onto the definition, which is what keeps a projected screen coordinate from
 * ever being mistaken for a placement.
 */
public class ResolvedOverlay(
    public val definition: WorldOverlayDefinition,
    public val projection: WorldProjection,
    /** Where to draw, already clamped to the edge when [isEdgeIndicator] is true. */
    public val screenPosition: Vec2,
    public val opacity: Float,
    /** True when the target is off screen or behind and this is an arrow, not a label. */
    public val isEdgeIndicator: Boolean,
    /** Radians, pointing from the screen centre towards the target. Only for indicators. */
    public val angleRadians: Float,
) {
    public val distance: Double get() = projection.distance

    override fun toString(): String =
        "ResolvedOverlay(${definition.id}, edge=$isEdgeIndicator, opacity=$opacity)"
}

/**
 * Registry and per-frame resolver for world overlays.
 *
 * Registration is scope-owned, so a module that unloads takes its waypoints with it —
 * the phase 6 criterion that overlays dispose cleanly with their registration scope is
 * this class delegating to [OwnedRegistry] rather than keeping its own map.
 */
public class WorldOverlayLayer {

    private val overlays = OwnedRegistry<UiId, WorldOverlayDefinition>("world overlay")

    public val size: Int get() = overlays.size

    public val ids: Set<UiId> get() = overlays.keys

    /**
     * @throws dev.th7bo.sidequest.ui.extension.DuplicateRegistrationException if the id
     *   is taken, naming both owners.
     */
    public fun register(scope: RegistrationScope, definition: WorldOverlayDefinition): Registration =
        overlays.register(scope, definition.id, definition)

    public operator fun get(id: UiId): WorldOverlayDefinition? = overlays[id]

    public fun snapshot(): Map<UiId, WorldOverlayDefinition> = overlays.snapshot()

    /**
     * Resolves every visible overlay for this frame.
     *
     * Ordered far to near, so nearer overlays paint over more distant ones — the reverse
     * of what a naive registration-order walk gives you, and the difference between a
     * waypoint field that reads correctly and one that does not.
     */
    public fun resolve(projector: WorldProjector, viewport: Size): List<ResolvedOverlay> {
        val resolved = ArrayList<ResolvedOverlay>(overlays.size)

        for (definition in overlays.snapshot().values) {
            if (!definition.visibleWhen.peek()) continue

            val projection = projector.project(definition.position.peek())
            val opacity = definition.fade.opacityAt(projection.distance)
            if (opacity <= 0f) continue

            val onScreen = projection.isOnScreen(viewport)
            if (onScreen) {
                resolved.add(
                    ResolvedOverlay(
                        definition = definition,
                        projection = projection,
                        screenPosition = projection.screenPosition,
                        opacity = opacity,
                        isEdgeIndicator = false,
                        angleRadians = 0f,
                    ),
                )
                continue
            }

            if (!definition.edgeIndicator.isEnabled) continue

            val edge = edgePosition(projection, viewport, definition.edgeIndicator.margin)
            resolved.add(
                ResolvedOverlay(
                    definition = definition,
                    projection = projection,
                    screenPosition = edge.position,
                    opacity = opacity,
                    isEdgeIndicator = true,
                    angleRadians = edge.angle,
                ),
            )
        }

        // Farthest first. Ties break on priority so a definition can insist on being in
        // front of another at the same range.
        resolved.sortWith(
            compareByDescending<ResolvedOverlay> { it.distance }
                .thenBy { it.definition.priority },
        )
        return resolved
    }

    private class Edge(val position: Vec2, val angle: Float)

    /**
     * Clamps an off-screen target to the viewport edge.
     *
     * A point behind the camera projects to a coordinate that is numerically valid and
     * geometrically meaningless — mirrored through the centre. Negating the direction is
     * what stops a waypoint directly behind you pointing forwards.
     */
    private fun edgePosition(projection: WorldProjection, viewport: Size, margin: Float): Edge {
        val center = Vec2(viewport.width / 2f, viewport.height / 2f)
        val raw = projection.screenPosition - center
        val direction = if (projection.isBehind) Vec2(-raw.x, -raw.y) else raw

        // A target exactly at the centre while behind the camera has no direction to
        // point in; straight down is the least surprising answer.
        if (direction.x == 0f && direction.y == 0f) {
            return Edge(Vec2(center.x, viewport.height - margin), HALF_PI)
        }

        val halfWidth = (viewport.width / 2f - margin).coerceAtLeast(0f)
        val halfHeight = (viewport.height / 2f - margin).coerceAtLeast(0f)

        // Scale the direction until it touches whichever edge it reaches first.
        val scaleX = if (direction.x != 0f) halfWidth / abs(direction.x) else Float.MAX_VALUE
        val scaleY = if (direction.y != 0f) halfHeight / abs(direction.y) else Float.MAX_VALUE
        val scale = minOf(scaleX, scaleY)

        val position = Vec2(center.x + direction.x * scale, center.y + direction.y * scale)
        return Edge(position, atan2(direction.y, direction.x))
    }

    private companion object {
        const val HALF_PI = (Math.PI / 2).toFloat()
    }
}
