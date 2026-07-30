package dev.th7bo.sidequest.platform.core.marker

import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.marker.Acknowledgement
import dev.th7bo.sidequest.platform.marker.Marker
import dev.th7bo.sidequest.platform.marker.MarkerAcknowledgedEvent
import dev.th7bo.sidequest.platform.marker.MarkerArrivedEvent
import dev.th7bo.sidequest.platform.marker.MarkerKind
import dev.th7bo.sidequest.platform.marker.MarkerPlacedEvent
import dev.th7bo.sidequest.platform.marker.MarkerRemovalReason
import dev.th7bo.sidequest.platform.marker.MarkerRemovedEvent
import dev.th7bo.sidequest.platform.marker.MarkerService
import dev.th7bo.sidequest.platform.marker.MarkerStore
import dev.th7bo.sidequest.platform.marker.TrackedMarker
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.skyblock.GameContextService
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import java.util.UUID

/**
 * Holds markers, and decides when they go away.
 *
 * The interesting part is not placing one, it is everything after. Three ideas shape it.
 *
 * **A marker knows its island, and comparisons go through that.** Two coordinates on different islands are not
 * near each other however close the numbers look. Without it, a death in the Catacombs draws a beam through
 * somebody's private island — the classic bug in every waypoint mod, and the reason nothing here takes a bare
 * position.
 *
 * **Nothing is removed silently.** Expiry, arrival, leaving the island and a deletion all look identical to a
 * listener otherwise, and "my waypoint disappeared" is then unanswerable.
 *
 * **Arrival is a thing that happened.** It gets its own event rather than being folded into the removal,
 * because a feature may care that the player got there regardless of what becomes of the marker.
 */
