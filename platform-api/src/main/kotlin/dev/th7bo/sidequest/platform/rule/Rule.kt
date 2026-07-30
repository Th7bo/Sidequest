package dev.th7bo.sidequest.platform.rule

import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.item.SqItem
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.skyblock.Activity
import dev.th7bo.sidequest.platform.skyblock.GameContext
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * One "when this, then that".
 *
 * The plan asks for a generic engine behind achievements, soundboard triggers, contracts, evidence
 * automation, cosmetic rewards, titles and notifications. All seven are the same shape — watch for something,
 * check some conditions, do some things — and writing them seven times is how a mod ends up with seven
 * slightly different ideas of what a cooldown means.
 *
 * A rule is **data**. It has no code in it beyond the conditions and actions, both of which are values, which
 * is what makes a rule inspectable: a debug command can say why one did not fire, and a rule that arrived from
 * the backend can be checked before it is trusted.
 */
public data class Rule(
    public val id: SqId,
    /** Shown to a player when the rule does something. Not needed for a [isHidden] rule. */
    public val displayName: String = "",
    public val description: String = "",
    /** What kind of event wakes it up. Narrowing here is what stops every rule evaluating on every event. */
    public val trigger: RuleTrigger,
    public val condition: Condition = Condition.Always,
    public val actions: List<RuleAction> = emptyList(),

    /**
     * Progress thresholds, in ascending order.
     *
     * A rule with tiers fires each time progress crosses one, and its actions can ask which tier with
     * [RuleOutcome.tier]. A rule without them fires whenever its conditions hold.
     *
     * Empty is the common case. Tiers exist because "kill 10, 100, 1000" is one rule and three achievements,
     * and modelling it as three rules means three copies of the condition that can drift apart.
     */
    public val tiers: List<Int> = emptyList(),

    /**
     * The shortest gap between two firings, per subject.
     *
     * Per subject rather than global: a rule about a party member's drops should not go quiet because a
     * different member triggered it a second ago.
     */
    public val cooldown: Duration = ZERO,

    /**
     * How many times this can ever fire for one subject, or null for no limit.
     *
     * One is the common case for an achievement, and modelling it as a limit rather than as "unlock and then
     * check the unlock" means the engine enforces it instead of every rule remembering to.
     */
    public val maxFirings: Int? = null,

    /** When progress goes back to zero. */
    public val reset: RuleReset = RuleReset.NEVER,

    /**
     * Whether the player is told this rule exists.
     *
     * The plan asks for hidden rules, and they are the point of half the joke content: an achievement nobody
     * knew about is worth more than one on a checklist. A hidden rule is left out of a listing until it fires.
     */
    public val isHidden: Boolean = false,

    /** False to leave a rule registered but inert. For a rule being worked on, or one a user switched off. */
    public val isEnabled: Boolean = true,
) {
    override fun toString(): String = id.value
}

/**
 * What wakes a rule up.
 *
 * The event *class*, so the engine can index rules by it and evaluate only the handful that could possibly
 * match. Without that, every rule's conditions run on every event, and at twenty ticks a second with a
 * hundred rules that is a lot of work to conclude nothing.
 */
public data class RuleTrigger(
    public val eventType: Class<out SidequestEvent>,
) {
    override fun toString(): String = eventType.simpleName

    public companion object {
        public inline fun <reified T : SidequestEvent> of(): RuleTrigger = RuleTrigger(T::class.java)
    }
}

/** When a rule's progress goes back to zero. */
@Serializable
public enum class RuleReset {
    NEVER,

    /** Each session. For anything about "this run" rather than "ever". */
    ON_DISCONNECT,

    /** Each SkyBlock profile. Progression that belongs to a profile and not to the account. */
    ON_PROFILE_CHANGE,

    DAILY,
}

// -- conditions ------------------------------------------------------------

/**
 * Whether a rule should fire.
 *
 * A sealed tree rather than a lambda, and that is the most important decision in this file. A lambda is opaque:
 * when a rule does not fire, all anybody can say is "the condition was false". A tree can be *walked*, so
 * [Condition.explain] names the branch that failed — and "which condition stopped it" is the only question
 * anybody ever asks about a rule that is not working.
 *
 * It also means a rule can arrive from the backend as data and be inspected before it is run, rather than
 * being a piece of code somebody has to trust.
 */
