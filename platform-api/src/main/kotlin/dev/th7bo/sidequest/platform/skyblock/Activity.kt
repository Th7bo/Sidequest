package dev.th7bo.sidequest.platform.skyblock

import dev.th7bo.sidequest.platform.event.SidequestEvent
import kotlinx.serialization.Serializable

/**
 * What the player is doing, at the level a feature cares about.
 *
 * Coarse on purpose. The useful questions are "is this a bad moment to interrupt", "what should
 * the HUD show", "what is worth recording" — and all three are answered by a dozen categories.
 * A hundred would be a taxonomy nobody maintains.
 *
 * [UNKNOWN] is a real answer, and the plan says so: detection falls back to it rather than
 * inventing state. A feature that needs certainty checks the confidence.
 */
@Serializable
public enum class Activity(public val displayName: String) {
    DUNGEONS("Dungeons"),
    KUUDRA("Kuudra"),
    MINING("Mining"),
    GARDEN("Garden"),
    FARMING("Farming"),
    FISHING("Fishing"),
    FORAGING("Foraging"),
    SLAYER("Slayer"),
    RIFT("Rift"),
    CRIMSON_QUESTING("Crimson Isle"),
    FORGE("Forge"),
    AUCTION("Auction House"),

    /** Moving around with nothing else going on. The normal state. */
    EXPLORING("Exploring"),

    /** On an island but doing nothing detectable — usually AFK on a private island. */
    IDLE("Idle"),

    UNKNOWN("Unknown"),
    ;

    /**
     * Whether this is something a player would mind being interrupted during.
     *
     * Distinct from [Island.isHazardous]: standing in the Crimson Isle is not hazardous, and being
     * mid-Kuudra is. Read by the cinematic and notification policies.
     */
    public val isDemanding: Boolean get() = this in DEMANDING

    public companion object {
        private val DEMANDING = setOf(DUNGEONS, KUUDRA, SLAYER)
    }
}

/**
 * What the player is doing, and how sure we are.
 *
 * [reason] is not decoration. Activity detection is a pile of heuristics over widgets and area
 * names, and when it is wrong the only useful debugging question is "what made you think that" —
 * so the answer is carried with the verdict rather than reconstructed from a log.
 */
@Serializable
public data class ActivityReading(
    public val activity: Activity = Activity.UNKNOWN,
    public val confidence: ContextConfidence = ContextConfidence.NONE,
    /** The signal that decided it, in a few words. */
    public val reason: String = "",
) {
    /** Whether this is worth acting on rather than merely displaying. */
    public val isReliable: Boolean get() = confidence >= ContextConfidence.PROBABLE

    override fun toString(): String = "${activity.displayName} ($confidence: $reason)"

    public companion object {
        public val Unknown: ActivityReading = ActivityReading()
    }
}

/** The activity changed. */
public class ActivityChangedEvent(
    public val previous: ActivityReading,
    public val reading: ActivityReading,
    override val context: GameContext,
) : ContextEvent() {
    override fun describe(): String = "${previous.activity.displayName} -> $reading"
}
