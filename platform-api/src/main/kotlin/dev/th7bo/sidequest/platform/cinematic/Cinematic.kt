package dev.th7bo.sidequest.platform.cinematic

import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.item.SqItem
import dev.th7bo.sidequest.platform.player.PlayerId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One thing worth stopping the game for.
 *
 * A rare drop, a completed contract, an achievement nobody expected. The plan asks for **one** reusable
 * cinematic runtime rather than a bespoke animation per feature, and the reason is the part that is easy to
 * miss: the hard problem here is not the drawing, it is deciding *not* to draw. A full-screen animation during
 * a Kuudra run is the mod getting somebody killed, and every feature that rolled its own would have to get
 * that right independently.
 *
 * So a cinematic is data: a list of components and a policy for what to do when now is a bad moment. What it
 * looks like is the sink's business; whether it plays at all is [CinematicDirector]'s.
 */
public data class Cinematic(
    public val id: SqId,
    /** What it is about, for a recap and for a replay listing. */
    public val title: String = "",
    public val priority: CinematicPriority = CinematicPriority.NORMAL,
    /** Drawn in order. A component the sink cannot draw is skipped, not fatal — see [CinematicSink]. */
    public val components: List<CinematicComponent> = emptyList(),
    /** How long the whole thing runs. The sink may finish early; it may not run longer. */
    public val duration: Duration = DEFAULT_DURATION,
    /** What happens when the moment is unsafe. */
    public val policy: CinematicPolicy = CinematicPolicy.QUEUE,

    /**
     * How long a queued one stays worth showing.
     *
     * The plan asks for expiry and it is not a detail: a cinematic for a drop from a dungeon run two hours ago
     * is noise, and playing it because the queue happened to reach it is worse than never playing it.
     */
    public val expiry: Duration = DEFAULT_EXPIRY,

    /**
     * Cinematics with the same key collapse into one.
     *
     * Distinct from [dedupeKey]: grouping *merges* several genuine events into one showing that says how many,
     * while deduplication drops a repeat of the same event. Eleven drops in a dungeon should be one cinematic
     * saying eleven, not one saying the last.
     */
    public val groupingKey: String? = null,

    /** The same event arriving twice — a replayed realtime message, a duplicated chat line. */
    public val dedupeKey: String? = null,

    /** Who it is about. The local player for most, a friend for anything that arrived over the wire. */
    public val subject: PlayerId? = null,

    /**
     * Whether the player can cut it short.
     *
     * True by default, and the default is the important one. Something unskippable had better be worth it, and
     * almost nothing is.
     */
    public val isSkippable: Boolean = true,

    /** When it was created, for expiry. Filled in by the director when zero. */
    public val timestampMillis: Long = 0,
) {
    override fun toString(): String = "${id.value}${title.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}"

    public companion object {
        public val DEFAULT_DURATION: Duration = 4.seconds

        /** Ten minutes. Long enough to survive a dungeon run, short enough that nothing stale ever plays. */
        public val DEFAULT_EXPIRY: Duration = 10.minutes
    }
}

/**
 * How much a cinematic is worth interrupting for.
 *
 * Orders the queue, and decides what is dropped when it is full. Nothing here overrides the safety gate —
 * see [CinematicDirector] for why even `CRITICAL` waits.
 */
public enum class CinematicPriority {
    LOW,
    NORMAL,
    HIGH,

    /** Rare and worth remembering forever. A first clear, a one-in-a-million drop. */
    CRITICAL,
    ;

    public fun isAtLeast(other: CinematicPriority): Boolean = ordinal >= other.ordinal
}

/**
 * What to do when the moment is unsafe.
 *
 * The plan's event policies, as one closed set. Naming them is what lets a feature state its intent once
 * instead of re-deriving the whole decision at every call site — and it is why the policy lives on the
 * cinematic rather than in the director: only the feature knows whether its thing is worth queueing.
 */
public enum class CinematicPolicy {
    /**
     * Play it now regardless.
     *
     * Exists for completeness and should almost never be used. There is no cinematic worth a death, and the
     * director still refuses this one when the player is dead or not in a world — those are not judgement
     * calls, they are the absence of anywhere to draw.
     */
    SHOW_ANYWAY,

    /** Fall back to a notification. What most things should do: the player still learns it happened. */
    COMPACT,

