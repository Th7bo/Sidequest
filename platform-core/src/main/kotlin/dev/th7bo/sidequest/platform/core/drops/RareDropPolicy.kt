package dev.th7bo.sidequest.platform.core.drops

import dev.th7bo.sidequest.platform.chat.DropRarity
import dev.th7bo.sidequest.platform.chat.TrophyTier
import dev.th7bo.sidequest.platform.skyblock.Island
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What a drop is worth showing for.
 *
 * Stored globally, because an ignore list is about what a person finds interesting rather than about an
 * account or a profile — somebody tired of Enchanted Hay Bale is tired of it on their alt too.
 */
@Serializable
public data class RareDropSettings(
    public val isEnabled: Boolean = true,
    /**
     * Below this tier, nothing plays.
     *
     * The most useful single control, and the reason the feature is bearable at all: most drops Hypixel
     * calls rare are not worth stopping for, and a mod that animates every one of them gets switched off
     * within an hour of farming.
     */
    public val minimumRarity: DropRarity = DropRarity.RARE,
    public val durationMillis: Long = DEFAULT_DURATION_MILLIS,
    /**
     * Minecraft's own totem animation instead of a cinematic.
     *
     * Kept as an option because it is *familiar*: some people would rather have the thing the game already
     * does than something the mod invented, and it does not cover the screen.
     */
    public val useTotemAnimation: Boolean = false,
    public val playsSound: Boolean = true,
    /** Exact item names never to announce. Matched case-insensitively against the cleaned name. */
    public val ignoredItems: Set<String> = emptySet(),
    /** Islands where nothing plays. For places where drops are constant and the animation becomes noise. */
    public val ignoredIslands: Set<Island> = emptySet(),
    /** Whether a screenshot is taken. Off by default: writing a file per drop is somebody's disk. */
    public val takesScreenshot: Boolean = false,
) {
    public val duration: Duration get() = durationMillis.milliseconds

    public fun ignores(itemName: String): Boolean =
        ignoredItems.any { it.equals(itemName.trim(), ignoreCase = true) }

    public companion object {
        public const val DEFAULT_DURATION_MILLIS: Long = 4_000
    }
}

/**
 * Why a drop is not being announced, or that it is.
 *
 * A reason rather than a boolean, for the argument this codebase keeps making: an animation that does not
 * play looks exactly like a broken feature, and this is the sentence that says which of somebody's own
 * settings did it. `/sqdrops` prints it.
 */
public sealed interface DropDecision {

    public data object Announce : DropDecision

    public data class Skip(public val reason: String) : DropDecision

    public val isAnnounced: Boolean get() = this is Announce
}

/**
 * Decides whether a drop is worth interrupting somebody for.
 *
 * Pure, and in this module rather than in the mod, **because it is the only part of the feature worth
 * testing**. Everything else there is wiring — submit a cinematic, post a toast, play a sound — and each of
 * those is already covered where it lives. This is the part with branches, and the mod module has no test
 * source set, so leaving it there would have made it untestable. The world projector taught that lesson at
 * some cost.
 */
public object RareDropPolicy {

    public fun decide(
        settings: RareDropSettings,
        itemName: String,
        rarity: DropRarity,
        island: Island,
    ): DropDecision = when {
        !settings.isEnabled -> DropDecision.Skip("the feature is switched off")

        settings.ignores(itemName) -> DropDecision.Skip("'$itemName' is on the ignore list")

        island in settings.ignoredIslands -> DropDecision.Skip("${island.displayName} is ignored")

        // A pet is exempt from the threshold on purpose. `PET` sits at the end of the rarity enum because it
        // is a *kind* rather than a tier — Hypixel announces pets on their own line and says nothing about
        // how rare one is — so comparing it against a threshold would either announce every pet or none,
        // depending on where somebody set the bar, and neither is what they meant.
        rarity == DropRarity.PET -> DropDecision.Announce

        rarity.ordinal < settings.minimumRarity.ordinal ->
            DropDecision.Skip("$rarity is below the ${settings.minimumRarity} threshold")

        else -> DropDecision.Announce
    }

    /**
     * A trophy catch, graded on the drop ladder.
     *
     * A diamond trophy deserves as much noise as an insane rare drop and a bronze one deserves none, so the
     * tier is mapped across rather than every trophy being announced identically — which would make the
     * threshold useless for fishing, the activity that produces the most of them.
     */
    public fun rarityOf(tier: TrophyTier): DropRarity = when (tier) {
        TrophyTier.BRONZE -> DropRarity.RARE
        TrophyTier.SILVER -> DropRarity.VERY_RARE
        TrophyTier.GOLD -> DropRarity.CRAZY_RARE
        TrophyTier.DIAMOND -> DropRarity.INSANE_RARE
    }

    /** Hypixel's own wording for each tier, so the headline reads as the game's rather than as the mod's. */
    public fun headlineFor(rarity: DropRarity): String = when (rarity) {
        DropRarity.RARE -> "RARE DROP"
        DropRarity.VERY_RARE -> "VERY RARE DROP"
        DropRarity.CRAZY_RARE -> "CRAZY RARE DROP"
        DropRarity.INSANE_RARE -> "INSANE DROP"
        DropRarity.PRAY_TO_RNGESUS -> "PRAY TO RNGESUS"
        DropRarity.PET -> "PET DROP"
    }

    /** And their colours, for the same reason. */
    public fun colourFor(rarity: DropRarity): Int = when (rarity) {
        DropRarity.RARE -> 0x55FFFF
        DropRarity.VERY_RARE -> 0x5555FF
        DropRarity.CRAZY_RARE -> 0xFF55FF
        DropRarity.INSANE_RARE -> 0xFFAA00
        DropRarity.PRAY_TO_RNGESUS -> 0xAA00AA
        DropRarity.PET -> 0xFFAA00
    }
}
