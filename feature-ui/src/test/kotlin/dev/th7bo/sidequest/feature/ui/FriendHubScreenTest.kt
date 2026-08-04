package dev.th7bo.sidequest.feature.ui

import dev.th7bo.sidequest.platform.core.friend.FriendEntry
import dev.th7bo.sidequest.platform.core.friend.FriendRoster
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.player.PlayerIdentity
import dev.th7bo.sidequest.platform.player.PlayerPresence
import dev.th7bo.sidequest.platform.player.PresenceState
import dev.th7bo.sidequest.platform.skyblock.Activity
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.ui.config.ConfigScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Building the friend hub.
 *
 * The waypoint manager shipped three bugs a running client had to find — a duplicate id, an id the grammar
 * rejected, and every field frozen by a getter reading a snapshot. This screen is built the same way, so it
 * can make all three of the same mistakes, and these are the tests that stop it.
 */
class FriendHubScreenTest {

    private val alice = PlayerId("00000000-0000-0000-0000-00000000000a")
    private val bob = PlayerId("00000000-0000-0000-0000-00000000000b")
    private val carol = PlayerId("00000000-0000-0000-0000-00000000000c")

    private var roster = FriendRoster()

    /** Who the directory says is around. The screen reads through this, never from the roster. */
    private var identities = mapOf<PlayerId, PlayerIdentity>()

    private val presence = PresenceStates(
        object : dev.th7bo.sidequest.platform.player.PlayerDirectory {
            override fun byId(id: PlayerId) = identities[id]
            override fun resolveUsername(username: String) = null
            override fun all() = identities.values
            override fun customFriends() = emptyList<PlayerIdentity>()
            override fun remember(id: PlayerId, username: String, skinTexture: String?) = error("unused")
            override fun setNickname(id: PlayerId, nickname: String?) = null
            override fun updatePresence(id: PlayerId, presence: PlayerPresence) = null
            override fun setCustomFriend(id: PlayerId, isFriend: Boolean) = null
            override fun onChange(listener: (PlayerIdentity) -> Unit) =
                dev.th7bo.sidequest.platform.lifecycle.Registration { }
        },
    )

    private val actions = FriendActions(
        current = { roster },
        identity = { identities[it] },
        presenceOf = presence::of,
        edit = { id, change -> roster = roster.edit(id, change) },
        remove = { id -> roster = roster.without(id) },
        reopen = { },
    )

    private fun friend(id: PlayerId, name: String, nickname: String? = null, favourite: Boolean = false) =
        FriendEntry(id = id, username = name, nickname = nickname, isFavourite = favourite)

    private fun online(
        id: PlayerId,
        name: String,
        activity: Activity = Activity.UNKNOWN,
        island: Island = Island.NONE,
    ) {
        val now = PlayerPresence(state = PresenceState.ONLINE, activity = activity, island = island)
        identities = identities + (id to PlayerIdentity(id = id, username = name, presence = now))
        presence.onChanged(id, now)
    }

    /** Somebody going offline, announced the way the mod announces it. */
    private fun offline(id: PlayerId) {
        identities = identities - id
        presence.onChanged(id, PlayerPresence(state = PresenceState.OFFLINE))
    }

    private fun build(): ConfigScreen = buildFriendHubScreen(roster, actions)

    private fun sections() = build().categories.flatMap { it.sections }

    // -- the ids -------------------------------------------------------------

    /**
     * Two friends showing the same name.
     *
     * A section's id derives from its title unless one is given, and a duplicate id *throws* — taking the
     * whole screen with it. Two people nicknaming themselves the same thing is not a mistake anybody needs
     * protecting from.
     */
    @Test
    fun `two friends with the same display name both get a section`() {
        roster = roster
            .with(friend(alice, "Alice", nickname = "Ace"))
            .with(friend(bob, "Bob", nickname = "Ace"))

        val sections = sections()

        assertEquals(2, sections.count { it.title.peek() == "Ace" })
        assertEquals(sections.size, sections.map { it.id }.distinct().size, "every section needs its own id")
    }

    /** Every id on the screen has to be unique — the builder rejects duplicates by throwing. */
    @Test
    fun `a busy list produces no duplicate ids at all`() {
        repeat(8) { index ->
            roster = roster.with(
                friend(
                    PlayerId("00000000-0000-0000-0000-00000000000$index"),
                    "Same",
                    favourite = index % 2 == 0,
                ),
            )
        }

        val screen = build()

        val everyId = screen.categories.map { it.id } +
            screen.categories.flatMap { category -> category.sections.map { it.id } } +
            screen.settings.map { it.id }
        assertEquals(
            everyId.size,
            everyId.distinct().size,
            "duplicates: ${everyId.groupBy { it }.filter { it.value.size > 1 }.keys}",
        )
    }

    // -- the bindings --------------------------------------------------------

    /**
     * Typing writes through, and reading back shows what was typed.
     *
     * The frozen-field bug, in the shape it took last time: `MirrorBinding.set` re-reads after writing, so a
     * getter closed over a stale snapshot silently undoes every edit. The field looks dead while in fact
     * working and instantly reverting.
     */
    @Test
    fun `editing a nickname sticks`() {
        roster = roster.with(friend(alice, "Alice"))
        val screen = build()

        val field = screen.settings.first { it.id.value.endsWith(".nickname") }
        @Suppress("UNCHECKED_CAST")
        (field.binding as dev.th7bo.sidequest.ui.binding.Binding<String>).set("Ace")

        assertEquals("Ace", roster[alice]?.nickname, "the roster took it")
        assertEquals("Ace", field.binding.state.peek(), "and the control shows it")
    }

