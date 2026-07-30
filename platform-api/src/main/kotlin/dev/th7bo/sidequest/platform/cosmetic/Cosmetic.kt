package dev.th7bo.sidequest.platform.cosmetic

import dev.th7bo.sidequest.platform.asset.AssetId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.skyblock.GameContext
import dev.th7bo.sidequest.platform.skyblock.Island
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Where a cosmetic goes.
 *
 * The two flags on each slot carry more weight than the list does, because they are what the viewer's
 * preferences act on. A person who turns off particles means every particle slot, not the four they happen to
 * know the names of, so "is this a particle effect" has to be a property of the slot rather than a list kept
 * somewhere else that will fall out of date the first time a slot is added.
 */
@Serializable
public enum class CosmeticSlot(
    public val displayName: String,
    /**
     * Whether this is drawn *on a player*, as opposed to changing the viewer's own client.
     *
     * The distinction decides whether visibility is even a question. A notification style is about how your
     * own toasts look; nobody else can see it, so "who may see this" is meaningless for it — and treating the
     * two kinds the same is how a setting ends up hiding your own interface because somebody is not a friend.
     */
    public val isWorn: Boolean,
    /** Particle-like, and therefore covered by "hide particles". */
    public val isEffect: Boolean = false,
    /** Replaces how a player looks, and therefore covered by "hide skin overrides". */
    public val isAppearanceOverride: Boolean = false,
) {
    SKIN("Skin", isWorn = true, isAppearanceOverride = true),
    CAPE("Cape", isWorn = true, isAppearanceOverride = true),
    NAMETAG_PREFIX("Nametag prefix", isWorn = true),
    NAMETAG_SUFFIX("Nametag suffix", isWorn = true),
    TITLE("Title", isWorn = true),
    BADGE("Badge", isWorn = true),
    PARTICLE_TRAIL("Particle trail", isWorn = true, isEffect = true),
    AURA("Aura", isWorn = true, isEffect = true),
    JOIN_EFFECT("Join effect", isWorn = true, isEffect = true),
    DEATH_EFFECT("Death effect", isWorn = true, isEffect = true),
    PROFILE_BORDER("Profile border", isWorn = true),

    // The personal ones. These change the viewer's own client and are never rendered on anybody else.
    NOTIFICATION_STYLE("Notification style", isWorn = false),
    CINEMATIC_STYLE("Cinematic style", isWorn = false),
    SOUND_PACK("Sound pack", isWorn = false),
    ;

    /** Whether wearing two of these at once is meaningful. Nothing today, but the check reads better named. */
    public val isExclusive: Boolean get() = true
}

@Serializable
public enum class CosmeticRarity(public val displayName: String, public val colour: Int) {
    COMMON("Common", 0xAAAAAA),
    UNCOMMON("Uncommon", 0x55FF55),
    RARE("Rare", 0x5555FF),
    EPIC("Epic", 0xAA00AA),
    LEGENDARY("Legendary", 0xFFAA00),
    MYTHIC("Mythic", 0xFF55FF),
}

/** Where a cosmetic came from. Shown in the wardrobe, and the answer to "how did they get that". */
@Serializable
public enum class UnlockSource(public val displayName: String) {
    DEFAULT("Available to everyone"),
    ACHIEVEMENT("Achievement"),
    PURCHASE("Bought with Sidequest currency"),
    GIFT("Given by a friend"),
    EVENT("Limited event"),
    REPUTATION("Reputation trait"),

    /** Handed out by an operator. Deliberately named, so an unearned cosmetic is never mistaken for an earned one. */
    GRANTED("Granted"),
}

/**
 * Who may see a cosmetic on the wearer.
 *
 * The wearer's half of the answer. The viewer has a half too, in [CosmeticSettings], and **the viewer's half
 * always wins**. Somebody who has turned off particles does not see particles, whatever anybody else has
 * chosen — a mod where another person's preference can override yours about your own screen is a mod that gets
 * uninstalled the first time somebody thinks it is funny.
 */
@Serializable
public enum class CosmeticVisibility(public val displayName: String) {
    /** Only the wearer sees it. For trying something on. */
    LOCAL_ONLY("Only me"),
    FRIENDS("Friends"),
    EVERYONE("Everyone"),
}

