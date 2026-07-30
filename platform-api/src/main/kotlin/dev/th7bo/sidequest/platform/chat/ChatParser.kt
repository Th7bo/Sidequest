package dev.th7bo.sidequest.platform.chat

import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.lifecycle.Registration

/**
 * The one place chat is classified.
 *
 * Every incoming line goes through here once, and features receive typed events. The
 * alternative — each feature holding its own regex and subscribing to a raw-chat event — is
 * the failure this whole layer exists to prevent: forty copies of nearly the same pattern,
 * thirty-nine of which are not fixed when Hypixel rewords the message.
 *
 * Registrations are owned, like everything else on the platform, so a feature that unloads
 * takes its rules with it without participating.
 */
public interface ChatParser {

    /**
     * Registers [rule] for as long as the returned registration lives.
     *
     * A rule with the id of one already registered replaces it when its version is higher
     * and is refused when it is not — see [ChatPattern]. That is how a corrected pattern
     * supersedes a built-in one without the feature above it knowing which won.
     */
    public fun register(rule: ChatRule<*>, owner: OwnerId): Registration

    /** Registers several rules as one registration. Cancelling it drops all of them. */
    public fun registerAll(rules: List<ChatRule<*>>, owner: OwnerId): Registration

    /** Everything registered by [owner] stops matching. */
    public fun unregisterAll(owner: OwnerId)

    /** Live patterns, for the inspector and for the fixture check. */
    public fun patterns(): List<ChatPattern>

    /**
     * Runs every live pattern against its own fixtures.
     *
     * Exposed at runtime rather than only in tests because the patterns can be replaced at
     * runtime: a pattern that arrived from the repository has not been through this
     * project's test suite, and this is the check that says whether it does what it claims.
     */
    public fun verifyFixtures(): List<FixtureFailure>

    /**
     * Logs every line and what matched it.
     *
     * The single most useful thing when a rule stops firing, because it distinguishes
     * "Hypixel changed the message" from "the line never reached the parser" — two
     * problems that look identical from the outside and have nothing in common.
     */
    public var isDebugLogging: Boolean

    /** How many lines were seen and how many were classified. For the inspector. */
    public val stats: ChatParserStats
}

/** Counters for the developer inspector. Cheap to keep, and the first thing worth seeing. */
public data class ChatParserStats(
    public val received: Long = 0,
    /** Lines at least one rule matched. */
    public val classified: Long = 0,
    /** Lines dropped as a repeat of the one before. See duplicate suppression. */
    public val duplicates: Long = 0,
    /** Rules that threw while building an event. Should be zero. */
    public val failures: Long = 0,
) {
    public val unclassified: Long get() = received - classified - duplicates
}

/**
 * Every chat line, before any rule has looked at it.
 *
 * Posted for all of them, including the ones no rule matched and the ones suppressed as
 * duplicates, because a feature that logs or mirrors chat wants exactly the stream the
 * player saw. Rule-derived events are the filtered view; this is the unfiltered one.
 */
public class ChatMessageEvent(
    public val message: ChatMessage,
    /** True when this line repeats the previous one within the suppression window. */
    public val isDuplicate: Boolean = false,
) : SidequestEvent() {
    override fun describe(): String =
        (if (isDuplicate) "duplicate: " else "") + message.clean.take(DESCRIBE_LIMIT)

    private companion object {
        const val DESCRIBE_LIMIT = 80
    }
}
