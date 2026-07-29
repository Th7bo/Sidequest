package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.config.ButtonSetting
import dev.th7bo.sidequest.ui.config.DropdownSetting
import dev.th7bo.sidequest.ui.config.FloatSliderSetting
import dev.th7bo.sidequest.ui.config.IntSliderSetting
import dev.th7bo.sidequest.ui.config.KeybindSetting
import dev.th7bo.sidequest.ui.config.Keybind
import dev.th7bo.sidequest.ui.config.Setting
import dev.th7bo.sidequest.ui.config.TextSetting
import dev.th7bo.sidequest.ui.config.ToggleSetting
import dev.th7bo.sidequest.ui.core.animation.AnimatedFloat
import dev.th7bo.sidequest.ui.core.animation.AnimationHost
import dev.th7bo.sidequest.ui.core.animation.Easing
import dev.th7bo.sidequest.ui.core.animation.HostedAnimation
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.input.EventPhase
import dev.th7bo.sidequest.ui.input.InputEvent
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.KeyDownEvent
import dev.th7bo.sidequest.ui.input.PointerDownEvent
import dev.th7bo.sidequest.ui.input.PointerDragEvent
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.mutableStateOf

/**
 * Shared behaviour for a control that edits one setting.
 *
 * Handles the things every control gets wrong independently otherwise: refusing input
 * while disabled, activating on Enter and Space as well as on click, and drawing a
 * focus ring.
 */
public abstract class ControlNode<T>(
    protected val setting: Setting<T>,
    protected val componentContext: ComponentContext,
    suffix: String,
) : UiNode(setting.id.child(suffix)) {

    protected val tokens = componentContext.theme.tokens

    init {
        interactive = true
        focusable = true
        setting.isEnabled.observe(scope) { invalidatePaint() }
    }

    /** False while the setting is disabled or locked by a permission. */
    protected val isEnabled: Boolean get() = setting.isEnabled.peek()

    /** Invoked by click and by Enter/Space. */
    protected abstract fun activate()

    override fun onInputEvent(event: InputEvent) {
        if (event.phase != EventPhase.TARGET) return
        if (!isEnabled) {
            // A disabled control still swallows the event, so a click does not fall
            // through to whatever is behind it.
            if (event is PointerDownEvent) event.consume()
            return
        }

        when {
            event is PointerDownEvent -> {
                activate()
                event.consume()
            }
            event is KeyDownEvent && (event.key == Key.ENTER || event.key == Key.SPACE) -> {
                activate()
                event.consume()
            }
        }
    }

    /** Draws the focus ring. Call from [paintSelf] after the control's own visuals. */
    protected fun paintFocusRing(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        if (!isFocused) return
        renderer.border(
            bounds.outset(dev.th7bo.sidequest.ui.geometry.Insets(2f, 2f, 2f, 2f)),
            tokens.radii.small,
            tokens.metrics.focusRingWidth,
            tokens.colors.focusRing,
        )
        context.diagnostics.drawCalls++
    }

    /** Content colour, dimmed when the control is disabled. */
    protected fun contentColor(base: Color): Color =
        if (isEnabled) base else tokens.colors.textDisabled
}

/**
 * An on/off switch.
 *
 * The knob's travel is animated, so a toggle flicked twice in quick succession glides
 * rather than snapping — the animation continues from wherever the knob currently is.
 */
