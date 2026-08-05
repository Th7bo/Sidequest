package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.config.ColorSetting
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.Corners
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.state.derivedStateOf

/**
 * A colour swatch with its hex value, matching the reference design.
 *
 * Activating it opens a swatch grid in the overlay layer. Without an overlay host it
 * degrades to cycling the presets in place, so the setting is never inert.
 */
public class ColorControlNode(
    private val colorSetting: ColorSetting,
    context: ComponentContext,
) : ControlNode<Color>(colorSetting, context, "color") {

    private val hexLabel = TextNode(
        colorSetting.id.child("hex"),
        derivedStateOf("${colorSetting.id.value}.hex") {
            val value = colorSetting.state.value
            if (colorSetting.allowAlpha) value.toHexString() else "#%06X".format(value.argb and 0xFFFFFF)
        },
        TextRole.MONO,
    )

    init {
        addChild(hexLabel)
        colorSetting.onChange(scope) { invalidatePaint() }
    }

    /** True while the swatch popup is showing. */
    public var isOpen: Boolean = false
        private set

    /** Opens the popup as a click would. Exposed for in-game tests. */
    public fun activateForTest() {
        activate()
    }

    override fun activate() {
        if (isOpen) {
            componentContext.overlays?.dismiss(colorSetting.id)
            return
        }

        val host = componentContext.overlays
        if (host == null) {
            // No overlay layer: fall back to cycling the presets, so the setting is
            // still adjustable rather than inert.
            val presets = colorSetting.presets
            if (presets.isEmpty()) return
            val current = presets.indexOfFirst { it == colorSetting.value }
            colorSetting.setUnchecked(presets[(current + 1) % presets.size])
            return
        }

        isOpen = true
        invalidatePaint()
        host.show(
            key = colorSetting.id,
            anchor = this,
            content = ColorPopupNode(colorSetting.id.child("popup"), colorSetting, componentContext) { chosen ->
                colorSetting.setUnchecked(chosen)
                host.dismiss(colorSetting.id)
            },
            placement = dev.th7bo.sidequest.ui.core.overlay.OverlayPlacement.BELOW_END,
            onDismiss = {
                isOpen = false
                invalidatePaint()
            },
        )
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val labelSize = hexLabel.measure(constraints.loosen(), context)
        return Size(
            SWATCH_SIZE + tokens.spacing.medium.value + labelSize.width + tokens.spacing.large.value * 2,
            tokens.metrics.controlHeight.value,
        )
    }

    override fun arrangeChildren(context: LayoutContext) {
        val labelSize = hexLabel.measuredSize
        hexLabel.arrange(
            Rect.of(
                Vec2(
                    tokens.spacing.large.value + SWATCH_SIZE + tokens.spacing.medium.value,
                    (measuredSize.height - labelSize.height) / 2f,
                ),
                labelSize,
            ),
            context,
        )
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors

        renderer.roundedRect(
            bounds,
            Corners.all(tokens.radii.medium),
            if (isHovered && isEnabled) palette.pressedBackground else palette.panelBackground,
        )
        renderer.border(
            bounds,
            Corners.all(tokens.radii.medium),
            tokens.metrics.borderWidth,
            if (isFocused || isOpen) palette.accent else palette.border,
        )
        context.diagnostics.drawCalls += 2
        renderer.roundedRect(
            Rect(bounds.x + 6f, bounds.y + 1f, bounds.width - 12f, 1f),
            tokens.radii.pill,
            palette.textPrimary.withAlpha(CONTROL_SHEEN_ALPHA),
        )
        context.diagnostics.drawCalls++

        val swatch = Rect(
            bounds.x + tokens.spacing.large.value,
            bounds.y + (bounds.height - SWATCH_SIZE) / 2f,
            SWATCH_SIZE,
            SWATCH_SIZE,
        )

        // A checker behind the swatch, so a translucent colour reads as translucent
        // rather than as a darker opaque one.
        if (colorSetting.allowAlpha && colorSetting.value.alpha < OPAQUE) {
            renderer.roundedRect(swatch, Corners.all(tokens.radii.small), palette.borderStrong)
            context.diagnostics.drawCalls++
        }

        renderer.roundedRect(
            swatch,
            Corners.all(tokens.radii.small),
            if (isEnabled) colorSetting.value else colorSetting.value.scaleAlpha(DISABLED_ALPHA),
        )
        renderer.border(
            swatch,
            Corners.all(tokens.radii.small),
            tokens.metrics.borderWidth,
            palette.borderStrong,
        )
        context.diagnostics.drawCalls += 2

        hexLabel.colorOverride = contentColor(palette.textSecondary)
        paintFocusRing(renderer, bounds, context)
    }

    private companion object {
        const val SWATCH_SIZE = 11f
        const val OPAQUE = 255
        const val DISABLED_ALPHA = 0.4f
        const val CONTROL_SHEEN_ALPHA = 0.045f
    }
}
