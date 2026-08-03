package dev.th7bo.sidequest.ui.minecraft.screen

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.platform.ping.PingStyle
import dev.th7bo.sidequest.ui.components.RadialMenuNode
import dev.th7bo.sidequest.ui.components.RadialOption
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.TextMeasurer
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * The ping wheel: hold the key, push, let go.
 *
 * **Opening a screen rather than drawing on the HUD**, which is the decision everything else follows from.
 * A screen frees the cursor, which is what makes "push the mouse a direction" possible at all — while the
 * mouse is grabbed there is no pointer to read, only camera movement, and turning the player's head to choose
 * a ping would be unusable. It also freezes what the crosshair was on at the moment the wheel opened, which
 * is what somebody means: they aimed, *then* asked for the menu.
 *
 * The wheel commits on **release**, not on click. That is the gesture this is imitating — hold, flick, let
 * go — and it is why the release is handled here rather than through the keybind: Minecraft stops updating
 * keybind state while a screen is open, so the only reliable signal is the event this screen is handed.
 */
public class PingWheelScreen(
    /**
     * The binding that opened it, so releasing that same input is what commits.
     *
     * The mapping itself rather than a key code, because it knows whether it is a mouse button or a key and
     * can answer `matches` for either — and because the player may have rebound it, which a code captured at
     * open time would not follow.
     */
    private val binding: KeyMapping,
    /** Called with the choice, or with null when the wheel was dismissed. */
    private val onChosen: (PingStyle?) -> Unit,
) : SidequestScreen(Component.literal("Ping"), Sidequest.activeTheme()) {

    private var menu: RadialMenuNode? = null

    /** Set once a choice has been made or refused, so it cannot happen twice. */
    private var settled = false

    override fun buildTree(runtime: UiRuntime, measurer: TextMeasurer): UiNode {
        val context = ComponentContext(
            theme = theme,
            animations = runtime.animations,
            scheduler = runtime,
        )
        val node = RadialMenuNode(UiId.of(Sidequest.MOD_ID, "ping.wheel"), context)
        node.options = STYLES.map { style ->
            RadialOption(id = style.wireName, label = style.displayName, colour = style.colour)
        }
        menu = node
        return node
    }

    /**
     * The pointer, as an offset from the middle of the screen.
     *
     * Read from the cursor rather than accumulated from movement deltas, because the cursor is free while a
     * screen is open and is already exactly this. Centring it means the wheel's dead zone is around the
     * crosshair, where the eye already is.
     */
    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        super.mouseMoved(mouseX, mouseY)
        menu?.pointer = Vec2(
            (mouseX - width / 2.0).toFloat(),
            (mouseY - height / 2.0).toFloat(),
        )
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (binding.matchesMouse(event)) {
            settle(menu?.selected?.let { PingStyle.ofWire(it.id) })
            return true
        }
        return super.mouseReleased(event)
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        if (binding.matches(event)) {
            settle(menu?.selected?.let { PingStyle.ofWire(it.id) })
            return true
        }
        return super.keyReleased(event)
    }

    /** Escape dismisses without pinging, like every other screen. */
    override fun shouldCloseOnEsc(): Boolean = true

    override fun removed() {
        // Covers escape and anything else that closes the screen out from under the wheel. Settling with
        // null rather than with the current selection: a screen that vanished is not a choice somebody made.
        settle(null)
        super.removed()
    }

    private fun settle(choice: PingStyle?) {
        if (settled) return
        // Set before the callback, so the `removed` that closing triggers finds the wheel already settled
        // and does not report a second, null choice over the real one.
        settled = true
        onChosen(choice)
        onClose()
    }

    /** No dimming of its own: the wheel draws its own backdrop, and two would be twice as dark. */
    override fun renderBackdrop(graphics: GuiGraphicsExtractor) {
        // Deliberately empty.
    }

    private companion object {
        /**
         * The wheel's contents, in the order they sit around it.
         *
         * Ordered by hand rather than by [PingStyle.entries], because position is the interface: the two
         * that mean "something is wrong" sit opposite the two that mean "come and look", so the hand learns
         * a shape rather than a list. `CUSTOM` is left out — it needs words, and typing them is what the
         * command is for.
         */
        val STYLES = listOf(
            PingStyle.GO_HERE,
            PingStyle.LOOK_HERE,
            PingStyle.ITEM_HERE,
            PingStyle.NPC_HERE,
            PingStyle.DANGER,
            PingStyle.NEED_HELP,
            PingStyle.COME_TO_ME,
        )
    }
}
