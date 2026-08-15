package dev.th7bo.sidequest.ui.minecraft.screen

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import dev.th7bo.sidequest.platform.core.profile.SkyCryptUrls
import dev.th7bo.sidequest.platform.minecraft.Mcef
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftTextMeasurer
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftUiRenderer
import dev.th7bo.sidequest.ui.rendering.FrameInfo
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.theme.Theme
import net.dimaskama.mcef.api.MCEFBrowser
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import org.joml.Matrix3x2f
import org.lwjgl.glfw.GLFW
import java.net.URI

/**
 * A web page, inside the game.
 *
 * The browser fills the screen exactly, and that is a decision rather than laziness: MCEF is handed the
 * screen's own mouse coordinates untouched, so there is no offset to get wrong and no chrome for a click to
 * land behind. Anything the viewer needs to know is said by a hint that fades out, and everything it can do
 * is a key — which is also why the page gets the full window rather than the window minus a toolbar.
 *
 * **The browser is kept on SkyCrypt.** Chromium will follow any link it is given, and a general-purpose web
 * browser is a much larger thing to have shipped than a profile viewer — so [tick] watches the address and
 * pulls it back if a click leaves the site. See [SkyCryptUrls.isAllowed], which is where the actual rule
 * lives and where it is tested.
 *
 * This class names MCEF's types, so it must only ever be constructed when the browser mod is present. See
 * [dev.th7bo.sidequest.platform.minecraft.EmbeddedBrowsers], which is the check.
 */
