package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.core.layout.ColumnNode
import dev.th7bo.sidequest.ui.core.layout.RowNode
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.geometry.VerticalAlignment
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.input.EventPhase
import dev.th7bo.sidequest.ui.input.InputEvent
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.KeyDownEvent
import dev.th7bo.sidequest.ui.input.PointerDownEvent
import dev.th7bo.sidequest.ui.rendering.Corners
import dev.th7bo.sidequest.ui.rendering.Edges
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.mutableStateOf

/** One tab: a label and the content it reveals. */
public class TabDefinition(
    public val id: UiId,
    public val title: UiState<String>,
    public val icon: Icon? = null,
    /** Built lazily, the first time the tab is selected. */
    public val content: () -> UiNode,
)

/**
 * A tab strip with a content area beneath it.
 *
 * Tab content is built lazily and then kept. Building every tab up front would pay the
 * layout cost of screens nobody opened; discarding on switch would throw away scroll
 * position and in-flight animation, which is the retained tree's whole point.
 */
public class TabsNode(
    id: UiId,
    private val tabs: List<TabDefinition>,
    private val componentContext: ComponentContext,
    initialIndex: Int = 0,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    private val selectedState: MutableUiState<Int> =
        mutableStateOf(initialIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)), "${id.value}.selected")

    /** Index of the visible tab. */
    public val selected: UiState<Int> get() = selectedState

    private val strip = RowNode(id.child("strip"), spacing = tokens.spacing.xs).apply {
        verticalAlignment = VerticalAlignment.CENTER
    }

    private val content = ColumnNode(id.child("content"))

    /** Content nodes by index, built on first selection. */
    private val built = HashMap<Int, UiNode>()

    init {
        require(tabs.isNotEmpty()) { "A tab strip needs at least one tab" }
        focusable = true
        interactive = true

        tabs.forEachIndexed { index, tab ->
            strip.addChild(TabButtonNode(id.child("tab_$index"), tab, index))
        }
        addChild(strip)
        addChild(content)
        showSelected()
    }

    public fun select(index: Int) {
        val clamped = index.coerceIn(0, tabs.lastIndex)
        if (selectedState.peek() == clamped) return
        selectedState.value = clamped
        showSelected()
    }

    /** Whether the content for [index] has been built yet. For assertions. */
    public fun isBuilt(index: Int): Boolean = index in built

    private fun showSelected() {
        val index = selectedState.peek()
        val node = built.getOrPut(index) { tabs[index].content() }

        // Detached rather than hidden: a tab that is not showing should cost nothing,
        // and an invisible child still sits in the tree being walked.
        for (child in content.children.toList()) content.removeChild(child)
        content.addChild(node)
        invalidateMeasure()
    }

    override fun onInputEvent(event: InputEvent) {
        if (event !is KeyDownEvent || event.phase != EventPhase.TARGET) return
        when (event.key) {
            Key.ARROW_LEFT -> {
                select(selectedState.peek() - 1)
                event.consume()
            }
            Key.ARROW_RIGHT -> {
                select(selectedState.peek() + 1)
                event.consume()
            }
            else -> Unit
        }
    }

    private inner class TabButtonNode(
        id: UiId,
        tab: TabDefinition,
        private val index: Int,
    ) : UiNode(id) {

        private val label = TextNode(id.child("label"), tab.title, TextRole.LABEL)

        private val isActive: Boolean get() = selectedState.peek() == index

        init {
            interactive = true
            focusable = true
            addChild(label)
            selectedState.observe(scope) { invalidatePaint() }
        }

        override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
            val size = label.measure(constraints.loosen(), context)
            return Size(
                size.width + tokens.spacing.medium.value * 2f,
                size.height + tokens.spacing.small.value * 2f,
            )
        }

        override fun arrangeChildren(context: LayoutContext) {
            label.arrange(
                Rect.of(Vec2(tokens.spacing.medium.value, tokens.spacing.small.value), label.measuredSize),
                context,
            )
        }

        override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
            val palette = context.theme.tokens.colors
            if (isHovered && !isActive) {
                renderer.roundedRect(bounds, Corners.top(tokens.radii.small), palette.hoverBackground)
                context.diagnostics.drawCalls++
            }
            if (isActive) {
                renderer.roundedRect(bounds, Corners.top(tokens.radii.small), palette.selectedBackground)
                // An underline rather than a filled pill: the strip reads as one surface
                // with a marker on it, which is what makes the selection obvious at a
                // glance without four competing shapes.
                renderer.edges(bounds, Edges(bottom = true), tokens.metrics.focusRingWidth, palette.accent)
                context.diagnostics.drawCalls += 2
            }
        }

        override fun onInputEvent(event: InputEvent) {
            if (event is PointerDownEvent && event.phase == EventPhase.TARGET) {
                select(index)
                event.consume()
            }
        }
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val stripSize = strip.measure(constraints.loosen(), context)
        val contentSize = content.measure(
            Constraints(
                maxWidth = constraints.maxWidth,
                maxHeight = (constraints.maxHeight - stripSize.height).coerceAtLeast(0f),
            ),
            context,
        )
        return Size(
            maxOf(stripSize.width, contentSize.width),
            stripSize.height + contentSize.height + tokens.spacing.small.value,
        )
    }

    override fun arrangeChildren(context: LayoutContext) {
        strip.arrange(Rect.of(Vec2.Zero, strip.measuredSize), context)
        content.arrange(
            Rect.of(
                Vec2(0f, strip.measuredSize.height + tokens.spacing.small.value),
                content.measuredSize,
            ),
            context,
        )
    }
}

