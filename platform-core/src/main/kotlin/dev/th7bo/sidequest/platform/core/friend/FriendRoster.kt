package dev.th7bo.sidequest.platform.core.friend

import dev.th7bo.sidequest.platform.player.PlayerId
import kotlinx.serialization.Serializable

/**
 * One person on the friend list.
 *
 * **Almost nothing lives here.** Online status, activity, island, title, cosmetics, debts — the plan lists
 * all of them under the friend system, and none of them is a field on this type. They belong to the player
 * directory and the backend, which already know them and already keep them current; copying them in would
 * make this file a second answer to "is Bob online" that goes stale the moment it is written and is then
 * persisted in that state. What is stored is only what nothing else remembers: that this person is a friend
 * at all, and the couple of things the owner typed about them.
 *
 * [username] is the exception, and it is a *cache* rather than a fact. It is what they were called when last
 * seen, kept so a list can be drawn before anybody is online to ask. It is never matched against, never sent
 * anywhere, and refreshed whenever they turn up.
 */
@Serializable
public data class FriendEntry(
    /**
     * The key, and the only durable one.
     *
     * A name can be changed and, worse, *taken over* — released by one account and claimed by another — so a
     * friend list keyed on names would eventually contain a stranger. `PlayerIdentity` makes the same point
     * at greater length; this type is one of the places it has to hold.
     */
    public val id: PlayerId,
    /** What they were last called. Display only. See the class comment. */
    public val username: String = "",
    /** A name the owner chose for them. Local, and never sent anywhere. */
    public val nickname: String? = null,
    /** Whatever the owner wants to remember about them. Private, like a waypoint's note. */
    public val note: String? = null,
    public val addedAtMillis: Long = 0,
    /** Sorted to the top of the list. The friend list of somebody with forty friends and four they play with. */
    public val isFavourite: Boolean = false,
) {

    /** What to call them: the chosen name, then the seen one, then their id. */
    public val displayName: String
        get() = nickname?.takeIf { it.isNotBlank() }
            ?: username.takeIf { it.isNotBlank() }
            ?: id.value.take(SHORT_ID)

    private companion object {
        /** Enough of a UUID to tell two apart, for the case where a friend has never been seen by name. */
        const val SHORT_ID = 8
    }
}

/**
 * The friend list.
 *
 * Immutable and replaced wholesale, for the same reason [WaypointBook][dev.th7bo.sidequest.platform.core.waypoint.WaypointBook]
 * is: this is the thing being persisted, and a mutable one would let a caller hand a half-edited list to a
 * save. It is a friend group, so it is tens of entries and the copying is free.
 *
 * Membership is a set in behaviour if not in type — [with] replaces by id — so adding somebody twice cannot
 * produce two of them. A list rather than a map because the order is shown to a person and a map's is not
 * anybody's.
 */
@Serializable
public data class FriendRoster(
    public val friends: List<FriendEntry> = emptyList(),
) {

    public val size: Int get() = friends.size

    public operator fun contains(id: PlayerId): Boolean = friends.any { it.id == id }

    public operator fun get(id: PlayerId): FriendEntry? = friends.firstOrNull { it.id == id }

    /** Adds or replaces, keyed by id. Adding somebody already here updates them rather than duplicating. */
    public fun with(entry: FriendEntry): FriendRoster =
        copy(friends = friends.filterNot { it.id == entry.id } + entry)

    public fun without(id: PlayerId): FriendRoster = copy(friends = friends.filterNot { it.id == id })

    /**
     * Applies a change to one friend, if they are on the list.
     *
     * Takes a function rather than a replacement so a caller never holds a whole entry: it edits a field of
     * whatever is current, which is the difference between "set the note" and "put back the friend I was
     * looking at five minutes ago".
     */
    public fun edit(id: PlayerId, change: (FriendEntry) -> FriendEntry): FriendRoster {
        val existing = get(id) ?: return this
        return with(change(existing))
    }

    /**
     * Records the name somebody is currently using.
     *
     * The only thing that keeps the cached names honest, and it is why a rename cannot cost somebody a
     * friend: the entry is found by id and only its display name moves. Does nothing for a stranger, so it
     * is safe to call for every player the client sees.
     */
    public fun seen(id: PlayerId, username: String): FriendRoster {
        val existing = get(id) ?: return this
        if (existing.username == username || username.isBlank()) return this
        return with(existing.copy(username = username))
    }

    /**
     * In the order a person would want to read them: favourites first, then by name.
     *
     * Case-insensitively, because a list sorted by ASCII puts every capitalised name above every lowercase
     * one and reads as unsorted to the person looking at it.
     */
    public fun sorted(): List<FriendEntry> =
        friends.sortedWith(compareByDescending<FriendEntry> { it.isFavourite }.thenBy { it.displayName.lowercase() })

    /**
     * Finds a friend by something somebody typed.
     *
     * Tries a UUID, then an exact name, then a unique prefix — in that order, so an exact name always beats
     * somebody else's prefix. **Ambiguity is refused rather than guessed at**: two friends whose names both
     * start with "th" produce null, because removing the wrong friend or writing a note onto the wrong
     * person is not something the person typing would notice.
     *
     * Matches nicknames as well as names, since the nickname is what the list actually showed them.
     */
    public fun find(query: String): FriendEntry? {
        val text = query.trim().trim('"')
        if (text.isEmpty()) return null

        PlayerId.parse(text)?.let { id -> get(id)?.let { return it } }

        val exact = friends.filter {
            it.username.equals(text, ignoreCase = true) || it.nickname.equals(text, ignoreCase = true)
        }
        if (exact.size == 1) return exact.single()
        // Two people with the same name is not a lookup that can be answered. Falling through to prefixes
        // would answer it with whichever of them happened to be stored first.
        if (exact.size > 1) return null

        val prefixed = friends.filter {
            it.username.startsWith(text, ignoreCase = true) ||
                it.nickname?.startsWith(text, ignoreCase = true) == true
        }
        return prefixed.singleOrNull()
    }
}
