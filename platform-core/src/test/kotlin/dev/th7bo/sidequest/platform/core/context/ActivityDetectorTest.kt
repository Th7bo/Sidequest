package dev.th7bo.sidequest.platform.core.context

import dev.th7bo.sidequest.platform.core.parser.ScoreboardParser
import dev.th7bo.sidequest.platform.core.parser.TabListParser
import dev.th7bo.sidequest.platform.parser.ScoreboardSnapshot
import dev.th7bo.sidequest.platform.parser.TabListSnapshot
import dev.th7bo.sidequest.platform.skyblock.Activity
import dev.th7bo.sidequest.platform.skyblock.ContextConfidence
import dev.th7bo.sidequest.platform.skyblock.Island
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Activity detection.
 *
 * The plan asks for confidence levels and a fallback to unknown rather than invented state, and the
 * tests here are mostly about that: the interesting assertions are not "mining is detected" but
 * "standing in the Hub does not claim to know what the player is doing".
 */
class ActivityDetectorTest {

    private fun detect(
        island: Island = Island.HUB,
        scoreboardLines: List<String> = emptyList(),
        tabEntries: List<String> = emptyList(),
        isMoving: Boolean = true,
    ) = ActivityDetector.detect(
        island = island,
        scoreboard = ScoreboardParser.parse(ScoreboardSnapshot("§e§lSKYBLOCK", scoreboardLines)),
        tabList = TabListParser.parse(TabListSnapshot(tabEntries)),
        isMoving = isMoving,
    )

    // ---------------------------------------------------------------
    // What the game says outright
    // ---------------------------------------------------------------

    @Test
    fun `a named floor is a confirmed dungeon run`() {
        val reading = detect(Island.CATACOMBS, scoreboardLines = listOf(" §7⏣ §cThe Catacombs §8(F7)"))
        assertEquals(Activity.DUNGEONS, reading.activity)
        assertEquals(ContextConfidence.CONFIRMED, reading.confidence)
        assertTrue(reading.isReliable)
    }

    @Test
    fun `a named tier is a confirmed Kuudra run`() {
        val reading = detect(Island.KUUDRA_ARENA, scoreboardLines = listOf(" §7⏣ §cKuudra's Hollow §8(T5)"))
        assertEquals(Activity.KUUDRA, reading.activity)
        assertEquals(ContextConfidence.CONFIRMED, reading.confidence)
    }

    /**
     * The slayer widget is everywhere; a quest in it is not.
     *
     * The widget sits on the board across most of SkyBlock, so its mere presence says nothing. What
     * counts is whether it has lines under it, which it only does while a quest is active.
     */
    @Test
    fun `an active slayer quest is confirmed, an empty widget is not`() {
        val active = detect(tabEntries = listOf("Slayer:", " Revenant Horror IV", " Slay 4 Zombies"))
        assertEquals(Activity.SLAYER, active.activity)
        assertEquals(ContextConfidence.CONFIRMED, active.confidence)

        val idle = detect(tabEntries = listOf("Slayer:"))
        assertFalse(idle.activity == Activity.SLAYER)
    }

    // ---------------------------------------------------------------
    // What the tab list implies
    // ---------------------------------------------------------------

    @Test
    fun `a widget that only appears where the activity happens is probable`() {
        val mining = detect(Island.DWARVEN_MINES, tabEntries = listOf("Commissions:", " Titanium: 40%"))
        assertEquals(Activity.MINING, mining.activity)
        assertEquals(ContextConfidence.PROBABLE, mining.confidence)

        val garden = detect(Island.GARDEN, tabEntries = listOf("Visitors: (3)", " Jerry"))
        assertEquals(Activity.GARDEN, garden.activity)
        assertEquals(ContextConfidence.PROBABLE, garden.confidence)
    }