    /** Hold it until it is safe. The default. */
    QUEUE,

    /** Hold it, and collapse it with anything sharing its [Cinematic.groupingKey]. */
    MERGE,

    /** Write it to the log and nothing else. For something a developer wants a record of. */
    LOG_ONLY,

    /** Throw it away. For anything only meaningful in the moment. */
    DISCARD,
}

// -- components ------------------------------------------------------------

/**
 * A piece of a cinematic.
 *
 * Data, and dispatched by the sink the same way a rule action is dispatched by a handler — for the same reason.
 * The plan's component list includes particles, shaders and voice clips, none of which the mod can draw today.
 * Modelling them now and having the sink **skip what it cannot draw** means a cinematic written against the
 * full list degrades rather than fails, and starts looking better when a sink learns a new component.
 */
public sealed interface CinematicComponent {

    /** Which drawing path handles it. Also what appears in a log when a sink has none. */
    public val kind: String

    /** Black bars top and bottom. The cheapest thing that makes a moment feel deliberate. */
    public data class Letterbox(public val heightFraction: Float = 0.12f) : CinematicComponent {
        override val kind: String get() = "letterbox"
    }

    /** A dim or a tint over the world. */
    public data class Background(
        public val colour: Int = 0x000000,
        public val opacity: Float = 0.6f,
    ) : CinematicComponent {
        override val kind: String get() = "background"
    }

    public data class Title(
        public val text: String,
        /** An ARGB colour, or null to take the cinematic's rarity colour. */
        public val colour: Int? = null,
    ) : CinematicComponent {
        override val kind: String get() = "title"
    }

    public data class Subtitle(public val text: String) : CinematicComponent {
        override val kind: String get() = "subtitle"
    }

    /**
     * A number that counts up to [value].
     *
     * Its own component rather than a formatted title, because the animation is the point: a coin total that
     * lands is worth more than the same number appearing.
     */
    public data class AnimatedNumber(
        public val value: Long,
        public val prefix: String = "",
        public val suffix: String = "",
    ) : CinematicComponent {
        override val kind: String get() = "number"
    }

    public data class ProgressBar(
        public val fraction: Float,
        public val label: String = "",
    ) : CinematicComponent {
        override val kind: String get() = "progress"
    }

    /**
     * An item, drawn as its model.
     *
     * Takes an [SqItem] snapshot and never an `ItemStack`: a cinematic can sit in a queue for ten minutes, and
     * a stack held that long is a reference to something the player may have sold.
     */
    public data class ItemModel(public val item: SqItem) : CinematicComponent {
        override val kind: String get() = "item"
    }

    /**
     * An item named only by its display name, drawn if the game has a picture of it.
     *
     * Separate from [ItemModel] because a drop read from chat is exactly this and nothing more: Hypixel
     * announces `RARE DROP! Enchanted Book` and the client never sees a stack, so there is no [SqItem] to
     * build. A component demanding one would mean the thing this exists to illustrate can never use it.
     *
     * The adapter resolves the name — against a SkyBlock item database first, since "Revenant Catalyst" is not
     * a Minecraft item, then against the game's own registry — and **skips the component when it cannot**.
     * Drawing a guess would be worse than drawing nothing, because nobody could tell it was wrong.
     */
    public data class ItemIcon(public val itemName: String) : CinematicComponent {
        override val kind: String get() = "item_icon"
    }

    public data class PlayerHead(public val player: PlayerId) : CinematicComponent {
        override val kind: String get() = "player_head"
    }

    /** A reveal, hidden until [atFraction] of the way through. */
    public data class RewardReveal(
        public val label: String,
        public val atFraction: Float = 0.5f,
    ) : CinematicComponent {
        override val kind: String get() = "reward"
    }

    public data class Sound(
        public val soundId: SqId,
        public val atFraction: Float = 0f,
        public val volume: Float = 1f,
    ) : CinematicComponent {
        override val kind: String get() = "sound"
    }

    // The ones nothing can draw yet. Kept as data so a cinematic can name them and a sink can grow into them.

    public data class VoiceClip(public val soundId: SqId) : CinematicComponent {
        override val kind: String get() = "voice"
    }

    public data class Particles(public val effectId: SqId, public val count: Int = 32) : CinematicComponent {
        override val kind: String get() = "particles"
    }

