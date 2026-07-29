package dev.th7bo.sidequest

import dev.th7bo.sidequest.ui.components.world.DEFAULT_WAYPOINT_COLOR
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.minecraft.hud.SidequestHudLayer
import dev.th7bo.sidequest.ui.notification.Notification
import dev.th7bo.sidequest.ui.notification.NotificationSeverity
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.world.DistanceFade
import dev.th7bo.sidequest.ui.world.WorldOverlayDefinition
import dev.th7bo.sidequest.ui.world.WorldPosition

/**
 * The mod's world overlays and its notification helpers.
 *
 * The waypoint here is the phase 6 demo: an ordinary registration against a scope, which
 * is what makes "disposing the scope takes the waypoint with it" true for third-party
 * modules and not just in principle.
 */
public object SidequestWorld {

    /** Owns the demo waypoint. Disposing it removes the overlay. */
    public val scope: RegistrationScope = RegistrationScope(UiId.of(Sidequest.MOD_ID, "world"))

    /** Where the demo waypoint sits. Observable, so moving it needs no re-registration. */
    public val waypointPosition: MutableUiState<WorldPosition> =
        mutableStateOf(WorldPosition.Origin, "waypoint.demo.position")

    private val waypointVisible: MutableUiState<Boolean> =
        mutableStateOf(false, "waypoint.demo.visible")

    public val demoWaypoint: WorldOverlayDefinition = WorldOverlayDefinition(
        id = UiId.of(Sidequest.MOD_ID, "waypoint.demo"),
        position = waypointPosition,
        label = constantState("Waypoint"),
        color = DEFAULT_WAYPOINT_COLOR,
        // Generous, so the demo stays visible across a normal render distance.
        fade = DistanceFade(nearDistance = 64.0, farDistance = 512.0, minimumDistance = 2.0),
        showsDistance = true,
        visibleWhen = waypointVisible,
    )

    public fun register() {
        SidequestHudLayer.worldOverlays.register(scope, demoWaypoint)
    }

    /** Places the demo waypoint and shows it. */
    public fun placeWaypoint(position: WorldPosition) {
        waypointPosition.value = position
        waypointVisible.value = true
        notify(
            "waypoint.placed",
            "Waypoint set",
            "at ${position.x.toInt()}, ${position.y.toInt()}, ${position.z.toInt()}",
            NotificationSeverity.SUCCESS,
        )
    }

    public fun clearWaypoint() {
        waypointVisible.value = false
    }

    public val isWaypointVisible: Boolean get() = waypointVisible.peek()

    /** Posts a notification. The queue decides whether it shows now or waits. */
    public fun notify(
        name: String,
        title: String,
        message: String? = null,
        severity: NotificationSeverity = NotificationSeverity.INFO,
        coalesceKey: Any? = null,
    ): Boolean = SidequestHudLayer.notifications.post(
        Notification(
            id = UiId.of(Sidequest.MOD_ID, "notify.$name"),
            title = constantState(title),
            message = message?.let { constantState(it) },
            severity = severity,
            coalesceKey = coalesceKey,
        ),
    ) != null
}
