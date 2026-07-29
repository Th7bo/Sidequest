package dev.th7bo.sidequest.gametest

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.ui.core.hud.editor.HudHandle
import dev.th7bo.sidequest.ui.geometry.Anchor
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.hud.HudPlacement
import dev.th7bo.sidequest.SidequestHuds
import dev.th7bo.sidequest.ui.components.hud.ProgressHudNode
import dev.th7bo.sidequest.ui.minecraft.hud.SidequestHudLayer
import dev.th7bo.sidequest.ui.minecraft.screen.SidequestHudEditorScreen
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

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

        // Placement has to survive quitting the game, which no single-process test can
        // show. The run directory persists between launches, so this test proves it
        // across two: the last run writes a known placement, and this one asserts the
        // load applied it. On the very first run there is no file, and the check is
        // skipped rather than failed.
        //
        // Asserted against what the *load* applied rather than where the element sits
        // now: the gametests share one client, and the HUD render test runs first and
        // legitimately moves things around.
        val layoutFile = FabricLoader.getInstance().configDir
            .resolve("sidequest/profiles/default/huds.json")
        val hadSavedLayout = Files.exists(layoutFile)

        onClient(context) {
            val layer = checkNotNull(SidequestHudLayer.hudLayer) { "The HUD layer never built" }
            if (hadSavedLayout) {
                val instanceId = checkNotNull(layer.ordered.firstOrNull()) { "No HUD to check" }
                    .instance.instanceId
                val restored = Sidequest.loadedHudPlacements[instanceId]
                check(restored == PERSISTED_PLACEMENT) {
                    "A previous run saved $PERSISTED_PLACEMENT but this one loaded " +
                        "$restored — HUD placement did not survive the restart"
                }
            }

            // Then back to defaults, so the captures below do not depend on test order.
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

        // Preview data: the live values are zeroed first, so a card that still reads
        // sensibly can only be reading the sample. Outside a world there is no real
        // mining XP, and this is the case the editor has to stay usable in.
        onClient(context) {
            SidequestHuds.miningXp.value = 0L
            val element = checkNotNull(SidequestHudLayer.hudLayer?.ordered?.firstOrNull())
            check(element.isEditing.peek()) { "The editor should have marked its elements as editing" }
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("hud_editor_preview_data")

        onClient(context) {
            val element = checkNotNull(SidequestHudLayer.hudLayer?.ordered?.firstOrNull())
            val bar = checkNotNull(findProgressHud(element)) { "No progress card in the mining HUD" }
            // Live XP is zero. A non-empty bar can only be the preview sample, so this is
            // the assertion rather than the screenshot: pixels are hard to read, and the
            // fill fraction says exactly what is being displayed.
            check(bar.fillFraction > 0.1f) {
                "The card should show preview data while editing, but the bar is at ${bar.fillFraction}"
            }
        }

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

        // 6. Leave a known placement on disk for the next run to find.
        onClient(context) {
            val session = checkNotNull(screen.session)
            val element = session.layer.ordered.first()
            element.setPlacement(PERSISTED_PLACEMENT)
            Sidequest.saveHudLayout()
        }
        context.waitTicks(SETTLE_TICKS)

        check(Files.exists(layoutFile)) { "No HUD layout was written to $layoutFile" }
        val written = Files.readString(layoutFile)
        check(PERSISTED_PLACEMENT.anchor.serializedId in written) {
            "The saved layout does not name the anchor by its serialized id: $written"
        }

        // Back to the title screen, which is where a client gametest has to end.
        context.setScreen { null }
        context.waitTicks(SETTLE_TICKS)
    }

    /** Finds the progress card inside a HUD element. */
    private fun findProgressHud(root: dev.th7bo.sidequest.ui.core.tree.UiNode): ProgressHudNode? {
        var found: ProgressHudNode? = null
        root.forEachInTree { if (found == null && it is ProgressHudNode) found = it }
        return found
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
        /** Distinctive enough that a default could never be mistaken for it. */
        val PERSISTED_PLACEMENT = HudPlacement(
            anchor = Anchor.TOP_RIGHT,
            offset = Vec2(-37f, 23f),
            scale = 1.25f,
            zIndex = 2,
            opacity = 0.9f,
        )

        const val SETTLE_TICKS = 10
        const val WORLD_SETTLE_TICKS = 40
        const val BASELINE_GUI_SCALE = 2
    }
}
