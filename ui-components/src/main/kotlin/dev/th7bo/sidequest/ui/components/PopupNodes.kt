package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.config.ColorSetting
import dev.th7bo.sidequest.ui.config.DropdownSetting
import dev.th7bo.sidequest.ui.config.Option
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.core.layout.ColumnNode
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.derivedStateOf
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.input.CharTypedEvent
import dev.th7bo.sidequest.ui.input.EventPhase
import dev.th7bo.sidequest.ui.input.InputEvent
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.KeyDownEvent
import dev.th7bo.sidequest.ui.input.PointerDownEvent
import dev.th7bo.sidequest.ui.input.ScrollEvent
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.Corners
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import kotlin.math.max
import kotlin.math.min

/** One selectable line in a popup list. */
public class PopupItemNode(
    id: UiId,
    private val componentContext: ComponentContext,
    label: dev.th7bo.sidequest.ui.state.UiState<String>,
    private val isSelected: () -> Boolean,
    private val onChoose: () -> Unit,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens
    private val text = TextNode(id.child("label"), label, TextRole.LABEL)

    init {
        interactive = true
        focusable = true
        addChild(text)
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val labelSize = text.measure(constraints.loosen(), context)
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            labelSize.width + horizontalPadding * 2 + MARKER_COLUMN
        }
        return Size(width, tokens.metrics.controlHeight.value)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val labelSize = text.measuredSize
        text.arrange(
            Rect.of(
                Vec2(horizontalPadding + MARKER_COLUMN, (measuredSize.height - labelSize.height) / 2f),
                labelSize,
            ),
            context,
        )
    }

    override fun onInputEvent(event: InputEvent) {
        if (event.phase != EventPhase.TARGET) return
        when {
            event is PointerDownEvent -> {
                onChoose()
                event.consume()
            }
            event is KeyDownEvent && (event.key == Key.ENTER || event.key == Key.SPACE) -> {
                onChoose()
                event.consume()
            }
        }
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val selected = isSelected()

        if (selected || isHovered || isFocused) {
            renderer.roundedRect(
                bounds,
                Corners.all(tokens.radii.small),
                if (selected) palette.selectedBackground else palette.hoverBackground,
            )
            context.diagnostics.drawCalls++
        }

        if (selected) {
            // A dot as well as a tint: selection must not rely on colour alone.
            val dot = MARKER_SIZE
            renderer.roundedRect(
                Rect(
                    bounds.x + horizontalPadding + (MARKER_COLUMN - dot) / 2f,
                    bounds.y + (bounds.height - dot) / 2f,
                    dot,
                    dot,
                ),
                Corners.all(tokens.radii.pill),
                palette.accent,
            )
            context.diagnostics.drawCalls++
        }

        text.colorOverride = if (selected) palette.textPrimary else palette.textSecondary
    }

    private val horizontalPadding: Float get() = tokens.spacing.medium.value

    private companion object {
        const val MARKER_COLUMN = 10f
        const val MARKER_SIZE = 4f
    }
}

