package dev.th7bo.sidequest.ui.core.layout

import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Dp
import dev.th7bo.sidequest.ui.geometry.HorizontalAlignment
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.VerticalAlignment
import dev.th7bo.sidequest.ui.ids.UiId
import kotlin.math.max

/** Which way a linear layout stacks its children. */
public enum class Orientation { VERTICAL, HORIZONTAL }

/**
 * Stacks children along one axis with uniform [spacing].
 *
 * Children with a positive [UiNode.layoutWeight] share whatever space is left after
 * the intrinsically sized ones have been measured, in proportion to their weights. A
 * weighted child in an unbounded axis has nothing to share, so it falls back to its
 * intrinsic size rather than expanding to infinity.
 *
 * Prefer [ColumnNode] and [RowNode] over constructing this directly.
 */
public open class LinearLayoutNode(
    id: UiId,
    private val orientation: Orientation,
    spacing: Dp = Dp.Zero,
    key: Any? = null,
) : UiNode(id, key) {

    /** Gap between adjacent visible children. */
    public var spacing: Dp = spacing
        set(value) {
            if (field == value) return
            field = value
            invalidateMeasure()
        }

    /** Cross-axis placement of children narrower than the layout. */
    public var horizontalAlignment: HorizontalAlignment = HorizontalAlignment.START
        set(value) {
            if (field == value) return
            field = value
            invalidateArrange()
        }

    public var verticalAlignment: VerticalAlignment = VerticalAlignment.TOP
        set(value) {
            if (field == value) return
            field = value
            invalidateArrange()
        }

    private val isVertical: Boolean get() = orientation == Orientation.VERTICAL

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val visible = children.filter { it.isVisible }
        if (visible.isEmpty()) return Size(constraints.minWidth, constraints.minHeight)

        val totalSpacing = spacing.value * (visible.size - 1)
        val mainMax = if (isVertical) constraints.maxHeight else constraints.maxWidth
        val crossMax = if (isVertical) constraints.maxWidth else constraints.maxHeight
        val bounded = mainMax != Float.POSITIVE_INFINITY

        var usedMain = 0f
        var maxCross = 0f
        var totalWeight = 0f

        // Pass 1: intrinsically sized children.
        for (child in visible) {
            if (child.layoutWeight > 0f && bounded) {
                totalWeight += child.layoutWeight
                continue
            }
            val childSize = child.measure(childConstraints(crossMax, Float.POSITIVE_INFINITY), context)
            usedMain += mainOf(childSize)
            maxCross = max(maxCross, crossOf(childSize))
        }

        // Pass 2: weighted children share what is left.
        if (totalWeight > 0f) {
            val remaining = max(0f, mainMax - usedMain - totalSpacing)
            for (child in visible) {
                if (child.layoutWeight <= 0f) continue
                val share = remaining * (child.layoutWeight / totalWeight)
                val childSize = child.measure(childConstraints(crossMax, share, exactMain = true), context)
                usedMain += mainOf(childSize)
                maxCross = max(maxCross, crossOf(childSize))
            }
        }

        // Pass 3: children that fill the cross axis are re-measured once the widest
        // sibling is known. Their main-axis size can change as a result — a wrapped
        // label given more width needs fewer lines — so the total is adjusted rather
        // than assumed constant.
        for (child in visible) {
            if (!child.fillCrossAxis) continue
            if (crossOf(child.measuredSize) == maxCross) continue
            val before = mainOf(child.measuredSize)
            val stretched = child.measure(
                childConstraints(maxCross, Float.POSITIVE_INFINITY, exactCross = true),
                context,
            )
            usedMain += mainOf(stretched) - before
        }

        val mainTotal = usedMain + totalSpacing
        return if (isVertical) Size(maxCross, mainTotal) else Size(mainTotal, maxCross)
    }

    private fun childConstraints(
        crossMax: Float,
        mainAvailable: Float,
        exactMain: Boolean = false,
        exactCross: Boolean = false,
    ): Constraints {
        val mainMin = if (exactMain) mainAvailable else 0f
        val crossMin = if (exactCross) crossMax else 0f
        return if (isVertical) {
            Constraints(crossMin, crossMax, mainMin, mainAvailable)
        } else {
            Constraints(mainMin, mainAvailable, crossMin, crossMax)
        }
    }

    private fun mainOf(size: Size): Float = if (isVertical) size.height else size.width

    private fun crossOf(size: Size): Float = if (isVertical) size.width else size.height

    override fun arrangeChildren(context: LayoutContext) {
        var offset = 0f
        val ownCross = if (isVertical) measuredSize.width else measuredSize.height

        for (child in children) {
            if (!child.isVisible) continue
            val childSize = child.measuredSize
            val crossFree = max(0f, ownCross - crossOf(childSize))
            val crossOffset = crossFree * crossFactor()

            val position = if (isVertical) {
                Rect(crossOffset, offset, childSize.width, childSize.height)
            } else {
                Rect(offset, crossOffset, childSize.width, childSize.height)
            }
            child.arrange(position, context)
            offset += mainOf(childSize) + spacing.value
        }
    }

    private fun crossFactor(): Float =
        if (isVertical) horizontalAlignment.factor else verticalAlignment.factor
}

/** Vertical stack. */
public class ColumnNode(
    id: UiId,
    spacing: Dp = Dp.Zero,
    key: Any? = null,
) : LinearLayoutNode(id, Orientation.VERTICAL, spacing, key)

/** Horizontal stack. */
public class RowNode(
    id: UiId,
    spacing: Dp = Dp.Zero,
    key: Any? = null,
) : LinearLayoutNode(id, Orientation.HORIZONTAL, spacing, key)
