package dev.th7bo.sidequest.platform.core.player

import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.lifecycle.Registration
import dev.th7bo.sidequest.platform.player.PlayerDirectory
import dev.th7bo.sidequest.platform.player.PlayerFirstSeenEvent
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.player.PlayerIdentity
import dev.th7bo.sidequest.platform.player.PlayerPresence
import dev.th7bo.sidequest.platform.player.PlayerPresenceChangedEvent
import dev.th7bo.sidequest.platform.player.PlayerRenamedEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Remembers who is who.
 *
 * **Keyed on [PlayerId] and nothing else.** The name index exists, and it is a *cache for
 * resolution*, not a store: every identity lives in one map under its UUID, and the name index
 * points into it. That is the difference between "look up by name" and "key on name", and it is
 * the difference between a debt that survives a rename and one that follows the name to whoever
 * claims it next.
 *
 * Names are indexed lowercase and historical names are kept in the index too, so a name written
 * down a year ago still finds the person. Where a name has been *taken over* — released by one
 * account and claimed by another — the current holder wins, which is right for "invite this
 * person" and is exactly why nothing durable may store a name.
 *
 * Thread-safe: the tab list is read on the client thread and the backend will write from a
 * coroutine, so the maps are concurrent rather than the callers being careful.
 */
public class DefaultPlayerDirectory(
    private val events: EventBus,
    private val now: () -> Long = System::currentTimeMillis,
) : PlayerDirectory {

    private val byId = ConcurrentHashMap<PlayerId, PlayerIdentity>()

    /**
     * Lowercase name to id, current and historical.
     *
     * A cache, and rebuildable from [byId] alone — which is the property that makes it safe. If it
     * ever disagreed with the identities, the identities would be right.
     */
    private val byName = ConcurrentHashMap<String, PlayerId>()

    private val listeners = CopyOnWriteArrayList<(PlayerIdentity) -> Unit>()

    override fun byId(id: PlayerId): PlayerIdentity? = byId[id]

    override fun resolveUsername(username: String): PlayerIdentity? {
        val id = byName[username.lowercase()] ?: return null
        return byId[id]
    }

    override fun all(): Collection<PlayerIdentity> = byId.values

    override fun customFriends(): Collection<PlayerIdentity> = byId.values.filter { it.isCustomFriend }

    override fun remember(id: PlayerId, username: String, skinTexture: String?): PlayerIdentity {
        val existing = byId[id]
        val timestamp = now()

        if (existing == null) {
            val created = PlayerIdentity(
                id = id,
                username = username,
                skinTexture = skinTexture,
                lastSeenMillis = timestamp,
            )
            byId[id] = created
            byName[username.lowercase()] = id
            announce(created)
            events.post(PlayerFirstSeenEvent(created), EventSource.DERIVED)
            return created
        }

        val isRename = !existing.username.equals(username, ignoreCase = true)
        val updated = existing.copy(
            username = username,
            // The old name goes into history rather than being dropped, and only once: a player
            // flipping between two names must not grow the list forever.
            usernameHistory = if (isRename && existing.username !in existing.usernameHistory) {
                existing.usernameHistory + existing.username
            } else {
                existing.usernameHistory
            },
            skinTexture = skinTexture ?: existing.skinTexture,
            lastSeenMillis = timestamp,
        )
        if (updated == existing) return existing

        byId[id] = updated
        // Both names stay indexed. The new one is claimed unconditionally — a name points at
        // whoever holds it now — and the old one is left pointing here so a stale reference still
        // resolves to somebody.
        byName[username.lowercase()] = id
        byName.putIfAbsent(existing.username.lowercase(), id)

        announce(updated)
        if (isRename) events.post(PlayerRenamedEvent(existing.username, updated), EventSource.DERIVED)
        return updated
    }

    override fun setNickname(id: PlayerId, nickname: String?): PlayerIdentity? =
        update(id) { it.copy(nickname = nickname?.takeIf(String::isNotBlank)) }

    override fun updatePresence(id: PlayerId, presence: PlayerPresence): PlayerIdentity? {
        val existing = byId[id] ?: return null
        if (existing.presence == presence) return existing
        val updated = existing.copy(presence = presence)
        byId[id] = updated
        announce(updated)
        events.post(PlayerPresenceChangedEvent(existing.presence, updated), EventSource.DERIVED)
        return updated
    }

    override fun setCustomFriend(id: PlayerId, isFriend: Boolean): PlayerIdentity? =
        update(id) { it.copy(isCustomFriend = isFriend) }

    override fun onChange(listener: (PlayerIdentity) -> Unit): Registration {
        listeners.add(listener)
        return Registration { listeners.remove(listener) }
    }

    /**
     * Forgets where everybody is, without forgetting who they are.
     *
     * What a disconnect means. Identities are worth keeping — a name and a UUID do not stop being
     * true because we left the server, and clearing them would throw away the rename history that
     * makes a year-old name resolvable. Presence is worth nothing five minutes later, and a stale
     * "online" has features messaging people who are not there.
     */
    public fun forgetPresence() {
        for ((id, identity) in byId) {
            if (identity.presence == PlayerPresence.Unknown) continue
            updatePresence(id, PlayerPresence.Unknown)
        }
    }

    /** Clears everything. For a test, or a profile switch. */
    public fun clear() {
        byId.clear()
        byName.clear()
    }

    private inline fun update(id: PlayerId, transform: (PlayerIdentity) -> PlayerIdentity): PlayerIdentity? {
        val existing = byId[id] ?: return null
        val updated = transform(existing)
        if (updated == existing) return existing
        byId[id] = updated
        announce(updated)
        return updated
    }

    /**
     * Tells the observers, without letting one of them break the others.
     *
     * Same isolation as the event bus and for the same reason: a screen listening for changes must
     * not be able to stop the directory recording who is present.
     */
    private fun announce(identity: PlayerIdentity) {
        for (listener in listeners) {
            runCatching { listener(identity) }
        }
    }
}
