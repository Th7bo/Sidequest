package dev.th7bo.sidequest.platform.player

import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.lifecycle.Registration
import dev.th7bo.sidequest.platform.skyblock.Activity
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A player, canonically.
 *
 * **Never key anything on a username.** That is the one rule this type exists to enforce, and it
 * is enforced structurally: nothing in the directory is stored under a name, and the only way to
 * get from a name to a player is [PlayerDirectory.resolveUsername], which is documented as
 * best-effort and can return the wrong answer.
 *
 * The reason is not theoretical. Minecraft names are free to change and free to be *taken over*:
 * a name released by one account can be claimed by another, so a debt recorded against "Notch"
 * can end up owed by a stranger. A UUID cannot be taken over.
 */
@Serializable
@JvmInline
public value class PlayerId(public val value: String) {

    public fun toUuid(): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    override fun toString(): String = value

    public companion object {
        public fun of(uuid: UUID): PlayerId = PlayerId(uuid.toString())

        /** Parses a UUID string, dashed or not. Null when it is not one. */
        public fun parse(value: String): PlayerId? {
            val text = value.trim()
            if (text.length == UNDASHED_LENGTH && '-' !in text) {
                val dashed = StringBuilder(text)
                    .insert(8, '-').insert(13, '-').insert(18, '-').insert(23, '-')
                    .toString()
                return runCatching { PlayerId.of(UUID.fromString(dashed)) }.getOrNull()
            }
            return runCatching { PlayerId.of(UUID.fromString(text)) }.getOrNull()
        }

        private const val UNDASHED_LENGTH = 32
    }
}

/**
 * Everything known about one player.
 *
 * Assembled from several sources with different reliability, which is why so much of it is
 * nullable. The client knows names and UUIDs of players it can see; the backend will know
 * friendship, cosmetics and history; nothing knows all of it at once. A field that has never been
 * filled is null rather than a default, so "we do not know" and "there is none" stay different
 * answers.
 */
@Serializable
public data class PlayerIdentity(
    public val id: PlayerId,
    /**
     * The name last seen. **Display only.**
     *
     * Correct at the moment it was recorded and not before or after. Compare [id].
     */
    public val username: String,
    /**
     * Names this player has been seen under, oldest first, current excluded.
     *
     * Kept so a stale name still resolves: somebody who wrote down a name a year ago should still
     * be able to find the person. Recorded from observation — the client sees a rename when the
     * same UUID turns up with a different name — rather than fetched, because there is nowhere to
     * fetch it from yet.
     */
    public val usernameHistory: List<String> = emptyList(),
    /** A name we chose for them locally. Never sent anywhere, never used for resolution. */
    public val nickname: String? = null,
    /** The base64 skin value, when it has been seen. Same form as an item's head texture. */
    public val skinTexture: String? = null,
    /** Whether they are in the friend group. Set by the friend service, once it exists. */
    public val isCustomFriend: Boolean = false,
    public val presence: PlayerPresence = PlayerPresence.Unknown,
    /** Wall clock of the last time this player was seen, epoch milliseconds. */
    public val lastSeenMillis: Long? = null,
) {

    /** What to call them: the nickname if we set one, otherwise the name they use. */
    public val displayName: String get() = nickname ?: username

    /** Whether [name] is a name this player has ever been seen under. */
    public fun hasUsedName(name: String): Boolean =
        username.equals(name, ignoreCase = true) || usernameHistory.any { it.equals(name, ignoreCase = true) }
}

/**
 * Whether a player is around, and what they are up to.
 *
 * Separate from [PlayerIdentity] because it changes on a different timescale: an identity is worth
 * persisting and a presence is worth nothing five minutes later.
 *
 * [isLocationShared] is the permission gate the plan asks for. Location is the one piece of this
 * that a player has to opt into sharing, so it is not a field on the location — it is the reason
 * the location field is there at all or is null.
 */
