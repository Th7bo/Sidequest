package dev.th7bo.sidequest.ui.core.overlay

import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.input.EventPhase
import dev.th7bo.sidequest.ui.input.InputEvent
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.KeyDownEvent
import dev.th7bo.sidequest.ui.input.PointerDownEvent
import dev.th7bo.sidequest.ui.state.Disposable

/** Where a popup sits relative to the control that opened it. */
public enum class OverlayPlacement {
    /** Below the anchor, left edges aligned. */
    BELOW_START,

    /** Below the anchor, right edges aligned — for a right-aligned control. */
    BELOW_END,

    ABOVE_START,
    ABOVE_END,
    ;

    internal val isBelow: Boolean get() = this == BELOW_START || this == BELOW_END
    internal val alignsEnd: Boolean get() = this == BELOW_END || this == ABOVE_END
}

/** A live overlay. Disposing it takes the popup down. */
public interface OverlayHandle {
    public val key: Any
    public val isShowing: Boolean
    public fun dismiss()
}

/**
 * Somewhere to put content that must escape its parent's clip.
 *
 * A dropdown list opened from a row inside a scrolling, clipping list cannot be a child
 * of that row: it would be cut off at the row's edge and painted under its siblings.
 * It has to be hosted at the top of the tree instead, which is what this provides.
 */
public interface OverlayHost {

    public val isShowingOverlay: Boolean

    /**
     * Shows [content] anchored to [anchor].
     *
     * Showing a second overlay with the same [key] replaces the first, so a control that
     * opens on every click cannot stack popups.
     *
     * @param dismissOnOutsideClick close when a press lands anywhere else. The press is
     *   consumed by the dismissal, so it does not also activate whatever was underneath.
     * @param onDismiss notified however the overlay closes, including by outside click
     *   or Escape, so the control that opened it can update its own state.
     */
    public fun show(
        key: Any,
        anchor: UiNode,
        content: UiNode,
        placement: OverlayPlacement = OverlayPlacement.BELOW_START,
        dismissOnOutsideClick: Boolean = true,
        onDismiss: (() -> Unit)? = null,
    ): OverlayHandle

    public fun dismiss(key: Any): Boolean

    /** @return true if anything was dismissed. */
    public fun dismissAll(): Boolean
}

/**
 * The root of a screen: ordinary content, plus any overlays above it.
 *
 * Overlays are later children, which gives them the behaviour they need for free —
 * children paint in order so overlays paint last, and hit testing walks children in
 * reverse so overlays are tested first.
 */
