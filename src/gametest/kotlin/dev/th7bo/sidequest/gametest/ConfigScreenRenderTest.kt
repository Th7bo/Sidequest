package dev.th7bo.sidequest.gametest

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.SidequestGallery
import dev.th7bo.sidequest.ui.components.KeybindControlNode
import dev.th7bo.sidequest.ui.components.ListControlNode
import dev.th7bo.sidequest.ui.components.TextAreaControlNode
import dev.th7bo.sidequest.ui.components.TextFieldControlNode
import dev.th7bo.sidequest.ui.core.component.MissingComponentNode
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.Modifiers
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

        onClient(context) {
            screen.controller?.clearSearch()
            Sidequest.configScreen.categories.firstOrNull()?.let {
                screen.controller?.selectCategory(it.id)
            }
        }
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

        // 7. The component gallery, which is the one screen that shows every standard
        //    control at once — including the two that had no renderer until phase 7.
        context.setScreen { null }
        context.waitTicks(SETTLE_TICKS)

        lateinit var gallery: SidequestConfigScreen
        onClient(context) { client ->
            gallery = Sidequest.createGalleryScreen()
            client.setScreenAndShow(gallery)
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("gallery_inputs")

        onClient(context) {
            // Nothing may fall through to the missing-component placeholder.
            val missing = mutableListOf<String>()
            gallery.uiRoot?.forEachInTree { node ->
                if (node is MissingComponentNode) missing.add(node.id.value)
            }
            check(missing.isEmpty()) { "Controls with no renderer: $missing" }
        }

        for (index in 1..2) {
            onClient(context) {
                gallery.controller?.let { controller ->
                    SidequestGallery.screen.categories.getOrNull(index)?.let { controller.selectCategory(it.id) }
                }
            }
            context.waitTicks(SETTLE_TICKS)
            context.takeScreenshot("gallery_category_$index")
        }

        // 8. The two controls reported as broken in game: a keybind and a multiline
        //    text area, driven through the real screen at a real GUI scale rather than
        //    against a node in isolation, which is where they already passed.
        //
        //    Both are filtered to with the search box first. They sit near the bottom of
        //    a long category, and a click on a row that is scrolled past the fold lands
        //    on the scroll container's clip rather than on the control — that is the
        //    harness missing, not the control being broken.
        onClient(context) { gallery.controller?.search("Keybind") }
        context.waitTicks(SETTLE_TICKS)

        onClient(context) {
            val keybind = checkNotNull(findControl<KeybindControlNode>(gallery)) { "No keybind control" }
            val bounds = keybind.absoluteBounds()
            val runtime = checkNotNull(gallery.uiRuntime) { "No runtime" }
            check(runtime.input.hitTest(bounds.center).contains(keybind)) {
                "The keybind at $bounds is not reachable by a click (viewport ${runtime.viewport})"
            }

            val before = keybind.bindingLabel
            runtime.input.pointerPressed(bounds.center)
            check(keybind.isCapturing) { "Clicking the keybind did not start capturing" }
            check(keybind.bindingLabel != before) {
                "The label still reads '$before' while capturing — it did not refresh"
            }

            runtime.input.keyPressed(Key.K)
            check(!keybind.isCapturing) { "The keybind is still capturing after a key press" }
            // The bug the player reported: the value was set but the label kept whatever
            // text it had until a category switch rebuilt the control.
            check(keybind.bindingLabel.contains("K")) {
                "The label reads '${keybind.bindingLabel}' after binding K"
            }
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("gallery_keybind_bound")

        // The editable list: every row's buttons have to sit at the same trailing edge,
        // which they did not while the row sized itself to its label.
        onClient(context) { gallery.controller?.search("Editable") }
        context.waitTicks(SETTLE_TICKS)

        onClient(context) {
            val list = checkNotNull(findControl<ListControlNode<*>>(gallery)) { "No list control" }
            val right = list.absoluteBounds().right
            val entries = list.children.first().children.dropLast(1)
            check(entries.isNotEmpty()) { "The list built no entry rows" }
            for (entry in entries) {
                val actions = entry.children.last().absoluteBounds()
                check(kotlin.math.abs(actions.right - right) < 0.5f) {
                    "Row ${entry.id.value} ends its buttons at ${actions.right}, not $right"
                }
            }
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("gallery_list_aligned")

        onClient(context) { gallery.controller?.search("Multiline") }
        context.waitTicks(SETTLE_TICKS)

        onClient(context) {
            val area = checkNotNull(findControl<TextAreaControlNode>(gallery)) { "No text area control" }
            val runtime = checkNotNull(gallery.uiRuntime) { "No runtime" }
            val bounds = area.absoluteBounds()
            check(bounds.width > 0f && bounds.height > 0f) { "The text area has no bounds: $bounds" }
            check(runtime.input.hitTest(bounds.center).contains(area)) {
                "The text area at $bounds is not reachable by a click (viewport ${runtime.viewport})"
            }

            runtime.input.pointerPressed(bounds.center)
            check(area.isEditing) { "Clicking the text area did not start editing" }

            val lengthBefore = area.text.length
            check(runtime.input.charTyped('Z'.code)) {
                "The typed character reached nothing — focus did not follow the click"
            }
            check(area.isEditing) { "Typing stopped the edit" }
            check(area.text.length == lengthBefore + 1) {
                "Typing did not insert: '${area.text}'"
            }

            runtime.input.keyPressed(Key.ENTER)
            check(area.lineCount > 1) { "Enter did not insert a newline: '${area.text}'" }
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("gallery_text_area_typed")

        // 8b. Caret navigation, against the real font — the caret's position comes from
        //     text measurement, so a fake measurer proves nothing about where it lands.
        onClient(context) {
            val area = checkNotNull(findControl<TextAreaControlNode>(gallery)) { "No text area control" }
            val runtime = checkNotNull(gallery.uiRuntime) { "No runtime" }

            runtime.input.keyPressed(Key.HOME, Modifiers.Control)
            check(area.caret == 0) { "Ctrl+Home left the caret at ${area.caret}" }
            check(area.firstVisibleLine == 0) { "The view did not scroll back to the top" }

            runtime.input.keyPressed(Key.END)
            val firstLineEnd = area.caret
            check(firstLineEnd > 0) { "End on the first line went nowhere" }

            runtime.input.keyPressed(Key.ARROW_DOWN)
            check(area.caret > firstLineEnd) { "Down did not reach the second line" }

            runtime.input.keyPressed(Key.ARROW_UP)
            check(area.caret == firstLineEnd) { "Up did not return to where Down started" }
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("gallery_text_area_caret")

        onClient(context) {
            val area = checkNotNull(findControl<TextAreaControlNode>(gallery)) { "No text area control" }
            val runtime = checkNotNull(gallery.uiRuntime) { "No runtime" }

            // Ctrl+Backspace from the end of a line takes the word before it, not a letter.
            val before = area.text
            runtime.input.keyPressed(Key.BACKSPACE, Modifiers.Control)
            val removed = before.length - area.text.length
            check(removed > 1) { "Ctrl+Backspace removed $removed character(s) from '$before'" }

            // And a click lands the caret where it was clicked rather than at the end.
            val bounds = area.absoluteBounds()
            runtime.input.pointerPressed(Vec2(bounds.x + 6f, bounds.y + 6f))
            check(area.caret < area.text.length) {
                "Clicking near the start left the caret at the end (${area.caret})"
            }
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("gallery_text_area_word_delete")

        // 8c. The single-line field shares the same editor, so it gets the same caret and
        //     the same word deletion. Only the drawing differs.
        onClient(context) { gallery.controller?.search("Single-line") }
        context.waitTicks(SETTLE_TICKS)

        onClient(context) {
            val field = checkNotNull(findControl<TextFieldControlNode>(gallery)) { "No text field" }
            val runtime = checkNotNull(gallery.uiRuntime) { "No runtime" }

            runtime.input.pointerPressed(field.absoluteBounds().center)
            check(field.isEditing) { "Clicking the field did not start editing" }

            runtime.input.keyPressed(Key.END)
            for (character in " two words") runtime.input.charTyped(character.code)
            check(field.text.endsWith("two words")) { "Typing did not land: '${field.text}'" }

            runtime.input.keyPressed(Key.BACKSPACE, Modifiers.Control)
            check(field.text.endsWith("two ")) {
                "Ctrl+Backspace did not take the whole word: '${field.text}'"
            }

            runtime.input.keyPressed(Key.HOME)
            check(field.caret == 0) { "Home left the caret at ${field.caret}" }
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("gallery_text_field_caret")

        // 8d. Moving to another input has to put the first one away. Both fields drawing a
        //     caret at once is what the player saw, and only the control losing focus can
        //     notice — nothing else tells it.
        onClient(context) { gallery.controller?.search("field") }
        context.waitTicks(SETTLE_TICKS)

        onClient(context) {
            val fields = findControls<TextFieldControlNode>(gallery)
            check(fields.size >= 2) { "Expected two text fields in the search results, got ${fields.size}" }
            val runtime = checkNotNull(gallery.uiRuntime) { "No runtime" }

            runtime.input.pointerPressed(fields[0].absoluteBounds().center)
            check(fields[0].isEditing) { "The first field did not start editing" }

            runtime.input.pointerPressed(fields[1].absoluteBounds().center)
            check(fields[1].isEditing) { "The second field did not start editing" }
            check(!fields[0].isEditing) {
                "The first field is still editing — its caret is drawn next to the one that has focus"
            }
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("gallery_one_caret_at_a_time")

        // 8e. Escape has to reach the screen as soon as nothing is left to dismiss. The
        //     screen closes when the framework reports the press unhandled, so claiming
        //     one without a visible effect costs the player an extra press.
        onClient(context) {
            val runtime = checkNotNull(gallery.uiRuntime) { "No runtime" }
            val fields = findControls<TextFieldControlNode>(gallery)
            check(fields.any { it.isEditing }) { "Expected a field to still be editing" }

            check(runtime.input.keyPressed(Key.ESCAPE)) {
                "Escape while editing should stop the edit and be claimed"
            }
            check(fields.none { it.isEditing }) { "Escape did not stop the edit" }

            check(!runtime.input.keyPressed(Key.ESCAPE)) {
                "Escape was swallowed with nothing left to dismiss — the screen would not close"
            }
        }
        context.waitTicks(SETTLE_TICKS)

        onClient(context) { gallery.controller?.clearSearch() }
        context.waitTicks(SETTLE_TICKS)

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

    /** Every control of a given type, in tree order. */
    private inline fun <reified T> findControls(
        screen: SidequestConfigScreen,
    ): List<T> {
        val found = mutableListOf<T>()
        screen.uiRoot?.forEachInTree { node -> if (node is T) found.add(node) }
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
