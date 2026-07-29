package dev.th7bo.sidequest.ui.core.hud

import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.extension.DuplicateRegistrationException
import dev.th7bo.sidequest.ui.extension.OwnedRegistry
import dev.th7bo.sidequest.ui.extension.Registration
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.hud.HudDefinition
import dev.th7bo.sidequest.ui.hud.HudInstance
import dev.th7bo.sidequest.ui.hud.HudPlacement
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Transform
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.mutableStateOf

/**
 * What a HUD element is given when it builds and while it runs.
 *
 * Passed explicitly rather than read from a global, so a HUD can be built and tested
 * without a game running — and so the editor can render one with fake data.
 */
public class HudContext(
    public val screenSize: Size,
    public val guiScale: Float,
    /** Fraction of the way to the next tick, for interpolation. */
    public val partialTick: Float,
    public val components: ComponentContext,
    /** True while the editor is open, so a HUD can show preview data. */
    public val isEditing: Boolean = false,
    /** True when this element is the editor's current selection. */
    public val isSelected: Boolean = false,
)

/** Builds the node tree for a HUD instance. */
public fun interface HudRenderer {
    public fun createNode(instance: HudInstance, context: ComponentContext): UiNode
}

/**
 * Definitions and their renderers, owned by registration scopes.
 *
 * Disposing a scope removes its definitions *and* takes down any live instances built
 * from them, which is what stops an unloaded module leaving HUDs on screen.
 */
public class HudRegistry {

    private val definitions = OwnedRegistry<UiId, HudDefinition>("HUD definition")
    private val renderers = HashMap<UiId, HudRenderer>()

    public val size: Int get() = definitions.size

    public val ids: Set<UiId> get() = definitions.keys

    /**
     * @throws DuplicateRegistrationException if the id is taken, naming both owners.
     */
    public fun register(
        scope: RegistrationScope,
        definition: HudDefinition,
        renderer: HudRenderer,
    ): Registration {
        val registration = definitions.register(scope, definition.id, definition)
        renderers[definition.id] = renderer
        scope.register { renderers.remove(definition.id) }
        return registration
    }

    public operator fun get(id: UiId): HudDefinition? = definitions[id]

    public fun rendererFor(id: UiId): HudRenderer? = renderers[id]

    public fun snapshot(): Map<UiId, HudDefinition> = definitions.snapshot()
}

/**
 * A single placed HUD element.
 *
 * Owns its placement and its node tree. Scaling is a [Transform] on this node rather
 * than a re-layout, so changing scale costs a repaint and not a measure — and hit
 * testing stays correct because the input dispatcher un-transforms the pointer on the
 * way down.
 */
