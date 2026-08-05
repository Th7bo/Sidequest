package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.content.TextNode
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
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.constantState

/** How a [ChromeButtonNode] is styled. */
public enum class ButtonTone {
    /** Filled with the accent — the primary action. */
    PRIMARY,

    /** Outlined, for secondary actions. */
    NEUTRAL,

    /** Outlined and tinted with the error colour. */
    DANGER,
}

/**
 * A standalone button for screen chrome — Save & Close, Reset All, the close cross.
 *
 * Distinct from `ButtonControlNode`, which presents a `ButtonSetting` inside a row.
 * This one is not backed by a setting because the screen's own actions are not
 * configuration.
 */
public class ChromeButtonNode(
    id: UiId,
    private val componentContext: ComponentContext,
    private val label: UiState<String>,
    private val tone: ButtonTone = ButtonTone.NEUTRAL,
    /** Fixed width when the button should not size to its label, e.g. an icon button. */
    private val fixedWidth: Float? = null,
    private val onActivate: () -> Unit,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens
    private val text = TextNode(id.child("label"), label, TextRole.LABEL)

    public constructor(
        id: UiId,
        componentContext: ComponentContext,
        label: String,
        tone: ButtonTone = ButtonTone.NEUTRAL,
        fixedWidth: Float? = null,
        onActivate: () -> Unit,
    ) : this(id, componentContext, constantState(label), tone, fixedWidth, onActivate)

    init {
        interactive = true
        focusable = true
        addChild(text)
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val labelSize = text.measure(constraints.loosen(), context)
        return Size(
            fixedWidth ?: (labelSize.width + tokens.spacing.xl.value * 2),
            tokens.metrics.controlHeight.value + tokens.spacing.xs.value * 2,
        )
    }

    override fun arrangeChildren(context: LayoutContext) {
        val labelSize = text.measuredSize
        text.arrange(
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

    override fun onInputEvent(event: InputEvent) {
        if (event.phase != EventPhase.TARGET) return
        when {
            event is PointerDownEvent -> {
                onActivate()
                event.consume()
            }
            event is KeyDownEvent && (event.key == Key.ENTER || event.key == Key.SPACE) -> {
                onActivate()
                event.consume()
            }
        }
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val corners = Corners.all(tokens.radii.medium)

        val background: Color
        val border: Color
        val content: Color
        when (tone) {
            ButtonTone.PRIMARY -> {
                background = if (isHovered) palette.accentHover else palette.accent
                border = background
                content = palette.onAccent
            }
            ButtonTone.NEUTRAL -> {
                background = if (isHovered) palette.hoverBackground else palette.panelBackground
                border = palette.border
                content = palette.textPrimary
            }
            ButtonTone.DANGER -> {
                background = if (isHovered) palette.error.withAlpha(DANGER_HOVER) else palette.panelBackground
                border = if (isHovered) palette.error.withAlpha(DANGER_BORDER) else palette.border
                content = if (isHovered) palette.error else palette.textSecondary
            }
        }

        renderer.roundedRect(bounds, corners, background)
        renderer.border(bounds, corners, tokens.metrics.borderWidth, border)
        context.diagnostics.drawCalls += 2

        text.colorOverride = content

        if (isFocused) {
            renderer.border(
                bounds.outset(dev.th7bo.sidequest.ui.geometry.Insets(2f, 2f, 2f, 2f)),
                corners,
                tokens.metrics.focusRingWidth,
                palette.focusRing,
            )
            context.diagnostics.drawCalls++
        }
    }

    private companion object {
        const val DANGER_HOVER = 0.18f
        const val DANGER_BORDER = 0.5f
    }
}

/**
 * The bar across the top of the screen: title, subtitle, and the screen's own actions.
 *
 * Actions are supplied by the host rather than assumed, because what "done" means
 * depends on the interaction mode — immediate persistence has nothing to save, while
 * apply-and-cancel has two buttons.
 */
public class ScreenHeaderNode(
    id: UiId,
    private val componentContext: ComponentContext,
    title: UiState<String>,
    subtitle: UiState<String>? = null,
    eyebrow: UiState<String>? = null,
    actions: List<UiNode> = emptyList(),
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    private val titleNode = TextNode(id.child("title"), title, TextRole.TITLE)
    private val subtitleNode = subtitle?.let { TextNode(id.child("subtitle"), it, TextRole.SECONDARY) }
    private val eyebrowNode = eyebrow?.let { TextNode(id.child("eyebrow"), it, TextRole.CAPTION) }
    private val actionNodes = actions

    init {
        eyebrowNode?.let(::addChild)
        addChild(titleNode)
        subtitleNode?.let(::addChild)
        actionNodes.forEach(::addChild)
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else FALLBACK_WIDTH

        var actionsWidth = 0f
        for (action in actionNodes) {
            val size = action.measure(constraints.loosen(), context)
            actionsWidth += size.width + tokens.spacing.medium.value
        }

        val textWidth = (available - horizontalPadding * 2 - actionsWidth).coerceAtLeast(0f)
        val eyebrowSize = eyebrowNode?.measure(Constraints(maxWidth = textWidth), context)
        val titleSize = titleNode.measure(Constraints(maxWidth = textWidth), context)
        val subtitleSize = subtitleNode?.measure(Constraints(maxWidth = textWidth), context)

        val textHeight = titleSize.height +
            (eyebrowSize?.height?.plus(tokens.spacing.xs.value) ?: 0f) +
            (subtitleSize?.height?.plus(tokens.spacing.xs.value) ?: 0f)
        val actionHeight = actionNodes.maxOfOrNull { it.measuredSize.height } ?: 0f

        return Size(available, maxOf(textHeight, actionHeight) + verticalPadding * 2)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val titleSize = titleNode.measuredSize
        val subtitleSize = subtitleNode?.measuredSize
        val eyebrowSize = eyebrowNode?.measuredSize

        val textHeight = titleSize.height +
            (eyebrowSize?.height?.plus(tokens.spacing.xs.value) ?: 0f) +
            (subtitleSize?.height?.plus(tokens.spacing.xs.value) ?: 0f)
        val textTop = (measuredSize.height - textHeight) / 2f
        var nextY = textTop

        eyebrowNode?.arrange(Rect.of(Vec2(horizontalPadding, nextY), eyebrowSize!!), context)
        if (eyebrowSize != null) nextY += eyebrowSize.height + tokens.spacing.xs.value

        titleNode.arrange(Rect.of(Vec2(horizontalPadding, nextY), titleSize), context)
        nextY += titleSize.height
        subtitleNode?.arrange(
            Rect.of(
                Vec2(horizontalPadding, nextY + tokens.spacing.xs.value),
                subtitleSize!!,
            ),
            context,
        )

        // Actions are laid out from the right edge inwards, so adding one does not move
        // the others.
        var right = measuredSize.width - horizontalPadding
        for (action in actionNodes.asReversed()) {
            val size = action.measuredSize
            action.arrange(
                Rect.of(Vec2(right - size.width, (measuredSize.height - size.height) / 2f), size),
                context,
            )
            right -= size.width + tokens.spacing.medium.value
        }
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        eyebrowNode?.colorOverride = context.theme.tokens.colors.accent
        // A hairline under the header separates it from the scrolling content without
        // adding another surface.
        renderer.fillRect(
            Rect(bounds.x, bounds.bottom - 1f, bounds.width, 1f),
            context.theme.tokens.colors.border,
        )
        context.diagnostics.drawCalls++
    }

    private val horizontalPadding: Float get() = tokens.spacing.xl.value
    private val verticalPadding: Float get() = tokens.spacing.large.value

    private companion object {
        const val FALLBACK_WIDTH = 480f
    }
}
