package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.platform.core.friend.FriendEntry
import dev.th7bo.sidequest.platform.core.friend.FriendRoster
import dev.th7bo.sidequest.platform.core.notification.notification
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.player.PlayerFirstSeenEvent
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.player.PlayerRenamedEvent
import dev.th7bo.sidequest.platform.storage.Repository
import dev.th7bo.sidequest.platform.storage.StorageScope

/**
 * The friend list the rest of the mod has been assuming existed.
 *
 * `PlayerDirectory.customFriends` has been on the interface since it was written, `isCustomFriend` was
 * documented as "set by the friend service, once it exists", and nothing ever called the setter. So the
 * answer to "who are my friends" was permanently empty — which meant nametag highlighting had nobody to
 * highlight and sharing a waypoint with friends resolved to nobody and quietly sent nothing. This is the
 * service that was missing.
 *
 * **Deliberately separate from Hypixel's friend list.** The plan asks for that and it is the right call:
 * Hypixel's list is everybody who ever accepted, and the point of this one is the handful of people the
 * features are actually for.
 *
 * **The roster stores almost nothing.** Online status, activity, island, cosmetics, debts — all of it is
 * asked of the directory and the backend at the moment it is drawn, never copied here. `FriendRoster` says
 * why at greater length: a copy is a second answer that goes stale and then gets persisted in that state.
 */