/** The surface a popup sits on: rounded, bordered, elevated. */
public abstract class PopupSurfaceNode(
    id: UiId,
    protected val componentContext: ComponentContext,
) : UiNode(id) {

    protected val tokens = componentContext.theme.tokens

    /** The stack of items. Subclasses fill it. */
    protected val body: ColumnNode = ColumnNode(id.child("body"), spacing = tokens.spacing.xs)

    init {
        interactive = true
        addChild(body)
    }

    /**
     * Clips the overflow.
     *
     * The body is taller than the surface once there are more items than fit, and the overflow has to be
     * hidden rather than drawn over whatever is behind the popup.
     */
    override val clipsChildren: Boolean get() = true

    /** How far the list is scrolled, in logical units. Zero when everything fits. */
    public var scrollOffset: Float = 0f
        private set

    /** The full height of the item stack, which may exceed what the surface shows. */
    private var contentHeight: Float = 0f

    /** How much of it is visible. */
    private var viewportHeight: Float = 0f

    /** True when there is more than fits, so the wheel does something and a bar is worth drawing. */
    public val isScrollable: Boolean get() = contentHeight > viewportHeight + 0.5f

    /** The furthest [scrollOffset] may go. */
    private val maxScroll: Float get() = max(0f, contentHeight - viewportHeight)

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        // Measured unbounded: the body's natural height is what decides whether there is anything to scroll,
        // and passing the constraint down would let the column shrink itself and hide the overflow instead.
        val bodySize = body.measure(
            Constraints(maxWidth = max(0f, constraints.maxWidth - padding * 2)),
            context,
        )
        contentHeight = bodySize.height

        val available = if (constraints.hasBoundedHeight) {
            max(0f, constraints.maxHeight - padding * 2)
        } else {
            MAX_CONTENT_HEIGHT
        }
        viewportHeight = min(bodySize.height, min(available, MAX_CONTENT_HEIGHT))

        // A list that shrank under the cursor must not leave the view past its end.
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll)

        val barWidth = if (isScrollable) SCROLLBAR_WIDTH + tokens.spacing.xs.value else 0f
        return Size(bodySize.width + padding * 2 + barWidth, viewportHeight + padding * 2)
    }

    override fun arrangeChildren(context: LayoutContext) {
        body.arrange(Rect.of(Vec2(padding, padding - scrollOffset), body.measuredSize), context)
    }

    /** Returns the view to the start. */
    public fun scrollToTop() {
        if (scrollOffset == 0f) return
        scrollOffset = 0f
        invalidateArrange()
    }

    /** Scrolls by [delta] logical units, clamped. Returns whether anything moved. */
    public fun scrollBy(delta: Float): Boolean {
        val next = (scrollOffset + delta).coerceIn(0f, maxScroll)
        if (next == scrollOffset) return false
        scrollOffset = next
        invalidateArrange()
        return true
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val corners = Corners.all(tokens.radii.medium)

        renderer.shadow(bounds, tokens.radii.medium, tokens.effects.overlayShadow)
        // Fully opaque, unlike the panels beneath it: a popup floats over arbitrary
        // content, and letting that content show through makes it hard to read.
        renderer.roundedRect(bounds, corners, palette.elevatedPanelBackground.withAlpha(1f))
        renderer.border(bounds, corners, tokens.metrics.borderWidth, palette.borderStrong)
        context.diagnostics.drawCalls += 3

        // Drawn with the surface rather than left to subclasses: without it a long list looks like a short
        // one that has simply lost its remaining options.
        paintScrollbar(renderer, bounds, context)
    }

    override fun onInputEvent(event: InputEvent) {
        // The popup absorbs presses that land on it, so they do not reach the overlay
        // root and dismiss the very popup being interacted with.
        if (event is PointerDownEvent && event.phase == EventPhase.TARGET) event.consume()

        if (event is ScrollEvent && (event.phase == EventPhase.TARGET || event.phase == EventPhase.BUBBLE)) {
            // Only consumed when it actually moved. A popup that swallowed the wheel at its ends would stop
            // the screen behind it scrolling for no visible reason.
            if (scrollBy(-event.scrollY * SCROLL_STEP)) event.consume()
        }
    }

    /** Draws the scroll indicator, if there is anything to indicate. Called after the subclass's content. */
    protected fun paintScrollbar(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        if (!isScrollable) return
        val palette = context.theme.tokens.colors

        val trackHeight = max(0f, bounds.height - padding * 2)
        val x = bounds.right - padding - SCROLLBAR_WIDTH
        // Proportional to how much is showing, with a floor so a very long list still has something to grab.
        val thumbHeight = max(MIN_THUMB_HEIGHT, trackHeight * (viewportHeight / contentHeight))
        val travel = max(0f, trackHeight - thumbHeight)
        val progress = if (maxScroll <= 0f) 0f else scrollOffset / maxScroll

        renderer.roundedRect(
            Rect(x, bounds.y + padding + travel * progress, SCROLLBAR_WIDTH, thumbHeight),
            tokens.radii.pill,
            palette.border,
        )
        context.diagnostics.drawCalls++
    }

    protected val padding: Float get() = tokens.spacing.small.value

    private companion object {
        /**
         * The tallest a popup gets before it scrolls.
         *
         * Roughly a dozen rows. The list of islands is forty, and a popup that grows to hold all of them
         * covers the screen it is a control on — which is what a screenshot showed.
         */
        const val MAX_CONTENT_HEIGHT = 180f

        /** Logical units per wheel notch. */
        const val SCROLL_STEP = 24f

        const val SCROLLBAR_WIDTH = 3f
        const val MIN_THUMB_HEIGHT = 12f
    }
}

/**
 * The expanded option list of a dropdown.
 *
 * Lives in the overlay layer rather than under the control, because a row inside a
 * clipping, scrolling list cannot draw outside itself.
 */
