package dev.th7bo.sidequest.platform.core.evidence

import dev.th7bo.sidequest.platform.evidence.EvidenceCreatedEvent
import dev.th7bo.sidequest.platform.evidence.EvidenceEntry
import dev.th7bo.sidequest.platform.evidence.EvidenceLocker
import dev.th7bo.sidequest.platform.evidence.EvidenceService
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.player.PlayerId

/**
 * The locker, in memory.
 *
 * Small on purpose, like the debt ledger it sits beside: one immutable [EvidenceLocker] replaced per
 * change, so the change callback always sees a complete file and there is nothing to lock. Recording
 * posts the event *after* the state has moved, so a listener reading back through the service never
 * observes an entry that is not there yet.
 */
public class DefaultEvidenceService(
    private val events: EventBus,
    private val log: Logger,
    private val nextId: () -> String = { "ev" + System.currentTimeMillis().toString(36) },
) : EvidenceService {

    private var locker = EvidenceLocker()

    override var onStoreChanged: ((EvidenceLocker) -> Unit)? = null

    override fun record(entry: EvidenceEntry): EvidenceEntry? {
        if (entry.title.isBlank()) {
            log.debug { "Refused an evidence entry with no title" }
            return null
        }
        val id = entry.id.ifBlank { nextId() }
        val stored = entry.copy(id = id)
        apply(locker.with(stored))
        log.debug { "Recorded ${stored.kind} \"$id\"" }
        events.post(EvidenceCreatedEvent(stored), EventSource.DERIVED)
        return stored
    }

    override fun remove(id: String): Boolean {
        if (locker[id] == null) return false
        apply(locker.without(id))
        return true
    }

    override fun hide(id: String): Boolean = setHidden(id, true)

    override fun unhide(id: String): Boolean = setHidden(id, false)

    override fun tag(id: String, tag: String): Boolean {
        val entry = locker[id] ?: return false
        val cleaned = tag.trim()
        if (cleaned.isEmpty() || entry.isTagged(cleaned)) return false
        apply(locker.edit(id) { it.copy(tags = it.tags + cleaned) })
        return true
    }

    override fun all(includeHidden: Boolean): List<EvidenceEntry> =
        if (includeHidden) locker.entries else locker.entries.filterNot { it.isHiddenLocally }

    override fun get(id: String): EvidenceEntry? = locker[id]

    override fun search(query: String, includeHidden: Boolean): List<EvidenceEntry> =
        all(includeHidden).filter { it.matches(query) }

    override fun involving(player: PlayerId): List<EvidenceEntry> = locker.involving(player)

    override fun load(locker: EvidenceLocker) {
        this.locker = locker
        log.debug { "Loaded ${locker.size} evidence entr${if (locker.size == 1) "y" else "ies"}" }
    }

    private fun setHidden(id: String, hidden: Boolean): Boolean {
        if (locker[id] == null) return false
        apply(locker.edit(id) { it.copy(isHiddenLocally = hidden) })
        return true
    }

    /** One path for every accepted change, so nothing can move the locker without announcing it. */
    private fun apply(updated: EvidenceLocker) {
        locker = updated
        onStoreChanged?.invoke(updated)
    }
}
