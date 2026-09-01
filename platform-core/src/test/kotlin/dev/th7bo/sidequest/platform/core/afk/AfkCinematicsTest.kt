package dev.th7bo.sidequest.platform.core.afk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The AFK camera, without a game.
 *
 * Two halves, and the first one is the half that can hurt somebody: deciding that a person is away. A camera
 * that starts while somebody is playing takes their view during whatever they were doing, so most of what
 * follows is about the signs of life that have to reset the clock.
 *
 * The second half is the reel, where the assertions worth reading are the ends — the camera has to leave from
 * exactly where the player left it and come back to exactly there, because it is handed back the instant
 * anybody touches anything.
 */
class AfkCinematicsTest {

    // -- knowing somebody is away --------------------------------------------

    private fun pose(
        x: Double = 0.0,
        y: Double = 64.0,
        z: Double = 0.0,
        yaw: Float = 0f,
        pitch: Float = 0f,
    ) = CameraPose(x, y, z, yaw, pitch)

    @Test
    fun `standing still counts up`() {
        val watch = IdleWatch()
        watch.observe(pose(), Duration.ZERO)
        watch.observe(pose(), 1.seconds)

        assertTrue(watch.hasBeenIdleFor(1.seconds, 1.seconds))
        assertFalse(watch.hasBeenIdleFor(2.seconds, 1.seconds))
    }

    @Test
    fun `walking resets the clock`() {
        val watch = IdleWatch()
        watch.observe(pose(), Duration.ZERO)
        assertTrue(watch.hasBeenIdleFor(5.minutes, 5.minutes))

        assertTrue(watch.observe(pose(x = 3.0), 5.minutes))
        assertEquals(Duration.ZERO, watch.idleFor(5.minutes))
    }

    @Test
    fun `turning the view resets the clock, without moving a block`() {
        val watch = IdleWatch()
        watch.observe(pose(), Duration.ZERO)

        assertTrue(watch.observe(pose(yaw = 20f), 1.minutes))
        assertEquals(Duration.ZERO, watch.idleFor(1.minutes))
    }

    @Test
    fun `a standing player's wobble is not movement`() {
        val watch = IdleWatch()
        watch.observe(pose(), Duration.ZERO)

        // Fractions of a block, which is what collision resolution produces under a player doing nothing.
        assertFalse(watch.observe(pose(x = 0.001, y = 63.999), 1.seconds))
        assertTrue(watch.hasBeenIdleFor(1.seconds, 1.seconds))
    }

    @Test
    fun `yaw crossing zero is two degrees, not three hundred and fifty`() {
        val watch = IdleWatch(turned = 5f)
        watch.observe(pose(yaw = 359f), Duration.ZERO)

        assertFalse(watch.observe(pose(yaw = 1f), 1.seconds))
    }

    @Test
    fun `no world is not time spent away`() {
        val watch = IdleWatch()
        watch.observe(pose(), Duration.ZERO)

        // A loading screen in the middle of it. Coming back in must not arrive already idle.
        watch.observe(null, 5.minutes)
        watch.observe(pose(), 5.minutes)
        assertEquals(Duration.ZERO, watch.idleFor(5.minutes))
    }

    @Test
    fun `anything else can say somebody is there`() {
        val watch = IdleWatch()
        watch.observe(pose(), Duration.ZERO)
        watch.stir(2.minutes)

        assertEquals(Duration.ZERO, watch.idleFor(2.minutes))
    }

    // -- the shots -----------------------------------------------------------

    @Test
    fun `a shot starts and ends where it says`() {
        val shot = CameraShot("test", ShotFrame(-40f, 10f), ShotFrame(40f, 30f), 4.seconds)

        assertEquals(-40f, shot.frameAt(0f).yaw, TOLERANCE)
        assertEquals(10f, shot.frameAt(0f).pitch, TOLERANCE)
        assertEquals(40f, shot.frameAt(1f).yaw, TOLERANCE)
        assertEquals(30f, shot.frameAt(1f).pitch, TOLERANCE)
    }

    @Test
    fun `a shot eases rather than sweeping at a constant rate`() {
        val shot = CameraShot("test", ShotFrame(0f, 0f), ShotFrame(100f, 0f), 4.seconds)

        // Halfway through is halfway round; the first tenth is not a tenth of the way, which is the whole
        // difference between a camera move and a rotation.
        assertEquals(50f, shot.frameAt(0.5f).yaw, TOLERANCE)
        assertTrue(shot.frameAt(0.1f).yaw < 10f)
        assertTrue(shot.frameAt(0.9f).yaw > 90f)
    }

    @Test
    fun `a shot never points past the limits`() {
        val shot = CameraShot("steep", ShotFrame(0f, -400f), ShotFrame(0f, 400f), 4.seconds)

        for (step in 0..20) {
            val pitch = shot.frameAt(step / 20f).pitch
            assertTrue(pitch in ShotFrame.MIN_PITCH..ShotFrame.MAX_PITCH, "pitch was $pitch")
        }
    }

    @Test
    fun `every shot in the catalogue is scaled by the setting`() {
        val short = Shots.catalogue(4.seconds)
        val long = Shots.catalogue(8.seconds)

        assertEquals(short.size, long.size)
        // The rhythm is the point: doubling the setting doubles every shot, so a long orbit stays longer
        // than a quick profile at every setting rather than everything converging on one length.
        short.zip(long).forEach { (a, b) -> assertEquals(2.0, b.duration / a.duration, TOLERANCE.toDouble()) }
        assertTrue(short.map { it.duration }.distinct().size > 1)
    }

