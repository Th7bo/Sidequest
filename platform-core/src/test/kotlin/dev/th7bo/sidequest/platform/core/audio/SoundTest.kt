package dev.th7bo.sidequest.platform.core.audio

import dev.th7bo.sidequest.platform.audio.SoundDefinition
import dev.th7bo.sidequest.platform.audio.SoundGroup
import dev.th7bo.sidequest.platform.audio.SoundOrigin
import dev.th7bo.sidequest.platform.audio.SoundPool
import dev.th7bo.sidequest.platform.audio.SoundRequest
import dev.th7bo.sidequest.platform.audio.SoundResult
import dev.th7bo.sidequest.platform.audio.SoundSettings
import dev.th7bo.sidequest.platform.audio.SoundSink
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The sound manager's policy.
 *
 * No audio device and no game, which is what [SoundSink] being an interface buys. As with notifications, the
 * assertions worth reading are about *not* playing something: cooldowns, mutes and serious mode are the whole
 * reason a feature should not call the game directly.
 */
class SoundTest {

    private class RecordingSink(var succeeds: Boolean = true) : SoundSink {
        data class Played(val resource: String, val volume: Float, val pitch: Float, val position: SqPosition?)

        val played = mutableListOf<Played>()
        val stopped = mutableListOf<String>()

        /** Resources this sink refuses to play, standing in for an asset that has not downloaded. */
        val missing = mutableSetOf<String>()

        override fun play(resource: String, volume: Float, pitch: Float, position: SqPosition?): Boolean {
            if (!succeeds || resource in missing) return false
            played.add(Played(resource, volume, pitch, position))
            return true
        }

        override fun stop(resource: String) { stopped.add(resource) }
    }

    private lateinit var sink: RecordingSink
    private lateinit var sounds: DefaultSoundManager
    private var clock = 10_000L

    private val chime = SqId.sidequest("sound.chime")
    private val airhorn = SqId.sidequest("sound.airhorn")
    private val fallback = SqId.sidequest("sound.click")

    @BeforeEach
    fun setUp() {
        sink = RecordingSink()
        // Seeded, so a pool's random pick is deterministic and a test is not flaky.
        sounds = DefaultSoundManager(sink, NoopLogger, now = { clock }, random = Random(7))

        sounds.register(SoundDefinition(chime, "minecraft:ui.button.click", group = SoundGroup.INTERFACE))
        sounds.register(
            SoundDefinition(
                airhorn,
                "sidequest:airhorn",
                origin = SoundOrigin.REMOTE,
                group = SoundGroup.SOUNDBOARD,
                cooldown = 5.seconds,
                fallbackId = fallback,
            ),
        )
        sounds.register(SoundDefinition(fallback, "minecraft:ui.button.click", group = SoundGroup.SOUNDBOARD))
    }

    // -- the basics --------------------------------------------------------

    @Test
    fun `a registered sound plays`() {
        assertEquals(SoundResult.PLAYED, sounds.play(chime))
        assertEquals("minecraft:ui.button.click", sink.played.single().resource)
    }

    /** An unregistered id is a bug, and silence would leave somebody guessing which one. */
    @Test
    fun `an unregistered sound is reported as missing`() {
        assertEquals(SoundResult.MISSING, sounds.play(SqId.sidequest("sound.nope")))
        assertTrue(sink.played.isEmpty())
    }

    @Test
    fun `a later registration replaces an earlier one`() {
        sounds.register(SoundDefinition(chime, "minecraft:entity.player.levelup"))
        sounds.play(chime)
        assertEquals("minecraft:entity.player.levelup", sink.played.single().resource)
    }

    // -- volume ------------------------------------------------------------

