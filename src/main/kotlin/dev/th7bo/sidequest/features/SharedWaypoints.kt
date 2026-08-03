package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.platform.core.notification.notification
import dev.th7bo.sidequest.platform.core.waypoint.WaypointBook
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.marker.Marker
import dev.th7bo.sidequest.platform.marker.MarkerKind
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.skyblock.IslandChangedEvent
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import dev.th7bo.sidequest.platform.storage.Repository
import dev.th7bo.sidequest.platform.storage.StorageScope
import dev.th7bo.sidequest.platform.waypoint.AudienceMembers
import dev.th7bo.sidequest.platform.waypoint.SharedWaypoint
import dev.th7bo.sidequest.platform.waypoint.WaypointAudience
import dev.th7bo.sidequest.platform.waypoint.WaypointCollection
import dev.th7bo.sidequest.platform.waypoint.WaypointDelivery
import dev.th7bo.sidequest.platform.waypoint.deliveryTo
import dev.th7bo.sidequest.platform.waypoint.isShared
import kotlin.time.Duration.Companion.seconds

/**
 * Waypoints somebody saved, and who they are shared with.
 *
 * The deciding is all in [WaypointBook] — who may see what, what a folder means, what happens to the contents
 * of a deleted collection. What is here is the part that needs a client: reading where the player is standing,
 * keeping the book on disk, and turning the entries that should be on screen into markers.
 *
 * **Markers rather than its own rendering.** The marker service already draws beams, edge indicators and
 * distance labels, and already decides what is on screen. A second thing drawing waypoints would be a second
 * set of those decisions, differing in small ways nobody could explain.
 *
 * **What actually goes out is narrower than what is stored.** Notes are stripped, for the reason
 * `WaypointBook.shareableWith` gives: a note is private commentary attached to a place somebody is happy to
 * share. And a waypoint shared with named people is *addressed* to them on the wire rather than sent to
 * everybody and filtered on arrival — a client that receives something and chooses not to draw it has still
 * received it.
 */