    @Test
    fun `editing a note sticks`() {
        roster = roster.with(friend(alice, "Alice"))
        val screen = build()

        val field = screen.settings.first { it.id.value.endsWith(".note") }
        @Suppress("UNCHECKED_CAST")
        (field.binding as dev.th7bo.sidequest.ui.binding.Binding<String>).set("owes me")

        assertEquals("owes me", roster[alice]?.note)
    }

    /** Clearing a field clears the value rather than storing an empty one that would draw as a blank name. */
    @Test
    fun `clearing a nickname removes it`() {
        roster = roster.with(friend(alice, "Alice", nickname = "Ace"))
        val screen = build()

        val field = screen.settings.first { it.id.value.endsWith(".nickname") }
        @Suppress("UNCHECKED_CAST")
        (field.binding as dev.th7bo.sidequest.ui.binding.Binding<String>).set("")

        assertNull(roster[alice]?.nickname)
    }

    @Test
    fun `toggling a favourite sticks`() {
        roster = roster.with(friend(alice, "Alice"))
        val screen = build()

        val toggle = screen.settings.first { it.id.value.endsWith(".favourite") }
        @Suppress("UNCHECKED_CAST")
        (toggle.binding as dev.th7bo.sidequest.ui.binding.Binding<Boolean>).set(true)

        assertTrue(roster[alice]!!.isFavourite)
    }

    // -- what it says --------------------------------------------------------

    /**
     * A friend who logs off stops being described as online, without reopening.
     *
     * This test used to assert the opposite, and said so: the description was frozen at build time and the
     * honest thing was to pin that rather than claim otherwise. It notes that if presence ever became
     * reactive state this is the test that should start failing. It did, and this is the other side of it.
     */
    @Test
    fun `a friend who logs off stops being described as online`() {
        roster = roster.with(friend(alice, "Alice"))
        online(alice, "Alice")

        val section = build().categories.first().sections.first { it.title.peek() == "Alice" }
        assertTrue(section.description?.peek()?.contains("Online") == true)

        offline(alice)

        assertTrue(
            section.description?.peek()?.contains("Offline") == true,
            "still said ${section.description?.peek()} after they logged off",
        )
    }

    /** What somebody shares is shown; what they do not share is simply absent. */
    @Test
    fun `shared activity and island are shown`() {
        roster = roster.with(friend(alice, "Alice"))
        online(alice, "Alice", activity = Activity.DUNGEONS, island = Island.CATACOMBS)

        val description = build().categories.first().sections
            .first { it.title.peek() == "Alice" }.description?.peek().orEmpty()

        assertTrue(description.contains(Activity.DUNGEONS.displayName), description)
        assertTrue(description.contains(Island.CATACOMBS.displayName), description)
    }

    /**
     * Nothing is invented to fill a gap.
     *
     * Somebody sharing that they are online but not what they are doing must not have "Unknown" drawn next
     * to their name — the screen has nothing to say there, so it says nothing.
     */
    @Test
    fun `an unshared activity is left out rather than guessed at`() {
        roster = roster.with(friend(alice, "Alice"))
        online(alice, "Alice")

        val description = build().categories.first().sections
            .first { it.title.peek() == "Alice" }.description?.peek().orEmpty()

        assertFalse(description.contains("Unknown"), description)
        assertEquals("Online", description)
    }

    /** A nickname covers the real name, so the real name goes in the line underneath. */
    @Test
    fun `a nicknamed friend still shows the name on their nametag`() {
        roster = roster.with(friend(alice, "Alice", nickname = "Ace"))

        val section = build().categories.first().sections.first { it.title.peek() == "Ace" }

        assertTrue(section.description?.peek()?.contains("Alice") == true, section.description?.peek())
    }

    // -- the shape -----------------------------------------------------------

    @Test
    fun `an empty list still builds and says so`() {
        val screen = build()

        assertTrue(screen.categories.first().sections.isNotEmpty(), "it should say there is nobody yet")
    }

    /** Online first, because that is the question somebody opening a friend list is asking. */
    @Test
    fun `online friends are listed first`() {
        roster = roster
            .with(friend(alice, "Alice"))
            .with(friend(bob, "Bob"))
            .with(friend(carol, "Carol"))
        online(carol, "Carol")

        val titles = build().categories.first().sections.map { it.title.peek() }

        assertEquals("Carol", titles.first())
    }

    /** A favourite outranks a name, but not somebody who is actually online. */
    @Test
    fun `being online outranks being a favourite`() {
        roster = roster
            .with(friend(alice, "Alice", favourite = true))
            .with(friend(bob, "Bob"))
        online(bob, "Bob")

        val titles = build().categories.first().sections.map { it.title.peek() }

        assertEquals(listOf("Bob", "Alice"), titles)
    }

    @Test
    fun `favourites get their own category`() {
        roster = roster.with(friend(alice, "Alice", favourite = true)).with(friend(bob, "Bob"))

        val screen = build()

        val favourites = screen.categories.first { it.title.peek() == "Favourites" }
        assertEquals(listOf("Alice"), favourites.sections.map { it.title.peek() })
    }

    /** No favourites means no empty category, rather than a heading over nothing. */
    @Test
    fun `no favourites means no favourites category`() {
        roster = roster.with(friend(alice, "Alice"))

        assertTrue(build().categories.none { it.title.peek() == "Favourites" })
    }
}