    /** One place decides how loud anything is; the sink applies no policy of its own. */
    @Test
    fun `volume is the master, the group, the gain and the request multiplied`() {
        sounds.register(SoundDefinition(chime, "res", group = SoundGroup.EFFECTS, gain = 0.5f))
        sounds.update(
            SoundSettings(masterVolume = 0.8f, groupVolumes = mapOf(SoundGroup.EFFECTS to 0.5f)),
        )

        sounds.play(SoundRequest(chime, volume = 0.5f))

        // 0.8 * 0.5 * 0.5 * 0.5
        assertEquals(0.1f, sink.played.single().volume, 0.0001f)
    }

    /**
     * Never above one.
     *
     * Minecraft treats a volume above one as a distance multiplier rather than as loudness, so a mistyped
     * gain would make a sound audible from a hundred blocks away.
     */
    @Test
    fun `volume is clamped to one`() {
        sounds.register(SoundDefinition(chime, "res", gain = 10f))
        sounds.play(SoundRequest(chime, volume = 10f))
        assertEquals(1f, sink.played.single().volume)
    }

    @Test
    fun `a muted group plays nothing`() {
        sounds.update(SoundSettings(mutedGroups = setOf(SoundGroup.SOUNDBOARD)))
        assertEquals(SoundResult.MUTED, sounds.play(airhorn))
        assertTrue(sink.played.isEmpty())
    }

    @Test
    fun `a zero group volume is the same as muted`() {
        sounds.update(SoundSettings(groupVolumes = mapOf(SoundGroup.SOUNDBOARD to 0f)))
        assertEquals(SoundResult.MUTED, sounds.play(airhorn))
    }

    /** The group a user most wants to turn off independently is the one somebody else triggers. */
    @Test
    fun `muting the soundboard leaves the interface audible`() {
        sounds.update(SoundSettings(mutedGroups = setOf(SoundGroup.SOUNDBOARD)))
        assertEquals(SoundResult.MUTED, sounds.play(airhorn))
        assertEquals(SoundResult.PLAYED, sounds.play(chime))
    }

    // -- cooldowns ---------------------------------------------------------

    @Test
    fun `a sound on cooldown does not play again`() {
        assertEquals(SoundResult.PLAYED, sounds.play(airhorn))
        clock += 1_000
        assertEquals(SoundResult.ON_COOLDOWN, sounds.play(airhorn))
        assertEquals(1, sink.played.size)
    }

    @Test
    fun `the cooldown ends`() {
        sounds.play(airhorn)
        clock += 5_001
        assertEquals(SoundResult.PLAYED, sounds.play(airhorn))
    }

    /** Cooldowns are per sound, because the right answer differs wildly between them. */
    @Test
    fun `one sound's cooldown does not silence another`() {
        sounds.play(airhorn)
        assertEquals(SoundResult.PLAYED, sounds.play(chime))
    }

    /**
     * A deliberate action is not throttled.
     *
     * Somebody pressing a soundboard button twice meant it, and a cooldown that swallows that feels broken
     * rather than considerate.
     */
    @Test
    fun `a request that opts out of the cooldown plays anyway`() {
        sounds.play(airhorn)
        assertEquals(SoundResult.PLAYED, sounds.play(SoundRequest(airhorn, respectCooldown = false)))
        assertEquals(2, sink.played.size)
    }

    @Test
    fun `resetting forgets every cooldown`() {
        sounds.play(airhorn)
        sounds.resetCooldowns()
        assertEquals(SoundResult.PLAYED, sounds.play(airhorn))
    }

    // -- serious mode ------------------------------------------------------

    /**
     * The interface group survives.
     *
     * Silencing what confirms an action worked would leave a mod that appears to have stopped responding.
     */
    @Test
    fun `serious mode silences everything except the interface`() {
        sounds.update(SoundSettings(seriousMode = true))

        assertEquals(SoundResult.SUPPRESSED, sounds.play(airhorn))
        assertEquals(SoundResult.PLAYED, sounds.play(chime))
    }

    // -- fallback ----------------------------------------------------------

