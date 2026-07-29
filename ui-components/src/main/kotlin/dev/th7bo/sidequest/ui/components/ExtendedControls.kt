package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.config.ListSetting
import dev.th7bo.sidequest.ui.config.TextAreaSetting
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
import dev.th7bo.sidequest.ui.input.CharTypedEvent
import dev.th7bo.sidequest.ui.input.EventPhase
import dev.th7bo.sidequest.ui.input.InputEvent
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.KeyDownEvent
import dev.th7bo.sidequest.ui.input.PointerDownEvent
import dev.th7bo.sidequest.ui.rendering.Corners
import dev.th7bo.sidequest.ui.rendering.TextOverflow
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.TextStyle
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.derivedStateOf
import kotlin.math.max

/**
 * A multi-line text field.
 *
 * Separate from [TextFieldControlNode] rather than a flag on it: a text area wraps, has a
 * caret that moves vertically, and sizes to a line count rather than to one line. Those
 * are different enough that sharing the implementation would mean branching on the flag
 * in every method.
 */
public class TextAreaControlNode(
    private val area: TextAreaSetting,
    context: ComponentContext,
) : ControlNode<String>(area, context, "text_area") {

    /** Caret index within the value. Runtime state, never persisted. */
    public var caret: Int = area.value.length
        private set

    public var isEditing: Boolean = false
        private set

    private val display = TextNode(
        area.id.child("text"),
        derivedStateOf("${area.id.value}.display") {
            area.state.value.ifEmpty { area.placeholder.value }
        },
        TextRole.BODY,
    ).apply {
        maxLines = area.visibleLines
        // Wrapping, not ellipsis: a line too long for the box belongs on the next line in
        // a text area. Overflow past the declared line count is still truncated.
        overflow = TextOverflow.WRAP
    }

    init {
        addChild(display)
        area.onChange(scope) {
            caret = caret.coerceAtMost(it.length)
            invalidatePaint()
        }
    }

    /** Line count of the current value, for assertions. */
    public val lineCount: Int get() = area.value.count { it == '\n' } + 1

    /** The current value, for assertions. */
    public val text: String get() = area.value

    override fun activate() {
        isEditing = !isEditing
        invalidatePaint()
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val width = constraints.maxWidth.coerceAtMost(MAX_WIDTH)
        display.measure(Constraints(maxWidth = width - padding * 2, maxHeight = constraints.maxHeight), context)

        // Sized to the declared visible line count rather than to the content, so the
        // row's height does not jump around as the value is typed.
        val lineHeight = context.textMeasurer.lineHeight(TextStyle.Default)
        return Size(width, lineHeight * area.visibleLines + padding * 2)
    }

    override fun arrangeChildren(context: LayoutContext) {
        display.arrange(Rect.of(Vec2(padding, padding), display.measuredSize), context)
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val corners = Corners.all(tokens.radii.small)

        renderer.roundedRect(bounds, corners, palette.hoverBackground)
        renderer.border(
            bounds,
            corners,
            if (isEditing) tokens.metrics.focusRingWidth else tokens.metrics.borderWidth,
            if (isEditing) palette.accent else palette.border,
        )
        context.diagnostics.drawCalls += 2
    }

    override fun onInputEvent(event: InputEvent) {
        // While editing, the area handles keys *before* the base control does. The base
        // class treats Enter and Space as "activate", which for a text area would toggle
        // editing off instead of inserting — so the order here is the difference between
        // a working text area and one you cannot type a newline into.
        if (!isEditing) {
            super.onInputEvent(event)
            return
        }

        when (event) {
            is CharTypedEvent -> {
                insert(event.char.toString())
                event.consume()
            }

            is KeyDownEvent -> when (event.key) {
                // The one thing a text area has that a text field does not.
                Key.ENTER -> {
                    insert("\n")
                    event.consume()
                }
                Key.BACKSPACE -> {
                    if (caret > 0) {
                        val next = StringBuilder(area.value).deleteCharAt(caret - 1).toString()
                        caret--
                        area.setUnchecked(next)
                    }
                    event.consume()
                }
                Key.ESCAPE -> {
                    isEditing = false
                    event.consume()
                }
                else -> Unit
            }

            else -> super.onInputEvent(event)
        }
    }

    private fun insert(text: String) {
        val current = area.value
        if (current.length + text.length > area.maxLength) return
        val next = StringBuilder(current).insert(caret, text).toString()
        caret += text.length
        area.setUnchecked(next)
    }

    private val padding: Float get() = tokens.spacing.small.value

    private companion object {
        const val MAX_WIDTH = 220f
    }
}

