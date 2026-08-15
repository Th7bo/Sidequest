package dev.th7bo.sidequest.ui.minecraft.screen

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import dev.th7bo.sidequest.SidequestSettings
import dev.th7bo.sidequest.platform.core.profile.SkyCryptUrls
import dev.th7bo.sidequest.platform.minecraft.Mcef
import dev.th7bo.sidequest.ui.components.ProfileWindowChrome
import dev.th7bo.sidequest.ui.components.ProfileWindowLayout
import dev.th7bo.sidequest.ui.components.ProfileWindowState
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftTextMeasurer
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftUiRenderer
import dev.th7bo.sidequest.ui.rendering.FrameInfo
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
import kotlin.math.ln

/**
 * A web page in a window, with chrome the mod drew itself.
 *
 * What is left in this class is the part that needs a running game: a browser, a texture, and input. The
 * frame around it is [ProfileWindowChrome], which lives in `ui-components` where it can be rendered to a
 * PNG and looked at — this screen cannot, because a browser is precisely the thing a test cannot have.
 *
 * **The browser is sized in framebuffer pixels and positioned in GUI units, and those are different
 * coordinate systems.** At a GUI scale of 3 the content area might be 600×300 GUI units and 1800×900
 * pixels; the texture is made at the second size, blitted into the first, and every mouse coordinate has to
 * be moved from one to the other. [inBrowserSpace] is that conversion and every input path goes through it.
 *
 * **The browser is kept on SkyCrypt.** Chromium will follow any link it is given, and a general-purpose web
 * browser is a much larger thing to have shipped than a profile viewer — so [tick] watches the address and
 * pulls it back if a click leaves the site. See [SkyCryptUrls.isAllowed], which is where the rule lives and
 * where it is tested.
 *
 * This class names MCEF's types, so it must only ever be constructed when the browser mod is present. See
 * [dev.th7bo.sidequest.platform.minecraft.EmbeddedBrowsers], which is the check.
 */
