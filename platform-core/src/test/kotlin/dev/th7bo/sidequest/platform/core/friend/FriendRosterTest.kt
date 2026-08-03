package dev.th7bo.sidequest.platform.core.friend

import dev.th7bo.sidequest.platform.player.PlayerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The friend list.
 *
 * Two things here have a cost when they are wrong and both get most of the attention: a friend lost to a
 * rename, and a lookup that answered confidently with the wrong person. The rest of the list is a list.
 */
class FriendRosterTest {

    private val alice = PlayerId("00000000-0000-0000-0000-00000000000a")
    private val bob = PlayerId("00000000-0000-0000-0000-00000000000b")
    private val carol = PlayerId("00000000-0000-0000-0000-00000000000c")

    private fun entry(id: PlayerId, name: String, nickname: String? = null, favourite: Boolean = false) =
        FriendEntry(id = id, username = name, nickname = nickname, isFavourite = favourite)

    private val roster = FriendRoster()
        .with(entry(alice, "Alice"))
        .with(entry(bob, "Bob"))

    // -- membership ----------------------------------------------------------

    @Test
    fun `somebody added is on the list`() {
        assertTrue(alice in roster)
        assertFalse(carol in roster)
        assertEquals(2, roster.size)
    }

    /** Adding twice is not two friends. The list behaves as a set even though it is drawn as a list. */
    @Test
    fun `adding somebody already there updates rather than duplicating`() {
        val again = roster.with(entry(alice, "Alice", nickname = "Al"))

        assertEquals(2, again.size)
        assertEquals("Al", again[alice]?.nickname)
    }

    @Test
    fun `removing somebody who was never there changes nothing`() {
        assertEquals(roster, roster.without(carol))
    }

    @Test
    fun `editing a stranger changes nothing`() {
        assertEquals(roster, roster.edit(carol) { it.copy(note = "hello") })
    }

    // -- renames -------------------------------------------------------------

    /**
     * A rename costs the name, not the friend.
     *
     * The whole reason the list is keyed on a UUID. A name can be changed and can be *taken over* by a
     * different account entirely, so a list keyed on names would quietly come to contain a stranger.
     */
    @Test
    fun `a friend who renames is still the same friend`() {
        val renamed = roster.seen(alice, "Alicia")

        assertTrue(alice in renamed)
        assertEquals(2, renamed.size)
        assertEquals("Alicia", renamed[alice]?.username)
    }

    /** Called for every player the client sees, so it has to be free for the thousands who are not friends. */
    @Test
    fun `seeing a stranger does not add them`() {
        val after = roster.seen(carol, "Carol")

        assertFalse(carol in after)
        assertEquals(roster, after)
    }

    @Test
    fun `a blank name never overwrites a known one`() {
        assertEquals("Alice", roster.seen(alice, "").let { it[alice]?.username })
    }

    // -- looking somebody up -------------------------------------------------

    @Test
    fun `a name finds its friend, whatever the case`() {
        assertEquals(alice, roster.find("alice")?.id)
        assertEquals(bob, roster.find("BOB")?.id)
    }

    @Test
    fun `a nickname finds them too, because that is what the list showed`() {
        val nicknamed = roster.with(entry(carol, "Carol", nickname = "Cee"))

        assertEquals(carol, nicknamed.find("Cee")?.id)
    }

    @Test
    fun `a uuid finds its friend`() {
        assertEquals(alice, roster.find(alice.value)?.id)
    }

    @Test
    fun `a unique prefix is enough`() {
        assertEquals(alice, roster.find("Ali")?.id)
    }

    /**
     * An ambiguous query is refused rather than guessed at.
     *
     * The one that earns the method its length. Removing the wrong friend, or writing a private note onto
     * the wrong person, is not a mistake the person typing would ever notice happening.
     */
    @Test
    fun `an ambiguous prefix answers nothing`() {
        val both = FriendRoster().with(entry(alice, "Thomas")).with(entry(bob, "Thorin"))

        assertNull(both.find("Th"))
        assertEquals(alice, both.find("Thomas")?.id) { "an exact name is not ambiguous" }
    }

    /** Two friends really called the same thing cannot be told apart, so neither is picked. */
    @Test
    fun `two friends with the same name answer nothing`() {
        val clash = FriendRoster().with(entry(alice, "Steve")).with(entry(bob, "Steve"))

        assertNull(clash.find("Steve"))
    }

    /** An exact name beats a prefix, so somebody called "Al" is not shadowed by "Alice". */
    @Test
    fun `an exact name wins over somebody else's prefix`() {
        val both = FriendRoster().with(entry(alice, "Alice")).with(entry(bob, "Al"))

        assertEquals(bob, both.find("Al")?.id)
    }

    @Test
    fun `nothing typed finds nothing`() {
        assertNull(roster.find(""))
        assertNull(roster.find("   "))
        assertNull(roster.find("nobody"))
    }

    // -- order ---------------------------------------------------------------

    @Test
    fun `favourites come first, then names, ignoring case`() {
        val list = FriendRoster()
            .with(entry(alice, "zoe"))
            .with(entry(bob, "Adam"))
            .with(entry(carol, "Yara", favourite = true))

        assertEquals(listOf("Yara", "Adam", "zoe"), list.sorted().map { it.displayName })
    }

    /** The name shown is the one chosen, and a friend never seen by name still has something to draw. */
    @Test
    fun `a display name always exists`() {
        assertEquals("Al", entry(alice, "Alice", nickname = "Al").displayName)
        assertEquals("Alice", entry(alice, "Alice").displayName)
        assertEquals(alice.value.take(8), FriendEntry(id = alice).displayName)
    }

    /** A blank nickname is not a name. Somebody clearing one should get their real name back. */
    @Test
    fun `a blank nickname falls through to the real name`() {
        assertEquals("Alice", entry(alice, "Alice", nickname = "  ").displayName)
    }
}
