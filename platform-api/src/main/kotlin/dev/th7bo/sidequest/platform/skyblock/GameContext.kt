package dev.th7bo.sidequest.platform.skyblock

import dev.th7bo.sidequest.platform.event.SidequestEvent
import kotlinx.serialization.Serializable

/**
 * Where the player is and what they are doing, as one value.
 *
 * The single authority. Features read this instead of each deciding for themselves what
 * "in a dungeon" means — which is how a mod ends up with four slightly different answers
 * to the same question, three of them wrong in different situations.
 *
 * A snapshot, so a feature reading several fields sees one consistent moment rather than
 * a location from before a server hop and a profile from after it.
 */
@Serializable
public data class GameContext(
    public val isOnHypixel: Boolean = false,
    public val isInSkyBlock: Boolean = false,
    public val island: Island = Island.NONE,
    public val subLocation: SubLocation = SubLocation.Unknown,
    public val serverId: ServerId = ServerId.Unknown,
    public val profile: SkyBlockProfile = SkyBlockProfile.Unknown,
    /** Visiting someone else's island. */
    public val isGuest: Boolean = false,
    /**
     * Dungeon floor as Hypixel words it — `F7`, `M3`, `E` — or null when not in one.
     *
     * A string rather than a number because `Entrance` is not one, and converting loses it.
     */
    public val dungeonFloor: String? = null,
    /** Kuudra tier, or null when not in Kuudra. */
    public val kuudraTier: Int? = null,
    /** The profile's game mode, when the scoreboard has named one. */
    public val profileType: ProfileType = ProfileType.NORMAL,
    /** How much of the above is believed, and why. */
    public val confidence: ContextConfidence = ContextConfidence.NONE,
) {

    /**
     * Whether it is a bad moment to interrupt the player.
     *
     * The one question the cinematic and notification policies ask. Kept here so there is
     * a single place to be wrong about it, rather than a condition copied into every
     * feature that shows something.
     */
    public val isBusy: Boolean get() = island.isBusy

    /** Whether interrupting could cost the player something. Stricter than [isBusy]. */
    public val isHazardous: Boolean get() = island.isHazardous

    /** In a dungeon run. Distinct from standing in the Dungeon Hub. */
    public val isInDungeon: Boolean get() = dungeonFloor != null

    /** In a Kuudra fight. */
    public val isInKuudra: Boolean get() = kuudraTier != null

    /**
     * Whether the context is trustworthy enough to act on.
     *
     * Features that *do* something — play a sound, capture a screenshot, send a message —
     * should check this. Features that merely display can read a guess.
     */
    public val isReliable: Boolean get() = confidence >= ContextConfidence.PROBABLE

    public companion object {
        /** Not on Hypixel, or nothing known yet. */
        public val None: GameContext = GameContext()
    }
}

/**
 * How much the context is believed.
 *
 * The plan asks for confidence levels and a fallback to unknown rather than invented
 * state, and this is that. Detection sources disagree in normal operation — the tab list
 * lags a server hop by a second or two, the scoreboard reloads a frame late — and a
 * feature that acts on the disagreement does the wrong thing. Saying "I am not sure yet"
 * is a supported answer.
 */
@Serializable
public enum class ContextConfidence {
    /** Nothing is known. Not on Hypixel, or the session has only just started. */
    NONE,

    /**
     * One source said something and nothing has confirmed it.
     *
     * Typically the moment after a server hop, when the scoreboard has updated and the
     * tab list has not. Display it, do not act on it.
     */
    GUESSED,

    /** A source that names the island directly, uncorroborated. */
    PROBABLE,

    /** Independent sources agree. */
    CONFIRMED,
    ;
}

/**
 * The authority on [GameContext].
 *
 * Reading is cheap and allocation-free; the value is recomputed only when a source
 * changes, not per call, because features read it on every tick.
 */
public interface GameContextService {

    /** The current context. Never null; unknown state is represented, not absent. */
    public val context: GameContext

    /** Convenience for the most-asked question. */
    public val isInSkyBlock: Boolean get() = context.isInSkyBlock

    /** Convenience for the second-most-asked. */
    public val island: Island get() = context.island

    /**
     * The boards as last read, and what was made of them.
     *
     * The parser debug output the plan asks for, and the first thing to look at when the
     * context is wrong. Raw lines included: a line that looks right and does not match
     * almost always has an invisible character or a formatting code where nobody expected
     * one, and only the raw form shows that.
     *
     * A list of lines rather than one string so a caller can print it to chat, to a log, or
     * into an inspector panel without re-splitting it.
     */
    public fun describeSources(): List<String>
}

// -- events ---------------------------------------------------------------

/** Base for everything the context service announces. */
public sealed class ContextEvent : SidequestEvent() {
    public abstract val context: GameContext
}

/** Entered SkyBlock. Fires once per session, not per island. */
public class SkyBlockJoinEvent(override val context: GameContext) : ContextEvent() {
    override fun describe(): String = "joined SkyBlock on ${context.island.displayName}"
}

/** Left SkyBlock, by going to a lobby, another game, or disconnecting. */
public class SkyBlockLeaveEvent(override val context: GameContext) : ContextEvent()

/**
 * The island changed.
 *
 * Fires for a real change only. A server hop within the same island produces a
 * [ServerChangedEvent] instead, which is the distinction that matters to anything caching
 * per-instance state.
 */
public class IslandChangedEvent(
    public val previous: Island,
    public val island: Island,
    override val context: GameContext,
) : ContextEvent() {
    override fun describe(): String = "${previous.displayName} -> ${island.displayName}"
}

/** The server instance changed, island possibly unchanged. */
public class ServerChangedEvent(
    public val previous: ServerId,
    public val serverId: ServerId,
    override val context: GameContext,
) : ContextEvent() {
    override fun describe(): String = "$previous -> $serverId"
}

/** The SkyBlock profile changed. */
public class ProfileChangedEvent(
    public val previous: SkyBlockProfile,
    public val profile: SkyBlockProfile,
    override val context: GameContext,
) : ContextEvent() {
    override fun describe(): String = "$previous -> $profile"
}

/** The area within an island changed. Far more frequent than the others. */
public class SubLocationChangedEvent(
    public val previous: SubLocation,
    public val subLocation: SubLocation,
    override val context: GameContext,
) : ContextEvent() {
    override fun describe(): String = "$previous -> $subLocation"
}