class ProfileBrowserScreen(
    username: String,
    private val profile: String?,
    private val theme: Theme,
    private val parent: Screen?,
) : Screen(Component.literal("$username on SkyCrypt")) {

    private var browser: MCEFBrowser? = null

    /** Whose page is showing. A `var` because the search box can change it without reopening the screen. */
    private var username: String = username

    /** Where the leash returns to. Always an address this mod built, never one the page supplied. */
    private var home: String = SkyCryptUrls.statsUrl(username, profile) ?: "https://${SkyCryptUrls.HOST}/"

    /** Full-bleed, for when the frame is in the way rather than helping. */
    private var isMaximised: Boolean = false

    private var layout: ProfileWindowLayout = ProfileWindowLayout.of(Rect(0f, 0f, 1f, 1f), false)

    private var searchText: String = username
    private var isSearchFocused: Boolean = false
    private var caretSeconds: Float = 0f

    private var hintAgeSeconds: Float = 0f
    private var lastFrameNanos: Long = 0

    private var notice: String? = null
    private var noticeAgeSeconds: Float = 0f
    private var externalUrl: String? = null

    private var measurer: MinecraftTextMeasurer? = null

    override fun init() {
        if (measurer == null) measurer = MinecraftTextMeasurer(minecraft!!.font)
        layout = ProfileWindowLayout.of(Rect(0f, 0f, width.toFloat(), height.toFloat()), isMaximised)

        val existing = browser
        if (existing == null) {
            browser = Mcef.create(home, contentPixelWidth(), contentPixelHeight())
        } else {
            // A resize re-runs `init`. The browser survives it and is simply told the new size, because
            // recreating it would reload the page and lose the reader's scroll position.
            existing.resize(contentPixelWidth(), contentPixelHeight())
        }
        browser?.setFocus(!isSearchFocused)
        applyZoom()
    }

    /** Hit-testing in the coordinates the screen hands out. `Rect.contains` wants a point; this is the pair. */
    private fun Rect.holds(x: Float, y: Float): Boolean = contains(Vec2(x, y))

    /** The content area in framebuffer pixels, which is what the browser is actually made at. */
    private fun contentPixelWidth(): Int =
        (layout.content.width * pixelsPerGuiUnitX()).toInt().coerceAtLeast(1)

    private fun contentPixelHeight(): Int =
        (layout.content.height * pixelsPerGuiUnitY()).toInt().coerceAtLeast(1)

    /**
     * How far a GUI coordinate is from a browser one.
     *
     * Taken as a ratio of the two sizes the window reports rather than from `guiScale`, because the integer
     * scale does not divide the window exactly — Minecraft rounds the GUI-scaled size, so at some window
     * sizes `width * guiScale` is a pixel or two wide of the framebuffer. Over a 1920-pixel span that
     * rounding is a visible drift between where the cursor is drawn and where the page thinks it is.
     */
    private fun pixelsPerGuiUnitX(): Double {
        val window = minecraft!!.window
        return window.width.toDouble() / window.guiScaledWidth.coerceAtLeast(1)
    }

    private fun pixelsPerGuiUnitY(): Double {
        val window = minecraft!!.window
        return window.height.toDouble() / window.guiScaledHeight.coerceAtLeast(1)
    }

    /**
     * A screen point in the browser's own coordinates.
     *
     * Two steps, and both are needed: subtract where the content area starts, then scale GUI units to
     * pixels. Skipping the first puts every click low and to the right by the size of the frame; skipping
     * the second puts it at a fraction of the distance across the page it was aimed at.
     */
    private fun inBrowserSpace(x: Double, y: Double): Pair<Int, Int> = Pair(
        ((x - layout.content.x) * pixelsPerGuiUnitX()).toInt(),
        ((y - layout.content.y) * pixelsPerGuiUnitY()).toInt(),
    )

    /** The same click, moved. [MouseButtonEvent] is a record, so this rebuilds one rather than mutating it. */
    private fun inBrowserSpace(event: MouseButtonEvent): MouseButtonEvent {
        val (x, y) = inBrowserSpace(event.x(), event.y())
        return MouseButtonEvent(x.toDouble(), y.toDouble(), event.buttonInfo())
    }

    /**
     * Applies the configured page zoom.
     *
     * Chromium counts zoom in multiplicative steps of 1.2 rather than in percent, so the setting is
     * converted rather than passed through — level 0 is 100%, level 1 is 120%, and so on.
     */
    private fun applyZoom() {
        val live = browser ?: return
        val percent = SidequestSettings.Profiles.zoomPercent.toDouble().coerceAtLeast(MIN_ZOOM_PERCENT)
        val level = ln(percent / PERCENT_FULL) / ln(CHROMIUM_ZOOM_STEP)
        runCatching { live.cefBrowser.zoomLevel = level }
    }

    // -- drawing --------------------------------------------------------------

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
        caretSeconds += delta
        if (notice != null) noticeAgeSeconds += delta

        // The world behind, dimmed. The window no longer covers everything, so what surrounds it matters.
        graphics.fill(0, 0, width, height, theme.tokens.colors.scrim.argb)

        val measure = measurer ?: return
        val renderer = MinecraftUiRenderer(
            graphics = graphics,
            font = minecraft!!.font,
            textMeasurer = measure,
            frame = FrameInfo(
                viewport = Rect(0f, 0f, width.toFloat(), height.toFloat()),
                deltaSeconds = delta,
                frameIndex = 0,
                guiScale = minecraft!!.window.guiScale.toFloat(),
            ),
        )

        try {
            ProfileWindowChrome.paintFrame(renderer, theme, layout)
            if (browser?.textureView == null) {
                ProfileWindowChrome.paintPagePlaceholder(renderer, theme, layout)
            }
            // Between the frame and the bar: the page must sit above the panel it is inset into, and below
            // the notice pill that is drawn over it.
            blitPage(graphics)
            ProfileWindowChrome.paintBar(
                renderer,
                theme,
                layout,
                ProfileWindowState(
                    searchText = searchText,
                    isSearchFocused = isSearchFocused,
                    isCaretVisible = (caretSeconds % CARET_PERIOD) < CARET_PERIOD / 2f,
                    pointer = Vec2(mouseX.toFloat(), mouseY.toFloat()),
                ),
            )
            paintNotices(renderer)
        } finally {
            renderer.endFrame()
        }

        browser?.let { graphics.requestCursor(it.cursorType) }
    }

    /** The page. A texture the size of the content area in pixels, drawn into it in GUI units. */
    private fun blitPage(graphics: GuiGraphicsExtractor) {
        val view = browser?.textureView ?: return
        graphics.guiRenderState.addGuiElement(
            BlitRenderState(
                RenderPipelines.GUI_TEXTURED,
                TextureSetup.singleTexture(
                    view,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                ),
                Matrix3x2f(graphics.pose()),
                layout.content.x.toInt(),
                layout.content.y.toInt(),
                layout.content.right.toInt(),
                layout.content.bottom.toInt(),
                0.0f,
                1.0f,
                0.0f,
                1.0f,
                OPAQUE_WHITE,
                graphics.scissorStack.peek(),
            ),
        )
    }

    private fun paintNotices(renderer: MinecraftUiRenderer) {
        notice?.let {
            if (noticeAgeSeconds < NOTICE_SECONDS) {
                ProfileWindowChrome.paintNotice(renderer, theme, layout, it, 1f)
                return
            }
            notice = null
        }
        if (hintAgeSeconds < HINT_SECONDS) {
            val fade = ((HINT_SECONDS - hintAgeSeconds) / FADE_SECONDS).coerceIn(0f, 1f)
            ProfileWindowChrome.paintNotice(renderer, theme, layout, HINT, fade)
        }
    }

    // -- the leash ------------------------------------------------------------

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

        val host = runCatching { URI(current).host }.getOrNull() ?: "That link"
        show("$host is outside SkyCrypt — Ctrl+O opens links in your browser")
        externalUrl = current
        runCatching { live.cefBrowser.loadURL(home) }
    }

    private fun show(message: String) {
        notice = message
        noticeAgeSeconds = 0f
    }

    override fun removed() {
        // Chromium holds native memory and a render process; leaving one behind per screen open is a leak
        // measured in hundreds of megabytes.
        runCatching { browser?.close() }
        browser = null
    }

    override fun onClose() {
        // `setScreenAndShow` will not take null, so "back to no screen at all" goes through the base
        // implementation — which is the one door both 26.1.2 and 26.2 agree on.
        val back = parent
        if (back == null) super.onClose() else minecraft!!.setScreenAndShow(back)
    }

    override fun isPauseScreen(): Boolean = false

    // -- input ----------------------------------------------------------------

    /**
     * Shortcuts take a modifier, deliberately.
     *
     * An earlier version claimed bare `O`, `F5` and `Backspace`, which is fine right up until somebody types
     * into SkyCrypt's own search box and the letter `o` opens their desktop browser. Anything that could be
     * a character has to reach the page.
     */
    override fun keyPressed(event: KeyEvent): Boolean {
        if (isSearchFocused) return searchKeyPressed(event)

        val live = browser
        val control = (event.modifiers() and GLFW.GLFW_MOD_CONTROL) != 0
        val alt = (event.modifiers() and GLFW.GLFW_MOD_ALT) != 0
        when {
            // Handled here and never forwarded: the page must not be able to swallow the one key that gets
            // somebody out of it.
            event.key() == GLFW.GLFW_KEY_ESCAPE -> {
                onClose()
                return true
            }
            control && event.key() == GLFW.GLFW_KEY_O -> {
                openOutside()
                return true
            }
            control && event.key() == GLFW.GLFW_KEY_L -> {
                focusSearch()
                return true
            }
            event.key() == GLFW.GLFW_KEY_F5 -> {
                runCatching { live?.cefBrowser?.reload() }
                return true
            }
            alt && event.key() == GLFW.GLFW_KEY_LEFT && live?.cefBrowser?.canGoBack() == true -> {
                runCatching { live.cefBrowser.goBack() }
                return true
            }
        }
        live?.onKeyPressed(event)
        return true
    }

    private fun searchKeyPressed(event: KeyEvent): Boolean {
        when (event.key()) {
            // Out of the box, not out of the screen. A second press then closes, which is what somebody
            // pressing escape twice expects.
            GLFW.GLFW_KEY_ESCAPE -> {
                blurSearch()
                searchText = username
            }
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> submitSearch()
            GLFW.GLFW_KEY_BACKSPACE -> searchText = searchText.dropLast(1)
        }
        return true
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        if (!isSearchFocused) browser?.onKeyReleased(event)
        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (!isSearchFocused) {
            browser?.onCharTyped(event)
            return true
        }
        val typed = event.codepoint().toChar()
        // Filtered to what a Minecraft name can contain, so the box cannot hold something that could never
        // be looked up. `SkyCryptUrls` still validates on submit; this only stops the field accepting
        // characters it would then refuse.
        if (searchText.length < MAX_NAME && (typed.isLetterOrDigit() || typed == '_')) {
            searchText += typed
        }
        return true
    }

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        val x = event.x().toFloat()
        val y = event.y().toFloat()

        when {
            layout.close.holds(x, y) -> onClose()
            layout.expand.holds(x, y) -> toggleMaximised()
            layout.search.holds(x, y) -> focusSearch()
            layout.content.holds(x, y) -> {
                if (isSearchFocused) blurSearch()
                browser?.onMouseClicked(inBrowserSpace(event), doubled)
            }
            // Outside the window entirely. Clicking the dimmed surround closes, which is what a modal
            // window trains people to expect.
            !layout.window.holds(x, y) -> onClose()
            else -> if (isSearchFocused) blurSearch()
        }
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (layout.content.holds(event.x().toFloat(), event.y().toFloat())) {
            browser?.onMouseReleased(inBrowserSpace(event))
        }
        return true
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        val (x, y) = inBrowserSpace(mouseX, mouseY)
        browser?.onMouseMoved(x, y)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        if (!layout.content.holds(mouseX.toFloat(), mouseY.toFloat())) return true
        val (x, y) = inBrowserSpace(mouseX, mouseY)
        browser?.onMouseScrolled(x, y, verticalAmount)
        return true
    }

    // -- actions --------------------------------------------------------------

    private fun focusSearch() {
        isSearchFocused = true
        caretSeconds = 0f
        // The page loses focus, or it would keep drawing its own caret and taking keys.
        browser?.setFocus(false)
    }

    private fun blurSearch() {
        isSearchFocused = false
        browser?.setFocus(true)
    }

    /**
     * Loads whatever is in the box.
     *
     * The name is validated here as well as in the feature, because this is a second door into the same
     * house: a name typed into the search box never passed through the command.
     */
    private fun submitSearch() {
        val typed = searchText.trim()
        val url = SkyCryptUrls.statsUrl(typed) ?: run {
            // Refused rather than loaded. The box keeps what was typed so it can be corrected.
            show("\"$typed\" is not a Minecraft username")
            return
        }
        username = typed
        home = url
        blurSearch()
        runCatching { browser?.cefBrowser?.loadURL(url) }
    }

    private fun toggleMaximised() {
        isMaximised = !isMaximised
        layout = ProfileWindowLayout.of(Rect(0f, 0f, width.toFloat(), height.toFloat()), isMaximised)
        browser?.resize(contentPixelWidth(), contentPixelHeight())
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
        const val HINT = "Esc closes · Ctrl+L searches · Ctrl+O opens in your browser · F5 reloads"

        const val MAX_NAME = 16
        const val CARET_PERIOD = 1f

        const val HINT_SECONDS = 6f
        const val NOTICE_SECONDS = 5f
        const val FADE_SECONDS = 1.5f

        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val OPAQUE_WHITE = -1

        /** Chromium's zoom is multiplicative in steps of this, which is why a percentage needs converting. */
        const val CHROMIUM_ZOOM_STEP = 1.2
        const val PERCENT_FULL = 100.0

        /** Below this the conversion goes to negative infinity, and a zero would black the page out. */
        const val MIN_ZOOM_PERCENT = 25.0
    }
}