public sealed interface Condition {

    /** Whether this holds. */
    public fun test(context: RuleContext): Boolean

    /**
     * Why this did or did not hold, in a few words.
     *
     * Written as a statement about the *actual* state so a log line reads as an explanation rather than as a
     * restatement of the rule.
     */
    public fun explain(context: RuleContext): String

    /** Always true. The default, for a rule that only needs its trigger. */
    public data object Always : Condition {
        override fun test(context: RuleContext): Boolean = true
        override fun explain(context: RuleContext): String = "no conditions"
    }

    public data class All(public val conditions: List<Condition>) : Condition {
        override fun test(context: RuleContext): Boolean = conditions.all { it.test(context) }

        /** Names the first failure only. The rest are irrelevant once one has stopped it. */
        override fun explain(context: RuleContext): String =
            conditions.firstOrNull { !it.test(context) }?.explain(context) ?: "all conditions held"
    }

    public data class Any(public val conditions: List<Condition>) : Condition {
        override fun test(context: RuleContext): Boolean = conditions.any { it.test(context) }
        override fun explain(context: RuleContext): String =
            if (test(context)) {
                conditions.first { it.test(context) }.explain(context)
            } else {
                "none of ${conditions.size} alternatives held"
            }
    }

    public data class Not(public val condition: Condition) : Condition {
        override fun test(context: RuleContext): Boolean = !condition.test(context)
        override fun explain(context: RuleContext): String = "not (${condition.explain(context)})"
    }

    // -- where and what -----------------------------------------------------

    public data class OnIsland(public val islands: Set<Island>) : Condition {
        override fun test(context: RuleContext): Boolean = context.game.island in islands
        override fun explain(context: RuleContext): String = "on ${context.game.island.displayName}"
    }

    public data class DoingActivity(public val activities: Set<Activity>) : Condition {
        override fun test(context: RuleContext): Boolean = context.game.activity.activity in activities
        override fun explain(context: RuleContext): String =
            "doing ${context.game.activity.activity.displayName}"
    }

    /**
     * Whether the activity is believed, not merely guessed.
     *
     * The condition that stops a rule acting on a heuristic. A sound that plays because the mod *thinks* the
     * player is in a dungeon is worse than one that does not play.
     */
    public data object ActivityIsReliable : Condition {
        override fun test(context: RuleContext): Boolean = context.game.activity.isReliable
        override fun explain(context: RuleContext): String =
            "activity confidence is ${context.game.activity.confidence}"
    }

    public data object InParty : Condition {
        override fun test(context: RuleContext): Boolean = context.isInParty
        override fun explain(context: RuleContext): String =
            if (context.isInParty) "in a party" else "not in a party"
    }

    /** Whether the subject is somebody in the friend group. */
    public data object IsCustomFriend : Condition {
        override fun test(context: RuleContext): Boolean = context.isSubjectCustomFriend
        override fun explain(context: RuleContext): String =
            if (context.isSubjectCustomFriend) "subject is a friend" else "subject is not a friend"
    }

    // -- the event itself ----------------------------------------------------

    /**
     * A phrase in the event's text.
     *
     * The escape hatch for anything the typed conditions do not cover. Matched against whatever the event
     * offers as text, case-insensitively, because nobody writing a rule wants to think about case.
     */
    public data class TextContains(public val phrase: String) : Condition {
        override fun test(context: RuleContext): Boolean =
            context.text?.contains(phrase, ignoreCase = true) == true
        override fun explain(context: RuleContext): String =
            "text ${if (test(context)) "contains" else "does not contain"} '$phrase'"
    }

    /**
     * A named item, by SkyBlock id *or* display name.
     *
     * Both, because what is available depends on where the event came from and a rule author should not have to
     * know. A drop read from chat has only a name — the line says "Hyperion" and nothing else — while an item
     * read from a stack has an id. Matching either means one condition covers both, and a rule written against
     * a chat drop keeps working when the same event starts carrying a full snapshot.
     */
    public data class ItemIs(public val names: Set<String>) : Condition {
        override fun test(context: RuleContext): Boolean =
            names.any { name ->
                context.item?.skyblockId.equals(name, ignoreCase = true) ||
                    context.itemName?.equals(name, ignoreCase = true) == true
            }

