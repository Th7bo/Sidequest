package dev.th7bo.sidequest.platform.testkit

import dev.th7bo.sidequest.platform.lifecycle.Registration
import dev.th7bo.sidequest.platform.player.PlayerDirectory
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.player.PlayerIdentity
import dev.th7bo.sidequest.platform.player.PlayerPresence
import dev.th7bo.sidequest.platform.player.PresenceState
import dev.th7bo.sidequest.platform.skyblock.Activity
import dev.th7bo.sidequest.platform.skyblock.Island
import java.util.UUID

/**
 * A cast of players, with stable ids.
 *
 * **Derived from their names rather than random**, which is the only interesting thing here: a random UUID per
 * run makes a failure message unreproducible and makes two tests that both mean "the friend" disagree about
 * who that is. `UUID.nameUUIDFromBytes` gives the same id for the same name forever, so `FakePlayers.friend`
 * is one person across the whole suite and a logged id can be recognised.
 *
 * The names are the group's own, because a fixture that reads like the real thing catches things a fixture
 * full of `player1` does not — a parser that happens to work on short names, for instance.
 */
public object FakePlayers {

    /** The local player. */
    public val me: PlayerIdentity = identity("chrooted", isCustomFriend = false)

    public val friend: PlayerIdentity = identity(
        "Ashwood",
        isCustomFriend = true,
        presence = PlayerPresence(
            state = PresenceState.ONLINE,
            activity = Activity.DUNGEONS,
            island = Island.CATACOMBS,
        ),
    )

    public val partyMember: PlayerIdentity = identity(
        "Brambleton",
        isCustomFriend = true,
        presence = PlayerPresence(state = PresenceState.ONLINE, island = Island.HUB),
    )

    /** Somebody who is not in the group. For every "and this person should not see it" case. */
    public val stranger: PlayerIdentity = identity("PassingBy")

    /** Someone who has been seen before under another name, for the rename cases. */
    public val renamed: PlayerIdentity = identity("NewName").copy(usernameHistory = listOf("OldName"))

    /** Offline, so presence-dependent behaviour has something to be false about. */
    public val offline: PlayerIdentity = identity(
        "Sleeping",
        isCustomFriend = true,
        presence = PlayerPresence(state = PresenceState.OFFLINE),
    )

    public val everyone: List<PlayerIdentity> =
        listOf(me, friend, partyMember, stranger, renamed, offline)

    /** A stable id for any name, so a test can invent a player without adding one here. */
    public fun idOf(username: String): PlayerId =
        PlayerId.of(UUID.nameUUIDFromBytes("sidequest:$username".toByteArray()))

    public fun identity(
        username: String,
        isCustomFriend: Boolean = false,
        nickname: String? = null,
        presence: PlayerPresence = PlayerPresence.Unknown,
    ): PlayerIdentity = PlayerIdentity(
        id = idOf(username),
        username = username,
        nickname = nickname,
        isCustomFriend = isCustomFriend,
        presence = presence,
    )
}

/**
 * A directory holding whoever it is given.
 *
 * Deliberately simple-minded: it does not learn, merge or rename. Anything testing *that* behaviour should be
 * testing the real directory, and a fake that reimplemented it would pass while the real one was broken.
 */
public class FakePlayerDirectory(
    players: List<PlayerIdentity> = FakePlayers.everyone,
) : PlayerDirectory {

    private val byId = LinkedHashMap<PlayerId, PlayerIdentity>()

    init {
        for (player in players) byId[player.id] = player
    }

    override fun byId(id: PlayerId): PlayerIdentity? = byId[id]

    override fun resolveUsername(username: String): PlayerIdentity? =
        byId.values.firstOrNull { it.username.equals(username, ignoreCase = true) }
            ?: byId.values.firstOrNull { it.hasUsedName(username) }

    override fun all(): Collection<PlayerIdentity> = byId.values

    override fun customFriends(): Collection<PlayerIdentity> = byId.values.filter { it.isCustomFriend }

    override fun remember(id: PlayerId, username: String, skinTexture: String?): PlayerIdentity {
        val existing = byId[id]
        val updated = existing?.copy(username = username, skinTexture = skinTexture ?: existing.skinTexture)
            ?: PlayerIdentity(id, username, skinTexture = skinTexture)
        byId[id] = updated
        notify(updated)
        return updated
    }

    override fun setNickname(id: PlayerId, nickname: String?): PlayerIdentity? =
        update(id) { it.copy(nickname = nickname) }

    override fun updatePresence(id: PlayerId, presence: PlayerPresence): PlayerIdentity? =
        update(id) { it.copy(presence = presence) }

    override fun setCustomFriend(id: PlayerId, isFriend: Boolean): PlayerIdentity? =
        update(id) { it.copy(isCustomFriend = isFriend) }

    override fun onChange(listener: (PlayerIdentity) -> Unit): Registration {
        listeners.add(listener)
        return Registration { listeners.remove(listener) }
    }

    private val listeners = mutableListOf<(PlayerIdentity) -> Unit>()

    private fun update(id: PlayerId, transform: (PlayerIdentity) -> PlayerIdentity): PlayerIdentity? {
        val updated = byId[id]?.let(transform) ?: return null
        byId[id] = updated
        notify(updated)
        return updated
    }

    private fun notify(identity: PlayerIdentity) {
        // Copied before iterating: a listener that deregisters itself on the first change is a normal thing
        // to write and would otherwise be a concurrent modification.
        for (listener in listeners.toList()) listener(identity)
    }

    /** Adds or replaces somebody outright. For arranging a case rather than for exercising the directory. */
    public fun put(identity: PlayerIdentity) {
        byId[identity.id] = identity
    }
}
