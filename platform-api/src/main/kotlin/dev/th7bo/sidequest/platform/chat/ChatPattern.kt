package dev.th7bo.sidequest.platform.chat

import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.id.SqId

/**
 * One versioned, self-testing chat pattern.
 *
 * Three things here are load-bearing.
 *
 * **The [id] is stable and the [version] is not.** A pattern is a piece of data about
 * Hypixel that goes stale, and the day it does the fix has to be shippable without a mod
 * update — so a pattern with the same id and a higher version replaces one already
 * registered. That is what lets a corrected pattern arrive from the repository later
 * without any of the rules above it changing.
 *
 * **[fixtures] are part of the pattern, not of a test.** Every pattern carries the real
 * lines it was written against, copied from an observed message. A pattern with no fixture
 * is a guess, and [verify] is what makes that visible rather than something discovered in
 * game three weeks later.
 *
 * **[target] is explicit.** A pattern written against the formatted text and matched
 * against the cleaned text fails silently. Saying which view it wants makes the mismatch a
 * compile-time-shaped decision instead of a runtime mystery.
 */
public data class ChatPattern(
    public val id: SqId,
    public val regex: Regex,
    /** Bumped whenever [regex] changes. Higher wins on re-registration. */
    public val version: Int = 1,
    public val target: MatchTarget = MatchTarget.FORMATTED,
    /**
     * Whether the whole line has to match.
     *
     * True by default, and deliberately: an unanchored pattern that happens to appear
     * inside a player's own message is how a chat rule gets triggered by somebody typing
     * the words. Turn it off only for patterns that genuinely look for a fragment.
     */
    public val anchored: Boolean = true,
    /** Real lines this pattern must match. See the class comment. */
    public val fixtures: List<String> = emptyList(),
    /** Lines this pattern must *not* match. Where a near-miss was found, record it. */
    public val counterFixtures: List<String> = emptyList(),
    /**
     * Language this pattern describes.
     *
     * [ANY_LOCALE] means "whatever the client is set to", which is correct for every
     * pattern today: Hypixel sends its SkyBlock messages in English regardless of the
     * client's language. The field exists so the day one message does get translated, the
     * fix is a second pattern with the same id and a locale rather than a rework of the
     * registry.
     */
    public val locale: String = ANY_LOCALE,
) {

    /** Matches [message] against this pattern's [target], or null. */
    public fun matchOrNull(message: ChatMessage): MatchResult? {
        val text = message.textFor(target)
        return if (anchored) regex.matchEntire(text) else regex.find(text)
    }

    /**
     * Checks this pattern against its own fixtures.
     *
     * Returns the failures rather than throwing, so a caller can report all of them at
     * once — one broken pattern among forty is worth knowing about alongside the other
     * thirty-nine that are fine.
     */
    public fun verify(): List<FixtureFailure> = buildList {
        for (fixture in fixtures) {
            val message = ChatMessage.of(fixture)
            if (matchOrNull(message) == null) {
                add(FixtureFailure(id, fixture, expectedMatch = true, matchedText = message.textFor(target)))
            }
        }
        for (fixture in counterFixtures) {
            val message = ChatMessage.of(fixture)
            if (matchOrNull(message) != null) {
                add(FixtureFailure(id, fixture, expectedMatch = false, matchedText = message.textFor(target)))
            }
        }
    }

    override fun toString(): String = "$id v$version [$target]"

    public companion object {
        public const val ANY_LOCALE: String = "*"
    }
}

/** A pattern that did not do what its fixtures say it does. */
public data class FixtureFailure(
    public val patternId: SqId,
    public val fixture: String,
    /** True when the fixture should have matched and did not. */
    public val expectedMatch: Boolean,
    /** The text the pattern was actually run against, after the target's cleaning. */
    public val matchedText: String,
) {
    override fun toString(): String =
        if (expectedMatch) "$patternId did not match its fixture: '$matchedText'"
        else "$patternId matched a counter-fixture it must not: '$matchedText'"
}

/**
 * A successful match, and the message behind it.
 *
 * Both halves matter. The groups are the parsed data; the message is still there because
 * the most reliable field on a Hypixel line is often not in the text at all — it is the
 * command behind the click. See [ChatMessage.commands].
 */
public class ChatMatch(
    public val message: ChatMessage,
    public val pattern: ChatPattern,
    private val result: MatchResult,
) {

    /** A named group, or null when the group did not participate. */
    public fun group(name: String): String? = runCatching { result.groups[name]?.value }.getOrNull()

    /**
     * A named group that has to be there.
     *
     * Throws rather than returning a default. A rule whose group is missing has a pattern
     * that does not say what its author thought, and the loud failure is caught by the
     * fixtures; a default would ship an event with an empty player name in it.
     */
    public fun require(name: String): String = requireNotNull(group(name)) {
        "Pattern ${pattern.id} matched but has no group '$name'"
    }

    /** A named group as an Int, or null when absent or not a number. */
    public fun int(name: String): Int? = group(name)?.trim()?.replace(",", "")?.toIntOrNull()

    /** A named group as a player name, with rank and emblem tags stripped. */
    public fun playerName(name: String): String? = group(name)?.let(HypixelNames::playerName)

    /** The part of the line the pattern matched. */
    public val matched: String get() = result.value
}

/**
 * A pattern plus what to make of it.
 *
 * The whole point of the registry: a feature declares the shape of a line and the event it
 * means, and never sees a chat string again. [build] returning null is the escape hatch for
 * a line that matched but turned out not to be interesting — a drop of an item the feature
 * does not track — and is not an error.
 */
public class ChatRule<out E : SidequestEvent>(
    public val pattern: ChatPattern,
    public val build: (ChatMatch) -> E?,
) {
    override fun toString(): String = "rule(${pattern.id})"
}

/** Builds a rule, inferring the event type. */
public fun <E : SidequestEvent> chatRule(
    id: SqId,
    regex: String,
    version: Int = 1,
    target: MatchTarget = MatchTarget.FORMATTED,
    anchored: Boolean = true,
    fixtures: List<String> = emptyList(),
    counterFixtures: List<String> = emptyList(),
    build: (ChatMatch) -> E?,
): ChatRule<E> = ChatRule(
    pattern = ChatPattern(
        id = id,
        regex = Regex(regex),
        version = version,
        target = target,
        anchored = anchored,
        fixtures = fixtures,
        counterFixtures = counterFixtures,
    ),
    build = build,
)