public class ToggleControlNode(
    setting: ToggleSetting,
    context: ComponentContext,
) : ControlNode<Boolean>(setting, context, "toggle") {

    private val toggleSetting = setting

    private val knob = HostedAnimation(
        context.animations,
        AnimatedFloat(
            initial = if (setting.value) 1f else 0f,
            duration = context.theme.tokens.motion.fast,
            easing = Easing.EaseOut,
            debugName = "${setting.id.value}.knob",
        ),
    )

    init {
        setting.onChange(scope) { knob.target = if (it) 1f else 0f }
        knob.state.observe(scope) { invalidatePaint() }
    }

    /** Knob travel in `0..1`. Exposed so tests can assert the animation rather than guess. */
    public val knobPosition: Float get() = knob.value

    override fun activate() {
        toggleSetting.toggle()
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size =
        Size(TRACK_WIDTH, TRACK_HEIGHT)

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val on = toggleSetting.value

        val trackColor = when {
            !isEnabled -> palette.border
            on -> palette.accent
            else -> palette.borderStrong
        }
        renderer.roundedRect(bounds, tokens.radii.pill, trackColor)
        context.diagnostics.drawCalls++

        val travel = bounds.width - KNOB_DIAMETER - KNOB_INSET * 2
        val knobBounds = Rect(
            bounds.x + KNOB_INSET + travel * knob.value,
            bounds.y + KNOB_INSET,
            KNOB_DIAMETER,
            KNOB_DIAMETER,
        )
        renderer.roundedRect(
            knobBounds,
            tokens.radii.pill,
            if (isEnabled) palette.onAccent else palette.textDisabled,
        )
        context.diagnostics.drawCalls++

        paintFocusRing(renderer, bounds, context)
    }

    public companion object {
        public const val TRACK_WIDTH: Float = 26f
        public const val TRACK_HEIGHT: Float = 14f
        private const val KNOB_INSET = 2f
        private const val KNOB_DIAMETER = TRACK_HEIGHT - KNOB_INSET * 2
    }
}

/** A square check box. Same semantics as a toggle, different affordance. */
public class CheckboxControlNode(
    setting: ToggleSetting,
    context: ComponentContext,
) : ControlNode<Boolean>(setting, context, "checkbox") {

    private val toggleSetting = setting

    init {
        setting.onChange(scope) { invalidatePaint() }
    }

    override fun activate() {
        toggleSetting.toggle()
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size =
        Size(BOX_SIZE, BOX_SIZE)

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val checked = toggleSetting.value

        renderer.roundedRect(
            bounds,
            tokens.radii.small,
            if (checked && isEnabled) palette.accent else palette.panelBackground,
        )
        renderer.border(
            bounds,
            tokens.radii.small,
            tokens.metrics.borderWidth,
            if (isEnabled) palette.borderStrong else palette.border,
        )
        context.diagnostics.drawCalls += 2

        if (checked) {
            // A filled inner square rather than a glyph: the check must read at any
            // scale, and never relies on colour alone to signal state.
            val inset = BOX_SIZE * CHECK_INSET_FRACTION
            renderer.fillRect(
                Rect(bounds.x + inset, bounds.y + inset, bounds.width - inset * 2, bounds.height - inset * 2),
                if (isEnabled) palette.onAccent else palette.textDisabled,
            )
            context.diagnostics.drawCalls++
        }

        paintFocusRing(renderer, bounds, context)
    }

    private companion object {
        const val BOX_SIZE = 13f
        const val CHECK_INSET_FRACTION = 0.28f
    }
}

/** A pressable action. */
public class ButtonControlNode(
    setting: ButtonSetting,
    context: ComponentContext,
) : ControlNode<Int>(setting, context, "button") {

    private val buttonSetting = setting
    private val label = TextNode(setting.id.child("button_label"), setting.label, TextRole.LABEL)

    init {
        addChild(label)
    }

    override fun activate() {
        buttonSetting.invoke()
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val labelSize = label.measure(constraints.loosen(), context)
        return Size(
            labelSize.width + tokens.spacing.large.value * 2,
            maxOf(labelSize.height + tokens.spacing.small.value * 2, tokens.metrics.controlHeight.value),
        )
    }

    override fun arrangeChildren(context: LayoutContext) {
        val labelSize = label.measuredSize
        label.arrange(
            Rect.of(
                Vec2(
                    (measuredSize.width - labelSize.width) / 2f,
                    (measuredSize.height - labelSize.height) / 2f,
                ),
                labelSize,
            ),
            context,
        )
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val background = when {
            !isEnabled -> palette.panelBackground
            buttonSetting.isDestructive -> palette.error.withAlpha(DESTRUCTIVE_ALPHA)
            isHovered -> palette.accentHover
            else -> palette.accent
        }
        renderer.roundedRect(bounds, tokens.radii.small, background)
        context.diagnostics.drawCalls++

        label.colorOverride = contentColor(
            if (buttonSetting.isDestructive) palette.error else palette.onAccent,
        )
        paintFocusRing(renderer, bounds, context)
    }

    private companion object {
        const val DESTRUCTIVE_ALPHA = 0.18f
    }
}

