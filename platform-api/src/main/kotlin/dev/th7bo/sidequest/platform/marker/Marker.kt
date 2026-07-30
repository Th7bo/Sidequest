package dev.th7bo.sidequest.platform.marker

import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Something at a place.
 *
 * The plan asks for one shared marker system behind permanent waypoints, shared waypoints, temporary pings,
 * death markers, NPC markers, navigation requests and "come here" targets. All seven are a position, a label
 * and a lifetime; written separately they become seven slightly different answers to "when does this go away",
 * and the one that gets it wrong leaves markers on the screen forever.
 *
 * A marker is data and is serialisable, because it is the same value whether it was placed locally, restored
 * from disk, or arrived from a friend over the wire.
 */
@Serializable
public data class Marker(
    public val id: String,
    /** What kind it is. Decides the default lifetime and how it is drawn. */
    public val kind: MarkerKind,
    /**
     * Where it is — island *and* position.
     *
     * Never a bare position. Two coordinates on different islands are not near each other however close the
     * numbers look, and a marker that forgot its island is a marker that draws a beam through the Hub because
     * somebody died in the Catacombs.
     */
    public val location: SqLocation,
    public val label: String = "",
    public val note: String? = null,

    /** Who placed it. Null for one the mod placed itself, like a death marker. */
    public val creator: PlayerId? = null,

    /**
     * Who can see it.
     *
     * Empty means everybody the sharing rules already allow — the marker does not re-decide that, the
     * permission service does. A non-empty set narrows it further, which is what a "come here" aimed at one
     * person needs.
     */
    public val recipients: Set<PlayerId> = emptySet(),

    /** Overrides the kind's icon. Null takes it. */
    public val iconId: SqId? = null,
    /** ARGB, or null for the kind's colour. */
    public val colour: Int? = null,

    public val createdAtMillis: Long = 0,

    /**
     * How long it lasts, or null for as long as the player keeps it.
     *
     * Null means permanent, and it is the *exception* rather than the default: a marker with no expiry is one
     * somebody has to remember to delete, and nobody does.
     */
    public val lifetime: Duration? = null,

    /**
     * How close counts as arrived, in blocks.
     *
     * Zero means arrival is not tracked. A navigation request that never notices it was fulfilled leaves an
     * arrow pointing at the player's own feet.
     */
    public val arrivalRadius: Double = 0.0,

    /** Beyond this many blocks it is not drawn at all. Null takes the kind's default. */
    public val visibilityRange: Double? = null,

    /** An entity or player it follows, if any. Resolved by whoever can see entities. */
    public val attachedTo: MarkerAttachment? = null,

    /** Position in a route, for a series meant to be visited in order. Null for a standalone marker. */
    public val routeOrder: Int? = null,

    public val render: MarkerRender = MarkerRender(),
) {

    /** Whether it has run out. Permanent markers never have. */
    public fun hasExpired(nowMillis: Long): Boolean {
        val duration = lifetime ?: return false
        return nowMillis - createdAtMillis >= duration.inWholeMilliseconds
    }

    /** How long is left, or null for a permanent one. */
    public fun remaining(nowMillis: Long): Duration? {
        val duration = lifetime ?: return null
        val left = duration.inWholeMilliseconds - (nowMillis - createdAtMillis)
        return if (left <= 0) Duration.ZERO else left.milliseconds
    }

    /** Whether [viewer] is among those meant to see it. */
    public fun isFor(viewer: PlayerId?): Boolean =
        recipients.isEmpty() || (viewer != null && viewer in recipients)

    override fun toString(): String = "$kind:$id${label.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}"
}

/**
 * What a marker is for.
 *
 * The kind carries the defaults, so a feature placing a death marker does not also have to decide how long a
 * death marker should last — and every death marker in the mod then lasts the same time, which is the point of
 * having a kind at all.
 */
@Serializable
public enum class MarkerKind(
    public val displayName: String,
    /** The default lifetime. Null is permanent. */
    public val defaultLifetime: Duration?,
    /** How far away it is still drawn, in blocks. */
    public val defaultRange: Double,
    /** ARGB. */
    public val defaultColour: Int,
) {
    /** Placed by the player, kept until deleted. The only permanent kind, and deliberately the only one. */
    WAYPOINT("Waypoint", null, 512.0, 0xFF8B5CF6.toInt()),

    /**
     * A gesture, not a landmark.
     *
     * Thirty seconds, matching the protocol's own expiry for a ping: a ping from two minutes ago is noise, and
     * a client that kept it longer than the server would show markers nobody else can see.
     */
    PING("Ping", 30.seconds, 256.0, 0xFFFBBF24.toInt()),

    /** Where somebody died. Long enough to walk back for the items, and not longer. */
    DEATH("Death", TEN_MINUTES, 1024.0, 0xFFEF4444.toInt()),

    /** An NPC or a fixture worth remembering. Permanent in effect, and rebuilt from data rather than saved. */
    NPC("NPC", null, 128.0, 0xFF34D399.toInt()),

    /** "Go here." Cleared on arrival rather than by a clock, which is what [Marker.arrivalRadius] is for. */
    NAVIGATION("Navigation", THIRTY_MINUTES, 2048.0, 0xFF60A5FA.toInt()),

    /** "Come here." A request aimed at somebody, so it usually has recipients. */
    RALLY("Rally", FIVE_MINUTES, 1024.0, 0xFFF472B6.toInt()),
}

// Top level, because an enum's own companion is not initialised while its constants are being constructed —
// which is a Kotlin rule worth stating once rather than rediscovering per enum.
private val TEN_MINUTES: Duration = 10.minutes
private val THIRTY_MINUTES: Duration = 30.minutes
private val FIVE_MINUTES: Duration = 5.minutes

