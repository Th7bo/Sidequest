package dev.th7bo.sidequest.gametest

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.ui.core.hud.editor.HudHandle
import dev.th7bo.sidequest.ui.geometry.Anchor
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.minecraft.hud.SidequestHudLayer
import dev.th7bo.sidequest.ui.minecraft.screen.SidequestHudEditorScreen
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest

/**
 * Drives the HUD editor in a running client and captures each state.
 *
 * The headless acceptance tests already prove the arithmetic. What this adds is that the
 * chrome actually draws over the live HUDs, that the inspector lays out at a real GUI
 * scale, and that a drag through the real input path lands where the session says it
 * should — none of which a fake renderer can tell you.
 */
class HudEditorRenderTest : FabricClientGameTest {

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

        // 26.2 no longer exposes the open screen, so the test holds its own reference.
        // It is only ever touched on the client thread.
        lateinit var screen: SidequestHudEditorScreen

        // Every HUD back to its defaults. The gametests share one client and one HUD
        // layer, so without this the editor would start from wherever the previous test
        // left things — and a capture that depends on test order is worth very little.
        onClient(context) {
            val layer = checkNotNull(SidequestHudLayer.hudLayer) { "The HUD layer never built" }
            for (element in layer.ordered) element.reset()
        }

        // 1. The editor open, with nothing selected: safe areas and idle outlines only.
        onClient(context) { client ->
            screen = checkNotNull(Sidequest.createHudEditorScreen()) {
                "The HUD layer was never built, so there was nothing to edit"
            }
            client.setScreenAndShow(screen)
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("hud_editor_idle")

        // 2. A selection, which brings up the handles and the inspector.
        val selected = computeOnClient(context) {
            val session = checkNotNull(screen.session) { "The editor built no session" }
            val element = checkNotNull(session.layer.ordered.firstOrNull()) { "No HUD to select" }
            session.select(element.instance.instanceId)
            session.screenBounds(element).center
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("hud_editor_selected")

        // 3. A drag that snaps to the screen's centre line, so a guide is drawn.
        onClient(context) {
            val session = checkNotNull(screen.session)
            val viewport = screen.viewport

            session.beginDrag(selected)
            // Aim just off the centre line, close enough for the snap to take it.
            session.updateDrag(Vec2(viewport.width / 2f - 2f, viewport.height / 2f))
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("hud_editor_snapping")

        onClient(context) {
            val session = checkNotNull(screen.session)
            val viewport = screen.viewport
            val element = session.selectedElements.first()

            check(session.guides.peek().isNotEmpty()) { "The drag should have snapped and drawn a guide" }
            session.endGesture()

            val bounds = session.screenBounds(element)
            val drift = kotlin.math.abs(bounds.center.x - viewport.width / 2f)
            check(drift < 0.5f) { "The element should sit on the centre line, was $drift off" }
        }

        // 4. Scaling by a corner handle.
        onClient(context) {
            val session = checkNotNull(screen.session)
            val element = session.selectedElements.first()
            val bounds = session.screenBounds(element)
            val corner = Vec2(bounds.right, bounds.bottom)

            session.beginScale(corner, HudHandle.BOTTOM_RIGHT)
            session.updateScale(corner + Vec2(bounds.width * 0.4f, 0f))
            session.endGesture()
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("hud_editor_scaled")

        // 5. Undo puts it back, which is the criterion that matters most here: the
        //    editor is only safe to explore in if every gesture is reversible.
        onClient(context) {
            val session = checkNotNull(screen.session)
            val element = session.selectedElements.first()
            val scaled = element.placement.peek().scale

            check(session.undo.undo()) { "The scale gesture left nothing to undo" }
            check(element.placement.peek().scale != scaled) { "Undo did not change the scale back" }

            session.setAnchor(Anchor.TOP_LEFT)
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("hud_editor_reanchored")

        // Back to the title screen, which is where a client gametest has to end.
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
    }
}
