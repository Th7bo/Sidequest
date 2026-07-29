package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.config.Category
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.content.SurfaceNode
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.core.layout.ColumnNode
import dev.th7bo.sidequest.ui.core.layout.PaddingNode
import dev.th7bo.sidequest.ui.core.layout.RowNode
import dev.th7bo.sidequest.ui.core.layout.SpacerNode
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Insets
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.input.CharTypedEvent
import dev.th7bo.sidequest.ui.input.EventPhase
import dev.th7bo.sidequest.ui.input.InputEvent
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.KeyDownEvent
import dev.th7bo.sidequest.ui.input.PointerDownEvent
import dev.th7bo.sidequest.ui.rendering.Corners
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.derivedStateOf
import dev.th7bo.sidequest.ui.state.mutableStateOf

/** One entry in the category sidebar. */
public class CategoryButtonNode(
    public val category: Category,
    private val componentContext: ComponentContext,
    private val isActive: UiState<Boolean>,
    private val onSelect: (UiId) -> Unit,
) : UiNode(category.id.child("sidebar_item")) {

    private val tokens = componentContext.theme.tokens
    private val label = TextNode(category.id.child("sidebar_label"), category.title, TextRole.LABEL)

    init {
        interactive = true
        focusable = true
        addChild(label)
        isActive.observe(scope) { invalidatePaint() }
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val labelSize = label.measure(constraints.loosen(), context)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else labelSize.width
        return Size(width, tokens.metrics.controlHeight.value)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val labelSize = label.measuredSize
        label.arrange(
            Rect.of(
                Vec2(labelLeft, (measuredSize.height - labelSize.height) / 2f),
                labelSize,
            ),
            context,
        )
    }

    /** Text starts past the icon column, so labels line up whether or not icons exist. */
    private val labelLeft: Float
        get() = tokens.spacing.large.value + ICON_SIZE + tokens.spacing.medium.value

    override fun onInputEvent(event: InputEvent) {
        if (event.phase != EventPhase.TARGET) return
        when {
            event is PointerDownEvent -> {
                onSelect(category.id)
                event.consume()
            }
            event is KeyDownEvent && (event.key == Key.ENTER || event.key == Key.SPACE) -> {
                onSelect(category.id)
                event.consume()
            }
        }
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val active = isActive.peek()

        if (active || isHovered) {
            renderer.roundedRect(
                bounds,
                tokens.radii.small,
                if (active) palette.selectedBackground else palette.hoverBackground,
            )
            context.diagnostics.drawCalls++
        }
        if (active) {
            // The active category carries a marker as well as a fill, so selection is
            // never signalled by colour alone.
            renderer.fillRect(Rect(bounds.x, bounds.y + 3f, 2f, bounds.height - 6f), palette.accent)
            context.diagnostics.drawCalls++
        }
        val content = if (active) palette.textPrimary else palette.textSecondary
        label.colorOverride = content

        category.icon?.let { icon ->
            componentContext.icons.draw(
                renderer,
                icon,
                Rect(
                    bounds.x + tokens.spacing.large.value,
                    bounds.y + (bounds.height - ICON_SIZE) / 2f,
                    ICON_SIZE,
                    ICON_SIZE,
                ),
                if (active) palette.accent else content,
            )
            context.diagnostics.drawCalls++
        }

        if (isFocused) {
            renderer.border(bounds, tokens.radii.small, tokens.metrics.focusRingWidth, palette.focusRing)
            context.diagnostics.drawCalls++
        }
    }

    private companion object {
        const val ICON_SIZE = 10f
    }
}

/**
 * The category list down the left-hand side.
 *
 * A plain column rather than a virtualized list: a screen with enough categories to need
 * virtualizing here has a navigation problem, not a performance one.
 */
public class SidebarNode(
    id: UiId,
    categories: List<Category>,
    private val componentContext: ComponentContext,
    activeCategory: UiState<UiId?>,
    /** Pinned to the bottom: search, reset, status. Empty for a bare sidebar. */
    footer: List<UiNode> = emptyList(),
    onSelect: (UiId) -> Unit,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    private val body = ColumnNode(id.child("body"), spacing = tokens.spacing.xs)

    init {
        for (category in categories) {
            val isActive = derivedStateOf("${category.id.value}.active") {
                activeCategory.value == category.id
            }
            val button = CategoryButtonNode(category, componentContext, isActive, onSelect)
            button.isVisible = category.visibleWhen.peek()
            body.addChild(button)
        }

        // A weighted spacer pushes the footer to the bottom without anyone computing a
        // coordinate — the whole point of the layout engine.
        body.addChild(SpacerNode(id.child("push")).apply { layoutWeight = 1f })
        footer.forEach(body::addChild)

        addChild(
            PaddingNode(id.child("padding"), Insets.all(tokens.spacing.medium)).apply {
                addChild(body)
            },
        )
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val width = tokens.metrics.sidebarWidth.value
        val height = constraints.maxHeight.takeIf { constraints.hasBoundedHeight } ?: 0f
        children.firstOrNull()?.measure(
            Constraints(minWidth = width, maxWidth = width, minHeight = height, maxHeight = height),
            context,
        )
        return Size(width, height)
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        renderer.fillRect(bounds, palette.panelBackground)
        renderer.fillRect(
            Rect(bounds.right - 1f, bounds.y, 1f, bounds.height),
            palette.border,
        )
        context.diagnostics.drawCalls += 2
    }
}