/**
 * A titled panel that collapses to its header.
 *
 * The content is detached while collapsed rather than hidden, so a collapsed panel costs
 * nothing to measure. That matters on a configuration screen where most sections are
 * closed: the alternative is paying for every setting on the screen whether or not any
 * of them are visible.
 */
public class ExpandablePanelNode(
    id: UiId,
    title: UiState<String>,
    private val componentContext: ComponentContext,
    isExpanded: Boolean = false,
    /** Built the first time the panel opens. */
    private val content: () -> UiNode,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    private val expandedState: MutableUiState<Boolean> = mutableStateOf(isExpanded, "${id.value}.expanded")

    public val isExpanded: UiState<Boolean> get() = expandedState

    private val chevron = TextNode(
        id.child("chevron"),
        dev.th7bo.sidequest.ui.state.derivedStateOf("${id.value}.chevron") {
            if (expandedState.value) "▾" else "▸"
        },
        TextRole.SECONDARY,
    )

    private val header = RowNode(id.child("header"), spacing = tokens.spacing.small).apply {
        verticalAlignment = VerticalAlignment.CENTER
        addChild(chevron)
        addChild(TextNode(id.child("title"), title, TextRole.LABEL))
    }

    private var built: UiNode? = null

    /** Whether the content has been built yet. For assertions. */
    public val isContentBuilt: Boolean get() = built != null

    init {
        interactive = true
        focusable = true
        addChild(header)
        if (isExpanded) attachContent()
    }

    public fun toggle(): Unit = setExpanded(!expandedState.peek())

    public fun setExpanded(expanded: Boolean) {
        if (expandedState.peek() == expanded) return
        expandedState.value = expanded
        if (expanded) {
            attachContent()
        } else {
            built?.let { removeChild(it) }
        }
        invalidateMeasure()
    }

    private fun attachContent() {
        val node = built ?: content().also { built = it }
        if (node.parent == null) addChild(node)
    }

    override fun onInputEvent(event: InputEvent) {
        if (event.phase != EventPhase.TARGET) return
        when {
            event is PointerDownEvent -> {
                toggle()
                event.consume()
            }
            event is KeyDownEvent && (event.key == Key.ENTER || event.key == Key.SPACE) -> {
                toggle()
                event.consume()
            }
            else -> Unit
        }
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val headerSize = header.measure(constraints.loosen(), context)
        val body = built?.takeIf { expandedState.peek() }
        val bodySize = body?.measure(
            Constraints(
                maxWidth = constraints.maxWidth,
                maxHeight = (constraints.maxHeight - headerSize.height).coerceAtLeast(0f),
            ),
            context,
        ) ?: Size.Zero

        return Size(
            maxOf(headerSize.width, bodySize.width),
            headerSize.height + if (bodySize.height > 0f) bodySize.height + tokens.spacing.small.value else 0f,
        )
    }

    override fun arrangeChildren(context: LayoutContext) {
        header.arrange(Rect.of(Vec2.Zero, header.measuredSize), context)
        if (!expandedState.peek()) return
        built?.let {
            it.arrange(
                Rect.of(Vec2(0f, header.measuredSize.height + tokens.spacing.small.value), it.measuredSize),
                context,
            )
        }
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        if (!isHovered) return
        val headerBounds = Rect(bounds.x, bounds.y, bounds.width, header.measuredSize.height)
        renderer.roundedRect(headerBounds, Corners.all(tokens.radii.small), context.theme.tokens.colors.hoverBackground)
        context.diagnostics.drawCalls++
    }
}

/** Convenience for a panel whose title never changes. */
public fun expandablePanel(
    id: UiId,
    title: String,
    componentContext: ComponentContext,
    isExpanded: Boolean = false,
    content: () -> UiNode,
): ExpandablePanelNode = ExpandablePanelNode(id, constantState(title), componentContext, isExpanded, content)
