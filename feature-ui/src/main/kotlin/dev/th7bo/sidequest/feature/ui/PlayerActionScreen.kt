package dev.th7bo.sidequest.feature.ui

import dev.th7bo.sidequest.platform.player.PlayerActionContext
import dev.th7bo.sidequest.platform.player.PlayerActionEntry
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Icon

/** The icons the action menu draws. Defaulted to the framework's own; the host may supply better. */
public data class PlayerActionIcons(
    public val player: Icon = Icon(UiId.of("sidequest", "icon.friend")),
    public val action: Icon = Icon(UiId.of("sidequest", "icon.features")),
)

/**
 * What somebody can do to a player, as a screen.
 *
 * **It knows about no feature at all**, which is the whole point of the registry it draws from. The plan
 * asks for seventeen actions spread across a dozen features — a menu that named them would have to import
 * every one, making one screen the thing that couples the entire mod together. Here the actions arrive as
 * data and the screen cannot tell a party invite from a debt.
 *
 * That also means the menu is never full of buttons for features that do not exist yet. An unbuilt feature
 * registers nothing and simply does not appear, so this screen grows on its own as the rest is written.
 *
 * Built from a snapshot, like the other two screens here: the entries were gathered when it opened, and each
 * one closed over the player it was offered for. Nothing between opening and clicking can substitute a
 * different target.
 */
public fun buildPlayerActionScreen(
    context: PlayerActionContext,
    entries: List<PlayerActionEntry>,
    /** Runs after an action, so the host can close the screen. Actions are one-shot; the menu is done. */
    onChosen: () -> Unit = {},
    icons: PlayerActionIcons = PlayerActionIcons(),
): ConfigScreen {
    val name = context.player.displayName

    return configScreen(id("actions"), name, describe(context)) {
        category(id("actions.all"), "Actions", description = describe(context), icon = icons.player) {
            if (entries.isEmpty()) {
                // Reached when every feature declined — looking at yourself, or at somebody offline with
                // nothing on offer. Saying so beats an empty panel that reads as a broken screen.
                section("Nothing to do", description = nothingToDo(context)) {
                    button(id("actions.none"), "Close", label = "Close") { onChosen() }
                }
            }

            if (entries.isNotEmpty()) {
                section("Do something", id = id("actions.list")) {
                    for ((index, entry) in entries.withIndex()) {
                        val action = entry.action
                        button(
                            // Position, not the action's own id. Two features contributing an id that slugs
                            // the same would collide, and a duplicate id throws the whole menu away — which
                            // for this screen means every action becomes unreachable, not just the two that
                            // clashed.
                            id = id("actions.a.$index"),
                            title = action.label,
                            label = action.label,
                            description = action.description.ifBlank { null },
                            destructive = action.isDestructive,
                        ) {
                            entry.run()
                            onChosen()
                        }
                    }
                }
            }
        }
    }
}

/** The line under their name: who they are to you, and whether they are around. */
private fun describe(context: PlayerActionContext): String = buildString {
    append(if (context.isOnline) "Online" else "Offline")
    if (context.isFriend) append(" · friend")
    if (context.isInParty) append(" · in your party")
    // Their real name when a nickname is covering it, so the person on screen can be matched to the one on
    // the nametag that opened this menu.
    val real = context.player.username
    if (context.player.nickname != null && real.isNotBlank()) append(" · ").append(real)
}

private fun nothingToDo(context: PlayerActionContext): String = when {
    context.isSelf -> "This is you."
    else -> "No feature has anything to offer for them right now."
}

/** The mod's own namespace. Spelled out rather than imported, so this module needs nothing from the mod. */
private const val NAMESPACE = "sidequest"

private fun id(path: String): UiId = UiId.of(NAMESPACE, path)
