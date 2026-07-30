package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.marker.MarkerService
import dev.th7bo.sidequest.platform.marker.TrackedMarker
import dev.th7bo.sidequest.ui.extension.Registration
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.world.DistanceFade
import dev.th7bo.sidequest.ui.world.EdgeIndicator
import dev.th7bo.sidequest.ui.core.world.WorldOverlayLayer
import dev.th7bo.sidequest.ui.world.WorldOverlayDefinition
import dev.th7bo.sidequest.ui.world.WorldPosition

/**
 * Draws the platform's markers through the UI framework's world overlays.
 *
 * The translation between two vocabularies that must not know about each other: a `Marker` knows about
 * SkyBlock islands and acknowledgements, a `WorldOverlayDefinition` knows about projection and fading, and
 * neither module is on the other's classpath. Same split as the two `Notification` types, for the same reason.
 *
 * Reconciled rather than rebuilt each frame. The overlay layer holds registrations owned by a scope, and
 * tearing the whole set down twenty times a second would churn every `UiState` the nodes read — so this
 * diffs, and only touches what changed.
 */
class MinecraftMarkerBridge(
    private val markers: MarkerService,
    private val overlays: WorldOverlayLayer,
    private val log: () -> Logger,
) {

    private val scope = RegistrationScope(UiId.of("sidequest", "markers"))

    /** Live overlays by marker id, so a marker that has not changed is left alone. */
    private val live = HashMap<String, LiveOverlay>()

    private class LiveOverlay(
        val registration: Registration,
        val position: dev.th7bo.sidequest.ui.state.MutableUiState<WorldPosition>,
        val label: dev.th7bo.sidequest.ui.state.MutableUiState<String>,
        /** What the marker looked like when this was built, so a real change can be told from a redraw. */
        var signature: Int,
    )

    /**
     * Brings the overlays in line with what the service says is visible.
     *
     * Called from the tick rather than the render loop: the service's own answer only changes when a marker is
     * placed, expires or the player moves an appreciable distance, and none of that happens between frames.
     */
    fun sync() {
        val visible = markers.visible()
        val wanted = visible.associateBy { it.marker.id }

        // Gone first, so an id being reused for a different marker in the same pass cannot collide.
        val stale = live.keys - wanted.keys
        for (id in stale) {
            live.remove(id)?.registration.let { it?.dispose() }
        }

        for ((id, tracked) in wanted) {
            val existing = live[id]
            if (existing == null) {
                live[id] = create(tracked)
                continue
            }

            // A moved marker updates its state in place. Rebuilding would drop the node's animation and make a
            // marker attached to a walking player flicker every tick.
            existing.position.value = tracked.marker.location.position.toWorldPosition()
            existing.label.value = tracked.label()

            val signature = tracked.signature()
            if (signature != existing.signature) {
                // Something that is baked into the definition changed — a colour, a render flag. Those are not
                // observable state, so the overlay has to be replaced.
                existing.registration.dispose()
                live[id] = create(tracked)
            }
        }
    }

    private fun create(tracked: TrackedMarker): LiveOverlay {
        val marker = tracked.marker
        val position = mutableStateOf(marker.location.position.toWorldPosition())
        val label = mutableStateOf(tracked.label())

        val registration = overlays.register(
            scope,
            WorldOverlayDefinition(
                id = UiId.of("sidequest", "marker." + marker.id.sanitised()),
                position = position,
                label = label,
                color = Color(marker.colour ?: marker.kind.defaultColour),
                // The kind's range is the far edge of the fade, so a marker fades out as it reaches the
                // distance at which the service stops reporting it — rather than vanishing at full opacity.
                fade = DistanceFade(
                    nearDistance = (marker.visibilityRange ?: marker.kind.defaultRange) * FADE_START,
                    farDistance = marker.visibilityRange ?: marker.kind.defaultRange,
                ),
                edgeIndicator = if (marker.render.edgeIndicator) EdgeIndicator() else EdgeIndicator.Disabled,
                showsDistance = marker.render.distanceLabel,
                // Nearer markers already draw over further ones by the layer's own ordering; this is the
                // tie-break for two at the same distance, and a route position is the only thing that has an
                // opinion about it.
                priority = marker.routeOrder ?: 0,
            ),
        )
        log().trace { "Drawing marker ${marker.id}" }
        return LiveOverlay(registration, position, label, tracked.signature())
    }

    /** Releases every overlay. For a full teardown. */
    fun dispose() {
        live.clear()
        if (!scope.isDisposed) scope.dispose()
    }

    private fun TrackedMarker.label(): String {
        val base = marker.label.ifEmpty { marker.kind.displayName }
        // A route marker says where it comes in the order. Without it, four identical waypoints are four
        // identical waypoints and the order they are meant to be visited in is invisible.
        val order = marker.routeOrder?.let { "$it. " } ?: ""
        return order + base
    }

    /**
     * What would force a rebuild if it changed.
     *
     * Only the fields baked into the definition — position and label are observable state and are updated in
     * place, so they are deliberately absent. Including them would rebuild every marker on every step.
     */
    private fun TrackedMarker.signature(): Int = listOf(
        marker.colour,
        marker.visibilityRange,
        marker.render,
        marker.routeOrder,
        marker.kind,
    ).hashCode()

    private fun dev.th7bo.sidequest.platform.skyblock.SqPosition.toWorldPosition() = WorldPosition(x, y, z)

    /** A `UiId` path is `[a-z0-9_]`; a marker id is a UUID. The same sanitiser the notification sink needs. */
    private fun String.sanitised(): String = lowercase().map { character ->
        if (character in 'a'..'z' || character in '0'..'9' || character == '_') character else '_'
    }.joinToString("")

    private companion object {
        /** Where the fade begins, as a fraction of the visible range. */
        const val FADE_START = 0.6
    }
}
