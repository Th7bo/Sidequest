package dev.th7bo.sidequest.platform.core.skyblock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Colouring a SkyBlock level.
 *
 * The tier boundaries are the game's and the fixtures are real nametags, which is the point of most of what
 * follows: the parts that are facts about SkyBlock are pinned so a refactor cannot quietly change them, and
 * the parts that are this mod's own are checked for the properties that make them usable rather than for
 * particular values.
 */
class SkyBlockLevelColoursTest {

    // -- the game's own ladder -----------------------------------------------

    @Test
    fun `a level takes the colour of its band of forty`() {
        assertEquals(0xAAAAAA, SkyBlockLevelColours.colourFor(0), "grey at the very start")
        assertEquals(0xAAAAAA, SkyBlockLevelColours.colourFor(39), "still grey at the end of the band")
        assertEquals(0xFFFFFF, SkyBlockLevelColours.colourFor(40), "white begins the next")
        assertEquals(0xFFFF55, SkyBlockLevelColours.colourFor(119))
        assertEquals(0xAA0000, SkyBlockLevelColours.colourFor(480), "dark red, the last one the game names")
    }

    /** Every level in a band is the same colour, which is what makes it a band. */
    @Test
    fun `a band is one colour throughout`() {
        val band = (200..239).map { SkyBlockLevelColours.colourFor(it) }.distinct()

        assertEquals(1, band.size, "levels 200-239 should share a colour, got $band")
    }

    /** No two adjacent bands share a colour, or the boundary would be invisible. */
    @Test
    fun `neighbouring bands differ`() {
        val perBand = (0 until SkyBlockLevelColours.NAMED_TIERS.size)
            .map { SkyBlockLevelColours.colourFor(it * SkyBlockLevelColours.TIER_SIZE) }

        assertEquals(perBand.size, perBand.distinct().size, "a colour is used twice: $perBand")
    }

    /**
     * A level below zero answers rather than throwing.
     *
     * This runs once per player per frame on a number parsed out of text somebody else's client wrote. The
     * one thing it must never do is fail.
     */
    @Test
    fun `a nonsense level is treated as the lowest`() {
        assertEquals(SkyBlockLevelColours.colourFor(0), SkyBlockLevelColours.colourFor(-5))
    }

    // -- past where the game stops -------------------------------------------

    /** Past the named colours there is still an answer, and it is still banded. */
    @Test
    fun `levels past the ceiling stay banded`() {
        val ceiling = SkyBlockLevelColours.NAMED_CEILING

        val band = (ceiling..ceiling + 39).map { SkyBlockLevelColours.colourFor(it) }.distinct()
        assertEquals(1, band.size, "the first band past the ceiling should be one colour, got $band")
        assertNotEquals(
            SkyBlockLevelColours.colourFor(ceiling - 1),
            SkyBlockLevelColours.colourFor(ceiling),
            "and must not continue the last named colour",
        )
    }

    /**
     * A very high level never wears a colour a middling one already has.
     *
     * The whole reason to colour by level. If the ramp wrapped, somebody at seven hundred would be drawn in
     * the same shade as somebody at two hundred, which is worse than not colouring at all.
     */
    @Test
    fun `the ramp past the ceiling does not collide with the named colours`() {
        val named = SkyBlockLevelColours.NAMED_TIERS.toSet()
        val beyond = (SkyBlockLevelColours.NAMED_CEILING..1200 step 40)
            .map { SkyBlockLevelColours.colourFor(it) }

        assertTrue(beyond.none { it in named }, "a named colour was reused past the ceiling")
        assertEquals(beyond.size, beyond.distinct().size, "the ramp repeated itself: $beyond")
    }

    // -- the rainbow ---------------------------------------------------------

    @Test
    fun `the rainbow changes with every single level`() {
        val hues = (100..140).map { SkyBlockLevelColours.colourFor(it, LevelPalette.RAINBOW) }

        assertEquals(hues.size, hues.distinct().size, "levels inside one band should still differ")
    }

    /** Stops short of a full turn, so nobody at the top wears the colour of somebody at the bottom. */
    @Test
    fun `the rainbow never wraps back to its start`() {
        val start = SkyBlockLevelColours.colourFor(0, LevelPalette.RAINBOW)
        val far = (1..1000).map { SkyBlockLevelColours.colourFor(it, LevelPalette.RAINBOW) }

        assertTrue(start !in far, "the sweep came back round to where it started")
    }

    // -- reading it off a nametag --------------------------------------------

    /** Both fixtures are real nametags, formatting codes and all. */
    @Test
    fun `a level is read out of a nametag`() {
        assertEquals(480, SkyBlockLevelColours.levelIn("§8[§b480§8] §6Player"))
        assertEquals(419, SkyBlockLevelColours.levelIn("§8[§6419§8] §bPlayer"))
    }

    /** The game writes a separator once the number gets long, and that is exactly when this matters. */
    @Test
    fun `a level with a thousands separator is read`() {
        assertEquals(1234, SkyBlockLevelColours.levelIn("§8[§b1,234§8] §6Player"))
    }

    @Test
    fun `text with no level answers null`() {
        assertNull(SkyBlockLevelColours.levelIn("§6Player"))
        assertNull(SkyBlockLevelColours.levelIn("§8[§bMVP§8] §6Player"), "a rank is not a level")
    }

    /**
     * A bracketed number that is not somebody's level is left alone.
     *
     * The tab list on Hypixel is mostly not players: stat readouts, headers and counters share it, and one of
     * them containing a number in brackets is not unlikely. A level always leads the line, so anchoring is
     * what keeps this from repainting the middle of a stat.
     */
    @Test
    fun `a bracketed number mid-line is not a level`() {
        assertNull(SkyBlockLevelColours.levelIn("§7Bank: §6[1,000,000]"))
        assertNull(SkyBlockLevelColours.levelIn("§aGarden §7Visitors [12]"))
    }

    /** A tab-list entry may be indented, and it is still a level. */
    @Test
    fun `leading space does not hide a level`() {
        assertEquals(287, SkyBlockLevelColours.levelIn(" §8[§b287§8] §aPlayer"))
    }

    /**
     * The range covers the digits and nothing else.
     *
     * A caller recolours what this points at. Including the brackets would repaint the grey Hypixel chose for
     * them, and including the formatting codes would put a colour in the middle of one.
     */
    @Test
    fun `the range covers only the digits`() {
        val text = "§8[§b480§8] §6Player"

        val range = SkyBlockLevelColours.levelRangeIn(text)

        assertEquals("480", range?.let { text.substring(it) })
    }
}
