package dev.th7bo.sidequest.feature.ui

import dev.th7bo.sidequest.platform.lifecycle.Registration
import dev.th7bo.sidequest.platform.player.PlayerDirectory
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.player.PlayerIdentity
import dev.th7bo.sidequest.platform.player.PlayerPresence
import dev.th7bo.sidequest.platform.player.PresenceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Presence, as something a screen can watch.
 *
 * Small, and every test here is about one of the two ways this could quietly not work: handing back a
 * different state each time, so nothing ever observes a change; or growing a state per stranger, so a busy
 * lobby leaks a map.
 */
class PresenceStatesTest {

    private val alice = PlayerId("00000000-0000-0000-0000-00000000000a")
    private val bob = PlayerId("00000000-0000-0000-0000-00000000000b")

    private var known = mapOf<PlayerId, PlayerIdentity>()

    private val directory = object : PlayerDirectory {
        override fun byId(id: PlayerId) = known[id]
        override fun resolveUsername(username: String) = null
        override fun all() = known.values
        override fun customFriends() = emptyList<PlayerIdentity>()
        override fun remember(id: PlayerId, username: String, skinTexture: String?) = error("unused")
        override fun setNickname(id: PlayerId, nickname: String?) = null
        override fun updatePresence(id: PlayerId, presence: PlayerPresence) = null
        override fun setCustomFriend(id: PlayerId, isFriend: Boolean) = null
        override fun onChange(listener: (PlayerIdentity) -> Unit) = Registration { }
    }

    private val states = PresenceStates(directory)

    private fun online() = PlayerPresence(state = PresenceState.ONLINE)

    /**
     * The same object every time.
     *
     * The failure this prevents is silent: a screen observing a state that nothing later writes to shows
     * its first value forever, and looks exactly like a screen whose data has not changed.
     */
    @Test
    fun `asking twice gives the same state`() {
        assertSame(states.of(alice), states.of(alice))
    }

    @Test
    fun `a change reaches whoever is watching`() {
        val watched = states.of(alice)
        assertFalse(watched.value.isOnline)

        states.onChanged(alice, online())

        assertTrue(watched.value.isOnline)
    }

    /** Seeded from the directory, so the first read is right rather than briefly claiming offline. */
    @Test
    fun `a state starts from what the directory already knows`() {
        known = mapOf(alice to PlayerIdentity(id = alice, username = "Alice", presence = online()))

        assertTrue(states.of(alice).value.isOnline)
    }

    @Test
    fun `somebody the directory has never seen starts unknown`() {
        assertEquals(PlayerPresence.Unknown, states.of(bob).value)
    }

    /**
     * Nothing is created for somebody nobody is watching.
     *
     * Presence lands for every player the client sees, and a state per stranger would be a map that only
     * grows — in a busy lobby, quickly.
     */
    @Test
    fun `a change for an unwatched player creates nothing`() {
        states.onChanged(bob, online())

        assertEquals(0, states.watched)
    }

    @Test
    fun `only what has been asked for is held`() {
        states.of(alice)
        states.of(alice)

        assertEquals(1, states.watched)
    }

    @Test
    fun `clearing drops everything`() {
        states.of(alice)
        states.clear()

        assertEquals(0, states.watched)
    }
}
