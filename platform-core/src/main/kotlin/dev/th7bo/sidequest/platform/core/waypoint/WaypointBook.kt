package dev.th7bo.sidequest.platform.core.waypoint

import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.waypoint.AudienceMembers
import dev.th7bo.sidequest.platform.waypoint.SharedWaypoint
import dev.th7bo.sidequest.platform.waypoint.WaypointCollection
import kotlinx.serialization.Serializable

/**
 * Everything somebody has saved, and where it is filed.
 *
 * Immutable, and every operation returns a new one. That is not ceremony: this is the thing being persisted,
 * synced and merged, and a mutable version would let a caller hand a half-edited book to a save and a sync at
 * the same time. It is at most a few hundred entries — the copying is free at that size and the class of bug
 * it removes is not.
 */
@Serializable
public data class WaypointBook(
    public val collections: List<WaypointCollection> = emptyList(),
    public val waypoints: List<SharedWaypoint> = emptyList(),
) {

    // -- collections ---------------------------------------------------------

    public fun collection(id: String): WaypointCollection? = collections.firstOrNull { it.id == id }

    /** Adds or replaces a collection, keyed by id. */
    public fun withCollection(collection: WaypointCollection): WaypointBook = copy(
        collections = collections.filterNot { it.id == collection.id } + collection,
    )

    /**
     * Removes a collection.
     *
     * Its waypoints are kept and moved to the top level rather than deleted with it. Deleting a folder should
     * not silently destroy what was in it — somebody tidying up would lose a season of waypoints to one
     * keystroke, and there is no undo for a file that has already been written.
     */
    public fun withoutCollection(id: String): WaypointBook = copy(
        collections = collections.filterNot { it.id == id },
        waypoints = waypoints.map { if (it.collectionId == id) it.copy(collectionId = "") else it },
    )

    /** Every folder path in use, deduplicated, including the parents that only exist implicitly. */
    public fun folders(): List<String> = collections
        .map { it.folder }
        .filter { it.isNotEmpty() }
        // A collection filed at `dungeons/f7/secrets` implies `dungeons` and `dungeons/f7`, which nothing
        // ever created. Deriving them is why the folder is a path rather than a tree of objects.
        .flatMap { path -> path.split('/').scan("") { parent, part -> if (parent.isEmpty()) part else "$parent/$part" } }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()

    /** The collections directly inside [folder], not those in its sub-folders. */
    public fun collectionsIn(folder: String): List<WaypointCollection> =
        collections.filter { it.folder == folder }.sortedBy { it.name.lowercase() }

    // -- waypoints -----------------------------------------------------------

    public fun waypoint(id: String): SharedWaypoint? = waypoints.firstOrNull { it.id == id }

    public fun withWaypoint(waypoint: SharedWaypoint): WaypointBook = copy(
        waypoints = waypoints.filterNot { it.id == waypoint.id } + waypoint,
    )

    public fun withoutWaypoint(id: String): WaypointBook =
        copy(waypoints = waypoints.filterNot { it.id == id })

    /** Moves a waypoint into a collection, or to the top level with an empty id. */
    public fun moved(waypointId: String, collectionId: String): WaypointBook = copy(
        waypoints = waypoints.map { if (it.id == waypointId) it.copy(collectionId = collectionId) else it },
    )

    public fun inCollection(collectionId: String): List<SharedWaypoint> =
        waypoints.filter { it.collectionId == collectionId }

    /**
     * The waypoints [viewer] is *allowed* to see: audience and expiry, nothing else.
     *
     * One place answers this, and everything about permission goes through it. Two places deciding who can
     * see a waypoint is how one of them ends up showing somebody a private one.
     *
     * Deliberately unaffected by whether anything is hidden. Hiding is a display switch — see [drawnFor] —
     * and folding it in here would mean tidying your own screen quietly withdrew waypoints from the people
     * you had shared them with.
     */
    public fun visibleTo(viewer: PlayerId, members: AudienceMembers, nowMillis: Long): List<SharedWaypoint> =
        waypoints.filter { waypoint ->
            !waypoint.hasExpired(nowMillis) && waypoint.isVisibleTo(viewer, members)
        }

    /**
     * What to actually draw for [viewer]: allowed, and not hidden.
     *
     * The two switches compose the obvious way — a hidden collection hides everything in it, and a hidden
     * waypoint stays hidden even in a shown collection — so "hide this folder except one" is one toggle and
     * not a special case.
     */
    public fun drawnFor(viewer: PlayerId, members: AudienceMembers, nowMillis: Long): List<SharedWaypoint> =
        visibleTo(viewer, members, nowMillis).filter { isShown(it) }

    /** Whether [waypoint] and its collection are both switched on. */
    public fun isShown(waypoint: SharedWaypoint): Boolean =
        waypoint.isVisible && (collection(waypoint.collectionId)?.isVisible ?: true)

    /** Sets every waypoint's own switch, for a "show all" or "hide all" that does not touch collections. */
    public fun withAllShown(shown: Boolean): WaypointBook =
        copy(waypoints = waypoints.map { it.copy(isVisible = shown) })

    /**
     * The route in a collection, in order.
     *
     * Only the waypoints that declared an order — an unordered one in the same collection is a place, not a
     * step, and silently appending it to the end would send somebody somewhere they never asked to go.
     */
    public fun route(collectionId: String): List<SharedWaypoint> = waypoints
        .filter { it.collectionId == collectionId && it.routeOrder != null }
        .sortedWith(compareBy({ it.routeOrder }, { it.id }))

    /** Drops everything past its expiry. For a periodic tidy rather than for reading. */
    public fun pruned(nowMillis: Long): WaypointBook =
        copy(waypoints = waypoints.filterNot { it.hasExpired(nowMillis) })

    /**
     * Merges [incoming] into this book.
     *
     * Used for an import and for a sync. **The existing entry wins on a clash of ids**, which is the safe
     * direction: an import that overwrote what somebody already had would destroy local edits to make room
     * for a copy of them, and there is no way back from that. A caller wanting the other behaviour removes
     * the entry first, which is at least a decision somebody made.
     */
    public fun merged(incoming: WaypointBook): WaypointBook {
        val knownCollections = collections.map { it.id }.toSet()
        val knownWaypoints = waypoints.map { it.id }.toSet()
        return WaypointBook(
            collections = collections + incoming.collections.filterNot { it.id in knownCollections },
            waypoints = waypoints + incoming.waypoints.filterNot { it.id in knownWaypoints },
        )
    }

    /**
     * A copy safe to hand to somebody else.
     *
     * Only what [viewer] is meant to see, and **notes are stripped**. A note is where somebody writes "sell
     * here, the guy undercuts" — private commentary attached to a place they are happy to share. Sharing the
     * place should not share the commentary, and the least surprising rule is that it never does.
     *
     * Built from [visibleTo] rather than [drawnFor], so hiding something on your own screen does not withdraw
     * it from the people you shared it with. The recipient's own copy is theirs to hide.
     */
    public fun shareableWith(viewer: PlayerId, members: AudienceMembers, nowMillis: Long): WaypointBook {
        val shared = visibleTo(viewer, members, nowMillis).map { it.copy(note = null) }
        val used = shared.map { it.collectionId }.toSet()
        return WaypointBook(
            collections = collections.filter { it.id in used },
            waypoints = shared,
        )
    }
}
