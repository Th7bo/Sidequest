package dev.th7bo.sidequest.feature.ui

import dev.th7bo.sidequest.platform.core.friend.FriendEntry
import dev.th7bo.sidequest.platform.core.friend.FriendRoster
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.player.PlayerIdentity
import dev.th7bo.sidequest.platform.player.PlayerPresence
import dev.th7bo.sidequest.platform.skyblock.Activity
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.ui.binding.bind
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.derivedStateOf

/** The icons the friend hub draws. Defaulted to the framework's own; the host may supply better. */
public data class FriendScreenIcons(
    public val friend: Icon = Icon(UiId.of("sidequest", "icon.friend")),
    public val online: Icon = Icon(UiId.of("sidequest", "icon.online")),
    public val add: Icon = Icon(UiId.of("sidequest", "icon.plus")),
)

/**
 * What the friend hub can do, and what it may read.
 *
 * Callbacks rather than the feature itself, for the reason [WaypointActions] gives at greater length: the
 * screen is built from a snapshot and every value has to read through something that knows the current
 * state, or the rows draw whatever was true when the screen opened and then quietly revert edits.
 */
public class FriendActions(
    /** The list as it is *now*. Every getter on the screen reads through this, never the snapshot. */
    public val current: () -> FriendRoster,
    /**
     * What the directory knows about somebody: online, activity, island.
     *
     * Asked per draw rather than copied into the roster. The roster stores who is a friend; whether they are
     * online is a different question with a different lifetime, and a copy of it in a persisted file would
     * be wrong within a minute and wrong forever after a crash.
     */
    public val identity: (PlayerId) -> PlayerIdentity?,
    /**
     * Somebody's presence, as something the row can watch.
     *
     * The difference between a friend list that is right when it opens and one that stays right. Defaulted
     * to a constant, so a host without a presence bridge still gets a working hub — it simply does not
     * update while open, which is where this screen was before.
     */
    public val presenceOf: (PlayerId) -> UiState<PlayerPresence> = { constantState(PlayerPresence.Unknown) },
    public val edit: (id: PlayerId, change: (FriendEntry) -> FriendEntry) -> Unit,
    public val remove: (id: PlayerId) -> Unit,
    /** Rebuilds and reopens. For the edits that change the screen's own shape. */
    public val reopen: () -> Unit,
    /**
     * Opens the action menu for somebody.
     *
     * One of the five places the plan wants that menu opened from. Defaulted to doing nothing so a host
     * without one still gets a usable hub — the rest of this screen does not depend on it.
     */
    public val openActions: (PlayerId) -> Unit = {},
)

/**
 * The friend hub.
 *
 * A configuration screen rather than a bespoke one, like the waypoint manager and for the same reason: the
 * framework already has the sidebar, the search across every row, the collapsible sections and the text
 * fields, and all of it is already tested and already looks like the rest of the mod.
 *
 * **Ordered by who is online**, because that is the question somebody opening a friend list is actually
 * asking. A purely alphabetical list makes finding the two people currently playing a scan through forty
 * names.
 *
 * **The rows stay right while the screen is open.** Every editable value reads through
 * [FriendActions.current], so typing sticks; and the line under each name is a derivation over
 * [FriendActions.presenceOf], so somebody logging out while you are looking at the list stops being
 * described as online.
 *
 * That second half was a real limitation for two commits, documented rather than papered over, because the
 * obvious fix is not one: a description that recomputed on read but never notified would leave the text node
 * holding whatever it had cached — the same staleness with more machinery in the way. What makes it work is
 * that presence is now a *source* state something writes to, so the derivation has a dependency that can
 * actually invalidate. See `PresenceStates`.
 *
 * The screen's *shape* is still a snapshot: who is in which category, and the order, are fixed when it
 * opens. That is deliberate — rows appearing and reordering under a cursor is worse than a list that is one
 * reopen out of date about its own ordering.
 */
