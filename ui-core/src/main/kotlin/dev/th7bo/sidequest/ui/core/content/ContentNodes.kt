package dev.th7bo.sidequest.ui.core.content

import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Dp
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.TextLayout
import dev.th7bo.sidequest.ui.rendering.TextOverflow
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.theme.ThemeTokens
import kotlin.math.max

/**
 * Draws a string, sized to the text.
 *
 * The text comes from a [UiState], and the node observes it. That is the link that
 * makes targeted invalidation work: changing one label invalidates one node and its
 * ancestors' measurement, not the screen.
 *
 * The measured [TextLayout] is cached and only recomputed when the string, the role or
 * the available width actually changes — text measurement is the single most repeated
 * expensive operation in a config screen.
 */
public class TextNode(
    id: UiId,
    text: UiState<String>,
    role: TextRole = TextRole.BODY,
    key: Any? = null,
) : UiNode(id, key) {

    public var role: TextRole = role
        set(value) {
            if (field == value) return
            field = value
            layout = null
            invalidateMeasure()
        }

    /** Overrides the theme colour for this role when set. */
    public var colorOverride: Color? = null
        set(value) {
            if (field == value) return
            field = value
            invalidatePaint()
        }

    public var maxLines: Int = 1
        set(value) {
            require(value >= 1) { "maxLines must be at least 1, was $value" }
            if (field == value) return
            field = value
            layout = null
            invalidateMeasure()
        }

    public var overflow: TextOverflow = TextOverflow.ELLIPSIS
        set(value) {
            if (field == value) return
            field = value
            layout = null
            invalidateMeasure()
        }

    public var text: UiState<String> = text
        set(value) {
            if (field === value) return
            field = value
            observeText()
            layout = null
            invalidateMeasure()
        }

    private var layout: TextLayout? = null
    private var layoutText: String? = null
    private var layoutWidth: Float = Float.NaN

    init {
        observeText()
    }

    private fun observeText() {
        text.observe(scope) {
            layout = null
            invalidateMeasure()
        }
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val content = text.value
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else Float.NaN

        val cached = layout
        if (cached != null && layoutText == content && layoutWidth.equalsOrBothNaN(available)) {
            context.diagnostics.cacheHits++
            return cached.size
        }

        context.diagnostics.cacheMisses++
        context.diagnostics.textLayouts++
        val measured = context.textMeasurer.measure(
            text = content,
            style = context.theme.textStyle(role),
            maxWidth = if (available.isNaN()) null else available,
            maxLines = maxLines,
            overflow = overflow,
        )
        layout = measured
        layoutText = content
        layoutWidth = available
        return measured.size
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val measured = layout ?: return
        val color = colorOverride ?: context.theme.textColor(role)
        renderer.text(measured, bounds.position, color)
        context.diagnostics.drawCalls++
    }

    private fun Float.equalsOrBothNaN(other: Float): Boolean =
        (isNaN() && other.isNaN()) || this == other

    public companion object {
        /** Convenience for text that never changes. */
        public fun of(
            id: UiId,
            text: String,
            role: TextRole = TextRole.BODY,
        ): TextNode = TextNode(id, constantState(text), role)
    }
}

/**
 * A filled, optionally rounded and bordered rectangle.
 *
 * The primitive every surface in the design language is built from: cards, panels,
 * track backgrounds and progress fills are all this node with different tokens.
 */
public class SurfaceNode(
    id: UiId,
    key: Any? = null,
) : UiNode(id, key) {

    /** Literal fill colour, used when [colorToken] is not set. */
    public var color: Color = Color.Transparent
        set(value) {
            if (field == value) return
            field = value
            invalidatePaint()
        }

    /**
     * Resolves the fill colour from the active theme at paint time.
     *
     * Prefer this over [color]: a literal colour is captured once and would not follow
     * a runtime theme switch, which is exactly the kind of hard-coding the token system
     * exists to prevent.
     *
     * ```
     * surface.colorToken = { it.colors.elevatedPanelBackground }
     * ```
     */
    public var colorToken: ((ThemeTokens) -> Color)? = null
        set(value) {
            if (field === value) return
            field = value
            invalidatePaint()
        }

    public var cornerRadius: Dp = Dp.Zero
        set(value) {
            if (field == value) return
            field = value
            invalidatePaint()
        }

    public var borderColor: Color = Color.Transparent
        set(value) {
            if (field == value) return
            field = value
            invalidatePaint()
        }

    /** Theme-resolved border colour. See [colorToken]. */
    public var borderColorToken: ((ThemeTokens) -> Color)? = null
        set(value) {
            if (field === value) return
            field = value
            invalidatePaint()
        }

    public var borderWidth: Dp = Dp.Zero
        set(value) {
            if (field == value) return
            field = value
            invalidatePaint()
        }

    /** Fixed size, when the surface is not sizing itself to a child. */
    public var preferredSize: Size? = null
        set(value) {
            if (field == value) return
            field = value
            invalidateMeasure()
        }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val fixed = preferredSize
        // Children are measured even when the size is fixed. Returning early here would
        // leave them unmeasured, and therefore invisible, which is a silent failure.
        val childConstraints = if (fixed != null) Constraints.atMost(fixed) else constraints.loosen()

        var width = 0f
        var height = 0f
        for (child in children) {
            if (!child.isVisible) continue
            val childSize = child.measure(childConstraints, context)
            width = max(width, childSize.width)
            height = max(height, childSize.height)
        }

        if (fixed != null) return fixed
        if (children.none { it.isVisible }) return Size(constraints.minWidth, constraints.minHeight)
        return Size(width, height)
    }

    override fun arrangeChildren(context: LayoutContext) {
        for (child in children) {
            if (child.isVisible) child.arrange(Rect.of(Vec2.Zero, child.measuredSize), context)
        }
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val tokens = context.theme.tokens
        val fill = colorToken?.invoke(tokens) ?: color
        val stroke = borderColorToken?.invoke(tokens) ?: borderColor

        if (!fill.isTransparent) {
            if (cornerRadius.value > 0f) {
                renderer.roundedRect(bounds, cornerRadius, fill)
            } else {
                renderer.fillRect(bounds, fill)
            }
            context.diagnostics.drawCalls++
        }
        if (!stroke.isTransparent && borderWidth.value > 0f) {
            renderer.border(bounds, cornerRadius, borderWidth, stroke)
            context.diagnostics.drawCalls++
        }
    }
}
