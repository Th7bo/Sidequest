package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.config.Section
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
import dev.th7bo.sidequest.ui.rendering.Edges
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.UiRenderer

/**
 * Which part of a card a row draws.
 *
 * A section is presented as one card, but the rows inside it are separate nodes so the
 * list can still virtualize them. Each row therefore draws its own horizontal slice of
 * the card, rounding only the corners that are genuinely at the card's edge.
 */
public enum class CardSegment {
    /** First slice: rounded top corners, top edge. */
    TOP,

    /** Neither first nor last: vertical edges only. */
    MIDDLE,

    /** Last slice: rounded bottom corners, bottom edge. */
    BOTTOM,

    /** The only slice: a complete card. */
    SINGLE,
    ;

    public val roundsTop: Boolean get() = this == TOP || this == SINGLE
    public val roundsBottom: Boolean get() = this == BOTTOM || this == SINGLE
}

/**
 * Wraps a row in its slice of the section card.
 *
 * Draws the card surface, its border, and the hairline divider separating this row from
 * the one above. Card geometry lives here once rather than in every control.
 */
public class CardSliceNode(
    id: UiId,
    private val segment: CardSegment,
    /** Whether to draw a divider along the top edge, between this row and the previous. */
    private val showDivider: Boolean,
    componentContext: ComponentContext,
    content: UiNode,
    /**
     * Whether this slice adds the inset that squares the card's bottom against its top.
     *
     * A section header pads itself by `spacing.large`; a setting row pads itself by `spacing.medium`. So a
     * card of a header and some rows breathes more at the top than at the bottom, which shows most clearly
     * when a section is down to a single row and the control sits almost on the card's edge.
     *
     * The difference is added here rather than by widening every row's padding, because it is a property of
     * being *last in a card* rather than of being a row — and card geometry is what this class owns.
     */
    private val squaresBottom: Boolean = false,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    init {
        addChild(content)
    }

    private val horizontalMargin: Float get() = tokens.spacing.large.value
    private val radius get() = tokens.radii.large

    /** What a row is short by, against the padding a header gives itself. Zero for anything else. */
    private val bottomInset: Float
        get() = if (squaresBottom) {
            (tokens.spacing.large.value - tokens.spacing.medium.value).coerceAtLeast(0f)
        } else {
            0f
        }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else FALLBACK_WIDTH
        val contentWidth = (available - horizontalMargin * 2).coerceAtLeast(0f)

        val child = children.firstOrNull { it.isVisible } ?: return Size(available, 0f)
        val childSize = child.measure(
            Constraints(minWidth = contentWidth, maxWidth = contentWidth),
            context,
        )

        // The extra height is the gap between cards, added below the last slice only.
        val trailing = if (segment.roundsBottom) tokens.spacing.large.value else 0f
        return Size(available, childSize.height + bottomInset + trailing)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val child = children.firstOrNull { it.isVisible } ?: return
        child.arrange(Rect.of(Vec2(horizontalMargin, 0f), child.measuredSize), context)
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors

        val trailing = if (segment.roundsBottom) tokens.spacing.large.value else 0f
        val card = Rect(
            bounds.x + horizontalMargin,
            bounds.y,
            (bounds.width - horizontalMargin * 2).coerceAtLeast(0f),
            (bounds.height - trailing).coerceAtLeast(0f),
        )
        if (card.isEmpty) return

        val corners = when (segment) {
            CardSegment.TOP -> Corners.top(radius)
            CardSegment.BOTTOM -> Corners.bottom(radius)
            CardSegment.MIDDLE -> Corners.None
            CardSegment.SINGLE -> Corners.all(radius)
        }

        renderer.roundedRect(card, corners, palette.elevatedPanelBackground)
        context.diagnostics.drawCalls++

        // The horizontal edges belong to the card, not to every row — drawing them on a
        // middle slice would put a line through the middle of the card.
        if (segment == CardSegment.MIDDLE) {
            renderer.edges(card, Edges.Sides, tokens.metrics.borderWidth, palette.border)
        } else {
            renderer.border(card, corners, tokens.metrics.borderWidth, palette.border)
            if (segment == CardSegment.TOP) {
                renderer.edges(card, Edges(left = true, right = true), tokens.metrics.borderWidth, palette.border)
            }
        }
        context.diagnostics.drawCalls++

        if (showDivider) {
            // Inset from the border so the two do not visually merge into one thick line.
            val inset = tokens.spacing.small.value
            renderer.fillRect(
                Rect(card.x + inset, card.y, (card.width - inset * 2).coerceAtLeast(0f), DIVIDER_THICKNESS),
                palette.border,
            )
            context.diagnostics.drawCalls++
        }
    }

    private companion object {
        const val FALLBACK_WIDTH = 320f
        const val DIVIDER_THICKNESS = 1f
    }
}

/**
 * A section's card header: a small accent glyph, the title, and a subtitle.
 *
 * Matches the reference design, where every card announces itself rather than relying
 * on a bare line of text above a group of rows.
 */