/**
 * Whether a cosmetic applies right now.
 *
 * Its own small tree rather than the rule engine's [dev.th7bo.sidequest.platform.rule.Condition], and the
 * reason is not tidiness: a rule's condition is about *one* subject and a cosmetic's is inherently about a
 * **pair** — whose cosmetic it is and who is looking at it. `RuleContext` has one subject and an event, and a
 * cosmetic has two players and no event, so reusing it would mean inventing a synthetic event and a second
 * meaning for `subject`. Two small trees are better than one that lies about what it holds.
 */
@Serializable
public sealed interface CosmeticCondition {

    public fun test(context: CosmeticContext): Boolean

    /** Why it did or did not hold. Written about the actual state, so a log line explains rather than restates. */
    public fun explain(context: CosmeticContext): String

    @Serializable
    public data object Always : CosmeticCondition {
        override fun test(context: CosmeticContext): Boolean = true
        override fun explain(context: CosmeticContext): String = "no conditions"
    }

    @Serializable
    public data class All(public val conditions: List<CosmeticCondition>) : CosmeticCondition {
        override fun test(context: CosmeticContext): Boolean = conditions.all { it.test(context) }
        override fun explain(context: CosmeticContext): String =
            conditions.firstOrNull { !it.test(context) }?.explain(context) ?: "all conditions held"
    }

    @Serializable
    public data class Any(public val conditions: List<CosmeticCondition>) : CosmeticCondition {
        override fun test(context: CosmeticContext): Boolean = conditions.any { it.test(context) }
        override fun explain(context: CosmeticContext): String =
            if (test(context)) "an alternative held" else "none of ${conditions.size} alternatives held"
    }

    @Serializable
    public data class Not(public val condition: CosmeticCondition) : CosmeticCondition {
        override fun test(context: CosmeticContext): Boolean = !condition.test(context)
        override fun explain(context: CosmeticContext): String = "not (${condition.explain(context)})"
    }

    /** For a cosmetic that only makes sense somewhere: a dungeon aura, a garden trail. */
    @Serializable
    public data class OnIsland(public val islands: Set<Island>) : CosmeticCondition {
        override fun test(context: CosmeticContext): Boolean = context.game.island in islands
        override fun explain(context: CosmeticContext): String =
            "on ${context.game.island}, wanted ${islands.joinToString()}"
    }

    /** The wearer and the viewer are the same person. For a cosmetic only its owner is meant to see. */
    @Serializable
    public data object ViewerIsWearer : CosmeticCondition {
        override fun test(context: CosmeticContext): Boolean = context.viewer == context.wearer
        override fun explain(context: CosmeticContext): String =
            if (test(context)) "you are the wearer" else "somebody else is wearing it"
    }

    @Serializable
    public data object InParty : CosmeticCondition {
        override fun test(context: CosmeticContext): Boolean = context.isInParty
        override fun explain(context: CosmeticContext): String =
            if (context.isInParty) "in a party" else "not in a party"
    }

    public companion object {
        public fun all(vararg conditions: CosmeticCondition): CosmeticCondition = All(conditions.toList())
        public fun any(vararg conditions: CosmeticCondition): CosmeticCondition = Any(conditions.toList())
    }
}

/** What a cosmetic condition can see. Two players, and where they are. */
public interface CosmeticContext {

    /** Whose cosmetic this is. */
    public val wearer: PlayerId

    /** Who is looking. The local player, always — this client only ever renders its own view. */
    public val viewer: PlayerId?

    public val game: GameContext

    public val isInParty: Boolean

    /** Whether the wearer is on the viewer's custom friend list. */
    public val isFriend: Boolean

    public val nowMillis: Long
}

/**
 * One cosmetic.
 *
 * Data rather than code, so it can arrive from the backend, be inspected before it is trusted, and be shown in
 * a wardrobe without the client needing to know what it is in advance.
 */
