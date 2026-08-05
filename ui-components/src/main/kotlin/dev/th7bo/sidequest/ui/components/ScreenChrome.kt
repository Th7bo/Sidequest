package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.config.Category
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.content.SurfaceNode
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.core.layout.ColumnNode
import dev.th7bo.sidequest.ui.core.layout.FixedSizeNode
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
import dev.th7bo.sidequest.ui.geometry.dp
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.input.CharTypedEvent
import dev.th7bo.sidequest.ui.input.EventPhase
import dev.th7bo.sidequest.ui.input.InputEvent
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.KeyDownEvent
import dev.th7bo.sidequest.ui.input.PointerDownEvent
import dev.th7bo.sidequest.ui.rendering.Corners
import dev.th7bo.sidequest.ui.rendering.Gradient
import dev.th7bo.sidequest.ui.rendering.TextOverflow
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
    private val count = TextNode(
        category.id.child("sidebar_count"),
        derivedStateOf("${category.id.value}.visible_setting_count") {
            category.settings.count { it.isVisible.value }.toString()
        },
        TextRole.CAPTION,
    )

    init {
        interactive = true
        focusable = true
        addChildren(label, count)
        isActive.observe(scope) { invalidatePaint() }
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        count.measure(constraints.loosen(), context)
        val reserved = countPillWidth + tokens.spacing.large.value
        val labelSize = label.measure(
            Constraints(maxWidth = (constraints.maxWidth - labelLeft - reserved).coerceAtLeast(0f)),
            context,
        )
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
        count.arrange(
            Rect.of(
                Vec2(
                    measuredSize.width - tokens.spacing.medium.value - countPillWidth +
                        (countPillWidth - count.measuredSize.width) / 2f,
                    (measuredSize.height - count.measuredSize.height) / 2f,
                ),
                count.measuredSize,
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
            renderer.roundedRect(
                Rect(bounds.x, bounds.y + 4f, 2f, bounds.height - 8f),
                tokens.radii.pill,
                palette.accent,
            )
            // A one-pixel inner sheen keeps the selected surface crisp without boxing
            // the entire navigation item in the accent colour.
            renderer.roundedRect(
                Rect(bounds.x + 5f, bounds.y + 1f, bounds.width - 10f, 1f),
                tokens.radii.pill,
                palette.accent.withAlpha(ACTIVE_SHEEN_ALPHA),
            )
            context.diagnostics.drawCalls += 2
        }
        val content = if (active) palette.textPrimary else palette.textSecondary
        label.colorOverride = content
        val countPill = Rect(
            bounds.right - tokens.spacing.medium.value - countPillWidth,
            bounds.y + (bounds.height - countPillHeight) / 2f,
            countPillWidth,
            countPillHeight,
        )
        renderer.roundedRect(
            countPill,
            tokens.radii.pill,
            if (active) palette.accent.withAlpha(0.16f) else palette.elevatedPanelBackground,
        )
        count.colorOverride = if (active) palette.accent else palette.textDisabled
        context.diagnostics.drawCalls++

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
        const val COUNT_MIN_WIDTH = 20f
        const val COUNT_MIN_HEIGHT = 15f
        const val COUNT_HORIZONTAL_PADDING = 6f
        const val COUNT_VERTICAL_PADDING = 3f
        const val ACTIVE_SHEEN_ALPHA = 0.18f
    }

    private val countPillWidth: Float
        get() = maxOf(COUNT_MIN_WIDTH, count.measuredSize.width + COUNT_HORIZONTAL_PADDING * 2f)

    private val countPillHeight: Float
        get() = maxOf(COUNT_MIN_HEIGHT, count.measuredSize.height + COUNT_VERTICAL_PADDING * 2f)
}

