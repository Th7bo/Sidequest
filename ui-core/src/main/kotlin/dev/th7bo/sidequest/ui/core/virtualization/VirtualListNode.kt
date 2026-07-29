package dev.th7bo.sidequest.ui.core.virtualization

import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.input.EventPhase
import dev.th7bo.sidequest.ui.input.InputEvent
import dev.th7bo.sidequest.ui.input.ScrollEvent
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import kotlin.math.max
import kotlin.math.min

/**
 * Supplies rows to a [VirtualListNode] on demand.
 *
 * The list only ever asks for rows it is about to show, so a provider backed by ten
 * thousand settings costs the same as one backed by ten until the user scrolls.
 */
public interface RowProvider {

    /** Total number of rows, materialized or not. */
    public val rowCount: Int

    /** A stable identity for row [index], used to preserve node identity across changes. */
    public fun keyAt(index: Int): Any

    /**
     * Builds the node for row [index].
     *
     * Called only when the row enters the materialization window. The returned node is
     * retained until the row leaves it.
     */
    public fun createRow(index: Int): UiNode

    /**
     * An estimate of row [index]'s height, used for scroll extent before the row has
     * ever been measured. A wrong estimate costs scrollbar accuracy, not correctness —
     * measured heights replace it as rows are seen.
     */
    public fun estimatedHeight(index: Int): Float = DEFAULT_ROW_HEIGHT

    public companion object {
        public const val DEFAULT_ROW_HEIGHT: Float = 24f
    }
}

/**
 * A vertically scrolling list that materializes only what it needs.
 *
 * Rows outside the viewport plus [overscanRows] have no node at all: they are not
 * measured, not painted, not hit tested and not animated. That is what keeps a screen
 * with thousands of settings costing the same as one with thirty.
 *
 * Heights are cached per row as they are measured, so scroll extent sharpens from
 * estimate to exact without ever re-measuring a row that has not changed.
 */