/**
 * Shared slider behaviour for the integer and decimal variants.
 *
 * Dragging captures the pointer, so the knob keeps following the cursor once it leaves
 * the track — and arrow keys nudge by one step, which is what makes a slider usable
 * without a mouse at all.
 */
public abstract class SliderControlNode<T>(
    setting: Setting<T>,
    context: ComponentContext,
    /**
     * The formatted value shown beside the track.
     *
     * Passed in rather than produced by an overridable method: a base-class property
     * initializer runs before the subclass's fields exist, so calling down into the
     * subclass here would read an uninitialised value.
     */
    readoutText: UiState<String>,
) : ControlNode<T>(setting, context, "slider") {

    private val readout = TextNode(setting.id.child("readout"), readoutText, TextRole.SECONDARY)

    init {
        capturesPointer = true
        addChild(readout)
        setting.onChange(scope) { invalidatePaint() }
    }

    /** The 0..1 track position of the current value. */
    protected abstract fun currentFraction(): Float

    /** Writes the value corresponding to track position [fraction]. */
    protected abstract fun applyFraction(fraction: Float)

    /** Moves by [steps] increments, for keyboard nudging. */
    protected abstract fun nudge(steps: Int)

    /** Track position in `0..1`. Exposed for assertions. */
    public val fraction: Float get() = currentFraction()

    override fun activate() {
        // Sliders act on drag and on arrow keys; Enter is a no-op rather than a jump.
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val readoutSize = readout.measure(constraints.loosen(), context)
        return Size(
            TRACK_WIDTH + tokens.spacing.medium.value + readoutSize.width,
            maxOf(readoutSize.height, KNOB_DIAMETER),
        )
    }

    override fun arrangeChildren(context: LayoutContext) {
        val readoutSize = readout.measuredSize
        readout.arrange(
            Rect.of(
                Vec2(
                    TRACK_WIDTH + tokens.spacing.medium.value,
                    (measuredSize.height - readoutSize.height) / 2f,
                ),
                readoutSize,
            ),
            context,
        )
    }

    override fun onInputEvent(event: InputEvent) {
        if (event.phase != EventPhase.TARGET) return
        if (!isEnabled) return

        when (event) {
            is PointerDownEvent -> {
                applyFraction(fractionAt(event.position))
                event.consume()
            }
            is PointerDragEvent -> {
                applyFraction(fractionAt(event.position))
                event.consume()
            }
            is KeyDownEvent -> when (event.key) {
                Key.ARROW_LEFT, Key.ARROW_DOWN -> {
                    nudge(-1)
                    event.consume()
                }
                Key.ARROW_RIGHT, Key.ARROW_UP -> {
                    nudge(1)
                    event.consume()
                }
                Key.HOME -> {
                    applyFraction(0f)
                    event.consume()
                }
                Key.END -> {
                    applyFraction(1f)
                    event.consume()
                }
                else -> Unit
            }
            else -> Unit
        }
    }

    private fun fractionAt(localPosition: Vec2): Float =
        ((localPosition.x - KNOB_DIAMETER / 2f) / (TRACK_WIDTH - KNOB_DIAMETER)).coerceIn(0f, 1f)

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val centreY = bounds.y + bounds.height / 2f
        val trackBounds = Rect(bounds.x, centreY - TRACK_THICKNESS / 2f, TRACK_WIDTH, TRACK_THICKNESS)

        renderer.roundedRect(trackBounds, tokens.radii.pill, palette.border)
        context.diagnostics.drawCalls++

        val filled = (TRACK_WIDTH - KNOB_DIAMETER) * currentFraction() + KNOB_DIAMETER / 2f
        renderer.roundedRect(
            Rect(trackBounds.x, trackBounds.y, filled, TRACK_THICKNESS),
            tokens.radii.pill,
            if (isEnabled) palette.accent else palette.textDisabled,
        )
        context.diagnostics.drawCalls++

        val knobX = bounds.x + (TRACK_WIDTH - KNOB_DIAMETER) * currentFraction()
        renderer.roundedRect(
            Rect(knobX, centreY - KNOB_DIAMETER / 2f, KNOB_DIAMETER, KNOB_DIAMETER),
            tokens.radii.pill,
            if (isEnabled) palette.onAccent else palette.textDisabled,
        )
        context.diagnostics.drawCalls++

        paintFocusRing(renderer, trackBounds, context)
    }

    public companion object {
        public const val TRACK_WIDTH: Float = 90f
        internal const val TRACK_THICKNESS = 3f
        internal const val KNOB_DIAMETER = 9f
    }
}