/** Compact product lockup at the top of the navigation rail. */
private class SidebarBrandNode(
    id: UiId,
    private val componentContext: ComponentContext,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens
    private val name = TextNode(id.child("name"), constantState("SIDEQUEST"), TextRole.TITLE)
    private val edition = TextNode(id.child("edition"), constantState("SKYBLOCK CLIENT"), TextRole.CAPTION)

    init {
        addChildren(name, edition)
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else 120f
        val textWidth = (available - BRAND_MARK - tokens.spacing.large.value * 2).coerceAtLeast(0f)
        name.measure(Constraints(maxWidth = textWidth), context)
        edition.measure(Constraints(maxWidth = textWidth), context)
        return Size(available, BRAND_HEIGHT)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val left = BRAND_MARK + tokens.spacing.large.value
        val contentHeight = name.measuredSize.height + tokens.spacing.xs.value + edition.measuredSize.height
        val top = (measuredSize.height - contentHeight) / 2f
        name.arrange(Rect.of(Vec2(left, top), name.measuredSize), context)
        edition.arrange(
            Rect.of(Vec2(left, top + name.measuredSize.height + tokens.spacing.xs.value), edition.measuredSize),
            context,
        )
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val mark = Rect(bounds.x, bounds.y + (bounds.height - BRAND_MARK) / 2f, BRAND_MARK, BRAND_MARK)
        renderer.shadow(mark, tokens.radii.medium, tokens.effects.panelShadow.copy(color = palette.accent.withAlpha(0.24f), blurRadius = 10f))
        renderer.roundedRect(mark, tokens.radii.medium, palette.accent)
        renderer.border(mark, tokens.radii.medium, tokens.metrics.borderWidth, palette.accentHover)
        val inset = 5f
        renderer.border(
            mark.inset(Insets(inset, inset, inset, inset)),
            tokens.radii.small,
            tokens.metrics.borderWidth,
            palette.onAccent,
        )
        edition.colorOverride = palette.textDisabled
        renderer.roundedRect(
            Rect(mark.x + 4f, mark.y + 2f, mark.width - 8f, 1f),
            tokens.radii.pill,
            palette.onAccent.withAlpha(0.38f),
        )
        context.diagnostics.drawCalls += 5
    }

    private companion object {
        const val BRAND_HEIGHT = 42f
        const val BRAND_MARK = 24f
    }
}

/** Quiet overline separating product identity from the navigation choices. */
private class SidebarSectionLabelNode(id: UiId) : UiNode(id) {
    private val label = TextNode(id.child("label"), constantState("CONFIGURATION"), TextRole.CAPTION)

    init { addChild(label) }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        label.measure(constraints.loosen(), context)
        return Size(constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: 120f, 16f)
    }

    override fun arrangeChildren(context: LayoutContext) {
        label.arrange(Rect.of(Vec2(4f, (measuredSize.height - label.measuredSize.height) / 2f), label.measuredSize), context)
    }
}