        override fun explain(context: RuleContext): String =
            "item is ${context.item?.skyblockId ?: context.itemName ?: "none"}"
    }

    /**
     * The event's numeric value, against a range.
     *
     * Covers "a drop worth more than a million", "a debt under a hundred", "at least five of them". A range
     * rather than an operator plus a number, because a range cannot be written the wrong way round.
     */
    public data class ValueIn(public val range: LongRange) : Condition {
        override fun test(context: RuleContext): Boolean = context.value?.let { it in range } == true
        override fun explain(context: RuleContext): String = "value is ${context.value ?: "none"}, wanted $range"
    }

    // -- state ---------------------------------------------------------------

    /** Progress on this rule, or another. Null means this rule's own. */
    public data class ProgressAtLeast(
        public val count: Int,
        public val ruleId: SqId? = null,
    ) : Condition {
        override fun test(context: RuleContext): Boolean = context.progressOf(ruleId) >= count
        override fun explain(context: RuleContext): String =
            "progress is ${context.progressOf(ruleId)}, wanted $count"
    }

    /** Whether another rule has ever fired for this subject. How a rule depends on an achievement. */
    public data class HasFired(public val ruleId: SqId) : Condition {
        override fun test(context: RuleContext): Boolean = context.firingsOf(ruleId) > 0
        override fun explain(context: RuleContext): String =
            if (test(context)) "$ruleId has fired" else "$ruleId has not fired yet"
    }

    /**
     * Within a window of the subject's last firing of another rule.
     *
     * For anything about *sequence*: two drops within a minute, a payment shortly after a debt. Cheaper and
     * clearer than a rule that has to keep its own timestamps.
     */
    public data class WithinOf(
        public val ruleId: SqId,
        public val window: Duration,
    ) : Condition {
        override fun test(context: RuleContext): Boolean {
            val last = context.lastFiredAt(ruleId) ?: return false
            return context.nowMillis - last <= window.inWholeMilliseconds
        }
        override fun explain(context: RuleContext): String {
            val last = context.lastFiredAt(ruleId) ?: return "$ruleId has never fired"
            return "${context.nowMillis - last}ms since $ruleId, wanted within ${window.inWholeMilliseconds}ms"
        }
    }

    /** An arbitrary predicate, for a condition not worth a type. Opaque to [explain], and says so. */
    public data class Custom(
        public val name: String,
        public val predicate: (RuleContext) -> Boolean,
    ) : Condition {
        override fun test(context: RuleContext): Boolean = predicate(context)
        override fun explain(context: RuleContext): String =
            "$name is ${if (test(context)) "true" else "false"}"
    }

    public companion object {
        /** Reads as `Condition.all(OnIsland(…), InParty)`. */
        public fun all(vararg conditions: Condition): Condition = All(conditions.toList())

        public fun any(vararg conditions: Condition): Condition = Any(conditions.toList())
    }
}

/**
 * What a condition can see.
 *
 * Deliberately a *narrow view* rather than the whole platform. A condition that could reach the event bus could
 * post events, and a rule engine whose conditions have side effects is a rule engine nobody can reason about.
 *
 * The event-shaped fields — [text], [item], [value] — are extracted by the engine from whatever triggered the
 * rule, so a condition does not have to know the event's type. That is what lets `TextContains` work against a
 * chat line, a notification title and an achievement name without three conditions.
 */
public interface RuleContext {

    public val event: SidequestEvent

    public val game: GameContext

    /** Who this firing is about. The local player for most rules. */
    public val subject: PlayerId?

    public val nowMillis: Long

    public val isInParty: Boolean

    public val isSubjectCustomFriend: Boolean

    /** The event's text, where it has one. */
    public val text: String?

    /**
     * The event's item snapshot, where it has one.
     *
     * Null for anything read from chat, which is most of what exists today: a chat line names an item and
     * carries nothing else, so there is nothing to build a snapshot from. See [itemName].
     */
    public val item: SqItem?

    /**
     * The event's item *name*, where it has one.
     *
     * Separate from [item] rather than derived from it, because the two have different sources and one is
     * usually absent. A drop read from chat populates this and not [item]; an item read from a stack populates
     * both.
     */
    public val itemName: String?