/** Whole-number slider. */
public class IntSliderControlNode(
    private val slider: IntSliderSetting,
    context: ComponentContext,
) : SliderControlNode<Int>(slider, context, slider.formatted(slider.format)) {

    override fun currentFraction(): Float = slider.fractionOf(slider.value)

    override fun applyFraction(fraction: Float) {
        slider.setUnchecked(slider.valueAtFraction(fraction))
    }

    override fun nudge(steps: Int) {
        val next = (slider.value + steps * slider.step)
            .coerceIn(slider.range.first, slider.range.last)
        slider.setUnchecked(next)
    }
}

/** Fractional slider. */
public class FloatSliderControlNode(
    private val slider: FloatSliderSetting,
    context: ComponentContext,
) : SliderControlNode<Float>(slider, context, slider.formatted(slider.format)) {

    override fun currentFraction(): Float = slider.fractionOf(slider.value)

    override fun applyFraction(fraction: Float) {
        slider.setUnchecked(slider.valueAtFraction(fraction))
    }

    override fun nudge(steps: Int) {
        val increment = if (slider.step > 0f) slider.step else DEFAULT_NUDGE_FRACTION *
            (slider.range.endInclusive - slider.range.start)
        val next = (slider.value + steps * increment)
            .coerceIn(slider.range.start, slider.range.endInclusive)
        slider.setUnchecked(next)
    }

    private companion object {
        const val DEFAULT_NUDGE_FRACTION = 0.05f
    }
}

/**
 * A closed dropdown.
 *
 * The expanded list is a separate overlay owned by the screen rather than a child of
 * this node, because a popup has to escape the row's clip and paint above every
 * sibling. This node owns only the closed state and the open/close signal.
 */
