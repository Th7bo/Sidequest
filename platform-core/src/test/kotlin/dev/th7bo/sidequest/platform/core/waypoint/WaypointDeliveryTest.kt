package dev.th7bo.sidequest.platform.core.waypoint

import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.waypoint.AudienceMembers
import dev.th7bo.sidequest.platform.waypoint.WaypointAudience
import dev.th7bo.sidequest.platform.waypoint.WaypointDelivery
import dev.th7bo.sidequest.platform.waypoint.deliveryTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Turning "who is this for" into "who do I address it to".
 *
 * Worth its own file because of one trap, and the whole type exists to make that trap unreachable: on the
 * wire an *empty* recipient list means **everybody entitled**. So a resolver that answered with a bare set
 * would give the same answer for "shared with the group" and "shared with a party that turned out to be
 * empty" — and the second one would be sent to the entire group.
 */
class WaypointDeliveryTest {

    private val friend = PlayerId("00000000-0000-0000-0000-00000000000b")
    private val partyMate = PlayerId("00000000-0000-0000-0000-00000000000c")
    private val named = PlayerId("00000000-0000-0000-0000-00000000000d")

    private val members = AudienceMembers(friends = setOf(friend), party = setOf(partyMate))

    @Test
    fun `private sends nothing`() {
        assertEquals(WaypointDelivery.None, WaypointAudience.Private.deliveryTo(members))
    }

    /** The group is the one audience that is deliberately *not* narrowed. */
    @Test
    fun `the group is everybody entitled, with no recipient list`() {
        assertEquals(WaypointDelivery.Everybody, WaypointAudience.Group.deliveryTo(members))
    }

    @Test
    fun `a resolved audience names the people in it`() {
        assertEquals(WaypointDelivery.Named(setOf(friend)), WaypointAudience.Friends.deliveryTo(members))
        assertEquals(WaypointDelivery.Named(setOf(partyMate)), WaypointAudience.Party.deliveryTo(members))
        assertEquals(
            WaypointDelivery.Named(setOf(named)),
            WaypointAudience.Selected(setOf(named)).deliveryTo(members),
        )
    }

    // -- the trap ------------------------------------------------------------

    /**
     * An audience that resolves to nobody sends nothing.
     *
     * The one that matters. "Share with my party" while standing alone must not become a broadcast, and the
     * only reason it cannot is that the empty case has a name of its own rather than being an empty set.
     */
    @Test
    fun `an audience that resolves to nobody is not everybody`() {
        val alone = AudienceMembers()

        assertEquals(WaypointDelivery.None, WaypointAudience.Party.deliveryTo(alone))
        assertEquals(WaypointDelivery.None, WaypointAudience.Friends.deliveryTo(alone))
        assertEquals(WaypointDelivery.None, WaypointAudience.Selected(emptySet()).deliveryTo(alone))
    }

    /** Stated as the property rather than case by case, so a new audience cannot quietly opt out of it. */
    @Test
    fun `only the group ever resolves to everybody`() {
        val audiences = listOf(
            WaypointAudience.Private,
            WaypointAudience.Friends,
            WaypointAudience.Party,
            WaypointAudience.Selected(setOf(named)),
            WaypointAudience.Group,
        )

        val broadcasting = audiences.filter { it.deliveryTo(members) == WaypointDelivery.Everybody }
        assertEquals(listOf(WaypointAudience.Group), broadcasting)
    }
}
