package dev.th7bo.sidequest.gametest

import dev.th7bo.sidequest.SidequestHuds
import dev.th7bo.sidequest.ui.geometry.Anchor
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.minecraft.hud.SidequestHudLayer
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/**
 * Renders the mining XP HUD in a real world and captures it.
 *
 * A HUD only draws during gameplay, so unlike the configuration screen this test has to
 * enter a world. It is the only way to see the element over the game rather than over a
 * blank screen — which is the whole point of a HUD.
 */
class HudRenderTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        context.waitTicks(SETTLE_TICKS)

        onClient(context) { client ->
            client.options.guiScale().set(BASELINE_GUI_SCALE)
            client.resizeGui()
        }

        context.worldBuilder().create().use { singleplayer ->
            context.waitTicks(WORLD_SETTLE_TICKS)

            // 1. The default placement, straight from the definition.
            context.takeScreenshot("hud_mining_xp_default")

            onClient(context) {
                val layer = checkNotNull(SidequestHudLayer.hudLayer) { "The HUD layer never built" }
                check(layer.elementCount > 0) { "No HUD elements were registered" }
            }

            // 2. Reactive data: the bar and the readout follow the value.
            onClient(context) { SidequestHuds.miningXp.value = 54_000L }
            context.waitTicks(ANIMATION_TICKS)
            context.takeScreenshot("hud_mining_xp_updated")

            // 3. Scaled, to show the transform path.
            onClient(context) {
                SidequestHudLayer.hudLayer?.ordered?.firstOrNull()?.rescale(1.6f)
            }
            context.waitTicks(SETTLE_TICKS)
            context.takeScreenshot("hud_mining_xp_scaled")

            // 4. Re-anchored to a corner. The element must stay put on screen, which is
            //    the acceptance criterion, and then be moved deliberately.
            onClient(context) { client ->
                val element = SidequestHudLayer.hudLayer?.ordered?.firstOrNull() ?: return@onClient
                val screen = dev.th7bo.sidequest.ui.geometry.Size(
                    client.window.guiScaledWidth.toFloat(),
                    client.window.guiScaledHeight.toFloat(),
                )
                val before = element.placement.value.resolve(element.scaledSize, screen)
                element.reanchor(Anchor.TOP_LEFT, screen)
                val after = element.placement.value.resolve(element.scaledSize, screen)
                check(kotlin.math.abs(before.x - after.x) < 0.5f && kotlin.math.abs(before.y - after.y) < 0.5f) {
                    "Re-anchoring moved the HUD from $before to $after"
                }
                element.moveTo(Vec2(12f, 12f), screen)
                element.rescale(1f)
            }
            context.waitTicks(SETTLE_TICKS)
            context.takeScreenshot("hud_mining_xp_top_left")
        }

        context.waitTicks(SETTLE_TICKS)
    }

    private fun onClient(
        context: ClientGameTestContext,
        action: (net.minecraft.client.Minecraft) -> Unit,
    ) {
        context.runOnClient<RuntimeException> { client -> action(client) }
    }

    private companion object {
        const val SETTLE_TICKS = 10
        const val WORLD_SETTLE_TICKS = 40
        const val ANIMATION_TICKS = 30
        const val BASELINE_GUI_SCALE = 2
    }
}
