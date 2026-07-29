package dev.th7bo.sidequest.ui.components.world

import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.core.world.ResolvedOverlay
import dev.th7bo.sidequest.ui.core.world.WorldOverlayLayer
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.Corners
import dev.th7bo.sidequest.ui.rendering.TextStyle
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.world.WorldProjector
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws every world overlay for the current frame.
 *
 * Immediate-mode rather than a retained child per overlay, and deliberately so: a
 * waypoint's screen position changes every frame the camera moves, so a retained node
 * would be re-arranged every frame and the invalidation machinery would earn nothing.
 * Nothing here is stored between frames, which is also what guarantees a projected screen
 * coordinate can never be mistaken for a placement.
 */
public class WaypointLayerNode(
    id: UiId,
    private val overlays: WorldOverlayLayer,
    private val componentContext: ComponentContext,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    /** Supplies the camera. Replaced each frame by the adapter; null draws nothing. */
    public var projector: WorldProjector? = null

    /** The overlays drawn on the last frame, for assertions and diagnostics. */
    public var lastResolved: List<ResolvedOverlay> = emptyList()
        private set

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size = Size(
        constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: 0f,
        constraints.maxHeight.takeIf { constraints.hasBoundedHeight } ?: 0f,
    )

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val active = projector ?: run {
            lastResolved = emptyList()
            return
        }

        val resolved = overlays.resolve(active, bounds.size)
        lastResolved = resolved

        for (overlay in resolved) {
            // The origin is the viewport's, not the node's, only when the node fills the
            // screen — which it does. Offsetting keeps that honest if it ever does not.
            val at = Vec2(bounds.x + overlay.screenPosition.x, bounds.y + overlay.screenPosition.y)
            if (overlay.isEdgeIndicator) {
                paintIndicator(renderer, overlay, at, context)
            } else {
                paintMarker(renderer, overlay, at, context)
            }
        }
    }

    private fun paintMarker(
        renderer: UiRenderer,
        overlay: ResolvedOverlay,
        at: Vec2,
        context: RenderContext,
    ) {
        val color = (overlay.definition.color ?: context.theme.tokens.colors.accent)
            .withAlpha(overlay.opacity)

        val label = buildString {
            append(overlay.definition.label.peek())
            if (overlay.definition.showsDistance) {
                append("  ")
                append(overlay.distance.roundToInt())
                append('m')
            }
        }

        // A shadow, because this text sits over the world rather than over a panel and
        // has to stay legible against whatever happens to be behind it.
        val style = TextStyle(shadow = true)
        val layout = renderer.textMeasurer.measure(label, style)
        val width = layout.size.width
        val height = layout.size.height

        // Centred on the projected point and lifted clear of it, so the diamond marks the
        // block and the label does not cover it.
        val boxWidth = width + tokens.spacing.small.value * 2f
        val boxHeight = height + tokens.spacing.xs.value * 2f
        val box = Rect(
            at.x - boxWidth / 2f,
            at.y - boxHeight - MARKER_SIZE,
            boxWidth,
            boxHeight,
        )

        renderer.roundedRect(
            box,
            Corners.all(tokens.radii.small),
            context.theme.tokens.colors.elevatedPanelBackground.withAlpha(overlay.opacity * PANEL_ALPHA),
        )
        renderer.border(box, Corners.all(tokens.radii.small), tokens.metrics.borderWidth, color)
        renderer.text(
            layout,
            Vec2(box.x + tokens.spacing.small.value, box.y + tokens.spacing.xs.value),
            color,
        )

        // The marker itself: a small diamond on the exact projected point.
        renderer.fillRect(
            Rect(at.x - MARKER_SIZE / 2f, at.y - MARKER_SIZE / 2f, MARKER_SIZE, MARKER_SIZE),
            color,
        )
        context.diagnostics.drawCalls += 4
    }

    private fun paintIndicator(
        renderer: UiRenderer,
        overlay: ResolvedOverlay,
        at: Vec2,
        context: RenderContext,
    ) {
        val color = (overlay.definition.color ?: context.theme.tokens.colors.accent)
            .withAlpha(overlay.opacity)
        val size = overlay.definition.edgeIndicator.size

        // A triangle from three spans along the pointing direction. The renderer has no
        // polygon primitive, and an arrow built from rotated spans is cheaper than adding
        // one for a shape this small.
        val angle = overlay.angleRadians
        val tipX = at.x + cos(angle) * size
        val tipY = at.y + sin(angle) * size

        renderer.fillRect(Rect(at.x - size / 2f, at.y - size / 2f, size, size), color.withAlpha(overlay.opacity * BODY_ALPHA))
        renderer.fillRect(
            Rect(tipX - TIP_SIZE / 2f, tipY - TIP_SIZE / 2f, TIP_SIZE, TIP_SIZE),
            color,
        )
        context.diagnostics.drawCalls += 2
    }

    private companion object {
        const val MARKER_SIZE = 4f
        const val TIP_SIZE = 3f
        const val PANEL_ALPHA = 0.85f
        const val BODY_ALPHA = 0.5f
    }
}

/** Convenience for the demo: a waypoint colour that stays readable on any background. */
public val DEFAULT_WAYPOINT_COLOR: Color = Color.parse("#FF8B5CF6")
