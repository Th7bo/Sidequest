package dev.th7bo.sidequest.gametest

import dev.th7bo.sidequest.SidequestWorld
import dev.th7bo.sidequest.ui.minecraft.hud.SidequestHudLayer
import dev.th7bo.sidequest.ui.notification.NotificationSeverity
import dev.th7bo.sidequest.ui.world.WorldPosition
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/**
 * Renders notifications and a waypoint in a real world and captures them.
 *
 * The headless tests already prove the queue and the projection arithmetic. What only a
 * running client can show is that the projector agrees with the camera the player is
 * actually looking through — a sign error in the basis is invisible to a fake projector
 * and obvious the moment a waypoint appears behind you.
 */
class WorldOverlayRenderTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        context.waitTicks(SETTLE_TICKS)
        onClient(context) { client ->
            client.options.guiScale().set(BASELINE_GUI_SCALE)
            client.resizeGui()
        }

        context.worldBuilder().create().use {
            run(context)
        }
    }

    private fun run(context: ClientGameTestContext) {
        context.waitTicks(WORLD_SETTLE_TICKS)

        // 1. Notifications of each severity, so the region's ordering and colours are
        //    visible together.
        onClient(context) {
            SidequestWorld.notify("info", "Sidequest loaded", "Everything is fine", NotificationSeverity.INFO)
            SidequestWorld.notify("success", "Waypoint saved", severity = NotificationSeverity.SUCCESS)
            SidequestWorld.notify("warning", "Low durability", "Pickaxe at 12%", NotificationSeverity.WARNING)
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("notifications_stack")

        onClient(context) {
            val showing = SidequestHudLayer.notifications.showing.peek()
            check(showing.isNotEmpty()) { "Nothing was showing" }
            check(showing.first().notification.severity == NotificationSeverity.WARNING) {
                "The warning should sort above the routine ones, got ${showing.map { it.notification.severity }}"
            }
        }

        // 2. Coalescing: five identical pickups must be one toast counting to five.
        onClient(context) {
            SidequestHudLayer.notifications.dismissAll()
            repeat(COALESCE_COUNT) {
                SidequestWorld.notify("pickup", "Picked up Diamond", coalesceKey = "pickup")
            }
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("notifications_coalesced")

        onClient(context) {
            val entry = SidequestHudLayer.notifications.showing.peek().single()
            check(entry.count == COALESCE_COUNT) {
                "Five repeats should be one toast counting to five, was ${entry.count}"
            }
            SidequestHudLayer.notifications.dismissAll()
        }

        // 3. A waypoint placed in front of the player, which must project on screen.
        val ahead = computeOnClient(context) { client ->
            val player = checkNotNull(client.player) { "No player" }
            val look = player.lookAngle
            WorldPosition(
                player.x + look.x * WAYPOINT_DISTANCE,
                player.eyeY,
                player.z + look.z * WAYPOINT_DISTANCE,
            )
        }
        onClient(context) { SidequestWorld.placeWaypoint(ahead) }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("waypoint_ahead")

        onClient(context) {
            val resolved = SidequestHudLayer.worldOverlays
            check(resolved.size == 1) { "The demo waypoint should be registered" }
        }

        // 4. Turn around. The waypoint is now behind the camera and must become an edge
        //    indicator pointing back at it — not a marker mirrored to the wrong side.
        onClient(context) { client ->
            val player = checkNotNull(client.player)
            player.yRot += HALF_TURN
            player.yHeadRot = player.yRot
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("waypoint_behind")

        // 5. And a scope disposal takes the overlay away, which is the phase's second
        //    acceptance criterion seen end to end rather than in a unit test.
        onClient(context) {
            SidequestWorld.scope.dispose()
            check(SidequestHudLayer.worldOverlays.size == 0) {
                "Disposing the owning scope must remove the overlay"
            }
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("waypoint_disposed")

        context.setScreen { null }
        context.waitTicks(SETTLE_TICKS)
    }

    private fun onClient(
        context: ClientGameTestContext,
        action: (net.minecraft.client.Minecraft) -> Unit,
    ) {
        context.runOnClient<RuntimeException> { client -> action(client) }
    }

    private fun <T> computeOnClient(
        context: ClientGameTestContext,
        action: (net.minecraft.client.Minecraft) -> T,
    ): T = context.computeOnClient<T, RuntimeException> { client -> action(client) }

    private companion object {
        const val SETTLE_TICKS = 10
        const val WORLD_SETTLE_TICKS = 40
        const val BASELINE_GUI_SCALE = 2
        const val COALESCE_COUNT = 5
        const val WAYPOINT_DISTANCE = 20.0
        const val HALF_TURN = 180f
    }
}