/**
 * An editable, optionally reorderable list.
 *
 * Rows are rebuilt when the value changes rather than diffed. A settings list is short by
 * nature — `maxItems` exists precisely to keep it so — and a diff would buy nothing over
 * rebuilding a handful of rows while costing the identity bugs that come with it.
 */
public class ListControlNode<T>(
    private val list: ListSetting<T>,
    context: ComponentContext,
) : ControlNode<List<T>>(list, context, "list") {

    // The list itself has no single activation: each row owns its own buttons, and a
    // click on the list background should do nothing rather than guess.
    override fun activate(): Unit = Unit

    private val rows = ColumnNode(list.id.child("rows"), spacing = tokens.spacing.xs)

    init {
        addChild(rows)
        list.onChange(scope) { rebuild() }
        rebuild()
    }

    /** Number of rows currently built. */
    public val rowCount: Int get() = rows.children.size - if (list.createItem != null) 1 else 0

    private fun rebuild() {
        rows.clearChildren()
        val items = list.value

        items.forEachIndexed { index, item ->
            rows.addChild(EntryNode(index, item))
        }

        // The add button is a row of the list rather than a separate control, so the
        // whole thing occupies one setting row and grows downwards predictably.
        list.createItem?.let { create ->
            rows.addChild(
                ActionNode(
                    list.id.child("add"),
                    if (items.size >= list.maxItems) "Full" else "+ Add",
                    isEnabled = items.size < list.maxItems,
                ) { list.add(create()) },
            )
        }
        invalidateMeasure()
    }

    /** One entry: its label plus the buttons that act on it. */
    private inner class EntryNode(
        private val index: Int,
        item: T,
    ) : UiNode(list.id.child("entry_$index")) {

        private val label = TextNode(
            list.id.child("entry_label_$index"),
            constantState(list.itemLabel(item)),
            TextRole.BODY,
        )

        /** The buttons, kept together so they can be placed as one block at the end. */
        private val actions = RowNode(list.id.child("entry_actions_$index"), spacing = tokens.spacing.xs).apply {
            verticalAlignment = VerticalAlignment.CENTER

            if (list.isReorderable) {
                // Disabled at the ends rather than hidden, so the buttons do not shift
                // sideways as an entry moves through the list.
                addChild(
                    ActionNode(list.id.child("up_$index"), "↑", isEnabled = index > 0) {
                        list.move(index, index - 1)
                    },
                )
                addChild(
                    ActionNode(
                        list.id.child("down_$index"),
                        "↓",
                        isEnabled = index < list.value.lastIndex,
                    ) { list.move(index, index + 1) },
                )
            }
            addChild(
                ActionNode(list.id.child("remove_$index"), "×", isDestructive = true) {
                    list.removeAt(index)
                },
            )
        }

        init {
            interactive = true
            addChildren(label, actions)
        }

        // Placed explicitly rather than with a weighted spacer: the buttons must sit at
        // the trailing edge of every row, and the label must be told how much room is
        // left so a long entry ellipsizes instead of pushing them off the end.
        override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
            val actionsSize = actions.measure(constraints.loosen(), context)
            val gap = tokens.spacing.small.value
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Float.POSITIVE_INFINITY

            val labelSize = label.measure(
                Constraints(
                    maxWidth = max(0f, width - actionsSize.width - gap),
                    maxHeight = constraints.maxHeight,
                ),
                context,
            )

            return Size(
                if (width.isFinite()) width else labelSize.width + gap + actionsSize.width,
                max(labelSize.height, actionsSize.height),
            )
        }

        override fun arrangeChildren(context: LayoutContext) {
            val labelSize = label.measuredSize
            val actionsSize = actions.measuredSize
            label.arrange(
                Rect.of(Vec2(0f, (measuredSize.height - labelSize.height) / 2f), labelSize),
                context,
            )
            actions.arrange(
                Rect.of(
                    Vec2(
                        measuredSize.width - actionsSize.width,
                        (measuredSize.height - actionsSize.height) / 2f,
                    ),
                    actionsSize,
                ),
                context,
            )
        }

        override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
            if (!isHovered) return
            renderer.roundedRect(bounds, Corners.all(tokens.radii.small), context.theme.tokens.colors.hoverBackground)
            context.diagnostics.drawCalls++
        }
    }

    /** A compact button used inside a list row. */
    private inner class ActionNode(
        id: UiId,
        label: String,
        private val isEnabled: Boolean = true,
        private val isDestructive: Boolean = false,
        private val onActivate: () -> Unit,
    ) : UiNode(id) {

        private val text = TextNode(id.child("label"), constantState(label), TextRole.LABEL)

        init {
            interactive = isEnabled
            addChild(text)
        }

        override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
            val size = text.measure(constraints.loosen(), context)
            return Size(
                (size.width + tokens.spacing.small.value * 2f).coerceAtLeast(MIN_ACTION_WIDTH),
                size.height + tokens.spacing.xs.value * 2f,
            )
        }

        override fun arrangeChildren(context: LayoutContext) {
            val size = text.measuredSize
            text.arrange(
                Rect.of(
                    Vec2((measuredSize.width - size.width) / 2f, (measuredSize.height - size.height) / 2f),
                    size,
                ),
                context,
            )
        }

        override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
            val palette = context.theme.tokens.colors
            val corners = Corners.all(tokens.radii.small)
            val background = when {
                !isEnabled -> palette.hoverBackground
                isHovered && isDestructive -> DESTRUCTIVE
                isHovered -> palette.accent
                else -> palette.hoverBackground
            }
            renderer.roundedRect(bounds, corners, background)
            renderer.border(bounds, corners, tokens.metrics.borderWidth, palette.border)
            context.diagnostics.drawCalls += 2
        }

        override fun onInputEvent(event: InputEvent) {
            if (!isEnabled) return
            if (event is PointerDownEvent && event.phase == EventPhase.TARGET) {
                onActivate()
                event.consume()
            }
        }
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        // The setting row measures its control with an unbounded main axis, so passing
        // the constraints straight through would leave every entry row sized to its
        // content and the weighted spacer with nothing to distribute — which is why the
        // ↑/↓/× buttons used to sit against the label instead of at the trailing edge.
        // Committing to a width here is what gives the spacer something to push against.
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth.coerceAtMost(PREFERRED_WIDTH)
        } else {
            PREFERRED_WIDTH
        }
        val size = rows.measure(Constraints(width, width, 0f, constraints.maxHeight), context)
        return Size(width, size.height)
    }

    override fun arrangeChildren(context: LayoutContext) {
        rows.arrange(Rect.of(Vec2.Zero, rows.measuredSize), context)
    }

    private companion object {
        const val MIN_ACTION_WIDTH = 14f

        /** Matches the text area, so multi-line controls line up down the screen. */
        const val PREFERRED_WIDTH = 220f
        val DESTRUCTIVE = dev.th7bo.sidequest.ui.rendering.Color.parse("#FFF87171")
    }
}