public class VirtualListNode(
    id: UiId,
    private val provider: RowProvider,
    key: Any? = null,
) : UiNode(id, key) {

    /** Extra rows materialized above and below the viewport. */
    public var overscanRows: Int = DEFAULT_OVERSCAN
        set(value) {
            require(value >= 0) { "overscanRows must not be negative, was $value" }
            if (field == value) return
            field = value
            invalidateMeasure()
        }

    /** Gap between rows. */
    public var rowSpacing: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidateMeasure()
        }

    /** Distance scrolled from the top, in logical units. */
    public var scrollOffset: Float = 0f
        private set

    /** Units scrolled per wheel notch. */
    public var scrollStep: Float = DEFAULT_SCROLL_STEP

    override val clipsChildren: Boolean get() = true

    /** Live nodes, keyed by row identity so a row keeps its node while it stays in view. */
    private val materialized = LinkedHashMap<Any, RowEntry>()

    /** Measured heights by row identity. Survives a row leaving and re-entering view. */
    private val measuredHeights = HashMap<Any, Float>()

    /** Viewport height from the last measure pass. */
    private var viewportHeight: Float = 0f
    private var viewportWidth: Float = 0f

    /** Index range currently materialized, inclusive-exclusive. */
    public var materializedRange: IntRange = IntRange.EMPTY
        private set

    /** Rows that exist as definitions, whether or not they have a node. */
    public val registeredRowCount: Int get() = provider.rowCount

    /** Rows with a live node. */
    public val materializedRowCount: Int get() = materialized.size

    /**
     * Prefix sums of row offsets, `offsets[i]` being the top of row `i` and
     * `offsets[count]` the total extent.
     *
     * Without this, finding a row's position would be a walk from row zero, making
     * arrange O(materialized × rowCount) — which is exactly the cost virtualization
     * exists to avoid. Rebuilt only when a height or the row count actually changes.
     */
    private var offsets: FloatArray = FloatArray(1)
    private var offsetsValid: Boolean = false

    /** Total scrollable height, mixing measured and estimated row heights. */
    public val contentHeight: Float
        get() {
            ensureOffsets()
            return max(0f, offsets[provider.rowCount] - rowSpacing)
        }

    /** How far the list can scroll. Zero when everything fits. */
    public val maxScrollOffset: Float get() = max(0f, contentHeight - viewportHeight)

    init {
        interactive = true
    }

    // -- scrolling ----------------------------------------------------------

    /** Scrolls to an absolute offset, clamped into range. Returns true if it moved. */
    public fun scrollTo(offset: Float): Boolean {
        val clamped = offset.coerceIn(0f, maxScrollOffset)
        if (clamped == scrollOffset) return false
        scrollOffset = clamped
        // Scrolling changes which rows are materialized, so it is a measure-level change.
        invalidateMeasure()
        return true
    }

    public fun scrollBy(delta: Float): Boolean = scrollTo(scrollOffset + delta)

    /**
     * Brings row [index] fully into view with the least movement.
     *
     * Used by search navigation: jumping to a result must work whether or not the row
     * currently has a node.
     */
    public fun scrollToRow(index: Int, alignment: ScrollAlignment = ScrollAlignment.NEAREST): Boolean {
        if (index !in 0 until provider.rowCount) return false

        val top = offsetOf(index)
        val height = heightOf(index)
        val bottom = top + height

        val target = when (alignment) {
            ScrollAlignment.START -> top
            ScrollAlignment.CENTER -> top - (viewportHeight - height) / 2f
            ScrollAlignment.END -> bottom - viewportHeight
            ScrollAlignment.NEAREST -> when {
                top < scrollOffset -> top
                bottom > scrollOffset + viewportHeight -> bottom - viewportHeight
                else -> return false
            }
        }
        return scrollTo(target)
    }

    /** True when row [index] is inside the visible viewport. */
    public fun isRowVisible(index: Int): Boolean {
        val top = offsetOf(index)
        return top + heightOf(index) > scrollOffset && top < scrollOffset + viewportHeight
    }

    /** The live node for row [index], or null if it is not materialized. */
    public fun nodeForRow(index: Int): UiNode? = materialized[provider.keyAt(index)]?.node

    // -- geometry -----------------------------------------------------------

    private fun heightOf(index: Int): Float =
        measuredHeights[provider.keyAt(index)] ?: provider.estimatedHeight(index)

    private fun offsetOf(index: Int): Float {
        ensureOffsets()
        return offsets[index]
    }

    /**
     * Marks the offset table stale.
     *
     * Call after the provider's contents change; heights that the list measures itself
     * invalidate it automatically.
     */
    public fun invalidateRows() {
        offsetsValid = false
        invalidateMeasure()
    }

    private fun ensureOffsets() {
        val count = provider.rowCount
        if (offsetsValid && offsets.size == count + 1) return

        if (offsets.size != count + 1) offsets = FloatArray(count + 1)
        var running = 0f
        for (index in 0 until count) {
            offsets[index] = running
            running += heightOf(index) + rowSpacing
        }
        offsets[count] = running
        offsetsValid = true
    }

    /** Binary search for the first row whose bottom edge is past [y]. */
    private fun firstRowEndingAfter(y: Float): Int {
        ensureOffsets()
        val count = provider.rowCount
        var low = 0
        var high = count - 1
        var result = count
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (offsets[mid] + heightOf(mid) > y) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    /** The rows intersecting the viewport, before overscan is applied. */
    private fun visibleRange(): IntRange {
        val count = provider.rowCount
        if (count == 0 || viewportHeight <= 0f) return IntRange.EMPTY

        val first = firstRowEndingAfter(scrollOffset)
        if (first >= count) return IntRange.EMPTY

        val viewBottom = scrollOffset + viewportHeight
        var last = first
        while (last + 1 < count && offsets[last + 1] < viewBottom) last++
        return first..last
    }

    // -- measure / arrange --------------------------------------------------

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        viewportWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else FALLBACK_WIDTH
        viewportHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else FALLBACK_HEIGHT

        // Clamp first: a shrinking viewport or a shorter list can leave the offset past
        // the end, which would otherwise show an empty gap below the content.
        scrollOffset = scrollOffset.coerceIn(0f, maxScrollOffset)

        syncMaterializedRows()

        val rowConstraints = Constraints(maxWidth = viewportWidth)
        for (entry in materialized.values) {
            val size = entry.node.measure(rowConstraints, context)
            // A row measuring differently from its estimate moves every row below it,
            // so the offset table has to be rebuilt — but only then.
            if (measuredHeights.put(entry.key, size.height) != size.height) offsetsValid = false
        }

        return Size(viewportWidth, viewportHeight)
    }

    /**
     * Creates nodes for rows that entered the window and disposes those that left.
     *
     * Rows are keyed by identity, so a row that stays in the window across a change
     * keeps its node — and therefore its focus, hover and animation state.
     */
    private fun syncMaterializedRows() {
        val visible = visibleRange()
        val count = provider.rowCount

        val window = if (visible.isEmpty()) {
            IntRange.EMPTY
        } else {
            max(0, visible.first - overscanRows)..min(count - 1, visible.last + overscanRows)
        }
        materializedRange = window

        val wanted = LinkedHashMap<Any, Int>(window.count().coerceAtLeast(1))
        for (index in window) wanted[provider.keyAt(index)] = index

        // Retire rows that fell outside the window.
        val departed = materialized.keys.filterNot { it in wanted }
        for (key in departed) {
            val entry = materialized.remove(key) ?: continue
            removeChild(entry.node)
            entry.node.dispose()
        }

        // Materialize newcomers, in index order so the child list stays sorted.
        for ((key, index) in wanted) {
            val existing = materialized[key]
            if (existing != null) {
                existing.index = index
                continue
            }
            val node = provider.createRow(index)
            addChild(node)
            materialized[key] = RowEntry(key, index, node)
        }
    }

    override fun arrangeChildren(context: LayoutContext) {
        for (entry in materialized.values) {
            val top = offsetOf(entry.index) - scrollOffset
            entry.node.arrange(
                Rect(0f, top, entry.node.measuredSize.width, entry.node.measuredSize.height),
                context,
            )
        }
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        context.diagnostics.nodesRegistered = max(context.diagnostics.nodesRegistered, provider.rowCount)
    }

    // -- input --------------------------------------------------------------

    override fun onInputEvent(event: InputEvent) {
        if (event !is ScrollEvent || event.phase != EventPhase.TARGET && event.phase != EventPhase.BUBBLE) return
        // A list that cannot scroll must not swallow the wheel: an outer scroller may
        // still want it.
        if (maxScrollOffset <= 0f) return
        if (scrollBy(-event.scrollY * scrollStep)) event.consume()
    }

    override fun dispose() {
        materialized.clear()
        measuredHeights.clear()
        super.dispose()
    }

    private class RowEntry(val key: Any, var index: Int, val node: UiNode)

    public companion object {
        public const val DEFAULT_OVERSCAN: Int = 3
        public const val DEFAULT_SCROLL_STEP: Float = 30f
        private const val FALLBACK_WIDTH = 320f
        private const val FALLBACK_HEIGHT = 180f
    }
}

/** Where a row should end up when it is scrolled into view. */
public enum class ScrollAlignment {
    /** Row top flush with the viewport top. */
    START,

    /** Row centred vertically. */
    CENTER,

    /** Row bottom flush with the viewport bottom. */
    END,

    /** Move as little as possible; do nothing if already fully visible. */
    NEAREST,
}