@Serializable
public data class PlayerPresence(
    public val state: PresenceState = PresenceState.UNKNOWN,
    public val activity: Activity = Activity.UNKNOWN,
    public val island: Island = Island.NONE,
    /**
     * Where they are, when they have chosen to share it.
     *
     * Null both when they have not shared and when nothing has been reported. The two are not
     * distinguished on purpose: a feature that could tell "sharing off" from "no data" could tell
     * the player something they did not agree to reveal.
     */
    public val location: SqLocation? = null,
    public val isLocationShared: Boolean = false,
) {
    public val isOnline: Boolean get() = state == PresenceState.ONLINE

    public companion object {
        public val Unknown: PlayerPresence = PlayerPresence()
    }
}

@Serializable
public enum class PresenceState {
    ONLINE,
    OFFLINE,

    /** Nothing has said. Distinct from offline, and treated differently by everything social. */
    UNKNOWN,
}

/**
 * The one place players are looked up.
 *
 * Reads are cheap; nothing here touches the network. What it holds is what the client has seen
 * plus whatever the backend has told it, and the two are merged behind one interface so a feature
 * does not care which knew.
 */
public interface PlayerDirectory {

    /** Everything known about a player, or null when the id has never been seen. */
    public fun byId(id: PlayerId): PlayerIdentity?

    /**
     * Best-effort resolution of a name to a player.
     *
     * **Can be wrong**, and is documented so at every call site that matters. A name resolves
     * through the current names first and the historical ones second, so a name that has been
     * taken over resolves to whoever holds it now — which is right for "invite this person" and
     * wrong for "this person owes me money". Anything durable stores the [PlayerId].
     *
     * Null when the name has never been seen, which is common: the client only knows players it
     * has been in a lobby with.
     */
    public fun resolveUsername(username: String): PlayerIdentity?

    /** Every player the directory knows, in no particular order. */
    public fun all(): Collection<PlayerIdentity>

    /** The friend group. Empty until the friend service fills it. */
    public fun customFriends(): Collection<PlayerIdentity>

    /**
     * Records that a player exists and what they were called.
     *
     * The main way the directory learns anything: the tab list, a chat message, a nearby entity.
     * Repeated calls are cheap and idempotent; a call with a different name than last time is a
     * rename and is recorded as one.
     */
    public fun remember(id: PlayerId, username: String, skinTexture: String? = null): PlayerIdentity

    /** Sets or clears a local nickname. */
    public fun setNickname(id: PlayerId, nickname: String?): PlayerIdentity?

    /** Replaces a player's presence. */
    public fun updatePresence(id: PlayerId, presence: PlayerPresence): PlayerIdentity?

    /** Marks friend-group membership. Called by the friend service. */
    public fun setCustomFriend(id: PlayerId, isFriend: Boolean): PlayerIdentity?

    /** Observes every change. Cheaper than polling [all] for a screen that lists players. */
    public fun onChange(listener: (PlayerIdentity) -> Unit): Registration
}

// -- events ---------------------------------------------------------------

/** Base for what the directory announces. */
public sealed class PlayerEvent : SidequestEvent() {
    public abstract val player: PlayerIdentity
}

/** A player the directory had never seen before. */
public class PlayerFirstSeenEvent(override val player: PlayerIdentity) : PlayerEvent() {
    override fun describe(): String = "first saw ${player.username}"
}

/**
 * A player turned up under a different name.
 *
 * Worth an event of its own: anything showing a stored name wants to update it, and anything that
 * keyed on a name — which it should not have — finds out here that it was wrong to.
 */
public class PlayerRenamedEvent(
    public val previousUsername: String,
    override val player: PlayerIdentity,
) : PlayerEvent() {
    override fun describe(): String = "$previousUsername is now ${player.username}"
}

/** A player's presence changed. */
public class PlayerPresenceChangedEvent(
    public val previous: PlayerPresence,
    override val player: PlayerIdentity,
) : PlayerEvent() {
    override fun describe(): String = "${player.username}: ${previous.state} -> ${player.presence.state}"
}
