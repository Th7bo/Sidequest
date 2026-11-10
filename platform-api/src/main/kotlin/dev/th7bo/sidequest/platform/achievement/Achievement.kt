package dev.th7bo.sidequest.platform.achievement

import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.player.PlayerId
import kotlinx.serialization.Serializable

/**
 * What kind of achievement something is.
 *
 * The plan's categories, kept as an enum rather than free text so a screen can group by
 * them and a backend can validate one that arrives. [JOKE] and [SECRET] are the reason
 * the rest exist: a friend group's progression is mostly an excuse for them.
 */
@Serializable
public enum class AchievementCategory(public val displayName: String) {
    PROGRESSION("Progression"),
    FRIEND_GROUP("Friend group"),
    FAILURE("Failure"),
    COMPETITIVE("Competitive"),
    SECRET("Secret"),
    SEASONAL("Seasonal"),
    JOKE("Joke"),
}

/**
 * What earning one hands over.
 *
 * All four slots at once, because "an achievement worth coins *and* a title" is a real thing
 * and modelling the reward as a sealed choice would make that two achievements. Zero fields
 * is the common case — most achievements are their own reward, which is what [hasReward]
 * exists to ask.
 *
 * The currency amount is data here and a transaction somewhere else: this type is a claim
 * about what *should* be granted, and whoever applies it decides whether it can be. That
 * keeps the achievement service from knowing the currency service exists.
 */
public data class AchievementReward(
    public val currency: Long = 0,
    public val cosmeticId: SqId? = null,
    public val titleId: SqId? = null,
) {

    /** Whether anything at all is handed over. */
    public val hasReward: Boolean
        get() = currency > 0 || cosmeticId != null || titleId != null
}

/**
 * One thing that can be earned, once.
 *
 * An achievement is **presentation**, not machinery. The machinery — watching for events,
 * checking conditions, counting progress across tiers — belongs to the rule engine, and an
 * achievement names the rule it rides on with [ruleId] so a progress bar has something to
 * read. Modelling achievements as rules of their own would mean two engines answering one
 * question ("did you do the thing") and drifting the way duplicated conditions always do.
 *
 * Unlocking goes through the rule engine's `achievement` action, through a manual admin
 * trigger, or straight from a feature that knows something no rule could observe. Whichever
 * door it comes in through, the state lives in one place.
 */
public data class Achievement(
    public val id: SqId,
    /** Shown when it unlocks and in the listing. */
    public val displayName: String,
    public val description: String = "",
    public val category: AchievementCategory = AchievementCategory.PROGRESSION,

    /**
     * Whether the player is told this exists before they have earned it.
     *
     * A hidden achievement is left out of a listing until it is unlocked, and then it is
     * listed like any other — nobody wants a trophy cabinet with holes in it. This mirrors
     * the rule engine's own hidden flag, which governs the same question for the machinery.
     */
    public val isHidden: Boolean = false,

    /**
     * The rule whose progress this displays, or null for one unlocked outright.
     *
     * Read by whatever draws progress; the service never asks the engine anything. Keeping
     * the join on the caller's side is what stops the service importing the engine, and an
     * import like that is how two subsystems become one knot.
     */
    public val ruleId: SqId? = null,

    public val reward: AchievementReward = AchievementReward(),
) {
    override fun toString(): String = id.value
}

/** When an achievement was earned. Persisted; the definition is not. */
@Serializable
public data class AchievementRecord(
    public val unlockedAtMillis: Long,
)

/**
 * Everything the service has stored.
 *
 * Keyed by achievement id, then subject id — `""` for a record about nobody in particular,
 * the same convention as [dev.th7bo.sidequest.platform.rule.RuleStore]. Most records are
 * about the local player, but "who in the group has this" is the same map with more keys,
 * and building it for one player means rebuilding it later.
 */
@Serializable
public data class AchievementStore(
    public val byAchievement: Map<String, Map<String, AchievementRecord>> = emptyMap(),
) {

    /** Every subject who has earned [achievementId]. */
    public fun earnersOf(achievementId: SqId): Set<String> =
        byAchievement[achievementId.value]?.keys?.filterNot { it.isEmpty() }?.toSet() ?: emptySet()
}

/** How an achievement was earned. Recorded so history can say more than "unlocked". */
@Serializable
public enum class AchievementSource {
    /** A rule fired and its actions named this achievement. */
    RULE,

    /** A developer command or a manual admin trigger. */
    MANUAL,
}

/** The service behind every achievement: definitions in, unlock records out. */
public interface AchievementService {

    /** Registers a definition. Returns false for one already registered under the same id. */
    public fun register(definition: Achievement): Boolean

    public fun unregister(id: SqId): Boolean

    /**
     * Every registered definition, hidden ones included only if asked *or* already earned.
     *
     * Hidden-and-unearned is the pair a listing must not show; hidden-but-earned belongs in
     * the cabinet, which is why the filter reads the store and not just the flag.
     */
    public fun all(subject: PlayerId? = null, includeHidden: Boolean = false): List<Achievement>

    public fun definitionOf(id: SqId): Achievement?

    /** When [id] was earned by [subject], or null. Null means the local player, as everywhere else. */
    public fun recordOf(id: SqId, subject: PlayerId? = null): AchievementRecord?

    public fun isUnlocked(id: SqId, subject: PlayerId? = null): Boolean

    /** Everything [subject] has earned, unlocked first. Empty for somebody with nothing. */
    public fun unlocked(subject: PlayerId? = null): List<Achievement>

    /**
     * Earns [id] for [subject], returning false when there is nothing to do.
     *
     * False covers three different answers — unknown id, already earned, and (for a manual
     * call) nothing at all — and callers who need to tell them apart should ask the state
     * methods first. Unlocking twice is a normal race, not an error: two rules naming one
     * achievement both fire, and the second one losing quietly is correct.
     */
    public fun unlock(
        id: SqId,
        source: AchievementSource = AchievementSource.RULE,
        subject: PlayerId? = null,
    ): Boolean

    /** Restores persisted records. Definitions are re-registered by whoever owns them. */
    public fun load(store: AchievementStore)

    /** Called after every change, so the caller can persist. Set by whoever owns the storage. */
    public var onStoreChanged: ((AchievementStore) -> Unit)?
}

/** An achievement was earned. Posted so presentation can react without knowing which rule produced it. */
public class AchievementUnlockedEvent(
    public val achievementId: SqId,
    public val displayName: String,
    public val category: AchievementCategory,
    public val source: AchievementSource,
    /** The local player for most unlocks, hence nullable like every other subject. */
    public val subject: PlayerId?,
    public val atMillis: Long,
) : SidequestEvent() {
    override fun describe(): String = "$achievementId ($category)"
}
