package dev.th7bo.sidequest.platform.core.player

import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.player.PlayerFirstSeenEvent
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.player.PlayerPresence
import dev.th7bo.sidequest.platform.player.PlayerPresenceChangedEvent
import dev.th7bo.sidequest.platform.player.PlayerRenamedEvent
import dev.th7bo.sidequest.platform.player.PresenceState
import dev.th7bo.sidequest.platform.skyblock.Activity
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The player directory.
 *
 * The tests that matter here are about renames. Everything else is a map; the reason this class
 * exists at all is that Minecraft names change hands, and a mod that keys on one eventually
 * attributes somebody's debt to a stranger.
 */
class PlayerDirectoryTest {

    private lateinit var events: DefaultEventBus
    private lateinit var directory: DefaultPlayerDirectory

    private val owner = OwnerId(SqId.sidequest("test"))
    private var clock = 1_000L

    private val alice = PlayerId.of(UUID.fromString("11111111-1111-4111-8111-111111111111"))
    private val bob = PlayerId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"))

    @BeforeEach
    fun setUp() {
        events = DefaultEventBus(TestScheduler(), NoopLogger)
        directory = DefaultPlayerDirectory(events, now = { clock })
    }

    // ---------------------------------------------------------------
    // Remembering
    // ---------------------------------------------------------------

    @Test
    fun `a player seen for the first time is announced`() {
        val seen = mutableListOf<String>()
        events.on<PlayerFirstSeenEvent>(owner) { seen.add(it.player.username) }

        directory.remember(alice, "Alice")
        assertEquals(listOf("Alice"), seen)
        assertEquals("Alice", directory.byId(alice)?.username)
    }

    @Test
    fun `seeing the same player again is not a new player`() {
        var count = 0
        events.on<PlayerFirstSeenEvent>(owner) { count++ }

        directory.remember(alice, "Alice")
        directory.remember(alice, "Alice")
        directory.remember(alice, "Alice")
        assertEquals(1, count)
    }

    @Test
    fun `a name resolves to the player who used it`() {
        directory.remember(alice, "Alice")
        assertEquals(alice, directory.resolveUsername("Alice")?.id)
        assertEquals(alice, directory.resolveUsername("alice")?.id, "resolution ignores case")
        assertNull(directory.resolveUsername("Nobody"))
    }

    // ---------------------------------------------------------------
    // Renames — the reason this class exists
    // ---------------------------------------------------------------

    @Test
    fun `a rename keeps the identity and records the old name`() {
        directory.remember(alice, "Alice")
        val renames = mutableListOf<String>()
        events.on<PlayerRenamedEvent>(owner) { renames.add("${it.previousUsername} -> ${it.player.username}") }

        directory.remember(alice, "Alicia")

        assertEquals(listOf("Alice -> Alicia"), renames)
        assertEquals("Alicia", directory.byId(alice)?.username)
        assertEquals(listOf("Alice"), directory.byId(alice)?.usernameHistory)
    }

    /** A name written down a year ago should still find the person. */
    @Test
    fun `an old name still resolves after a rename`() {
        directory.remember(alice, "Alice")
        directory.remember(alice, "Alicia")

        assertEquals(alice, directory.resolveUsername("Alice")?.id)
        assertEquals(alice, directory.resolveUsername("Alicia")?.id)
        assertTrue(directory.byId(alice)!!.hasUsedName("Alice"))
    }

    /**
     * The failure this whole design exists to prevent.
     *
     * Minecraft names can be released and claimed by somebody else. Once Bob takes the name Alice
     * used to have, that name has to resolve to Bob — and anything that had stored the *name*
     * "Alice" is now pointing at the wrong person, while anything that stored the id is not.
     */
    @Test
    fun `a name taken over by another account resolves to whoever holds it now`() {
        directory.remember(alice, "Alice")
        directory.remember(alice, "Alicia")
        directory.remember(bob, "Alice")

        assertEquals(bob, directory.resolveUsername("Alice")?.id, "the current holder wins")
        assertEquals("Alicia", directory.byId(alice)?.username, "the original is untouched")
        assertEquals("Alice", directory.byId(bob)?.username)
    }

    /**
     * The history is a set of names ever used, so flipping between two does not grow it.
     *
     * Both names belong in it — either could be the one somebody wrote down — and each belongs once.
     * Without the check, four flips would be four entries and forty would be forty.
     */
    @Test
    fun `flipping between two names records each of them once`() {
        directory.remember(alice, "Alice")
        directory.remember(alice, "Alicia")
        directory.remember(alice, "Alice")
        directory.remember(alice, "Alicia")
        directory.remember(alice, "Alice")

        assertEquals(listOf("Alice", "Alicia"), directory.byId(alice)?.usernameHistory)
        assertEquals("Alice", directory.byId(alice)?.username)
        assertEquals(alice, directory.resolveUsername("Alicia")?.id)
    }