public fun buildFriendHubScreen(
    roster: FriendRoster,
    actions: FriendActions,
    icons: FriendScreenIcons = FriendScreenIcons(),
): ConfigScreen {
    // Online first, then the roster's own order. Somebody opening a friend list is usually asking "who can
    // I play with", and burying the two people currently playing in an alphabetical list of forty answers a
    // different question. The rest of the order — favourites, then name — is the roster's, so the two rules
    // do not have to agree across two files.
    val sorted = roster.sorted().sortedByDescending { actions.isOnline(it.id) }

    // Position in the list, so every id on this screen is unique whatever the names look like. A slug alone
    // cannot promise that — two friends nicknamed the same thing would collide, and a duplicate id throws
    // the entire screen away rather than drawing a wrong label.
    val ordinals = sorted.withIndex().associate { (index, friend) -> friend.id to index }

    val onlineCount = sorted.count { actions.isOnline(it.id) }

    return configScreen(
        id("friends"),
        "Friends",
        "The people Sidequest's social features are for.",
    ) {
        category(
            id("friends.all"),
            "Everyone",
            description = describeRoster(roster, onlineCount),
            icon = icons.friend,
        ) {
            if (roster.friends.isEmpty()) {
                section(
                    "Nobody yet",
                    description = "Look at somebody and run /sqfriend add, or /sqfriend add <name>.",
                ) {
                    button(id("friends.help"), "How it works", label = "Got it") { }
                }
            }

            for (friend in sorted) friendSection(friend, "all", key(friend.id, ordinals), actions, icons)
        }

        val favourites = sorted.filter { it.isFavourite }
        if (favourites.isNotEmpty()) {
            category(
                id("friends.favourites"),
                "Favourites",
                description = "${favourites.size} pinned",
                icon = icons.online,
            ) {
                // The category is part of the id, because a favourite is drawn twice — once here and once
                // in Everyone — and every id on a screen shares one namespace. Keying on the friend alone
                // made the second one a duplicate, and a duplicate id throws the whole screen away. So
                // marking somebody a favourite crashed the screen that has the toggle on it.
                for (friend in favourites) friendSection(friend, "fav", key(friend.id, ordinals), actions, icons)
            }
        }
    }
}

/** Whether somebody is online according to the directory, which is the only thing that knows. */
private fun FriendActions.isOnline(id: PlayerId): Boolean = identity(id)?.presence?.isOnline == true

/**
 * One friend, as a collapsible section.
 *
 * Collapsed to start with, like a waypoint: the list is for scanning, and a page of expanded forms is harder
 * to read than a column of names. The search box reaches inside them whether they are open or not.
 */
private fun dev.th7bo.sidequest.ui.config.CategoryBuilder.friendSection(
    friend: FriendEntry,
    /** Which category is drawing this row. Part of the id, since a favourite is drawn in two of them. */
    category: String,
    key: String,
    actions: FriendActions,
    icons: FriendScreenIcons,
) {
    val prefix = "friends.$category.$key"
    section(
        friend.displayName,
        // Keyed on the friend rather than on the title, because a section's id derives from its title by
        // default and two friends nicknamed the same thing would throw the screen away before it drew.
        id = id(prefix),
        // A derivation, so the line under a name follows them logging in and out while the screen is open.
        // Reading the presence state inside is what registers the dependency; nothing else has to.
        descriptionState = derivedStateOf("friend.$key.description") { describe(friend, actions) },
        icon = if (actions.isOnline(friend.id)) icons.online else icons.friend,
        collapsible = true,
        startsCollapsed = true,
    ) {
        textField(
            id = id("$prefix.nickname"),
            title = "Nickname",
            description = "What Sidequest calls them. Only ever on this client.",
            value = bind(
                get = { live(actions, friend).nickname.orEmpty() },
                set = { value -> actions.edit(friend.id) { it.copy(nickname = value.ifBlank { null }) } },
                debugName = "friend.nickname",
            ),
            placeholder = friend.username,
        )
        textField(
            id = id("$prefix.note"),
            title = "Note",
            description = "Only for you. A note never leaves this client.",
            value = bind(
                get = { live(actions, friend).note.orEmpty() },
                set = { value -> actions.edit(friend.id) { it.copy(note = value.ifBlank { null }) } },
                debugName = "friend.note",
            ),
            placeholder = "owes me a Hyperion",
        )
        toggle(
            id = id("$prefix.favourite"),
            title = "Favourite",
            description = "Sorts them to the top",
            value = bind(
                get = { live(actions, friend).isFavourite },
                set = { value -> actions.edit(friend.id) { it.copy(isFavourite = value) } },
                debugName = "friend.favourite",
            ),
        )
        button(
            id = id("$prefix.actions"),
            title = "Do something",
            label = "Actions",
            // Deliberately vague, because this screen genuinely does not know what will be on the menu —
            // every feature contributes its own, and naming them here would go stale the moment one is added.
            description = "Invite them, ping them, whatever else is on offer",
        ) {
            actions.openActions(friend.id)
        }
        button(
            id = id("$prefix.remove"),
            title = "Remove",
            label = "Remove",
            // Said plainly rather than behind a confirmation. Nothing is destroyed — they stop being a
            // friend, and adding them again costs one command.
            description = "They stop being a friend. Nothing else is deleted.",
            destructive = true,
        ) {
            actions.remove(friend.id)
            actions.reopen()
        }
    }
}