public class SectionCardHeaderNode(
    private val section: Section,
    private val componentContext: ComponentContext,
    /**
     * Whether the section is folded away, or null when it cannot be.
     *
     * Null rather than a `false` state, so a header that is not collapsible draws no chevron and swallows no
     * clicks — a control that looks pressable and does nothing is worse than no control.
     */
    private val isCollapsed: (() -> Boolean)? = null,
    private val onToggle: () -> Unit = {},
) : UiNode(section.id.child("card_header")) {

    private val tokens = componentContext.theme.tokens

    private val title = TextNode(section.id.child("card_title"), section.title, TextRole.TITLE)
    private val subtitle = section.description?.let {
        TextNode(section.id.child("card_subtitle"), it, TextRole.SECONDARY)
    }

    private val textColumn = ColumnNode(section.id.child("card_header_text"), spacing = tokens.spacing.xs)

    init {
        textColumn.addChild(title)
        subtitle?.let(textColumn::addChild)
        addChild(textColumn)
        // Only a folding header takes clicks. `interactive` defaults to false and the hit test consults it,
        // so without this the header drew a chevron nothing could press — which is exactly what shipped.
        interactive = isCollapsed != null
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else FALLBACK_WIDTH
        val textWidth = (available - horizontalPadding * 2 - iconBlock - tokens.spacing.large.value)
            .coerceAtLeast(0f)

        val textSize = textColumn.measure(
            Constraints(maxWidth = textWidth),
            context,
        )
        return Size(available, maxOf(textSize.height, iconBlock) + verticalPadding * 2)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val textSize = textColumn.measuredSize
        textColumn.arrange(
            Rect.of(
                Vec2(
                    horizontalPadding + iconBlock + tokens.spacing.large.value,
                    (measuredSize.height - textSize.height) / 2f,
                ),
                textSize,
            ),
            context,
        )
    }

    /**
     * The whole header is the hit target, not just the chevron.
     *
     * A twelve-pixel arrow is a hard thing to click, and every part of the header means the same thing — so
     * the row folds wherever it is pressed.
     */
    override fun onInputEvent(event: InputEvent) {
        if (isCollapsed == null) return
        if (event.phase != EventPhase.TARGET) return
        when {
            event is PointerDownEvent -> {
                onToggle()
                event.consume()
            }
            event is KeyDownEvent && (event.key == Key.ENTER || event.key == Key.SPACE) -> {
                onToggle()
                event.consume()
            }
        }
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors

        // The space is reserved either way, so headers with and without an icon keep
        // their titles on the same left edge. The *block* is only drawn when there is a
        // glyph to put in it: an empty tinted square reads as a missing icon rather than
        // as a deliberate absence.
        // The fold indicator, on the right. Drawn before the early return below, because a collapsible
        // section with no icon still has to say it can be folded.
        isCollapsed?.let { collapsed ->
            drawChevron(renderer, bounds, palette.textSecondary, pointsDown = !collapsed())
            context.diagnostics.drawCalls += 2
        }

        val icon = section.icon ?: return

        // The glyph alone, with no tile behind it.
        //
        // This used to draw an accent-tinted rounded square with its own border and put the icon inside.
        // Two problems, and they compound: a chip of solid colour on every heading is a lot of visual weight
        // for decoration, and the thing inside it is a Minecraft item texture — so the one element drawing
        // the eye was also the most pixel-art element on the screen. Modern panels label a section with
        // words and use an icon as a quiet marker beside them, if at all.
        val block = Rect(
            bounds.x + horizontalPadding,
            bounds.y + (bounds.height - iconBlock) / 2f,
            iconBlock,
            iconBlock,
        )
        componentContext.icons.draw(renderer, icon, block, palette.accent)
        context.diagnostics.drawCalls += 1
    }

    /**
     * A chevron, from two spans.
     *
     * The renderer has no polygon primitive, and an arrow built from two rotated-looking rectangles is
     * cheaper than adding one for a shape this small — the same trade the world-overlay edge indicator makes.
     * Down means open, right means folded, which is the convention every file tree uses.
     */
    private fun drawChevron(renderer: UiRenderer, bounds: Rect, colour: Color, pointsDown: Boolean) {
        val size = CHEVRON_SIZE
        val centreX = bounds.right - horizontalPadding - size / 2f
        val centreY = bounds.y + bounds.height / 2f
        val step = size / CHEVRON_STEPS

        // A triangle stacked out of shrinking bars. The renderer has no polygon primitive, and a staircase is
        // both cheaper than adding one and closer to how the game draws everything else — the first attempt
        // used two crossed bars and rendered as a "T", which is what the screenshot caught.
        for (index in 0 until CHEVRON_STEPS) {
            val inset = index * step
            if (pointsDown) {
                val width = size - inset * 2f
                if (width <= 0f) break
                renderer.fillRect(Rect(centreX - width / 2f, centreY - size / 2f + inset, width, step), colour)
            } else {
                val height = size - inset * 2f
                if (height <= 0f) break
                renderer.fillRect(Rect(centreX - size / 2f + inset, centreY - height / 2f, step, height), colour)
            }
        }
    }

    private val horizontalPadding: Float get() = tokens.spacing.large.value
    private val verticalPadding: Float get() = tokens.spacing.large.value
    private val iconBlock: Float get() = ICON_BLOCK_SIZE

    private companion object {
        const val FALLBACK_WIDTH = 320f
        const val ICON_BLOCK_SIZE = 12f
        const val CHEVRON_SIZE = 8f

        /** How many bars the triangle is stacked from. Four reads as a chevron at every GUI scale. */
        const val CHEVRON_STEPS = 4
    }
}