/** Persistent reassurance that config edits are live and do not need a separate apply step. */
private class SidebarStatusNode(
    id: UiId,
    private val componentContext: ComponentContext,
) : UiNode(id) {
    private val label = TextNode(id.child("label"), constantState("ALL CHANGES SAVED"), TextRole.CAPTION)

    init { addChild(label) }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        label.measure(constraints.loosen(), context)
        return Size(constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: 120f, 18f)
    }

    override fun arrangeChildren(context: LayoutContext) {
        label.arrange(Rect.of(Vec2(14f, (measuredSize.height - label.measuredSize.height) / 2f), label.measuredSize), context)
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        renderer.roundedRect(bounds, componentContext.theme.tokens.radii.small, palette.success.withAlpha(STATUS_WASH_ALPHA))
        renderer.border(
            bounds,
            componentContext.theme.tokens.radii.small,
            componentContext.theme.tokens.metrics.borderWidth,
            palette.success.withAlpha(STATUS_BORDER_ALPHA),
        )
        renderer.roundedRect(Rect(bounds.x + 3f, bounds.y + (bounds.height - 5f) / 2f, 5f, 5f), componentContext.theme.tokens.radii.pill, palette.success)
        label.colorOverride = palette.textDisabled
        context.diagnostics.drawCalls += 3
    }

    private companion object {
        const val STATUS_WASH_ALPHA = 0.055f
        const val STATUS_BORDER_ALPHA = 0.13f
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
        body.addChild(SidebarBrandNode(id.child("brand"), componentContext))
        body.addChild(SpacerNode(id.child("brand_gap"), Size(0f, tokens.spacing.medium.value)))
        body.addChild(SidebarSectionLabelNode(id.child("section_label")))
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
        renderer.gradient(
            Rect(bounds.x, bounds.y, bounds.width, minOf(bounds.height, SIDEBAR_GLOW_HEIGHT)),
            Gradient.linear(palette.accent.withAlpha(SIDEBAR_GLOW_ALPHA), palette.accent.withAlpha(0f)),
            tokens.radii.none,
        )
        renderer.fillRect(
            Rect(bounds.right - 1f, bounds.y, 1f, bounds.height),
            palette.border,
        )
        context.diagnostics.drawCalls += 3
    }

    private companion object {
        const val SIDEBAR_GLOW_HEIGHT = 92f
        const val SIDEBAR_GLOW_ALPHA = 0.035f
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

    private val clearLabel = TextNode(id.child("clear"), constantState("×"), TextRole.LABEL)

    init {
        interactive = true
        focusable = true
        addChild(display)
        addChild(clearLabel)
        clearLabel.isVisible = false

        externalQuery?.observe(scope) { authoritative ->
            // Adopt without echoing back, or the two would notify each other forever.
            if (queryState.peek() != authoritative) {
                queryState.value = authoritative
                clearLabel.isVisible = authoritative.isNotEmpty()
                invalidatePaint()
            }
        }
    }

    /** Replaces the query and notifies. Used by "clear" buttons and by tests. */
    public fun setQuery(value: String) {
        if (queryState.peek() == value) return
        queryState.value = value
        clearLabel.isVisible = value.isNotEmpty()
        onQueryChanged(value)
        invalidatePaint()
    }

    override fun onInputEvent(event: InputEvent) {
        if (event.phase != EventPhase.TARGET) return

        when {
            event is PointerDownEvent -> {
                if (queryState.peek().isNotEmpty() && event.position.x >= measuredSize.width - CLEAR_HIT_WIDTH) {
                    setQuery("")
                }
                event.consume()
            }

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
            Constraints(
                maxWidth = width - SEARCH_TEXT_LEFT -
                    if (queryState.peek().isEmpty()) tokens.spacing.medium.value else CLEAR_HIT_WIDTH,
            ),
            context,
        )
        clearLabel.measure(constraints.loosen(), context)
        return Size(width, tokens.metrics.controlHeight.value + tokens.spacing.small.value)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val textSize = display.measuredSize
        display.arrange(
            Rect.of(
                Vec2(SEARCH_TEXT_LEFT, (measuredSize.height - textSize.height) / 2f),
                textSize,
            ),
            context,
        )
        clearLabel.arrange(
            Rect.of(
                Vec2(measuredSize.width - tokens.spacing.large.value - clearLabel.measuredSize.width, (measuredSize.height - clearLabel.measuredSize.height) / 2f),
                clearLabel.measuredSize,
            ),
            context,
        )
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        renderer.roundedRect(bounds, tokens.radii.small, palette.elevatedPanelBackground)
        if (isFocused) {
            renderer.roundedRect(
                bounds.outset(Insets(1f, 1f, 1f, 1f)),
                tokens.radii.small,
                palette.accent.withAlpha(SEARCH_FOCUS_GLOW_ALPHA),
            )
            // Restore the field over its glow.
            renderer.roundedRect(bounds, tokens.radii.small, palette.elevatedPanelBackground)
            context.diagnostics.drawCalls += 2
        }
        renderer.border(
            bounds,
            tokens.radii.small,
            tokens.metrics.borderWidth,
            if (isFocused) palette.accent else palette.border,
        )
        context.diagnostics.drawCalls += 2
        renderer.roundedRect(
            Rect(bounds.x + 7f, bounds.y + 1f, bounds.width - 14f, 1f),
            tokens.radii.pill,
            palette.textPrimary.withAlpha(SEARCH_SHEEN_ALPHA),
        )
        context.diagnostics.drawCalls++
        val icon = Rect(
            bounds.x + tokens.spacing.large.value,
            bounds.y + (bounds.height - SEARCH_ICON) / 2f,
            SEARCH_ICON - 2f,
            SEARCH_ICON - 2f,
        )
        val iconColour = if (isFocused) palette.accent else palette.textDisabled
        renderer.border(icon, tokens.radii.pill, tokens.metrics.borderWidth, iconColour)
        // Two tiny rounded steps form a smooth diagonal handle without relying on a low-resolution sprite.
        renderer.roundedRect(Rect(icon.right - 1f, icon.bottom - 1f, 2f, 2f), tokens.radii.pill, iconColour)
        renderer.roundedRect(Rect(icon.right, icon.bottom, 2f, 2f), tokens.radii.pill, iconColour)
        context.diagnostics.drawCalls += 3
        display.colorOverride =
            if (queryState.peek().isEmpty()) palette.textDisabled else palette.textPrimary
        clearLabel.colorOverride = if (isHovered) palette.textPrimary else palette.textSecondary
    }

    private companion object {
        const val DEFAULT_WIDTH = 200f
        const val SEARCH_ICON = 10f
        const val SEARCH_TEXT_LEFT = 28f
        const val CLEAR_HIT_WIDTH = 22f
        const val SEARCH_FOCUS_GLOW_ALPHA = 0.11f
        const val SEARCH_SHEEN_ALPHA = 0.055f
    }
}

