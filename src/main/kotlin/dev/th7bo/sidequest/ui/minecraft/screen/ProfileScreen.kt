package dev.th7bo.sidequest.ui.minecraft.screen

import dev.th7bo.sidequest.platform.core.profile.SkyCryptUrls
import dev.th7bo.sidequest.protocol.ApiErrorCode
import dev.th7bo.sidequest.protocol.ApiResult
import dev.th7bo.sidequest.protocol.ProfileCollection
import dev.th7bo.sidequest.protocol.ProfileMetric
import dev.th7bo.sidequest.protocol.ProfilePet
import dev.th7bo.sidequest.protocol.ProfileProgress
import dev.th7bo.sidequest.protocol.ProfileSection
import dev.th7bo.sidequest.protocol.ProfileSkill
import dev.th7bo.sidequest.protocol.ProfileSlayer
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.minecraft.client.gui.GuiGraphicsExtractor
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
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A tabbed, item-backed native view over Hypixel's public SkyBlock profile data. */
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

    private enum class Tab(val label: String, val item: String) {
        OVERVIEW("Overview", "minecraft:player_head"),
        SKILLS("Skills", "minecraft:diamond_sword"),
        COMBAT("Combat", "minecraft:wither_skeleton_skull"),
        INVENTORY("Inventory", "minecraft:chest"),
        COLLECTIONS("Collections", "minecraft:item_frame"),
        PETS("Pets", "minecraft:bone"),
        MINING("Mining", "minecraft:diamond_pickaxe"),
        FARMING("Farming", "minecraft:wheat"),
        FISHING("Fishing", "minecraft:fishing_rod"),
        FORAGING("Foraging", "minecraft:oak_log"),
        RIFT("Rift", "minecraft:ender_eye"),
        MORE("More", "minecraft:nether_star"),
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
                    is ApiResult.Success -> ViewState.Ready(result.value)
                    is ApiResult.Failure -> ViewState.Failed(friendlyError(result.error.code))
                }
            }
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTicks: Float) {
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
            renderer.pushClip(layout.content)
            try {
                paintContent(renderer)
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
                    credit = "official Hypixel API",
                ),
            )
        } finally {
            renderer.endFrame()
        }
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
        val outer = layout.content
        val inset = if (outer.width < 420f) 7f else 11f
        val width = outer.width - inset * 2f
        var y = outer.y + inset

        y = paintHero(renderer, profile, Rect(outer.x + inset, y, width, HERO_HEIGHT)) + GAP
        y = paintTabs(renderer, Rect(outer.x + inset, y, width, TAB_HEIGHT)) + GAP

        val viewport = Rect(outer.x + inset, y, width, outer.bottom - y - inset)
        scrollViewportHeight = viewport.height
        renderer.pushClip(viewport)
        try {
            val start = viewport.y + scrollOffset
            val end = when (selectedTab) {
                Tab.OVERVIEW -> paintOverview(renderer, profile, viewport.x, start, viewport.width)
                Tab.SKILLS -> paintSkills(renderer, profile.skills, viewport.x, start, viewport.width)
                Tab.COMBAT -> paintCombat(renderer, profile, viewport.x, start, viewport.width)
                Tab.INVENTORY -> paintProfileSections(renderer, profile, setOf("inventory", "shared_inventory", "sacks", "loadout"), viewport.x, start, viewport.width)
                Tab.COLLECTIONS -> paintCollections(renderer, profile.collections, viewport.x, start, viewport.width)
                Tab.PETS -> paintPets(renderer, profile.pets, viewport.x, start, viewport.width)
                Tab.MINING -> paintMining(renderer, profile, viewport.x, start, viewport.width)
                Tab.FARMING -> paintFarming(renderer, profile, viewport.x, start, viewport.width)
                Tab.FISHING -> paintProfileSections(renderer, profile, setOf("trophy_fish"), viewport.x, start, viewport.width)
                Tab.FORAGING -> paintProfileSections(renderer, profile, setOf("foraging", "foraging_core", "attributes"), viewport.x, start, viewport.width)
                Tab.RIFT -> paintProfileSections(renderer, profile, setOf("rift"), viewport.x, start, viewport.width)
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
        val avatar = Rect(bounds.x + 11f, bounds.y + 8f, 32f, 32f)
        renderer.roundedRect(avatar, theme.tokens.radii.medium, theme.tokens.colors.windowBackground)
        renderer.item(ItemRef("minecraft:player_head", skin = profile.skinTexture), avatar.inset(4f))

        val textX = avatar.right + 10f
        text(renderer, profile.username, textX, bounds.y + 8f, TextRole.TITLE, theme.tokens.colors.accent)
        val mode = profile.gameMode?.humanName()?.let { " · $it" }.orEmpty()
        text(renderer, "${profile.profileName}$mode${if (profile.selected) " · selected" else ""}", textX, bounds.y + 24f, TextRole.SECONDARY)

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

    private fun paintTabs(renderer: MinecraftUiRenderer, bounds: Rect): Float {
        surface(renderer, bounds)
        val width = bounds.width / Tab.entries.size
        val showLabels = width >= 58f
        tabHitboxes = Tab.entries.mapIndexed { index, tab ->
            val tabBounds = Rect(bounds.x + index * width, bounds.y, width, bounds.height)
            if (tab == selectedTab) {
                renderer.roundedRect(tabBounds.inset(3f), theme.tokens.radii.medium, theme.tokens.colors.hoverBackground)
                renderer.fillRect(Rect(tabBounds.x + 6f, tabBounds.bottom - 2f, tabBounds.width - 12f, 2f), theme.tokens.colors.accent)
            }
            val icon = Rect(tabBounds.x + if (showLabels) 7f else (tabBounds.width - 15f) / 2f, tabBounds.y + 5f, 15f, 15f)
            renderer.item(ItemRef(tab.item), icon)
            if (showLabels) text(renderer, tab.label, icon.right + 4f, tabBounds.y + 8f, TextRole.CAPTION, maxWidth = tabBounds.right - icon.right - 7f)
            tab to tabBounds
        }
        return bounds.bottom
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
                renderer.item(ItemRef(if (choice.selected) "minecraft:nether_star" else "minecraft:paper"), Rect(box.x + 5f, box.y + 5f, 15f, 15f))
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
            renderer.item(ItemRef(slayerItem(slayer.id)), Rect(box.x + 9f, box.y + 11f, 34f, 34f))
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
        y = paintProfileSections(renderer, profile, setOf("bestiary", "nether_island_player_data"), x, y, width, emptyWhenMissing = false)
        return if (y == top) emptyState(renderer, "Combat API disabled", x, y, width) else y
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
        var y = sectionTitle(renderer, "Collections", "minecraft:chest", x, top, width)
        if (values.isEmpty()) return emptyState(renderer, "Collection API disabled", x, y, width)
        val columns = columns(width, 145f, 5)
        val gap = 6f
        val cardWidth = (width - gap * (columns - 1)) / columns
        for ((index, collection) in values.withIndex()) {
            val row = index / columns
            val column = index % columns
            val box = Rect(x + column * (cardWidth + gap), y + row * 36f, cardWidth, 30f)
            surface(renderer, box)
            renderer.item(ItemRef(collectionItem(collection.id)), Rect(box.x + 6f, box.y + 6f, 18f, 18f))
            text(renderer, collection.name, box.x + 29f, box.y + 5f, TextRole.CAPTION, maxWidth = box.width - 35f)
            text(renderer, formatNumber(collection.amount.toDouble()), box.x + 29f, box.y + 17f, TextRole.LABEL, theme.tokens.colors.accent)
        }
        return y + ceil(values.size / columns.toFloat()).toInt() * 36f
    }

    private fun paintPets(renderer: MinecraftUiRenderer, pets: List<ProfilePet>, x: Float, top: Float, width: Float): Float {
        var y = sectionTitle(renderer, "Pets", "minecraft:bone", x, top, width)
        if (pets.isEmpty()) return emptyState(renderer, "Pet API disabled or no pets", x, y, width)
        val columns = columns(width, 185f, 4)
        val gap = 7f
        val cardWidth = (width - gap * (columns - 1)) / columns
        for ((index, pet) in pets.withIndex()) {
            val row = index / columns
            val column = index % columns
            val box = Rect(x + column * (cardWidth + gap), y + row * 70f, cardWidth, 63f)
            surface(renderer, box, strong = pet.active)
            renderer.item(ItemRef(petItem(pet.type)), Rect(box.x + 7f, box.y + 9f, 28f, 28f))
            text(renderer, pet.name, box.x + 42f, box.y + 7f, TextRole.LABEL, rarityColor(pet.rarity), maxWidth = box.width - 49f)
            text(renderer, pet.rarity.humanName() + if (pet.active) " · active" else "", box.x + 42f, box.y + 21f, TextRole.CAPTION)
            text(renderer, "${formatNumber(pet.experience)} XP", box.x + 42f, box.y + 34f, TextRole.CAPTION)
            val detail = buildList {
                pet.heldItem?.let { add(it.removePrefix("PET_ITEM_").humanName()) }
                if (pet.candyUsed > 0) add("${pet.candyUsed} candy")
            }.joinToString(" · ")
            if (detail.isNotEmpty()) text(renderer, detail, box.x + 42f, box.y + 47f, TextRole.CAPTION, maxWidth = box.width - 49f)
        }
        return y + ceil(pets.size / columns.toFloat()).toInt() * 70f
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
        y = paintProfileSections(renderer, profile, setOf("glacite_player_data", "skill_tree", "forge"), x, y, width, emptyWhenMissing = false)
        return if (y == top) emptyState(renderer, "Mining API disabled", x, y, width) else y
    }

    private fun paintFarming(renderer: MinecraftUiRenderer, profile: SkyBlockProfile, x: Float, top: Float, width: Float): Float {
        var y = top
        if (profile.garden.isNotEmpty()) {
            y = sectionTitle(renderer, "Garden", "minecraft:wheat", x, y, width)
            y = metricGrid(renderer, profile.garden, x, y, width, preferredColumns = 4) + GAP
        }
        y = paintProfileSections(renderer, profile, setOf("garden_player_data", "jacobs_contest"), x, y, width, emptyWhenMissing = false)
        return if (y == top) emptyState(renderer, "Farming API disabled", x, y, width) else y
    }

    private fun paintMore(renderer: MinecraftUiRenderer, profile: SkyBlockProfile, x: Float, top: Float, width: Float): Float {
        var y = top
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
            "garden_player_data", "jacobs_contest", "trophy_fish", "foraging", "foraging_core", "attributes", "rift",
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
            val box = Rect(x + column * (cardWidth + gap), top + row * 39f, cardWidth, 33f)
            surface(renderer, box)
            renderer.item(ItemRef(metricItem(metric.id)), Rect(box.x + 6f, box.y + 7f, 19f, 19f))
            text(renderer, metric.name, box.x + 30f, box.y + 5f, TextRole.CAPTION, maxWidth = box.width - 36f)
            val value = metric.text ?: metric.value?.let(::formatNumber) ?: "—"
            text(renderer, value, box.x + 30f, box.y + 18f, TextRole.LABEL, theme.tokens.colors.accent, maxWidth = box.width - 36f)
        }
        return top + ceil(metrics.size / columns.toFloat()).toInt() * 39f
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
        else -> "minecraft:iron_sword"
    }

    private fun dungeonItem(id: String): String = when (id.lowercase()) {
        "catacombs" -> "minecraft:wither_skeleton_skull"
        "healer" -> "minecraft:golden_apple"
        "mage" -> "minecraft:blaze_rod"
        "berserk" -> "minecraft:iron_sword"
        "archer" -> "minecraft:bow"
        "tank" -> "minecraft:iron_chestplate"
        else -> "minecraft:nether_star"
    }

    private fun sectionItem(section: ProfileSection): String = when (section.id) {
        "attributes" -> "minecraft:enchanted_book"
        "bestiary" -> "minecraft:zombie_head"
        "events" -> "minecraft:clock"
        "experimentation" -> "minecraft:enchanting_table"
        "foraging", "foraging_core" -> "minecraft:oak_log"
        "forge" -> "minecraft:blast_furnace"
        "garden_player_data", "jacobs_contest" -> "minecraft:wheat"
        "glacite_player_data" -> "minecraft:packed_ice"
        "inventory", "shared_inventory" -> "minecraft:chest"
        "item_data", "loadout" -> "minecraft:barrel"
        "leveling" -> "minecraft:experience_bottle"
        "nether_island_player_data" -> "minecraft:netherrack"
        "objectives", "quests" -> "minecraft:writable_book"
        "rift" -> "minecraft:ender_eye"
        "sacks" -> "minecraft:bundle"
        "safari" -> "minecraft:compass"
        "shards" -> "minecraft:amethyst_shard"
        "skill_tree" -> "minecraft:nether_star"
        "temples" -> "minecraft:chiseled_stone_bricks"
        "trophy_fish" -> "minecraft:golden_boots"
        else -> "minecraft:paper"
    }

    private fun collectionItem(id: String): String {
        val key = id.uppercase()
        return when {
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
            "LOG" in key || "WOOD" in key -> "minecraft:oak_log"
            "FISH" in key || "SALMON" in key -> "minecraft:cod"
            else -> "minecraft:chest"
        }
    }

    private fun petItem(type: String): String = when (type.uppercase()) {
        "ENDER_DRAGON" -> "minecraft:dragon_head"
        "PHOENIX" -> "minecraft:blaze_powder"
        "SHEEP" -> "minecraft:white_wool"
        "RABBIT" -> "minecraft:rabbit_foot"
        "WOLF" -> "minecraft:bone"
        "OCELOT" -> "minecraft:cod"
        "ELEPHANT" -> "minecraft:leather"
        "MONKEY" -> "minecraft:jungle_sapling"
        "ROCK" -> "minecraft:stone"
        "DOLPHIN" -> "minecraft:prismarine_shard"
        "SQUID" -> "minecraft:ink_sac"
        "TIGER" -> "minecraft:orange_dye"
        "LION" -> "minecraft:golden_apple"
        else -> "minecraft:player_head"
    }

    private fun metricItem(id: String): String {
        val key = id.lowercase()
        return when {
            "purse" in key || "bank" in key || "coin" in key -> "minecraft:gold_ingot"
            "essence" in key -> "minecraft:nether_star"
            "powder" in key -> "minecraft:gunpowder"
            "token" in key -> "minecraft:sunflower"
            "fairy" in key -> "minecraft:pink_dye"
            "kill" in key -> "minecraft:iron_sword"
            "death" in key -> "minecraft:skeleton_skull"
            "museum" in key || "item" in key -> "minecraft:painting"
            "visitor" in key -> "minecraft:emerald"
            "crop" in key || "garden" in key -> "minecraft:wheat"
            "plot" in key -> "minecraft:grass_block"
            "power" in key -> "minecraft:beacon"
            "level" in key || "experience" in key -> "minecraft:experience_bottle"
            else -> "minecraft:paper"
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val HERO_HEIGHT = 48f
        const val TAB_HEIGHT = 25f
        const val SKILL_CARD_HEIGHT = 43f
        const val GAP = 7f
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
    }
}
