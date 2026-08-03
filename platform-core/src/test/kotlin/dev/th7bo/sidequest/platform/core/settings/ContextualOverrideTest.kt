package dev.th7bo.sidequest.platform.core.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Borrowing one of the player's settings and giving it back.
 *
 * Every test here is a way of getting that wrong, and they are all quiet failures — nothing crashes, somebody
 * just finds a setting they never touched at a value they did not choose, weeks later, with no way to connect
 * it to the mod.
 */
class ContextualOverrideTest {

    private val override = ContextualOverride<Boolean>()

    /** Drives the machine the way a feature would: read, apply, write back if told to. */
    private class Setting(var value: Boolean)

    private fun Setting.step(desired: Boolean?) {
        override.apply(value, desired)?.let { value = it }
    }

    // -- the ordinary path ---------------------------------------------------

    @Test
    fun `it takes the setting and gives it back`() {
        val bobbing = Setting(value = true)

        bobbing.step(desired = false)
        assertFalse(bobbing.value, "it should be off while the condition holds")
        assertTrue(override.isOverriding)

        bobbing.step(desired = null)
        assertTrue(bobbing.value, "and back on afterwards")
        assertFalse(override.isOverriding)
    }

    @Test
    fun `holding the condition writes nothing further`() {
        val bobbing = Setting(value = true)
        bobbing.step(desired = false)

        assertNull(override.apply(bobbing.value, desired = false), "already ours, nothing to write")
        assertNull(override.apply(bobbing.value, desired = false))
    }

    /**
     * Nothing is remembered when the setting already reads the way the override wants it.
     *
     * Remembering here looks harmless and is not: the player turns bobbing *on* while on the Garden, walks
     * out, and finds it turned off again by a "restore" of a value they had already abandoned.
     */
    @Test
    fun `a setting already at the wanted value is left alone entirely`() {
        val bobbing = Setting(value = false)

        bobbing.step(desired = false)
        assertFalse(override.isOverriding, "there was nothing to override")

        bobbing.value = true
        bobbing.step(desired = null)
        assertTrue(bobbing.value, "the player's own change survives")
    }

    // -- the player taking over ----------------------------------------------

    /**
     * The case that makes this a class rather than two lines.
     *
     * Somebody opens the options menu while the override is on and turns bobbing back on. That is a
     * deliberate choice and it has to win — both immediately, and on the way out.
     */
    @Test
    fun `a change made during the override wins`() {
        val bobbing = Setting(value = true)
        bobbing.step(desired = false)

        // The player turns it back on themselves.
        bobbing.value = true
        bobbing.step(desired = false)

        assertTrue(bobbing.value, "it must not be forced off again")
        assertFalse(override.isOverriding, "and it should stop trying")
    }

    /** Standing down is permanent for that stint, not a one-tick truce. */
    @Test
    fun `it does not resume fighting after standing down`() {
        val bobbing = Setting(value = true)
        bobbing.step(desired = false)
        bobbing.value = true

        repeat(5) { bobbing.step(desired = false) }

        assertTrue(bobbing.value)
    }

    /** Nothing is restored over a value the player chose while the override was on. */
    @Test
    fun `leaving does not undo a change the player made`() {
        val bobbing = Setting(value = true)
        bobbing.step(desired = false)
        bobbing.value = true
        bobbing.step(desired = false)

        bobbing.step(desired = null)

        assertTrue(bobbing.value, "their choice, not the remembered one")
    }

    // -- edges ---------------------------------------------------------------

    @Test
    fun `ending a condition that never started writes nothing`() {
        assertNull(override.apply(current = true, desired = null))
        assertNull(override.apply(current = false, desired = null))
    }

    @Test
    fun `it can be taken and given back repeatedly`() {
        val bobbing = Setting(value = true)

        repeat(3) {
            bobbing.step(desired = false)
            assertFalse(bobbing.value)
            bobbing.step(desired = null)
            assertTrue(bobbing.value)
        }
    }

    /**
     * Releasing gives the setting back whatever the condition says.
     *
     * For a feature being turned off, which has to leave the game exactly as it found it — a mod that
     * disabled itself and kept one of your settings hostage would be worse than one that never ran.
     */
    @Test
    fun `releasing restores immediately`() {
        val bobbing = Setting(value = true)
        bobbing.step(desired = false)

        override.release(bobbing.value)?.let { bobbing.value = it }

        assertTrue(bobbing.value)
        assertFalse(override.isOverriding)
    }

    /** A player who had it off to begin with gets it back off, not on. */
    @Test
    fun `the remembered value is the player's, not a default`() {
        val bobbing = Setting(value = false)

        // Wanting it *on* here, so there is something to override.
        bobbing.step(desired = true)
        assertTrue(bobbing.value)

        bobbing.step(desired = null)
        assertFalse(bobbing.value, "it went back to off, which is where they had it")
    }

    @Test
    fun `it works for values that are not booleans`() {
        val distance = ContextualOverride<Int>()

        assertEquals(4, distance.apply(current = 12, desired = 4))
        assertNull(distance.apply(current = 4, desired = 4))
        assertEquals(12, distance.apply(current = 4, desired = null))
    }
}