/** Designed search-empty state rather than an unexplained blank settings column. */
private class ConfigEmptyStateNode(
    id: UiId,
    private val componentContext: ComponentContext,
    query: UiState<String>,
) : UiNode(id) {
    private val title = TextNode(
        id.child("title"),
        derivedStateOf("${id.value}.title") { "No settings match “${query.value}”" },
        TextRole.TITLE,
    )
    private val hint = TextNode(
        id.child("hint"),
        constantState("Try a shorter search, choose another category, or press Esc to clear."),
        TextRole.SECONDARY,
    ).apply {
        maxLines = 2
        overflow = TextOverflow.WRAP
    }

    init { addChildren(title, hint) }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val width = constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: 320f
        val height = constraints.maxHeight.takeIf { constraints.hasBoundedHeight } ?: 160f
        val textWidth = minOf(width - 40f, EMPTY_TEXT_WIDTH).coerceAtLeast(0f)
        title.measure(Constraints(maxWidth = textWidth), context)
        hint.measure(Constraints(maxWidth = textWidth), context)
        return Size(width, height)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val contentHeight = EMPTY_ICON_SPACE + title.measuredSize.height + 4f + hint.measuredSize.height
        val top = ((measuredSize.height - contentHeight) / 2f).coerceAtLeast(12f)
        title.arrange(
            Rect.of(Vec2((measuredSize.width - title.measuredSize.width) / 2f, top + EMPTY_ICON_SPACE), title.measuredSize),
            context,
        )
        hint.arrange(
            Rect.of(
                Vec2((measuredSize.width - hint.measuredSize.width) / 2f, top + EMPTY_ICON_SPACE + title.measuredSize.height + 4f),
                hint.measuredSize,
            ),
            context,
        )
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val titleBounds = title.absoluteBounds()
        val ring = Rect(titleBounds.center.x - 10f, titleBounds.y - EMPTY_ICON_SPACE + 5f, 20f, 20f)
        renderer.roundedRect(ring, componentContext.theme.tokens.radii.pill, palette.selectedBackground)
        renderer.border(ring, componentContext.theme.tokens.radii.pill, componentContext.theme.tokens.metrics.borderWidth, palette.accent.withAlpha(0.55f))
        renderer.roundedRect(Rect(ring.center.x - 3f, ring.center.y - 3f, 6f, 6f), componentContext.theme.tokens.radii.pill, palette.accent)
        title.colorOverride = palette.textPrimary
        hint.colorOverride = palette.textSecondary
        context.diagnostics.drawCalls += 3
    }

    private companion object {
        const val EMPTY_TEXT_WIDTH = 260f
        const val EMPTY_ICON_SPACE = 34f
    }
}