public class OverlayRootNode(
    id: UiId,
    content: UiNode,
) : UiNode(id), OverlayHost {

    private class ActiveOverlay(
        val key: Any,
        val node: UiNode,
        val anchor: UiNode,
        val placement: OverlayPlacement,
        val dismissOnOutsideClick: Boolean,
        val onDismiss: (() -> Unit)?,
    ) {
        /** Ties this overlay's lifetime to the anchor's. Released on dismissal. */
        var anchorRegistration: Disposable? = null
    }

    private val active = ArrayList<ActiveOverlay>(1)

    /** Gap between an anchor and the popup it opened. */
    public var anchorGap: Float = DEFAULT_ANCHOR_GAP

    override val isShowingOverlay: Boolean get() = active.isNotEmpty()

    init {
        addChild(content)
    }

    private val content: UiNode get() = children.first()

    override fun show(
        key: Any,
        anchor: UiNode,
        content: UiNode,
        placement: OverlayPlacement,
        dismissOnOutsideClick: Boolean,
        onDismiss: (() -> Unit)?,
    ): OverlayHandle {
        dismiss(key)

        val entry = ActiveOverlay(key, content, anchor, placement, dismissOnOutsideClick, onDismiss)
        active.add(entry)
        addChild(content)

        // A popup outlives nothing: if the control that opened it is disposed, the popup
        // goes with it. Registering into the anchor's own scope means no caller has to
        // remember this.
        entry.anchorRegistration = Disposable { dismiss(key) }
            .also { anchor.scope.register(it) }

        // While anything is open the root itself becomes a hit-test target, so a press
        // that misses every popup still has somewhere to land and can dismiss them.
        interactive = true
        invalidateMeasure()

        return Handle(entry)
    }

    override fun dismiss(key: Any): Boolean {
        val index = active.indexOfFirst { it.key == key }
        if (index < 0) return false
        removeEntry(active[index])
        return true
    }

    override fun dismissAll(): Boolean {
        if (active.isEmpty()) return false
        for (entry in active.toList()) removeEntry(entry)
        return true
    }

    private fun removeEntry(entry: ActiveOverlay) {
        active.remove(entry)
        entry.anchorRegistration?.let { registration ->
            entry.anchorRegistration = null
            // Only if the scope is still alive: if the anchor's disposal is what got us
            // here, the scope is already tearing itself down.
            if (!entry.anchor.scope.isDisposed) entry.anchor.scope.unregister(registration)
        }
        removeChild(entry.node)
        entry.node.dispose()
        interactive = active.isNotEmpty()
        invalidateMeasure()
        entry.onDismiss?.invoke()
    }

    /**
     * Drops any overlay whose anchor has left the tree.
     *
     * A popup is positioned from its anchor's absolute bounds, so an anchor that is no
     * longer under this root has no meaningful position — the popup would be stranded
     * wherever it last was, floating over unrelated content. That is not hypothetical:
     * the settings list is virtualized, so switching category recycles a row out of the
     * tree without disposing it, and a dropdown opened from that row would otherwise
     * survive with a stale position.
     *
     * Cheap enough to run every measure: there is almost never more than one overlay,
     * and the walk is up a spine rather than across a tree.
     */
    private fun pruneDetachedOverlays() {
        if (active.isEmpty()) return
        for (entry in active.toList()) {
            if (!isUnderThisRoot(entry.anchor)) removeEntry(entry)
        }
    }

    private fun isUnderThisRoot(node: UiNode): Boolean {
        var current: UiNode? = node
        while (current != null) {
            if (current === this) return true
            current = current.parent
        }
        return false
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        pruneDetachedOverlays()

        val width = constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: FALLBACK
        val height = constraints.maxHeight.takeIf { constraints.hasBoundedHeight } ?: FALLBACK

        content.measure(
            Constraints(minWidth = width, maxWidth = width, minHeight = height, maxHeight = height),
            context,
        )

        // Popups size themselves, bounded only by the screen.
        val loose = Constraints(maxWidth = width, maxHeight = height)
        for (entry in active) entry.node.measure(loose, context)

        return Size(width, height)
    }

    override fun arrangeChildren(context: LayoutContext) {
        content.arrange(Rect.of(Vec2.Zero, content.measuredSize), context)

        for (entry in active) {
            val position = resolvePosition(
                anchor = entry.anchor.absoluteBounds(),
                size = entry.node.measuredSize,
                viewport = measuredSize,
                placement = entry.placement,
            )
            entry.node.arrange(Rect.of(position, entry.node.measuredSize), context)
        }
    }

    /**
     * Places a popup so it stays on screen.
     *
     * Preferred side first; if it does not fit, flip to the other side; then clamp into
     * the viewport. A popup that runs off the bottom of the screen is worse than one
     * that opens upwards.
     */
    private fun resolvePosition(
        anchor: Rect,
        size: Size,
        viewport: Size,
        placement: OverlayPlacement,
    ): Vec2 {
        val x = if (placement.alignsEnd) anchor.right - size.width else anchor.x

        val below = anchor.bottom + anchorGap
        val above = anchor.y - size.height - anchorGap

        var y = if (placement.isBelow) below else above
        if (placement.isBelow && below + size.height > viewport.height && above >= 0f) {
            y = above
        } else if (!placement.isBelow && above < 0f && below + size.height <= viewport.height) {
            y = below
        }

        return Vec2(
            x.coerceIn(0f, (viewport.width - size.width).coerceAtLeast(0f)),
            y.coerceIn(0f, (viewport.height - size.height).coerceAtLeast(0f)),
        )
    }

    override fun onInputEvent(event: InputEvent) {
        if (active.isEmpty()) return

        // Escape closes the topmost popup. Handled during capture so a control inside the
        // popup cannot swallow it first.
        if (event is KeyDownEvent && event.key == Key.ESCAPE && event.phase == EventPhase.CAPTURE) {
            removeEntry(active.last())
            event.consume()
            return
        }

        // A press that reaches the root itself missed every popup, so it is an outside
        // click. Consuming it stops the same press also activating what was underneath.
        if (event is PointerDownEvent && event.phase == EventPhase.TARGET) {
            val dismissible = active.filter { it.dismissOnOutsideClick }
            if (dismissible.isNotEmpty()) {
                for (entry in dismissible) removeEntry(entry)
                event.consume()
            }
        }
    }

    override fun dispose() {
        active.clear()
        super.dispose()
    }

    private inner class Handle(private val entry: ActiveOverlay) : OverlayHandle {
        override val key: Any get() = entry.key
        override val isShowing: Boolean get() = active.contains(entry)
        override fun dismiss() {
            if (isShowing) removeEntry(entry)
        }
    }

    private companion object {
        const val FALLBACK = 480f
        const val DEFAULT_ANCHOR_GAP = 2f
    }
}