/**
 * This friend as they stand now, falling back to the snapshot.
 *
 * Never null, unlike the waypoint equivalent: a removed friend's section is gone on the next open anyway,
 * and a getter with nothing to answer with would draw a blank field over what somebody was mid-way through
 * typing.
 */
private fun live(actions: FriendActions, friend: FriendEntry): FriendEntry =
    actions.current()[friend.id] ?: friend

/**
 * The line under a friend's name.
 *
 * Reads presence through [FriendActions.presenceOf] rather than off the identity, which is the whole of what
 * makes it live: reading a state inside a derivation is what registers the dependency that later invalidates
 * this text.
 */
private fun describe(friend: FriendEntry, actions: FriendActions): String {
    val presence = actions.presenceOf(friend.id).value

    return buildString {
        // Their real name, when a nickname is covering it. Otherwise somebody renamed to "Cee" in the list
        // cannot be matched to the name on their nametag.
        if (friend.nickname != null && friend.username.isNotBlank()) append(friend.username).append(" · ")

        when {
            presence.isOnline != true -> append("Offline")
            else -> {
                append("Online")
                // Only what they have agreed to share, and nothing is invented to fill a gap. "Not shared"
                // and "not known" are deliberately indistinguishable here — a screen that could tell them
                // apart would be telling the viewer something the sender chose not to say.
                presence.activity.takeIf { it != Activity.UNKNOWN }?.let { append(" · ").append(it.displayName) }
                presence.island.takeIf { it != Island.NONE }?.let { append(" · ").append(it.displayName) }
            }
        }

        if (friend.note != null) append(" · noted")
    }
}

private fun describeRoster(roster: FriendRoster, online: Int): String = when {
    roster.friends.isEmpty() -> "None yet"
    online > 0 -> "${roster.size} · $online online"
    else -> "${roster.size} · none online"
}

/**
 * A unique, legal id fragment for one friend.
 *
 * The slug for readability in a log, the ordinal for uniqueness. A UUID slugs to something long and
 * unreadable but legal, and the ordinal is what actually guarantees no two rows collide — a duplicate id
 * throws, taking the whole screen with it.
 */
private fun key(id: PlayerId, ordinals: Map<PlayerId, Int>): String =
    slug(id.value.take(SLUG_LENGTH)) + "_" + (ordinals[id] ?: 0)

/** Any string, as a legal [UiId] path segment. The grammar allows letters, digits and underscores only. */
private fun slug(raw: String): String {
    val cleaned = raw.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_')
    return cleaned.ifEmpty { "unknown" }
}

/** Enough of a UUID to read in a log without putting all thirty-six characters into every id. */
private const val SLUG_LENGTH = 8

/** The mod's own namespace. Spelled out rather than imported, so this module needs nothing from the mod. */
private const val NAMESPACE = "sidequest"

private fun id(path: String): UiId = UiId.of(NAMESPACE, path)
