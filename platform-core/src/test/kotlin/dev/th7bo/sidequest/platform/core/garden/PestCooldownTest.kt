package dev.th7bo.sidequest.platform.core.garden

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The Pests widget's cooldown line, in the shapes SkyHanni records for it. See [PestCooldown]. */
class PestCooldownTest {

    @Test
    fun `every recorded shape is read`() {
        assertEquals(PestCooldown.Waiting(1.minutes + 58.seconds), PestCooldown.read(listOf(" Cooldown: 1m 58s")))
        assertEquals(PestCooldown.Waiting(1.minutes), PestCooldown.read(listOf(" Cooldown: 1m")))
        assertEquals(PestCooldown.Waiting(58.seconds), PestCooldown.read(listOf(" Cooldown: 58s")))
        assertEquals(PestCooldown.Ready, PestCooldown.read(listOf(" Cooldown: READY")))
        assertEquals(PestCooldown.MaxPests, PestCooldown.read(listOf(" Cooldown: MAX PESTS")))
    }

    /** Cleaning may or may not keep the leading space; either way it is the same line. */
    @Test
    fun `the indent is optional`() {
        assertEquals(PestCooldown.Waiting(58.seconds), PestCooldown.read(listOf("Cooldown: 58s")))
    }

    @Test
    fun `the line is found among the others`() {
        val lines = listOf(" Alive: 1", " Infested Plots: 3", " Cooldown: 12s")
        assertEquals(PestCooldown.Waiting(12.seconds), PestCooldown.read(lines))
    }

    /** A partial widget can drop the cooldown line; eight alive is the cap regardless. */
    @Test
    fun `eight alive is max pests whatever else is there`() {
        assertEquals(PestCooldown.MaxPests, PestCooldown.read(listOf(" Alive: 8")))
        assertEquals(PestCooldown.MaxPests, PestCooldown.read(listOf(" Alive: 8", " Cooldown: READY")))
    }

    @Test
    fun `no cooldown line reads as nothing`() {
        assertNull(PestCooldown.read(listOf(" Alive: 1")))
        assertNull(PestCooldown.read(emptyList()))
    }
}
