package dev.th7bo.sidequest.platform.audio

import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A sound the mod can play.
 *
 * Either one of the game's own or one the group has added. The distinction matters more than it looks: a
 * remote sound is a file somebody uploaded, so it goes through the asset manager and can be absent, and
 * everything that plays one has to cope with that. A vanilla sound is always there.
 */
@Serializable
public data class SoundDefinition(
    public val id: SqId,
    /**
     * What actually plays.
     *
     * For [SoundOrigin.GAME] this is a Minecraft sound id like `minecraft:entity.player.levelup`. For
     * [SoundOrigin.REMOTE] it is an asset id the asset manager resolves.
     */
    public val resource: String,
    public val origin: SoundOrigin = SoundOrigin.GAME,
    public val group: SoundGroup = SoundGroup.EFFECTS,
    /** Multiplied by the group's volume. For a sound that is simply mastered too loud. */
    public val gain: Float = 1f,
    public val pitch: Float = 1f,
    /**
     * The shortest gap between two plays of this sound.
     *
     * Per sound rather than global, because the right answer differs wildly: a level-up chime can play twice
     * a second and a soundboard airhorn cannot.
     */
    public val cooldown: Duration = DEFAULT_COOLDOWN,
    /**
     * A sound to fall back to when this one cannot be resolved.
     *
     * The answer to a remote asset that has not downloaded yet, or has been removed. Silence would be
     * indistinguishable from the feature being broken; a vanilla click is not.
     */
    public val fallbackId: SqId? = null,
) {
    public companion object {
        public val DEFAULT_COOLDOWN: Duration = 1.seconds
    }
}

@Serializable
public enum class SoundOrigin {
    /** One of Minecraft's own. Always available. */
    GAME,

    /**
     * A file the group added, through the asset manager.
     *
     * Can be absent — not downloaded yet, removed, or refused by the asset policy — which is why
     * [SoundDefinition.fallbackId] exists.
     */
    REMOTE,
}

/**
 * A volume group.
 *
 * The unit a user actually adjusts. Nobody wants a slider per sound; everybody wants "turn the joke sounds
 * down without losing the alerts", and that is what these are for.
 */
@Serializable
public enum class SoundGroup(public val displayName: String) {
    /** Notification chimes and confirmations. Quiet, frequent, load-bearing. */
    INTERFACE("Interface"),

    /** Progression and cinematic sounds. */
    EFFECTS("Effects"),

    /**
     * The soundboard — sounds one person triggers in somebody else's ears.
     *
     * Its own group because it is the one people want to turn off independently of everything else, and the
     * one where "somebody else chose this" makes a mute control a requirement rather than a nicety.
     */
    SOUNDBOARD("Soundboard"),

    /** Joke sounds tied to achievements and cosmetics. First thing to go in serious mode. */
    FUN("Fun"),
}

/**
 * A set of sounds to choose between.
 *
 * The reason a pool is a type rather than a list at a call site: a sound that plays the same sample every
 * time becomes irritating quickly, and the fix — pick a different one, but not the one you just played — is
 * the kind of thing every feature would otherwise implement slightly differently.
 */
@Serializable
public data class SoundPool(
    public val id: SqId,
    public val soundIds: List<SqId>,
    /**
     * Whether to avoid repeating the last one picked.
     *
     * On by default. Two plays of the same sample in a row is what makes a random pool sound broken rather
     * than random.
     */
    public val avoidRepeats: Boolean = true,
)

/** One request to play something. */
public data class SoundRequest(
    public val soundId: SqId,
    /** Multiplied by the sound's gain and the group's volume. */
    public val volume: Float = 1f,
    public val pitch: Float? = null,
    /**
     * Where it comes from, or null for a sound that is simply *heard* rather than located.
     *
     * A notification chime has no position. A ping does — and a positioned sound is how somebody knows which
     * direction to look, which is most of the value of a ping.
     */
    public val position: SqPosition? = null,
    /**
     * Whether the cooldown applies.
     *
     * False for anything a user did deliberately: somebody pressing a soundboard button twice meant it, and
     * a cooldown that swallows a deliberate action feels broken rather than considerate.
     */
    public val respectCooldown: Boolean = true,
)

