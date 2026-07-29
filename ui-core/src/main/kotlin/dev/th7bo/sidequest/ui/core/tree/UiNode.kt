package dev.th7bo.sidequest.ui.core.tree

import dev.th7bo.sidequest.ui.diagnostics.UiDiagnostics
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.input.InputEvent
import dev.th7bo.sidequest.ui.rendering.TextMeasurer
import dev.th7bo.sidequest.ui.rendering.Transform
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.state.Disposable
import dev.th7bo.sidequest.ui.state.DisposableScope
import dev.th7bo.sidequest.ui.theme.Theme

/**
 * A retained node in the UI tree.
 *
 * Nodes persist across frames. That is what preserves focus, scroll offsets, hover
 * state and in-flight animations — none of which would survive a tree that was rebuilt
 * every frame. What is ephemeral is the draw command list a frame produces, not the
 * nodes that produce it.
 *
 * Identity is `(parent path, [id], [key])`. [key] disambiguates siblings generated from
 * a collection so that reordering a list moves nodes rather than recreating them.
 *
 * Confined to the UI thread.
 */
public abstract class UiNode(
    public val id: UiId,
    public val key: Any? = null,
) : Disposable {

    // -- tree ---------------------------------------------------------------

    public var parent: UiNode? = null
        private set

    private val mutableChildren = ArrayList<UiNode>(INITIAL_CHILDREN)

    public val children: List<UiNode> get() = mutableChildren

    /** Registrations owned by this node; released when the node is disposed. */
    public val scope: DisposableScope = DisposableScope()

    /** The root of the tree this node belongs to. */
    public val root: UiNode get() = parent?.root ?: this

    // -- layout state -------------------------------------------------------

    /**
     * Position and size in the parent's child-coordinate space, written by the parent
     * during arrange.
     *
     * Absolute positions are deliberately not cached: they are accumulated during the
     * paint and hit-test walks instead, so moving a container does not have to touch
     * every descendant.
     */
    public var bounds: Rect = Rect.Zero
        internal set

    /** Local transform applied to this node and its subtree. Used for HUD scaling. */
    public var transform: Transform = Transform.Identity
        set(value) {
            if (field == value) return
            field = value
            invalidatePaint()
        }

    /**
     * Growth weight within a linear layout. `0` means "take intrinsic size"; any
     * positive value shares the leftover space in proportion.
     */
    public var layoutWeight: Float = 0f
        set(value) {
            require(value >= 0f) { "layoutWeight must not be negative, was $value" }
            if (field == value) return
            field = value
            invalidateMeasure()
        }

    /**
     * Whether a linear layout should stretch this child to the full cross-axis extent
     * of its siblings — a progress bar under a label spanning the label's width, say.
     *
     * The counterpart to [layoutWeight] on the other axis: weight distributes along the
     * stacking direction, this fills across it.
     */
    public var fillCrossAxis: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidateMeasure()
        }

    /** Hidden nodes are neither painted nor hit tested, but keep their state. */
    public var isVisible: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            parent?.invalidateMeasure() ?: invalidateMeasure()
        }

    /** True if this node clips its children to its own bounds. */
    public open val clipsChildren: Boolean get() = false

    // -- interaction --------------------------------------------------------

    /** Whether this node is a hit-test candidate. */
    public var interactive: Boolean = false

    /** Whether keyboard focus can land here. */
    public var focusable: Boolean = false

    /** Whether a press on this node grabs the pointer until release. */
    public var capturesPointer: Boolean = false

    /** Maintained by the input dispatcher. */
    public var isHovered: Boolean = false
        internal set

    /** Maintained by the focus manager. */
    public var isFocused: Boolean = false
        internal set

    /**
     * Optional handler, so behaviour can be attached without subclassing.
     * Invoked after [onInputEvent] on the UI thread.
     */
    public var onEvent: ((InputEvent) -> Unit)? = null

    // -- dirty tracking -----------------------------------------------------

    internal var needsMeasure: Boolean = true
        private set

    internal var needsArrange: Boolean = true
        private set

    internal var needsPaint: Boolean = true
        private set

    private var cachedConstraints: Constraints? = null
    private var cachedSize: Size = Size.Zero

    /** The size produced by the last measure pass. */
    public val measuredSize: Size get() = cachedSize

    /**
     * Marks this node's size as possibly changed.
     *
     * Propagation stops at the first ancestor that is already dirty, so invalidating a
     * leaf costs a short walk up the spine rather than a tree traversal.
     */
    public fun invalidateMeasure() {
        if (needsMeasure) return
        var node: UiNode? = this
        while (node != null && !node.needsMeasure) {
            node.needsMeasure = true
            node.needsArrange = true
            node.needsPaint = true
            node = node.parent
        }
    }

    /** Marks children as needing repositioning without changing this node's size. */
    public fun invalidateArrange() {
        if (needsArrange) return
        needsArrange = true
        needsPaint = true
        var node = parent
        while (node != null && !node.needsArrange) {
            node.needsArrange = true
            node.needsPaint = true
            node = node.parent
        }
    }

    /** Marks the node as visually stale without any geometry change. */
    public fun invalidatePaint() {
        needsPaint = true
    }

    // -- children -----------------------------------------------------------

    public fun addChild(child: UiNode) {
        check(child.parent == null) { "Node ${child.id} already has a parent" }
        child.parent = this
        mutableChildren.add(child)
        invalidateMeasure()
    }

    public fun addChildren(vararg newChildren: UiNode) {
        for (child in newChildren) addChild(child)
    }

    public fun removeChild(child: UiNode) {
        if (mutableChildren.remove(child)) {
            child.parent = null
            invalidateMeasure()
        }
    }

    /** Removes and disposes every child. */
    public fun clearChildren() {
        if (mutableChildren.isEmpty()) return
        for (child in mutableChildren) {
            child.parent = null
            child.dispose()
        }
        mutableChildren.clear()
        invalidateMeasure()
    }

    /** Finds a descendant by [id], depth first. */
    public fun find(target: UiId): UiNode? {
        if (id == target) return this
        for (child in mutableChildren) {
            child.find(target)?.let { return it }
        }
        return null
    }

    /** Walks this node and every descendant, parents before children. */
    public fun forEachInTree(action: (UiNode) -> Unit) {
        action(this)
        for (child in mutableChildren) child.forEachInTree(action)
    }

    // -- measure / arrange --------------------------------------------------

    /**
     * Returns this node's size under [constraints], reusing the cached result when the
     * node is clean and the constraints are unchanged.
     *
     * A clean subtree costs one comparison here and nothing below it — which is the
     * whole reason an idle frame measures zero nodes.
     */
    public fun measure(constraints: Constraints, context: LayoutContext): Size {
        if (!needsMeasure && cachedConstraints == constraints) {
            context.diagnostics.cacheHits++
            return cachedSize
        }
        context.diagnostics.cacheMisses++
        context.diagnostics.nodesMeasured++

        val measured = constraints.constrain(measureSelf(constraints, context))
        val changed = measured != cachedSize
        cachedSize = measured
        cachedConstraints = constraints
        needsMeasure = false
        if (changed) needsArrange = true
        return measured
    }

    /**
     * Computes this node's desired size. Implementations must measure their children
     * through [measure], never by reaching into the cache directly.
     */
    protected abstract fun measureSelf(constraints: Constraints, context: LayoutContext): Size

    /** Positions this node at [newBounds] and lays out its children. */
    public fun arrange(newBounds: Rect, context: LayoutContext) {
        val moved = bounds != newBounds
        if (!needsArrange && !moved) return
        bounds = newBounds
        context.diagnostics.nodesArranged++
        arrangeChildren(context)
        needsArrange = false
        needsPaint = true
    }

    /**
     * Positions children within this node's own size. Leaves need do nothing.
     *
     * Child positions are in this node's coordinate space; the standard API never
     * exposes absolute coordinates to a component author.
     */
    protected open fun arrangeChildren(context: LayoutContext) {
        for (child in children) {
            if (child.isVisible) child.arrange(Rect.of(Vec2.Zero, child.measuredSize), context)
        }
    }

    // -- paint --------------------------------------------------------------

    /**
     * Paints this node and its subtree. [origin] is the absolute position of the
     * parent's child-coordinate space.
     */
    public fun paint(renderer: UiRenderer, origin: Vec2, context: RenderContext) {
        if (!isVisible) return

        val position = origin + bounds.position
        if (transform == Transform.Identity) {
            val absolute = Rect.of(position, bounds.size)
            if (!absolute.overlaps(context.viewport)) return
            paintTree(renderer, absolute, position, context)
        } else {
            // Inside a transform the subtree draws in its own space, so the node's own
            // bounds start at the origin and children inherit that origin.
            //
            // The local transform is applied *before* the translation to `position`:
            // `a.then(b)` applies `a` first, so translating first would scale the
            // node's screen position too and a 2x HUD would land at twice its anchored
            // distance from the edge. This is also the order `toLocalFromParent`
            // inverts, so paint and hit testing agree.
            renderer.pushTransform(transform.then(Transform.translation(position)))
            try {
                paintTree(renderer, Rect.of(Vec2.Zero, bounds.size), Vec2.Zero, context)
            } finally {
                renderer.popTransform()
            }
        }
    }

    private fun paintTree(
        renderer: UiRenderer,
        selfBounds: Rect,
        childOrigin: Vec2,
        context: RenderContext,
    ) {
        context.diagnostics.nodesVisible++
        if (interactive) context.diagnostics.hitTestCandidates++

        paintSelf(renderer, selfBounds, context)

        if (children.isEmpty()) {
            needsPaint = false
            return
        }

        if (clipsChildren) renderer.pushClip(selfBounds)
        try {
            for (child in children) child.paint(renderer, childOrigin, context)
        } finally {
            if (clipsChildren) renderer.popClip()
        }
        needsPaint = false
    }

    /** Draws this node's own visuals. [bounds] is absolute (or transform-local). */
    protected open fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        // Layout containers draw nothing of their own.
    }

    // -- hit testing --------------------------------------------------------

    /**
     * Hit tests this subtree.
     *
     * @param point in this node's *parent* coordinate space.
     * @param path receives the chain from this node down to the deepest hit, in that
     *   order, and is left untouched if nothing is hit.
     * @return true if something in this subtree was hit.
     */
    public fun hitTest(point: Vec2, path: MutableList<UiNode>): Boolean {
        if (!isVisible) return false

        val local = toLocalFromParent(point)
        val localBounds = Rect.of(Vec2.Zero, bounds.size)
        val inside = local in localBounds

        // A clipping container cannot be hit, nor can its children, outside its bounds.
        if (clipsChildren && !inside) return false

        val depth = path.size
        path.add(this)

        // Later children paint on top, so they are tested first.
        for (index in children.indices.reversed()) {
            if (children[index].hitTest(local, path)) return true
        }

        if (interactive && inside) return true

        while (path.size > depth) path.removeAt(path.size - 1)
        return false
    }

    /** Converts a point from the parent's coordinate space into this node's. */
    private fun toLocalFromParent(point: Vec2): Vec2 {
        val relative = point - bounds.position
        return if (transform == Transform.Identity) relative else transform.invert(relative)
    }

    /**
     * Converts a point from the root's coordinate space into this node's local space,
     * unwinding every transform on the way down.
     *
     * This is what keeps the cursor aligned with a scaled HUD: the pointer arrives in
     * screen space and is un-transformed once per level rather than compared against
     * pre-scaled bounds.
     */
    public fun toLocal(pointInRootSpace: Vec2): Vec2 {
        val chain = ArrayList<UiNode>(DEFAULT_DEPTH)
        var node: UiNode? = this
        while (node != null) {
            chain.add(node)
            node = node.parent
        }
        var point = pointInRootSpace
        for (index in chain.indices.reversed()) {
            point = chain[index].toLocalFromParent(point)
        }
        return point
    }

    /** The node's absolute rectangle in root space. */
    public fun absoluteBounds(): Rect {
        var position = bounds.position
        var node = parent
        while (node != null) {
            position += node.bounds.position
            node = node.parent
        }
        return Rect.of(position, bounds.size)
    }

    // -- input --------------------------------------------------------------

    /** Handles an event delivered by the dispatcher. Override to add behaviour. */
    protected open fun onInputEvent(event: InputEvent) {
        // No default behaviour.
    }

    internal fun deliver(event: InputEvent) {
        onInputEvent(event)
        onEvent?.invoke(event)
    }

    // -- lifecycle ----------------------------------------------------------

    /** Releases this node's registrations and those of its whole subtree. */
    override fun dispose() {
        for (child in mutableChildren) {
            child.parent = null
            child.dispose()
        }
        mutableChildren.clear()
        onEvent = null
        scope.dispose()
    }

    override fun toString(): String =
        "${this::class.simpleName}($id${key?.let { "#$it" } ?: ""}, $bounds)"

    private companion object {
        const val INITIAL_CHILDREN = 4
        const val DEFAULT_DEPTH = 8
    }
}

/** Context threaded through a measure/arrange pass. */
public class LayoutContext(
    public val diagnostics: UiDiagnostics,
    public val textMeasurer: TextMeasurer,
    public val theme: Theme,
)

/** Context threaded through a paint pass. */
public class RenderContext(
    public val diagnostics: UiDiagnostics,
    public val theme: Theme,
    /** Absolute region worth drawing; anything outside is culled. */
    public val viewport: Rect,
)
