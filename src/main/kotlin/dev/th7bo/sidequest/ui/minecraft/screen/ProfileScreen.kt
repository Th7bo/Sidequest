package dev.th7bo.sidequest.ui.minecraft.screen

import dev.th7bo.sidequest.platform.core.profile.SkyCryptUrls
import dev.th7bo.sidequest.protocol.ApiErrorCode
import dev.th7bo.sidequest.protocol.ApiResult
import dev.th7bo.sidequest.protocol.ProfileProgress
import dev.th7bo.sidequest.protocol.SkyBlockProfile
import dev.th7bo.sidequest.ui.components.ProfileWindowChrome
import dev.th7bo.sidequest.ui.components.ProfileWindowLayout
import dev.th7bo.sidequest.ui.components.ProfileWindowState
import dev.th7bo.sidequest.ui.geometry.Dp
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftTextMeasurer
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftUiRenderer
import dev.th7bo.sidequest.ui.rendering.FrameInfo
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.theme.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.util.Locale
import kotlin.math.roundToInt

/** The native, crash-free SkyBlock profile viewer. */
public class ProfileScreen(
    username: String,
    profile: String?,
    private val theme: Theme,
    private val parent: Screen?,
    private val fetch: suspend (String, String?) -> ApiResult<SkyBlockProfile>,
    private val quickSwitch: () -> List<String> = { emptyList() },
    private val remember: (String) -> Unit = {},
) : Screen(Component.literal("$username's SkyBlock profile")) {

    private sealed interface ViewState {
        data object Loading : ViewState
        data class Ready(val profile: SkyBlockProfile) : ViewState
        data class Failed(val message: String) : ViewState
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var state: ViewState = ViewState.Loading
    private var lookupJob: Job? = null
    private var username = username
    private var requestedProfile = profile
    private var searchText = username
    private var searchFocused = false
    private var isMaximised = false
    private var caretSeconds = 0f
    private var lastFrameNanos = 0L
    private var measurer: MinecraftTextMeasurer? = null
    private var layout = ProfileWindowLayout.of(Rect(0f, 0f, 1f, 1f), false)

    override fun init() {
        if (measurer == null) measurer = MinecraftTextMeasurer(minecraft.font)
        updateLayout()
        if (state is ViewState.Loading && lookupJob == null) fetchCurrent()
    }

    private fun updateLayout() {
        layout = ProfileWindowLayout.of(
            Rect(0f, 0f, width.toFloat(), height.toFloat()),
            isMaximised,
            hasQuickSwitch = quickSwitch().size > 1,
        )
    }

    private fun fetchCurrent() {
        lookupJob?.cancel()
        state = ViewState.Loading
        val name = username
        val profile = requestedProfile
        lookupJob = scope.launch {
            val result = fetch(name, profile)
            minecraft.schedule {
                if (username != name || requestedProfile != profile) return@schedule
                lookupJob = null
                state = when (result) {
                    is ApiResult.Success -> ViewState.Ready(result.value)
                    is ApiResult.Failure -> ViewState.Failed(friendlyError(result.error.code))
                }
            }
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val now = System.nanoTime()
        val delta = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1_000_000_000.0).toFloat()
        lastFrameNanos = now
        caretSeconds += delta

        graphics.fill(0, 0, width, height, theme.tokens.colors.scrim.argb)
        val measure = measurer ?: return
        val renderer = MinecraftUiRenderer(
            graphics,
            minecraft.font,
            measure,
            FrameInfo(
                viewport = Rect(0f, 0f, width.toFloat(), height.toFloat()),
                deltaSeconds = delta,
                frameIndex = 0,
                guiScale = minecraft.window.guiScale.toFloat(),
            ),
        )
        try {
            ProfileWindowChrome.paintFrame(renderer, theme, layout)
            ProfileWindowChrome.paintPagePlaceholder(renderer, theme, layout)
            renderer.pushClip(layout.content)
            try {
                paintProfile(renderer)
            } finally {
                renderer.popClip()
            }
            ProfileWindowChrome.paintBar(
                renderer,
                theme,
                layout,
                ProfileWindowState(
                    searchText = searchText,
                    isSearchFocused = searchFocused,
                    isCaretVisible = (caretSeconds % 1f) < .5f,
                    pointer = Vec2(mouseX.toFloat(), mouseY.toFloat()),
                    title = "SkyBlock Profiles",
                    credit = "official Hypixel API",
                ),
            )
        } finally {
            renderer.endFrame()
        }
    }

    private fun paintProfile(renderer: MinecraftUiRenderer) {
        val content = layout.content
        when (val current = state) {
            ViewState.Loading -> centredMessage(renderer, "Loading $username…", TextRole.SECONDARY)
            is ViewState.Failed -> {
                centredMessage(renderer, current.message, TextRole.SECONDARY)
                val retry = renderer.textMeasurer.measure("Press R to retry", theme.textStyle(TextRole.CAPTION))
                renderer.text(
                    retry,
                    Vec2(content.x + (content.width - retry.size.width) / 2f, content.y + content.height / 2f + 12f),
                    theme.textColor(TextRole.CAPTION),
                )
            }
            is ViewState.Ready -> paintReady(renderer, current.profile)
        }
    }

    private fun centredMessage(renderer: MinecraftUiRenderer, text: String, role: TextRole) {
        val measured = renderer.textMeasurer.measure(text, theme.textStyle(role), layout.content.width - 32f)
        renderer.text(
            measured,
            Vec2(
                layout.content.x + (layout.content.width - measured.size.width) / 2f,
                layout.content.y + (layout.content.height - measured.size.height) / 2f,
            ),
            theme.textColor(role),
        )
    }

    private fun paintReady(renderer: MinecraftUiRenderer, profile: SkyBlockProfile) {
        val c = layout.content
        val pad = 14f
        val left = c.x + pad
        val right = c.right - pad
        var y = c.y + 12f

        text(renderer, profile.username, left, y, TextRole.TITLE)
        val mode = profile.gameMode?.replaceFirstChar(Char::uppercase)?.let { " · $it" }.orEmpty()
        text(renderer, "${profile.profileName}$mode${if (profile.selected) " · selected" else ""}", left, y + 13f, TextRole.SECONDARY)
        profile.skyBlockLevel?.let {
            val value = "SkyBlock ${formatLevel(it)}"
            val measured = renderer.textMeasurer.measure(value, theme.textStyle(TextRole.LABEL))
            renderer.text(measured, Vec2(right - measured.size.width, y), theme.tokens.colors.accent)
        }
        y += 34f

        val money = listOfNotNull(
            profile.purse?.let { "Purse  ${formatNumber(it)}" },
            profile.bank?.let { "Bank  ${formatNumber(it)}" },
        ).joinToString("     ").ifEmpty { "Coin balances hidden" }
        panel(renderer, Rect(left, y, right - left, 25f))
        text(renderer, money, left + 9f, y + 8f, TextRole.LABEL)
        y += 34f

        text(renderer, "Skills", left, y, TextRole.LABEL)
        y += 14f
        if (profile.skills.isEmpty()) {
            text(renderer, "Skill API disabled", left, y, TextRole.SECONDARY)
            y += 15f
        } else {
            val columns = when {
                c.width >= 420f -> 4
                c.width >= 270f -> 3
                else -> 2
            }
            val gap = 7f
            val cellWidth = (right - left - gap * (columns - 1)) / columns
            for ((index, skill) in profile.skills.withIndex()) {
                val column = index % columns
                val row = index / columns
                val box = Rect(left + column * (cellWidth + gap), y + row * 25f, cellWidth, 20f)
                panel(renderer, box)
                text(renderer, skill.name, box.x + 7f, box.y + 4f, TextRole.CAPTION)
                val level = skill.level.toString()
                val measured = renderer.textMeasurer.measure(level, theme.textStyle(TextRole.LABEL))
                renderer.text(measured, Vec2(box.right - measured.size.width - 7f, box.y + 4f), theme.tokens.colors.accent)
                val bar = Rect(box.x + 7f, box.bottom - 3f, box.width - 14f, 2f)
                renderer.roundedRect(bar, Dp(1f), theme.tokens.colors.border)
                renderer.roundedRect(bar.copy(width = bar.width * skill.progress.toFloat()), Dp(1f), theme.tokens.colors.accent)
            }
            y += ((profile.skills.size + columns - 1) / columns) * 25f + 4f
        }

        val remaining = c.bottom - y - 8f
        if (remaining >= 35f) {
            val half = (right - left - 8f) / 2f
            paintProgress(renderer, "Slayers", profile.slayers, Rect(left, y, half, remaining))
            paintProgress(renderer, "Dungeons", profile.dungeons, Rect(left + half + 8f, y, half, remaining))
        }
    }

    private fun paintProgress(renderer: MinecraftUiRenderer, title: String, values: List<ProfileProgress>, area: Rect) {
        panel(renderer, area)
        text(renderer, title, area.x + 8f, area.y + 7f, TextRole.LABEL)
        if (values.isEmpty()) {
            text(renderer, "API disabled", area.x + 8f, area.y + 20f, TextRole.CAPTION)
            return
        }
        var y = area.y + 20f
        for (value in values.take(4)) {
            val suffix = value.level?.let { " ${it}" } ?: value.experience?.let { "  ${formatNumber(it)} XP" }.orEmpty()
            text(renderer, value.name + suffix, area.x + 8f, y, TextRole.CAPTION)
            y += 11f
        }
    }

    private fun panel(renderer: MinecraftUiRenderer, bounds: Rect) {
        renderer.roundedRect(bounds, theme.tokens.radii.medium, theme.tokens.colors.elevatedPanelBackground)
    }

    private fun text(renderer: MinecraftUiRenderer, value: String, x: Float, y: Float, role: TextRole) {
        val measured = renderer.textMeasurer.measure(value, theme.textStyle(role))
        renderer.text(measured, Vec2(x, y), theme.textColor(role))
    }

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        val point = Vec2(event.x().toFloat(), event.y().toFloat())
        when {
            layout.close.contains(point) -> onClose()
            layout.expand.contains(point) -> { isMaximised = !isMaximised; updateLayout() }
            layout.search.contains(point) -> { searchFocused = true; caretSeconds = 0f }
            layout.previous.width > 0f && layout.previous.contains(point) -> step(-1)
            layout.next.width > 0f && layout.next.contains(point) -> step(1)
            !layout.window.contains(point) -> onClose()
            else -> searchFocused = false
        }
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (searchFocused) {
            when (event.key()) {
                GLFW.GLFW_KEY_ESCAPE -> { searchFocused = false; searchText = username }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> submitSearch()
                GLFW.GLFW_KEY_BACKSPACE -> searchText = searchText.dropLast(1)
            }
            return true
        }
        if (event.key() == GLFW.GLFW_KEY_R) {
            fetchCurrent()
            return true
        }
        val control = (event.modifiers() and GLFW.GLFW_MOD_CONTROL) != 0
        if (control && event.key() == GLFW.GLFW_KEY_O) {
            openOutside()
            return true
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (!searchFocused) return super.charTyped(event)
        val char = event.codepoint().toChar()
        if (searchText.length < 16 && (char.isLetterOrDigit() || char == '_')) searchText += char
        return true
    }

    private fun submitSearch() {
        val name = searchText.trim()
        if (!SkyCryptUrls.isValidUsername(name)) return
        searchFocused = false
        load(name, null)
    }

    private fun step(direction: Int) {
        val names = quickSwitch()
        if (names.size < 2) return
        val current = names.indexOfFirst { it.equals(username, true) }
        val index = if (current < 0) 0 else (current + direction + names.size) % names.size
        load(names[index], null)
    }

    private fun load(name: String, profile: String?) {
        username = name
        requestedProfile = profile
        searchText = name
        remember(name)
        updateLayout()
        fetchCurrent()
    }

    override fun onClose() {
        if (parent == null) super.onClose() else minecraft.setScreenAndShow(parent)
    }

    override fun removed() {
        scope.cancel()
        super.removed()
    }

    override fun isPauseScreen(): Boolean = false

    private fun openOutside() {
        val url = SkyCryptUrls.statsUrl(username, requestedProfile) ?: return
        if (!SkyCryptUrls.isAllowed(url)) return
        runCatching { net.minecraft.util.Util.getPlatform().openUri(java.net.URI(url)) }
    }

    private fun friendlyError(code: ApiErrorCode): String = when (code) {
        ApiErrorCode.UNAUTHENTICATED, ApiErrorCode.DEVICE_REVOKED -> "Pair this client with the Sidequest backend first."
        ApiErrorCode.NOT_FOUND -> "No matching SkyBlock profile was found."
        ApiErrorCode.RATE_LIMITED -> "Hypixel is rate-limiting lookups. Try again shortly."
        ApiErrorCode.UNAVAILABLE -> "The profile service is unavailable. Check the backend and its Hypixel key."
        else -> "Could not load this profile."
    }

    private fun formatLevel(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private fun formatNumber(value: Double): String {
        val absolute = kotlin.math.abs(value)
        val (amount, suffix) = when {
            absolute >= 1_000_000_000 -> value / 1_000_000_000 to "b"
            absolute >= 1_000_000 -> value / 1_000_000 to "m"
            absolute >= 1_000 -> value / 1_000 to "k"
            else -> return value.roundToInt().toString()
        }
        return String.format(Locale.ROOT, if (kotlin.math.abs(amount) >= 100) "%.0f%s" else "%.1f%s", amount, suffix)
    }
}