    @Test
    fun `a case-only change is not a rename`() {
        directory.remember(alice, "Alice")
        var renames = 0
        events.on<PlayerRenamedEvent>(owner) { renames++ }

        directory.remember(alice, "ALICE")
        assertEquals(0, renames)
        assertEquals(emptyList<String>(), directory.byId(alice)?.usernameHistory)
    }

    @Test
    fun `an undashed uuid parses`() {
        val parsed = PlayerId.parse("11111111111141118111111111111111")
        assertEquals(alice, parsed)
        assertNull(PlayerId.parse("not-a-uuid"))
    }

    // ---------------------------------------------------------------
    // Nicknames and presence
    // ---------------------------------------------------------------

    @Test
    fun `a nickname is what we call them, and is not used for resolution`() {
        directory.remember(alice, "Alice")
        directory.setNickname(alice, "The Farmer")

        assertEquals("The Farmer", directory.byId(alice)?.displayName)
        assertEquals("Alice", directory.byId(alice)?.username)
        assertNull(directory.resolveUsername("The Farmer"), "a nickname is ours, not an identity")
    }

    @Test
    fun `a blank nickname clears it`() {
        directory.remember(alice, "Alice")
        directory.setNickname(alice, "  ")
        assertNull(directory.byId(alice)?.nickname)
    }

    @Test
    fun `a presence change is announced`() {
        directory.remember(alice, "Alice")
        val changes = mutableListOf<String>()
        events.on<PlayerPresenceChangedEvent>(owner) { changes.add(it.describe()) }

        directory.updatePresence(alice, PlayerPresence(PresenceState.ONLINE, Activity.DUNGEONS))

        assertEquals(1, changes.size)
        assertTrue(directory.byId(alice)!!.presence.isOnline)
        assertEquals(Activity.DUNGEONS, directory.byId(alice)!!.presence.activity)
    }

    @Test
    fun `an unchanged presence is not announced`() {
        directory.remember(alice, "Alice")
        directory.updatePresence(alice, PlayerPresence(PresenceState.ONLINE))
        var changes = 0
        events.on<PlayerPresenceChangedEvent>(owner) { changes++ }

        directory.updatePresence(alice, PlayerPresence(PresenceState.ONLINE))
        assertEquals(0, changes)
    }

    /**
     * A disconnect forgets where people are, not who they are.
     *
     * Clearing identities would throw away the rename history, which is the thing that makes an old
     * name resolvable — and identities do not stop being true because we left a server.
     */
    @Test
    fun `a disconnect forgets presence and keeps identity`() {
        directory.remember(alice, "Alice")
        directory.remember(alice, "Alicia")
        directory.updatePresence(alice, PlayerPresence(PresenceState.ONLINE, Activity.MINING))

        directory.forgetPresence()

        assertEquals(PlayerPresence.Unknown, directory.byId(alice)?.presence)
        assertEquals("Alicia", directory.byId(alice)?.username)
        assertEquals(listOf("Alice"), directory.byId(alice)?.usernameHistory)
    }

    @Test
    fun `presence has no location until it is shared`() {
        directory.remember(alice, "Alice")
        directory.updatePresence(alice, PlayerPresence(PresenceState.ONLINE))
        val presence = directory.byId(alice)!!.presence
        assertNull(presence.location)
        assertFalse(presence.isLocationShared)
    }

    // ---------------------------------------------------------------
    // Friends and observers
    // ---------------------------------------------------------------

    @Test
    fun `the friend group is the players marked as friends`() {
        directory.remember(alice, "Alice")
        directory.remember(bob, "Bob")
        directory.setCustomFriend(alice, true)

        assertEquals(listOf("Alice"), directory.customFriends().map { it.username })
        assertEquals(2, directory.all().size)
    }

    @Test
    fun `observers see every change`() {
        val seen = mutableListOf<String>()
        val registration = directory.onChange { seen.add(it.username) }

        directory.remember(alice, "Alice")
        directory.setNickname(alice, "A")
        registration.cancel()
        directory.remember(bob, "Bob")

        assertEquals(listOf("Alice", "Alice"), seen)
    }

    /** Same isolation as the event bus: one broken observer must not stop the directory working. */
    @Test
    fun `an observer that throws does not stop the others`() {
        val seen = mutableListOf<String>()
        directory.onChange { error("badly behaved") }
        directory.onChange { seen.add(it.username) }

        directory.remember(alice, "Alice")

        assertEquals(listOf("Alice"), seen)
        assertNotNull(directory.byId(alice))
    }
}