class SharedWaypoints(
    /** Who is playing, and where they are standing. Both need the game. */
    private val localPlayer: () -> PlayerId?,
    private val standingAt: () -> SqPosition?,
    /**
     * Sends a waypoint to the group, or takes it back when [removed] is set.
     *
     * A function rather than the realtime client, so this feature cannot do anything else with the
     * connection and the whole of the sharing logic stays testable without one. Returns false when nothing
     * went out, which is what lets sharing say so instead of claiming a waypoint reached people it did not.
     */
    private val publish: (waypoint: SharedWaypoint, to: WaypointDelivery, removed: Boolean) -> Boolean =
        { _, _, _ -> false },
    private val now: () -> Long = System::currentTimeMillis,
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("waypoints.shared"),
        displayName = "Shared waypoints",
        category = FeatureCategory.UTILITY,
        description = "Saves places, and shares them with friends or your party",
    )

    private lateinit var context: FeatureContext

    private var repository: Repository<WaypointBook>? = null

    /** The book as it stands. Replaced wholesale on every edit, which is why it is immutable. */
    private var book: WaypointBook = WaypointBook()

    override fun onEnable(context: FeatureContext) {
        this.context = context

        // Account scope, not profile: a waypoint in the Hub is in the Hub whichever profile you are on, and
        // filing them per profile would mean re-marking the same bazaar door on every one.
        localPlayer()?.let { player ->
            val store = context.store(
                name = "book",
                scope = StorageScope.Account(player),
                serializer = WaypointBook.serializer(),
                default = { WaypointBook() },
            )
            repository = store
            context.scheduler.async(context.owner) {
                val loaded = store.load()
                context.scheduler.onMain(context.owner) {
                    book = loaded.value
                    refresh()
                }
            }
        }

        context.listen(IslandChangedEvent::class) { refresh() }
        // Expiry is a wall-clock thing, so something has to notice it passing. Cheap: a filter over a list
        // that is a few hundred entries at the outside.
        context.scheduler.every(context.owner, period = SWEEP, initialDelay = SWEEP) { refresh() }

        registerCommands()
    }

    override fun onDisable() {
        context.markers.removeAll(MarkerKind.WAYPOINT)
    }

    // -- commands ------------------------------------------------------------

    private fun registerCommands() {
        context.command(
            name = "sqwp",
            description = "Save and manage waypoints",
            usage = "[add <name>|list|hide <name>|show <name>|remove <name>|share <name> <who>]",
            completions = { arguments ->
                when (arguments.size) {
                    0, 1 -> VERBS
                    2 -> when (arguments.first().lowercase()) {
                        // Names rather than ids. An id is a timestamp in base 36 and nobody is typing one —
                        // completing them was the command's worst part.
                        "remove", "share", "hide", "show" ->
                            book.waypoints.map { it.label.quotedIfSpaced() }
                        else -> emptyList()
                    }
                    3 -> if (arguments.first().lowercase() == "share") AUDIENCES.keys.toList() else emptyList()
                    else -> emptyList()
                }
            },
        ) { arguments -> handle(arguments) }
    }

    private fun handle(arguments: List<String>) {
        when (arguments.firstOrNull()?.lowercase()) {
            // No verb opens the manager. Saving one waypoint is worth a command; everything after that —
            // renaming, recolouring, filing, sharing — is worth a screen, and typing it was never nicer.
            null, "" -> openManager()
            "add", "here" -> add(arguments.drop(1).joinToString(" "))
            "list" -> list()
            "remove", "delete" -> remove(arguments.drop(1).joinToString(" "))
            "share" -> share(arguments.getOrNull(1), arguments.getOrNull(2))
            // Hiding is a display switch, not a sharing one: what you have shared stays shared.
            "hide" -> setShown(arguments.drop(1).joinToString(" "), shown = false)
            "show" -> setShown(arguments.drop(1).joinToString(" "), shown = true)
            // An unrecognised first word is a name, not a mistake: `/sqwp bazaar door` saves one. The verbs
            // are short and specific enough that nothing anybody would call a waypoint collides with them.
            else -> add(arguments.joinToString(" "))
        }
    }

    /**
     * Finds a waypoint by what somebody typed.
     *
     * By name, case-insensitively, and by id as a fallback so anything that prints one stays usable. An
     * ambiguous name is refused rather than guessed at — deleting the wrong waypoint because two were both
     * called "boss" is not recoverable.
     */
    private fun find(query: String): SharedWaypoint? {
        val trimmed = query.trim().trim('"')
        if (trimmed.isEmpty()) return null
        val byName = book.waypoints.filter { it.label.equals(trimmed, ignoreCase = true) }
        return when {
            byName.size == 1 -> byName.single()
            byName.size > 1 -> null
            else -> book.waypoint(trimmed)
        }
    }

    private fun String.quotedIfSpaced(): String = if (' ' in this) "\"$this\"" else this

    private fun add(label: String) {
        val position = standingAt()
        if (position == null) {
            say("Nowhere to save", "You have to be in a world.")
            return
        }
        val name = label.ifBlank { "Waypoint" }
        val waypoint = SharedWaypoint(
            // Plain letters and digits. This was `SqId.sidequest("wp")` stringified, which carries a colon
            // and a slash — and the manager screen builds a `UiId` per row from it, whose grammar allows
            // neither. Every waypoint saved made that screen throw on the way up.
            id = "wp" + nextId(),
            label = name,
            location = SqLocation(context.gameContext.island, position, context.gameContext.context.profile),
            creator = localPlayer(),
            createdAtMillis = now(),
        )

        book = book.withWaypoint(waypoint)
        persist()
        refresh()
        say("Saved $name", "Private. Use /sqwp share to let somebody else see it.")
    }

    private fun list() {
        val mine = localPlayer()
        if (mine == null) {
            say("Waypoints", "Not signed in yet.")
            return
        }
        val visible = book.visibleTo(mine, membersNow(), now())
        if (visible.isEmpty()) {
            say("Waypoints", "None saved. /sqwp add <name> saves where you are standing.")
            return
        }
        val hidden = visible.count { !book.isShown(it) }
        say(
            "${visible.size} waypoint${if (visible.size == 1) "" else "s"}" +
                if (hidden > 0) " · $hidden hidden" else "",
            // Hidden ones are still listed, marked. Leaving them out would make "where did my waypoint go"
            // unanswerable by the command that exists to answer it.
            visible.joinToString(" · ") { waypoint ->
                val mark = if (book.isShown(waypoint)) "" else " (hidden)"
                "${waypoint.label}$mark"
            }.take(SUBTITLE_LIMIT),
        )
    }

    /** Flips one waypoint's own switch, or every one of them when no name is given. */
    private fun setShown(query: String, shown: Boolean) {
        if (query.isBlank()) {
            book = book.withAllShown(shown)
            persist()
            refresh()
            say(if (shown) "Showing every waypoint" else "Hid every waypoint", "Collections keep their own switch.")
            return
        }
        val waypoint = find(query)
        if (waypoint == null) {
            say("No such waypoint", ambiguityHint(query))
            return
        }
        editWaypoint(waypoint.id) { it.copy(isVisible = shown) }
        say(if (shown) "Showing ${waypoint.label}" else "Hid ${waypoint.label}")
    }

    private fun remove(query: String) {
        val waypoint = find(query)
        if (waypoint == null) {
            say("No such waypoint", ambiguityHint(query))
            return
        }
        book = book.withoutWaypoint(waypoint.id)
        persist()
        refresh()
        if (waypoint.audience.isShared) retract(waypoint, waypoint.audience)
        say("Removed ${waypoint.label}")
    }

    private fun share(query: String?, who: String?) {
        val waypoint = query?.let { find(it) }
        if (waypoint == null) {
            say("No such waypoint", ambiguityHint(query.orEmpty()))
            return
        }
        val audience = AUDIENCES[who?.lowercase()]
        if (audience == null) {
            say("Who with?", AUDIENCES.keys.joinToString(" · "))
            return
        }

        val updated = waypoint.copy(audience = audience)
        book = book.withWaypoint(updated)
        persist()
        refresh()

        // Said plainly, because sharing is the one action here somebody cannot take back by themselves — and
        // so is *not* sharing when somebody has been told it worked.
        say("${waypoint.label} is now ${describe(audience)}", explain(send(updated, previously = waypoint.audience)))
    }

    // -- sending -------------------------------------------------------------

    /**
     * What became of an attempt to send.
     *
     * Four outcomes rather than a boolean, because three of them need different sentences and telling them
     * apart is the point. "Your party is empty" and "the group is unreachable" are both nothing happening,
     * and only one of them is fixed by checking the network settings.
     */
    private enum class Sent {
        /** It went out. */
        YES,

        /** Private, so there was nothing to send. Not a failure. */
        NOT_SHARED,

        /** Shared with a party or a friends list that currently has nobody in it. */
        NOBODY,

        /** Somebody to send to, and no way to send it. */
        UNREACHABLE,
    }

    /**
     * Sends a waypoint out, or takes it back, according to what its audience now resolves to.
     *
     * @param previously the audience before this change, so narrowing can be told from never having shared.
     */
    private fun send(waypoint: SharedWaypoint, previously: WaypointAudience): Sent {
        val delivery = waypoint.audience.deliveryTo(membersNow())
        if (delivery != WaypointDelivery.None) {
            // Notes never travel. `WaypointBook.shareableWith` already established that sharing a place does
            // not share the commentary on it, and this is the other door out of the same house.
            val sent = publish(waypoint.copy(note = null), delivery, false)
            return if (sent) Sent.YES else Sent.UNREACHABLE
        }

        // Narrowed to nobody. Whoever had it has to be told, or their copy sits on their screen forever —
        // the one case where sending nothing is worse than sending something.
        if (previously.isShared) retract(waypoint, previously)
        return if (waypoint.audience.isShared) Sent.NOBODY else Sent.NOT_SHARED
    }

    private fun explain(sent: Sent): String = when (sent) {
        Sent.YES, Sent.NOT_SHARED -> ""
        // The distinction that earns the enum. Somebody who shares with an empty party has not hit a bug and
        // should not be sent to the network settings to look for one.
        Sent.NOBODY -> "Nobody to send it to right now. It will not be sent later either — share it again."
        Sent.UNREACHABLE -> "Nothing was sent. Sidequest is not connected to the group."
    }

    /**
     * Takes back a waypoint from the audience that had it.
     *
     * Addressed to the *previous* audience rather than to everybody, because a removal still carries the
     * waypoint's name and coordinates — telling the whole group to forget a place would be telling them where
     * it is. The cost is that somebody who has left the party since it was shared keeps their copy; they
     * already have its contents, so that is a stale marker rather than a leak, and it is the better half of
     * the trade.
     */
    private fun retract(waypoint: SharedWaypoint, audience: WaypointAudience): Boolean {
        val delivery = audience.deliveryTo(membersNow())
        if (delivery == WaypointDelivery.None) return false
        return publish(waypoint.copy(note = null), delivery, true)
    }

    // -- drawing -------------------------------------------------------------

    /**
     * Puts the waypoints that belong on screen into the marker service, and takes the rest out.
     *
     * Rebuilt wholesale rather than diffed. The list is small, the marker service is keyed by id so replacing
     * is idempotent, and a diff would be a second model of what is currently shown — which is the thing that
     * goes out of step.
     */
    private fun refresh() {
        val mine = localPlayer() ?: return
        context.markers.removeAll(MarkerKind.WAYPOINT)

        val here = context.gameContext.island
        for (waypoint in book.drawnFor(mine, membersNow(), now())) {
            // Only this island's. A waypoint in the Hub means nothing while standing in a dungeon, and the
            // marker service would happily draw a beam at those coordinates anyway.
            if (waypoint.location.island != here) continue
            context.markers.place(
                Marker(
                    id = waypoint.id,
                    kind = MarkerKind.WAYPOINT,
                    location = waypoint.location,
                    label = waypoint.label,
                    note = waypoint.note,
                    creator = waypoint.creator,
                    colour = waypoint.colour ?: book.collection(waypoint.collectionId)?.colour,
                    createdAtMillis = waypoint.createdAtMillis,
                    routeOrder = waypoint.routeOrder,
                ),
            )
        }
    }

    /**
     * Who counts as a friend and who is in the party, right now.
     *
     * Asked at the moment of the question rather than stored on the waypoint. That is the whole reason the
     * audience is an intent — see `WaypointAudience` — and this is where the intent meets the answer.
     */
    private fun membersNow(): AudienceMembers = AudienceMembers(
        friends = context.players.customFriends().map { it.id }.toSet(),
        // Only the members whose UUID this client has actually seen. A party member known by name alone
        // cannot be matched against anything, and inventing an id for them would be worse than omitting
        // them: sharing is the one decision here that cannot be taken back.
        party = context.party.party.members.mapNotNull { it.id }.toSet(),
    )

    private fun persist() {
        val store = repository ?: return
        val snapshot = book
        context.scheduler.async(context.owner) {
            runCatching { store.save(snapshot) }
                .onFailure { context.log.warn(it) { "Could not save waypoints" } }
        }
    }

    /** Says *why* a lookup failed, since "no such waypoint" is wrong when the problem is two of them. */
    private fun ambiguityHint(query: String): String {
        val clashes = book.waypoints.count { it.label.equals(query.trim().trim('"'), ignoreCase = true) }
        return if (clashes > 1) {
            "There are $clashes called that. Rename one in /sqwp."
        } else {
            "Run /sqwp to see them all."
        }
    }

    /** Opens the manager. Set by the mod, which owns screens. */
    var openManager: () -> Unit = {}

    /** Counts within a millisecond, so two things made in the same tick cannot share an id. */
    private var idCounter = 0

    /**
     * A short, unique, alphanumeric id.
     *
     * Alphanumeric because ids end up as `UiId` path segments on the manager screen, and that grammar allows
     * letters, digits and underscores only. Base 36 keeps it short enough to type after `/sqwp remove`, on
     * the rare occasion somebody addresses one by id rather than by name.
     */
    private fun nextId(): String = now().toString(RADIX) + (idCounter++).toString(RADIX)

    private fun say(title: String, subtitle: String = "") {
        context.notifications.notify(
            notification(category = NotificationCategory.SOCIAL, title = title, subtitle = subtitle),
        )
    }

    private fun describe(audience: WaypointAudience): String = when (audience) {
        is WaypointAudience.Private -> "private"
        is WaypointAudience.Friends -> "shared with your friends"
        is WaypointAudience.Party -> "shared with your party"
        is WaypointAudience.Group -> "shared with the group"
        is WaypointAudience.Selected -> "shared with ${audience.players.size} people"
    }

    /** The book, for the screen that draws it. */
    fun book(): WaypointBook = book

    // -- what the manager screen does ----------------------------------------

    /**
     * Applies a change to one waypoint.
     *
     * Takes a function rather than the new value so the screen never holds a whole waypoint: it edits one
     * field of whatever is current, which is the difference between "set the label" and "replace this
     * waypoint with the one I was drawing five minutes ago".
     */
    fun editWaypoint(id: String, change: (SharedWaypoint) -> SharedWaypoint) {
        val existing = book.waypoint(id) ?: return
        val updated = change(existing)
        book = book.withWaypoint(updated)
        persist()
        refresh()

        // Only when what the recipients are holding actually changed. The manager screen calls this on every
        // keystroke and every toggle, and republishing on each would put a message on the wire for hiding a
        // waypoint on your own screen — which is explicitly not a sharing decision.
        if (updated.sharedFace != existing.sharedFace) send(updated, previously = existing.audience)
    }

    /** Sets every waypoint's own switch. Collections keep theirs, so this cannot un-hide a hidden folder. */
    fun showAll(shown: Boolean) {
        book = book.withAllShown(shown)
        persist()
        refresh()
    }

    fun deleteWaypoint(id: String) {
        val waypoint = book.waypoint(id) ?: return
        book = book.withoutWaypoint(id)
        persist()
        refresh()
        if (waypoint.audience.isShared) retract(waypoint, waypoint.audience)
    }

    fun editCollection(id: String, change: (WaypointCollection) -> WaypointCollection) {
        val existing = book.collection(id) ?: return
        book = book.withCollection(change(existing))
        persist()
        refresh()
    }

    /** Removes a collection. Its waypoints survive — see [WaypointBook.withoutCollection]. */
    fun deleteCollection(id: String) {
        book = book.withoutCollection(id)
        persist()
        refresh()
    }

    /** Adds an empty collection with a placeholder name, for the screen's "add" button to then rename. */
    fun addCollection(name: String = "New collection"): WaypointCollection {
        val collection = WaypointCollection(id = "col" + nextId(), name = name)
        book = book.withCollection(collection)
        persist()
        refresh()
        return collection
    }

    private companion object {
        val VERBS = listOf("add", "list", "remove", "share", "hide", "show")

        val AUDIENCES: Map<String, WaypointAudience> = mapOf(
            "private" to WaypointAudience.Private,
            "friends" to WaypointAudience.Friends,
            "party" to WaypointAudience.Party,
            "group" to WaypointAudience.Group,
        )

        /** How often expiry is noticed. */
        val SWEEP = 10.seconds

        /** Base 36, so an id built from a timestamp is short enough to type after `/sqwp remove`. */
        const val RADIX = 36

        const val SUBTITLE_LIMIT = 160
    }
}

/**
 * The part of a waypoint that the people it was shared with are actually holding.
 *
 * Compared to decide whether an edit is worth putting on the wire. Deliberately *not* the whole waypoint: the
 * note is stripped before it goes out and `isVisible` is a switch on your own screen, so changing either must
 * not send anything. The audience is in here because changing it changes who the next message is addressed
 * to, even when the words on the beam are the same.
 */
private val SharedWaypoint.sharedFace: List<Any?>
    get() = listOf(label, location, audience)