@Serializable
public data class Cosmetic(
    public val id: SqId,
    public val slot: CosmeticSlot,
    public val displayName: String,
    /**
     * Who made or owns this definition.
     *
     * Not who is wearing it — that is a loadout. This is for a group where somebody made a badge and it is
     * worth being able to say so.
     */
    public val owner: PlayerId? = null,
    /**
     * The asset that draws it, if it has one.
     *
     * Null for a cosmetic that is pure text — a title, a nametag prefix — which is most of the cheap ones and
     * the reason this is nullable rather than every title needing a PNG uploaded.
     */
    public val assetId: AssetId? = null,
    /** Text content, for the slots that are text. */
    public val text: String? = null,
    public val rarity: CosmeticRarity = CosmeticRarity.COMMON,
    public val unlockSource: UnlockSource = UnlockSource.DEFAULT,
    public val visibility: CosmeticVisibility = CosmeticVisibility.EVERYONE,
    /**
     * How long it lasts once equipped. Null for permanent.
     *
     * Measured from when it was equipped, which is held on the loadout rather than here — the definition is
     * shared by everybody wearing it and cannot carry one person's clock.
     */
    public val duration: Duration? = null,
    /**
     * Other cosmetics that cannot be worn at the same time.
     *
     * Beyond the implicit one-per-slot rule, which needs no declaration. This is for the cross-slot cases: an
     * aura that looks wrong over a particular cape.
     */
    public val conflicts: Set<SqId> = emptySet(),
    public val condition: CosmeticCondition = CosmeticCondition.Always,
    /**
     * Draw order within a slot, and the tie-break when two cosmetics conflict.
     *
     * Higher wins. Deterministic on purpose: two clients resolving the same conflict differently would show
     * two people different things and neither could tell which was right.
     */
    public val renderLayer: Int = 0,
    /** What to show instead when this one cannot be — a missing asset, usually. */
    public val fallbackId: SqId? = null,
    /**
     * A cosmetic meant to be funny.
     *
     * Its own flag because "hide joke cosmetics" is a real setting people want: the group's sense of humour is
     * not everybody's, and the alternative to this flag is turning cosmetics off entirely.
     */
    public val isJoke: Boolean = false,
    /** Whether it moves. Covered by reduced-animation mode. */
    public val isAnimated: Boolean = false,
) {
    init {
        require(displayName.isNotBlank()) { "A cosmetic needs a name: $id" }
        require(id !in conflicts) { "$id cannot conflict with itself" }
        require(id != fallbackId) { "$id cannot fall back to itself" }
    }

    /** Whether this needs an asset that might not have arrived. */
    public val needsAsset: Boolean get() = assetId != null
}

/**
 * What somebody is wearing.
 *
 * One cosmetic per slot, which is why this is a map rather than a list — the exclusivity is in the shape rather
 * than in a check that has to be remembered.
 */
@Serializable
public data class CosmeticLoadout(
    public val equipped: Map<CosmeticSlot, EquippedCosmetic> = emptyMap(),
) {
    public operator fun get(slot: CosmeticSlot): EquippedCosmetic? = equipped[slot]

    public fun with(slot: CosmeticSlot, entry: EquippedCosmetic): CosmeticLoadout =
        copy(equipped = equipped + (slot to entry))

    public fun without(slot: CosmeticSlot): CosmeticLoadout = copy(equipped = equipped - slot)

    public val isEmpty: Boolean get() = equipped.isEmpty()

    public companion object {
        public val Empty: CosmeticLoadout = CosmeticLoadout()
    }
}

/** A cosmetic in a slot, with the clock that a timed one runs on. */
@Serializable
public data class EquippedCosmetic(
    public val cosmeticId: SqId,
    /** When it went on. Zero means "unknown", which a timed cosmetic treats as never expiring rather than as expired. */
    public val equippedAtMillis: Long = 0,
)

/**
 * What the viewer will put up with.
 *
 * **Every one of these beats the wearer's own choice.** That is the whole design of this type: a cosmetic
 * system where somebody else's setting decides what appears on your screen is one that gets turned off wholesale
 * the first time a friend finds something annoying. Giving people the specific switch means they use it instead
 * of [isEnabled].
 */
@Serializable
public data class CosmeticSettings(
    /** The master switch. False means nothing at all, including your own. */
    public val isEnabled: Boolean = true,
    /** Whether other people's skins and capes are replaced for you. */
    public val showAppearanceOverrides: Boolean = true,
    public val showEffects: Boolean = true,
    public val showJokeCosmetics: Boolean = true,
    /**
     * Stops animated cosmetics from animating rather than hiding them.
     *
     * An accessibility setting first and a performance one second. Hiding them instead would be the easier
     * implementation and the wrong one — somebody who cannot tolerate motion still wants to know what people
     * are wearing.
     */
    public val reducedAnimation: Boolean = false,
    /** Slots switched off individually. */
    public val hiddenSlots: Set<CosmeticSlot> = emptySet(),
    /** People whose cosmetics are switched off individually. */
    public val hiddenPlayers: Set<PlayerId> = emptySet(),
) {
    public companion object {
        public val Default: CosmeticSettings = CosmeticSettings()

        /** Nothing renders. For the master switch and for a screenshot. */
        public val AllOff: CosmeticSettings = CosmeticSettings(isEnabled = false)
    }
}
