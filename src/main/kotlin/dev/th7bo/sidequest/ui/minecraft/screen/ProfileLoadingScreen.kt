package dev.th7bo.sidequest.ui.minecraft.screen

import dev.th7bo.sidequest.platform.minecraft.EmbeddedBrowsers
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftTextMeasurer
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftUiRenderer
import dev.th7bo.sidequest.ui.rendering.FrameInfo
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.theme.Theme
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * Chromium coming up, with something to look at while it does.
 *
 * The first time anybody opens a profile on a fresh install, MCEF downloads and unpacks a couple of hundred
 * megabytes. That is a long time to show nothing, and a frozen screen is how a working feature gets reported
 * as broken — so the stage and the percentage are on screen, in the mod's own vocabulary.
 *
 * **Names no MCEF type.** It asks [EmbeddedBrowsers], which answers in this mod's words, and only constructs
 * [ProfileBrowserScreen] once the answer is [EmbeddedBrowsers.Startup.Ready]. That ordering is what lets
 * this screen exist on a client with no browser mod at all.
 */
class ProfileLoadingScreen(
    private val username: String,
    private val profile: String?,
    private val theme: Theme,
    private val parent: Screen?,
    /** Opens the page outside the game. Supplied rather than reached for, so the fallback stays one place. */
    private val openOutside: () -> Unit,
) : Screen(Component.literal("Opening $username")) {

    private var measurer: MinecraftTextMeasurer? = null

    override fun init() {
        if (measurer == null) measurer = MinecraftTextMeasurer(minecraft!!.font)
        EmbeddedBrowsers.beginStartup()
    }

    override fun tick() {
        // Swapped on a tick rather than in the frame: replacing the screen mid-render is how a screen ends up
        // drawing half of itself and half of its successor.
        if (EmbeddedBrowsers.startup() is EmbeddedBrowsers.Startup.Ready) {
            // `setScreenAndShow` is the one spelling both 26.1.2 and 26.2 have — see `ProfileBrowserScreen`.
            minecraft!!.setScreenAndShow(ProfileBrowserScreen(username, profile, theme, parent))
        }
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
    ) {
        graphics.fill(0, 0, width, height, theme.tokens.colors.scrim.argb)

        val measure = measurer ?: return
        val renderer = MinecraftUiRenderer(
            graphics = graphics,
            font = minecraft!!.font,
            textMeasurer = measure,
            frame = FrameInfo(
                viewport = Rect(0f, 0f, width.toFloat(), height.toFloat()),
                deltaSeconds = 0f,
                frameIndex = 0,
                guiScale = minecraft!!.window.guiScale.toFloat(),
            ),
        )

        try {
            val startup = EmbeddedBrowsers.startup()
            val heading = "Opening $username on SkyCrypt"
            val detail = when (startup) {
                is EmbeddedBrowsers.Startup.Absent ->
                    "The browser mod is not installed. Press O to open this in your own browser."
                is EmbeddedBrowsers.Startup.Failed ->
                    "The browser could not start: ${startup.reason}. Press O to open it outside the game."
                is EmbeddedBrowsers.Startup.Working -> startup.stage
                EmbeddedBrowsers.Startup.Ready -> "Ready"
            }
            val percent = (startup as? EmbeddedBrowsers.Startup.Working)?.percent

            val panelWidth = minOf(width - theme.tokens.spacing.xxl.value * 2, PANEL_WIDTH)
            val panelHeight = PANEL_HEIGHT
            val left = (width - panelWidth) / 2f
            val top = (height - panelHeight) / 2f

            renderer.roundedRect(
                Rect(left, top, panelWidth, panelHeight),
                theme.tokens.radii.large,
                theme.tokens.colors.elevatedPanelBackground,
            )

            val pad = theme.tokens.spacing.xl.value
            val titleLayout = measure.measure(heading, theme.textStyle(TextRole.TITLE), panelWidth - pad * 2)
            renderer.text(titleLayout, Vec2(left + pad, top + pad), theme.textColor(TextRole.TITLE))

            val detailLayout = measure.measure(detail, theme.textStyle(TextRole.SECONDARY), panelWidth - pad * 2)
            val detailTop = top + pad + titleLayout.size.height + theme.tokens.spacing.medium.value
            renderer.text(detailLayout, Vec2(left + pad, detailTop), theme.textColor(TextRole.SECONDARY))

            if (percent != null) {
                val barTop = detailTop + detailLayout.size.height + theme.tokens.spacing.large.value
                val barWidth = panelWidth - pad * 2
                renderer.roundedRect(
                    Rect(left + pad, barTop, barWidth, BAR_HEIGHT),
                    theme.tokens.radii.pill,
                    theme.tokens.colors.hoverBackground,
                )
                // Only the filled part, and only when there is one: a zero-width rounded rectangle at a pill
                // radius still paints two overlapping half-circles, which reads as a dot at 0%.
                val filled = barWidth * (percent / PERCENT_FULL)
                if (filled > BAR_HEIGHT) {
                    renderer.roundedRect(
                        Rect(left + pad, barTop, filled, BAR_HEIGHT),
                        theme.tokens.radii.pill,
                        theme.tokens.colors.accent,
                    )
                }
            }
        } finally {
            renderer.endFrame()
        }
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_O) {
            openOutside()
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        val back = parent
        if (back == null) super.onClose() else minecraft!!.setScreenAndShow(back)
    }

    override fun isPauseScreen(): Boolean = false

    private companion object {
        const val PANEL_WIDTH = 260f
        const val PANEL_HEIGHT = 84f
        const val BAR_HEIGHT = 6f
        const val PERCENT_FULL = 100f
    }
}
