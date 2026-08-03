package dev.th7bo.sidequest.platform.core.waypoint

import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import dev.th7bo.sidequest.platform.waypoint.AudienceMembers
import dev.th7bo.sidequest.platform.waypoint.SharedWaypoint
import dev.th7bo.sidequest.platform.waypoint.WaypointAudience
import dev.th7bo.sidequest.platform.waypoint.WaypointCollection
import dev.th7bo.sidequest.platform.waypoint.isShared
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Saved waypoints, and who gets to see them.
 *
 * Most of what follows is about the answer to "who can see this", because that is the half with a cost when
 * it is wrong: a waypoint shown to the wrong person cannot be un-shown, and one silently deleted cannot be
 * recovered from a file that has already been written.
 */
class WaypointBookTest {

    private val me = PlayerId("00000000-0000-0000-0000-00000000000a")
    private val friend = PlayerId("00000000-0000-0000-0000-00000000000b")
    private val partyMate = PlayerId("00000000-0000-0000-0000-00000000000c")
    private val stranger = PlayerId("00000000-0000-0000-0000-00000000000d")

    private val members = AudienceMembers(friends = setOf(friend), party = setOf(partyMate))

    private fun waypoint(
        id: String,
        audience: WaypointAudience = WaypointAudience.Private,
        collection: String = "",
        note: String? = null,
        expiresAt: Long? = null,
        order: Int? = null,
    ) = SharedWaypoint(
        id = id,
        label = id,
        location = SqLocation(Island.HUB, SqPosition(0.0, 70.0, 0.0)),
        collectionId = collection,
        note = note,
        creator = me,
        audience = audience,
        expiresAtMillis = expiresAt,
        routeOrder = order,
    )

    // -- who sees what -------------------------------------------------------

    /** The owner always sees their own, whatever the audience says. */
    @Test
    fun `a private waypoint is for its creator alone`() {
        val book = WaypointBook().withWaypoint(waypoint("home"))

        assertEquals(1, book.visibleTo(me, members, NOW).size)
        assertTrue(book.visibleTo(friend, members, NOW).isEmpty())
        assertTrue(book.visibleTo(stranger, members, NOW).isEmpty())
    }

    /**
     * A friends audience is resolved when somebody looks, not when the waypoint was made.
     *
     * The whole reason the audience is an intent rather than a list. Somebody befriended after the waypoint
     * was saved should see it, and somebody unfriended should stop.
     */
    @Test
    fun `a friends waypoint follows the friends list`() {
        val book = WaypointBook().withWaypoint(waypoint("spot", WaypointAudience.Friends))

        assertTrue(book.visibleTo(friend, members, NOW).isNotEmpty())
        assertTrue(book.visibleTo(stranger, members, NOW).isEmpty())

        // Befriended afterwards, with the waypoint untouched.
        val later = members.copy(friends = members.friends + stranger)
        assertTrue(book.visibleTo(stranger, later, NOW).isNotEmpty())
    }

    @Test
    fun `a party waypoint follows the party`() {
        val book = WaypointBook().withWaypoint(waypoint("boss", WaypointAudience.Party))

        assertTrue(book.visibleTo(partyMate, members, NOW).isNotEmpty())
        assertTrue(book.visibleTo(friend, members, NOW).isEmpty(), "a friend is not automatically in the party")
    }

    /**
     * Naming people freezes the list, and that is the point of naming them.
     *
     * A waypoint shared with two specific people is a decision about those two. Growing it to include
     * somebody befriended next week would be the mod making a sharing decision nobody asked for.
     */
    @Test
    fun `a selected audience does not grow`() {
        val book = WaypointBook().withWaypoint(waypoint("secret", WaypointAudience.Selected(setOf(friend))))

        val later = members.copy(friends = setOf(friend, stranger))
        assertTrue(book.visibleTo(friend, later, NOW).isNotEmpty())
        assertTrue(book.visibleTo(stranger, later, NOW).isEmpty())
    }

    @Test
    fun `an expired waypoint is shown to nobody`() {
        val book = WaypointBook().withWaypoint(
            waypoint("temp", WaypointAudience.Friends, expiresAt = NOW - 1),
        )

        assertTrue(book.visibleTo(me, members, NOW).isEmpty(), "not even its owner")
        assertTrue(book.visibleTo(friend, members, NOW).isEmpty())
    }

    /** A hidden collection is "not right now" — it applies to the owner too, or it would be a strange switch. */
    @Test
    fun `hiding a collection hides it for its owner as well`() {
        val book = WaypointBook()
            .withCollection(WaypointCollection(id = "f7", name = "Floor 7", isVisible = false))
            .withWaypoint(waypoint("secret", collection = "f7"))

        assertTrue(book.visibleTo(me, members, NOW).isEmpty())
    }

    // -- collections and folders ---------------------------------------------

    /**
     * Deleting a collection keeps what was in it.
     *
     * Somebody tidying up should not lose a season of waypoints to one keystroke, and there is no undo for a
     * file that has already been written.
     */
    @Test
    fun `removing a collection moves its waypoints to the top level`() {
        val book = WaypointBook()
            .withCollection(WaypointCollection(id = "old", name = "Old"))
            .withWaypoint(waypoint("keep", collection = "old"))

        val after = book.withoutCollection("old")

        assertNull(after.collection("old"))
        assertEquals(1, after.waypoints.size, "the waypoint survived")
        assertEquals("", after.waypoint("keep")?.collectionId)
    }