public class DropdownPopupNode<T>(
    id: UiId,
    /**
     * What the popup offers, whether it filters, and what counts as chosen.
     *
     * Taken as three values rather than as a `DropdownSetting`, because a multi-select needs the same list,
     * the same filter and the same keyboard handling while having no single selected value. Passing the
     * setting would have meant either a second near-identical popup or a fake setting to satisfy the type.
     */
    private val options: UiState<List<Option<T>>>,
    private val isSearchable: Boolean,
    private val isChosen: (Option<T>) -> Boolean,
    componentContext: ComponentContext,
    private val onChoose: (Option<T>) -> Unit,
) : PopupSurfaceNode(id, componentContext) {

    public constructor(
        id: UiId,
        setting: DropdownSetting<T>,
        componentContext: ComponentContext,
        onChoose: (Option<T>) -> Unit,
    ) : this(
        id = id,
        options = setting.options,
        isSearchable = setting.isSearchable,
        isChosen = { option -> setting.value == option.value },
        componentContext = componentContext,
        onChoose = onChoose,
    )

    /** Item nodes in option order, so the keyboard can walk them. */
    private val items = ArrayList<PopupItemNode>()

    private val queryState = mutableStateOf("", "${id.value}.filter")

    /** What the filter box currently holds. Empty when the popup is not searchable. */
    public val query: UiState<String> get() = queryState

    /** The options passing the current filter, in declaration order. */
    public var visibleOptions: List<Option<T>> = options.peek()
        private set

    private val filterLabel = if (isSearchable) {
        TextNode(
            id.child("filter"),
            derivedStateOf("${id.value}.filterLabel") {
                queryState.value.ifEmpty { "Type to filter…" }
            },
            TextRole.SECONDARY,
        )
    } else {
        null
    }

    init {
        // A searchable popup takes the keyboard, so typing goes to the filter rather
        // than to whatever had focus before the popup opened.
        focusable = isSearchable
        filterLabel?.let { body.addChild(it) }
        rebuildItems()
    }

    /**
     * Rebuilds the option list for the current filter.
     *
     * Matching is a case-insensitive substring over the option's label. Deliberately not
     * fuzzy: a dropdown filter that reorders results by score makes the list jump around
     * as you type, which is worse than a short list you can predict.
     */
    private fun rebuildItems() {
        for (item in items) {
            body.removeChild(item)
            item.dispose()
        }
        items.clear()

        val query = queryState.peek().trim()
        val matching = if (query.isEmpty()) {
            options.peek()
        } else {
            options.peek().filter { it.label.peek().contains(query, ignoreCase = true) }
        }
        visibleOptions = matching

        matching.forEachIndexed { index, option ->
            val item = PopupItemNode(
                id = id.child("item_$index"),
                componentContext = componentContext,
                label = option.label,
                isSelected = { isChosen(option) },
                onChoose = { onChoose(option) },
            )
            items.add(item)
            body.addChild(item)
        }

        // An empty result says so rather than showing a bare box, for the same reason the
        // settings list has an empty state: silence looks like a bug.
        if (matching.isEmpty()) {
            val none = PopupItemNode(
                id = id.child("no_match"),
                componentContext = componentContext,
                label = constantState("No matches"),
                isSelected = { false },
                onChoose = {},
            ).apply { interactive = false }
            items.add(none)
            body.addChild(none)
        }

        invalidateMeasure()
    }

    /** Sets the filter. Exposed so a test drives the same path a keystroke does. */
    public fun setQuery(value: String) {
        if (!isSearchable || queryState.peek() == value) return
        queryState.value = value
        // Back to the top. Filtering a scrolled list otherwise leaves the view past the end of the new,
        // shorter result — which looks exactly like a filter that matched nothing.
        scrollToTop()
        rebuildItems()
    }

    /** Number of options shown, after filtering. */
    public val itemCount: Int get() = visibleOptions.size

    /** The item for [index], for tests and for keyboard navigation. */
    public fun itemAt(index: Int): UiNode? = items.getOrNull(index)

    /** Index of the option currently selected within the visible list, or -1. */
    public fun selectedIndex(): Int = visibleOptions.indexOfFirst { isChosen(it) }

    override fun onInputEvent(event: InputEvent) {
        super.onInputEvent(event)
        if (!isSearchable) return

        when (event) {
            is CharTypedEvent -> {
                setQuery(queryState.peek() + event.char)
                event.consume()
            }

            is KeyDownEvent -> when (event.key) {
                Key.BACKSPACE -> {
                    setQuery(queryState.peek().dropLast(1))
                    event.consume()
                }
                // Enter takes the only remaining match, which is the whole point of
                // typing to filter.
                Key.ENTER -> {
                    visibleOptions.singleOrNull()?.let {
                        onChoose(it)
                        event.consume()
                    }
                }
                else -> Unit
            }

            else -> Unit
        }
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        // Wide enough for the longest option, so the list does not truncate what the
        // closed control already shows in full.
        val natural = super.measureSelf(Constraints(maxHeight = constraints.maxHeight), context)
        return Size(
            natural.width.coerceIn(MIN_WIDTH, constraints.maxWidth.coerceAtLeast(MIN_WIDTH)),
            natural.height.coerceAtMost(constraints.maxHeight),
        )
    }

    private companion object {
        const val MIN_WIDTH = 110f
    }
}

