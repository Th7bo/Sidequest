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

    internal val roundsTop: Boolean get() = this == TOP || this == SINGLE
    internal val roundsBottom: Boolean get() = this == BOTTOM || this == SINGLE
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
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    init {
        addChild(content)
    }

    private val horizontalMargin: Float get() = tokens.spacing.large.value
    private val radius get() = tokens.radii.large

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
        return Size(available, childSize.height + trailing)
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
 * A section's card header: an accent-tinted icon block, the title, and a subtitle.
 *
 * Matches the reference design, where every card announces itself rather than relying
 * on a bare line of text above a group of rows.
 */
public class SectionCardHeaderNode(
    private val section: Section,
    private val componentContext: ComponentContext,
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

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors

        // The space is reserved either way, so headers with and without an icon keep
        // their titles on the same left edge. The *block* is only drawn when there is a
        // glyph to put in it: an empty tinted square reads as a missing icon rather than
        // as a deliberate absence.
        val icon = section.icon ?: return

        val block = Rect(
            bounds.x + horizontalPadding,
            bounds.y + (bounds.height - iconBlock) / 2f,
            iconBlock,
            iconBlock,
        )
        renderer.roundedRect(block, Corners.all(tokens.radii.small), palette.accent.withAlpha(ICON_TINT))
        renderer.border(
            block,
            Corners.all(tokens.radii.small),
            tokens.metrics.borderWidth,
            palette.accent.withAlpha(ICON_BORDER_TINT),
        )

        val inset = iconBlock * ICON_GLYPH_INSET
        componentContext.icons.draw(
            renderer,
            icon,
            Rect(block.x + inset, block.y + inset, block.width - inset * 2, block.height - inset * 2),
            palette.accent,
        )
        context.diagnostics.drawCalls += 3
    }

    private val horizontalPadding: Float get() = tokens.spacing.large.value
    private val verticalPadding: Float get() = tokens.spacing.large.value
    private val iconBlock: Float get() = ICON_BLOCK_SIZE

    private companion object {
        const val FALLBACK_WIDTH = 320f
        const val ICON_BLOCK_SIZE = 20f
        const val ICON_TINT = 0.18f
        const val ICON_BORDER_TINT = 0.45f
        const val ICON_GLYPH_INSET = 0.2f
    }
}
