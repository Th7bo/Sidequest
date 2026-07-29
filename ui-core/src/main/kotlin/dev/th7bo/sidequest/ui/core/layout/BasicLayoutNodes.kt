package dev.th7bo.sidequest.ui.core.layout

import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Alignment
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Dp
import dev.th7bo.sidequest.ui.geometry.Insets
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import kotlin.math.max

/**
 * Overlays children on top of one another, each aligned within the box.
 *
 * Sizes itself to the largest child unless the constraints force otherwise.
 */
public class BoxNode(
    id: UiId,
    alignment: Alignment = Alignment.TopStart,
    key: Any? = null,
) : UiNode(id, key) {

    public var alignment: Alignment = alignment
        set(value) {
            if (field == value) return
            field = value
            invalidateArrange()
        }

    /** When true, children are clipped to the box and cannot be hit outside it. */
    override var clipsChildren: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidatePaint()
        }

    /**
     * Fixes the box's size instead of sizing it to the largest child.
     *
     * Combined with [clipsChildren] this is the minimal viewport: content larger than
     * the box is drawn clipped and cannot be hit outside it.
     */
    public var preferredSize: Size? = null
        set(value) {
            if (field == value) return
            field = value
            invalidateMeasure()
        }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val fixed = preferredSize
        val childConstraints = when {
            // A clipping box is a viewport: children may be larger than it and are
            // simply not all visible, which is the whole point.
            clipsChildren -> Constraints.Unbounded
            fixed != null -> Constraints.atMost(fixed)
            else -> constraints.loosen()
        }

        var width = 0f
        var height = 0f
        for (child in children) {
            if (!child.isVisible) continue
            val childSize = child.measure(childConstraints, context)
            width = max(width, childSize.width)
            height = max(height, childSize.height)
        }
        return fixed ?: Size(width, height)
    }

    override fun arrangeChildren(context: LayoutContext) {
        for (child in children) {
            if (!child.isVisible) continue
            val offset = alignment.align(child.measuredSize, measuredSize)
            child.arrange(Rect.of(offset, child.measuredSize), context)
        }
    }
}

/** Wraps a single child in padding. */
public class PaddingNode(
    id: UiId,
    insets: Insets,
    key: Any? = null,
) : UiNode(id, key) {

    public var insets: Insets = insets
        set(value) {
            if (field == value) return
            field = value
            invalidateMeasure()
        }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val child = children.firstOrNull { it.isVisible } ?: return Size(insets.horizontal, insets.vertical)
        val childSize = child.measure(constraints.deflate(insets).loosen(), context)
        return childSize.outset(insets)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val child = children.firstOrNull { it.isVisible } ?: return
        child.arrange(
            Rect.of(Vec2(insets.left, insets.top), child.measuredSize),
            context,
        )
    }
}

/**
 * Empty space.
 *
 * With a positive [UiNode.layoutWeight] it becomes a flexible gap — the standard way to
 * push siblings apart in a row without computing any coordinates.
 */
public class SpacerNode(
    id: UiId,
    size: Size = Size.Zero,
    key: Any? = null,
) : UiNode(id, key) {

    public var size: Size = size
        set(value) {
            if (field == value) return
            field = value
            invalidateMeasure()
        }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size = size
}

/**
 * Forces a fixed size on a single child, independent of what the child asks for.
 *
 * Useful for icon slots and any control whose height comes from a theme metric.
 */
public class FixedSizeNode(
    id: UiId,
    width: Dp? = null,
    height: Dp? = null,
    key: Any? = null,
) : UiNode(id, key) {

    public var fixedWidth: Dp? = width
        set(value) {
            if (field == value) return
            field = value
            invalidateMeasure()
        }

    public var fixedHeight: Dp? = height
        set(value) {
            if (field == value) return
            field = value
            invalidateMeasure()
        }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val child = children.firstOrNull { it.isVisible }
        val childSize = child?.measure(
            Constraints(
                minWidth = fixedWidth?.value ?: 0f,
                maxWidth = fixedWidth?.value ?: constraints.maxWidth,
                minHeight = fixedHeight?.value ?: 0f,
                maxHeight = fixedHeight?.value ?: constraints.maxHeight,
            ),
            context,
        ) ?: Size.Zero

        return Size(
            fixedWidth?.value ?: childSize.width,
            fixedHeight?.value ?: childSize.height,
        )
    }

    override fun arrangeChildren(context: LayoutContext) {
        val child = children.firstOrNull { it.isVisible } ?: return
        child.arrange(Rect.of(Vec2.Zero, child.measuredSize), context)
    }
}
