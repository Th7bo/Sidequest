package dev.th7bo.sidequest.platform.core.achievement

import dev.th7bo.sidequest.platform.achievement.Achievement
import dev.th7bo.sidequest.platform.achievement.AchievementRecord
import dev.th7bo.sidequest.platform.achievement.AchievementService
import dev.th7bo.sidequest.platform.achievement.AchievementSource
import dev.th7bo.sidequest.platform.achievement.AchievementStore
import dev.th7bo.sidequest.platform.achievement.AchievementUnlockedEvent
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.id.SqId

/**
 * Keeps the definitions and the records.
 *
 * Two maps, deliberately separate: definitions are code and re-register themselves every
 * session, while records are data and survive a restart. Merging them into one map of
 * "achievements" is how a definition renamed between sessions loses its unlock — the file
 * remembers the record under the old key and nothing can find it.
 *
 * **Unlocking twice loses quietly, and that is correct.** Two rules naming one achievement
 * both firing in the same second is not a race to fix but a race that already happened; the
 * first one won and told the player. The second gets `false` and moves on.
 */
public class DefaultAchievementService(
    private val events: EventBus,
    private val log: Logger,
    private val localPlayer: () -> PlayerId? = { null },
    private val now: () -> Long = System::currentTimeMillis,
) : AchievementService {

    private val definitions = LinkedHashMap<SqId, Achievement>()

    /** Record by achievement id, then subject key. The shape of [AchievementStore], live. */
    private var records: Map<String, Map<String, AchievementRecord>> = emptyMap()

    override var onStoreChanged: ((AchievementStore) -> Unit)? = null

    override fun register(definition: Achievement): Boolean {
        if (definitions.containsKey(definition.id)) {
            log.debug { "Achievement ${definition.id.value} was registered twice; keeping the first" }
            return false
        }
        definitions[definition.id] = definition
        return true
    }

    override fun unregister(id: SqId): Boolean = definitions.remove(id) != null

    override fun all(subject: PlayerId?, includeHidden: Boolean): List<Achievement> =
        definitions.values.filter { it.isVisibleTo(subject, includeHidden) }

    override fun definitionOf(id: SqId): Achievement? = definitions[id]

    override fun recordOf(id: SqId, subject: PlayerId?): AchievementRecord? =
        records[id.value]?.get(subjectKey(subject))

    override fun isUnlocked(id: SqId, subject: PlayerId?): Boolean = recordOf(id, subject) != null

    override fun unlocked(subject: PlayerId?): List<Achievement> {
        val earned = records.entries
            .filter { (_, perSubject) -> perSubject.containsKey(subjectKey(subject)) }
            .mapNotNull { (id, _) -> runCatching { SqId.parse(id) }.getOrNull()?.let(definitions::get) }
        return earned.sortedByDescending { recordOf(it.id, subject)?.unlockedAtMillis ?: 0 }
    }

    override fun unlock(
        id: SqId,
        source: AchievementSource,
        subject: PlayerId?,
    ): Boolean {
        val definition = definitions[id] ?: run {
            // Debug rather than warn: an achievement unregistered while its rule still runs is a
            // normal state during development, not something a user did wrong.
            log.debug { "Cannot unlock ${id.value}: no such achievement" }
            return false
        }
        val key = subjectKey(subject)
        if (records[id.value]?.containsKey(key) == true) return false

        val atMillis = now()
        records = records + (
            id.value to ((records[id.value] ?: emptyMap()) + (key to AchievementRecord(atMillis)))
            )
        onStoreChanged?.invoke(AchievementStore(records))

        log.info { "Achievement unlocked: ${definition.displayName} ($source)" }
        events.post(
            AchievementUnlockedEvent(
                achievementId = id,
                displayName = definition.displayName,
                category = definition.category,
                source = source,
                subject = resolvedSubject(subject),
                atMillis = atMillis,
            ),
            EventSource.DERIVED,
        )
        return true
    }

    /**
     * Whether a listing may show this one.
     *
     * Hidden-and-unearned is the pair that stays out; hidden-but-earned belongs in the cabinet,
     * which is why this reads the store rather than just the flag.
     */
    private fun Achievement.isVisibleTo(subject: PlayerId?, includeHidden: Boolean): Boolean =
        !isHidden || includeHidden || isUnlocked(id, subject)

    override fun load(store: AchievementStore) {
        records = store.byAchievement
        log.debug { "Loaded ${store.byAchievement.size} achievement record(s)" }
    }

    /**
     * Who a record belongs to.
     *
     * The local player when no one is named — the same meaning of a null subject the rule engine
     * uses, for the same reason: an unlock with nobody named is still about somebody. The empty
     * string survives only for a record from before login, which is a state the storage attach
     * exists to prevent but not one worth crashing over.
     */
    private fun subjectKey(subject: PlayerId?): String = resolvedSubject(subject)?.value ?: ""

    private fun resolvedSubject(subject: PlayerId?): PlayerId? = subject ?: localPlayer()
}