/**
 * A grid of colour swatches.
 *
 * Not a full HSV picker: the reference design offers presets, and a preset grid is the
 * part that is genuinely useful without a mouse. A continuous picker can be added on the
 * same overlay once there is a reason to.
 */
public class ColorPopupNode(
    id: UiId,
    private val setting: ColorSetting,
    componentContext: ComponentContext,
    private val onChoose: (Color) -> Unit,
) : PopupSurfaceNode(id, componentContext) {

    private val swatches = ArrayList<SwatchNode>()

    init {
        val presets = setting.presets.ifEmpty { DEFAULT_PRESETS }
        presets.forEachIndexed { index, color ->
            val swatch = SwatchNode(
                id = id.child("swatch_$index"),
                componentContext = componentContext,
                color = color,
                isSelected = { setting.value == color },
                onChoose = { onChoose(color) },
            )
            swatches.add(swatch)
            body.addChild(swatch)
        }
    }

    public val swatchCount: Int get() = swatches.size

    public fun swatchAt(index: Int): UiNode? = swatches.getOrNull(index)

    private class SwatchNode(
        id: UiId,
        componentContext: ComponentContext,
        private val color: Color,
        private val isSelected: () -> Boolean,
        private val onChoose: () -> Unit,
    ) : UiNode(id) {

        private val tokens = componentContext.theme.tokens
        private val label = TextNode(
            id.child("hex"),
            dev.th7bo.sidequest.ui.state.constantState(color.toHexString()),
            TextRole.MONO,
        )

        init {
            interactive = true
            focusable = true
            addChild(label)
        }

        override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
            val labelSize = label.measure(constraints.loosen(), context)
            return Size(
                SWATCH + tokens.spacing.medium.value + labelSize.width + tokens.spacing.medium.value * 2,
                tokens.metrics.compactControlHeight.value,
            )
        }

        override fun arrangeChildren(context: LayoutContext) {
            val labelSize = label.measuredSize
            label.arrange(
                Rect.of(
                    Vec2(
                        tokens.spacing.medium.value + SWATCH + tokens.spacing.medium.value,
                        (measuredSize.height - labelSize.height) / 2f,
                    ),
                    labelSize,
                ),
                context,
            )
        }

        override fun onInputEvent(event: InputEvent) {
            if (event.phase != EventPhase.TARGET) return
            when {
                event is PointerDownEvent -> {
                    onChoose()
                    event.consume()
                }
                event is KeyDownEvent && (event.key == Key.ENTER || event.key == Key.SPACE) -> {
                    onChoose()
                    event.consume()
                }
            }
        }

        override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
            val palette = context.theme.tokens.colors
            val selected = isSelected()

            if (selected || isHovered || isFocused) {
                renderer.roundedRect(
                    bounds,
                    Corners.all(tokens.radii.small),
                    if (selected) palette.selectedBackground else palette.hoverBackground,
                )
                context.diagnostics.drawCalls++
            }

            val swatch = Rect(
                bounds.x + tokens.spacing.medium.value,
                bounds.y + (bounds.height - SWATCH) / 2f,
                SWATCH,
                SWATCH,
            )
            renderer.roundedRect(swatch, Corners.all(tokens.radii.small), color)
            renderer.border(
                swatch,
                Corners.all(tokens.radii.small),
                tokens.metrics.borderWidth,
                if (selected) palette.accent else palette.borderStrong,
            )
            context.diagnostics.drawCalls += 2

            label.colorOverride = if (selected) palette.textPrimary else palette.textSecondary
        }

        private companion object {
            const val SWATCH = 10f
        }
    }

    private companion object {
        /** Used when a setting declares no presets, so the popup is never empty. */
        val DEFAULT_PRESETS = listOf(
            Color.parse("#8B5CF6"),
            Color.parse("#38BDF8"),
            Color.parse("#34D399"),
            Color.parse("#FBBF24"),
            Color.parse("#F87171"),
            Color.parse("#E8EAF2"),
        )
    }
}