    public data class Shader(public val shaderId: SqId) : CinematicComponent {
        override val kind: String get() = "shader"
    }

    /** Takes a screenshot while it plays. Needs the asset manager to have somewhere to put it. */
    public data object Screenshot : CinematicComponent {
        override val kind: String get() = "screenshot"
    }

    /** Shows what the group said about it. Needs the backend's reactions, which do not exist yet. */
    public data object FriendReactions : CinematicComponent {
        override val kind: String get() = "reactions"
    }
}

// -- safety ----------------------------------------------------------------

/** One reason now is a bad moment. */
public enum class UnsafeReason(public val displayName: String) {
    NOT_IN_GAME("not in a world"),
    DEAD("dead"),
    LOW_HEALTH("on low health"),
    IN_COMBAT("taking damage"),
    DEMANDING_ACTIVITY("mid-run"),
    HAZARDOUS_ISLAND("somewhere dangerous"),
    SCREEN_OPEN("a screen is open"),
    ALREADY_PLAYING("another cinematic is playing"),
    SERIOUS_MODE("serious mode is on"),
    DISABLED("cinematics are switched off"),
    ;

    /**
     * Whether this is a *refusal* rather than a bad moment.
     *
     * The two are handled differently and conflating them is how `SHOW_ANYWAY` becomes a way to draw over a
     * loading screen. There is no player to show anything to when not in a world, and no screen to draw on —
     * that is not a judgement about interrupting.
     */
    public val isAbsolute: Boolean get() = this == NOT_IN_GAME || this == DEAD || this == DISABLED
}

/**
 * Whether now is a good moment, and why.
 *
 * The reasons are the point, as everywhere else in the platform. "The cinematic did not play" has nine possible
 * causes that look identical from the player's chair, and a debug command that can name the one that applied is
 * the difference between a minute and an evening.
 */
public data class SafetyReading(
    public val reasons: Set<UnsafeReason> = emptySet(),
) {
    public val isSafe: Boolean get() = reasons.isEmpty()

    /** True when no amount of waiting or policy will help. */
    public val isRefused: Boolean get() = reasons.any { it.isAbsolute }

    public fun explain(): String =
        if (isSafe) "safe" else reasons.joinToString(", ") { it.displayName }

    public companion object {
        public val Safe: SafetyReading = SafetyReading()
    }
}

// -- results ---------------------------------------------------------------

/** What became of a submitted cinematic. */
public sealed interface CinematicDisposition {

    public val cinematic: Cinematic

    public data class Played(override val cinematic: Cinematic) : CinematicDisposition

    /** Shown as a notification instead. */
    public data class Compacted(override val cinematic: Cinematic) : CinematicDisposition

    public data class Queued(
        override val cinematic: Cinematic,
        public val position: Int,
        public val reason: String,
    ) : CinematicDisposition

    /** Collapsed into an already-queued one. [into] is the surviving cinematic. */
    public data class Merged(
        override val cinematic: Cinematic,
        public val into: SqId,
        public val count: Int,
    ) : CinematicDisposition

    public data class Logged(override val cinematic: Cinematic, public val reason: String) : CinematicDisposition

    /** Never shown, and this is why. Covers discarded, deduplicated, expired and dropped-from-a-full-queue. */
    public data class Dropped(
        override val cinematic: Cinematic,
        public val reason: String,
    ) : CinematicDisposition
}

/** A queued cinematic, and what has happened to it while it waited. */
public data class QueuedCinematic(
    public val cinematic: Cinematic,
    /** How many events this one stands for, after merging. 1 for an unmerged cinematic. */
    public val count: Int = 1,
    public val queuedAtMillis: Long,
) {
    public fun hasExpired(nowMillis: Long): Boolean =
        nowMillis - queuedAtMillis >= cinematic.expiry.inWholeMilliseconds
}

