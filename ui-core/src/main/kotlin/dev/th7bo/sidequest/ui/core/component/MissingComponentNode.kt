package dev.th7bo.sidequest.ui.core.component

import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.TextLayout
import dev.th7bo.sidequest.ui.rendering.TextOverflow
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import kotlin.math.max

/**
 * The placeholder shown where a control could not be built.
 *
 * In development it names the setting, its type and the problem, so the failure is
 * actionable without a debugger. In production it degrades to a neutral strip: a normal
 * user should never be shown a stack trace, but the row must still occupy space so the
 * rest of the screen keeps its layout.
 */
public class MissingComponentNode(
    public val settingId: UiId,
    public val settingType: String,
    public val problem: String,
    private val isDevelopment: Boolean,
) : UiNode(settingId.child("missing_renderer")) {

    private var titleLayout: TextLayout? = null
    private var detailLayout: TextLayout? = null

    init {
        interactive = true
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        if (!isDevelopment) {
            titleLayout = null
            detailLayout = null
            return Size(constraints.maxWidth.coerceAtMost(FALLBACK_WIDTH), PRODUCTION_HEIGHT)
        }

        val available = if (constraints.hasBoundedWidth) constraints.maxWidth - PADDING * 2 else null

        val title = context.textMeasurer.measure(
            text = "Cannot render $settingType",
            style = context.theme.textStyle(TextRole.LABEL),
            maxWidth = available,
            overflow = TextOverflow.ELLIPSIS,
        )
        val detail = context.textMeasurer.measure(
            text = "$settingId — $problem",
            style = context.theme.textStyle(TextRole.CAPTION),
            maxWidth = available,
            maxLines = DETAIL_LINES,
            overflow = TextOverflow.WRAP,
        )
        titleLayout = title
        detailLayout = detail
        context.diagnostics.textLayouts += 2

        val intrinsicWidth = max(title.size.width, detail.size.width) + PADDING * 2
        return Size(
            // Clamped: the placeholder describes a failure, and a description that runs
            // off the edge of the panel is a second failure on top of the first.
            if (constraints.hasBoundedWidth) minOf(intrinsicWidth, constraints.maxWidth) else intrinsicWidth,
            title.size.height + detail.size.height + PADDING * 2 + GAP,
        )
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val tokens = context.theme.tokens

        renderer.roundedRect(bounds, tokens.radii.small, tokens.colors.error.withAlpha(BACKDROP_ALPHA))
        renderer.border(bounds, tokens.radii.small, tokens.metrics.borderWidth, tokens.colors.error)
        context.diagnostics.drawCalls += 2

        val title = titleLayout ?: return
        val detail = detailLayout ?: return
        val origin = bounds.position + Vec2(PADDING, PADDING)

        renderer.text(title, origin, tokens.colors.error)
        renderer.text(detail, origin + Vec2(0f, title.size.height + GAP), tokens.colors.textSecondary)
        context.diagnostics.drawCalls += 2
    }

    private companion object {
        const val PADDING = 6f
        const val GAP = 2f
        const val DETAIL_LINES = 3
        const val FALLBACK_WIDTH = 240f
        const val PRODUCTION_HEIGHT = 20f
        const val BACKDROP_ALPHA = 0.12f
    }
}