public class DefaultMarkerService(
    private val context: GameContextService,
    private val events: EventBus,
    private val log: Logger,
    /** Where the local player is. Null when not in a world, which makes everything invisible rather than near. */
    private val localPosition: () -> SqPosition?,
    private val localPlayer: () -> PlayerId?,
    private val now: () -> Long = System::currentTimeMillis,
) : MarkerService {

    private val markers = LinkedHashMap<String, Marker>()
    private val acknowledgements = HashMap<String, MutableMap<PlayerId, Acknowledgement>>()

    /** Markers the local player has reached, so arrival fires once rather than every tick inside the radius. */
    private val arrived = HashSet<String>()

    /** Called after every change, so the caller can persist. Set by whoever owns the storage. */
    public var onStoreChanged: ((MarkerStore) -> Unit)? = null

    /** Restores saved markers. */
    public fun load(store: MarkerStore) {
        for (marker in store.markers) {
            // Defaulted before the expiry check, and that ordering is the fix rather than a nicety: a stored
            // marker carries whatever lifetime it had, which for one saved before its kind gained a default is
            // null — and a null lifetime never expires. Loading is the same normalisation as placing.
            val restored = marker.withDefaults()
            // Checked here and not only on tick: a file can hold something that ran out while the game was
            // closed, and restoring it would show a marker from last week until the first tick.
            if (restored.hasExpired(now())) continue
            markers[restored.id] = restored
        }
        log.debug { "Loaded ${markers.size} marker(s)" }
    }

    /**
     * Fills in what the kind decides.
     *
     * One function, used by both [place] and [load], because a marker restored from disk has to mean the same
     * thing as one just placed. Two copies of this drifted apart once already — the loaded one had no lifetime
     * and therefore never expired.
     */
    private fun Marker.withDefaults(): Marker = copy(
        id = id.ifEmpty { UUID.randomUUID().toString() },
        createdAtMillis = if (createdAtMillis == 0L) now() else createdAtMillis,
        lifetime = lifetime ?: kind.defaultLifetime,
    )

    override fun place(marker: Marker): Marker {
        // Defaults from the kind unless the caller said otherwise, so every death marker in the mod lasts the
        // same time and a feature placing one does not also have to decide how long that is.
        val stamped = marker.withDefaults()

        markers.put(stamped.id, stamped)?.let { previous ->
            // Announced rather than quietly overwritten. Replacing a marker under an existing id is legitimate
            // — a shared waypoint being updated — and a listener holding the old one needs to know.
            events.post(MarkerRemovedEvent(previous, MarkerRemovalReason.REPLACED), EventSource.DERIVED)
        }
        arrived.remove(stamped.id)

        log.debug { "Placed $stamped at ${stamped.location.island}" }
        events.post(MarkerPlacedEvent(stamped), EventSource.DERIVED)
        persist()
        return stamped
    }

    override fun remove(markerId: String): Boolean {
        val marker = markers.remove(markerId) ?: return false
        acknowledgements.remove(markerId)
        arrived.remove(markerId)
        events.post(MarkerRemovedEvent(marker, MarkerRemovalReason.DELETED), EventSource.DERIVED)
        persist()
        return true
    }

    override fun removeAll(kind: MarkerKind): Int {
        val doomed = markers.values.filter { it.kind == kind }
        for (marker in doomed) remove(marker.id)
        return doomed.size
    }

    override fun all(): List<TrackedMarker> = markers.values.map { track(it) }

    override fun visible(): List<TrackedMarker> {
        val viewer = localPlayer()
        return markers.values
            .filter { it.isFor(viewer) }
            .map { track(it) }
            .filter { it.isVisible }
            // Far to near, so a nearer marker draws over a more distant one. The reverse of insertion order,
            // and the difference between a readable field of waypoints and a pile.
            .sortedByDescending { it.distance ?: Double.MAX_VALUE }
    }

    override fun get(markerId: String): TrackedMarker? = markers[markerId]?.let { track(it) }

    override fun acknowledge(markerId: String, viewer: PlayerId, acknowledgement: Acknowledgement) {
        val marker = markers[markerId] ?: return
        acknowledgements.getOrPut(markerId) { HashMap() }[viewer] = acknowledgement
        log.debug { "$viewer answered $acknowledgement to $marker" }
        events.post(MarkerAcknowledgedEvent(marker, viewer, acknowledgement), EventSource.DERIVED)
    }

    override fun route(): List<TrackedMarker> = markers.values
        .filter { it.routeOrder != null }
        .sortedBy { it.routeOrder }
        .map { track(it) }

    /**
     * Expires what has run out and notices arrivals. Called from a tick.
     *
     * Both here rather than in two places, because both need the same walk over the same map and the tick is
     * already paying for it. Cheap: returns immediately when there is nothing held, which is most of the time.
     */
    public fun tick() {
        if (markers.isEmpty()) return

        val expired = markers.values.filter { it.hasExpired(now()) }
        for (marker in expired) {
            markers.remove(marker.id)
            acknowledgements.remove(marker.id)
            arrived.remove(marker.id)
            events.post(MarkerRemovedEvent(marker, MarkerRemovalReason.EXPIRED), EventSource.DERIVED)
        }
        if (expired.isNotEmpty()) {
            log.debug { "Expired ${expired.size} marker(s)" }
            persist()
        }

        checkArrivals()
    }

    /**
     * Notices the local player reaching a marker.
     *
     * Once per marker, tracked in [arrived]: inside the radius is true on every tick, and posting an event for
     * each would be twenty a second for as long as somebody stands still.
     */
    private fun checkArrivals() {
        val here = currentLocation() ?: return

        for (marker in markers.values.toList()) {
            if (marker.arrivalRadius <= 0.0) continue
            if (marker.id in arrived) continue
            if (!marker.location.isSameSpaceAs(here)) continue

            val away = here.position.distanceTo(marker.location.position)
            if (away > marker.arrivalRadius) continue

            arrived.add(marker.id)
            log.debug { "Arrived at $marker" }
            events.post(MarkerArrivedEvent(marker), EventSource.DERIVED)

            // Navigation clears itself on arrival, because that is what it was for. A waypoint does not — the
            // player put it there and walking past is not asking for it to be deleted.
            if (marker.kind == MarkerKind.NAVIGATION) {
                markers.remove(marker.id)
                events.post(MarkerRemovedEvent(marker, MarkerRemovalReason.ARRIVED), EventSource.DERIVED)
                persist()
            }
        }
    }

    /**
     * Drops what belongs to somewhere the player no longer is.
     *
     * Only the transient kinds. A waypoint survives leaving the island — that is the whole point of one — but a
     * ping in a dungeon that has been left is pointing at a room that no longer exists.
     */
    public fun onIslandChanged() {
        val doomed = markers.values.filter { it.kind in TRANSIENT_KINDS }
        if (doomed.isEmpty()) return
        for (marker in doomed) {
            markers.remove(marker.id)
            acknowledgements.remove(marker.id)
            arrived.remove(marker.id)
            events.post(MarkerRemovedEvent(marker, MarkerRemovalReason.LEFT_AREA), EventSource.DERIVED)
        }
        log.debug { "Dropped ${doomed.size} marker(s) on leaving the area" }
        persist()
    }

    private fun track(marker: Marker): TrackedMarker = TrackedMarker(
        marker = marker,
        acknowledgements = acknowledgements[marker.id].orEmpty(),
        hasArrived = marker.id in arrived,
        distance = distanceTo(marker),
    )

    /**
     * How far away it is, or null when the question does not apply.
     *
     * Null rather than a large number for a marker in another coordinate space, and the difference matters: a
     * large number sorts and compares, so a marker on another island would be "far away" rather than "not
     * here", and would still be drawn once somebody widened a range.
     */
    private fun distanceTo(marker: Marker): Double? {
        val here = currentLocation() ?: return null
        if (!marker.location.isSameSpaceAs(here)) return null
        return here.position.distanceTo(marker.location.position)
    }

    private fun currentLocation(): SqLocation? {
        val position = localPosition() ?: return null
        val game = context.context
        return SqLocation(
            island = game.island,
            position = position,
            profile = game.profile,
        )
    }

    /** Only what is worth keeping. See [MarkerStore]. */
    private fun persist() {
        onStoreChanged?.invoke(
            MarkerStore(markers = markers.values.filter { it.kind in PERSISTED_KINDS }),
        )
    }

    private companion object {
        /**
         * The kinds that survive a restart.
         *
         * Waypoints only. A ping is about a moment, a death marker is about the last few minutes, and a
         * navigation request restored on the next login points at somewhere nobody remembers asking for.
         */
        val PERSISTED_KINDS = setOf(MarkerKind.WAYPOINT)

        /** The kinds that do not survive leaving the area. */
        val TRANSIENT_KINDS = setOf(MarkerKind.PING, MarkerKind.RALLY, MarkerKind.NPC)
    }
}
