package dev.th7bo.sidequest.platform.waypoint

import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import kotlinx.serialization.Serializable

/**
 * Who a waypoint is for.
 *
 * **Declared, not resolved.** "Everybody on my friends list" and "whoever is in the party" are answers that
 * change after the waypoint is made — somebody is added, somebody leaves — so storing the members at creation
 * time would freeze an answer that was only true that afternoon. The intent is stored and the membership is
 * asked for at the moment it matters.
 *
 * [Selected] is the exception and deliberately so: naming people is a decision about *those people*, and it
 * should not quietly grow to include somebody befriended next week.
 */
@Serializable
public sealed interface WaypointAudience {

    /** Nobody else. The default, because a waypoint that shared itself by accident is the bad outcome. */
    @Serializable
    public data object Private : WaypointAudience

    /** These people and nobody else, however the friends list changes afterwards. */
    @Serializable
    public data class Selected(public val players: Set<PlayerId>) : WaypointAudience

    /** Whoever is on the custom friends list when somebody looks. */
    @Serializable
    public data object Friends : WaypointAudience

    /** Whoever is in the party when somebody looks. Empty when there is no party. */
    @Serializable
    public data object Party : WaypointAudience

    /** Everybody the group's own sharing rules already allow. */
    @Serializable
    public data object Group : WaypointAudience
}

/**
 * What the audience needs to know to answer.
 *
 * Passed in rather than looked up, so [reaches] is a pure function of its inputs. The alternative — an
 * audience that consulted the party service itself — could not be tested without one.
 */
public data class AudienceMembers(
    public val friends: Set<PlayerId> = emptySet(),
    public val party: Set<PlayerId> = emptySet(),
    /** Everybody in the group, for [WaypointAudience.Group]. */
    public val group: Set<PlayerId> = emptySet(),
)

/**
 * Whether [viewer] is meant to see something shared with this audience.
 *
 * The creator is never included by any of these — a waypoint's owner sees their own regardless, and folding
 * that in here would mean a private waypoint whose audience said "nobody" had to make an exception for the
 * one person it is definitely for.
 */
public fun WaypointAudience.reaches(viewer: PlayerId, members: AudienceMembers): Boolean = when (this) {
    is WaypointAudience.Private -> false
    is WaypointAudience.Selected -> viewer in players
    is WaypointAudience.Friends -> viewer in members.friends
    is WaypointAudience.Party -> viewer in members.party
    is WaypointAudience.Group -> viewer in members.group
}

/** Whether this audience shares with anybody at all. Used to decide whether to send anything. */
public val WaypointAudience.isShared: Boolean
    get() = this !is WaypointAudience.Private &&
        !(this is WaypointAudience.Selected && players.isEmpty())

/**
 * A waypoint somebody saved.
 *
 * Distinct from a `Marker`, which is the transient in-world thing drawn this session: a marker is placed and
 * expires, and this is kept. One of these becomes a marker when it should be on screen, which keeps a single
 * rendering path rather than two things that draw beams slightly differently.
 */
@Serializable
public data class SharedWaypoint(
    public val id: String,
    public val label: String,
    public val location: SqLocation,
    /** Which collection it belongs to, or empty for the loose ones. */
    public val collectionId: String = "",
    public val note: String? = null,
    /** ARGB, or null to take the collection's. */
    public val colour: Int? = null,
    public val iconId: String? = null,
    /** Who made it. Null for one imported from a file, which has no author this client can vouch for. */
    public val creator: PlayerId? = null,
    public val audience: WaypointAudience = WaypointAudience.Private,
    public val createdAtMillis: Long = 0,
    /** When it stops being shown, or null to keep it until it is deleted. */
    public val expiresAtMillis: Long? = null,
    /** Position in a route, or null for one that is not part of one. */
    public val routeOrder: Int? = null,
) {
    public fun hasExpired(nowMillis: Long): Boolean =
        expiresAtMillis != null && nowMillis >= expiresAtMillis

    /** Whether [viewer] should see it, given who they are and who is around. */
    public fun isVisibleTo(viewer: PlayerId, members: AudienceMembers): Boolean =
        viewer == creator || audience.reaches(viewer, members)
}

/**
 * A named group of waypoints.
 *
 * Folders are a path on the collection rather than a tree of objects. A tree is the obvious model and the
 * wrong one here: it has to be kept consistent with the collections that live in it, it serialises into
 * something awkward, and every operation on it is a traversal. A path is a string, moving a collection is
 * assigning to it, and the tree is derived when something needs to draw one.
 */
@Serializable
public data class WaypointCollection(
    public val id: String,
    public val name: String,
    /** Slash-separated, no leading or trailing slash. Empty for the top level. */
    public val folder: String = "",
    public val colour: Int? = null,
    /** Whether its waypoints are drawn. Off hides the lot without deleting anything. */
    public val isVisible: Boolean = true,
    public val audience: WaypointAudience = WaypointAudience.Private,
)
