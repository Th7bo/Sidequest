package dev.th7bo.sidequest.gametest

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.ui.components.ColorControlNode
import dev.th7bo.sidequest.ui.components.DropdownControlNode
import dev.th7bo.sidequest.ui.minecraft.screen.SidequestConfigScreen
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/**
 * Opens the configuration screen in a real client and captures what it draws.
 *
 * This is the only way to verify the Minecraft adapter: everything below it is covered
 * by pure-JVM tests, but whether `GuiGraphicsExtractor` actually puts pixels where the
 * framework asked can only be answered by the game.
 *
 * The screenshots land in `run/screenshots/` and are meant to be looked at, not merely
 * counted — a scissor rectangle off by a hundred units still produces a valid PNG.
 */
class ConfigScreenRenderTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        context.waitTicks(SETTLE_TICKS)

        // Pin the GUI scale so the captures are deterministic. Minecraft auto-selects a
        // scale from the window size, which would make every screenshot depend on the
        // machine that took it.
        onClient(context) { client ->
            client.options.guiScale().set(BASELINE_GUI_SCALE)
            client.resizeGui()
        }
        context.waitTicks(SETTLE_TICKS)

        // Built on the *client* thread: constructing a Screen reaches for
        // Minecraft.getInstance(), which the gametest API forbids from the test thread.
        // The reference is kept here because Minecraft no longer exposes its current
        // screen for reading.
        val screen = onClientCompute(context) { Sidequest.createConfigScreen() }

        // 1. It opens and draws without throwing.
        context.setScreen { screen }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("config_screen_default")

        // 2. Nothing was left unbalanced. A stray scissor or pose corrupts every later
        //    frame and would never surface as an exception.
        onClient(context) {
            check(!screen.lastFrameWasUnbalanced) {
                "The renderer left its clip/transform/opacity stacks unbalanced"
            }
            check(screen.controller != null) { "The screen controller was never built" }
        }

        // 3. A second category, exercising the sidebar and the rebuild path.
        onClient(context) {
            Sidequest.configScreen.categories.getOrNull(1)?.let {
                screen.controller?.selectCategory(it.id)
            }
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("config_screen_second_category")

        // 4. Search: filtering, category switching and re-layout.
        onClient(context) { screen.controller?.search("notification") }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("config_screen_search")

        // 5. A query that matches nothing must show the empty state, not everything.
        onClient(context) { screen.controller?.search("zzzznotasetting") }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("config_screen_empty_state")

        onClient(context) { screen.controller?.clearSearch() }
        context.waitTicks(SETTLE_TICKS)

        // 5b. Popups: the dropdown list has to escape the row's clip and paint above
        //     every sibling, which is the whole reason the overlay layer exists.
        onClient(context) {
            val dropdown = findControl<DropdownControlNode<*>>(screen)
            checkNotNull(dropdown) { "No dropdown control was built" }
            dropdown.setOpen(true)
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("config_screen_dropdown_open")

        // 5c. Switching category with the dropdown still open. The list is virtualized,
        //     so the row the popup is anchored to is recycled out of the tree — the
        //     popup must go with it rather than float over the new category's content.
        onClient(context) {
            Sidequest.configScreen.categories.getOrNull(1)?.let {
                screen.controller?.selectCategory(it.id)
            }
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("config_screen_dropdown_orphaned")

        onClient(context) {
            val dropdown = findControl<DropdownControlNode<*>>(screen)
            check(dropdown?.isOpen != true) {
                "The dropdown survived a category switch and is now anchored to nothing"
            }
        }

        onClient(context) {
            Sidequest.configScreen.categories.firstOrNull()?.let {
                screen.controller?.selectCategory(it.id)
            }
        }
        context.waitTicks(SETTLE_TICKS)

        onClient(context) {
            val dropdown = findControl<DropdownControlNode<*>>(screen)
            checkNotNull(dropdown) { "No dropdown control was built" }
            dropdown.setOpen(false)
        }
        context.waitTicks(SETTLE_TICKS)

        onClient(context) {
            val color = findControl<ColorControlNode>(screen)
            checkNotNull(color) { "No colour control was built" }
            color.activateForTest()
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("config_screen_color_popup_open")

        onClient(context) {
            val root = screen.overlayRoot
            check(root?.isShowingOverlay == true) { "The colour popup is not showing" }
            root.dismissAll()
        }
        context.waitTicks(SETTLE_TICKS)

        // 6. GUI-scale changes — the acceptance criterion whose arithmetic is tested
        //    headlessly but whose appearance is not.
        for (scale in intArrayOf(3, 1, 2)) {
            onClient(context) { client ->
                client.options.guiScale().set(scale)
                client.resizeGui()
            }
            context.waitTicks(SETTLE_TICKS)
            context.takeScreenshot("config_screen_gui_scale_$scale")
        }

        onClient(context) {
            check(!screen.lastFrameWasUnbalanced) {
                "The renderer became unbalanced after the GUI-scale changes"
            }
        }

        // The harness requires the test to end on the title screen, so the config
        // screen is closed explicitly rather than left open.
        context.setScreen { null }
        context.waitTicks(SETTLE_TICKS)
    }

    /** Finds the first control of a given type anywhere in the screen's tree. */
    private inline fun <reified T> findControl(
        screen: SidequestConfigScreen,
    ): T? {
        var found: T? = null
        screen.uiRoot?.forEachInTree { node -> if (found == null && node is T) found = node }
        return found
    }

    /** Explicit type argument: Kotlin cannot infer the failable consumer's throwable. */
    private fun onClient(
        context: ClientGameTestContext,
        action: (net.minecraft.client.Minecraft) -> Unit,
    ) {
        context.runOnClient<RuntimeException> { client -> action(client) }
    }

    private fun <T> onClientCompute(
        context: ClientGameTestContext,
        action: (net.minecraft.client.Minecraft) -> T,
    ): T = context.computeOnClient<T, RuntimeException> { client -> action(client) }

    private companion object {
        /** Long enough for layout and any entry animation to settle before capturing. */
        const val SETTLE_TICKS = 10

        /** At 1280x720 this gives a 640x360 logical viewport — a realistic desktop. */
        const val BASELINE_GUI_SCALE = 2
    }
}
