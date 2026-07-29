package dev.th7bo.sidequest.ui.core.focus

import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.input.EventPhase
import dev.th7bo.sidequest.ui.input.FocusEvent

/**
 * Owns keyboard focus for one UI tree.
 *
 * Focus is stored as a reference to a node *and* as that node's identity path. The
 * reference is what the runtime uses; the path is what survives a node being discarded
 * by virtualization and later rematerialized, so scrolling a focused row out of view
 * and back does not silently drop focus.
 */
public class FocusManager {

    private var rootProvider: () -> UiNode? = { null }

    /** The node that currently has focus, if any. */
    public var focused: UiNode? = null
        private set

    /**
     * Identity path of the focused node, retained even when [focused] has been
     * discarded. [restoreIfPossible] uses it to re-acquire focus.
     */
    public var focusedPath: List<UiId> = emptyList()
        private set

    internal fun attach(provider: () -> UiNode?) {
        rootProvider = provider
    }

    /**
     * Moves focus to [node].
     *
     * @return true if focus moved. A node that is not [UiNode.focusable] or not visible
     * is refused rather than silently accepted.
     */
    public fun requestFocus(node: UiNode): Boolean {
        if (!node.focusable || !node.isVisible) return false
        if (focused === node) return true

        clearFocus()
        focused = node
        focusedPath = pathOf(node)
        node.isFocused = true
        node.invalidatePaint()
        deliverFocusEvent(node, gained = true)
        return true
    }

    /** Removes focus from whatever holds it. Retains the path for later restoration. */
    public fun clearFocus() {
        val previous = focused ?: return
        focused = null
        previous.isFocused = false
        previous.invalidatePaint()
        deliverFocusEvent(previous, gained = false)
    }

    /** Forgets both the focused node and its remembered path. */
    public fun reset() {
        clearFocus()
        focusedPath = emptyList()
    }

    /**
     * Re-acquires focus for the remembered path if a matching node exists again.
     * Called after a subtree is rematerialized.
     *
     * @return true if focus was restored.
     */
    public fun restoreIfPossible(): Boolean {
        if (focused != null || focusedPath.isEmpty()) return false
        val node = resolve(focusedPath) ?: return false
        return requestFocus(node)
    }

    /** Moves focus to the next focusable node in tree order, wrapping at the end. */
    public fun focusNext(): Boolean = move(forward = true)

    /** Moves focus to the previous focusable node in tree order, wrapping at the start. */
    public fun focusPrevious(): Boolean = move(forward = false)

    /** Focusable nodes in depth-first order — the order Tab walks. */
    public fun focusOrder(): List<UiNode> {
        val root = rootProvider() ?: return emptyList()
        val result = ArrayList<UiNode>()
        collectFocusable(root, result)
        return result
    }

    private fun move(forward: Boolean): Boolean {
        val order = focusOrder()
        if (order.isEmpty()) return false

        val currentIndex = focused?.let(order::indexOf) ?: -1
        val nextIndex = when {
            currentIndex < 0 -> if (forward) 0 else order.size - 1
            forward -> (currentIndex + 1) % order.size
            else -> (currentIndex - 1 + order.size) % order.size
        }
        return requestFocus(order[nextIndex])
    }

    private fun collectFocusable(node: UiNode, into: MutableList<UiNode>) {
        if (!node.isVisible) return
        if (node.focusable) into.add(node)
        for (child in node.children) collectFocusable(child, into)
    }

    private fun pathOf(node: UiNode): List<UiId> {
        val path = ArrayList<UiId>()
        var current: UiNode? = node
        while (current != null) {
            path.add(current.id)
            current = current.parent
        }
        path.reverse()
        return path
    }

    private fun resolve(path: List<UiId>): UiNode? {
        var current = rootProvider() ?: return null
        if (path.firstOrNull() != current.id) return null
        for (index in 1 until path.size) {
            current = current.children.firstOrNull { it.id == path[index] } ?: return null
        }
        return current.takeIf { it.focusable && it.isVisible }
    }

    private fun deliverFocusEvent(node: UiNode, gained: Boolean) {
        val event = FocusEvent(gained)
        event.setPhaseForDispatch(EventPhase.TARGET)
        node.deliver(event)
    }
}
