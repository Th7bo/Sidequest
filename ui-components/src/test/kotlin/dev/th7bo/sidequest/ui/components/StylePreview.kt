package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.geometry.Dp
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.theme.DarkTheme
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * A panel drawn from the live theme tokens, for judging the design against a reference.
 *
 * **Skipped unless asked for.** Set `SIDEQUEST_STYLE_PREVIEW` to a path.
 *
 * Honest about what it is: this composes the shapes by hand rather than running the real layout, so it
 * proves nothing about `ConfigScreenNode`. What it *does* show truthfully is every value the design is
 * actually made of — the palette, the radii, the spacing rhythm, the glyphs — because all of them are read
 * from [DarkTheme] rather than restated here. Text is a bar, because the font belongs to the game.
 */
class StylePreview {

    @Test
    fun `render a panel`() {
        val target = System.getenv("SIDEQUEST_STYLE_PREVIEW")
        assumeTrue(target != null, "set SIDEQUEST_STYLE_PREVIEW to write the preview")

        val scale = 4
        val width = 240
        val height = 150
        val image = BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_RGB)
        val renderer = PreviewRenderer(image, scale)

        val tokens = DarkTheme.tokens
        val colors = tokens.colors

        // Something behind it, so translucency and depth are visible rather than assumed.
        renderer.clear(Color.parse("#1B2432"))
        renderer.roundedRect(Rect(0f, 0f, width.toFloat(), height.toFloat()), Dp.Zero, colors.windowBackground)

        val panel = Rect(10f, 10f, 140f, 130f)
        renderer.roundedRect(panel, tokens.radii.large, colors.panelBackground)

        var y = panel.y + tokens.spacing.large.value

        // A section heading: glyph, then where the words go.
        GlyphIcons.target.paint(renderer, Rect(panel.x + tokens.spacing.large.value, y, 12f, 12f), colors.accent)
        textBar(renderer, panel.x + tokens.spacing.large.value + 18f, y + 2f, 54f, colors.textPrimary)
        textBar(renderer, panel.x + tokens.spacing.large.value + 18f, y + 11f, 78f, colors.textSecondary)
        y += 26f

        // Rows, as a card.
        val card = Rect(
            panel.x + tokens.spacing.large.value,
            y,
            panel.width - tokens.spacing.large.value * 2,
            84f,
        )
        renderer.roundedRect(card, tokens.radii.medium, colors.elevatedPanelBackground)
        renderer.border(card, tokens.radii.medium, tokens.metrics.borderWidth, colors.border)

        val rowHeight = 28f
        for (index in 0 until 3) {
            val rowY = card.y + index * rowHeight + tokens.spacing.medium.value
            textBar(renderer, card.x + tokens.spacing.large.value, rowY, 46f, colors.textPrimary)
            textBar(renderer, card.x + tokens.spacing.large.value, rowY + 9f, 66f, colors.textSecondary)

            val controlRight = card.right - tokens.spacing.large.value
            when (index) {
                0 -> toggle(renderer, controlRight, rowY + 4f, on = true, colors = colors, tokens.radii.pill)
                1 -> toggle(renderer, controlRight, rowY + 4f, on = false, colors = colors, tokens.radii.pill)
                else -> slider(renderer, controlRight, rowY + 6f, colors, tokens.radii.pill)
            }

            if (index < 2) {
                renderer.fillRect(
                    Rect(card.x + tokens.spacing.small.value, card.y + (index + 1) * rowHeight, card.width - tokens.spacing.small.value * 2, 1f),
                    colors.border,
                )
            }
        }

        // A second panel, so the gap between them can be judged as well as the padding inside.
        val side = Rect(panel.right + tokens.spacing.large.value, 10f, 78f, 62f)
        renderer.roundedRect(side, tokens.radii.large, colors.panelBackground)
        GlyphIcons.people.paint(renderer, Rect(side.x + tokens.spacing.large.value, side.y + tokens.spacing.large.value, 12f, 12f), colors.accent)
        textBar(renderer, side.x + tokens.spacing.large.value + 18f, side.y + tokens.spacing.large.value + 2f, 34f, colors.textPrimary)
        textBar(renderer, side.x + tokens.spacing.large.value, side.y + 34f, 54f, colors.textSecondary)
        textBar(renderer, side.x + tokens.spacing.large.value, side.y + 43f, 42f, colors.textSecondary)

        // A primary action, to see the accent at size.
        val button = Rect(side.x, side.bottom + tokens.spacing.large.value, side.width, tokens.metrics.controlHeight.value)
        renderer.roundedRect(button, tokens.radii.medium, colors.accent)
        textBar(renderer, button.x + 18f, button.y + 6f, 42f, colors.onAccent)

        val file = File(target!!)
        file.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
    }

    /** Where a line of text would sit. Not glyphs — see the class comment. */
    private fun textBar(renderer: PreviewRenderer, x: Float, y: Float, width: Float, color: Color) {
        renderer.roundedRect(Rect(x, y, width, 5f), Dp(1.5f), color.scaleAlpha(TEXT_BAR_ALPHA))
    }

    private fun toggle(
        renderer: PreviewRenderer,
        right: Float,
        y: Float,
        on: Boolean,
        colors: dev.th7bo.sidequest.ui.theme.ColorTokens,
        pill: Dp,
    ) {
        val track = Rect(right - 26f, y, 26f, 14f)
        renderer.roundedRect(track, pill, if (on) colors.accent else colors.borderStrong)
        val knobX = if (on) track.right - 12f else track.x + 2f
        renderer.roundedRect(Rect(knobX, track.y + 2f, 10f, 10f), pill, colors.onAccent)
    }

    private fun slider(
        renderer: PreviewRenderer,
        right: Float,
        y: Float,
        colors: dev.th7bo.sidequest.ui.theme.ColorTokens,
        pill: Dp,
    ) {
        val track = Rect(right - 52f, y, 52f, 4f)
        renderer.roundedRect(track, pill, colors.borderStrong)
        renderer.roundedRect(Rect(track.x, track.y, track.width * SLIDER_FILL, track.height), pill, colors.accent)
        renderer.roundedRect(
            Rect(track.x + track.width * SLIDER_FILL - 5f, track.y - 3f, 10f, 10f),
            pill,
            colors.onAccent,
        )
    }

    private companion object {
        const val TEXT_BAR_ALPHA = 0.72f
        const val SLIDER_FILL = 0.6f
    }
}
