package dev.th7bo.sidequest.ui.minecraft.screen

import dev.th7bo.sidequest.platform.core.item.NeuItemRepository
import dev.th7bo.sidequest.platform.core.profile.SkyCryptUrls
import dev.th7bo.sidequest.platform.item.SkyBlockItem
import dev.th7bo.sidequest.protocol.ApiErrorCode
import dev.th7bo.sidequest.protocol.ApiResult
import dev.th7bo.sidequest.protocol.ProfileCollection
import dev.th7bo.sidequest.protocol.ProfileBestiaryLocation
import dev.th7bo.sidequest.protocol.ProfileInventory
import dev.th7bo.sidequest.protocol.ProfileItemSlot
import dev.th7bo.sidequest.protocol.ProfileLoadout
import dev.th7bo.sidequest.protocol.ProfileMetric
import dev.th7bo.sidequest.protocol.ProfilePet
import dev.th7bo.sidequest.protocol.ProfileProgress
import dev.th7bo.sidequest.protocol.ProfileSection
import dev.th7bo.sidequest.protocol.ProfileSkill
import dev.th7bo.sidequest.protocol.ProfileSlayer
import dev.th7bo.sidequest.protocol.ProfileSkillTree
import dev.th7bo.sidequest.protocol.ProfileSkillTreeNode
import dev.th7bo.sidequest.protocol.ProfileAttribute
import dev.th7bo.sidequest.protocol.ProfileChocolateFactory
import dev.th7bo.sidequest.protocol.ProfileCrimsonIsle
import dev.th7bo.sidequest.protocol.ProfileExperimentation
import dev.th7bo.sidequest.protocol.ProfileRift
import dev.th7bo.sidequest.protocol.ProfileSack
import dev.th7bo.sidequest.protocol.ProfileTrophyFish
import dev.th7bo.sidequest.protocol.SkyBlockProfile
import dev.th7bo.sidequest.ui.components.ProfileWindowChrome
import dev.th7bo.sidequest.ui.components.ProfileWindowLayout
import dev.th7bo.sidequest.ui.components.ProfileWindowState
import dev.th7bo.sidequest.ui.geometry.Dp
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftTextMeasurer
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftUiRenderer
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.FrameInfo
import dev.th7bo.sidequest.ui.rendering.ItemRef
import dev.th7bo.sidequest.ui.rendering.TextOverflow
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.theme.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A tabbed, item-backed native view over Hypixel's public SkyBlock profile data. */
public class ProfileScreen(
    username: String,
    profile: String?,
    private val theme: Theme,
    private val parent: Screen?,
    private val fetch: suspend (String, String?) -> ApiResult<SkyBlockProfile>,
    private val resolveItem: suspend (String) -> SkyBlockItem? = { null },
    private val quickSwitch: () -> List<String> = { emptyList() },
    private val remember: (String) -> Unit = {},
) : Screen(Component.literal("$username's SkyBlock profile")) {

    private sealed interface ViewState {
        data object Loading : ViewState
        data class Ready(val profile: SkyBlockProfile) : ViewState
        data class Failed(val message: String) : ViewState
    }

    private enum class Tab(val label: String, val item: String) {
        OVERVIEW("Overview", "minecraft:compass"),
        SKILLS("Skills", "minecraft:enchanted_book"),
        COMBAT("Combat", "minecraft:zombie_head"),
        INVENTORY("Inventory", "minecraft:ender_chest"),
        COLLECTIONS("Collections", "minecraft:item_frame"),
        PETS("Pets", "minecraft:bone"),
        MINING("Mining", "minecraft:diamond_pickaxe"),
        FARMING("Farming", "minecraft:golden_hoe"),
        FISHING("Fishing", "minecraft:fishing_rod"),
        FORAGING("Foraging", "minecraft:oak_sapling"),
        RIFT("Rift", "minecraft:ender_eye"),
        MORE("More", "minecraft:recovery_compass"),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lookupJob: Job? = null
    private var state: ViewState = ViewState.Loading
    private var username = username
    private var requestedProfile = profile
    private var searchText = username
    private var searchFocused = false
    private var selectedTab = Tab.OVERVIEW
    private var isMaximised = false
    private var caretSeconds = 0f
    private var lastFrameNanos = 0L
    private var scrollOffset = 0f
    private var lastContentHeight = 0f
    private var scrollViewportHeight = 0f
    private var measurer: MinecraftTextMeasurer? = null
    private var layout = ProfileWindowLayout.of(Rect(0f, 0f, 1f, 1f), false)
    private var tabHitboxes: List<Pair<Tab, Rect>> = emptyList()
    private var profileHitboxes: List<Pair<String, Rect>> = emptyList()
    private var inventoryHitboxes: List<Pair<ProfileItemSlot, Rect>> = emptyList()

    /**
     * What the pointer is over, in the order it was drawn.
     *
     * Collected while painting rather than looked up afterwards, because the thing under the cursor is a
     * scrolled, clipped, tab-dependent rectangle and recomputing where everything went would be the layout
     * pass written twice. Drawn after the frame, so it sits above the content it describes.
     */
    private var tooltipZones: List<Pair<Rect, () -> List<Component>>> = emptyList()
    private var pointer = Vec2.Zero
    private var forcedPointerForTest: Vec2? = null
    private val itemIcons = ConcurrentHashMap<String, ItemRef>()
    private val requestedItemIcons = ConcurrentHashMap.newKeySet<String>()

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
        scrollOffset = 0f
        val name = username
        val profile = requestedProfile
        lookupJob = scope.launch {
            val result = fetch(name, profile)
            minecraft.schedule {
                if (username != name || requestedProfile != profile) return@schedule
                lookupJob = null
                state = when (result) {
                    is ApiResult.Success -> {
                        preloadVisuals(result.value, selectedTab)
                        ViewState.Ready(result.value)
                    }
                    is ApiResult.Failure -> ViewState.Failed(friendlyError(result.error.code))
                }
            }
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTicks: Float) {
        pointer = forcedPointerForTest ?: Vec2(mouseX.toFloat(), mouseY.toFloat())
        val now = System.nanoTime()
        val delta = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / NANOS_PER_SECOND).toFloat()
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
            // Submit the chrome before clipped page content. On 26.2 the GUI extractor retains the most
            // recent scissor for render states queued after disableScissor(), which used to make the title,
            // search field and window controls disappear even though our logical clip stack was balanced.
            // The content begins below the bar and is clipped there, so this order cannot cover the chrome.
            ProfileWindowChrome.paintBar(
                renderer,
                theme,
                layout,
                ProfileWindowState(
                    searchText = searchText,
                    isSearchFocused = searchFocused,
                    isCaretVisible = (caretSeconds % 1f) < .5f,
                    pointer = Vec2(mouseX.toFloat(), mouseY.toFloat()),
                    credit = "official Hypixel API",
                ),
            )
            renderer.pushClip(layout.content)
            try {
                paintContent(renderer)
            } finally {
                renderer.popClip()
            }
        } finally {
            renderer.endFrame()
        }
        paintTooltip(graphics)
    }

    /**
     * Draws whatever the pointer is over.
     *
     * The last matching zone wins, because that is the one drawn on top — a node sitting inside its tree's
     * panel should describe the node, not the panel. Clipped to the content area so a tooltip cannot be
     * summoned by a zone that has scrolled out of sight.
     */
    private fun paintTooltip(graphics: GuiGraphicsExtractor) {
        if (!layout.content.contains(pointer)) return
        val lines = tooltipZones.lastOrNull { (bounds, _) -> bounds.contains(pointer) }?.second?.invoke()
        if (lines.isNullOrEmpty()) return
        graphics.setComponentTooltipForNextFrame(minecraft.font, lines, pointer.x.roundToInt(), pointer.y.roundToInt())
    }

    private fun paintContent(renderer: MinecraftUiRenderer) {
        when (val current = state) {
            ViewState.Loading -> centredMessage(renderer, "Loading $username…", TextRole.SECONDARY)
            is ViewState.Failed -> {
                centredMessage(renderer, current.message, TextRole.SECONDARY)
                val retry = renderer.textMeasurer.measure("Press R to retry", theme.textStyle(TextRole.CAPTION))
                renderer.text(
                    retry,
                    Vec2(
                        layout.content.x + (layout.content.width - retry.size.width) / 2f,
                        layout.content.y + layout.content.height / 2f + 13f,
                    ),
                    theme.textColor(TextRole.CAPTION),
                )
            }
            is ViewState.Ready -> paintReady(renderer, current.profile)
        }
    }

    private fun paintReady(renderer: MinecraftUiRenderer, profile: SkyBlockProfile) {
        inventoryHitboxes = emptyList()
        tooltipZones = emptyList()
        val outer = layout.content
        val inset = if (outer.width < 420f) 7f else 11f
        val available = Rect(outer.x + inset, outer.y + inset, outer.width - inset * 2f, outer.height - inset * 2f)
        val navigationWidth = when {
            available.width >= 680f && available.height >= 310f -> 142f
            available.height < 310f -> 82f
            else -> 45f
        }
        val navigation = Rect(available.x, available.y, navigationWidth, available.height)
        paintNavigation(renderer, navigation, profile)

        val mainX = navigation.right + GAP
        val mainWidth = available.right - mainX
        val hero = Rect(mainX, available.y, mainWidth, HERO_HEIGHT)
        paintHero(renderer, profile, hero)

        val viewport = Rect(mainX, hero.bottom + GAP, mainWidth, available.bottom - hero.bottom - GAP)
        scrollViewportHeight = viewport.height
        renderer.pushClip(viewport)
        try {
            val start = viewport.y + scrollOffset
            val end = when (selectedTab) {
                Tab.OVERVIEW -> paintOverview(renderer, profile, viewport.x, start, viewport.width)
                Tab.SKILLS -> paintSkills(renderer, profile.skills, viewport.x, start, viewport.width)
                Tab.COMBAT -> paintCombat(renderer, profile, viewport.x, start, viewport.width)
                Tab.INVENTORY -> paintInventories(renderer, profile, viewport.x, start, viewport.width)
                Tab.COLLECTIONS -> paintCollections(renderer, profile.collections, viewport.x, start, viewport.width)
                Tab.PETS -> paintPets(renderer, profile.pets, viewport.x, start, viewport.width)
                Tab.MINING -> paintMining(renderer, profile, viewport.x, start, viewport.width)
                Tab.FARMING -> paintFarming(renderer, profile, viewport.x, start, viewport.width)
                Tab.FISHING -> paintFishing(renderer, profile.trophyFish, viewport.x, start, viewport.width)
                Tab.FORAGING -> paintForaging(renderer, profile, viewport.x, start, viewport.width)
                Tab.RIFT -> paintRift(renderer, profile.rift, viewport.x, start, viewport.width)
                Tab.MORE -> paintMore(renderer, profile, viewport.x, start, viewport.width)
            }
            lastContentHeight = (end - start).coerceAtLeast(0f)
        } finally {
            renderer.popClip()
        }
        paintScrollbar(renderer, viewport)
    }

    private fun paintHero(renderer: MinecraftUiRenderer, profile: SkyBlockProfile, bounds: Rect): Float {
        surface(renderer, bounds, strong = true)
        val avatar = Rect(bounds.x + 10f, bounds.y + 7f, 38f, 38f)
        renderer.roundedRect(avatar, theme.tokens.radii.medium, theme.tokens.colors.windowBackground)
        renderer.item(
            ItemRef("minecraft:player_head", skin = profile.skinTexture, skinSignature = profile.skinSignature),
            avatar.inset(3f),
        )

        val textX = avatar.right + 10f
        text(renderer, profile.username, textX, bounds.y + 9f, TextRole.TITLE, theme.tokens.colors.accent)
        val mode = profile.gameMode?.humanName()?.let { " · $it" }.orEmpty()
        text(renderer, "${profile.profileName}$mode${if (profile.selected) " · active" else ""}", textX, bounds.y + 27f, TextRole.SECONDARY)

        val level = profile.skyBlockLevel?.let { "SkyBlock ${formatLevel(it)}" } ?: "SkyBlock level hidden"
        rightText(renderer, level, bounds.right - 11f, bounds.y + 8f, TextRole.LABEL, theme.tokens.colors.accent)
        if (bounds.width >= 450f) {
            val wealth = listOfNotNull(
                profile.purse?.let { "Purse ${formatNumber(it)}" },
                profile.bank?.let { "Bank ${formatNumber(it)}" },
            ).joinToString("  ·  ").ifEmpty { "Balances hidden" }
            rightText(renderer, wealth, bounds.right - 11f, bounds.y + 25f, TextRole.CAPTION)
        }
        return bounds.bottom
    }

    private fun paintNavigation(renderer: MinecraftUiRenderer, bounds: Rect, profile: SkyBlockProfile) {
        surface(renderer, bounds)
        val columns = if (bounds.height < 310f) 2 else 1
        val showLabels = bounds.width >= 100f && columns == 1
        if (showLabels) {
            text(renderer, "PROFILE", bounds.x + 11f, bounds.y + 9f, TextRole.CAPTION, theme.tokens.colors.accent)
        }
        val top = bounds.y + if (showLabels) 26f else 6f
        val rows = ceil(Tab.entries.size / columns.toFloat()).toInt()
        val height = ((bounds.bottom - top - 6f) / rows).coerceAtMost(31f)
        val cellWidth = (bounds.width - 10f) / columns
        tabHitboxes = Tab.entries.mapIndexed { index, tab ->
            val row = index / columns
            val column = index % columns
            val tabBounds = Rect(bounds.x + 5f + column * cellWidth, top + row * height, cellWidth, height - 2f)
            if (tab == selectedTab) {
                renderer.roundedRect(tabBounds, theme.tokens.radii.medium, theme.tokens.colors.hoverBackground)
                renderer.fillRect(Rect(tabBounds.x, tabBounds.y + 5f, 2f, tabBounds.height - 10f), theme.tokens.colors.accent)
            }
            val icon = Rect(tabBounds.x + if (showLabels) 8f else (tabBounds.width - 17f) / 2f, tabBounds.y + (tabBounds.height - 17f) / 2f, 17f, 17f)
            val item = if (tab == Tab.OVERVIEW && profile.skinTexture != null) {
                ItemRef("minecraft:player_head", skin = profile.skinTexture, skinSignature = profile.skinSignature)
            } else {
                ItemRef(tab.item)
            }
            renderer.item(item, icon)
            if (showLabels) {
                text(
                    renderer,
                    tab.label,
                    icon.right + 7f,
                    tabBounds.y + (tabBounds.height - 8f) / 2f,
                    if (tab == selectedTab) TextRole.LABEL else TextRole.CAPTION,
                    if (tab == selectedTab) theme.tokens.colors.accent else theme.textColor(TextRole.CAPTION),
                    maxWidth = tabBounds.right - icon.right - 12f,
                )
            }
            tab to tabBounds
        }
    }

    private fun paintOverview(renderer: MinecraftUiRenderer, profile: SkyBlockProfile, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, "Profile overview", "minecraft:book", x, top, width)
        val facts = buildList {
            add(ProfileMetric("purse", "Purse", profile.purse))
            add(ProfileMetric("bank", "Bank", profile.bank))
            add(ProfileMetric("level", "SkyBlock level", profile.skyBlockLevel))
            add(ProfileMetric("fairy_souls", "Fairy souls", profile.fairySouls?.toDouble()))
            add(ProfileMetric("magical_power", "Magical power", profile.magicalPower?.toDouble()))
            profile.firstJoinMillis?.let { add(ProfileMetric("first_join", "First joined", text = formatDate(it))) }
            profile.selectedPower?.let { add(ProfileMetric("power", "Accessory power", text = it.humanName())) }
            profile.cookieBuffActive?.let { add(ProfileMetric("cookie", "Cookie buff", text = if (it) "Active" else "Inactive")) }
        }.filter { it.value != null || it.text != null }
        y = metricGrid(renderer, facts, x, y, width, preferredColumns = 4)

        if (profile.profiles.isNotEmpty()) {
            y += GAP
            y = sectionTitle(renderer, "Profiles", "minecraft:name_tag", x, y, width)
            val chipHeight = 25f
            val chipGap = 6f
            val columns = columns(width, 125f, 4)
            val chipWidth = (width - chipGap * (columns - 1)) / columns
            profileHitboxes = profile.profiles.mapIndexed { index, choice ->
                val row = index / columns
                val column = index % columns
                val box = Rect(x + column * (chipWidth + chipGap), y + row * (chipHeight + chipGap), chipWidth, chipHeight)
                surface(renderer, box, strong = choice.name.equals(profile.profileName, true))
                renderer.item(
                    ItemRef(if (choice.selected) "minecraft:lime_dye" else "minecraft:name_tag"),
                    Rect(box.x + 5f, box.y + 5f, 15f, 15f),
                )
                text(renderer, choice.name, box.x + 24f, box.y + 8f, TextRole.LABEL, maxWidth = box.width - 29f)
                choice.name to box
            }
            y += ceil(profile.profiles.size / columns.toFloat()).toInt() * (chipHeight + chipGap)
        } else {
            profileHitboxes = emptyList()
        }

        val extra = profile.stats + profile.currencies
        if (extra.isNotEmpty()) {
            y += GAP
            y = sectionTitle(renderer, "Stats & currencies", "minecraft:gold_ingot", x, y, width)
            y = metricGrid(renderer, extra, x, y, width, preferredColumns = 4)
        }
        return y
    }

    private fun paintSkills(renderer: MinecraftUiRenderer, skills: List<ProfileSkill>, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, "Skills", "minecraft:diamond_sword", x, top, width)
        if (skills.isEmpty()) return emptyState(renderer, "Skill API disabled", x, y, width)
        val columns = columns(width, 150f, 4)
        val gap = 7f
        val cardWidth = (width - gap * (columns - 1)) / columns
        for ((index, skill) in skills.withIndex()) {
            val row = index / columns
            val column = index % columns
            val box = Rect(x + column * (cardWidth + gap), y + row * (SKILL_CARD_HEIGHT + gap), cardWidth, SKILL_CARD_HEIGHT)
            paintSkillCard(renderer, skill, box)
        }
        return y + ceil(skills.size / columns.toFloat()).toInt() * (SKILL_CARD_HEIGHT + gap)
    }

    private fun paintSkillCard(renderer: MinecraftUiRenderer, skill: ProfileSkill, box: Rect) {
        surface(renderer, box)
        renderer.item(ItemRef(skillItem(skill.id)), Rect(box.x + 7f, box.y + 7f, 21f, 21f))
        text(renderer, skill.name, box.x + 34f, box.y + 7f, TextRole.LABEL, maxWidth = box.width - 68f)
        rightText(renderer, skill.level.toString(), box.right - 8f, box.y + 7f, TextRole.TITLE, theme.tokens.colors.accent)
        text(renderer, "${formatNumber(skill.experience)} XP", box.x + 34f, box.y + 22f, TextRole.CAPTION)
        val bar = Rect(box.x + 8f, box.bottom - 7f, box.width - 16f, 3f)
        renderer.roundedRect(bar, Dp(1.5f), theme.tokens.colors.border)
        renderer.roundedRect(bar.copy(width = bar.width * skill.progress.toFloat()), Dp(1.5f), theme.tokens.colors.accent)
    }

    private fun paintSlayers(renderer: MinecraftUiRenderer, slayers: List<ProfileSlayer>, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, "Slayer bosses", "minecraft:rotten_flesh", x, top, width)
        if (slayers.isEmpty()) return emptyState(renderer, "Slayer API disabled", x, y, width)
        val columns = columns(width, 190f, 3)
        val gap = 8f
        val cardWidth = (width - gap * (columns - 1)) / columns
        for ((index, slayer) in slayers.withIndex()) {
            val row = index / columns
            val column = index % columns
            val box = Rect(x + column * (cardWidth + gap), y + row * (72f + gap), cardWidth, 72f)
            surface(renderer, box)
            val slayerIcon = slayerVisualKey(slayer.id)?.let(itemIcons::get) ?: ItemRef(slayerItem(slayer.id))
            renderer.item(slayerIcon, Rect(box.x + 9f, box.y + 11f, 34f, 34f))
            text(renderer, slayer.name, box.x + 51f, box.y + 10f, TextRole.TITLE, maxWidth = box.width - 60f)
            slayer.level?.let { text(renderer, "Level $it", box.x + 51f, box.y + 27f, TextRole.LABEL, theme.tokens.colors.accent) }
            text(renderer, "${formatNumber(slayer.experience)} XP", box.x + 9f, box.bottom - 17f, TextRole.CAPTION)
            rightText(renderer, "${slayer.bossKills} bosses", box.right - 9f, box.bottom - 17f, TextRole.CAPTION)
        }
        return y + ceil(slayers.size / columns.toFloat()).toInt() * 80f
    }

    private fun paintDungeons(renderer: MinecraftUiRenderer, profile: SkyBlockProfile, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, "Dungeons", "minecraft:wither_skeleton_skull", x, top, width)
        if (profile.dungeons.isEmpty() && profile.dungeonClasses.isEmpty()) return emptyState(renderer, "Dungeon API disabled", x, y, width)
        y = progressCards(renderer, profile.dungeons, x, y, width)
        if (profile.dungeonClasses.isNotEmpty()) {
            y += GAP
            y = sectionTitle(renderer, "Classes", "minecraft:iron_chestplate", x, y, width)
            y = progressCards(renderer, profile.dungeonClasses, x, y, width)
        }
        val completions = profile.dungeons.flatMap { dungeon ->
            dungeon.details.map { it.copy(name = "${dungeon.name} ${it.name}") }
        }
        if (completions.isNotEmpty()) {
            y += GAP
            y = sectionTitle(renderer, "Floor completions", "minecraft:filled_map", x, y, width)
            y = metricGrid(renderer, completions, x, y, width, preferredColumns = 5)
        }
        return y
    }

    private fun paintCombat(renderer: MinecraftUiRenderer, profile: SkyBlockProfile, x: Float, top: Float, width: Float): Float {
        var y = top
        if (profile.slayers.isNotEmpty()) {
            y = paintSlayers(renderer, profile.slayers, x, y, width) + GAP
        }
        if (profile.dungeons.isNotEmpty() || profile.dungeonClasses.isNotEmpty()) {
            y = paintDungeons(renderer, profile, x, y, width) + GAP
        }
        if (profile.bestiary.isNotEmpty()) y = paintBestiary(renderer, profile.bestiary, x, y, width) + GAP
        profile.crimsonIsle?.let { y = paintCrimsonIsle(renderer, it, x, y, width) + GAP }
        return if (y == top) emptyState(renderer, "Combat API disabled", x, y, width) else y
    }

    private fun paintCrimsonIsle(renderer: MinecraftUiRenderer, crimson: ProfileCrimsonIsle, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, "Crimson Isle", "minecraft:netherrack", x, top, width)
        val reputation = Rect(x, y, width, 58f)
        surface(renderer, reputation, strong = true)
        renderer.item(ItemRef(if (crimson.selectedFaction == "Barbarian") "minecraft:netherite_axe" else "minecraft:blaze_rod"), Rect(x + 9f, y + 14f, 28f, 28f))
        text(renderer, crimson.selectedFaction?.let { "$it faction" } ?: "No faction selected", x + 45f, y + 8f, TextRole.TITLE)
        text(renderer, "Mage  ${formatNumber(crimson.mageReputation.toDouble())} · ${factionRank(crimson.mageReputation)}", x + 45f, y + 27f, TextRole.CAPTION, Color(0xFFAA55FFu.toInt()))
        text(renderer, "Barbarian  ${formatNumber(crimson.barbarianReputation.toDouble())} · ${factionRank(crimson.barbarianReputation)}", x + 45f, y + 42f, TextRole.CAPTION, Color(0xFFFF5555u.toInt()))
        y = reputation.bottom + GAP

        y = sectionTitle(renderer, "Kuudra", "minecraft:magma_cream", x, y, width)
        val kuudraColumns = columns(width, 125f, 5)
        val kuudraWidth = (width - 7f * (kuudraColumns - 1)) / kuudraColumns
        crimson.kuudra.forEachIndexed { index, tier ->
            val box = Rect(x + (index % kuudraColumns) * (kuudraWidth + 7f), y + (index / kuudraColumns) * 53f, kuudraWidth, 46f)
            surface(renderer, box)
            renderer.item(ItemRef(kuudraItem(tier.id)), Rect(box.x + 7f, box.y + 11f, 24f, 24f))
            text(renderer, tier.name, box.x + 37f, box.y + 6f, TextRole.LABEL, kuudraColor(tier.id), maxWidth = box.width - 43f)
            text(renderer, "${formatNumber(tier.completions.toDouble())} runs", box.x + 37f, box.y + 22f, TextRole.CAPTION, maxWidth = box.width - 43f)
            text(renderer, "Wave ${tier.highestWave}", box.x + 37f, box.y + 34f, TextRole.CAPTION, maxWidth = box.width - 43f)
        }
        y += ceil(crimson.kuudra.size / kuudraColumns.toFloat()).toInt() * 53f + GAP

        y = sectionTitle(renderer, "Dojo", "minecraft:golden_helmet", x, y, width)
        val dojoColumns = columns(width, 145f, 4)
        val dojoWidth = (width - 7f * (dojoColumns - 1)) / dojoColumns
        crimson.dojo.forEachIndexed { index, challenge ->
            val box = Rect(x + (index % dojoColumns) * (dojoWidth + 7f), y + (index / dojoColumns) * 47f, dojoWidth, 40f)
            surface(renderer, box)
            renderer.item(ItemRef(dojoItem(challenge.id)), Rect(box.x + 7f, box.y + 8f, 23f, 23f))
            text(renderer, challenge.name, box.x + 36f, box.y + 5f, TextRole.LABEL, maxWidth = box.width - 42f)
            val points = challenge.points.takeIf { it >= 0 }
            text(renderer, points?.let { "$it pts · ${dojoGrade(it)}" } ?: "Not attempted", box.x + 36f, box.y + 22f, TextRole.CAPTION, points?.let(::dojoGradeColor) ?: theme.textColor(TextRole.CAPTION), maxWidth = box.width - 42f)
        }
        return y + ceil(crimson.dojo.size / dojoColumns.toFloat()).toInt() * 47f
    }

    private fun progressCards(renderer: MinecraftUiRenderer, values: List<ProfileProgress>, x: Float, top: Float, width: Float): Float {
        val columns = columns(width, 155f, 4)
        val gap = 7f
        val cardWidth = (width - gap * (columns - 1)) / columns
        for ((index, progress) in values.withIndex()) {
            val row = index / columns
            val column = index % columns
            val box = Rect(x + column * (cardWidth + gap), top + row * 51f, cardWidth, 44f)
            surface(renderer, box)
            renderer.item(ItemRef(dungeonItem(progress.id)), Rect(box.x + 7f, box.y + 9f, 24f, 24f))
            text(renderer, progress.name, box.x + 37f, box.y + 7f, TextRole.LABEL, maxWidth = box.width - 44f)
            text(renderer, progress.level?.let { "Level $it" } ?: "${formatNumber(progress.experience ?: 0.0)} XP", box.x + 37f, box.y + 23f, TextRole.CAPTION)
        }
        return top + ceil(values.size / columns.toFloat()).toInt() * 51f
    }

    private fun paintCollections(renderer: MinecraftUiRenderer, values: List<ProfileCollection>, x: Float, top: Float, width: Float): Float {
        if (values.isEmpty()) return emptyState(renderer, "Collection API disabled", x, top, width)
        var y = top
        for ((category, collections) in values.groupBy { it.category }.toSortedMap()) {
            y = sectionTitle(renderer, category, collectionCategoryItem(category), x, y, width)
            val gridColumns = columns(width, 145f, 5)
            val gap = 6f
            val cardWidth = (width - gap * (gridColumns - 1)) / gridColumns
            for ((index, collection) in collections.withIndex()) {
                val row = index / gridColumns
                val column = index % gridColumns
                val box = Rect(x + column * (cardWidth + gap), y + row * 39f, cardWidth, 33f)
                surface(renderer, box)
                renderer.item(itemIcons[collection.id] ?: ItemRef(collectionItem(collection.id)), Rect(box.x + 6f, box.y + 7f, 19f, 19f))
                text(renderer, collection.name, box.x + 30f, box.y + 5f, TextRole.CAPTION, maxWidth = box.width - 36f)
                text(renderer, formatNumber(collection.amount.toDouble()), box.x + 30f, box.y + 19f, TextRole.LABEL, theme.tokens.colors.accent)
            }
            y += ceil(collections.size / gridColumns.toFloat()).toInt() * 39f + GAP
        }
        return y
    }

    private fun paintPets(renderer: MinecraftUiRenderer, pets: List<ProfilePet>, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, "Pets", "minecraft:bone", x, top, width)
        if (pets.isEmpty()) return emptyState(renderer, "Pet API disabled or no pets", x, y, width)
        val featured = pets.firstOrNull { it.active } ?: pets.first()
        val feature = Rect(x, y, width, 94f)
        surface(renderer, feature, strong = true)
        val featuredIcon = itemIcons[petVisualKey(featured)] ?: ItemRef("minecraft:player_head")
        renderer.item(featuredIcon, Rect(feature.x + 14f, feature.y + 14f, 66f, 66f))
        renderer.fillRect(Rect(feature.x, feature.y + 10f, 3f, feature.height - 20f), rarityColor(featured.rarity))
        text(renderer, featured.name, feature.x + 94f, feature.y + 14f, TextRole.TITLE, rarityColor(featured.rarity))
        text(renderer, featured.rarity.humanName() + if (featured.active) " · active pet" else "", feature.x + 94f, feature.y + 34f, TextRole.LABEL)
        text(renderer, "${formatNumber(featured.experience)} XP", feature.x + 94f, feature.y + 51f, TextRole.CAPTION)
        val held = featured.heldItem?.removePrefix("PET_ITEM_")?.humanName() ?: "No pet item"
        featured.heldItem?.let(itemIcons::get)?.let { renderer.item(it, Rect(feature.x + 94f, feature.y + 68f, 17f, 17f)) }
        text(renderer, held, feature.x + if (featured.heldItem == null) 94f else 117f, feature.y + 72f, TextRole.CAPTION)
        y = feature.bottom + GAP
        y = sectionTitle(renderer, "Pet stable", "minecraft:name_tag", x, y, width)
        val cell = 58f
        val gridColumns = (width / cell).toInt().coerceAtLeast(1)
        pets.forEachIndexed { index, pet ->
            val box = Rect(x + (index % gridColumns) * cell, y + (index / gridColumns) * cell, 52f, 52f)
            surface(renderer, box, pet.active)
            renderer.item(itemIcons[petVisualKey(pet)] ?: ItemRef("minecraft:player_head"), Rect(box.x + 8f, box.y + 6f, 36f, 36f))
            renderer.fillRect(Rect(box.x + 5f, box.bottom - 5f, box.width - 10f, 2f), rarityColor(pet.rarity))
        }
        return y + ceil(pets.size / gridColumns.toFloat()).toInt() * cell
    }

    private fun paintInventories(renderer: MinecraftUiRenderer, profile: SkyBlockProfile, x: Float, top: Float, width: Float): Float {
        var y = top
        if (profile.inventories.isEmpty()) {
            y = sectionTitle(renderer, "Inventories", "minecraft:ender_chest", x, y, width)
            y = emptyState(renderer, "Inventory API disabled or inventories are empty", x, y, width) + GAP
        } else {
            for (inventory in profile.inventories) y = paintInventory(renderer, inventory, x, y, width) + GAP
        }
        if (profile.loadouts.isNotEmpty()) y = paintLoadouts(renderer, profile.loadouts, x, y, width) + GAP
        y = paintSacks(renderer, profile.sacks, x, y, width)
        return y
    }

    /**
     * Sacks, one section per sack.
     *
     * Hypixel sends the contents as a single flat map of item to count with nothing saying which sack any
     * of it came from, which as one alphabetical list is several hundred rows of noise. The grouping comes
     * from the server, which reads it out of the item database's own sack table; here it only has to be
     * drawn, with each sack wearing its own item as its heading.
     */
    private fun paintSacks(renderer: MinecraftUiRenderer, sacks: List<ProfileSack>, x: Float, top: Float, width: Float): Float {
        if (sacks.isEmpty()) return top
        var y = sectionTitle(renderer, "Sacks", "minecraft:bundle", x, top, width)
        val gridColumns = columns(width, 150f, 5)
        val gap = 6f
        val cardWidth = (width - gap * (gridColumns - 1)) / gridColumns

        for (sack in sacks) {
            val header = Rect(x, y, width, 22f)
            renderer.item(
                ItemRef(itemIcons[sack.itemId]?.id ?: "minecraft:bundle", skin = itemIcons[sack.itemId]?.skin),
                Rect(header.x + 2f, header.y + 2f, 15f, 15f),
            )
            text(renderer, sack.name, header.x + 22f, header.y + 4f, TextRole.LABEL, maxWidth = width - 110f)
            // Pulled in off the edge: the scrollbar is drawn over the last three pixels of the viewport,
            // and a total ending in "items" put its last letter underneath it.
            rightText(renderer, "${formatNumber(sack.total.toDouble())} items", header.right - 6f, header.y + 5f, TextRole.CAPTION, theme.tokens.colors.accent)
            y = header.bottom

            sack.items.forEachIndexed { index, item ->
                val box = Rect(x + (index % gridColumns) * (cardWidth + gap), y + (index / gridColumns) * 34f, cardWidth, 29f)
                surface(renderer, box)
                renderer.item(itemIcons[item.id] ?: ItemRef(collectionItem(item.id)), Rect(box.x + 6f, box.y + 6f, 17f, 17f))
                text(renderer, item.name, box.x + 28f, box.y + 3f, TextRole.CAPTION, maxWidth = box.width - 34f)
                text(renderer, formatNumber(item.amount.toDouble()), box.x + 28f, box.y + 15f, TextRole.CAPTION, theme.tokens.colors.accent)
            }
            y += ceil(sack.items.size / gridColumns.toFloat()).toInt() * 34f + GAP
        }
        return y
    }

    private fun paintInventory(renderer: MinecraftUiRenderer, inventory: ProfileInventory, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, inventory.name, inventoryIcon(inventory.id), x, top, width)
        val columns = inventory.columns.coerceIn(1, 9)
        val slot = (width / columns).coerceAtMost(29f)
        val rows = ((inventory.slots.maxOfOrNull { it.slot } ?: 0) / columns + 1).coerceAtLeast(1)
        val gridWidth = slot * columns
        val grid = Rect(x, y, gridWidth, rows * slot)
        surface(renderer, grid)
        repeat(rows * columns) { index ->
            val box = Rect(x + (index % columns) * slot + 2f, y + (index / columns) * slot + 2f, slot - 4f, slot - 4f)
            renderer.roundedRect(box, Dp(2f), theme.tokens.colors.windowBackground.withAlpha(.75f))
        }
        for (item in inventory.slots) {
            val icon = item.internalName?.let(itemIcons::get) ?: ItemRef(itemFallback(item))
            val box = Rect(x + (item.slot % columns) * slot + 3f, y + (item.slot / columns) * slot + 3f, slot - 6f, slot - 6f)
            renderer.item(icon, box)
            inventoryHitboxes = inventoryHitboxes + (item to box)
            tooltipZones = tooltipZones + (box to { itemTooltip(item) })
            if (item.count > 1) rightText(renderer, item.count.toString(), box.right, box.bottom - 8f, TextRole.CAPTION, Color.White)
        }
        return y + rows * slot
    }

    private fun itemTooltip(item: ProfileItemSlot): List<Component> = buildList {
        add(legacyComponent(item.displayName ?: item.internalName?.humanName() ?: "Unknown item"))
        item.lore.take(24).mapTo(this, ::legacyComponent)
        if (item.count > 1) add(Component.literal("Count: ${item.count}").withStyle(ChatFormatting.GRAY))
    }

    /**
     * A Heart of the Mountain or Heart of the Forest perk, as the real menu describes it.
     *
     * The description is not stored anywhere — it is the perk's own formula, evaluated at this player's
     * level on the server, colour codes and all. So an untaken perk reads as what taking it would give,
     * and a maxed one reads as what it is giving, which is what makes the grid worth hovering over at all.
     */
    private fun perkTooltip(node: ProfileSkillTreeNode): List<Component> = buildList {
        add(Component.literal(node.name).withStyle(if (node.level > 0) ChatFormatting.GREEN else ChatFormatting.GRAY))
        if (node.maxLevel > 1) {
            add(
                Component.literal("Level ${node.level.coerceAtLeast(0)}")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("/${node.maxLevel}").withStyle(ChatFormatting.DARK_GRAY)),
            )
        } else if (node.level <= 0) {
            add(Component.literal("Not unlocked").withStyle(ChatFormatting.DARK_GRAY))
        }
        if (node.lore.isNotEmpty()) {
            add(Component.empty())
            node.lore.take(16).mapTo(this, ::legacyComponent)
        }
        node.powder?.let {
            add(Component.empty())
            add(Component.literal(it.humanName()).withStyle(ChatFormatting.DARK_GRAY))
        }
        node.enabled?.let {
            add(Component.literal(if (it) "Enabled" else "Disabled").withStyle(if (it) ChatFormatting.GREEN else ChatFormatting.RED))
        }
    }

    private fun legacyComponent(value: String): Component {
        val result = Component.empty()
        val formats = mutableListOf<ChatFormatting>()
        var start = 0
        var index = 0
        fun append(end: Int) {
            if (end > start) result.append(Component.literal(value.substring(start, end)).withStyle(*formats.toTypedArray()))
        }
        while (index + 1 < value.length) {
            if (value[index] != ChatFormatting.PREFIX_CODE) { index++; continue }
            append(index)
            val code = value[index + 1].lowercaseChar()
            val format = ChatFormatting.getByCode(code)
            when {
                code == 'r' -> formats.clear()
                code in '0'..'9' || code in 'a'..'f' -> {
                    formats.clear()
                    if (format != null) formats += format
                }
                format != null -> formats += format
            }
            index += 2
            start = index
        }
        append(value.length)
        return result
    }

    private fun paintBestiary(renderer: MinecraftUiRenderer, locations: List<ProfileBestiaryLocation>, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, "Bestiary", "minecraft:writable_book", x, top, width)
        for (location in locations) {
            val height = 39f + ceil(location.mobs.size.coerceAtMost(8) / 4f) * 16f
            val box = Rect(x, y, width, height)
            surface(renderer, box)
            renderer.item(ItemRef(bestiaryLocationItem(location.id)), Rect(box.x + 8f, box.y + 8f, 24f, 24f))
            text(renderer, location.name, box.x + 39f, box.y + 7f, TextRole.TITLE)
            text(renderer, "${formatNumber(location.kills.toDouble())} kills · ${location.mobs.size} mobs", box.x + 39f, box.y + 22f, TextRole.CAPTION)
            location.mobs.take(8).forEachIndexed { index, mob ->
                val cellWidth = (box.width - 16f) / 4f
                val cellX = box.x + 8f + (index % 4) * cellWidth
                val cellY = box.y + 39f + (index / 4) * 16f
                text(renderer, "${mob.name} ${formatNumber(mob.kills.toDouble())}", cellX, cellY, TextRole.CAPTION, maxWidth = cellWidth - 6f)
            }
            y = box.bottom + 6f
        }
        return y
    }

    private fun paintSkillTree(renderer: MinecraftUiRenderer, tree: ProfileSkillTree, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, tree.name, if (tree.id == "mining") "minecraft:beacon" else "minecraft:oak_sapling", x, top, width)
        val selected = tree.slots.firstOrNull { it.slot == tree.selectedSlot } ?: tree.slots.firstOrNull() ?: return y
        val summary = Rect(x, y, width, 39f)
        surface(renderer, summary, strong = true)
        text(renderer, "Preset ${selected.slot} · ${tree.slots.size} saved", summary.x + 10f, summary.y + 7f, TextRole.LABEL, theme.tokens.colors.accent)
        text(renderer, selected.selectedAbility ?: "No selected ability", summary.x + 10f, summary.y + 22f, TextRole.CAPTION)
        y = summary.bottom + 6f
        val slot = 26f
        // Measured from the tree rather than fixed: the mountain is ten rows deep and the forest seven, and
        // drawing the forest on the mountain's grid left it floating three rows down its own panel.
        val columns = tree.columns.coerceIn(1, 12)
        val rows = tree.rows.coerceIn(1, 16)
        val gridWidth = slot * columns + 20f
        val gridHeight = slot * rows + 12f
        val gridX = x + (width - gridWidth) / 2f
        val grid = Rect(gridX, y, gridWidth, gridHeight)
        surface(renderer, grid)
        repeat(rows) { tier ->
            val lineY = grid.y + 6f + tier * slot + slot / 2f
            renderer.fillRect(Rect(grid.x + 8f, lineY, grid.width - 16f, 1f), theme.tokens.colors.border.withAlpha(.22f))
        }
        selected.nodes.forEach { node ->
            val box = Rect(
                grid.x + 10f + node.column.coerceIn(0, columns - 1) * slot,
                grid.y + 6f + node.row.coerceIn(0, rows - 1) * slot,
                22f,
                22f,
            )
            renderer.roundedRect(box, Dp(3f), theme.tokens.colors.windowBackground)
            renderer.border(box, Dp(3f), Dp(1f), treeNodeColor(node.kind, node.level, node.maxLevel, node.enabled).withAlpha(.7f))
            // The layout answers what the real menu draws — coal when locked, diamond at the cap — so the
            // guess below is only reached when the description tables could not be fetched at all.
            val icon = node.itemId
                ?: treeNodeItem(tree.id, node.kind, node.level, node.maxLevel, node.enabled, selected.selectedAbility, node.name)
            renderer.item(ItemRef(icon), box.inset(3f))
            if (node.level > 1) rightText(renderer, node.level.toString(), box.right, box.bottom - 7f, TextRole.CAPTION, Color.White)
            tooltipZones = tooltipZones + (box to { perkTooltip(node) })
        }
        return grid.bottom
    }

    private fun paintLoadouts(renderer: MinecraftUiRenderer, loadouts: List<ProfileLoadout>, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, "Loadouts", "minecraft:armor_stand", x, top, width)
        val gridColumns = columns(width, 190f, 4)
        val cardWidth = (width - 7f * (gridColumns - 1)) / gridColumns
        loadouts.forEachIndexed { index, loadout ->
            val box = Rect(x + (index % gridColumns) * (cardWidth + 7f), y + (index / gridColumns) * 70f, cardWidth, 63f)
            surface(renderer, box, loadout.equipped)
            renderer.item(ItemRef(if (loadout.equipped) "minecraft:netherite_chestplate" else "minecraft:armor_stand"), Rect(box.x + 8f, box.y + 13f, 24f, 24f))
            text(renderer, loadout.name, box.x + 39f, box.y + 8f, TextRole.LABEL, maxWidth = box.width - 46f)
            val detail = listOfNotNull(loadout.powerStone, loadout.pet?.let { "Pet selected" }).joinToString(" · ").ifEmpty { "Saved equipment set" }
            text(renderer, detail, box.x + 39f, box.y + 25f, TextRole.CAPTION, maxWidth = box.width - 46f)
            (loadout.armor + loadout.equipment).take(8).forEachIndexed { slot, item ->
                val icon = item.internalName?.let(itemIcons::get) ?: ItemRef(itemFallback(item))
                renderer.item(icon, Rect(box.x + 8f + slot * 18f, box.y + 42f, 16f, 16f))
            }
        }
        return y + ceil(loadouts.size / gridColumns.toFloat()).toInt() * 70f
    }

    private fun paintForaging(renderer: MinecraftUiRenderer, profile: SkyBlockProfile, x: Float, top: Float, width: Float): Float {
        var y = top
        profile.skillTrees.firstOrNull { it.id == "foraging" }?.let { y = paintSkillTree(renderer, it, x, y, width) + GAP }
        if (profile.attributes.isNotEmpty()) y = paintAttributes(renderer, profile.attributes, x, y, width) + GAP
        y = paintProfileSections(renderer, profile, setOf("foraging_summary"), x, y, width, emptyWhenMissing = false)
        return if (y == top) emptyState(renderer, "Foraging API disabled", x, y, width) else y
    }

    private fun paintFishing(renderer: MinecraftUiRenderer, fish: List<ProfileTrophyFish>, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, "Trophy fish", "minecraft:fishing_rod", x, top, width)
        if (fish.isEmpty()) return emptyState(renderer, "No public trophy-fish data", x, y, width)
        val gridColumns = columns(width, 210f, 4)
        val gap = 7f
        val cardWidth = (width - gap * (gridColumns - 1)) / gridColumns
        fish.forEachIndexed { index, entry ->
            val box = Rect(x + (index % gridColumns) * (cardWidth + gap), y + (index / gridColumns) * 66f, cardWidth, 59f)
            surface(renderer, box)
            val key = trophyFishVisualKey(entry)
            renderer.item(itemIcons[key] ?: ItemRef("minecraft:cod"), Rect(box.x + 8f, box.y + 11f, 34f, 34f))
            text(renderer, entry.name, box.x + 49f, box.y + 7f, TextRole.LABEL, maxWidth = box.width - 56f)
            val tiers = listOf(
                "B ${entry.bronze}" to Color.parse("#B87333"), "S ${entry.silver}" to Color.parse("#C0C0C0"),
                "G ${entry.gold}" to Color.parse("#FFD75A"), "D ${entry.diamond}" to Color.parse("#55FFFF"),
            )
            tiers.forEachIndexed { tier, (label, color) ->
                text(renderer, label, box.x + 49f + (tier % 2) * 52f, box.y + 22f + (tier / 2) * 15f, TextRole.CAPTION, color)
            }
            rightText(renderer, entry.total.toString(), box.right - 8f, box.y + 7f, TextRole.CAPTION, theme.tokens.colors.accent)
        }
        return y + ceil(fish.size / gridColumns.toFloat()).toInt() * 66f
    }

    private fun paintAttributes(renderer: MinecraftUiRenderer, attributes: List<ProfileAttribute>, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, "Attributes & shards", "minecraft:amethyst_shard", x, top, width)
        attributes.groupBy { it.rarity }.forEach { (rarity, values) ->
            text(renderer, rarity.humanName(), x + 2f, y + 3f, TextRole.LABEL, rarityColor(rarity))
            y += 19f
            val gridColumns = columns(width, 145f, 5)
            val cardWidth = (width - 6f * (gridColumns - 1)) / gridColumns
            values.forEachIndexed { index, attribute ->
                val box = Rect(x + (index % gridColumns) * (cardWidth + 6f), y + (index / gridColumns) * 42f, cardWidth, 36f)
                surface(renderer, box)
                renderer.item(itemIcons[attribute.itemId] ?: ItemRef("minecraft:amethyst_shard"), Rect(box.x + 7f, box.y + 8f, 20f, 20f))
                text(renderer, attribute.name, box.x + 33f, box.y + 5f, TextRole.CAPTION, rarityColor(rarity), maxWidth = box.width - 40f)
                text(renderer, "${attribute.syphoned} syphoned · ${attribute.owned} owned", box.x + 33f, box.y + 20f, TextRole.CAPTION, maxWidth = box.width - 40f)
            }
            y += ceil(values.size / gridColumns.toFloat()).toInt() * 42f + 5f
        }
        return y
    }

    private fun paintRift(renderer: MinecraftUiRenderer, rift: ProfileRift?, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, "The Rift", "minecraft:ender_eye", x, top, width)
        if (rift == null) return emptyState(renderer, "No public Rift data", x, y, width)
        val facts = listOf(
            ProfileMetric("motes", "Lifetime motes", rift.lifetimeMotes.toDouble()), ProfileMetric("visits", "Rift visits", rift.visits.toDouble()),
            ProfileMetric("souls", "Enigma souls", rift.enigmaSouls.toDouble()), ProfileMetric("cats", "Montezuma cats", rift.foundCats.toDouble()),
            ProfileMetric("eyes", "Unlocked eyes", rift.unlockedEyes.toDouble()), ProfileMetric("grubber", "Grubber stacks", rift.grubberStacks.toDouble()),
            ProfileMetric("sitting", "Time with Ävaeìkx", text = formatDuration(rift.secondsSitting)),
        )
        y = metricGrid(renderer, facts, x, y, width, 4) + GAP
        y = sectionTitle(renderer, "Timecharms", "minecraft:clock", x, y, width)
        val charms = RIFT_TIMECHARMS.map { (id, name) -> rift.timecharms.firstOrNull { it.id == id } to name }
        val slot = (width / 8f).coerceAtMost(52f)
        charms.forEachIndexed { index, (found, name) ->
            val box = Rect(x + index * slot, y, slot - 5f, 49f)
            surface(renderer, box, found != null)
            renderer.item(ItemRef(if (found == null) "minecraft:gray_dye" else "minecraft:clock"), Rect(box.x + (box.width - 23f) / 2f, box.y + 5f, 23f, 23f))
            text(renderer, name.removeSuffix(" Timecharm"), box.x + 4f, box.y + 33f, TextRole.CAPTION, maxWidth = box.width - 8f)
        }
        return y + 49f
    }

    private fun paintExperimentation(renderer: MinecraftUiRenderer, data: ProfileExperimentation, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, "Experimentation table", "minecraft:enchanting_table", x, top, width)
        val facts = buildList {
            add(ProfileMetric("serums", "Metaphysical serums", data.serumsDrank.toDouble()))
            add(ProfileMetric("resets", "Resets used", data.resetsUsed.toDouble()))
            data.lastAttemptMillis?.let { add(ProfileMetric("last_attempt", "Last attempt", text = formatDate(it))) }
        }
        y = metricGrid(renderer, facts, x, y, width, 3)
        if (data.experiments.isNotEmpty()) {
            y += GAP
            y = sectionTitle(renderer, "Games", "minecraft:experience_bottle", x, y, width)
            y = metricGrid(renderer, data.experiments.flatMap {
                listOf(ProfileMetric("${it.id}_best", "${it.name} best", it.bestScore.toDouble()), ProfileMetric("${it.id}_attempts", "${it.name} attempts", it.attempts.toDouble()))
            }, x, y, width, 3)
        }
        return y
    }

    private fun paintChocolateFactory(renderer: MinecraftUiRenderer, data: ProfileChocolateFactory, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, "Chocolate Factory", "minecraft:rabbit_foot", x, top, width)
        y = metricGrid(renderer, listOf(
            ProfileMetric("chocolate", "Chocolate", data.chocolate.toDouble()), ProfileMetric("total_chocolate", "Lifetime chocolate", data.totalChocolate.toDouble()),
            ProfileMetric("prestige", "Factory prestige", data.prestige.toDouble()), ProfileMetric("barn", "Barn capacity", data.barnCapacity.toDouble()),
            ProfileMetric("rabbits", "Rabbits collected", data.rabbitsCollected.toDouble()),
        ), x, y, width, 5)
        if (data.employees.isNotEmpty()) {
            y += GAP
            y = sectionTitle(renderer, "Rabbit employees", "minecraft:rabbit_spawn_egg", x, y, width)
            y = metricGrid(renderer, data.employees.map { ProfileMetric(it.id, it.name, it.level.toDouble()) }, x, y, width, 5)
        }
        return y
    }

    private fun paintMetrics(renderer: MinecraftUiRenderer, title: String, metrics: List<ProfileMetric>, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, title, "minecraft:diamond_pickaxe", x, top, width)
        if (metrics.isEmpty()) return emptyState(renderer, "No public data in this section", x, y, width)
        return metricGrid(renderer, metrics, x, y, width, preferredColumns = 4)
    }

    private fun paintMining(renderer: MinecraftUiRenderer, profile: SkyBlockProfile, x: Float, top: Float, width: Float): Float {
        var y = top
        if (profile.mining.isNotEmpty()) {
            y = paintMetrics(renderer, "Mining & Heart of the Mountain", profile.mining, x, y, width) + GAP
        }
        profile.skillTrees.firstOrNull { it.id == "mining" }?.let { y = paintSkillTree(renderer, it, x, y, width) + GAP }
        y = paintProfileSections(renderer, profile, setOf("glacite_player_data", "forge"), x, y, width, emptyWhenMissing = false)
        return if (y == top) emptyState(renderer, "Mining API disabled", x, y, width) else y
    }

    private fun paintFarming(renderer: MinecraftUiRenderer, profile: SkyBlockProfile, x: Float, top: Float, width: Float): Float {
        var y = top
        if (profile.garden.isNotEmpty()) {
            y = sectionTitle(renderer, "Garden", "minecraft:wheat", x, y, width)
            y = metricGrid(renderer, profile.garden, x, y, width, preferredColumns = 4) + GAP
        }
        y = paintProfileSections(renderer, profile, setOf("farming_summary"), x, y, width, emptyWhenMissing = false)
        return if (y == top) emptyState(renderer, "Farming API disabled", x, y, width) else y
    }

    private fun paintMore(renderer: MinecraftUiRenderer, profile: SkyBlockProfile, x: Float, top: Float, width: Float): Float {
        var y = top
        profile.experimentation?.let { y = paintExperimentation(renderer, it, x, y, width) + GAP }
        profile.chocolateFactory?.let { y = paintChocolateFactory(renderer, it, x, y, width) + GAP }
        val fixed = listOf(
            Triple("Museum", "minecraft:gold_block", profile.museum),
            Triple("Currencies & essence", "minecraft:emerald", profile.currencies),
            Triple("General statistics", "minecraft:book", profile.stats),
        )
        for ((title, icon, metrics) in fixed) {
            if (metrics.isEmpty()) continue
            y = sectionTitle(renderer, title, icon, x, y, width)
            y = metricGrid(renderer, metrics, x, y, width, preferredColumns = 4)
            y += GAP
        }
        val dedicated = setOf(
            "inventory", "shared_inventory", "sacks", "loadout",
            "bestiary", "nether_island_player_data", "glacite_player_data", "skill_tree", "forge",
            "garden_player_data", "jacobs_contest", "farming_summary", "trophy_fish",
            "foraging", "foraging_core", "foraging_summary", "attributes", "rift",
            "experimentation", "events", "trophy_fish",
        )
        y = paintProfileSections(renderer, profile, profile.sections.map { it.id }.toSet() - dedicated, x, y, width, emptyWhenMissing = false)
        return if (y == top) emptyState(renderer, "No additional public progression data", x, y, width) else y
    }

    private fun paintProfileSections(
        renderer: MinecraftUiRenderer,
        profile: SkyBlockProfile,
        ids: Set<String>,
        x: Float,
        top: Float,
        width: Float,
        emptyWhenMissing: Boolean = true,
    ): Float {
        var y = top
        for (section in profile.sections.filter { it.id in ids }) {
            if (section.metrics.isEmpty()) continue
            y = sectionTitle(renderer, section.name, sectionItem(section), x, y, width)
            y = metricGrid(renderer, section.metrics, x, y, width, preferredColumns = 4) + GAP
        }
        return if (y == top && emptyWhenMissing) emptyState(renderer, "No public data in this section", x, y, width) else y
    }

    private fun metricGrid(
        renderer: MinecraftUiRenderer,
        metrics: List<ProfileMetric>,
        x: Float,
        top: Float,
        width: Float,
        preferredColumns: Int,
    ): Float {
        val columns = columns(width, 135f, preferredColumns)
        val gap = 6f
        val cardWidth = (width - gap * (columns - 1)) / columns
        for ((index, metric) in metrics.withIndex()) {
            val row = index / columns
            val column = index % columns
            val box = Rect(x + column * (cardWidth + gap), top + row * 43f, cardWidth, 37f)
            surface(renderer, box)
            val value = metric.text ?: metric.value?.let(::formatNumber) ?: "—"
            renderer.fillRect(Rect(box.x, box.y + 8f, 2f, box.height - 16f), metricAccent(metric.id))
            text(renderer, value, box.x + 10f, box.y + 6f, TextRole.TITLE, metricAccent(metric.id), maxWidth = box.width - 18f)
            text(renderer, metric.name, box.x + 10f, box.y + 23f, TextRole.CAPTION, maxWidth = box.width - 18f)
        }
        return top + ceil(metrics.size / columns.toFloat()).toInt() * 43f
    }

    private fun sectionTitle(renderer: MinecraftUiRenderer, title: String, icon: String, x: Float, top: Float, width: Float): Float {
        renderer.item(ItemRef(icon), Rect(x + 1f, top + 1f, 17f, 17f))
        text(renderer, title, x + 24f, top + 4f, TextRole.TITLE, maxWidth = width - 24f)
        return top + 24f
    }

    private fun emptyState(renderer: MinecraftUiRenderer, message: String, x: Float, top: Float, width: Float): Float {
        val box = Rect(x, top, width, 44f)
        surface(renderer, box)
        val measured = renderer.textMeasurer.measure(message, theme.textStyle(TextRole.SECONDARY))
        renderer.text(measured, Vec2(box.x + (box.width - measured.size.width) / 2f, box.y + 16f), theme.textColor(TextRole.SECONDARY))
        return box.bottom
    }

    private fun surface(renderer: MinecraftUiRenderer, bounds: Rect, strong: Boolean = false) {
        val color = if (strong) theme.tokens.colors.elevatedPanelBackground else theme.tokens.colors.panelBackground
        renderer.roundedRect(bounds, theme.tokens.radii.medium, color)
        if (strong) renderer.border(bounds, theme.tokens.radii.medium, Dp(1f), theme.tokens.colors.accent.withAlpha(.35f))
    }

    private fun paintScrollbar(renderer: MinecraftUiRenderer, viewport: Rect) {
        if (lastContentHeight <= viewport.height || lastContentHeight <= 0f) return
        val track = Rect(viewport.right - 3f, viewport.y, 3f, viewport.height)
        val thumbHeight = (viewport.height * viewport.height / lastContentHeight).coerceAtLeast(18f)
        val maxScroll = (lastContentHeight - viewport.height).coerceAtLeast(1f)
        val progress = (-scrollOffset / maxScroll).coerceIn(0f, 1f)
        renderer.roundedRect(track, Dp(1.5f), theme.tokens.colors.border.withAlpha(.45f))
        renderer.roundedRect(
            Rect(track.x, track.y + (track.height - thumbHeight) * progress, track.width, thumbHeight),
            Dp(1.5f),
            theme.tokens.colors.accent,
        )
    }

    private fun centredMessage(renderer: MinecraftUiRenderer, value: String, role: TextRole) {
        val measured = renderer.textMeasurer.measure(value, theme.textStyle(role), layout.content.width - 32f)
        renderer.text(
            measured,
            Vec2(
                layout.content.x + (layout.content.width - measured.size.width) / 2f,
                layout.content.y + (layout.content.height - measured.size.height) / 2f,
            ),
            theme.textColor(role),
        )
    }

    private fun text(
        renderer: MinecraftUiRenderer,
        value: String,
        x: Float,
        y: Float,
        role: TextRole,
        color: Color = theme.textColor(role),
        maxWidth: Float? = null,
    ) {
        val measured = renderer.textMeasurer.measure(
            value,
            theme.textStyle(role),
            maxWidth,
            overflow = if (maxWidth == null) TextOverflow.CLIP else TextOverflow.ELLIPSIS,
        )
        renderer.text(measured, Vec2(x, y), color)
    }

    private fun rightText(
        renderer: MinecraftUiRenderer,
        value: String,
        right: Float,
        y: Float,
        role: TextRole,
        color: Color = theme.textColor(role),
    ) {
        val measured = renderer.textMeasurer.measure(value, theme.textStyle(role))
        renderer.text(measured, Vec2(right - measured.size.width, y), color)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        val point = Vec2(event.x().toFloat(), event.y().toFloat())
        when {
            layout.close.contains(point) -> onClose()
            layout.expand.contains(point) -> { isMaximised = !isMaximised; updateLayout() }
            layout.search.contains(point) -> { searchFocused = true; caretSeconds = 0f }
            layout.previous.width > 0f && layout.previous.contains(point) -> step(-1)
            layout.next.width > 0f && layout.next.contains(point) -> step(1)
            tabHitboxes.any { (_, bounds) -> bounds.contains(point) } -> {
                selectedTab = tabHitboxes.first { (_, bounds) -> bounds.contains(point) }.first
                scrollOffset = 0f
                profileHitboxes = emptyList()
                (state as? ViewState.Ready)?.profile?.let { preloadVisuals(it, selectedTab) }
            }
            profileHitboxes.any { (_, bounds) -> bounds.contains(point) } -> {
                val profile = profileHitboxes.first { (_, bounds) -> bounds.contains(point) }.first
                load(username, profile)
            }
            !layout.window.contains(point) -> onClose()
            else -> searchFocused = false
        }
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val maxScroll = (lastContentHeight - scrollViewportHeight).coerceAtLeast(0f)
        if (maxScroll > 0f) scrollOffset = (scrollOffset + scrollY.toFloat() * 24f).coerceIn(-maxScroll, 0f)
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
        when (event.key()) {
            GLFW.GLFW_KEY_R -> { fetchCurrent(); return true }
            GLFW.GLFW_KEY_LEFT -> { stepTab(-1); return true }
            GLFW.GLFW_KEY_RIGHT -> { stepTab(1); return true }
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

    private fun stepTab(direction: Int) {
        val tabs = Tab.entries
        selectedTab = tabs[(selectedTab.ordinal + direction + tabs.size) % tabs.size]
        scrollOffset = 0f
        profileHitboxes = emptyList()
        (state as? ViewState.Ready)?.profile?.let { preloadVisuals(it, selectedTab) }
    }

    /** Real-client screenshot harness hook; normal UI navigation still goes through input above. */
    public fun selectTabForTest(label: String) {
        selectedTab = Tab.entries.first { it.label.equals(label, ignoreCase = true) }
        forcedPointerForTest = null
        scrollOffset = 0f
        profileHitboxes = emptyList()
        (state as? ViewState.Ready)?.profile?.let { preloadVisuals(it, selectedTab) }
    }

    /** Moves a screenshot harness directly to lower tab content without exercising unrelated input flow. */
    public fun scrollForTest(pixels: Float) {
        val maxScroll = (lastContentHeight - scrollViewportHeight).coerceAtLeast(0f)
        scrollOffset = -pixels.coerceIn(0f, maxScroll)
    }

    /** Positions the screenshot harness over a real inventory slot so the lore tooltip is exercised. */
    public fun hoverFirstInventoryItemForTest() {
        forcedPointerForTest = inventoryHitboxes.firstOrNull { it.first.lore.isNotEmpty() }?.second
            ?.let { Vec2(it.x + it.width / 2f, it.y + it.height / 2f) }
    }

    /**
     * Positions the harness over the [index]th hoverable thing that is actually on screen.
     *
     * Visible ones only, and that is the point: the content scrolls, so the fourth node in a tree is
     * usually below the viewport, and pointing at it would produce a screenshot with no tooltip and no
     * indication of why. A negative index parks the pointer back where the mouse really is.
     */
    public fun hoverTooltipZoneForTest(index: Int) {
        if (index < 0) {
            forcedPointerForTest = null
            return
        }
        forcedPointerForTest = visibleTooltipZones().getOrNull(index)
            ?.let { Vec2(it.x + it.width / 2f, it.y + it.height / 2f) }
    }

    /** How many hoverable things the current tab has on screen. Zero means the tooltips are not wired up. */
    public val tooltipZoneCountForTest: Int get() = visibleTooltipZones().size

    private fun visibleTooltipZones(): List<Rect> = tooltipZones.map { it.first }
        .filter { layout.content.contains(Vec2(it.x + it.width / 2f, it.y + it.height / 2f)) }

    public val loadedVisualCountForTest: Int get() = itemIcons.size

    /**
     * Fetches the icons the visible tab is about to want.
     *
     * A request is a cache key and the names the database might file it under, likeliest first — not one
     * name. That distinction is the whole of the pet fix: the database has one entry per rarity a pet is
     * *obtained* at, so a Golden Dragon somebody upgraded to mythic through Kat has no entry at its own
     * rarity, and asking for that one and stopping drew a blank Steve head for a large share of a real
     * stable. Walking down is safe because a pet's skin does not change with its rarity.
     */
    private fun preloadVisuals(profile: SkyBlockProfile, tab: Tab) {
        val requests: List<Pair<String, List<String>>> = when (tab) {
            Tab.COMBAT -> profile.slayers.mapNotNull { slayerVisualKey(it.id) }.map { it to listOf(it) }
            Tab.PETS -> profile.pets.flatMap { pet ->
                buildList {
                    add(petVisualKey(pet) to NeuItemRepository.petCandidatesFor(pet.type, pet.rarity, pet.skin))
                    pet.heldItem?.let { add(it to listOf(it)) }
                }
            }
            Tab.INVENTORY -> (
                profile.inventories.flatMap { inventory -> inventory.slots.mapNotNull { it.internalName } } +
                    profile.loadouts.flatMap { (it.armor + it.equipment).mapNotNull(ProfileItemSlot::internalName) } +
                    profile.sacks.flatMap { sack -> sack.items.map { it.id } + sack.itemId }
                ).map { it to itemCandidates(it) }
            Tab.COLLECTIONS -> profile.collections.map { it.id to itemCandidates(it.id) }
            Tab.FISHING -> profile.trophyFish.map(::trophyFishVisualKey).map { it to listOf(it) }
            Tab.FORAGING -> profile.attributes.map { it.itemId to listOf(it.itemId) }
            else -> emptyList()
        }.filter { (key, _) -> key.isNotEmpty() && requestedItemIcons.add(key) }
        if (requests.isEmpty()) return

        scope.launch {
            for (chunk in requests.chunked(8)) {
                chunk.map { (key, candidates) ->
                    async {
                        for (candidate in candidates) {
                            val item = resolveItem(candidate) ?: continue
                            itemIcons[key] = ItemRef(
                                item.minecraftId,
                                skin = item.skullTexture,
                                model = item.modelId,
                                fallbackId = visualFallback(candidate),
                            )
                            break
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /**
     * The spellings an ordinary item might be filed under.
     *
     * One character, and it decides whether every wood, dye and variant item in a profile has a picture:
     * Hypixel reports the 1.8 damage value as `LOG:1`, and the database cannot put a colon in a filename so
     * it stores `LOG-1`. Offering both costs a single extra request per item that misses.
     */
    private fun itemCandidates(id: String): List<String> =
        listOf(id, id.replace(':', '-')).filter { it.isNotEmpty() }.distinct()

    private fun petVisualKey(pet: ProfilePet): String = pet.skin?.takeIf { it.isNotBlank() }
        ?: "${pet.type};${petTier(pet.rarity)}"

    private fun trophyFishVisualKey(fish: ProfileTrophyFish): String {
        val tier = when {
            fish.diamond > 0 -> "DIAMOND"
            fish.gold > 0 -> "GOLD"
            fish.silver > 0 -> "SILVER"
            else -> "BRONZE"
        }
        return "${fish.id.uppercase()}_$tier"
    }

    private fun petTier(rarity: String): Int = when (rarity.uppercase()) {
        "COMMON" -> 0
        "UNCOMMON" -> 1
        "RARE" -> 2
        "EPIC" -> 3
        "LEGENDARY" -> 4
        else -> 5
    }

    private fun slayerVisualKey(id: String): String? = when (id.lowercase()) {
        "zombie" -> "REVENANT_FLESH"
        "spider" -> "TARANTULA_WEB"
        "wolf" -> "WOLF_TOOTH"
        "enderman" -> "NULL_SPHERE"
        "blaze" -> "DERELICT_ASHE"
        "vampire" -> "COVEN_SEAL"
        else -> null
    }

    /** NEU models that degrade to paper or another legacy carrier get a recognizable vanilla equivalent. */
    private fun visualFallback(internalName: String): String? = when (internalName) {
        "REVENANT_FLESH" -> "minecraft:rotten_flesh"
        "TARANTULA_WEB" -> "minecraft:cobweb"
        "WOLF_TOOTH" -> "minecraft:bone"
        "NULL_SPHERE" -> "minecraft:ender_pearl"
        "DERELICT_ASHE" -> "minecraft:blaze_powder"
        "COVEN_SEAL" -> "minecraft:fermented_spider_eye"
        else -> null
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

    private fun columns(width: Float, minimum: Float, maximum: Int): Int =
        (width / minimum).toInt().coerceIn(1, maximum)

    private fun Rect.inset(amount: Float): Rect = Rect(
        x + amount,
        y + amount,
        (width - amount * 2f).coerceAtLeast(0f),
        (height - amount * 2f).coerceAtLeast(0f),
    )

    private fun String.humanName(): String = lowercase().split('_').joinToString(" ") {
        it.replaceFirstChar(Char::uppercase)
    }

    private fun formatDate(epochMillis: Long): String = DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis))
    private fun formatDuration(seconds: Long): String = when {
        seconds >= 3_600 -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }

    private fun formatLevel(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private fun formatNumber(value: Double): String {
        val absolute = kotlin.math.abs(value)
        val (amount, suffix) = when {
            absolute >= 1_000_000_000_000 -> value / 1_000_000_000_000 to "t"
            absolute >= 1_000_000_000 -> value / 1_000_000_000 to "b"
            absolute >= 1_000_000 -> value / 1_000_000 to "m"
            absolute >= 1_000 -> value / 1_000 to "k"
            else -> return value.roundToInt().toString()
        }
        return String.format(Locale.ROOT, if (kotlin.math.abs(amount) >= 100) "%.0f%s" else "%.1f%s", amount, suffix)
    }

    private fun rarityColor(rarity: String): Color = when (rarity.uppercase()) {
        "COMMON" -> Color.White
        "UNCOMMON" -> Color.parse("#55FF55")
        "RARE" -> Color.parse("#5555FF")
        "EPIC" -> Color.parse("#AA00AA")
        "LEGENDARY" -> Color.parse("#FFAA00")
        "MYTHIC" -> Color.parse("#FF55FF")
        "DIVINE" -> Color.parse("#55FFFF")
        else -> theme.textColor(TextRole.LABEL)
    }

    private fun metricAccent(id: String): Color {
        val key = id.lowercase()
        return when {
            "coin" in key || "bank" in key || "purse" in key || "gold" in key -> Color.parse("#F6C453")
            "kill" in key || "death" in key || "boss" in key || "combat" in key -> Color.parse("#FF6B6B")
            "garden" in key || "crop" in key || "visitor" in key || "farming" in key -> Color.parse("#78D381")
            "mining" in key || "powder" in key || "gem" in key || "crystal" in key -> Color.parse("#68C7E8")
            "rift" in key || "essence" in key || "magic" in key || "power" in key -> Color.parse("#B58CFF")
            else -> theme.tokens.colors.accent
        }
    }

    private fun skillItem(id: String): String = when (id.uppercase()) {
        "FARMING" -> "minecraft:golden_hoe"
        "MINING" -> "minecraft:diamond_pickaxe"
        "COMBAT" -> "minecraft:diamond_sword"
        "FORAGING" -> "minecraft:dark_oak_sapling"
        "FISHING" -> "minecraft:fishing_rod"
        "ENCHANTING" -> "minecraft:enchanting_table"
        "ALCHEMY" -> "minecraft:brewing_stand"
        "TAMING" -> "minecraft:bone"
        "CARPENTRY" -> "minecraft:crafting_table"
        "RUNECRAFTING" -> "minecraft:magma_cream"
        "SOCIAL" -> "minecraft:emerald"
        "HUNTING" -> "minecraft:compass"
        else -> "minecraft:experience_bottle"
    }

    private fun slayerItem(id: String): String = when (id.lowercase()) {
        "zombie" -> "minecraft:rotten_flesh"
        "spider" -> "minecraft:spider_eye"
        "wolf" -> "minecraft:bone"
        "enderman" -> "minecraft:ender_pearl"
        "blaze" -> "minecraft:blaze_powder"
        "vampire" -> "minecraft:fermented_spider_eye"
        else -> "minecraft:target"
    }

    private fun dungeonItem(id: String): String = when (id.lowercase()) {
        "catacombs" -> "minecraft:wither_skeleton_skull"
        "healer" -> "minecraft:golden_apple"
        "mage" -> "minecraft:blaze_rod"
        "berserk" -> "minecraft:diamond_sword"
        "archer" -> "minecraft:bow"
        "tank" -> "minecraft:iron_chestplate"
        else -> "minecraft:experience_bottle"
    }

    private fun factionRank(reputation: Int): String = when {
        reputation >= 12_000 -> "Hero"
        reputation >= 7_000 -> "Honored"
        reputation >= 3_000 -> "Trusted"
        reputation >= 1_000 -> "Friendly"
        else -> "Neutral"
    }

    private fun kuudraItem(id: String): String = when (id) {
        "hot" -> "minecraft:magma_cream"
        "burning" -> "minecraft:blaze_powder"
        "fiery" -> "minecraft:fire_charge"
        "infernal" -> "minecraft:nether_star"
        else -> "minecraft:lava_bucket"
    }

    private fun kuudraColor(id: String): Color = when (id) {
        "hot" -> Color.rgb(255, 170, 0)
        "burning" -> Color.rgb(255, 85, 85)
        "fiery" -> Color.rgb(170, 0, 0)
        "infernal" -> Color.rgb(170, 0, 170)
        else -> theme.textColor(TextRole.LABEL)
    }

    private fun dojoItem(id: String): String = when (id) {
        "mob_kb" -> "minecraft:slime_ball"
        "wall_jump" -> "minecraft:rabbit_foot"
        "archer" -> "minecraft:bow"
        "snake" -> "minecraft:feather"
        "sword_swap" -> "minecraft:iron_sword"
        "fireball" -> "minecraft:fire_charge"
        else -> "minecraft:target"
    }

    private fun dojoGrade(points: Int): String = when {
        points >= 1_000 -> "S"
        points >= 800 -> "A"
        points >= 600 -> "B"
        points >= 400 -> "C"
        points >= 200 -> "D"
        else -> "F"
    }

    private fun dojoGradeColor(points: Int): Color = when {
        points >= 1_000 -> Color.rgb(255, 170, 0)
        points >= 800 -> Color.rgb(255, 85, 255)
        points >= 600 -> Color.rgb(85, 85, 255)
        points >= 200 -> Color.rgb(85, 255, 85)
        else -> Color.rgb(255, 85, 85)
    }

    private fun sectionItem(section: ProfileSection): String = when (section.id) {
        "attributes" -> "minecraft:enchanted_book"
        "bestiary" -> "minecraft:zombie_head"
        "events" -> "minecraft:clock"
        "experimentation" -> "minecraft:enchanting_table"
        "foraging", "foraging_core", "foraging_summary" -> "minecraft:oak_log"
        "forge" -> "minecraft:blast_furnace"
        "garden_player_data", "jacobs_contest", "farming_summary" -> "minecraft:wheat"
        "glacite_player_data" -> "minecraft:packed_ice"
        "inventory" -> "minecraft:ender_chest"
        "shared_inventory" -> "minecraft:shulker_box"
        "item_data", "loadout" -> "minecraft:barrel"
        "leveling" -> "minecraft:experience_bottle"
        "nether_island_player_data" -> "minecraft:netherrack"
        "objectives", "quests" -> "minecraft:writable_book"
        "rift" -> "minecraft:ender_eye"
        "sacks" -> "minecraft:bundle"
        "safari" -> "minecraft:compass"
        "shards" -> "minecraft:amethyst_shard"
        "skill_tree" -> "minecraft:beacon"
        "temples" -> "minecraft:chiseled_stone_bricks"
        "trophy_fish" -> "minecraft:golden_boots"
        else -> DISTINCT_SECTION_ITEMS[kotlin.math.abs(section.id.hashCode()) % DISTINCT_SECTION_ITEMS.size]
    }

    private fun collectionItem(id: String): String {
        val key = id.uppercase()
        return when {
            key == "SEEDS" -> "minecraft:wheat_seeds"
            key == "LOG" -> "minecraft:oak_log"
            key == "LOG:1" -> "minecraft:spruce_log"
            key == "LOG:2" -> "minecraft:birch_log"
            key == "LOG:3" -> "minecraft:jungle_log"
            key == "LOG_2" -> "minecraft:acacia_log"
            key == "LOG_2:1" -> "minecraft:dark_oak_log"
            "MANGROVE" in key -> "minecraft:mangrove_log"
            "MOONFLOWER" in key -> "minecraft:blue_orchid"
            "LILY" in key -> "minecraft:lily_pad"
            "SPONGE" in key -> "minecraft:sponge"
            "GUNPOWDER" in key || "SULPHUR" in key -> "minecraft:gunpowder"
            "WHEAT" in key -> "minecraft:wheat"
            "CARROT" in key -> "minecraft:carrot"
            "POTATO" in key -> "minecraft:potato"
            "MELON" in key -> "minecraft:melon_slice"
            "PUMPKIN" in key -> "minecraft:pumpkin"
            "SUGAR" in key -> "minecraft:sugar_cane"
            "COCOA" in key -> "minecraft:cocoa_beans"
            "MUSHROOM" in key -> "minecraft:red_mushroom"
            "COAL" in key -> "minecraft:coal"
            "IRON" in key -> "minecraft:iron_ingot"
            "GOLD" in key -> "minecraft:gold_ingot"
            "DIAMOND" in key -> "minecraft:diamond"
            "EMERALD" in key -> "minecraft:emerald"
            "REDSTONE" in key -> "minecraft:redstone"
            "QUARTZ" in key -> "minecraft:quartz"
            "GLOWSTONE" in key -> "minecraft:glowstone_dust"
            "ENDER" in key -> "minecraft:ender_pearl"
            "BLAZE" in key -> "minecraft:blaze_rod"
            "BONE" in key -> "minecraft:bone"
            "STRING" in key -> "minecraft:string"
            "ROTTEN" in key -> "minecraft:rotten_flesh"
            "SPIDER" in key -> "minecraft:spider_eye"
            "MAGMA" in key -> "minecraft:magma_cream"
            "SLIME" in key -> "minecraft:slime_ball"
            "GHAST" in key -> "minecraft:ghast_tear"
            "FEATHER" in key -> "minecraft:feather"
            "LEATHER" in key -> "minecraft:leather"
            "PORK" in key -> "minecraft:porkchop"
            "CHICKEN" in key -> "minecraft:chicken"
            "MUTTON" in key -> "minecraft:mutton"
            "RABBIT" in key -> "minecraft:rabbit"
            "CACTUS" in key -> "minecraft:cactus"
            "NETHER_STALK" in key -> "minecraft:nether_wart"
            "INK" in key -> "minecraft:ink_sac"
            "PRISMARINE" in key -> "minecraft:prismarine_shard"
            "CLAY" in key -> "minecraft:clay_ball"
            "SAND" in key -> "minecraft:sand"
            "GRAVEL" in key -> "minecraft:gravel"
            "OBSIDIAN" in key -> "minecraft:obsidian"
            "ICE" in key -> "minecraft:ice"
            "LOG" in key || "WOOD" in key -> "minecraft:oak_log"
            "FISH" in key || "SALMON" in key -> "minecraft:cod"
            else -> DISTINCT_COLLECTION_ITEMS[kotlin.math.abs(key.hashCode()) % DISTINCT_COLLECTION_ITEMS.size]
        }
    }

    private fun collectionCategoryItem(category: String): String = when (category.lowercase()) {
        "farming" -> "minecraft:golden_hoe"
        "mining" -> "minecraft:diamond_pickaxe"
        "combat" -> "minecraft:diamond_sword"
        "foraging" -> "minecraft:oak_log"
        "fishing" -> "minecraft:fishing_rod"
        else -> "minecraft:item_frame"
    }

    private fun inventoryIcon(id: String): String = when {
        "ender" in id -> "minecraft:ender_chest"
        "armor" in id -> "minecraft:diamond_chestplate"
        "equipment" in id -> "minecraft:elytra"
        "vault" in id -> "minecraft:chest"
        "bag" in id -> "minecraft:bundle"
        "backpack" in id -> "minecraft:shulker_box"
        else -> "minecraft:chest"
    }

    private fun itemFallback(item: ProfileItemSlot): String {
        val key = (item.internalName ?: item.displayName).orEmpty().uppercase()
        return when {
            "SWORD" in key -> "minecraft:diamond_sword"
            "PICK" in key || "DRILL" in key -> "minecraft:diamond_pickaxe"
            "BOW" in key -> "minecraft:bow"
            "HELMET" in key -> "minecraft:diamond_helmet"
            "CHESTPLATE" in key -> "minecraft:diamond_chestplate"
            "LEGGINGS" in key -> "minecraft:diamond_leggings"
            "BOOTS" in key -> "minecraft:diamond_boots"
            else -> "minecraft:barrier"
        }
    }

    private fun bestiaryLocationItem(id: String): String = when {
        "end" in id -> "minecraft:end_stone"
        "crimson" in id -> "minecraft:netherrack"
        "dungeon" in id -> "minecraft:wither_skeleton_skull"
        "sea" in id -> "minecraft:prismarine"
        "mining" in id -> "minecraft:diamond_pickaxe"
        "spider" in id -> "minecraft:cobweb"
        "park" in id -> "minecraft:spruce_sapling"
        "rift" in id -> "minecraft:ender_eye"
        else -> "minecraft:grass_block"
    }

    private fun treeNodeColor(kind: String, level: Int, maxLevel: Int, enabled: Boolean?): Color = when {
        level <= 0 -> Color.parse("#555B66")
        enabled == false -> Color.parse("#E65B65")
        kind == "ABILITY" -> Color.parse("#F0C94D")
        kind == "CORE" -> theme.tokens.colors.accent
        level >= maxLevel -> Color.parse("#62D8F5")
        else -> Color.parse("#62D982")
    }

    private fun treeNodeItem(
        tree: String,
        kind: String,
        level: Int,
        maxLevel: Int,
        enabled: Boolean?,
        selectedAbility: String?,
        name: String,
    ): String = when {
        level <= 0 -> if (tree == "mining") "minecraft:coal" else "minecraft:pale_oak_button"
        enabled == false -> "minecraft:redstone"
        kind == "ABILITY" && selectedAbility.equals(name, true) -> if (tree == "mining") "minecraft:emerald_block" else "minecraft:oak_sapling"
        kind == "ABILITY" -> if (tree == "mining") "minecraft:redstone_block" else "minecraft:cherry_sapling"
        kind == "CORE" && level >= maxLevel -> if (tree == "mining") "minecraft:diamond_block" else "minecraft:oak_wood"
        kind == "CORE" -> if (tree == "mining") "minecraft:copper_block" else "minecraft:stripped_birch_wood"
        level >= maxLevel -> if (tree == "mining") "minecraft:diamond" else "minecraft:oak_log"
        else -> if (tree == "mining") "minecraft:emerald" else "minecraft:stripped_oak_log"
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val HERO_HEIGHT = 48f
        const val SKILL_CARD_HEIGHT = 43f
        const val GAP = 7f
        val DISTINCT_SECTION_ITEMS = listOf(
            "minecraft:amethyst_shard",
            "minecraft:echo_shard",
            "minecraft:prismarine_crystals",
            "minecraft:blaze_powder",
            "minecraft:clock",
            "minecraft:compass",
        )
        val DISTINCT_COLLECTION_ITEMS = listOf(
            "minecraft:grass_block",
            "minecraft:stone",
            "minecraft:oak_sapling",
            "minecraft:flint",
            "minecraft:honeycomb",
            "minecraft:sea_pickle",
        )
        val RIFT_TIMECHARMS = linkedMapOf(
            "wyldly_supreme" to "Supreme Timecharm", "chicken_n_egg" to "Chicken N Egg Timecharm",
            "mirrored" to "Mirrorverse Timecharm", "citizen" to "SkyBlock Citizen Timecharm",
            "lazy_living" to "Living Timecharm", "slime" to "Globulate Timecharm",
            "vampiric" to "Vampiric Timecharm", "mountain" to "Celestial Timecharm",
        )
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
    }
}