    /**
     * A widget beats the island, which is the point of reading them.
     *
     * Standing on the Crimson Isle says nothing; a `Trapper:` widget says the player is questing.
     */
    @Test
    fun `a widget outranks the island it appears on`() {
        val reading = detect(Island.CRIMSON_ISLE, tabEntries = listOf("Trapper:", " Kill a Hunting Mob"))
        assertEquals(Activity.CRIMSON_QUESTING, reading.activity)
        assertEquals(ContextConfidence.PROBABLE, reading.confidence)
    }

    @Test
    fun `the Rift is recognised from its own widget`() {
        assertEquals(Activity.RIFT, detect(Island.THE_RIFT, tabEntries = listOf("Good to know:", " Time is short")).activity)
    }

    @Test
    fun `the forge is its own activity`() {
        assertEquals(Activity.FORGE, detect(Island.DWARVEN_MINES, tabEntries = listOf("Forges:", " Slot 1: Refined Diamond")).activity)
    }

    // ---------------------------------------------------------------
    // The island alone
    // ---------------------------------------------------------------

    @Test
    fun `an island that means one thing is a guess, not a certainty`() {
        val reading = detect(Island.CRYSTAL_HOLLOWS)
        assertEquals(Activity.MINING, reading.activity)
        assertEquals(ContextConfidence.GUESSED, reading.confidence)
        assertFalse(reading.isReliable, "a guess is for display, not for acting on")
    }

    // ---------------------------------------------------------------
    // Not knowing
    // ---------------------------------------------------------------

    /** The Hub is where players do everything and nothing, and claiming otherwise would be invention. */
    @Test
    fun `standing in the Hub is exploring, at low confidence`() {
        val reading = detect(Island.HUB)
        assertEquals(Activity.EXPLORING, reading.activity)
        assertEquals(ContextConfidence.GUESSED, reading.confidence)
    }

    @Test
    fun `no island at all is unknown, not exploring`() {
        assertEquals(Activity.UNKNOWN, detect(Island.NONE).activity)
        assertEquals(Activity.UNKNOWN, detect(Island.UNKNOWN).activity)
        assertEquals(ContextConfidence.NONE, detect(Island.NONE).confidence)
    }

    /**
     * Idle is only claimed where it can be told from busy.
     *
     * A still player on a private island is AFK; a still player in a dungeon is fighting something.
     * Only the first is claimed.
     */
    @Test
    fun `a still player on a personal island is idle`() {
        assertEquals(Activity.IDLE, detect(Island.PRIVATE_ISLAND, isMoving = false).activity)
        assertEquals(Activity.EXPLORING, detect(Island.PRIVATE_ISLAND, isMoving = true).activity)
    }

    @Test
    fun `a still player somewhere else is not idle`() {
        assertEquals(Activity.EXPLORING, detect(Island.HUB, isMoving = false).activity)
    }

    // ---------------------------------------------------------------
    // The reason
    // ---------------------------------------------------------------

    /**
     * Every verdict says what decided it.
     *
     * Detection is a pile of heuristics, and when one is wrong the only useful question is "what made
     * you think that". Reconstructing the answer from a log afterwards is not the same as having it.
     */
    @Test
    fun `every reading carries the signal that decided it`() {
        assertTrue("F7" in detect(Island.CATACOMBS, listOf(" §7⏣ §cThe Catacombs §8(F7)")).reason)
        assertTrue("COMMISSIONS" in detect(tabEntries = listOf("Commissions:")).reason)
        assertTrue("Crystal Hollows" in detect(Island.CRYSTAL_HOLLOWS).reason)
        assertEquals("", detect(Island.NONE).reason)
    }

    // ---------------------------------------------------------------
    // Interruption policy
    // ---------------------------------------------------------------

    /** The distinction the island alone cannot make: being on the Crimson Isle versus mid-Kuudra. */
    @Test
    fun `only the demanding activities are demanding`() {
        assertTrue(Activity.DUNGEONS.isDemanding)
        assertTrue(Activity.KUUDRA.isDemanding)
        assertTrue(Activity.SLAYER.isDemanding)
        assertFalse(Activity.MINING.isDemanding)
        assertFalse(Activity.EXPLORING.isDemanding)
        assertFalse(Activity.UNKNOWN.isDemanding)
    }
}