    /** The event's number, where it has one: coins, a count, a level. */
    public val value: Long?

    /** The event's place, where it has one. */
    public val location: SqLocation?

    /** Progress on a rule for this subject. Null means the rule being evaluated. */
    public fun progressOf(ruleId: SqId?): Int

    public fun firingsOf(ruleId: SqId): Int

    /** When a rule last fired for this subject, or null. */
    public fun lastFiredAt(ruleId: SqId): Long?
}

// -- actions ---------------------------------------------------------------

/**
 * Something a rule does.
 *
 * Data, not a lambda, and dispatched by a handler the engine looks up. That indirection is what lets the engine
 * exist now: the plan's action list includes granting currency, creating evidence and triggering a dashboard
 * event, none of which have subsystems yet. An action with no handler is **logged and skipped**, so a rule that
 * is half-supported does the half that works rather than failing whole.
 */
public sealed interface RuleAction {

    /** Which handler runs it. Also what appears in a log when one is missing. */
    public val kind: String

    /** Adds to the subject's progress. The only action the engine performs itself. */
    public data class AddProgress(public val amount: Int = 1) : RuleAction {
        override val kind: String get() = "progress"
    }

    public data class PlaySound(public val soundId: SqId, public val volume: Float = 1f) : RuleAction {
        override val kind: String get() = "sound"
    }

    public data class PlaySoundPool(public val poolId: SqId) : RuleAction {
        override val kind: String get() = "sound_pool"
    }

    /**
     * Tells the player something.
     *
     * The title may contain `{tier}` and `{progress}`, substituted when the action runs. A rule with tiers
     * otherwise needs one action per tier, which is the duplication tiers exist to avoid.
     */
    public data class Notify(
        public val title: String,
        public val subtitle: String? = null,
        public val category: String = "PROGRESSION",
        public val priority: String = "NORMAL",
    ) : RuleAction {
        override val kind: String get() = "notify"
    }

    /** Marks an achievement earned. Handled by the achievement service when it exists. */
    public data class UnlockAchievement(public val achievementId: SqId) : RuleAction {
        override val kind: String get() = "achievement"
    }

    public data class GrantCurrency(public val amount: Long, public val reason: String? = null) : RuleAction {
        override val kind: String get() = "currency"
    }

    public data class AssignTitle(public val titleId: SqId) : RuleAction {
        override val kind: String get() = "title"
    }

    public data class UnlockCosmetic(public val cosmeticId: SqId) : RuleAction {
        override val kind: String get() = "cosmetic"
    }

    public data class QueueCinematic(public val cinematicId: SqId) : RuleAction {
        override val kind: String get() = "cinematic"
    }

    public data class CreateEvidence(public val caption: String) : RuleAction {
        override val kind: String get() = "evidence"
    }

    public data class CreatePing(public val label: String = "") : RuleAction {
        override val kind: String get() = "ping"
    }

    public data class CreateWaypoint(public val label: String) : RuleAction {
        override val kind: String get() = "waypoint"
    }

    /** Records a number for the statistics screen. */
    public data class WriteStat(public val statId: SqId, public val amount: Long = 1) : RuleAction {
        override val kind: String get() = "stat"
    }

    /** Anything the sealed set does not cover. For a rule from the backend naming a newer action. */
    public data class Custom(
        override val kind: String,
        public val payload: Map<String, String> = emptyMap(),
    ) : RuleAction
}

/** Runs one kind of action. Registered with the engine, so the engine imports no subsystem. */
public fun interface ActionHandler {
    /** Returns false when the action could not be performed, which is logged and otherwise ignored. */
    public fun run(action: RuleAction, outcome: RuleOutcome): Boolean
}

// -- results ---------------------------------------------------------------

/** What happened when a rule was evaluated. */
public sealed interface RuleEvaluation {

    public val rule: Rule

    /** It fired. */
    public data class Fired(
        override val rule: Rule,
        public val outcome: RuleOutcome,
    ) : RuleEvaluation

    /**
     * It did not fire, and this is why.
     *
     * The reason is the point. A rule that silently does nothing is the single most frustrating thing in a
     * system like this, and the difference between "the condition was false" and "you are on the Hub, not in
     * the Catacombs" is the difference between an hour and a minute.
     */
    public data class Skipped(
        override val rule: Rule,
        public val reason: String,
    ) : RuleEvaluation
}

