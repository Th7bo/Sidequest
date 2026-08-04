package dev.th7bo.sidequest

import dev.th7bo.sidequest.platform.chat.DropRarity
import dev.th7bo.sidequest.platform.core.skyblock.LevelPalette
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.ui.binding.bind
import dev.th7bo.sidequest.ui.components.GlyphIconIds
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.SettingSerializers
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.config.option
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.validation.ValidationResult
import dev.th7bo.sidequest.ui.validation.Validator
import dev.th7bo.sidequest.ui.validation.Validators

/**
 * The configuration screen.
 *
 * One declarative block; no widget or renderer class appears anywhere in it.
 *
 * **Every setting here reaches something.** That is worth stating because the first version of this file did
 * not: it was a demonstration of the framework, with half its controls bound to properties nothing read. A
 * settings screen whose switches do nothing is worse than no settings screen, because somebody will change
 * one and believe it.
 *
 * The layout follows what a person is trying to do rather than how the code is arranged. Rare drops are their
 * own category because that is what somebody comes here to adjust; they are not filed under "chat" because
 * that happens to be where the message is parsed.
 */
public fun buildSidequestConfigScreen(): ConfigScreen {
    // Mirrors for the settings that gate others. Read by `visibleWhen`, which needs observable state rather
    // than a plain property.
    val notificationsOn = mutableStateOf(SidequestSettings.Notifications.isEnabled, "notifications.enabled")
    val cinematicsOn = mutableStateOf(SidequestSettings.Cinematics.isEnabled, "cinematics.enabled")
    val dropsOn = mutableStateOf(SidequestSettings.Drops.isEnabled, "drops.enabled")
    val cosmeticsOn = mutableStateOf(SidequestSettings.Cosmetics.isEnabled, "cosmetics.enabled")
    val playtimeOn = mutableStateOf(SidequestSettings.Playtime.isEnabled, "playtime.enabled")
    val titleScreenOn = mutableStateOf(SidequestSettings.TitleScreen.isEnabled, "title.enabled")
    val levelsOn = mutableStateOf(SidequestSettings.Levels.isEnabled, "levels.enabled")

    /** Every change pushes into the services. See [SidequestSettings.applyToPlatform]. */
    fun applied() = SidequestSettings.applyToPlatform()

    return configScreen(id("config"), "Sidequest", "Configure how Sidequest looks and behaves.") {
        category(id("general"), "General", description = "Appearance and the master switches", icon = GlyphIconIds.settings) {
            section("Appearance", description = "How every Sidequest screen looks", icon = GlyphIconIds.appearance, collapsible = true) {
                dropdown(
                    id = id("general.theme"),
                    title = "Theme",
                    description = "Colour scheme for every Sidequest screen",
                    value = bind(SidequestSettings::theme),
                    options = listOf(
                        option("dark", "Dark", "dark"),
                        option("light", "Light", "light"),
                        option("high_contrast_dark", "High Contrast", "high_contrast_dark"),
                    ),
                )
                colorPicker(
                    id = id("general.accent_color"),
                    title = "Accent colour",
                    description = "Highlights, sliders and progress bars. A cosmetic style overrides this.",
                    value = bind(SidequestSettings::accentColor),
                    presets = ACCENT_PRESETS,
                )
                toggle(
                    id = id("general.compact_mode"),
                    title = "Compact mode",
                    description = "Tighter spacing throughout",
                    value = bind(SidequestSettings::compactMode),
                ) {
                    keywords("density", "spacing", "small")
                }
                decimalSlider(
                    id = id("general.hud_scale"),
                    title = "HUD scale",
                    value = bind(SidequestSettings::hudScale),
                    range = 0.5f..2.5f,
                    step = 0.1f,
                    format = { String.format("%.1f×", it) },
                )
            }

            section("Serious mode", description = "When you would rather it stayed out of the way", icon = GlyphIconIds.quiet, collapsible = true) {
                toggle(
                    id = id("general.serious_mode"),
                    title = "Serious mode",
                    description = "Silences the playful half: cinematics, effect sounds and non-essential toasts",
                    value = bind(
                        get = { SidequestSettings.seriousMode },
                        set = { SidequestSettings.seriousMode = it; applied() },
                        debugName = "seriousMode",
                    ),
                ) {
                    // One switch rather than the three the services actually carry. See the property's own note.
                    keywords("quiet", "focus", "silent", "no distractions")
                }
            }
        }

        category(id("features"), "Features", description = "What the mod does, and how loudly", icon = GlyphIconIds.features) {
            section("Notifications", description = "Toasts and their timing", icon = GlyphIconIds.notifications, collapsible = true) {
                toggle(
                    id = id("notifications.enabled"),
                    title = "Show notifications",
                    value = bind(
                        get = { SidequestSettings.Notifications.isEnabled },
                        set = { SidequestSettings.Notifications.isEnabled = it; notificationsOn.value = it; applied() },
                        debugName = "notifications.enabled",
                    ),
                )
                slider(
                    id = id("notifications.duration"),
                    title = "How long they stay",
                    value = bind(
                        get = { SidequestSettings.Notifications.durationSeconds },
                        set = { SidequestSettings.Notifications.durationSeconds = it; applied() },
                        debugName = "notifications.duration",
                    ),
                    range = 1..30,
                    format = { "$it s" },
                    validator = Validators.intRange(1..30),
                ) {
                    visibleWhen = notificationsOn
                }
                toggle(
                    id = id("notifications.queue_while_busy"),
                    title = "Hold them while you are busy",
                    description = "A toast waits for a safe moment instead of appearing mid-fight",
                    value = bind(
                        get = { SidequestSettings.Notifications.queueWhileBusy },
                        set = { SidequestSettings.Notifications.queueWhileBusy = it; applied() },
                        debugName = "notifications.queue",
                    ),
                ) {
                    visibleWhen = notificationsOn
                }
            }

            section("Sound", description = "Volumes, and who may make one", icon = GlyphIconIds.sound, collapsible = true, startsCollapsed = true) {
                decimalSlider(
                    id = id("sound.master"),
                    title = "Master",
                    value = bind(
                        get = { SidequestSettings.Sound.master },
                        set = { SidequestSettings.Sound.master = it; applied() },
                        debugName = "sound.master",
                    ),
                    range = 0f..1f,
                    step = 0.05f,
                    format = ::percent,
                )
                decimalSlider(
                    id = id("sound.interface"),
                    title = "Interface",
                    description = "Confirmations and clicks. Serious mode leaves these alone — they are how you know something worked.",
                    value = bind(
                        get = { SidequestSettings.Sound.interfaceVolume },
                        set = { SidequestSettings.Sound.interfaceVolume = it; applied() },
                        debugName = "sound.interface",
                    ),
                    range = 0f..1f,
                    step = 0.05f,
                    format = ::percent,
                )
                decimalSlider(
                    id = id("sound.effects"),
                    title = "Effects",
                    description = "Drops, progression, cinematics",
                    value = bind(
                        get = { SidequestSettings.Sound.effects },
                        set = { SidequestSettings.Sound.effects = it; applied() },
                        debugName = "sound.effects",
                    ),
                    range = 0f..1f,
                    step = 0.05f,
                    format = ::percent,
                )
                decimalSlider(
                    id = id("sound.soundboard"),
                    title = "Soundboard",
                    description = "Sounds other people trigger in your ears. Set this to zero and they cannot.",
                    value = bind(
                        get = { SidequestSettings.Sound.soundboard },
                        set = { SidequestSettings.Sound.soundboard = it; applied() },
                        debugName = "sound.soundboard",
                    ),
                    range = 0f..1f,
                    step = 0.05f,
                    format = ::percent,
                )
            }

            section("Cinematics", description = "The things worth stopping for", icon = GlyphIconIds.cinematics, collapsible = true, startsCollapsed = true) {
                toggle(
                    id = id("cinematics.enabled"),
                    title = "Play cinematics",
                    value = bind(
                        get = { SidequestSettings.Cinematics.isEnabled },
                        set = { SidequestSettings.Cinematics.isEnabled = it; cinematicsOn.value = it; applied() },
                        debugName = "cinematics.enabled",
                    ),
                )
                toggle(
                    id = id("cinematics.compact_only"),
                    title = "Never take the screen",
                    description = "Shows the compact form of everything instead of the full animation",
                    value = bind(
                        get = { SidequestSettings.Cinematics.compactOnly },
                        set = { SidequestSettings.Cinematics.compactOnly = it; applied() },
                        debugName = "cinematics.compact",
                    ),
                ) {
                    visibleWhen = cinematicsOn
                }
                toggle(
                    id = id("cinematics.letterbox"),
                    title = "Letterbox",
                    description = "Bars at the top and bottom while one plays",
                    value = bind(
                        get = { SidequestSettings.Cinematics.letterbox },
                        set = { SidequestSettings.Cinematics.letterbox = it; applied() },
                        debugName = "cinematics.letterbox",
                    ),
                ) {
                    visibleWhen = cinematicsOn
                }
                toggle(
                    id = id("cinematics.queue_while_unsafe"),
                    title = "Hold them until it is safe",
                    description = "Rather than dropping one that arrives mid-fight",
                    value = bind(
                        get = { SidequestSettings.Cinematics.queueWhileUnsafe },
                        set = { SidequestSettings.Cinematics.queueWhileUnsafe = it; applied() },
                        debugName = "cinematics.queue",
                    ),
                ) {
                    visibleWhen = cinematicsOn
                }
                toggle(
                    id = id("cinematics.recap"),
                    title = "Recap what was held",
                    description = "One summary afterwards instead of playing a backlog in a row",
                    value = bind(
                        get = { SidequestSettings.Cinematics.recap },
                        set = { SidequestSettings.Cinematics.recap = it; applied() },
                        debugName = "cinematics.recap",
                    ),
                ) {
                    visibleWhen = cinematicsOn
                }
            }

            section("Rare drops", description = "What is worth interrupting you for", icon = GlyphIconIds.rareDrop, collapsible = true, startsCollapsed = true) {
                toggle(
                    id = id("drops.enabled"),
                    title = "Announce rare drops",
                    value = bind(
                        get = { SidequestSettings.Drops.isEnabled },
                        set = { SidequestSettings.Drops.isEnabled = it; dropsOn.value = it },
                        debugName = "drops.enabled",
                    ),
                )
                dropdown(
                    id = id("drops.minimum_rarity"),
                    title = "From this tier upwards",
                    description = "Most drops Hypixel calls rare are not worth stopping for. Pets always announce.",
                    value = bind(
                        get = { SidequestSettings.Drops.minimumRarity },
                        set = { SidequestSettings.Drops.minimumRarity = it },
                        debugName = "drops.rarity",
                    ),
                    options = DropRarity.entries
                        // A pet is a kind rather than a tier and is exempt from the threshold, so offering it
                        // as one would be offering a setting that does nothing.
                        .filter { it != DropRarity.PET }
                        .map { option(it.name.lowercase(), it.readable(), it) },
                ) {
                    visibleWhen = dropsOn
                }
                toggle(
                    id = id("drops.totem"),
                    title = "Use Minecraft's totem animation",
                    description = "The familiar one, which does not cover the screen",
                    value = bind(
                        get = { SidequestSettings.Drops.useTotemAnimation },
                        set = { SidequestSettings.Drops.useTotemAnimation = it },
                        debugName = "drops.totem",
                    ),
                ) {
                    visibleWhen = dropsOn
                }
                slider(
                    id = id("drops.duration"),
                    title = "How long it runs",
                    value = bind(
                        get = { SidequestSettings.Drops.durationSeconds },
                        set = { SidequestSettings.Drops.durationSeconds = it },
                        debugName = "drops.duration",
                    ),
                    range = 2..10,
                    format = { "$it s" },
                    validator = Validators.intRange(2..10),
                ) {
                    visibleWhen = dropsOn
                }
                toggle(
                    id = id("drops.sound"),
                    title = "Play a sound",
                    value = bind(
                        get = { SidequestSettings.Drops.playsSound },
                        set = { SidequestSettings.Drops.playsSound = it },
                        debugName = "drops.sound",
                    ),
                ) {
                    visibleWhen = dropsOn
                }
                toggle(
                    id = id("drops.screenshot"),
                    title = "Take a screenshot",
                    description = "Writes a file for every announced drop",
                    value = bind(
                        get = { SidequestSettings.Drops.takesScreenshot },
                        set = { SidequestSettings.Drops.takesScreenshot = it },
                        debugName = "drops.screenshot",
                    ),
                ) {
                    visibleWhen = dropsOn
                }
                list(
                    id = id("drops.ignored_items"),
                    title = "Items",
                    description = "Added by the Ignore button on a drop's notification. Remove them here.",
                    value = bind(
                        get = { SidequestSettings.Drops.ignoredItems },
                        set = { SidequestSettings.Drops.ignoredItems = it },
                        debugName = "drops.ignored_items",
                    ),
                    elementSerializer = SettingSerializers.string,
                    itemLabel = { it },
                    // No add button. An item is added from the toast at the moment it interrupted somebody,
                    // which is the only time they know its exact name — typing one here would mostly produce
                    // entries that never match.
                    createItem = null,
                    isReorderable = false,
                ) {
                    visibleWhen = dropsOn
                }
                multiSelect(
                    id = id("drops.ignored_islands"),
                    title = "Islands",
                    description = "Nothing is announced while you are on these",
                    value = bind(
                        get = { SidequestSettings.Drops.ignoredIslands },
                        set = { SidequestSettings.Drops.ignoredIslands = it },
                        debugName = "drops.ignored_islands",
                    ),
                    // Every island, sorted by name rather than by declaration order — the enum is grouped by
                    // how SkyBlock is built and somebody looking for the Garden is looking alphabetically.
                    options = ISLAND_OPTIONS,
                    elementSerializer = ISLAND_SERIALIZER,
                ) {
                    visibleWhen = dropsOn
                }
            }

            section("Garden", description = "While you are farming", icon = GlyphIconIds.garden, collapsible = true, startsCollapsed = true) {
                toggle(
                    id = id("garden.view_bobbing"),
                    title = "Turn off view bobbing",
                    description = "Yours comes back when you leave. Change it yourself there and the mod stops touching it.",
                    value = bind(
                        get = { SidequestSettings.Garden.suppressViewBobbing },
                        set = { SidequestSettings.Garden.suppressViewBobbing = it },
                        debugName = "garden.view_bobbing",
                    ),
                ) {
                    keywords("farming", "camera", "sway", "bob")
                }
            }

            section("SkyBlock levels", description = "How the level above a player's head is coloured", icon = GlyphIconIds.levels, collapsible = true, startsCollapsed = true) {
                toggle(
                    id = id("levels.enabled"),
                    title = "Colour the level",
                    description = "Adds the bands the game never named, past level 520",
                    value = bind(
                        get = { SidequestSettings.Levels.isEnabled },
                        set = { SidequestSettings.Levels.isEnabled = it; levelsOn.value = it },
                        debugName = "levels.enabled",
                    ),
                )
                toggle(
                    id = id("levels.nametags"),
                    title = "On nametags",
                    value = bind(
                        get = { SidequestSettings.Levels.inNametags },
                        set = { SidequestSettings.Levels.inNametags = it },
                        debugName = "levels.nametags",
                    ),
                ) {
                    visibleWhen = levelsOn
                }
                toggle(
                    id = id("levels.tab_list"),
                    title = "In the tab list",
                    description = "Only the level that leads a line, so Hypixel's stat rows are left alone",
                    value = bind(
                        get = { SidequestSettings.Levels.inTabList },
                        set = { SidequestSettings.Levels.inTabList = it },
                        debugName = "levels.tab_list",
                    ),
                ) {
                    visibleWhen = levelsOn
                }
                dropdown(
                    id = id("levels.palette"),
                    title = "Palette",
                    value = bind(
                        get = { SidequestSettings.Levels.palette },
                        set = { SidequestSettings.Levels.palette = it },
                        debugName = "levels.palette",
                    ),
                    options = listOf(
                        option("tiered", "Tiered", LevelPalette.TIERED),
                        option("rainbow", "Rainbow", LevelPalette.RAINBOW),
                    ),
                ) {
                    visibleWhen = levelsOn
                    keywords("nametag", "colour", "color", "level")
                }
            }

            section("Title screen", description = "The nebula behind the main menu", icon = GlyphIconIds.titleScreen, collapsible = true, startsCollapsed = true) {
                toggle(
                    id = id("title.enabled"),
                    title = "Replace the panorama",
                    description = "Off leaves Minecraft's own. Another mod's title screen is left alone either way.",
                    value = bind(
                        get = { SidequestSettings.TitleScreen.isEnabled },
                        set = { SidequestSettings.TitleScreen.isEnabled = it; titleScreenOn.value = it },
                        debugName = "title.enabled",
                    ),
                )
                toggle(
                    id = id("title.animate"),
                    title = "Let it drift",
                    description = "Turn this off for a still image, and keep the nebula",
                    value = bind(
                        get = { SidequestSettings.TitleScreen.animate },
                        set = { SidequestSettings.TitleScreen.animate = it },
                        debugName = "title.animate",
                    ),
                ) {
                    visibleWhen = titleScreenOn
                    keywords("motion", "reduced motion", "still", "animation")
                }
                colorPicker(
                    id = id("title.deep"),
                    title = "Empty sky",
                    value = bind(
                        get = { SidequestSettings.TitleScreen.deepColour },
                        set = { SidequestSettings.TitleScreen.deepColour = it },
                        debugName = "title.deep",
                    ),
                ) {
                    visibleWhen = titleScreenOn
                }
                colorPicker(
                    id = id("title.cloud"),
                    title = "Clouds",
                    value = bind(
                        get = { SidequestSettings.TitleScreen.cloudColour },
                        set = { SidequestSettings.TitleScreen.cloudColour = it },
                        debugName = "title.cloud",
                    ),
                    presets = ACCENT_PRESETS,
                ) {
                    visibleWhen = titleScreenOn
                }
                colorPicker(
                    id = id("title.highlight"),
                    title = "Bright cores",
                    description = "Where the clouds pile up",
                    value = bind(
                        get = { SidequestSettings.TitleScreen.highlightColour },
                        set = { SidequestSettings.TitleScreen.highlightColour = it },
                        debugName = "title.highlight",
                    ),
                    presets = ACCENT_PRESETS,
                ) {
                    visibleWhen = titleScreenOn
                }
            }

            section("Playtime", description = "How long you spend in SkyBlock", icon = GlyphIconIds.playtime, collapsible = true, startsCollapsed = true) {
                toggle(
                    id = id("playtime.enabled"),
                    title = "Track playtime",
                    description = "Counted per profile, and kept on this machine only",
                    value = bind(
                        get = { SidequestSettings.Playtime.isEnabled },
                        set = { SidequestSettings.Playtime.isEnabled = it; playtimeOn.value = it },
                        debugName = "playtime.enabled",
                    ),
                )
                slider(
                    id = id("playtime.retention"),
                    title = "Days of history",
                    description = "Older days are dropped. Lower it if you would rather not keep a record.",
                    value = bind(
                        get = { SidequestSettings.Playtime.retentionDays },
                        set = { SidequestSettings.Playtime.retentionDays = it },
                        debugName = "playtime.retention",
                    ),
                    range = 7..365,
                    format = { "$it d" },
                    validator = Validators.intRange(7..365),
                ) {
                    visibleWhen = playtimeOn
                }
            }

            section("Cosmetics", description = "What you are willing to look at. These beat what other people chose.", icon = GlyphIconIds.cosmetics, collapsible = true, startsCollapsed = true) {
                toggle(
                    id = id("cosmetics.enabled"),
                    title = "Show cosmetics",
                    value = bind(
                        get = { SidequestSettings.Cosmetics.isEnabled },
                        set = { SidequestSettings.Cosmetics.isEnabled = it; cosmeticsOn.value = it; applied() },
                        debugName = "cosmetics.enabled",
                    ),
                )
                toggle(
                    id = id("cosmetics.appearance"),
                    title = "Skins and capes",
                    description = "Let other people's cosmetics replace how they look",
                    value = bind(
                        get = { SidequestSettings.Cosmetics.showAppearanceOverrides },
                        set = { SidequestSettings.Cosmetics.showAppearanceOverrides = it; applied() },
                        debugName = "cosmetics.appearance",
                    ),
                ) {
                    visibleWhen = cosmeticsOn
                }
                toggle(
                    id = id("cosmetics.effects"),
                    title = "Particle effects",
                    value = bind(
                        get = { SidequestSettings.Cosmetics.showEffects },
                        set = { SidequestSettings.Cosmetics.showEffects = it; applied() },
                        debugName = "cosmetics.effects",
                    ),
                ) {
                    visibleWhen = cosmeticsOn
                }
                toggle(
                    id = id("cosmetics.jokes"),
                    title = "Joke cosmetics",
                    description = "The group's sense of humour is not everybody's",
                    value = bind(
                        get = { SidequestSettings.Cosmetics.showJokeCosmetics },
                        set = { SidequestSettings.Cosmetics.showJokeCosmetics = it; applied() },
                        debugName = "cosmetics.jokes",
                    ),
                ) {
                    visibleWhen = cosmeticsOn
                }
                toggle(
                    id = id("cosmetics.reduced_animation"),
                    title = "Reduced animation",
                    description = "Cosmetics stop moving. They are still shown — this is about motion, not about hiding them.",
                    value = bind(
                        get = { SidequestSettings.Cosmetics.reducedAnimation },
                        set = { SidequestSettings.Cosmetics.reducedAnimation = it; applied() },
                        debugName = "cosmetics.reduced_animation",
                    ),
                ) {
                    visibleWhen = cosmeticsOn
                    keywords("accessibility", "motion", "still")
                }
            }
        }

        category(id("network"), "Network", description = "The group's backend", icon = GlyphIconIds.network) {
            section("Server", description = "Where Sidequest syncs to", icon = GlyphIconIds.network, collapsible = true) {
                textField(
                    id = id("network.url"),
                    title = "Server address",
                    description = "Leave empty to use Sidequest entirely offline",
                    value = bind(SidequestSettings::backendUrl),
                    placeholder = SidequestSettings.DEFAULT_BACKEND_URL,
                    // Refused rather than accepted-and-broken. A URL over plain http would put this device's
                    // bearer token on the wire in clear, and the realtime socket carries one in its query
                    // string — so an address that is not https is not a typo to tolerate.
                    validator = Validator { field, value ->
                        when {
                            value.isBlank() -> ValidationResult.valid()
                            value.startsWith("https://") -> ValidationResult.valid()
                            value.startsWith("http://") -> ValidationResult.error(
                                field,
                                "A plain http server would put this device's token on the wire in clear",
                                remediation = "Use https://",
                            )
                            else -> ValidationResult.error(field, "Must start with https://")
                        }
                    },
                )
                button(
                    id = id("network.pair"),
                    title = "Pair this device",
                    label = "Pair",
                    description = "Shows a code to approve from the dashboard",
                ) {
                    Sidequest.startPairing()
                }
                button(
                    id = id("network.sign_out"),
                    title = "Sign out",
                    label = "Sign out",
                    description = "Forgets this device's credentials",
                    destructive = true,
                ) {
                    Sidequest.signOutOfBackend()
                }
            }
        }

        category(id("advanced"), "Advanced", icon = GlyphIconIds.tools) {
            section("Diagnostics", description = "Developer tools and troubleshooting", icon = GlyphIconIds.tools, collapsible = true) {
                warning(
                    id = id("advanced.notice"),
                    title = "These settings are for debugging",
                    body = "Leaving them on will cost frame time.",
                )
                toggle(
                    id = id("advanced.debug_overlay"),
                    title = "Debug overlay",
                    description = "Draws layout bounds and frame timings",
                    value = bind(SidequestSettings::debugOverlay),
                ) {
                    experimental = true
                }
                divider(id("advanced.rule"))
                description(
                    id = id("advanced.commands"),
                    body = "/sqstatus for everything at once · /sqerr for what has gone wrong · " +
                        "/sqcos resolve to see why a cosmetic is not showing · /sqtest to fire a subsystem",
                )
            }
        }
    }
}

/** A percentage, for the volume sliders. Zero reads as "off" rather than as "0%", which looks like a bug. */
private fun percent(value: Float): String = if (value <= 0f) "off" else "${(value * 100).toInt()}%"

/** `VERY_RARE` → `Very rare`. Hypixel's tiers, spelled for a human. */
private fun DropRarity.readable(): String =
    name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

/**
 * Every island, for the ignore picker.
 *
 * Built once: forty options rebuilt on every screen open is forty allocations for a list that never changes.
 */
private val ISLAND_OPTIONS = Island.entries
    .sortedBy { it.displayName }
    .map { option(it.name, it.displayName, it) }

private val ISLAND_SERIALIZER = SettingSerializers.option { ISLAND_OPTIONS }

private val ACCENT_PRESETS = listOf(
    Color.parse("#8B5CF6"),
    Color.parse("#A855F7"),
    Color.parse("#34D399"),
    Color.parse("#FBBF24"),
    Color.parse("#F87171"),
    Color.parse("#38BDF8"),
)

private fun id(path: String) = UiId.of(Sidequest.MOD_ID, path)
