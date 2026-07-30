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

        // 3. Face a known direction, so "left" and "right" mean something a test can check.
        //
        //    Yaw 0 faces south, which is +Z. Asserted rather than assumed: the whole point of the section
        //    below is that the projection agrees with the camera, and starting from an unverified premise
        //    about the camera would just move the assumption somewhere less visible.
        onClient(context) { client ->
            val player = checkNotNull(client.player) { "No player" }
            player.yRot = 0f
            player.yHeadRot = 0f
            player.xRot = 0f
        }
        context.waitTicks(SETTLE_TICKS)
        onClient(context) { client ->
            val look = checkNotNull(client.player).lookAngle
            check(look.z > FACING_TOLERANCE && kotlin.math.abs(look.x) < 1 - FACING_TOLERANCE) {
                "Yaw 0 should look south down +Z, got $look"
            }
        }

        // 4. A waypoint dead ahead, which lands in the centre.
        //
        //    On its own this proves almost nothing — the centre is the one point a 180° roll of the camera
        //    basis maps to itself, which is exactly how the basis stayed wrong through an earlier version of
        //    this test. It is here as the baseline for the two that follow.
        val eye = computeOnClient(context) { client ->
            val player = checkNotNull(client.player)
            WorldPosition(player.x, player.eyeY, player.z)
        }
        onClient(context) {
            SidequestWorld.placeWaypoint(WorldPosition(eye.x, eye.y, eye.z + WAYPOINT_DISTANCE))
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("waypoint_ahead")
        assertDrawnAt(context, "dead ahead", horizontal = Side.CENTRE, vertical = Side.CENTRE)

        // 5. West, which is the right hand of somebody facing south. This is the assertion that the old
        //    projector failed: it built the horizontal axis along the camera's *left*, so this drew on the
        //    wrong side of the screen.
        onClient(context) {
            SidequestWorld.placeWaypoint(
                WorldPosition(eye.x - WAYPOINT_OFFSET, eye.y, eye.z + WAYPOINT_DISTANCE),
            )
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("waypoint_right")
        assertDrawnAt(context, "to the west", horizontal = Side.HIGH, vertical = Side.CENTRE)

        // 6. And above, which the old projector also inverted — up was derived from the wrong right, so it
        //    came out pointing at the ground.
        onClient(context) {
            SidequestWorld.placeWaypoint(
                WorldPosition(eye.x, eye.y + WAYPOINT_OFFSET, eye.z + WAYPOINT_DISTANCE),
            )
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("waypoint_above")
        assertDrawnAt(context, "overhead", horizontal = Side.CENTRE, vertical = Side.LOW)

        // 7. Turn around. The waypoint is now behind the camera and must become an edge indicator pointing
        //    back at it — not a marker mirrored to the wrong side.
        onClient(context) { client ->
            val player = checkNotNull(client.player)
            player.yRot += HALF_TURN
            player.yHeadRot = player.yRot
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("waypoint_behind")

        onClient(context) {
            val drawn = SidequestHudLayer.lastResolvedOverlays.singleOrNull()
                ?: error("The waypoint should still be drawn as an indicator, got ${SidequestHudLayer.lastResolvedOverlays}")
            check(drawn.isEdgeIndicator) {
                "A waypoint behind the camera must be an edge indicator, not a marker at ${drawn.screenPosition}"
            }
        }

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

    /** Which half of the screen something should be in. */
    private enum class Side { LOW, CENTRE, HIGH }

    /**
     * Checks where the waypoint was actually painted.
     *
     * Reads what the layer resolved on the last frame rather than what is registered. An overlay being in the
     * registry says nothing about where it landed, and for a long time that was the only thing this test
     * looked at — which is how a projection that was upside down and back to front went unnoticed.
     */
    private fun assertDrawnAt(
        context: ClientGameTestContext,
        what: String,
        horizontal: Side,
        vertical: Side,
    ) {
        onClient(context) { client ->
            val drawn = SidequestHudLayer.lastResolvedOverlays.singleOrNull()
                ?: error("A waypoint $what should be on screen, drew ${SidequestHudLayer.lastResolvedOverlays}")
            check(!drawn.isEdgeIndicator) { "A waypoint $what should be a marker, not an edge indicator" }

            val width = client.window.guiScaledWidth.toFloat()
            val height = client.window.guiScaledHeight.toFloat()
            val at = drawn.screenPosition

            check(sideOf(at.x, width) == horizontal) {
                "A waypoint $what should be $horizontal horizontally, drew x=${at.x} of $width"
            }
            check(sideOf(at.y, height) == vertical) {
                "A waypoint $what should be $vertical vertically, drew y=${at.y} of $height"
            }
        }
    }

    /**
     * Which side of the middle a coordinate is on.
     *
     * The dead band is generous on purpose. This is checking that a waypoint is on the correct side of the
     * screen, which is the thing that was broken; pinning an exact pixel would only make the test brittle
     * against the field of view and the window size.
     */
    private fun sideOf(value: Float, extent: Float): Side {
        val fraction = value / extent
        return when {
            fraction < HALF - DEAD_BAND -> Side.LOW
            fraction > HALF + DEAD_BAND -> Side.HIGH
            else -> Side.CENTRE
        }
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

        /**
         * How far to one side the off-centre waypoints go.
         *
         * Eight blocks at twenty away is about 22°, comfortably inside a 70° frustum but well clear of the
         * dead band — so it must resolve as a marker on a definite side rather than as an edge indicator.
         */
        const val WAYPOINT_OFFSET = 8.0

        const val HALF_TURN = 180f
        const val HALF = 0.5f

        /** How far from the middle counts as "a side". */
        const val DEAD_BAND = 0.08f

        /** How close the look vector has to be to a cardinal direction for the premise to hold. */
        const val FACING_TOLERANCE = 0.99
    }
}