/** Compact live overview of the category currently being edited. */
private class CategorySummaryNode(
    id: UiId,
    private val controller: ConfigScreenController,
    private val componentContext: ComponentContext,
) : UiNode(id) {
    private data class Metric(val value: TextNode, val label: TextNode)

    private fun activeCategory(): Category? =
        controller.activeCategory.value?.let(controller.screen::category)

    private val metrics = listOf(
        Metric(
            TextNode(
                id.child("settings_value"),
                derivedStateOf("${id.value}.settings") {
                    activeCategory()?.settings?.count { it.isVisible.value }?.toString() ?: "0"
                },
                TextRole.TITLE,
            ),
            TextNode(id.child("settings_label"), constantState("AVAILABLE SETTINGS"), TextRole.CAPTION),
        ),
        Metric(
            TextNode(
                id.child("modified_value"),
                derivedStateOf("${id.value}.modified") {
                    activeCategory()?.settings?.count { it.isVisible.value && it.isModified.value }?.toString() ?: "0"
                },
                TextRole.TITLE,
            ),
            TextNode(id.child("modified_label"), constantState("CUSTOMIZED"), TextRole.CAPTION),
        ),
        Metric(
            TextNode(
                id.child("sections_value"),
                derivedStateOf("${id.value}.sections") {
                    activeCategory()?.sections?.count { section ->
                        section.visibleWhen.value && section.settings.any { it.isVisible.value }
                    }?.toString() ?: "0"
                },
                TextRole.TITLE,
            ),
            TextNode(id.child("sections_label"), constantState("SECTIONS"), TextRole.CAPTION),
        ),
    )

    init { metrics.forEach { addChildren(it.value, it.label) } }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        metrics.forEach { metric ->
            metric.value.measure(constraints.loosen(), context)
            metric.label.measure(constraints.loosen(), context)
        }
        return Size(constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: 480f, SUMMARY_HEIGHT)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val sideMargin = componentContext.theme.tokens.spacing.xl.value
        val cardWidth = ((measuredSize.width - sideMargin * 2f) / 3f).coerceAtLeast(0f)
        metrics.forEachIndexed { index, metric ->
            val cardX = sideMargin + index * cardWidth
            val contentX = cardX + METRIC_PADDING
            val contentHeight = metric.value.measuredSize.height + 2f + metric.label.measuredSize.height
            val top = (SUMMARY_HEIGHT - contentHeight) / 2f
            metric.value.arrange(Rect.of(Vec2(contentX, top), metric.value.measuredSize), context)
            metric.label.arrange(Rect.of(Vec2(contentX, top + metric.value.measuredSize.height + 2f), metric.label.measuredSize), context)
        }
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val sideMargin = componentContext.theme.tokens.spacing.xl.value
        val panel = Rect(
            bounds.x + sideMargin,
            bounds.y + SUMMARY_VERTICAL_MARGIN,
            (bounds.width - sideMargin * 2f).coerceAtLeast(0f),
            bounds.height - SUMMARY_VERTICAL_MARGIN * 2f,
        )
        val cardWidth = panel.width / 3f

        renderer.roundedRect(panel, componentContext.theme.tokens.radii.medium, palette.elevatedPanelBackground)
        renderer.border(panel, componentContext.theme.tokens.radii.medium, componentContext.theme.tokens.metrics.borderWidth, palette.border)
        renderer.roundedRect(
            Rect(panel.x + 8f, panel.y + 1f, panel.width - 16f, 1f),
            componentContext.theme.tokens.radii.pill,
            palette.textPrimary.withAlpha(SUMMARY_SHEEN_ALPHA),
        )
        renderer.roundedRect(
            Rect(panel.x, panel.y + 8f, 2f, panel.height - 16f),
            componentContext.theme.tokens.radii.pill,
            palette.accent,
        )
        context.diagnostics.drawCalls += 4

        for (index in 1 until metrics.size) {
            renderer.fillRect(
                Rect(panel.x + cardWidth * index, panel.y + 9f, 1f, panel.height - 18f),
                palette.border,
            )
            context.diagnostics.drawCalls++
        }

        metrics.forEachIndexed { index, metric ->
            if (index == 1) {
                val category = activeCategory()
                val total = category?.settings?.count { it.isVisible.value } ?: 0
                val modified = category?.settings?.count { it.isVisible.value && it.isModified.value } ?: 0
                val progress = if (total == 0) 0f else modified.toFloat() / total
                val track = Rect(
                    panel.x + cardWidth + METRIC_PADDING,
                    panel.bottom - 6f,
                    cardWidth - METRIC_PADDING * 2f,
                    2f,
                )
                renderer.roundedRect(track, componentContext.theme.tokens.radii.pill, palette.border)
                if (progress > 0f) {
                    renderer.roundedRect(
                        Rect(track.x, track.y, track.width * progress, track.height),
                        componentContext.theme.tokens.radii.pill,
                        palette.accent,
                    )
                    context.diagnostics.drawCalls++
                }
                context.diagnostics.drawCalls++
            }
            metric.value.colorOverride = if (index == 1) palette.accent else palette.textPrimary
            metric.label.colorOverride = palette.textDisabled
        }
    }

    private companion object {
        const val SUMMARY_HEIGHT = 50f
        const val SUMMARY_VERTICAL_MARGIN = 5f
        const val METRIC_PADDING = 12f
        const val SUMMARY_SHEEN_ALPHA = 0.05f
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

    /** Wide-layout search. It mirrors [searchBox] through the controller's authoritative query state. */
    private val headerSearchBox: SearchBoxNode = SearchBoxNode(
        id = id.child("header_search"),
        componentContext = componentContext,
        placeholder = "Search anything…",
        externalQuery = controller.searchQuery,
    ) { query ->
        controller.search(query)
    }

    private val headerSearch = FixedSizeNode(
        id.child("header_search_slot"),
        width = HEADER_SEARCH_WIDTH.dp,
    ).apply { addChild(headerSearchBox) }

    /** Restores every setting on the screen to its default. */
    public val resetButton: ChromeButtonNode = ChromeButtonNode(
        id = id.child("reset_all"),
        componentContext = componentContext,
        label = "Reset All",
        tone = ButtonTone.DANGER,
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
        footer = listOf(SidebarStatusNode(id.child("saved_status"), componentContext), searchBox, resetButton),
        onSelect = { categoryId -> controller.selectCategory(categoryId) },
    )

    private val activeCategoryTitle: UiState<String> = derivedStateOf("${id.value}.active_category_title") {
        controller.activeCategory.value
            ?.let(controller.screen::category)
            ?.title
            ?.value
            ?: controller.screen.title.value
    }

    private val activeCategoryDescription: UiState<String> =
        derivedStateOf("${id.value}.active_category_description") {
            controller.activeCategory.value
                ?.let(controller.screen::category)
                ?.description
                ?.value
                ?: controller.screen.description?.value
                ?: "Configure Sidequest"
        }

    private val activeCategoryMeta: UiState<String> = derivedStateOf("${id.value}.active_category_meta") {
        val category = controller.activeCategory.value?.let(controller.screen::category)
        val visible = category?.settings?.count { it.isVisible.value } ?: controller.screen.settings.size
        "CONFIGURATION  /  $visible ${if (visible == 1) "SETTING" else "SETTINGS"}"
    }

    private val header: ScreenHeaderNode = ScreenHeaderNode(
        id = id.child("header"),
        componentContext = componentContext,
        title = activeCategoryTitle,
        subtitle = activeCategoryDescription,
        eyebrow = activeCategoryMeta,
        actions = buildList {
            add(headerSearch)
            onSaveAndClose?.let { action ->
                add(
                    ChromeButtonNode(
                        id.child("save"),
                        componentContext,
                        "Done",
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
                        "×",
                        ButtonTone.NEUTRAL,
                        fixedWidth = CLOSE_BUTTON_WIDTH,
                        onActivate = action,
                    ),
                )
            }
        },
    )

    private val categorySummary = CategorySummaryNode(id.child("category_summary"), controller, componentContext)

    /** Shown instead of the list when a search matches nothing. */
    private val emptyState = ConfigEmptyStateNode(
        id.child("empty"),
        componentContext,
        controller.searchQuery,
    )

    init {
        val listArea = ColumnNode(id.child("list_area")).apply {
            layoutWeight = 1f
            addChild(controller.list.apply { layoutWeight = 1f })
            addChild(
                PaddingNode(id.child("empty_padding"), Insets.all(tokens.spacing.xl))
                    .apply {
                        layoutWeight = 1f
                        addChild(emptyState)
                    },
            )
        }

        val content = ColumnNode(id.child("content")).apply {
            layoutWeight = 1f
            addChild(header)
            addChild(categorySummary)
            addChild(listArea)
        }

        addChild(
            RowNode(id.child("root_row")).apply {
                addChild(sidebar)
                addChild(content)
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

        // Search lives where the available space makes it easiest to reach. On wide screens it sits beside
        // the page actions; on compact Minecraft GUI scales it returns to the bottom of the navigation rail.
        val useHeaderSearch = width >= HEADER_SEARCH_MIN_VIEWPORT
        headerSearch.isVisible = useHeaderSearch
        searchBox.isVisible = !useHeaderSearch

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
        renderer.shadow(panel, tokens.radii.large, tokens.effects.panelShadow)
        renderer.roundedRect(panel, Corners.all(tokens.radii.large), palette.windowBackground)
        renderer.border(panel, Corners.all(tokens.radii.large), tokens.metrics.borderWidth, palette.border)
        renderer.roundedRect(
            Rect(panel.x + tokens.radii.large.value, panel.y + 1f, panel.width - tokens.radii.large.value * 2f, 1f),
            tokens.radii.pill,
            palette.textPrimary.withAlpha(FRAME_SHEEN_ALPHA),
        )
        context.diagnostics.drawCalls += 4
    }

    private companion object {
        const val CLOSE_BUTTON_WIDTH = 26f
        const val HEADER_SEARCH_WIDTH = 154f
        const val HEADER_SEARCH_MIN_VIEWPORT = 620f
        const val FRAME_SHEEN_ALPHA = 0.055f
        const val FALLBACK_SIZE = 480f

    }
}