/** How much the player has agreed to be interrupted. */
public data class CinematicSettings(
    public val isEnabled: Boolean = true,
    /**
     * Whether a cinematic waits for a safe moment or is downgraded on the spot.
     *
     * Separate from the per-cinematic policy: the policy is the feature's intent, this is the user's, and the
     * user's wins. Somebody who never wants to be interrupted still wants to know what happened.
     */
    public val queueWhileUnsafe: Boolean = true,
    /** Nothing but a notification, ever. For somebody who finds the whole idea annoying. */
    public val compactOnly: Boolean = false,
    public val letterbox: Boolean = true,
    /** Cinematics are silent for a serious session, like notifications. */
    public val seriousMode: Boolean = false,
    public val maxQueue: Int = 20,

    /**
     * Whether releasing a backlog offers a recap instead of playing all of it.
     *
     * On, because the alternative is eleven cinematics back to back the moment somebody leaves a dungeon, which
     * is not a reward — it is a punishment for having had a good run.
     */
    public val recap: Boolean = true,

    /** How many held cinematics turn the release into a recap rather than a sequence. */
    public val recapThreshold: Int = 3,
) {
    init {
        require(maxQueue > 0) { "maxQueue must be positive" }
        require(recapThreshold > 0) { "recapThreshold must be positive" }
    }

    public companion object {
        public val Default: CinematicSettings = CinematicSettings()
    }
}

// -- the boundary ----------------------------------------------------------

/**
 * Where a cinematic is actually drawn.
 *
 * An interface for the same reason as the notification and sound sinks: every decision above it — the safety
 * gate, the queue, expiry, merging, recap — is then testable with no game and no renderer, which is the
 * majority of the code and all of the code that can hurt somebody.
 */
public interface CinematicSink {

    public val isPlaying: Boolean

    /** Whether this sink can draw a kind of component. Anything else is skipped and logged. */
    public fun supports(kind: String): Boolean

    /**
     * Starts playing, calling [onFinished] when it ends for any reason including a skip.
     *
     * Returns false when it could not start, which the director treats as the cinematic not having played —
     * a sink that quietly fails would otherwise consume a queue.
     */
    public fun play(cinematic: Cinematic, onFinished: () -> Unit): Boolean

    /** Cuts the current one short. Does nothing when none is playing. */
    public fun skip()

    public companion object {
        /** Draws nothing and says so. The default, so a headless platform has a working director. */
        public val None: CinematicSink = object : CinematicSink {
            override val isPlaying: Boolean get() = false
            override fun supports(kind: String): Boolean = false
            override fun play(cinematic: Cinematic, onFinished: () -> Unit): Boolean = false
            override fun skip() {}
        }
    }
}

/**
 * The one place a cinematic's fate is decided.
 *
 * A feature submits and does not decide. That is the whole design: eleven features each checking whether the
 * player is in a dungeon would be eleven chances to get it wrong, and one of them getting it wrong is somebody
 * dying at the boss.
 */
public interface CinematicDirector {

    public val settings: CinematicSettings

    /** Whether now is a good moment, and why not. Exposed because a debug command asking is the point. */
    public fun safety(): SafetyReading

    /** Submits one, and returns what became of it. */
    public fun submit(cinematic: Cinematic): CinematicDisposition

    /** What is waiting, in the order it would play. */
    public fun queued(): List<QueuedCinematic>

    /** Cuts the current one short and moves on. */
    public fun skip()

    /**
     * Plays a past one again, on demand.
     *
     * The plan asks for manual replay, and it earns its place twice: a player who missed something wants
     * another look, and it is the only way to see a cinematic that only fires on a one-in-a-million drop.
     */
    public fun replay(cinematicId: SqId): CinematicDisposition?

    /** What has played, newest first. Bounded. */
    public fun history(): List<Cinematic>

    /** Every disposition, newest first. Bounded. The whole of the debugging story. */
    public fun trace(): List<CinematicDisposition>
}

// -- events ----------------------------------------------------------------

public class CinematicStartedEvent(public val cinematic: Cinematic) : SidequestEvent() {
    override fun describe(): String = "cinematic ${cinematic.id} started"
}

public class CinematicFinishedEvent(
    public val cinematic: Cinematic,
    public val wasSkipped: Boolean,
) : SidequestEvent() {
    override fun describe(): String =
        "cinematic ${cinematic.id} " + if (wasSkipped) "skipped" else "finished"
}

/**
 * The queue changed.
 *
 * Posted so a HUD can show an indicator without polling. The plan asks for a queue indicator, and a player who
 * knows something is waiting is a player who does not think the mod missed it.
 */
public class CinematicQueueChangedEvent(public val waiting: Int) : SidequestEvent() {
    override fun describe(): String = "$waiting cinematic(s) waiting"
}
