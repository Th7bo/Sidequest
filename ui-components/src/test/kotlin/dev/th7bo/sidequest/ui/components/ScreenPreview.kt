package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.binding.bind
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.config.option
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.theme.DarkTheme
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the *real* configuration screen to a PNG.
 *
 * **Skipped unless asked for.** Set `SIDEQUEST_SCREEN_PREVIEW` to a path. Optional
 * `SIDEQUEST_SCREEN_PREVIEW_WIDTH` and `SIDEQUEST_SCREEN_PREVIEW_HEIGHT` values exercise responsive layouts.
 *
 * Not a mock. This drives `ConfigScreenController` with the registered controls, the real layout and the
 * real theme, through a renderer that draws into an image. Client screenshots remain the authority for
 * typography — which matters because text rasterization must be judged in Minecraft itself.
 *
 * The one thing it cannot show is Minecraft's own bitmap text rasterizer. Spacing, weight and colour are
 * approximate here; the exact glyphs are validated by the client game test.
 */
class ScreenPreview {

    @Test
    fun `render the configuration screen`() {
        val target = System.getenv("SIDEQUEST_SCREEN_PREVIEW")
        assumeTrue(target != null, "set SIDEQUEST_SCREEN_PREVIEW to write the preview")

        val scale = 2
        val viewport = Size(
            environmentDimension("SIDEQUEST_SCREEN_PREVIEW_WIDTH", 780f),
            environmentDimension("SIDEQUEST_SCREEN_PREVIEW_HEIGHT", 440f),
        )
        val image = BufferedImage(
            (viewport.width * scale).toInt(),
            (viewport.height * scale).toInt(),
            BufferedImage.TYPE_INT_RGB,
        )
        val renderer = PreviewRenderer(image, scale)
        renderer.clear(Color.parse("#1D2430"))
        renderer.roundedRect(
            dev.th7bo.sidequest.ui.geometry.Rect(0f, 0f, viewport.width, viewport.height),
            dev.th7bo.sidequest.ui.geometry.Dp.Zero,
            DarkTheme.tokens.colors.windowBackground,
        )

        val harness = ConfigScreenHarness(screen(), viewport, DarkTheme, target = renderer)
        // Without these the registry draws its missing-icon placeholder, which is a hollow square — and a
        // preview showing placeholders instead of art is a preview of the wrong screen.
        harness.context.icons.registerGlyphIcons(harness.registrationScope)
        harness.runtime.root = ConfigScreenLayoutNode(
            id("preview.layout"),
            harness.controller,
            harness.context,
            onSaveAndClose = {},
            onClose = {},
        )
        harness.frames(FRAMES)

        val file = File(target!!)
        file.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
    }

    /** A screen shaped like the mod's own: a couple of categories, mixed controls. */
    private fun screen() = configScreen(id("preview"), "Sidequest", "Configure how Sidequest looks.") {
        category(id("general"), "General", description = "Appearance and the master switches") {
            section("Appearance", description = "How every Sidequest screen looks", icon = GlyphIconIds.appearance) {
                dropdown(
                    id = id("theme"),
                    title = "Theme",
                    description = "Colour scheme for every Sidequest screen",
                    value = bind({ theme }, { theme = it }),
                    options = listOf(option("dark", "Dark", "dark"), option("light", "Light", "light")),
                )
                toggle(
                    id = id("compact"),
                    title = "Compact mode",
                    description = "Tighter spacing throughout",
                    value = bind({ compact }, { compact = it }),
                )
                decimalSlider(
                    id = id("scale"),
                    title = "HUD scale",
                    value = bind({ hudScale }, { hudScale = it }),
                    range = 0.5f..2f,
                    format = { "%.1fx".format(it) },
                )
            }
            section("Notifications", description = "Toasts and their timing", icon = GlyphIconIds.notifications) {
                toggle(
                    id = id("toasts"),
                    title = "Show toasts",
                    description = "Little cards in the corner when something happens",
                    value = bind({ toasts }, { toasts = it }),
                )
                textField(
                    id = id("name"),
                    title = "Display name",
                    value = bind({ name }, { name = it }),
                    placeholder = "chrooted",
                )
            }
        }
        category(id("features"), "Features", description = "What the mod does") {
            section("Rare drops", description = "What is worth interrupting you for", icon = GlyphIconIds.rareDrop) {
                toggle(
                    id = id("drops"),
                    title = "Announce rare drops",
                    value = bind({ drops }, { drops = it }),
                )
            }
        }
    }

    private var theme = "dark"
    private var compact = false
    private var hudScale = 1f
    private var toasts = true
    private var name = ""
    private var drops = true

    private fun id(path: String) = UiId.of("sidequest", path)

    private fun environmentDimension(name: String, fallback: Float): Float =
        System.getenv(name)?.toFloatOrNull()?.takeIf { it >= MIN_VIEWPORT_DIMENSION } ?: fallback

    private companion object {
        /** Enough frames for the entry animations to settle, so the preview is the resting state. */
        const val FRAMES = 40
        const val MIN_VIEWPORT_DIMENSION = 240f
    }
}