class ProfileBrowserScreen(
    /** Whose stats these are. Shown in the hint, and used to rebuild the address for the leash. */
    private val username: String,
    private val profile: String?,
    private val theme: Theme,
    private val parent: Screen?,
) : Screen(Component.literal("$username on SkyCrypt")) {

    private var browser: MCEFBrowser? = null

    /** Where this screen started, and where the leash returns to. Always an address we built ourselves. */
    private val home: String = SkyCryptUrls.statsUrl(username, profile) ?: "https://${SkyCryptUrls.HOST}/"

    /** Seconds the hint has been fading. Counted in frames, so it is wall-clock rather than tick-rate. */
    private var hintAgeSeconds: Float = 0f
    private var lastFrameNanos: Long = 0

    /** Set when the leash has just pulled the page back, so the hint can say why. */
    private var blockedHost: String? = null
    private var blockedAgeSeconds: Float = 0f

    private var measurer: MinecraftTextMeasurer? = null

    override fun init() {
        if (measurer == null) measurer = MinecraftTextMeasurer(minecraft!!.font)

        val existing = browser
        if (existing == null) {
            browser = Mcef.create(home, width, height)
        } else {
            // A resize re-runs `init`. The browser survives it and is simply told the new size, because
            // recreating it would reload the page and lose the reader's scroll position.
            existing.resize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        }
        browser?.setFocus(true)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
    ) {
        val now = System.nanoTime()
        val delta = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / NANOS_PER_SECOND).toFloat()
        lastFrameNanos = now
        hintAgeSeconds += delta
        if (blockedHost != null) blockedAgeSeconds += delta

        val live = browser
        if (live == null) {
            graphics.fill(0, 0, width, height, theme.tokens.colors.windowBackground.argb)
            drawNotice(graphics, "The browser could not start. Press O to open SkyCrypt outside the game.")
            return
        }

        val view = live.textureView
        if (view != null) {
            graphics.guiRenderState.addGuiElement(
                BlitRenderState(
                    RenderPipelines.GUI_TEXTURED,
                    TextureSetup.singleTexture(
                        view,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                    ),
                    Matrix3x2f(graphics.pose()),
                    0,
                    0,
                    width,
                    height,
                    0.0f,
                    1.0f,
                    0.0f,
                    1.0f,
                    OPAQUE_WHITE,
                    graphics.scissorStack.peek(),
                ),
            )
        } else {
            // Nothing painted yet. A flat panel rather than the world showing through, so the first frames
            // read as "loading" instead of as a broken overlay.
            graphics.fill(0, 0, width, height, theme.tokens.colors.windowBackground.argb)
            drawNotice(graphics, "Loading $username…")
        }

        // The page decides what the cursor looks like — a hand over links, a bar over text.
        graphics.requestCursor(live.cursorType)

        blockedHost?.let {
            if (blockedAgeSeconds < BLOCKED_SECONDS) {
                drawHint(graphics, "$it is outside SkyCrypt — press O to open links in your own browser")
                return
            }
            blockedHost = null
        }
        if (hintAgeSeconds < HINT_SECONDS) drawHint(graphics, HINT)
    }

    /** The fading pill along the bottom. Drawn with the mod's own renderer so it matches every other screen. */
    private fun drawHint(graphics: GuiGraphicsExtractor, message: String) {
        val age = if (blockedHost != null) blockedAgeSeconds else hintAgeSeconds
        val total = if (blockedHost != null) BLOCKED_SECONDS else HINT_SECONDS
        val fade = ((total - age) / FADE_SECONDS).coerceIn(0f, 1f)
        drawPill(graphics, message, fade)
    }

    private fun drawNotice(graphics: GuiGraphicsExtractor, message: String) =
        drawPill(graphics, message, 1f)

    private fun drawPill(graphics: GuiGraphicsExtractor, message: String, opacity: Float) {
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
            val style = theme.textStyle(TextRole.SECONDARY)
            val layout = measure.measure(message, style)
            val padding = theme.tokens.spacing.large.value
            val pillWidth = layout.size.width + padding * 2
            val pillHeight = layout.size.height + theme.tokens.spacing.medium.value * 2
            val left = (width - pillWidth) / 2f
            val top = height - pillHeight - theme.tokens.spacing.xxl.value

            renderer.roundedRect(
                Rect(left, top, pillWidth, pillHeight),
                theme.tokens.radii.pill,
                theme.tokens.colors.elevatedPanelBackground.withAlpha(opacity),
            )
            renderer.text(
                layout,
                Vec2(left + padding, top + theme.tokens.spacing.medium.value),
                theme.textColor(TextRole.SECONDARY).withAlpha(opacity),
            )
        } finally {
            renderer.endFrame()
        }
    }

    /**
     * Keeps the page on SkyCrypt.
     *
     * A poll rather than a navigation callback, because MCEF owns the `CefClient` and does not hand out a
     * seat for a request handler. That makes this a leash rather than a gate: an off-site page has already
     * begun loading by the time it is noticed. Acceptable for what it defends against — a stray click on a
     * footer link turning the viewer into a browser — and stated plainly rather than dressed up as a
     * security boundary it is not.
     */
    override fun tick() {
        val live = browser ?: return
        val current = runCatching { live.cefBrowser.url }.getOrNull() ?: return
        if (!SkyCryptUrls.isJudgeable(current)) return
        if (SkyCryptUrls.isAllowed(current)) return

        blockedHost = runCatching { URI(current).host }.getOrNull() ?: "That link"
        blockedAgeSeconds = 0f
        externalUrl = current
        runCatching { live.cefBrowser.loadURL(home) }
    }

    /** The last address the leash refused, so `O` can offer to open it properly. */
    private var externalUrl: String? = null

    override fun removed() {
        // Chromium holds native memory and a render process; leaving one behind per screen open is a leak
        // measured in hundreds of megabytes.
        runCatching { browser?.close() }
        browser = null
    }

    /**
     * `setScreenAndShow`, not `gui.setScreen`.
     *
     * The two versions disagree about where that method lives — 26.1.2 has it on `Minecraft`, 26.2 moved it
     * to `Gui` — and `setScreenAndShow` is the one spelling both of them have. MCEF's own example uses the
     * 26.2 form, which is why this is worth a note rather than looking like a stylistic choice.
     */
    override fun onClose() {
        // `setScreenAndShow` will not take null, so "back to no screen at all" goes through the base
        // implementation — which is the one door both versions agree on for closing to the game.
        val back = parent
        if (back == null) super.onClose() else minecraft!!.setScreenAndShow(back)
    }

    override fun isPauseScreen(): Boolean = false

    // -- input ---------------------------------------------------------------

    override fun keyPressed(event: KeyEvent): Boolean {
        val live = browser
        when {
            // Handled here and deliberately not forwarded: the page must not be able to swallow the only
            // key that gets somebody out of it.
            event.key() == GLFW.GLFW_KEY_ESCAPE -> {
                onClose()
                return true
            }
            event.key() == GLFW.GLFW_KEY_O -> {
                openOutside()
                return true
            }
            event.key() == GLFW.GLFW_KEY_F5 -> {
                runCatching { live?.cefBrowser?.reload() }
                return true
            }
            event.key() == GLFW.GLFW_KEY_BACKSPACE && live?.cefBrowser?.canGoBack() == true -> {
                runCatching { live.cefBrowser.goBack() }
                return true
            }
        }
        live?.onKeyPressed(event)
        return true
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        browser?.onKeyReleased(event)
        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        browser?.onCharTyped(event)
        return true
    }

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        browser?.onMouseClicked(event, doubled)
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        browser?.onMouseReleased(event)
        return true
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        browser?.onMouseMoved(mouseX.toInt(), mouseY.toInt())
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        browser?.onMouseScrolled(mouseX.toInt(), mouseY.toInt(), verticalAmount)
        return true
    }

    /**
     * Hands the current page to the player's real browser.
     *
     * The address is re-checked rather than trusted, even though it came from our own leash a moment ago:
     * this is the one place the mod asks the operating system to open something, and the operating system
     * will open whatever it is given.
     */
    private fun openOutside() {
        val candidate = externalUrl ?: runCatching { browser?.cefBrowser?.url }.getOrNull() ?: home
        val target = if (SkyCryptUrls.isAllowed(candidate) || candidate == externalUrl) candidate else home
        runCatching { Util.getPlatform().openUri(URI(target)) }
    }

    private companion object {
        const val HINT = "Esc closes · O opens this in your browser · F5 reloads · Backspace goes back"

        /** How long the hint stays before it fades, and how long the fade takes. */
        const val HINT_SECONDS = 6f
        const val BLOCKED_SECONDS = 5f
        const val FADE_SECONDS = 1.5f

        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val OPAQUE_WHITE = -1
    }
}