public class DropdownControlNode<T>(
    private val dropdown: DropdownSetting<T>,
    context: ComponentContext,
) : ControlNode<T>(dropdown, context, "dropdown") {

    private val label = TextNode(
        dropdown.id.child("selected"),
        dropdown.state.let { state ->
            dev.th7bo.sidequest.ui.state.derivedStateOf("${dropdown.id.value}.label") {
                dropdown.options.value.firstOrNull { it.value == state.value }?.label?.value ?: "—"
            }
        },
        TextRole.LABEL,
    )

    /** True while the option list is showing. Owned here, rendered by the screen. */
    public var isOpen: Boolean = false
        private set

    /** Notified when the dropdown wants to open or close. */
    public var onOpenChanged: ((Boolean) -> Unit)? = null

    init {
        addChild(label)
        dropdown.onChange(scope) { invalidatePaint() }
    }

    override fun activate() {
        setOpen(!isOpen)
    }

    public fun setOpen(open: Boolean) {
        if (isOpen == open) return
        isOpen = open
        invalidatePaint()

        if (open) showPopup() else componentContext.overlays?.dismiss(overlayKey)
        onOpenChanged?.invoke(open)
    }

    private val overlayKey: Any get() = dropdown.id

    /**
     * Opens the option list in the overlay layer.
     *
     * With no overlay host the control stays usable: it still cycles by keyboard, and it
     * simply reports itself closed rather than pretending a list is showing.
     */
    private fun showPopup() {
        val host = componentContext.overlays
        if (host == null) {
            isOpen = false
            return
        }
        host.show(
            key = overlayKey,
            anchor = this,
            content = DropdownPopupNode(dropdown.id.child("popup"), dropdown, componentContext) { option ->
                dropdown.setUnchecked(option.value)
                setOpen(false)
            },
            // The control is right-aligned in its row, so the list lines up with its
            // right edge rather than hanging off into the label column.
            placement = dev.th7bo.sidequest.ui.core.overlay.OverlayPlacement.BELOW_END,
            onDismiss = {
                // Dismissal can come from outside — an outside click or Escape — so the
                // control learns its own state from the overlay, not the other way round.
                if (isOpen) {
                    isOpen = false
                    invalidatePaint()
                    onOpenChanged?.invoke(false)
                }
            },
        )
    }

    /** Selects [index] from the current option list and closes. */
    public fun select(index: Int): Boolean {
        val options = dropdown.options.peek()
        if (index !in options.indices) return false
        dropdown.setUnchecked(options[index].value)
        setOpen(false)
        return true
    }

    override fun onInputEvent(event: InputEvent) {
        if (event.phase == EventPhase.TARGET && isEnabled && event is KeyDownEvent) {
            when (event.key) {
                Key.ESCAPE -> if (isOpen) {
                    setOpen(false)
                    event.consume()
                    return
                }
                Key.ARROW_DOWN -> {
                    cycle(1)
                    event.consume()
                    return
                }
                Key.ARROW_UP -> {
                    cycle(-1)
                    event.consume()
                    return
                }
                else -> Unit
            }
        }
        super.onInputEvent(event)
    }

    /** Moves the selection without opening the list — the keyboard-only path. */
    private fun cycle(direction: Int) {
        val options = dropdown.options.peek()
        if (options.isEmpty()) return
        val current = options.indexOfFirst { it.value == dropdown.value }
        val next = ((if (current < 0) 0 else current) + direction + options.size) % options.size
        dropdown.setUnchecked(options[next].value)
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val labelSize = label.measure(
            Constraints(maxWidth = MAX_LABEL_WIDTH),
            context,
        )
        return Size(
            (labelSize.width + tokens.spacing.large.value * 2 + CARET_WIDTH)
                .coerceAtLeast(MIN_WIDTH),
            tokens.metrics.controlHeight.value,
        )
    }

    override fun arrangeChildren(context: LayoutContext) {
        val labelSize = label.measuredSize
        label.arrange(
            Rect.of(
                Vec2(tokens.spacing.medium.value, (measuredSize.height - labelSize.height) / 2f),
                labelSize,
            ),
            context,
        )
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors

        renderer.roundedRect(
            bounds,
            tokens.radii.small,
            if (isHovered && isEnabled) palette.hoverBackground else palette.panelBackground,
        )
        renderer.border(
            bounds,
            tokens.radii.small,
            tokens.metrics.borderWidth,
            if (isOpen) palette.accent else palette.border,
        )
        context.diagnostics.drawCalls += 2

        label.colorOverride = contentColor(palette.textPrimary)

        // A small filled wedge stands in for a caret glyph, so the affordance does not
        // depend on the host font having one.
        val caretSize = 4f
        val caretX = bounds.right - tokens.spacing.medium.value - caretSize
        val caretY = bounds.y + bounds.height / 2f - caretSize / 2f
        renderer.fillRect(
            Rect(caretX, caretY, caretSize, caretSize),
            contentColor(palette.textSecondary),
        )
        context.diagnostics.drawCalls++

        paintFocusRing(renderer, bounds, context)
    }

    private companion object {
        const val MIN_WIDTH = 96f
        const val MAX_LABEL_WIDTH = 140f
        const val CARET_WIDTH = 10f
    }
}

/**
 * A single-line text field.
 *
 * Editing state — the caret position and whether the field is being edited — is runtime
 * state held here, not in the configuration model.
 */