    /**
     * Parent folders exist without anybody creating them.
     *
     * Filing something under `dungeons/f7/secrets` means `dungeons` and `dungeons/f7` are folders too. It is
     * why the folder is a path rather than a tree: nothing has to be kept in step with anything.
     */
    @Test
    fun `parent folders are derived from the paths in use`() {
        val book = WaypointBook()
            .withCollection(WaypointCollection(id = "s", name = "Secrets", folder = "dungeons/f7/secrets"))
            .withCollection(WaypointCollection(id = "m", name = "Mining", folder = "mining"))

        assertEquals(
            listOf("dungeons", "dungeons/f7", "dungeons/f7/secrets", "mining"),
            book.folders(),
        )
    }

    @Test
    fun `collections list only their own folder's`() {
        val book = WaypointBook()
            .withCollection(WaypointCollection(id = "a", name = "Alpha", folder = "dungeons"))
            .withCollection(WaypointCollection(id = "b", name = "Beta", folder = "dungeons/f7"))

        assertEquals(listOf("a"), book.collectionsIn("dungeons").map { it.id })
    }

    // -- routes --------------------------------------------------------------

    @Test
    fun `a route is ordered and excludes the unordered`() {
        val book = WaypointBook()
            .withWaypoint(waypoint("third", collection = "run", order = 3))
            .withWaypoint(waypoint("first", collection = "run", order = 1))
            .withWaypoint(waypoint("loose", collection = "run"))

        assertEquals(listOf("first", "third"), book.route("run").map { it.id })
    }

    // -- merging -------------------------------------------------------------

    /**
     * What is already here wins.
     *
     * An import that overwrote existing entries would destroy somebody's local edits to make room for a copy
     * of them, and nothing brings those back.
     */
    @Test
    fun `merging keeps the existing entry on a clash`() {
        val mine = WaypointBook().withWaypoint(waypoint("spot").copy(label = "Mine"))
        val theirs = WaypointBook().withWaypoint(waypoint("spot").copy(label = "Theirs"))

        val merged = mine.merged(theirs)

        assertEquals(1, merged.waypoints.size)
        assertEquals("Mine", merged.waypoint("spot")?.label)
    }

    @Test
    fun `merging brings in what is new`() {
        val mine = WaypointBook().withWaypoint(waypoint("a"))
        val theirs = WaypointBook()
            .withCollection(WaypointCollection(id = "c", name = "Theirs"))
            .withWaypoint(waypoint("b", collection = "c"))

        val merged = mine.merged(theirs)

        assertEquals(setOf("a", "b"), merged.waypoints.map { it.id }.toSet())
        assertEquals(listOf("c"), merged.collections.map { it.id })
    }

    // -- sharing -------------------------------------------------------------

    /**
     * A note is private commentary about a place somebody is happy to share.
     *
     * "Sell here, the guy undercuts" attached to a shared bazaar waypoint should not go out with it. The
     * least surprising rule is that a note never leaves this client.
     */
    @Test
    fun `a shared copy carries no notes`() {
        val book = WaypointBook()
            .withWaypoint(waypoint("bazaar", WaypointAudience.Friends, note = "he undercuts"))

        val shared = book.shareableWith(friend, members, NOW)

        assertEquals(1, shared.waypoints.size)
        assertNull(shared.waypoints.single().note)
    }

    @Test
    fun `a shared copy carries only what the viewer may see`() {
        val book = WaypointBook()
            .withWaypoint(waypoint("open", WaypointAudience.Friends))
            .withWaypoint(waypoint("mine", WaypointAudience.Private))

        val shared = book.shareableWith(friend, members, NOW)

        assertEquals(listOf("open"), shared.waypoints.map { it.id })
    }

    /** Only the collections the shared waypoints actually use travel with them. */
    @Test
    fun `a shared copy carries no empty collections`() {
        val book = WaypointBook()
            .withCollection(WaypointCollection(id = "used", name = "Used"))
            .withCollection(WaypointCollection(id = "unused", name = "Unused"))
            .withWaypoint(waypoint("open", WaypointAudience.Friends, collection = "used"))

        val shared = book.shareableWith(friend, members, NOW)

        assertEquals(listOf("used"), shared.collections.map { it.id })
    }

    // -- pruning -------------------------------------------------------------

    @Test
    fun `pruning drops only what has expired`() {
        val book = WaypointBook()
            .withWaypoint(waypoint("gone", expiresAt = NOW - 1))
            .withWaypoint(waypoint("soon", expiresAt = NOW + 1000))
            .withWaypoint(waypoint("forever"))

        assertEquals(setOf("soon", "forever"), book.pruned(NOW).waypoints.map { it.id }.toSet())
    }

    // -- the audience itself -------------------------------------------------

    @Test
    fun `an audience knows whether it shares with anybody`() {
        assertFalse(WaypointAudience.Private.isShared)
        assertFalse(WaypointAudience.Selected(emptySet()).isShared, "nobody selected is nobody")
        assertTrue(WaypointAudience.Selected(setOf(friend)).isShared)
        assertTrue(WaypointAudience.Friends.isShared)
        assertTrue(WaypointAudience.Party.isShared)
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