    // -- the reel ------------------------------------------------------------

    private fun reel(random: Random = Random(1)) =
        ShotReel(Shots.catalogue(4.seconds), transition = 1.seconds, random = random)

    @Test
    fun `nothing happens until it is started`() {
        val reel = reel()

        assertFalse(reel.isRunning)
        assertNull(reel.shot)
        assertEquals(ShotFrame.Centred, reel.advance(1.minutes))
    }

    @Test
    fun `it leaves from exactly where the player left the camera`() {
        val reel = reel()
        reel.start(Duration.ZERO)

        assertEquals(ShotFrame.Centred, reel.advance(Duration.ZERO))
        assertEquals(0f, reel.presence, TOLERANCE)

        // Part way through the transition it is on its way and has not arrived.
        val partway = reel.advance(500.milliseconds)
        assertNotEquals(ShotFrame.Centred, partway)
        assertEquals(ShotReel.Phase.ENTERING, reel.phase)
    }

    @Test
    fun `the bars come in on the same ramp as the camera`() {
        val reel = reel()
        reel.start(Duration.ZERO)

        reel.advance(500.milliseconds)
        assertEquals(0.5f, reel.presence, TOLERANCE)

        reel.advance(1.seconds)
        assertEquals(1f, reel.presence, TOLERANCE)
        assertEquals(ShotReel.Phase.RUNNING, reel.phase)
    }

    @Test
    fun `it cuts rather than sweeping on through one long shot`() {
        val reel = reel()
        reel.start(Duration.ZERO)

        val seen = mutableListOf<String>()
        var now = 1.seconds
        repeat(SAMPLES) {
            reel.advance(now)
            reel.shot?.name?.let { name -> if (seen.lastOrNull() != name) seen.add(name) }
            now += 1.seconds
        }

        assertTrue(seen.size > 3, "expected several shots, got $seen")
    }

    /**
     * The no-repeat rule, against a picker that wants to repeat.
     *
     * The [Random] here always names the shot already on screen, which is what makes the assertion mean
     * something: draw-and-reject would loop forever on it, and a picker that trusted the draw would show the
     * same angle twice in a row and read as the camera having frozen.
     */
    @Test
    fun `the same shot never runs twice in a row`() {
        val two = listOf(
            CameraShot("a", ShotFrame(0f, 10f), ShotFrame(10f, 10f), 2.seconds),
            CameraShot("b", ShotFrame(90f, 10f), ShotFrame(100f, 10f), 2.seconds),
        )
        val reel = ShotReel(two, transition = 1.seconds, random = AlwaysZero)
        reel.start(Duration.ZERO)

        val seen = mutableListOf<String>()
        var now = 1.seconds
        repeat(6) {
            reel.advance(now)
            seen.add(reel.shot!!.name)
            now += 2.seconds
        }

        assertEquals(listOf("a", "b", "a", "b", "a", "b"), seen)
    }

    @Test
    fun `a single-shot reel keeps showing it rather than stalling`() {
        val only = listOf(CameraShot("only", ShotFrame(0f, 10f), ShotFrame(90f, 10f), 2.seconds))
        val reel = ShotReel(only, transition = 1.seconds, random = Random(1))
        reel.start(Duration.ZERO)

        var now = Duration.ZERO
        repeat(SAMPLES) {
            now += 1.seconds
            reel.advance(now)
        }
        assertEquals("only", reel.shot?.name)
    }

    @Test
    fun `handing the view back returns it to where it started`() {
        val reel = reel()
        reel.start(Duration.ZERO)
        reel.advance(10.seconds)
        assertNotEquals(ShotFrame.Centred, reel.frame)

        reel.release(10.seconds)
        assertEquals(ShotReel.Phase.LEAVING, reel.phase)

        reel.advance(11.seconds)
        assertEquals(ShotFrame.Centred, reel.frame)
        assertFalse(reel.isRunning)
        assertEquals(0f, reel.presence, TOLERANCE)
    }

    @Test
    fun `the way out is continuous from wherever it had got to`() {
        val reel = reel()
        reel.start(Duration.ZERO)
        val before = reel.advance(10.seconds)

        reel.release(10.seconds)
        val justAfter = reel.advance(10.seconds + 1.milliseconds)

        assertTrue(abs(justAfter.yaw - before.yaw) < 1f, "$before jumped to $justAfter")
        assertTrue(abs(justAfter.pitch - before.pitch) < 1f, "$before jumped to $justAfter")
    }

    @Test
    fun `being switched off underneath it puts the camera straight back`() {
        val reel = reel()
        reel.start(Duration.ZERO)
        reel.advance(10.seconds)

        reel.stop()
        assertEquals(ShotFrame.Centred, reel.frame)
        assertFalse(reel.isRunning)
    }

    @Test
    fun `starting twice does not restart the reel`() {
        val reel = reel()
        reel.start(Duration.ZERO)
        reel.advance(10.seconds)
        val shot = reel.shot

        reel.start(10.seconds)
        assertEquals(ShotReel.Phase.RUNNING, reel.phase)
        assertEquals(shot, reel.shot)
    }

    /** Names the first candidate every time. See the no-repeat test. */
    private object AlwaysZero : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextInt(until: Int): Int = 0
    }

    private companion object {
        const val TOLERANCE = 0.01f

        /** Enough one-second steps to see a handful of cuts at a four-second base. */
        const val SAMPLES = 60
    }
}