/**
 * The search box.
 *
 * Owns the query text and pushes it to a callback on every keystroke. It does not know
 * what searching means — that belongs to the controller — which is what lets the same
 * widget drive setting search, HUD search and anything added later.
 */
public class SearchBoxNode(
    id: UiId,
    private val componentContext: ComponentContext,
    private val placeholder: String = "Search settings…",
    /**
     * The authoritative query, when something else can also change it.
     *
     * Search can be driven from a keybind, a command or a test as well as from this
     * box. Mirroring an external state keeps the box showing what is actually being
     * searched instead of only what was typed into it.
     */
    externalQuery: UiState<String>? = null,
    private val onQueryChanged: (String) -> Unit,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    private val queryState: MutableUiState<String> = mutableStateOf("", "${id.value}.query")

    /** The current query. Observable, so a result count can react to it. */
    public val query: UiState<String> get() = queryState

    private val display = TextNode(
        id.child("text"),
        derivedStateOf("${id.value}.display") {
            queryState.value.ifEmpty { placeholder }
        },
        TextRole.BODY,
    )

    init {
        interactive = true
        focusable = true
        addChild(display)

        externalQuery?.observe(scope) { authoritative ->
            // Adopt without echoing back, or the two would notify each other forever.
            if (queryState.peek() != authoritative) {
                queryState.value = authoritative
                invalidatePaint()
            }
        }
    }

    /** Replaces the query and notifies. Used by "clear" buttons and by tests. */
    public fun setQuery(value: String) {
        if (queryState.peek() == value) return
        queryState.value = value
        onQueryChanged(value)
        invalidatePaint()
    }

    override fun onInputEvent(event: InputEvent) {
        if (event.phase != EventPhase.TARGET) return

        when {
            event is PointerDownEvent -> event.consume()

            event is CharTypedEvent -> {
                setQuery(queryState.peek() + event.char)
                event.consume()
            }

            event is KeyDownEvent && event.key == Key.BACKSPACE -> {
                val current = queryState.peek()
                if (current.isNotEmpty()) setQuery(current.dropLast(1))
                event.consume()
            }

            event is KeyDownEvent && event.key == Key.ESCAPE && queryState.peek().isNotEmpty() -> {
                // Escape clears the query before it gives up focus, which is what a user
                // pressing it in a search box almost always means.
                setQuery("")
                event.consume()
            }
        }
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else DEFAULT_WIDTH
        display.measure(
            Constraints(maxWidth = width - tokens.spacing.large.value * 2),
            context,
        )
        return Size(width, tokens.metrics.controlHeight.value + tokens.spacing.small.value)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val textSize = display.measuredSize
        display.arrange(
            Rect.of(
                Vec2(tokens.spacing.large.value, (measuredSize.height - textSize.height) / 2f),
                textSize,
            ),
            context,
        )
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        renderer.roundedRect(bounds, tokens.radii.small, palette.elevatedPanelBackground)
        renderer.border(
            bounds,
            tokens.radii.small,
            tokens.metrics.borderWidth,
            if (isFocused) palette.accent else palette.border,
        )
        context.diagnostics.drawCalls += 2
        display.colorOverride =
            if (queryState.peek().isEmpty()) palette.textDisabled else palette.textPrimary
    }

    private companion object {
        const val DEFAULT_WIDTH = 200f
    }
}

/**
 * The whole configuration screen: search across the top, categories down the left, the
 * settings list filling the rest.
 *
 * This is the node a host attaches to a runtime.
 */
