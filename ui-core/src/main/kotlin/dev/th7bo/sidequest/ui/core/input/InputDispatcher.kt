package dev.th7bo.sidequest.ui.core.input

import dev.th7bo.sidequest.ui.core.focus.FocusManager
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.input.CharTypedEvent
import dev.th7bo.sidequest.ui.input.EventPhase
import dev.th7bo.sidequest.ui.input.InputEvent
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.KeyDownEvent
import dev.th7bo.sidequest.ui.input.KeyUpEvent
import dev.th7bo.sidequest.ui.input.Modifiers
import dev.th7bo.sidequest.ui.input.MouseButton
import dev.th7bo.sidequest.ui.input.PointerDownEvent
import dev.th7bo.sidequest.ui.input.PointerEvent
import dev.th7bo.sidequest.ui.input.PointerDragEvent
import dev.th7bo.sidequest.ui.input.PointerEnterEvent
import dev.th7bo.sidequest.ui.input.PointerExitEvent
import dev.th7bo.sidequest.ui.input.PointerMoveEvent
import dev.th7bo.sidequest.ui.input.PointerUpEvent
import dev.th7bo.sidequest.ui.input.ScrollEvent
import dev.th7bo.sidequest.ui.state.UiThread

/**
 * The single place input enters the framework.
 *
 * Components never poll global mouse or keyboard state; everything arrives here, is hit
 * tested once, and is delivered through an explicit capture / target / bubble walk.
 * That is what makes input testable without a window, and what makes pointer positions
 * correct inside scaled subtrees — each handler receives the position already converted
 * into its own local space.
 */