    /**
     * A remote asset that has not downloaded becomes a slightly wrong sound, not silence.
     *
     * Silence is indistinguishable from the feature being broken.
     */
    @Test
    fun `a sound that cannot be resolved falls back`() {
        sink.missing.add("sidequest:airhorn")

        assertEquals(SoundResult.PLAYED_FALLBACK, sounds.play(airhorn))
        assertEquals("minecraft:ui.button.click", sink.played.single().resource)
    }

    /**
     * The cooldown belongs to the sound that was asked for.
     *
     * Otherwise a missing asset would let the request through again immediately and hammer the sink.
     */
    @Test
    fun `a fallback still starts the requested sound's cooldown`() {
        sink.missing.add("sidequest:airhorn")
        sounds.play(airhorn)
        clock += 1_000
        assertEquals(SoundResult.ON_COOLDOWN, sounds.play(airhorn))
    }

    @Test
    fun `no fallback and nothing to play is reported as missing`() {
        sink.missing.add("minecraft:ui.button.click")
        assertEquals(SoundResult.MISSING, sounds.play(chime))
    }

    // -- pools -------------------------------------------------------------

    /** A value class cannot be a vararg parameter, hence the list. */
    private fun registerPool(ids: List<SqId>): SqId {
        for ((index, id) in ids.withIndex()) {
            sounds.register(SoundDefinition(id, "res$index", cooldown = 0.seconds))
        }
        val poolId = SqId.sidequest("pool.laughs")
        sounds.registerPool(SoundPool(poolId, ids))
        return poolId
    }

    @Test
    fun `a pool plays one of its sounds`() {
        val pool = registerPool(listOf(SqId.sidequest("a"), SqId.sidequest("b"), SqId.sidequest("c")))
        assertEquals(SoundResult.PLAYED, sounds.playPool(pool))
        assertEquals(1, sink.played.size)
    }

    /**
     * Two plays of the same sample in a row is what makes a random pool sound broken rather than random.
     */
    @Test
    fun `a pool does not repeat the sound it just played`() {
        val pool = registerPool(listOf(SqId.sidequest("a"), SqId.sidequest("b"), SqId.sidequest("c")))

        repeat(12) {
            clock += 1_000
            sounds.playPool(pool)
        }

        val resources = sink.played.map { it.resource }
        for (index in 1 until resources.size) {
            assertNotEquals(resources[index - 1], resources[index]) { "repeated at $index: $resources" }
        }
    }

    /** With one sound there is nothing to avoid, and pretending otherwise would play nothing. */
    @Test
    fun `a pool of one plays that one every time`() {
        val pool = registerPool(listOf(SqId.sidequest("only")))
        repeat(3) {
            clock += 1_000
            assertEquals(SoundResult.PLAYED, sounds.playPool(pool))
        }
        assertEquals(3, sink.played.size)
    }

    @Test
    fun `an unregistered pool is reported as missing`() {
        assertEquals(SoundResult.MISSING, sounds.playPool(SqId.sidequest("pool.nope")))
    }

    // -- positions ---------------------------------------------------------

    /** A positioned sound is how somebody knows which way to look, which is most of a ping's value. */
    @Test
    fun `a position is passed through untouched`() {
        val at = SqPosition(10.0, 70.0, -5.0)
        sounds.play(SoundRequest(chime, position = at))
        assertEquals(at, sink.played.single().position)
    }

    @Test
    fun `a sound with no position is simply heard`() {
        sounds.play(chime)
        assertEquals(null, sink.played.single().position)
    }

    @Test
    fun `stopping asks the sink for the right resource`() {
        sounds.stop(airhorn)
        assertEquals(listOf("sidequest:airhorn"), sink.stopped)
    }

    @Test
    fun `a request can override the pitch`() {
        sounds.register(SoundDefinition(chime, "res", pitch = 1f))
        sounds.play(SoundRequest(chime, pitch = 1.5f))
        assertEquals(1.5f, sink.played.single().pitch)
    }
}