public class HudElementNode(
    public val instance: HudInstance,
    public val definition: HudDefinition,
    private val hudContext: () -> HudContext,
    content: UiNode,
) : UiNode(instance.instanceId) {

    private val placementState: MutableUiState<HudPlacement> =
        mutableStateOf(definition.defaultPlacement(), "${instance.instanceId.value}.placement")

    /** Observable placement, so the editor's inspector can bind straight to it. */
    public val placement: UiState<HudPlacement> get() = placementState

    /** The unscaled size the content measured to. */
    public var contentSize: Size = Size.Zero
        private set

    /** The size this element occupies on screen, i.e. after scaling. */
    public val scaledSize: Size
        get() = contentSize * placementState.peek().scale

    init {
        // The content decides whether it is interactive; the element itself is a target
        // only in the editor, which sets this when it takes over.
        interactive = false
        addChild(content)

        definition.visibleWhen.observe(scope) { isVisible = it }
        isVisible = definition.visibleWhen.peek()

        applyTransform()
    }

    /** Replaces the placement wholesale. */
    public fun setPlacement(newPlacement: HudPlacement) {
        if (placementState.peek() == newPlacement) return
        val previous = placementState.peek()
        placementState.value = newPlacement

        // A scale change is a paint-level concern; a size change is a layout one.
        if (previous.size != newPlacement.size) invalidateMeasure()
        applyTransform()
        parent?.invalidateArrange()
    }

    public fun update(transform: (HudPlacement) -> HudPlacement) {
        setPlacement(transform(placementState.peek()))
    }

    /** Moves so the element's top-left lands at [position] on screen. */
    public fun moveTo(position: Vec2, screenSize: Size) {
        update { it.movedTo(position, scaledSize, screenSize) }
    }

    /** Changes anchor without moving the element on screen. */
    public fun reanchor(anchor: dev.th7bo.sidequest.ui.geometry.Anchor, screenSize: Size) {
        update { it.withAnchor(anchor, scaledSize, screenSize) }
    }

    /** Rescales within the definition's permitted range. */
    public fun rescale(scale: Float) {
        if (!definition.resizeMode.supportsScale) return
        update { it.withScale(scale, definition.scaleRange) }
    }

    /**
     * Resizes within the definition's bounds.
     *
     * Unlike [rescale] this changes the space the content lays out in, so it does force
     * a measure pass — which is the whole distinction between the two operations.
     */
    public fun resize(size: Size) {
        if (!definition.resizeMode.supportsResize) return
        val maximum = definition.maxSize
        val clamped = Size(
            size.width.coerceAtLeast(definition.minSize.width)
                .let { if (maximum != null) it.coerceAtMost(maximum.width) else it },
            size.height.coerceAtLeast(definition.minSize.height)
                .let { if (maximum != null) it.coerceAtMost(maximum.height) else it },
        )
        update { it.copy(size = clamped) }
    }

    /** Restores the definition's defaults. */
    public fun reset() {
        setPlacement(definition.defaultPlacement())
    }

    private fun applyTransform() {
        val current = placementState.peek()
        transform = if (current.scale == 1f) {
            Transform.Identity
        } else {
            Transform(scaleX = current.scale, scaleY = current.scale)
        }
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val child = children.firstOrNull { it.isVisible } ?: return Size.Zero
        val current = placementState.peek()

        // A resizable HUD is measured to its persisted size; everything else sizes to
        // its content. That is the difference between resizing and scaling: one changes
        // the space the content lays out in, the other only how it is drawn.
        val childConstraints = current.size?.let { Constraints.tight(it) } ?: constraints.loosen()
        contentSize = child.measure(childConstraints, context)
        return contentSize
    }

    override fun arrangeChildren(context: LayoutContext) {
        val child = children.firstOrNull { it.isVisible } ?: return
        child.arrange(Rect.of(Vec2.Zero, child.measuredSize), context)
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        // Opacity applies to the whole element, including its content, so it is pushed
        // here rather than being every component's problem.
        val opacity = placementState.peek().opacity
        if (opacity < 1f) renderer.pushOpacity(opacity)
    }

    /** Balances the opacity pushed in [paintSelf]. */
    public fun endPaint(renderer: UiRenderer) {
        if (placementState.peek().opacity < 1f) renderer.popOpacity()
    }
}

/**
 * Hosts every live HUD element.
 *
 * Elements are positioned by resolving their anchor against the current screen size, so
 * a resolution or GUI-scale change needs nothing more than a new viewport — the same
 * mechanism the configuration screen uses.
 *
 * Elements are arranged, never laid out relative to one another, which is why updating
 * one HUD's data cannot invalidate another: they share no layout parent that would have
 * to remeasure.
 */
public class HudLayerNode(
    id: UiId,
    private val screenSize: () -> Size,
) : UiNode(id) {

    private val elements = LinkedHashMap<UiId, HudElementNode>()

    /** Live elements in z-order, lowest first. */
    public val ordered: List<HudElementNode>
        get() = elements.values.sortedBy { it.placement.peek().zIndex }

    public val elementCount: Int get() = elements.size

    public fun add(element: HudElementNode) {
        check(!elements.containsKey(element.instance.instanceId)) {
            "HUD instance ${element.instance.instanceId} is already on the layer"
        }
        elements[element.instance.instanceId] = element
        addChild(element)
    }

    public fun remove(instanceId: UiId): Boolean {
        val element = elements.remove(instanceId) ?: return false
        removeChild(element)
        element.dispose()
        return true
    }

    public operator fun get(instanceId: UiId): HudElementNode? = elements[instanceId]

    public fun clear() {
        for (id in elements.keys.toList()) remove(id)
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        // Each element is measured against the whole screen and positioned
        // independently. No element's size can affect another's.
        val loose = Constraints(maxWidth = screenSize().width, maxHeight = screenSize().height)
        for (element in elements.values) {
            if (element.isVisible) element.measure(loose, context)
        }
        return screenSize()
    }

    override fun arrangeChildren(context: LayoutContext) {
        val screen = screenSize()
        for (element in elements.values) {
            if (!element.isVisible) continue
            val position = element.placement.peek().resolve(element.scaledSize, screen)
            element.arrange(Rect.of(position, element.measuredSize), context)
        }
    }
}