class CustomFriends(
    /** Who is playing. Needs the game, and decides which file the list lives in. */
    private val localPlayer: () -> PlayerId?,
    private val now: () -> Long = System::currentTimeMillis,
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("friends.custom"),
        displayName = "Friends",
        category = FeatureCategory.SOCIAL,
        description = "The people the social features are for, separate from your Hypixel friends",
    )

    private lateinit var context: FeatureContext

    private var repository: Repository<FriendRoster>? = null

    private var roster: FriendRoster = FriendRoster()

    override fun onEnable(context: FeatureContext) {
        this.context = context

        // Account scope, like the waypoint book: a friend is a friend on every profile, and filing the list
        // per profile would mean adding the same people again on each one.
        localPlayer()?.let { player ->
            val store = context.store(
                name = "friends",
                scope = StorageScope.Account(player),
                serializer = FriendRoster.serializer(),
                default = { FriendRoster() },
            )
            repository = store
            context.scheduler.async(context.owner) {
                val loaded = store.load()
                context.scheduler.onMain(context.owner) {
                    roster = loaded.value
                    publishToDirectory()
                }
            }
        }

        // A friend who turns up gets marked and has their cached name refreshed. Both matter on the same
        // event: the directory forgets between sessions, so without this a friend seen for the first time
        // this session would draw as a stranger until something else touched them.
        context.listen(PlayerFirstSeenEvent::class) { event -> onSeen(event.player.id, event.player.username) }
        context.listen(PlayerRenamedEvent::class) { event -> onSeen(event.player.id, event.player.username) }

        registerCommands()
    }

    override fun onDisable() {
        // The marks go with it. Leaving them behind would let a disabled feature keep colouring nametags,
        // and nothing else would ever clear them.
        for (friend in roster.friends) context.players.setCustomFriend(friend.id, false)
    }

    /**
     * Tells the directory who the friends are.
     *
     * The single point where the roster becomes visible to everything else. Called after loading and after
     * every change, wholesale rather than as a diff — it is tens of entries, and a diff would be a second
     * model of what the directory currently believes.
     *
     * **This is also what makes the list survive a restart.** The directory is in memory only and starts
     * every session empty, so the roster is the durable copy and this is the moment it is handed back.
     */
    private fun publishToDirectory() {
        for (friend in roster.friends) {
            // Remembered first, but only when there is a real name to remember. A friend added by UUID has
            // none, and handing the directory a placeholder would make it announce a rename to that
            // placeholder — which comes straight back here and caches it as what they are called.
            if (friend.username.isNotBlank()) context.players.remember(friend.id, friend.username)
            context.players.setCustomFriend(friend.id, true)
            // The nickname too, and only from here. It is stored on both — the roster persists it, the
            // directory is what everything else draws from — and without this the directory would come up
            // empty every session and a nickname would look like it had been forgotten.
            friend.nickname?.let { context.players.setNickname(friend.id, it) }
        }
    }

    private fun onSeen(id: PlayerId, username: String) {
        if (id !in roster) return
        context.players.setCustomFriend(id, true)

        val updated = roster.seen(id, username)
        if (updated == roster) return
        // Only when the name actually moved. This runs for every player the client sees, and persisting on
        // each one would write the file across a busy lobby for no change.
        roster = updated
        persist()
    }

    // -- commands ------------------------------------------------------------

    private fun registerCommands() {
        context.command(
            name = "sqfriend",
            description = "Manage the people the social features are for",
            usage = "[add <name>|remove <name>|list|nick <name> <nickname>|note <name> <text>|fav <name>]",
            completions = { arguments ->
                when (arguments.size) {
                    0, 1 -> VERBS
                    2 -> when (arguments.first().lowercase()) {
                        // Only the verbs that act on somebody already on the list. Completing friends for
                        // `add` would suggest exactly the people who cannot be added.
                        "remove", "nick", "note", "fav", "unfav" ->
                            roster.sorted().map { it.displayName.quotedIfSpaced() }
                        else -> emptyList()
                    }
                    else -> emptyList()
                }
            },
        ) { arguments -> handle(arguments) }
    }

    private fun handle(arguments: List<String>) {
        val rest = arguments.drop(1)
        when (arguments.firstOrNull()?.lowercase()) {
            // No verb opens the hub, matching `/sqwp`. Managing a list is worth a screen; `list` stays for
            // the times somebody wants the answer without one covering the game.
            null, "" -> openHub()
            "list" -> list()
            "add" -> add(rest.joinToString(" "))
            "remove", "delete" -> remove(rest.joinToString(" "))
            "nick", "nickname" -> nickname(rest.firstOrNull(), rest.drop(1).joinToString(" "))
            "note" -> note(rest.firstOrNull(), rest.drop(1).joinToString(" "))
            "fav", "favourite" -> favourite(rest.joinToString(" "), true)
            "unfav" -> favourite(rest.joinToString(" "), false)
            // An unrecognised first word is a name, matching `/sqwp`: `/sqfriend chrooted` adds them. The
            // verbs are short and specific enough that no Minecraft name collides with one.
            else -> add(arguments.joinToString(" "))
        }
    }

    /**
     * Adds somebody by name, by UUID, or by looking at them.
     *
     * **A name is resolved once, here, and the id is what is stored.** Everything after this point works on
     * the id, so the friendship survives a rename — and cannot be transferred to somebody who later claims
     * the name.
     *
     * Resolution is local: the client knows the players it has shared a lobby with, plus everybody in the
     * backend group. That is a real limit and it is stated rather than worked around — a name that cannot be
     * resolved is refused, because the alternative is storing a name-shaped placeholder that would silently
     * attach to whoever turned up under it.
     */
    private fun add(query: String) {
        val name = query.trim().trim('"')
        val target = when {
            // Nothing typed means whoever is under the crosshair. The plan's "friend the player you are
            // looking at", and by far the fastest way to do this in practice.
            name.isEmpty() -> context.targeting.resolveTarget()?.let { it.id to it.username }
            else -> resolve(name)
        }

        if (target == null) {
            say(
                if (name.isEmpty()) "Not looking at anybody" else "Never seen $name",
                "Sidequest can only add players it has seen. Stand near them, or use their UUID.",
            )
            return
        }

        val (id, username) = target
        if (id in roster) {
            say("${roster[id]?.displayName} is already a friend")
            return
        }
        if (id == localPlayer()) {
            say("That is you")
            return
        }

        val added = FriendEntry(id = id, username = username, addedAtMillis = now())
        roster = roster.with(added)
        persist()
        publishToDirectory()
        // The entry's own name, not the query. Somebody added by UUID has no name yet, and the entry knows
        // to fall back to a readable part of the id where the typed text would just be echoed back.
        say("Added ${added.displayName}", "${roster.size} friend${plural(roster.size)}.")
    }

    /**
     * Turns what somebody typed into a player.
     *
     * A UUID first, so somebody can always add a player the client has never seen if they know the id.
     * Then the directory, which is best-effort and documented as such — it resolves the name to whoever
     * holds it *now*, which is right for adding a friend and wrong for anything durable. That is exactly
     * why what gets stored is the id it returned rather than the name that was typed.
     */
    private fun resolve(name: String): Pair<PlayerId, String>? {
        PlayerId.parse(name)?.let { id ->
            return id to (context.players.byId(id)?.username ?: "")
        }
        val known = context.players.resolveUsername(name) ?: return null
        return known.id to known.username
    }

    private fun remove(query: String) {
        val friend = find(query) ?: return
        roster = roster.without(friend.id)
        persist()
        context.players.setCustomFriend(friend.id, false)
        say("Removed ${friend.displayName}")
    }

    private fun list() {
        if (roster.friends.isEmpty()) {
            say("No friends yet", "Look at somebody and run /sqfriend add.")
            return
        }
        val online = roster.friends.count { context.players.byId(it.id)?.presence?.isOnline == true }
        say(
            "${roster.size} friend${plural(roster.size)}" + if (online > 0) " · $online online" else "",
            roster.sorted().joinToString(" · ") { friend ->
                val mark = if (context.players.byId(friend.id)?.presence?.isOnline == true) "*" else ""
                "${friend.displayName}$mark"
            }.take(SUBTITLE_LIMIT),
        )
    }

    private fun nickname(query: String?, nickname: String) {
        val friend = find(query.orEmpty()) ?: return
        // Blank clears it. `/sqfriend nick Bob` with nothing after should undo the nickname rather than set
        // an empty one, which would leave them with a name that draws as nothing.
        val chosen = nickname.trim().trim('"').ifBlank { null }

        roster = roster.edit(friend.id) { it.copy(nickname = chosen) }
        persist()
        // The directory keeps its own nickname, and it is the one everything else draws from.
        context.players.setNickname(friend.id, chosen)
        say(if (chosen == null) "Cleared ${friend.username}'s nickname" else "${friend.username} is now $chosen")
    }

    private fun note(query: String?, text: String) {
        val friend = find(query.orEmpty()) ?: return
        val chosen = text.trim().ifBlank { null }?.take(MAX_NOTE)

        roster = roster.edit(friend.id) { it.copy(note = chosen) }
        persist()
        say(
            if (chosen == null) "Cleared the note on ${friend.displayName}" else "Noted about ${friend.displayName}",
            chosen.orEmpty(),
        )
    }

    private fun favourite(query: String, isFavourite: Boolean) {
        val friend = find(query) ?: return
        roster = roster.edit(friend.id) { it.copy(isFavourite = isFavourite) }
        persist()
        say(if (isFavourite) "${friend.displayName} is a favourite" else "${friend.displayName} is no longer a favourite")
    }

    /** Finds a friend, or explains why it could not. Null means something has already been said. */
    private fun find(query: String): FriendEntry? {
        val friend = roster.find(query)
        if (friend == null) {
            val text = query.trim().trim('"')
            val clashes = roster.friends.count { it.displayName.startsWith(text, ignoreCase = true) }
            say(
                if (text.isEmpty()) "Who?" else "No friend called $text",
                // Says *why*, since "no such friend" is the wrong answer when the problem is two of them.
                if (clashes > 1) "$clashes friends start with that. Type more of the name." else "/sqfriend list",
            )
        }
        return friend
    }

    // -- what the friend hub does --------------------------------------------

    /** The list, for a screen that draws it. */
    fun roster(): FriendRoster = roster

    /**
     * Applies a change to one friend.
     *
     * Takes a function rather than the new value, like the waypoint manager's equivalent and for the same
     * reason: the screen edits a field of whatever is current instead of putting back the entry it was
     * drawing a moment ago.
     */
    fun editFriend(id: PlayerId, change: (FriendEntry) -> FriendEntry) {
        val before = roster[id] ?: return
        roster = roster.edit(id, change)
        persist()

        // The directory keeps its own copy of the nickname and it is the one everything else draws from, so
        // a change here has to reach it or the nametag would keep the old name until the next restart.
        val after = roster[id]
        if (after?.nickname != before.nickname) context.players.setNickname(id, after?.nickname)
    }

    fun removeFriend(id: PlayerId) {
        if (id !in roster) return
        roster = roster.without(id)
        persist()
        context.players.setCustomFriend(id, false)
    }

    /** Opens the friend hub. Set by the mod, which owns screens. */
    var openHub: () -> Unit = {}

    private fun persist() {
        val store = repository ?: return
        val snapshot = roster
        context.scheduler.async(context.owner) {
            runCatching { store.save(snapshot) }
                .onFailure { context.log.warn(it) { "Could not save the friend list" } }
        }
    }

    private fun say(title: String, subtitle: String = "") {
        context.notifications.notify(
            notification(category = NotificationCategory.SOCIAL, title = title, subtitle = subtitle),
        )
    }

    private fun plural(count: Int): String = if (count == 1) "" else "s"

    private fun String.quotedIfSpaced(): String = if (' ' in this) "\"$this\"" else this

    private companion object {
        val VERBS = listOf("add", "remove", "list", "nick", "note", "fav", "unfav")

        const val MAX_NOTE = 200

        const val SUBTITLE_LIMIT = 160
    }
}
