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
 * **Nothing is sent yet.** The audience is recorded and resolved, and [shareableWith] produces exactly what
 * would go out, but there is no transport call here — sharing over the backend is its own piece of work and
 * pretending otherwise would mean a "shared" waypoint that silently reached nobody.
 */
class SharedWaypoints(
    /** Who is playing, and where they are standing. Both need the game. */
    private val localPlayer: () -> PlayerId?,
    private val standingAt: () -> SqPosition?,
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
            description = "Save, list and share waypoints",
            usage = "<add|list|remove|share|folder> [...]",
            completions = { arguments ->
                when (arguments.size) {
                    0, 1 -> VERBS
                    2 -> when (arguments.first().lowercase()) {
                        "remove" -> book.waypoints.map { it.id }
                        "share" -> book.waypoints.map { it.id }
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
            "add" -> add(arguments.drop(1).joinToString(" "))
            "list" -> list()
            "remove" -> remove(arguments.getOrNull(1))
            "share" -> share(arguments.getOrNull(1), arguments.getOrNull(2))
            else -> say("Waypoints", "add <name> · list · remove <id> · share <id> <who>")
        }
    }

    private fun add(label: String) {
        val position = standingAt()
        if (position == null) {
            say("Nowhere to save", "You have to be in a world.")
            return
        }
        val name = label.ifBlank { "Waypoint" }
        val waypoint = SharedWaypoint(
            id = SqId.sidequest("wp").toString() + "/" + now().toString(RADIX),
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
        say(
            "${visible.size} waypoint${if (visible.size == 1) "" else "s"}",
            visible.joinToString(" · ") { "${it.label} (${it.location.island.displayName})" }
                .take(SUBTITLE_LIMIT),
        )
    }

    private fun remove(id: String?) {
        val waypoint = id?.let { book.waypoint(it) }
        if (waypoint == null) {
            say("No such waypoint", "Try /sqwp list.")
            return
        }
        book = book.withoutWaypoint(waypoint.id)
        persist()
        refresh()
        say("Removed ${waypoint.label}")
    }

    private fun share(id: String?, who: String?) {
        val waypoint = id?.let { book.waypoint(it) }
        if (waypoint == null) {
            say("No such waypoint", "Try /sqwp list.")
            return
        }
        val audience = AUDIENCES[who?.lowercase()]
        if (audience == null) {
            say("Who with?", AUDIENCES.keys.joinToString(" · "))
            return
        }

        book = book.withWaypoint(waypoint.copy(audience = audience))
        persist()
        refresh()
        // Said plainly, because sharing is the one action here somebody cannot take back by themselves.
        say("${waypoint.label} is now ${describe(audience)}")
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
        for (waypoint in book.visibleTo(mine, membersNow(), now())) {
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

    /** The book, for a screen that wants to draw it. */
    fun book(): WaypointBook = book

    /** Adds a collection. For the GUI, which does not exist yet — the model is ready for it. */
    fun addCollection(collection: WaypointCollection) {
        book = book.withCollection(collection)
        persist()
        refresh()
    }

    private companion object {
        val VERBS = listOf("add", "list", "remove", "share")

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
