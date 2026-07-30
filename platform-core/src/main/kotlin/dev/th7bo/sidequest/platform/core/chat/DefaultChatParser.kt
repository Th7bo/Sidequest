package dev.th7bo.sidequest.platform.core.chat

import dev.th7bo.sidequest.platform.chat.ChatMatch
import dev.th7bo.sidequest.platform.chat.ChatMessage
import dev.th7bo.sidequest.platform.chat.ChatMessageEvent
import dev.th7bo.sidequest.platform.chat.ChatParser
import dev.th7bo.sidequest.platform.chat.ChatParserStats
import dev.th7bo.sidequest.platform.chat.ChatPattern
import dev.th7bo.sidequest.platform.chat.ChatRule
import dev.th7bo.sidequest.platform.chat.FixtureFailure
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.lifecycle.Registration
import dev.th7bo.sidequest.platform.log.Logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Classifies every chat line once, and posts what it found.
 *
 * Four decisions shape this class.
 *
 * **Every rule gets a look, not just the first one that matches.** First-match-wins is
 * tempting and quietly wrong: two features can legitimately care about the same line for
 * different reasons, and under first-match-wins whichever registered later silently stops
 * working. The cost is that overlapping patterns both fire, which the fixture tests are
 * there to catch — [HypixelChatRules] asserts that a line classifies exactly once.
 *
 * **A rule that throws is isolated.** Same reasoning as the event bus: a broken pattern in
 * one feature must not stop the others from seeing chat. The failure is logged and counted.
 *
 * **Duplicate suppression applies to derived events, never to the line itself.**
 * [ChatMessageEvent] is posted for every line including repeats, because a feature that
 * mirrors or logs chat wants exactly what the player saw. What is suppressed is the *second*
 * `RareDropEvent` for the same drop — Hypixel does send the same line twice, and a feature
 * that counted both would report twice the loot.
 *
 * **Version wins over registration order.** A rule whose pattern id is already registered
 * replaces it if its version is higher and is refused otherwise, so a corrected pattern can
 * arrive from anywhere without the caller needing to know what it is competing with.
 */
public class DefaultChatParser(
    private val events: EventBus,
    private val log: Logger,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * How close together two identical lines have to be to count as one.
     *
     * Short on purpose. Hypixel's duplicates arrive in the same tick or the next one; a
     * window long enough to catch a slow one is also long enough to swallow two genuine
     * drops in a row, and losing a real event is worse than counting a duplicate.
     */
    private val duplicateWindowMillis: Long = DEFAULT_DUPLICATE_WINDOW_MILLIS,
) : ChatParser {

    private class Entry(
        val rule: ChatRule<*>,
        val owner: OwnerId,
    ) {
        @Volatile
        var isActive: Boolean = true
    }

    private val entries = CopyOnWriteArrayList<Entry>()

    override var isDebugLogging: Boolean = false

    override var stats: ChatParserStats = ChatParserStats()
        private set

    private var lastRaw: String? = null
    private var lastAtMillis: Long = 0

    override fun register(rule: ChatRule<*>, owner: OwnerId): Registration {
        val id = rule.pattern.id
        val existing = entries.firstOrNull { it.isActive && it.rule.pattern.id == id }
        if (existing != null) {
            if (rule.pattern.version <= existing.rule.pattern.version) {
                log.debug {
                    "Keeping ${existing.rule.pattern} over the v${rule.pattern.version} " +
                        "offered by $owner — a replacement has to be newer"
                }
                return Registration.None
            }
            log.info { "Pattern $id replaced: v${existing.rule.pattern.version} to v${rule.pattern.version}" }
            existing.isActive = false
            entries.remove(existing)
        }

        val entry = Entry(rule, owner)
        entries.add(entry)
        return Registration {
            entry.isActive = false
            entries.remove(entry)
        }
    }

    override fun registerAll(rules: List<ChatRule<*>>, owner: OwnerId): Registration {
        val registrations = rules.map { register(it, owner) }
        return Registration { registrations.forEach { it.cancel() } }
    }

    override fun unregisterAll(owner: OwnerId) {
        val mine = entries.filter { it.owner == owner }
        for (entry in mine) entry.isActive = false
        entries.removeAll(mine)
    }

    override fun patterns(): List<ChatPattern> = entries.map { it.rule.pattern }

    override fun verifyFixtures(): List<FixtureFailure> = entries.flatMap { it.rule.pattern.verify() }

    /**
     * Feeds one line in. Called by the client adapter, once per message.
     *
     * Everything downstream of chat starts here, so this is deliberately the only entry
     * point: a second one would be a second place duplicate suppression and the counters
     * could disagree.
     */
    public fun onMessage(message: ChatMessage) {
        val isDuplicate = isRepeatOfPrevious(message)
        stats = stats.copy(
            received = stats.received + 1,
            duplicates = if (isDuplicate) stats.duplicates + 1 else stats.duplicates,
        )

        // Before the rules, and for duplicates too: this is the unfiltered stream.
        events.post(ChatMessageEvent(message, isDuplicate), EventSource.PARSER)

        if (isDuplicate) {
            if (isDebugLogging) log.debug { "chat (duplicate, no rules run): ${message.clean}" }
            return
        }

        var matchedAny = false
        for (entry in entries) {
            if (!entry.isActive) continue
            val pattern = entry.rule.pattern
            val result = try {
                pattern.matchOrNull(message)
            } catch (thrown: Throwable) {
                stats = stats.copy(failures = stats.failures + 1)
                log.error(thrown) { "Pattern ${pattern.id} threw while matching" }
                continue
            } ?: continue

            matchedAny = true
            val event = try {
                // `ChatRule` is covariant in its event type, so this is already a
                // `(ChatMatch) -> SidequestEvent?` with no cast needed.
                entry.rule.build(ChatMatch(message, pattern, result))
            } catch (thrown: Throwable) {
                stats = stats.copy(failures = stats.failures + 1)
                log.error(thrown) { "Rule ${pattern.id} (${entry.owner}) threw while building its event" }
                continue
            }

            if (event == null) {
                // Matched but not interesting. Normal, and not a failure — see ChatRule.
                if (isDebugLogging) log.debug { "  ${pattern.id} matched but built nothing" }
                continue
            }
            if (isDebugLogging) log.debug { "  ${pattern.id} -> ${event.describe()}" }
            events.post(event, EventSource.PARSER)
        }

        if (matchedAny) stats = stats.copy(classified = stats.classified + 1)
        if (isDebugLogging && !matchedAny) log.debug { "chat (unclassified): ${message.raw}" }
    }

    /**
     * Whether this line repeats the one before it, closely enough in time.
     *
     * Compares [ChatMessage.raw] rather than the cleaned text: two visually identical lines
     * with different formatting came from different messages, and treating them as one would
     * hide a real event.
     */
    private fun isRepeatOfPrevious(message: ChatMessage): Boolean {
        val timestamp = now()
        val isRepeat = message.raw == lastRaw && timestamp - lastAtMillis <= duplicateWindowMillis
        lastRaw = message.raw
        lastAtMillis = timestamp
        return isRepeat
    }

    /** Forgets what the last line was. Called on disconnect. */
    public fun reset() {
        lastRaw = null
        lastAtMillis = 0
    }

    /** Rule ids by owner, for the inspector. */
    public fun ownersById(): Map<SqId, OwnerId> = entries.associate { it.rule.pattern.id to it.owner }

    public companion object {
        public const val DEFAULT_DUPLICATE_WINDOW_MILLIS: Long = 150
    }
}