public class TextFieldControlNode(
    private val field: TextSetting,
    context: ComponentContext,
) : ControlNode<String>(field, context, "text_field") {

    private val display = TextNode(
        field.id.child("text"),
        dev.th7bo.sidequest.ui.state.derivedStateOf("${field.id.value}.display") {
            val value = field.state.value
            when {
                value.isEmpty() -> field.placeholder.value
                field.isMasked -> "•".repeat(value.length)
                else -> value
            }
        },
        TextRole.BODY,
    )

    private val editor = TextEditor(
        text = { field.value },
        maxLength = { field.maxLength },
        // Through `set`, so validation still runs and a refused value leaves the caret
        // where it was rather than sliding past a character that was never inserted.
        commit = { field.set(it).isValid },
        isMultiline = false,
    )

    /** Caret index within the value. Runtime state, never persisted. */
    public val caret: Int get() = editor.caret

    /** The current value, for assertions. Via [setting]: `field` is taken inside a getter. */
    public val text: String get() = setting.value

    public var isEditing: Boolean = false
        private set

    /** Caret offset from the text's left edge, measured during layout. */
    private var caretX: Float = 0f
    private var lineHeight: Float = 0f

    init {
        addChild(display)
        field.onChange(scope) {
            editor.clampToText()
            invalidateMeasure()
        }
    }

    override fun activate() {
        isEditing = true
        invalidatePaint()
    }

    /** Applies a text edit through the setting, so validation still runs. */
    public fun insert(text: String) {
        if (!isEnabled) return
        editor.insert(text)
        invalidateMeasure()
    }

    public fun backspace() {
        if (!isEnabled) return
        editor.backspace(word = false)
        invalidateMeasure()
    }

    public fun moveCaret(delta: Int) {
        editor.moveTo(editor.caret + delta)
        invalidateMeasure()
    }

    override fun onInputEvent(event: InputEvent) {
        if (event.phase == EventPhase.TARGET && isEnabled) {
            when {
                event is dev.th7bo.sidequest.ui.input.CharTypedEvent -> {
                    insert(event.char.toString())
                    event.consume()
                    return
                }
                event is KeyDownEvent && event.key == Key.ESCAPE && isEditing -> {
                    isEditing = false
                    event.consume()
                    return
                }
                // Everything else navigational or deleting — including Ctrl+arrow,
                // Ctrl+Backspace, Delete, Home and End — is the shared editor's job.
                event is KeyDownEvent && editor.handleKey(event.key, event.modifiers) -> {
                    invalidateMeasure()
                    event.consume()
                    return
                }
            }
        }
        super.onInputEvent(event)
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val inner = FIELD_WIDTH - tokens.spacing.medium.value * 2
        display.measure(Constraints(maxWidth = inner), context)

        val style = context.theme.textStyle(TextRole.BODY)
        lineHeight = context.textMeasurer.lineHeight(style)
        caretX = caretOffset(context, style, inner)

        return Size(FIELD_WIDTH, tokens.metrics.controlHeight.value)
    }

    /**
     * Where to draw the caret.
     *
     * Measured against what is *shown*, so the caret stays under the dots of a masked
     * field instead of tracking the hidden characters, whose widths differ.
     */
    private fun caretOffset(
        context: LayoutContext,
        style: dev.th7bo.sidequest.ui.rendering.TextStyle,
        innerWidth: Float,
    ): Float {
        val caret = editor.caret
        if (caret <= 0 || field.value.isEmpty()) return 0f
        val shown = if (field.isMasked) "•".repeat(caret) else field.value.take(caret)
        val width = context.textMeasurer
            .measure(shown, style, null, 1, dev.th7bo.sidequest.ui.rendering.TextOverflow.CLIP)
            .size.width
        return width.coerceAtMost(innerWidth)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val textSize = display.measuredSize
        display.arrange(
            Rect.of(
                Vec2(tokens.spacing.medium.value, (measuredSize.height - textSize.height) / 2f),
                textSize,
            ),
            context,
        )
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val hasError = field.validation.peek().errors.isNotEmpty()

        renderer.roundedRect(bounds, tokens.radii.small, palette.panelBackground)
        renderer.border(
            bounds,
            tokens.radii.small,
            tokens.metrics.borderWidth,
            when {
                hasError -> palette.error
                isFocused -> palette.accent
                else -> palette.border
            },
        )
        context.diagnostics.drawCalls += 2

        display.colorOverride = when {
            !isEnabled -> palette.textDisabled
            field.value.isEmpty() -> palette.textDisabled
            else -> palette.textPrimary
        }

        if (!isEditing || !isEnabled) return

        // Solid, not blinking: a blinking caret means the screen never reaches an idle
        // frame, and that guarantee is worth more than the animation.
        renderer.fillRect(
            Rect(
                bounds.x + tokens.spacing.medium.value + caretX,
                bounds.y + (bounds.height - lineHeight) / 2f,
                tokens.metrics.focusRingWidth.value,
                lineHeight,
            ),
            palette.accent,
        )
        context.diagnostics.drawCalls++
    }

    private companion object {
        const val FIELD_WIDTH = 140f
    }
}