public class ConfigScreenLayoutNode(
    id: UiId,
    private val controller: ConfigScreenController,
    private val componentContext: ComponentContext,
    /** Invoked by the header's primary action. Null hides it. */
    private val onSaveAndClose: (() -> Unit)? = null,
    /** Invoked by the header's close button. Null hides it. */
    private val onClose: (() -> Unit)? = null,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    /** Exposed so a host can focus the search box, and so tests can drive it. */
    public val searchBox: SearchBoxNode = SearchBoxNode(
        id = id.child("search"),
        componentContext = componentContext,
        externalQuery = controller.searchQuery,
    ) { query ->
        controller.search(query)
    }

    /** Restores every setting on the screen to its default. */
    public val resetButton: ChromeButtonNode = ChromeButtonNode(
        id = id.child("reset_all"),
        componentContext = componentContext,
        label = "Reset All",
        tone = ButtonTone.NEUTRAL,
    ) {
        controller.screen.resetAll()
    }

    public val sidebar: SidebarNode = SidebarNode(
        id = id.child("sidebar"),
        categories = controller.screen.categories,
        componentContext = componentContext,
        activeCategory = controller.activeCategory,
        // Search and Reset sit at the bottom of the sidebar, as in the reference: the
        // top of the screen belongs to the title and the primary action.
        footer = listOf(searchBox, resetButton),
        onSelect = { categoryId -> controller.selectCategory(categoryId) },
    )

    private val header: ScreenHeaderNode = ScreenHeaderNode(
        id = id.child("header"),
        componentContext = componentContext,
        title = controller.screen.title,
        // From the screen definition. A hardcoded subtitle here made every screen
        // claim to be the mod's own configuration, including the component gallery.
        subtitle = controller.screen.description,
        actions = buildList {
            onSaveAndClose?.let { action ->
                add(
                    ChromeButtonNode(
                        id.child("save"),
                        componentContext,
                        "Save & Close",
                        ButtonTone.PRIMARY,
                        onActivate = action,
                    ),
                )
            }
            onClose?.let { action ->
                add(
                    ChromeButtonNode(
                        id.child("close"),
                        componentContext,
                        "X",
                        ButtonTone.NEUTRAL,
                        fixedWidth = CLOSE_BUTTON_WIDTH,
                        onActivate = action,
                    ),
                )
            }
        },
    )

    /**
     * The right-hand column. Present in the tree always, hidden when the viewport is too
     * narrow — building it once and toggling visibility keeps its state across resizes.
     */
    public val infoPanel: InfoPanelNode = InfoPanelNode(
        id = id.child("info"),
        componentContext = componentContext,
        aboutTitle = "About",
        aboutBody = "Enable only the features you need and tune everything to your liking.",
        tipBody = "Hover any setting for more detail about what it does.",
        profileActions = emptyList(),
    )

    /** Shown instead of the list when a search matches nothing. */
    private val emptyState = TextNode(
        id.child("empty"),
        derivedStateOf("${id.value}.empty") {
            "No settings match \"${controller.searchQuery.value}\""
        },
        TextRole.SECONDARY,
    )

    init {
        val listArea = ColumnNode(id.child("list_area")).apply {
            layoutWeight = 1f
            addChild(controller.list.apply { layoutWeight = 1f })
            addChild(
                PaddingNode(id.child("empty_padding"), Insets.all(tokens.spacing.xl))
                    .apply { addChild(emptyState) },
            )
        }

        val content = ColumnNode(id.child("content")).apply {
            layoutWeight = 1f
            addChild(header)
            addChild(listArea)
        }

        addChild(
            RowNode(id.child("root_row")).apply {
                addChild(sidebar)
                addChild(content)
                addChild(infoPanel)
            },
        )

        controller.rowsChanged.observe(scope) { refreshEmptyState() }
        refreshEmptyState()
    }

    private fun refreshEmptyState() {
        val filtering = controller.searchQuery.peek().isNotBlank()
        val nothingToShow = filtering && controller.rows.isEmpty()
        emptyState.parent?.isVisible = nothingToShow
        controller.list.isVisible = !nothingToShow
    }

    /**
     * Inset from the window edge, so the panel reads as a floating surface over the
     * game rather than as a full-screen takeover. Also the reason nothing is clipped by
     * the window border.
     */
    private val outerMargin: Float get() = tokens.spacing.xl.value

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val width = constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: FALLBACK_SIZE
        val height = constraints.maxHeight.takeIf { constraints.hasBoundedHeight } ?: FALLBACK_SIZE

        // Responsive columns: the info panel only appears when the settings list would
        // still have room to breathe afterwards. Below that it is hidden rather than
        // squeezed, because three unreadable columns are worse than two readable ones.
        val panelWidth = tokens.metrics.inspectorWidth.value
        val sidebarWidth = tokens.metrics.sidebarWidth.value
        val listAfterPanel = width - outerMargin * 2 - sidebarWidth - panelWidth
        infoPanel.isVisible = listAfterPanel >= MIN_LIST_WIDTH

        val inner = Constraints(
            minWidth = (width - outerMargin * 2).coerceAtLeast(0f),
            maxWidth = (width - outerMargin * 2).coerceAtLeast(0f),
            minHeight = (height - outerMargin * 2).coerceAtLeast(0f),
            maxHeight = (height - outerMargin * 2).coerceAtLeast(0f),
        )
        children.first().measure(inner, context)
        return Size(width, height)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val child = children.first()
        child.arrange(Rect.of(Vec2(outerMargin, outerMargin), child.measuredSize), context)
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val panel = bounds.inset(
            Insets(outerMargin, outerMargin, outerMargin, outerMargin),
        )
        renderer.roundedRect(panel, Corners.all(tokens.radii.large), palette.windowBackground)
        renderer.border(panel, Corners.all(tokens.radii.large), tokens.metrics.borderWidth, palette.border)
        context.diagnostics.drawCalls += 2
    }

    private companion object {
        const val CLOSE_BUTTON_WIDTH = 26f
        const val FALLBACK_SIZE = 480f

        /**
         * Narrowest the settings list may get before the info panel is dropped.
         *
         * Roughly a label column plus a control; below this the descriptions start
         * wrapping to three lines and every row doubles in height.
         */
        const val MIN_LIST_WIDTH = 300f
    }
}