/** What happened to a request. Returned so a caller can tell "played" from "silently dropped". */
public enum class SoundResult {
    PLAYED,

    /** Played, but as the fallback — the real one could not be resolved. */
    PLAYED_FALLBACK,

    /** Too soon after the last play of this sound. */
    ON_COOLDOWN,

    /** The group is muted, or the master volume is zero. */
    MUTED,

    /** Serious mode is on and this sound is not important enough. */
    SUPPRESSED,

    /** Not resolvable and no fallback. The one case worth a log line. */
    MISSING,
}

/**
 * Where sounds actually come out.
 *
 * An interface, so cooldowns, pools, volume groups and serious mode are all testable with no audio device
 * and no game. What is below it is the twenty lines that ask Minecraft to play something.
 */
public interface SoundSink {

    /**
     * Plays [resource] at an absolute volume and pitch, optionally at a position.
     *
     * Volume arrives already multiplied through the group and the sound's own gain: the sink applies no
     * policy, so there is one place that decides how loud anything is.
     *
     * Returns false when the resource could not be played, which is how a missing remote asset becomes a
     * fallback rather than silence.
     */
    public fun play(resource: String, volume: Float, pitch: Float, position: SqPosition?): Boolean

    /** Stops a sound, if the platform can. Fading is the sink's business. */
    public fun stop(resource: String)

    public companion object {
        /** Plays nothing, and says so. The default, so a headless platform has a working manager. */
        public val None: SoundSink = object : SoundSink {
            override fun play(resource: String, volume: Float, pitch: Float, position: SqPosition?): Boolean = false
            override fun stop(resource: String) {}
        }
    }
}

/**
 * What the player has asked for.
 *
 * Per group, plus two blanket switches. [seriousMode] shares its name and its purpose with the notification
 * setting: somebody reaching for it wants the mod to stop being fun, in one action, without configuring
 * anything.
 */
@Serializable
public data class SoundSettings(
    public val masterVolume: Float = 1f,
    public val groupVolumes: Map<SoundGroup, Float> = emptyMap(),
    public val mutedGroups: Set<SoundGroup> = emptySet(),
    /**
     * Silences everything except [SoundGroup.INTERFACE].
     *
     * The interface group survives because it is what confirms that something worked. Silencing that too
     * would leave a mod that appears to have stopped responding.
     */
    public val seriousMode: Boolean = false,
) {

    /** The effective volume for a group: master times group, or zero when muted. */
    public fun volumeOf(group: SoundGroup): Float {
        if (group in mutedGroups) return 0f
        return masterVolume.coerceIn(0f, 1f) * (groupVolumes[group] ?: 1f).coerceIn(0f, 1f)
    }

    public companion object {
        public val Default: SoundSettings = SoundSettings()
    }
}

/**
 * The one place a sound is played.
 *
 * A feature asks for a sound by id and gets cooldowns, volume groups, pools, mute controls, serious mode and
 * asset fallback for free — or rather, gets them at all. A feature calling the game directly has none of
 * them, and the one that most needs them is the soundboard, where the sound is playing in somebody else's
 * ears and they did not choose it.
 */
public interface SoundManager {

    public val settings: SoundSettings

    /** Registers a sound. Later registrations of the same id replace earlier ones. */
    public fun register(definition: SoundDefinition)

    public fun registerPool(pool: SoundPool)

    /** Plays a sound by id. */
    public fun play(request: SoundRequest): SoundResult

    /** Plays a sound by id, with defaults. */
    public fun play(soundId: SqId): SoundResult = play(SoundRequest(soundId))

    /** Plays one sound from a pool, avoiding the last one where the pool asks for that. */
    public fun playPool(poolId: SqId, request: SoundRequest? = null): SoundResult

    public fun stop(soundId: SqId)
}