public class InputDispatcher(
    private val focus: FocusManager,
    private val rootProvider: () -> UiNode?,
) {

    /** The node holding pointer capture, if a press is in progress. */
    public var pointerCaptureNode: UiNode? = null
        private set

    /** The button that opened the current capture. */
    private var captureButton: MouseButton? = null

    /** Deepest-last chain of nodes currently under the pointer. */
    private var hoverPath: List<UiNode> = emptyList()

    /** Last pointer position in root space. */
    public var pointerPosition: Vec2 = Vec2.Zero
        private set

    private val scratchPath = ArrayList<UiNode>(DEFAULT_DEPTH)

    // -- pointer ------------------------------------------------------------

    /** @return true if a node consumed the event. */
    public fun pointerMoved(position: Vec2, modifiers: Modifiers = Modifiers.None): Boolean {
        UiThread.check()
        val delta = position - pointerPosition
        pointerPosition = position

        val captured = pointerCaptureNode
        if (captured != null) {
            val button = captureButton ?: MouseButton.LEFT
            return dispatchToPath(listOf(captured), PointerDragEvent(position, delta, button, modifiers))
        }

        val path = hitTest(position)
        updateHover(path, position)
        if (path.isEmpty()) return false
        return dispatchToPath(path, PointerMoveEvent(position, delta, modifiers))
    }

    public fun pointerPressed(
        position: Vec2,
        button: MouseButton = MouseButton.LEFT,
        modifiers: Modifiers = Modifiers.None,
        clickCount: Int = 1,
    ): Boolean {
        UiThread.check()
        pointerPosition = position

        val path = hitTest(position)
        val target = path.lastOrNull()

        // A press outside every focusable node drops focus — the "click outside" rule.
        if (target == null || !containsFocused(path)) {
            focus.clearFocus()
        }
        if (target == null) return false

        if (target.focusable) focus.requestFocus(target)

        val consumed = dispatchToPath(path, PointerDownEvent(position, button, modifiers, clickCount))

        // Capture is claimed by the deepest node in the path that asked for it, so a
        // drag started on a knob keeps receiving moves even once the cursor leaves it.
        val capturer = path.lastOrNull { it.capturesPointer }
        if (capturer != null) {
            pointerCaptureNode = capturer
            captureButton = button
        }
        return consumed
    }

    public fun pointerReleased(
        position: Vec2,
        button: MouseButton = MouseButton.LEFT,
        modifiers: Modifiers = Modifiers.None,
    ): Boolean {
        UiThread.check()
        pointerPosition = position

        val captured = pointerCaptureNode
        if (captured != null) {
            pointerCaptureNode = null
            captureButton = null
            val consumed = dispatchToPath(listOf(captured), PointerUpEvent(position, button, modifiers))
            // Hover may have moved elsewhere during the drag.
            updateHover(hitTest(position), position)
            return consumed
        }

        val path = hitTest(position)
        if (path.isEmpty()) return false
        return dispatchToPath(path, PointerUpEvent(position, button, modifiers))
    }

    public fun scrolled(
        position: Vec2,
        scrollX: Float,
        scrollY: Float,
        modifiers: Modifiers = Modifiers.None,
    ): Boolean {
        UiThread.check()
        val path = hitTest(position)
        if (path.isEmpty()) return false
        return dispatchToPath(path, ScrollEvent(position, scrollX, scrollY, modifiers))
    }

    /** Cancels any in-progress capture, e.g. when a screen closes mid-drag. */
    public fun cancelPointerCapture() {
        pointerCaptureNode = null
        captureButton = null
    }

    // -- keyboard -----------------------------------------------------------

    /**
     * Routes a key press.
     *
     * Tab traversal is handled here rather than in individual controls, so every
     * focusable node gets keyboard navigation without implementing anything.
     */
    public fun keyPressed(
        key: Key,
        modifiers: Modifiers = Modifiers.None,
        isRepeat: Boolean = false,
        rawCode: Int = -1,
    ): Boolean {
        UiThread.check()

        val event = KeyDownEvent(key, modifiers, isRepeat, rawCode)
        if (dispatchToFocusPath(event)) return true

        return when {
            key == Key.TAB && modifiers.shift -> focus.focusPrevious()
            key == Key.TAB -> focus.focusNext()
            key == Key.ESCAPE && focus.focused != null -> {
                // Focus is dropped, but the press is *not* claimed. Dropping focus is not
                // on its own a reason to swallow Escape: the host's own handling — closing
                // the screen — has to still run on the same press. Reporting this handled
                // cost the player an extra press for every control they had clicked, whose
                // only visible effect was a focus ring going away.
                //
                // Anything with a real dismissable state — an open popup, a field being
                // edited, a search query — consumes Escape above, in the focus path, and
                // still gets its own press.
                focus.clearFocus()
                false
            }
            else -> false
        }
    }

    public fun keyReleased(
        key: Key,
        modifiers: Modifiers = Modifiers.None,
        rawCode: Int = -1,
    ): Boolean {
        UiThread.check()
        return dispatchToFocusPath(KeyUpEvent(key, modifiers, rawCode))
    }

    public fun charTyped(codePoint: Int, modifiers: Modifiers = Modifiers.None): Boolean {
        UiThread.check()
        return dispatchToFocusPath(CharTypedEvent(codePoint, modifiers))
    }

    // -- internals ----------------------------------------------------------

    /** Root-to-target chain of nodes under [position], empty if nothing was hit. */
    public fun hitTest(position: Vec2): List<UiNode> {
        val root = rootProvider() ?: return emptyList()
        scratchPath.clear()
        if (!root.hitTest(position, scratchPath)) return emptyList()
        return ArrayList(scratchPath)
    }

    private fun containsFocused(path: List<UiNode>): Boolean {
        val focused = focus.focused ?: return false
        return path.contains(focused)
    }

    private fun dispatchToFocusPath(event: InputEvent): Boolean {
        val focused = focus.focused ?: return false
        val path = ArrayList<UiNode>(DEFAULT_DEPTH)
        var node: UiNode? = focused
        while (node != null) {
            path.add(node)
            node = node.parent
        }
        path.reverse()
        return dispatchToPath(path, event)
    }

    /**
     * Walks [path] root-to-target in the capture phase, fires on the target, then walks
     * back up in the bubble phase. Stops as soon as a handler consumes the event.
     *
     * A pointer event's position is rewritten into each node's local space immediately
     * before that node's handler runs.
     */
    private fun dispatchToPath(path: List<UiNode>, event: InputEvent): Boolean {
        if (path.isEmpty()) return false

        val lastIndex = path.size - 1

        event.setPhaseForDispatch(EventPhase.CAPTURE)
        for (index in 0 until lastIndex) {
            deliverTo(path[index], event)
            if (event.isConsumed) return true
        }

        event.setPhaseForDispatch(EventPhase.TARGET)
        deliverTo(path[lastIndex], event)
        if (event.isConsumed) return true

        event.setPhaseForDispatch(EventPhase.BUBBLE)
        for (index in lastIndex - 1 downTo 0) {
            deliverTo(path[index], event)
            if (event.isConsumed) return true
        }
        return false
    }

    private fun deliverTo(node: UiNode, event: InputEvent) {
        if (event is PointerEvent) {
            event.setPositionForDispatch(node.toLocal(event.rootPosition))
        }
        node.deliver(event)
    }

    /** Fires enter/exit events for the difference between the old and new hover paths. */
    private fun updateHover(newPath: List<UiNode>, position: Vec2) {
        if (newPath == hoverPath) return

        for (node in hoverPath) {
            if (!newPath.contains(node)) {
                node.isHovered = false
                node.invalidatePaint()
                val exit = PointerExitEvent(position)
                exit.setPositionForDispatch(node.toLocal(position))
                exit.setPhaseForDispatch(EventPhase.TARGET)
                node.deliver(exit)
            }
        }
        for (node in newPath) {
            if (!hoverPath.contains(node)) {
                node.isHovered = true
                node.invalidatePaint()
                val enter = PointerEnterEvent(position)
                enter.setPositionForDispatch(node.toLocal(position))
                enter.setPhaseForDispatch(EventPhase.TARGET)
                node.deliver(enter)
            }
        }
        hoverPath = newPath
    }

    /** Clears hover and capture. Called when the tree is replaced. */
    public fun reset() {
        for (node in hoverPath) node.isHovered = false
        hoverPath = emptyList()
        cancelPointerCapture()
    }

    private companion object {
        const val DEFAULT_DEPTH = 8
    }
}
