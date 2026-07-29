package dev.th7bo.sidequest.ui.components.hud

import dev.th7bo.sidequest.ui.components.ProgressBarNode
import dev.th7bo.sidequest.ui.core.animation.AnimatedFloat
import dev.th7bo.sidequest.ui.core.animation.Easing
import dev.th7bo.sidequest.ui.core.animation.HostedAnimation
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.content.SurfaceNode
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.core.layout.BoxNode
import dev.th7bo.sidequest.ui.core.layout.ColumnNode
import dev.th7bo.sidequest.ui.core.layout.FixedSizeNode
import dev.th7bo.sidequest.ui.core.layout.PaddingNode
import dev.th7bo.sidequest.ui.core.layout.RowNode
import dev.th7bo.sidequest.ui.core.layout.SpacerNode
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Alignment
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Insets
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.geometry.VerticalAlignment
import dev.th7bo.sidequest.ui.geometry.dp
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Corners
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.combine
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.derivedStateOf

/**
 * The compact progress card from the design reference: an icon block on the left, a
 * title with a level chip, the current and required values on the right, and a thin
 * accent progress bar underneath.
 *
 * A specialised builder over the standard primitives rather than a separate rendering
 * path — everything here is `SurfaceNode`, `TextNode` and the layout nodes, which is
 * what the plan means by specialised builders composing standard components.
 */
public class ProgressHudNode(
    id: UiId,
    private val componentContext: ComponentContext,
    title: UiState<String>,
    current: UiState<Long>,
    maximum: UiState<Long>,
    subtitle: UiState<String>? = null,
    private val icon: Icon? = null,
    /** Formats the numeric readout. Defaults to thousands separators. */
    format: (Long) -> String = ::groupDigits,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    private val fraction: UiState<Float> = combine(current, maximum, "${id.value}.fraction") { value, total ->
        if (total <= 0L) 0f else (value.toFloat() / total).coerceIn(0f, 1f)
    }

    /** Animated so a jump in value glides rather than snapping. */
    private val fill = HostedAnimation(
        componentContext.animations,
        AnimatedFloat(
            initial = fraction.peek(),
            duration = componentContext.theme.tokens.motion.slow,
            easing = Easing.EaseOut,
            debugName = "${id.value}.fill",
        ),
    )

    private val bar = ProgressBarNode(id.child("bar"), fill.state, TRACK_WIDTH)

    /** Current bar fill in `0..1`, for assertions. */
    public val fillFraction: Float get() = fill.value

    init {
        val iconSlot = FixedSizeNode(id.child("icon_slot"), width = ICON_BLOCK.dp, height = ICON_BLOCK.dp)

        // Split into two runs so the current value can carry the accent, as in the
        // reference: the number that changes is the one worth the colour.
        val currentLabel = TextNode(
            id.child("current"),
            derivedStateOf("${id.value}.current") { format(current.value) },
            TextRole.SECONDARY,
        ).apply { colorOverride = tokens.colors.accent }

        val subtitleLabel = subtitle?.let {
            TextNode(id.child("subtitle"), it, TextRole.SECONDARY)
                .apply { colorOverride = tokens.colors.accent }
        }

        val titleRow = RowNode(id.child("title_row"), spacing = tokens.spacing.small).apply {
            verticalAlignment = VerticalAlignment.CENTER
            addChild(TextNode(id.child("title"), title, TextRole.LABEL))
            subtitleLabel?.let {
                addChild(
                    TextNode(id.child("dot"), constantState("\u2022"), TextRole.SECONDARY),
                )
                addChild(it)
            }
            addChild(SpacerNode(id.child("gap")).apply { layoutWeight = 1f })
            addChild(currentLabel)
            addChild(
                TextNode(
                    id.child("maximum"),
                    derivedStateOf("${id.value}.maximum") { "/ ${format(maximum.value)}" },
                    TextRole.SECONDARY,
                ),
            )
        }

        // The title row sizes to its text and the bar stretches to match it, so the
        // readout can never overflow the card: the card is as wide as the text needs.
        // A fixed track width here would have pinned the bar and let the text run past
        // the card's right edge.
        bar.fillCrossAxis = true

        val body = ColumnNode(id.child("body"), spacing = tokens.spacing.small).apply {
            addChild(titleRow)
            addChild(bar)
        }

        val content = RowNode(id.child("content"), spacing = tokens.spacing.medium).apply {
            verticalAlignment = VerticalAlignment.CENTER
            addChildren(iconSlot, body)
        }

        addChild(
            PaddingNode(id.child("padding"), Insets.symmetric(tokens.spacing.large, tokens.spacing.medium))
                .apply { addChild(content) },
        )

        fraction.observe(scope) { fill.target = it }
        fill.state.observe(scope) { invalidatePaint() }
        fill.snapTo(fraction.peek())
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val child = children.first()
        return child.measure(constraints.loosen(), context)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val child = children.first()
        child.arrange(Rect.of(Vec2.Zero, child.measuredSize), context)
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val corners = Corners.all(tokens.radii.large)

        renderer.roundedRect(bounds, corners, palette.elevatedPanelBackground)
        renderer.border(bounds, corners, tokens.metrics.borderWidth, palette.border)
        context.diagnostics.drawCalls += 2

        // The icon block: an accent-tinted rounded square with the glyph inside, as in
        // the reference.
        val block = Rect(
            bounds.x + tokens.spacing.large.value,
            bounds.y + (bounds.height - ICON_BLOCK) / 2f,
            ICON_BLOCK,
            ICON_BLOCK,
        )
        renderer.roundedRect(block, Corners.all(tokens.radii.medium), palette.accent.withAlpha(ICON_TINT))
        renderer.border(
            block,
            Corners.all(tokens.radii.medium),
            tokens.metrics.borderWidth,
            palette.accent.withAlpha(ICON_BORDER_TINT),
        )
        context.diagnostics.drawCalls += 2

        icon?.let {
            val inset = ICON_BLOCK * ICON_GLYPH_INSET
            componentContext.icons.draw(
                renderer,
                it,
                Rect(block.x + inset, block.y + inset, block.width - inset * 2, block.height - inset * 2),
                palette.accent,
            )
            context.diagnostics.drawCalls++
        }
    }

    public companion object {
        public const val TRACK_WIDTH: Float = 120f
        private const val ICON_BLOCK = 22f
        private const val ICON_TINT = 0.18f
        private const val ICON_BORDER_TINT = 0.5f
        private const val ICON_GLYPH_INSET = 0.22f
    }
}

/** `28450` becomes `28,450`. Readable at a glance, which is the point of a HUD. */
public fun groupDigits(value: Long): String {
    val digits = value.toString()
    if (digits.length <= GROUP) return digits
    return buildString {
        val negative = digits.startsWith("-")
        val body = if (negative) digits.substring(1) else digits
        if (negative) append('-')
        for ((index, character) in body.withIndex()) {
            if (index > 0 && (body.length - index) % GROUP == 0) append(',')
            append(character)
        }
    }
}

private const val GROUP = 3
