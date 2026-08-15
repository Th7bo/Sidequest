package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.theme.DarkTheme
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the profile window's chrome to a PNG.
 *
 * **Skipped unless asked for.** Set `SIDEQUEST_PROFILE_PREVIEW` to a path.
 *
 * Exists because the alternative is shipping a layout that was reasoned about rather than seen, which this
 * project has paid for before. The page itself is a browser texture and cannot appear here, so a flat
 * rectangle stands in for it — everything around that rectangle is exactly what the game draws.
 *
 * Text is a monospaced approximation, as everywhere in these previews; what this checks is placement,
 * proportion and colour, not typography.
 */
class ProfileWindowPreview {

    @Test
    fun `render the profile window`() {
        val target = System.getenv("SIDEQUEST_PROFILE_PREVIEW")
        assumeTrue(target != null, "set SIDEQUEST_PROFILE_PREVIEW to write the preview")

        val scale = 3
        val width = 640f
        val height = 360f
        val image = BufferedImage((width * scale).toInt(), (height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val renderer = PreviewRenderer(image, scale)

        // Something behind it, so the dimming and the shadow have something to sit on.
        renderer.clear(Color.parse("#3B4A5A"))
        renderer.fillRect(Rect(0f, 0f, width, height), DarkTheme.tokens.colors.scrim)

        val layout = ProfileWindowLayout.of(Rect(0f, 0f, width, height), isMaximised = false, hasQuickSwitch = true)

        ProfileWindowChrome.paintFrame(renderer, DarkTheme, layout)
        // Standing in for the page, in something obviously not a real screenshot.
        renderer.fillRect(layout.content, Color.parse("#0F1216"))
        renderer.text(
            renderer.textMeasurer.measure("(the SkyCrypt page renders here)", DarkTheme.tokens.typography.body),
            Vec2(layout.content.x + 12f, layout.content.y + 12f),
            Color.parse("#55607A"),
        )

        ProfileWindowChrome.paintBar(
            renderer,
            DarkTheme,
            layout,
            ProfileWindowState(
                searchText = "coolstantin",
                isSearchFocused = true,
                isCaretVisible = true,
                // Parked over the close button, so its hover state is in the picture.
                pointer = Vec2(layout.close.x + layout.close.width / 2f, layout.close.y + layout.close.height / 2f),
            ),
        )
        ProfileWindowChrome.paintNotice(
            renderer,
            DarkTheme,
            layout,
            "Esc closes · Ctrl+L searches · Ctrl+O opens in your browser · F5 reloads",
            1f,
        )

        val file = File(target!!)
        file.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
    }
}
