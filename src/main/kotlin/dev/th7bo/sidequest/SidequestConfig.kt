package dev.th7bo.sidequest

import dev.th7bo.sidequest.ui.binding.bind
import dev.th7bo.sidequest.ui.components.Icons
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.config.option
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.not
import dev.th7bo.sidequest.ui.validation.Validators

/**
 * The mod's own settings.
 *
 * A plain object with plain properties: the framework binds to them rather than owning
 * them, so nothing here depends on the UI.
 */
public object SidequestSettings {

    public var notifications: Boolean = true
    public var notificationDuration: Int = 5
    public var compactMode: Boolean = false
    public var accentColor: Color = Color.parse("#8B5CF6")
    public var theme: String = "dark"
    public var playerName: String = ""
    public var hudScale: Float = 1.0f
    public var debugOverlay: Boolean = false
}

/**
 * Builds the configuration screen.
 *
 * The whole screen is one declarative block; no widget or renderer class appears
 * anywhere in it.
 */
public fun buildSidequestConfigScreen(): ConfigScreen {
    val compactState = mutableStateOf(SidequestSettings.compactMode, "compactMode")
    val notificationsState = mutableStateOf(SidequestSettings.notifications, "notifications")

    return configScreen(id("config"), "Sidequest") {
        category(id("general"), "General", description = "Core behaviour", icon = Icons.gear) {
            section("Interface", description = "How the mod looks and behaves", icon = Icons.sliders) {
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
                    title = "Accent Colour",
                    description = "Used for highlights, sliders and progress bars",
                    value = bind(SidequestSettings::accentColor),
                    presets = listOf(
                        Color.parse("#8B5CF6"),
                        Color.parse("#A855F7"),
                        Color.parse("#34D399"),
                        Color.parse("#FBBF24"),
                        Color.parse("#F87171"),
                        Color.parse("#38BDF8"),
                    ),
                )
                toggle(
                    id = id("general.compact_mode"),
                    title = "Compact Mode",
                    description = "Tighter spacing throughout",
                    value = bind(SidequestSettings::compactMode),
                ) {
                    keywords("density", "spacing", "small")
                }
            }

            section("Notifications", description = "In-game alerts and their timing", icon = Icons.bell) {
                toggle(
                    id = id("general.notifications"),
                    title = "Show In-Game Notifications",
                    value = bind(SidequestSettings::notifications),
                )
                slider(
                    id = id("general.notification_duration"),
                    title = "Notification Duration",
                    description = "How long a notification stays on screen",
                    value = bind(SidequestSettings::notificationDuration),
                    range = 1..60,
                    format = { "$it s" },
                    validator = Validators.intRange(1..60),
                ) {
                    visibleWhen = notificationsState
                    // Reads as the intent: enabled while compact mode is off.
                    enabledWhen = !compactState
                }
            }
        }

        category(id("hud"), "HUD", description = "On-screen elements", icon = Icons.monitor) {
            section("Appearance", description = "On-screen element presentation", icon = Icons.palette) {
                decimalSlider(
                    id = id("hud.scale"),
                    title = "HUD Scale",
                    value = bind(SidequestSettings::hudScale),
                    range = 0.5f..2.5f,
                    step = 0.1f,
                    format = { String.format("%.1f×", it) },
                )
                textField(
                    id = id("hud.player_name"),
                    title = "Display Name Override",
                    description = "Leave empty to use your account name",
                    value = bind(SidequestSettings::playerName),
                    placeholder = "Your name",
                    validator = Validators.length(0..32),
                )
            }
        }

        category(id("advanced"), "Advanced", icon = Icons.wrench) {
            section("Diagnostics", description = "Developer tools and troubleshooting", icon = Icons.wrench) {
                warning(
                    id = id("advanced.notice"),
                    title = "These settings are for debugging",
                    body = "Leaving them on will cost frame time.",
                )
                toggle(
                    id = id("advanced.debug_overlay"),
                    title = "Debug Overlay",
                    description = "Draws layout bounds and frame timings",
                    value = bind(SidequestSettings::debugOverlay),
                ) {
                    experimental = true
                }
                divider(id("advanced.rule"))
                button(
                    id = id("advanced.reset"),
                    title = "Reset Everything",
                    label = "Reset",
                    description = "Restores every setting to its default",
                    destructive = true,
                ) {
                    Sidequest.logger.info("Configuration reset requested")
                }
            }
        }
    }
}

private fun id(path: String) = UiId.of(Sidequest.MOD_ID, path)