/** The details of a firing, handed to each action. */
public data class RuleOutcome(
    public val rule: Rule,
    public val subject: PlayerId?,
    /** Progress after the firing. */
    public val progress: Int,
    /** Which tier was crossed, or null for a rule without tiers. */
    public val tier: Int? = null,
    /** How many times this rule has now fired for this subject. */
    public val firings: Int,
    public val event: SidequestEvent,
    public val game: GameContext,
    public val item: SqItem? = null,
    public val location: SqLocation? = null,
    public val timestampMillis: Long,
) {

    /**
     * Substitutes `{tier}`, `{progress}` and `{firings}` in a template.
     *
     * So one action can serve every tier of a rule. Without it, a rule with three tiers needs three notify
     * actions that differ only in a number, which is the duplication tiers exist to remove.
     */
    public fun format(template: String): String = template
        .replace("{tier}", tier?.toString() ?: "")
        .replace("{progress}", progress.toString())
        .replace("{firings}", firings.toString())
        .replace("{rule}", rule.displayName.ifEmpty { rule.id.value })
}

/** Per-subject state for one rule. Persisted, so progress survives a restart. */
@Serializable
public data class RuleProgress(
    public val progress: Int = 0,
    public val firings: Int = 0,
    public val lastFiredAtMillis: Long? = null,
    /** Tiers already awarded, so a reset that lowers progress does not re-award them. */
    public val awardedTiers: Set<Int> = emptySet(),
    /** When progress was last reset, for [RuleReset.DAILY]. */
    public val resetAtMillis: Long? = null,
)

/** Everything the engine has stored. One repository, so a rule's state cannot half-save. */
@Serializable
public data class RuleStore(
    /** Rule id, then subject id — or `""` for a rule about nobody in particular. */
    public val byRule: Map<String, Map<String, RuleProgress>> = emptyMap(),
)

/**
 * The one place a rule is evaluated.
 *
 * Rules are registered, not hard-coded, so a feature contributes rules the same way it contributes chat
 * patterns — and a rule from the backend arrives through the same door.
 */
public interface RuleEngine {

    public fun register(rule: Rule): Boolean

    public fun unregister(ruleId: SqId): Boolean

    /** Every rule, hidden ones included. For a debug listing. */
    public fun rules(): List<Rule>

    /** Registers a handler for an action kind. Later registrations replace earlier ones. */
    public fun handle(kind: String, handler: ActionHandler)

    /**
     * Evaluates a rule against an event and returns what happened.
     *
     * Exposed for the plan's "manual admin trigger" and "testing": a rule that only ever runs from the event
     * bus can only be tested by producing the event, and producing a rare drop on demand is not something a
     * developer can do.
     */
    public fun evaluate(ruleId: SqId, event: SidequestEvent, subject: PlayerId? = null): RuleEvaluation?

    /**
     * Progress on a rule, for a subject.
     *
     * A null [subject] means *the subject the engine would have picked itself* — the local player — and not
     * "nobody in particular". The distinction cost a real bug: an event's progress is filed under the local
     * player, so a caller asking for the progress without naming anybody got zero and read it as a rule that
     * never fired. One meaning of the default, everywhere, is what stops that.
     */
    public fun progressOf(ruleId: SqId, subject: PlayerId? = null): RuleProgress

    /** Resets a rule's state for one subject — the local player when [subject] is null, as above. */
    public fun reset(ruleId: SqId, subject: PlayerId? = null)

    /**
     * Resets a rule for every subject.
     *
     * Its own method rather than `reset(id, null)`, because that spelling already means the local player and a
     * default that means two things is the bug this pair exists to avoid.
     */
    public fun resetEverySubject(ruleId: SqId)

    /** The most recent evaluations, newest first. Bounded. The whole of the debugging story. */
    public fun trace(): List<RuleEvaluation>
}

/** A rule fired. Posted so anything can observe progression without knowing which rule produced it. */
public class RuleFiredEvent(public val outcome: RuleOutcome) : SidequestEvent() {
    override fun describe(): String =
        "${outcome.rule.id} fired" + (outcome.tier?.let { " (tier $it)" } ?: "") + " at ${outcome.progress}"
}
