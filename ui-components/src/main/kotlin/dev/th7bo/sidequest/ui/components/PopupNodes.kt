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
import dev.th7bo.sidequest.ui.input.EventPhase
import dev.th7bo.sidequest.ui.input.InputEvent
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.KeyDownEvent
import dev.th7bo.sidequest.ui.input.PointerDownEvent
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.Corners
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import kotlin.math.max

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

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val bodySize = body.measure(
            Constraints(maxWidth = max(0f, constraints.maxWidth - padding * 2), maxHeight = constraints.maxHeight),
            context,
        )
        return Size(bodySize.width + padding * 2, bodySize.height + padding * 2)
    }

    override fun arrangeChildren(context: LayoutContext) {
        body.arrange(Rect.of(Vec2(padding, padding), body.measuredSize), context)
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
    }

    override fun onInputEvent(event: InputEvent) {
        // The popup absorbs presses that land on it, so they do not reach the overlay
        // root and dismiss the very popup being interacted with.
        if (event is PointerDownEvent && event.phase == EventPhase.TARGET) event.consume()
    }

    protected val padding: Float get() = tokens.spacing.small.value
}

/**
 * The expanded option list of a dropdown.
 *
 * Lives in the overlay layer rather than under the control, because a row inside a
 * clipping, scrolling list cannot draw outside itself.
 */
public class DropdownPopupNode<T>(
    id: UiId,
    private val setting: DropdownSetting<T>,
    componentContext: ComponentContext,
    private val onChoose: (Option<T>) -> Unit,
) : PopupSurfaceNode(id, componentContext) {

    /** Item nodes in option order, so the keyboard can walk them. */
    private val items = ArrayList<PopupItemNode>()

    init {
        val options = setting.options.peek()
        options.forEachIndexed { index, option ->
            val item = PopupItemNode(
                id = id.child("item_$index"),
                componentContext = componentContext,
                label = option.label,
                isSelected = { setting.value == option.value },
                onChoose = { onChoose(option) },
            )
            items.add(item)
            body.addChild(item)
        }
    }

    /** Number of options shown. */
    public val itemCount: Int get() = items.size

    /** The item for [index], for tests and for keyboard navigation. */
    public fun itemAt(index: Int): UiNode? = items.getOrNull(index)

    /** Index of the option currently selected, or -1. */
    public fun selectedIndex(): Int =
        setting.options.peek().indexOfFirst { it.value == setting.value }

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
