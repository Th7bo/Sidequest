package dev.th7bo.sidequest.platform.evidence

import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.skyblock.Island
import kotlinx.serialization.Serializable

/**
 * What kind of moment this is.
 *
 * The plan's list, minus the ones that are really *who* rather than *what*: "wrong-lobby incident" and
 * "soundboard moment" are incidents with tags, and modelling every joke as its own enum is how the set
 * stops growing gracefully. [INCIDENT] plus a tag says the same thing and needs no migration when the
 * group invents a new category of embarrassment.
 */
@Serializable
public enum class EvidenceKind(public val displayName: String) {
    SCREENSHOT("Screenshot"),
    RARE_DROP("Rare drop"),
    DEATH("Death"),
    CHAT_MESSAGE("Chat"),
    ACHIEVEMENT("Achievement"),
    DEBT("Debt"),
    TRADE("Trade"),
    PERSONAL_BEST("Personal best"),
    INCIDENT("Incident"),
}

/**
 * A screenshot this entry points at, without being one.
 *
 * **A reference, never bytes.** Images live in the mod's own directory under their hash; an evidence file
 * that embedded them would make every caption edit rewrite megabytes. The hash is what makes the reference
 * trustworthy — a filename can be overwritten, a content address cannot.
 */
@Serializable
public data class ScreenshotRef(
    /** File name within the screenshots directory. */
    public val fileName: String,
    /** SHA-256 of the image, lowercase hex, when it has been hashed. */
    public val sha256: String? = null,
)

/**
 * One moment worth keeping.
 *
 * The local half of the plan's shared evidence locker: everything here is true on this machine, syncs later,
 * and never contains anything another person wrote. Reactions, comments and albums are group content and
 * belong to the backend's side of the locker — modelling them locally now would mean two places answering
 * who said what, which is exactly the drift the realtime protocol exists to prevent.
 *
 * **Nothing is ever deleted by hiding.** [isHiddenLocally] takes an entry out of view and out of search;
 * it stays in the file, because "why is there a screenshot of that" is a question answered by the entry
 * still being there. Only [dev.th7bo.sidequest.platform.evidence.EvidenceService.remove] removes.
 */
@Serializable
public data class EvidenceEntry(
    public val id: String,
    public val kind: EvidenceKind,
    /** What it is called in a list. */
    public val title: String,
    /** What somebody wrote about it. Free text, private until synced. */
    public val caption: String = "",
    public val atMillis: Long = 0,

    /** Everybody involved, the player themselves included. UUIDs, like everywhere durable. */
    public val players: List<PlayerId> = emptyList(),

    /**
     * Where it happened.
     *
     * An island rather than a position: the interesting question about an incident is *which lobby*, not
     * *which block*, and a position pins a moment to coordinates nobody will ever visit again.
     */
    public val island: Island? = null,

    /** What the group was doing at the time, as the context service names activities. */
    public val activity: String? = null,

    public val tags: Set<String> = emptySet(),
    public val screenshot: ScreenshotRef? = null,

    /**
     * Links to other things by id — `achievement` to an achievement id, `debt` to a debt, `sound` to a
     * soundboard rule. A map rather than typed fields because each link type would otherwise be a nullable
     * column, and six of those is a form nobody fills in.
     */
    public val links: Map<String, String> = emptyMap(),

    public val isHiddenLocally: Boolean = false,
) {

    public fun involves(player: PlayerId): Boolean = player in players

    public fun isTagged(tag: String): Boolean =
        tags.any { it.equals(tag, ignoreCase = true) }

    /** Whether it matches free text across the fields a person might search. Case-insensitive throughout. */
    public fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        return listOf(title, caption)
            .any { it.contains(query, ignoreCase = true) } ||
            tags.any { it.contains(query, ignoreCase = true) } ||
            kind.displayName.contains(query, ignoreCase = true)
    }
}

/**
 * Every entry, newest first.
 *
 * Immutable and replaced wholesale, like the debt ledger and the wallet. It is a friend group's scrapbook:
 * hundreds of entries after a year of playing, which is small enough to hold in memory whole and large
 * enough that [EvidenceService.search] earns its place.
 */
@Serializable
public data class EvidenceLocker(
    public val entries: List<EvidenceEntry> = emptyList(),
) {

    public val size: Int get() = entries.size

    public operator fun get(id: String): EvidenceEntry? = entries.firstOrNull { it.id == id }

    /** Adds or replaces, keyed by id, keeping newest first. */
    public fun with(entry: EvidenceEntry): EvidenceLocker {
        val rest = entries.filterNot { it.id == entry.id }
        return copy(entries = (listOf(entry) + rest).sortedByDescending { it.atMillis })
    }

    public fun without(id: String): EvidenceLocker = copy(entries = entries.filterNot { it.id == id })

    /** Applies a change to one entry, if it is here. */
    public fun edit(id: String, change: (EvidenceEntry) -> EvidenceEntry): EvidenceLocker {
        val existing = get(id) ?: return this
        return with(change(existing))
    }

    /** Everything visible, optionally narrowed to one kind. Hidden entries need their own ask. */
    public fun visible(kind: EvidenceKind? = null): List<EvidenceEntry> = entries.filter {
        !it.isHiddenLocally && (kind == null || it.kind == kind)
    }

    public fun involving(player: PlayerId): List<EvidenceEntry> =
        entries.filter { it.involves(player) }

    public fun tagged(tag: String): List<EvidenceEntry> = entries.filter { it.isTagged(tag) }
}

/**
 * The local half of the evidence locker.
 *
 * Features record through this and never write files, never take screenshots directly, and never invent ids.
 * Whether a screenshot gets *taken* is the caller's decision and always will be — the plan's rule that
 * nothing is uploaded silently starts as nothing is captured silently, and capture is a game-adapter act
 * that belongs behind an explicit ask.
 */
public interface EvidenceService {

    /**
     * Records an entry and posts [EvidenceCreatedEvent]. Returns it with its id filled in, or null when
     * refused — a blank title is the only refusal, because an entry nobody can name is an entry nobody
     * can find again.
     */
    public fun record(entry: EvidenceEntry): EvidenceEntry?

    public fun remove(id: String): Boolean

    /** Takes it out of view without deleting it. */
    public fun hide(id: String): Boolean

    public fun unhide(id: String): Boolean

    /** Adds a tag. Returns false for an unknown id or a tag already present. */
    public fun tag(id: String, tag: String): Boolean

    /** Everything recorded, newest first, hidden entries only when asked. */
    public fun all(includeHidden: Boolean = false): List<EvidenceEntry>

    public fun get(id: String): EvidenceEntry?

    /** Free text across titles, captions and tags. */
    public fun search(query: String, includeHidden: Boolean = false): List<EvidenceEntry>

    public fun involving(player: PlayerId): List<EvidenceEntry>

    /** Restores a persisted locker. */
    public fun load(locker: EvidenceLocker)

    /** Called after every change, so the caller can persist. Set by whoever owns the storage. */
    public var onStoreChanged: ((EvidenceLocker) -> Unit)?
}

/** Something was recorded. Posted so presentation can offer capture or react, without knowing who recorded it. */
public class EvidenceCreatedEvent(public val entry: EvidenceEntry) : SidequestEvent() {
    override fun describe(): String = "${entry.kind} \"${entry.title}\""
}
