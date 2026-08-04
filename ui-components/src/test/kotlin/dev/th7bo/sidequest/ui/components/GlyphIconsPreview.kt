package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.rendering.Color
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the glyph set, so it can be looked at rather than imagined.
 *
 * **Skipped unless asked for.** Set `SIDEQUEST_GLYPH_PREVIEW` to a path.
 *
 * Each glyph is drawn at the size a section header actually uses and again much larger, because the two
 * answer different questions: the small one is whether it survives at twelve pixels, and the large one is
 * whether it is the shape it was meant to be.
 */
class GlyphIconsPreview {

    @Test
    fun `render the glyph set`() {
        val target = System.getenv("SIDEQUEST_GLYPH_PREVIEW")
        assumeTrue(target != null, "set SIDEQUEST_GLYPH_PREVIEW to write the preview")

        val glyphs = listOf(
            "target" to GlyphIcons.target,
            "bars" to GlyphIcons.bars,
            "ring" to GlyphIcons.ring,
            "sliders" to GlyphIcons.sliders,
            "block" to GlyphIcons.block,
            "frame" to GlyphIcons.frame,
            "dot" to GlyphIcons.dot,
            "dash" to GlyphIcons.dash,
            "plus" to GlyphIcons.plus,
            "people" to GlyphIcons.people,
        )

        val scale = 6
        val cell = 46
        val image = BufferedImage(cell * glyphs.size * scale, cell * 2 * scale, BufferedImage.TYPE_INT_RGB)
        val renderer = PreviewRenderer(image, scale)
        renderer.clear(Color.parse("#0A0912"))

        val accent = Color.parse("#A855F7")

        glyphs.forEachIndexed { index, (_, painter) ->
            // Large: is it the shape it was meant to be?
            painter.paint(renderer, Rect(index * cell + 5f, 5f, 36f, 36f), accent)

            // Small: does it survive at the size a header draws it?
            painter.paint(renderer, Rect(index * cell + 17f, cell + 17f, 12f, 12f), accent)
        }

        val file = File(target!!)
        file.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
    }
}
