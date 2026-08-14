package dev.th7bo.sidequest.ui.minecraft.screen

import dev.th7bo.sidequest.SidequestSettings
import dev.th7bo.sidequest.ui.components.background.NebulaPainter
import dev.th7bo.sidequest.ui.components.background.NebulaPalette
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftTextMeasurer
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftUiRenderer
import dev.th7bo.sidequest.ui.rendering.FrameInfo
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen

/**
 * The nebula behind the main menu.
 *
 * Replaces Minecraft's panorama rather than drawing over it, which is why the hook is on the panorama itself:
 * drawing on top would still pay for the panorama's rendering every frame, and any translucency would show
 * the world sliding about underneath.
 *
 * **It refuses to draw whenever it is not certain it should.** Off in the settings, a screen that is not
 * a screen that is not based on the vanilla title screen, or no window to measure — each returns false and
 * Minecraft's own panorama runs untouched. Vanilla-derived screens still call the same panorama hook, and
 * accepting those is what lets lightweight decorators such as PackCore keep their buttons while Sidequest
 * owns the background.
 */
public object TitleScreenBackground {

    /**
     * Draws the sky, or declines.
     *
     * `@JvmStatic` because the caller is a mixin, and Java sees a Kotlin object's members as instance methods
     * on an `INSTANCE` field otherwise — which works and reads like a mistake at the call site.
     *
     * @return true when it painted, meaning the caller should skip the panorama.
     */
    @JvmStatic
    public fun paint(screen: Screen, graphics: GuiGraphicsExtractor): Boolean {
        if (!SidequestSettings.TitleScreen.isEnabled) return false
        // A vanilla title screen or a decorator built on it. Fully custom screens do not reach this branch,
        // so a mod with its own rendering pipeline keeps its own background.
        if (screen !is TitleScreen) return false

        val client = Minecraft.getInstance()
        val window = client.window ?: return false
        val width = window.guiScaledWidth.toFloat()
        val height = window.guiScaledHeight.toFloat()
        if (width <= 0f || height <= 0f) return false

        val bounds = Rect(0f, 0f, width, height)
        val renderer = MinecraftUiRenderer(
            graphics = graphics,
            font = client.font,
            textMeasurer = measurerFor(client.font),
            frame = FrameInfo(
                viewport = Rect.of(Vec2.Zero, Size(width, height)),
                // Neither is read by the painter, which takes its clock as an argument. Passed honestly
                // rather than invented, because a fake frame index that something later relied on would be
                // a very quiet bug.
                deltaSeconds = 0f,
                frameIndex = 0L,
                guiScale = window.guiScale.toFloat(),
            ),
        )

        NebulaPainter.paint(renderer, bounds, time = clock(), palette = palette())
        return true
    }

    /**
     * A measurer for the current font, kept between frames.
     *
     * The painter draws no text and never asks it anything — but the renderer requires one, and building a
     * fresh measurer sixty times a second to satisfy a parameter nobody reads would be a caching layer thrown
     * away every frame. Rebuilt when the font instance changes, which is what a resource reload produces.
     */
    private var measurer: Pair<net.minecraft.client.gui.Font, MinecraftTextMeasurer>? = null

    private fun measurerFor(font: net.minecraft.client.gui.Font): MinecraftTextMeasurer {
        measurer?.takeIf { it.first === font }?.let { return it.second }
        return MinecraftTextMeasurer(font).also { measurer = font to it }
    }

    /**
     * The nebula's clock, in seconds.
     *
     * Wall time rather than the render tick, because the title screen has no world and therefore no tick to
     * ride on. Held still when the animation is off: the painter has no reduced-motion branch of its own —
     * freezing the clock *is* reduced motion, which keeps the still and moving cases on one code path.
     */
    private fun clock(): Float =
        if (SidequestSettings.TitleScreen.animate) (System.nanoTime() - origin) / NANOS_PER_SECOND else 0f

    private fun palette(): NebulaPalette = NebulaPalette(
        // Forced opaque: the sky is what everything else is drawn against, and a palette entry somebody made
        // translucent in the picker would show the black void behind it rather than reading as subtle.
        deep = SidequestSettings.TitleScreen.deepColour.withAlpha(1f),
        cloud = SidequestSettings.TitleScreen.cloudColour.withAlpha(1f),
        highlight = SidequestSettings.TitleScreen.highlightColour.withAlpha(1f),
    )

    /**
     * When the mod started.
     *
     * Relative rather than absolute, so the drift starts from zero instead of from whatever the noise
     * happens to look like eighteen thousand seconds in. It also keeps the float precise: seconds since the
     * epoch as a `Float` has lost most of its fractional bits.
     */
    private val origin: Long = System.nanoTime()

    private const val NANOS_PER_SECOND = 1_000_000_000f
}