/**
 * Captures a key combination.
 *
 * While capturing, the next key press becomes the binding rather than being routed
 * normally — which is why this control has to consume keys ahead of the usual handling.
 */
public class KeybindControlNode(
    private val keybindSetting: KeybindSetting,
    context: ComponentContext,
) : ControlNode<Keybind>(keybindSetting, context, "keybind") {

    // Reactive, not a plain flag: the label below is derived from it, and a derived state
    // can only recompute for dependencies it can observe. As a `var` this read silently
    // did nothing, so the label kept whatever text it had until something else rebuilt
    // the control — which is exactly how the bug presented, the binding updating only
    // after a category switch.
    private val capturingState: MutableUiState<Boolean> =
        mutableStateOf(false, "${keybindSetting.id.value}.capturing")

    private val label = TextNode(
        keybindSetting.id.child("binding"),
        dev.th7bo.sidequest.ui.state.derivedStateOf("${keybindSetting.id.value}.binding") {
            if (capturingState.value) "Press a key…" else keybindSetting.state.value.toString()
        },
        TextRole.LABEL,
    )

    public val isCapturing: Boolean get() = capturingState.value

    /** What the control currently reads — the rendered text, not the bound value. */
    public val bindingLabel: String get() = label.text.value

    init {
        addChild(label)
        keybindSetting.onChange(scope) { invalidatePaint() }
        capturingState.observe(scope) { invalidatePaint() }
    }

    override fun activate() {
        capturingState.value = true
    }

    override fun onInputEvent(event: InputEvent) {
        if (isCapturing && event.phase == EventPhase.TARGET && event is KeyDownEvent) {
            event.consume()
            when {
                event.key == Key.ESCAPE -> capturingState.value = false
                event.key.isModifier && !keybindSetting.allowModifierOnly -> return
                else -> {
                    // Cleared first: while capturing is still set the derived label reads
                    // the "Press a key…" branch and never touches the binding state, so a
                    // notification delivered mid-write would drop the dependency on it.
                    capturingState.value = false
                    keybindSetting.setUnchecked(Keybind(event.key, event.modifiers))
                }
            }
            return
        }
        super.onInputEvent(event)
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        label.measure(constraints.loosen(), context)
        return Size(BINDING_WIDTH, tokens.metrics.controlHeight.value)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val labelSize = label.measuredSize
        label.arrange(
            Rect.of(
                Vec2(
                    (measuredSize.width - labelSize.width) / 2f,
                    (measuredSize.height - labelSize.height) / 2f,
                ),
                labelSize,
            ),
            context,
        )
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        renderer.roundedRect(bounds, tokens.radii.small, palette.panelBackground)
        renderer.border(
            bounds,
            tokens.radii.small,
            tokens.metrics.borderWidth,
            if (isCapturing) palette.accent else palette.border,
        )
        context.diagnostics.drawCalls += 2
        label.colorOverride = contentColor(palette.textPrimary)
        paintFocusRing(renderer, bounds, context)
    }

    private companion object {
        const val BINDING_WIDTH = 110f
    }
}

/** A read-only progress bar, for HUD composition and long-running actions. */
public class ProgressBarNode(
    id: dev.th7bo.sidequest.ui.ids.UiId,
    private val progress: UiState<Float>,
    private val width: Float = DEFAULT_WIDTH,
) : UiNode(id) {

    init {
        progress.observe(scope) { invalidatePaint() }
    }

    // Honours a tight width so a bar can be stretched to its siblings by a layout that
    // sets `fillCrossAxis`; otherwise it keeps its intrinsic track width.
    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size =
        Size(width.coerceAtLeast(constraints.minWidth).coerceAtMost(constraints.maxWidth), HEIGHT)

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val radii = context.theme.tokens.radii

        renderer.roundedRect(bounds, radii.pill, palette.border)
        val filled = bounds.width * progress.peek().coerceIn(0f, 1f)
        if (filled > 0f) {
            renderer.roundedRect(Rect(bounds.x, bounds.y, filled, bounds.height), radii.pill, palette.accent)
        }
        context.diagnostics.drawCalls += 2
    }

    public companion object {
        public const val DEFAULT_WIDTH: Float = 120f
        public const val HEIGHT: Float = 3f
    }
}