/** What a marker follows, when it is not fixed to a block. */
@Serializable
public sealed interface MarkerAttachment {

    /** Follows a player, so a rally target moves with the person who sent it. */
    @Serializable
    public data class Player(public val playerId: String) : MarkerAttachment

    /** Follows an entity by its network id. Only meaningful while that entity is loaded. */
    @Serializable
    public data class Entity(public val entityId: Int) : MarkerAttachment
}

/**
 * How a marker is drawn.
 *
 * Every option the plan lists, as flags rather than as a style enum. A beam and an edge indicator are
 * independent choices — a death marker wants both, a ping wants the beam and not the arrow — and an enum would
 * have to name every combination.
 */
@Serializable
public data class MarkerRender(
    /** A vertical beam from the ground. Visible through terrain, which is most of its value. */
    public val beam: Boolean = true,
    public val icon: Boolean = true,
    public val distanceLabel: Boolean = true,
    /** An arrow at the screen edge when it is off-screen. */
    public val edgeIndicator: Boolean = true,
    /** An outline around the block it sits on. */
    public val outline: Boolean = false,
    /** A directional arrow near the crosshair, for navigation. */
    public val directionalArrow: Boolean = false,
) {
    public companion object {
        /** Everything. For a navigation request, which is the one case that wants all of it. */
        public val Full: MarkerRender = MarkerRender(outline = true, directionalArrow = true)

        /** Beam and icon only. For a ping, which should be noticed and then forgotten. */
        public val Minimal: MarkerRender = MarkerRender(distanceLabel = false, edgeIndicator = false)
    }
}

/** Whether somebody has responded to a marker. */
@Serializable
public enum class Acknowledgement {
    /** Nobody has said anything. */
    NONE,

    /** Seen but not acted on. */
    SEEN,

    /** On the way. */
    COMING,

    /** Arrived, or otherwise done with. */
    ARRIVED,

    /** Explicitly declined. Distinct from [NONE] — a "no" is an answer and silence is not. */
    DECLINED,
}

/** A marker plus what has happened to it since it was placed. */
public data class TrackedMarker(
    public val marker: Marker,
    /** Per viewer, so a rally aimed at four people can show who is coming. */
    public val acknowledgements: Map<PlayerId, Acknowledgement> = emptyMap(),
    /** True once the local player has been within [Marker.arrivalRadius]. */
    public val hasArrived: Boolean = false,
    /** Distance from the local player, or null when they are not in the same space. */
    public val distance: Double? = null,
) {
    /** Whether it is drawable right now: same island, and within range. */
    public val isVisible: Boolean
        get() {
            val away = distance ?: return false
            return away <= (marker.visibilityRange ?: marker.kind.defaultRange)
        }
}

/** Everything stored about markers. One repository, so a marker's state cannot half-save. */
@Serializable
public data class MarkerStore(
    /**
     * Only the ones worth keeping.
     *
     * Pings and death markers are deliberately *not* persisted: both are about a moment, and a ping restored
     * on the next login is a marker pointing at somewhere nobody remembers.
     */
    public val markers: List<Marker> = emptyList(),
)

/**
 * The one place markers live.
 *
 * A feature places one and does not decide when it disappears, who may see it, or whether the player has got
 * there. Seven features each tracking their own would be seven answers to "when does this go away".
 */
public interface MarkerService {

    /** Places one. Returns it as stored, with its id and timestamp filled in. */
    public fun place(marker: Marker): Marker

    public fun remove(markerId: String): Boolean

    /** Removes every marker of a kind. For "clear my pings". */
    public fun removeAll(kind: MarkerKind): Int

    /** Everything currently held, expired ones already gone. */
    public fun all(): List<TrackedMarker>

    /** What should be drawn right now: this island, in range, meant for this viewer. */
    public fun visible(): List<TrackedMarker>

    public operator fun get(markerId: String): TrackedMarker?

    /** Records a response to a marker. */
    public fun acknowledge(markerId: String, viewer: PlayerId, acknowledgement: Acknowledgement)

    /**
     * The route, in order.
     *
     * Markers with a [Marker.routeOrder], sorted. Empty when nothing is routed, which is the usual case.
     */
    public fun route(): List<TrackedMarker>
}

// -- events ----------------------------------------------------------------

public class MarkerPlacedEvent(public val marker: Marker) : SidequestEvent() {
    override fun describe(): String = "marker placed: $marker"
}

public class MarkerRemovedEvent(
    public val marker: Marker,
    /** Why it went. Expiry, arrival and a deletion look identical to a listener otherwise. */
    public val reason: MarkerRemovalReason,
) : SidequestEvent() {
    override fun describe(): String = "marker removed ($reason): $marker"
}

public enum class MarkerRemovalReason {
    DELETED,
    EXPIRED,
    ARRIVED,
    /** The island changed, so it is no longer in this coordinate space. */
    LEFT_AREA,
    REPLACED,
}

/**
 * The local player reached a marker.
 *
 * Its own event rather than a flag on the removal, because arriving is a thing that *happened* and a feature
 * may want to react to it — a contract completing, a navigation clearing — regardless of what becomes of the
 * marker afterwards.
 */
public class MarkerArrivedEvent(public val marker: Marker) : SidequestEvent() {
    override fun describe(): String = "arrived at $marker"
}

public class MarkerAcknowledgedEvent(
    public val marker: Marker,
    public val viewer: PlayerId,
    public val acknowledgement: Acknowledgement,
) : SidequestEvent() {
    override fun describe(): String = "$viewer answered $acknowledgement to $marker"
}
