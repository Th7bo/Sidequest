package dev.th7bo.sidequest.platform.core.audio

import dev.th7bo.sidequest.platform.audio.SoundDefinition
import dev.th7bo.sidequest.platform.audio.SoundGroup
import dev.th7bo.sidequest.platform.audio.SoundManager
import dev.th7bo.sidequest.platform.audio.SoundPool
import dev.th7bo.sidequest.platform.audio.SoundRequest
import dev.th7bo.sidequest.platform.audio.SoundResult
import dev.th7bo.sidequest.platform.audio.SoundSettings
import dev.th7bo.sidequest.platform.audio.SoundSink
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.log.Logger
import kotlin.random.Random

/**
 * Plays sounds, once each, at the right volume, and not too often.
 *
 * Every one of those qualifiers is a thing a feature calling the game directly would get wrong, and the
 * feature that needs them most is the soundboard — where the sound is playing in somebody else's ears and
 * they did not choose it.
 *
 * The order of the checks is the design, and it is the same shape as the notification manager's for the same
 * reason: each can end the decision, and a different order produces a mod that is either silent when it
 * matters or noisy when it must not be.
 *
 * 1. **Is it registered.** An unregistered id is a bug, and reported as one.
 * 2. **Serious mode.** A blanket answer beats everything below.
 * 3. **Is the group audible.** Muted or zero-volume means stop; there is nothing to schedule.
 * 4. **Cooldown.** Checked before playing but recorded only *after* a successful play, so a sound that could
 *    not be resolved does not start a cooldown on a sound that never played.
 * 5. **Play, or fall back.**
 */
public class DefaultSoundManager(
    private val sink: SoundSink,
    private val log: Logger,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    initialSettings: SoundSettings = SoundSettings.Default,
) : SoundManager {

    override var settings: SoundSettings = initialSettings
        private set

    private val definitions = HashMap<SqId, SoundDefinition>()
    private val pools = HashMap<SqId, SoundPool>()

    /** When each sound last played. The whole of the cooldown state. */
    private val lastPlayedAt = HashMap<SqId, Long>()

    /** The last pick from each pool, so `avoidRepeats` has something to avoid. */
    private val lastFromPool = HashMap<SqId, SqId>()

    public fun update(settings: SoundSettings) {
        this.settings = settings
    }

    override fun register(definition: SoundDefinition) {
        definitions[definition.id] = definition
    }

    override fun registerPool(pool: SoundPool) {
        pools[pool.id] = pool
    }

    override fun play(request: SoundRequest): SoundResult {
        val definition = definitions[request.soundId] ?: run {
            // A bug rather than a condition. Silence would leave somebody wondering why a feature does
            // nothing, and the id is right there in the message.
            log.warn { "No sound registered as ${request.soundId}" }
            return SoundResult.MISSING
        }

        // Serious mode spares the interface group, because that is what confirms an action worked. Silencing
        // it too would leave a mod that appears to have stopped responding.
        if (settings.seriousMode && definition.group != SoundGroup.INTERFACE) {
            return SoundResult.SUPPRESSED
        }

        val groupVolume = settings.volumeOf(definition.group)
        if (groupVolume <= 0f) return SoundResult.MUTED

        if (request.respectCooldown && isOnCooldown(definition)) return SoundResult.ON_COOLDOWN

        val volume = (groupVolume * definition.gain * request.volume).coerceIn(0f, MAX_VOLUME)
        val pitch = request.pitch ?: definition.pitch

        if (sink.play(definition.resource, volume, pitch, request.position)) {
            lastPlayedAt[definition.id] = now()
            return SoundResult.PLAYED
        }

        // Could not be resolved: a remote asset that has not downloaded, or has been removed. The fallback is
        // what makes that a slightly wrong sound rather than a feature that appears broken.
        val fallback = definition.fallbackId?.let { definitions[it] }
        if (fallback == null) {
            log.warn { "Could not play ${definition.id} (${definition.resource}) and it has no fallback" }
            return SoundResult.MISSING
        }

        return if (sink.play(fallback.resource, volume, pitch, request.position)) {
            // The cooldown belongs to the sound that was *asked for*, not to the fallback: otherwise a
            // missing asset would let the request through again immediately.
            lastPlayedAt[definition.id] = now()
            log.debug { "Played the fallback for ${definition.id}" }
            SoundResult.PLAYED_FALLBACK
        } else {
            log.warn { "Neither ${definition.id} nor its fallback could be played" }
            SoundResult.MISSING
        }
    }

    override fun playPool(poolId: SqId, request: SoundRequest?): SoundResult {
        val pool = pools[poolId] ?: run {
            log.warn { "No sound pool registered as $poolId" }
            return SoundResult.MISSING
        }
        if (pool.soundIds.isEmpty()) return SoundResult.MISSING

        val chosen = choose(pool)
        lastFromPool[poolId] = chosen
        return play(request?.copy(soundId = chosen) ?: SoundRequest(chosen))
    }

    /**
     * Picks from a pool, avoiding the last one where asked.
     *
     * Two plays of the same sample in a row is what makes a random pool sound broken rather than random. With
     * one sound in the pool there is nothing to avoid, and pretending otherwise would mean playing nothing.
     */
    private fun choose(pool: SoundPool): SqId {
        if (pool.soundIds.size == 1 || !pool.avoidRepeats) {
            return pool.soundIds[random.nextInt(pool.soundIds.size)]
        }
        val previous = lastFromPool[pool.id]
        val candidates = pool.soundIds.filter { it != previous }.ifEmpty { pool.soundIds }
        return candidates[random.nextInt(candidates.size)]
    }

    private fun isOnCooldown(definition: SoundDefinition): Boolean {
        val last = lastPlayedAt[definition.id] ?: return false
        return now() - last < definition.cooldown.inWholeMilliseconds
    }

    override fun stop(soundId: SqId) {
        definitions[soundId]?.let { sink.stop(it.resource) }
    }

    /** Forgets every cooldown. For a disconnect, where nothing carries over. */
    public fun resetCooldowns() {
        lastPlayedAt.clear()
    }

    private companion object {
        /**
         * The loudest anything is allowed to be.
         *
         * One, not more. Minecraft treats a volume above one as a distance multiplier rather than as extra
         * loudness, so a feature passing two gets a sound audible from a hundred blocks away — which is a
         * surprising way to discover that a gain was mistyped.
         */
        const val MAX_VOLUME = 1f
    }
}
