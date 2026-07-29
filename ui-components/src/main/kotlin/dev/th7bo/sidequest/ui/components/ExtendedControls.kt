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
import dev.th7bo.sidequest.ui.rendering.TextMeasurer
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

    private val editor = TextEditor(
        text = { area.value },
        maxLength = { area.maxLength },
        commit = { area.setUnchecked(it); true },
        isMultiline = true,
    )

    /** Caret index within the value. Runtime state, never persisted. */
    public val caret: Int get() = editor.caret

    public var isEditing: Boolean = false
        private set

    private val display = TextNode(
        area.id.child("text"),
        derivedStateOf("${area.id.value}.display") {
            area.state.value.ifEmpty { area.placeholder.value }
        },
        TextRole.BODY,
    ).apply {
        // Every line is laid out, not just the visible ones; the control clips and scrolls
        // to keep the caret's line on screen. Limiting the layout here instead would make
        // a caret past the fold impossible to place.
        maxLines = MAX_LAID_OUT_LINES
        // Wrapping, not ellipsis: a line too long for the box belongs on the next line.
        overflow = TextOverflow.WRAP
    }

    // -- caret geometry, computed during layout ------------------------------

    /** Visual line the caret sits on, counting wrapped lines separately. */
    private var caretLine: Int = 0

    /** Caret offset from the left edge of the text, in logical units. */
    private var caretX: Float = 0f

    private var lineHeight: Float = 0f

    /** First visual line drawn. Advances so the caret stays inside the box. */
    private var scrollLine: Int = 0

    /** Start offset of each visual line, so a click can be turned into a caret index. */
    private var lineStarts: List<Int> = listOf(0)

    /** End offset of each visual line, excluding the separator the wrapper consumed. */
    private var lineEnds: List<Int> = listOf(0)

    /**
     * The measurer from the last layout pass.
     *
     * Held because placing the caret from a click needs to measure text, and input is
     * dispatched outside a layout pass where no [LayoutContext] exists. A node that has
     * never been laid out cannot be clicked, so this is set by the time it is read.
     */
    private var measurer: TextMeasurer? = null

    /**
     * The window the text scrolls behind.
     *
     * A separate node because the clip has to be the *inner* rectangle: clipping to the
     * control's own bounds instead lets the first line past the fold show through the
     * bottom padding, which looks like a rendering glitch rather than a scroll position.
     */
    private val window = ClippedWindowNode(area.id.child("window")).apply { addChild(display) }

    init {
        addChild(window)
        area.onChange(scope) {
            editor.clampToText()
            invalidateMeasure()
        }
    }

    /** Line count of the current value, for assertions. */
    public val lineCount: Int get() = TextEditing.lineCountOf(area.value)

    /** The current value, for assertions. */
    public val text: String get() = area.value

    /** First visual line on screen. Exposed so scrolling can be asserted. */
    public val firstVisibleLine: Int get() = scrollLine

    override fun activate() {
        isEditing = !isEditing
        invalidateMeasure()
    }

    override fun onFocusLost() {
        if (!isEditing) return
        isEditing = false
        invalidatePaint()
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        measurer = context.textMeasurer

        val style = context.theme.textStyle(TextRole.BODY)
        val width = constraints.maxWidth.coerceAtMost(MAX_WIDTH)
        val inner = width - padding * 2

        lineHeight = context.textMeasurer.lineHeight(style)
        val innerHeight = lineHeight * area.visibleLines

        window.measure(Constraints(inner, inner, innerHeight, innerHeight), context)
        updateCaretGeometry(context.textMeasurer, style, inner)

        // Scrolled by translating the whole text block behind the window, rather than by
        // laying out a slice of lines: one layout, and the caret's coordinates stay in the
        // same space as the text's.
        window.contentOffset = Vec2(0f, -scrollLine * lineHeight)

        // Sized to the declared visible line count rather than to the content, so the
        // row's height does not jump around as the value is typed.
        return Size(width, innerHeight + padding * 2)
    }

    override fun arrangeChildren(context: LayoutContext) {
        window.arrange(Rect.of(Vec2(padding, padding), window.measuredSize), context)
    }

    /**
     * Locates the caret in the wrapped layout and scrolls it into view.
     *
     * Offsets are recovered from the layout's own lines rather than by re-wrapping the
     * prefix: the wrapper drops the separator it broke on, so consuming each line's
     * content and then one separator reproduces exactly where each visual line starts.
     * Measuring the prefix instead would disagree with the real layout whenever the word
     * under the caret happens to fit on the previous line on its own.
     */
    private fun updateCaretGeometry(measurer: TextMeasurer, style: TextStyle, innerWidth: Float) {
        val value = area.value
        val layout = measurer.measure(value, style, innerWidth, MAX_LAID_OUT_LINES, TextOverflow.WRAP)

        val starts = ArrayList<Int>(layout.lines.size)
        val ends = ArrayList<Int>(layout.lines.size)
        var offset = 0
        for (line in layout.lines) {
            starts.add(offset)
            offset += line.content.length
            // The end of the *text* on this line, before the separator the wrapper
            // swallowed. A click past the last glyph belongs here, not after the newline.
            ends.add(offset)
            if (offset < value.length && (value[offset] == ' ' || value[offset] == '\n')) offset++
        }
        if (starts.isEmpty()) {
            starts.add(0)
            ends.add(0)
        }
        lineStarts = starts
        lineEnds = ends

        val caret = editor.caret
        var index = starts.indexOfLast { it <= caret }
        if (index < 0) index = 0
        caretLine = index

        val lineStart = starts[index]
        caretX = if (caret > lineStart) {
            measurer.measure(value.substring(lineStart, caret), style, null, 1, TextOverflow.CLIP)
                .size.width
                .coerceAtMost(innerWidth)
        } else {
            0f
        }

        // Keep the caret's line inside the window, and never scroll past the last line.
        val lastPossible = max(0, starts.size - area.visibleLines)
        scrollLine = scrollLine
            .coerceAtMost(caretLine)
            .coerceAtLeast(caretLine - area.visibleLines + 1)
            .coerceIn(0, lastPossible)
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

        if (!isEditing) return

        // Solid, not blinking. A blink would mean the screen never reaches an idle frame,
        // and the idle-frame guarantee is worth more than the animation.
        val row = caretLine - scrollLine
        if (row < 0 || row >= area.visibleLines) return
        renderer.fillRect(
            Rect(
                bounds.x + padding + caretX,
                bounds.y + padding + row * lineHeight,
                tokens.metrics.focusRingWidth.value,
                lineHeight,
            ),
            palette.accent,
        )
        context.diagnostics.drawCalls++
    }

    override fun onInputEvent(event: InputEvent) {
        // While editing, the area handles keys *before* the base control does. The base
        // class treats Enter and Space as "activate", which for a text area would toggle
        // editing off instead of inserting — so the order here is the difference between
        // a working text area and one you cannot type a newline into.
        if (!isEditing) {
            if (event is PointerDownEvent && event.phase == EventPhase.TARGET && isEnabled) {
                // Start editing *and* land the caret where the click was, rather than
                // dropping it at the end and making the user arrow back.
                placeCaretAt(event.position)
            }
            super.onInputEvent(event)
            return
        }

        when (event) {
            is CharTypedEvent -> {
                editor.insert(event.char.toString())
                invalidateMeasure()
                event.consume()
            }

            is PointerDownEvent -> {
                if (event.phase == EventPhase.TARGET) {
                    placeCaretAt(event.position)
                    invalidateMeasure()
                    event.consume()
                }
            }

            is KeyDownEvent -> when {
                // The one thing a text area has that a text field does not.
                event.key == Key.ENTER -> {
                    editor.insert("\n")
                    invalidateMeasure()
                    event.consume()
                }

                event.key == Key.ESCAPE -> {
                    isEditing = false
                    invalidatePaint()
                    event.consume()
                }

                editor.handleKey(event.key, event.modifiers) -> {
                    invalidateMeasure()
                    event.consume()
                }

                else -> Unit
            }

            else -> super.onInputEvent(event)
        }
    }

    /** Turns a click in this control's local space into a caret offset. */
    private fun placeCaretAt(local: Vec2) {
        val measurer = this.measurer ?: return
        val value = area.value
        if (value.isEmpty()) {
            editor.moveTo(0)
            return
        }

        val row = ((local.y - padding) / lineHeight).toInt().coerceAtLeast(0)
        val line = (scrollLine + row).coerceIn(0, lineStarts.lastIndex)
        val start = lineStarts[line]
        val end = lineEnds[line]

        // Walk the line one character at a time and stop at the gap nearest the click.
        // Lines here are at most a couple of hundred logical units wide and the measurer
        // caches, so a scan is cheaper than the machinery a binary search would need.
        val style = componentContext.theme.textStyle(TextRole.BODY)
        val targetX = local.x - padding
        var best = start
        var bestDistance = Float.MAX_VALUE
        for (offset in start..end) {
            val width = if (offset > start) {
                measurer.measure(value.substring(start, offset), style, null, 1, TextOverflow.CLIP).size.width
            } else {
                0f
            }
            val distance = kotlin.math.abs(width - targetX)
            if (distance < bestDistance) {
                bestDistance = distance
                best = offset
            }
        }
        editor.moveTo(best.coerceAtMost(value.length))
    }

    private val padding: Float get() = tokens.spacing.small.value

    /**
     * A fixed-size hole its child is drawn through, at an offset the parent controls.
     *
     * Exactly the size it is given, so the clip is the text box's interior rather than
     * anything the child measured, and the child keeps its full height so lines scrolled
     * out of view are still laid out and can be scrolled back to.
     */
    private class ClippedWindowNode(id: UiId) : UiNode(id) {

        override val clipsChildren: Boolean get() = true

        /** Where the content sits relative to the window's top-left. */
        var contentOffset: Vec2 = Vec2.Zero
            set(value) {
                if (field == value) return
                field = value
                invalidateArrange()
            }

        override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
            children.firstOrNull()?.measure(Constraints(maxWidth = constraints.maxWidth), context)
            return Size(constraints.maxWidth, constraints.maxHeight)
        }

        override fun arrangeChildren(context: LayoutContext) {
            val child = children.firstOrNull() ?: return
            child.arrange(Rect.of(contentOffset, child.measuredSize), context)
        }
    }

    private companion object {
        const val MAX_WIDTH = 220f

        /**
         * Cap on laid-out lines. Generous enough that no realistic setting reaches it, and
         * finite so a pathological value cannot lay out unboundedly.
         */
        const val MAX_LAID_OUT_LINES = 512
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
