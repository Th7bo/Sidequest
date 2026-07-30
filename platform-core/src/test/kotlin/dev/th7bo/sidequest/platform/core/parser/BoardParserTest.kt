package dev.th7bo.sidequest.platform.core.parser

import dev.th7bo.sidequest.platform.parser.ScoreboardSnapshot
import dev.th7bo.sidequest.platform.parser.TabListLayout
import dev.th7bo.sidequest.platform.parser.TabListSnapshot
import dev.th7bo.sidequest.platform.parser.TabWidget
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.ProfileType
import dev.th7bo.sidequest.platform.skyblock.SubLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The board parsers, beyond location.
 *
 * `GameContextTest` covers what the boards say about *where* the player is. This covers the
 * rest of what the plan asks the parsers for: key/value extraction, dungeon and Kuudra
 * detection, the tab list's widget structure, and the two layout quirks that make the tab
 * list harder than it looks.
 *
 * The distinction between what is a fixture and what is a construction matters here. The
 * scoreboard lines are recorded Hypixel output, taken from SkyHanni's `REGEX-TEST` comments.
 * The tab lists are **constructed** — they exercise a splitting algorithm that is ours, not
 * an observation about Hypixel, and building one by hand is the only way to cover a column
 * boundary at all.
 */
class BoardParserTest {

    private fun scoreboard(vararg lines: String, title: String = "§e§lSKYBLOCK") =
        ScoreboardParser.parse(ScoreboardSnapshot(title, lines.toList()))

    private fun tabList(vararg entries: String) = TabListParser.parse(TabListSnapshot(entries.toList()))

    // ---------------------------------------------------------------
    // Scoreboard: key/value
    // ---------------------------------------------------------------

    @Test
    fun `every key-value line on the board is available by key`() {
        val reading = scoreboard(
            "§707/16/24 §7mini123A",
            " §7⏣ §bVillage",
            "Purse: §6423,085,776",
            "Bits: §b140,965",
        )
        assertEquals("423,085,776", reading.value("Purse"))
        assertEquals("140,965", reading.value("bits"))
        assertNull(reading.value("Motes"))
    }

    @Test
    fun `numbers lose their separators`() {
        val reading = scoreboard("Purse: §6423,085,776 §e(+5)", "Bits: §b140,965")
        assertEquals(423_085_776L, reading.purse)
        assertEquals(140_965L, reading.bits)
    }

    /** Hypixel shows a piggy bank instead of a purse once the player has one. */
    @Test
    fun `a piggy bank is read as the purse`() {
        assertEquals(423_085_766L, scoreboard("Piggy: §6423,085,766").purse)
    }

    /**
     * An abbreviated amount reads as nothing rather than as the wrong number.
     *
     * `1.5k` parsed by stripping punctuation would come out as 15, which is worse than not
     * knowing.
     */
    @Test
    fun `an abbreviated amount is refused`() {
        assertNull(scoreboard("Sowdust: §26.5k §7(+912)").number("Sowdust"))
        // The raw value keeps the gain Hypixel prints after it; only the parse refuses.
        assertEquals("6.5k (+912)", scoreboard("Sowdust: §26.5k §7(+912)").value("Sowdust"))
    }

    @Test
    fun `the date and time of day are read`() {
        val reading = scoreboard(" Early Spring 13th", " §78:50am §b☽")
        assertEquals("Early Spring 13th", reading.date)
        assertEquals("8:50am", reading.timeOfDay)
    }

    /**
     * The weather symbol is not matched.
     *
     * `☽ ☀ ⚡ ☔` are what it has been, and Hypixel's texture pack has been moving symbols
     * like these into private-use glyphs. Whatever follows the time is allowed through.
     */
    @Test
    fun `the time survives a symbol we have never seen`() {
        assertEquals("5:50am", scoreboard(" §75:50am §b").timeOfDay)
    }

    // ---------------------------------------------------------------
    // Scoreboard: dungeon and Kuudra
    // ---------------------------------------------------------------

    @Test
    fun `a dungeon floor is read off the area line`() {
        val reading = scoreboard(" §7⏣ §cThe Catacombs §8(F7)")
        assertEquals("F7", reading.dungeonFloor)
        assertTrue(reading.isInDungeon)
        assertFalse(reading.isInKuudra)
        assertNull(reading.kuudraTier)
    }

    @Test
    fun `a master mode floor is read as Hypixel words it`() {
        assertEquals("M3", scoreboard(" §7⏣ §cThe Catacombs §8(M3)").dungeonFloor)
    }

    @Test
    fun `a Kuudra tier is a tier, not a floor`() {
        val reading = scoreboard(" §7⏣ §cKuudra's Hollow §8(T5)")
        assertEquals(5, reading.kuudraTier)
        assertTrue(reading.isInKuudra)
        assertNull(reading.dungeonFloor)
    }

    /** An ordinary area has neither, and must not acquire one from a passing bracket. */
    @Test
    fun `an ordinary area has no floor and no tier`() {
        val reading = scoreboard(" §7⏣ §bDwarven Mines")
        assertNull(reading.dungeonFloor)
        assertNull(reading.kuudraTier)
        assertEquals(SubLocation("Dwarven Mines"), reading.subLocation)
    }

    // ---------------------------------------------------------------
    // Scoreboard: profile type
    // ---------------------------------------------------------------

    /**
     * Read from the word, not the symbol.
     *
     * SkyHanni matches `♲ ☀ Ⓑ`; those are exactly the characters the texture pack has been
     * replacing. The words have not changed, so this also works for a glyph nobody has seen.
     */
    @Test
    fun `the game mode is read from the word beside the symbol`() {
        assertEquals(ProfileType.IRONMAN, scoreboard(" §7♲ §7Ironman").profileType)
        assertEquals(ProfileType.STRANDED, scoreboard(" §a☀ §aStranded").profileType)
        assertEquals(ProfileType.BINGO, scoreboard(" §9Ⓑ §9Bingo").profileType)
        assertEquals(ProfileType.BINGO, scoreboard(" §9 §9Bingo").profileType)
    }

    @Test
    fun `a normal profile says nothing, and nothing is not a mode`() {
        assertNull(scoreboard(" §7⏣ §bVillage", "Purse: §60").profileType)
    }

    // ---------------------------------------------------------------
    // Tab list: widget structure
    // ---------------------------------------------------------------

    @Test
    fun `a widget owns the lines between its header and the next`() {
        val reading = tabList(
            "Info",
            " Area: Dwarven Mines",
            " Server: mini123A",
            "Commissions:",
            " Titanium: 40%",
            " Mithril: 12%",
            "Powders:",
            " Mithril: 1,000",
        )

        assertEquals(listOf("Titanium: 40%", "Mithril: 12%"), reading.linesOf(TabWidget.COMMISSIONS))
        assertEquals(listOf("Mithril: 1,000"), reading.linesOf(TabWidget.POWDER))
        assertTrue(reading.has(TabWidget.COMMISSIONS))
        assertFalse(reading.has(TabWidget.VISITORS))
    }

    /** Which widgets are present is the activity clue the plan asks for. */
    @Test
    fun `the widgets present say what the player is doing`() {
        val mining = tabList("Info", " Area: Dwarven Mines", "Commissions:", " Titanium: 40%")
        assertTrue(mining.has(TabWidget.COMMISSIONS))

        val garden = tabList("Info", " Area: Garden", "Visitors: (3)", " Jerry", "Composter:", " Fuel: 40%")
        assertTrue(garden.has(TabWidget.VISITORS))
        assertFalse(garden.has(TabWidget.COMMISSIONS))
    }

    /** Hypixel indents a widget's values; the indentation is decoration and is trimmed. */
    @Test
    fun `empty entries do not end a widget`() {
        val reading = tabList("Commissions:", " Titanium: 40%", "", " Mithril: 12%")
        assertEquals(listOf("Titanium: 40%", "Mithril: 12%"), reading.linesOf(TabWidget.COMMISSIONS))
    }

    @Test
    fun `lines before the first header belong to nobody`() {
        val reading = tabList("padding", " more padding", "Commissions:", " Titanium: 40%")
        assertEquals(listOf("Titanium: 40%"), reading.linesOf(TabWidget.COMMISSIONS))
        assertEquals(setOf(TabWidget.COMMISSIONS), reading.widgets.keys)
    }

    /**
     * A widget split across a column boundary keeps both halves.
     *
     * Hypixel repeats the header when a long widget runs past the end of a column. Treating
     * the repeat as a new widget would throw away the first half — which for the party
     * widget means losing half the party.
     */
    @Test
    fun `a widget repeated across a column keeps both halves`() {
        val reading = tabList("Party:", " Alice", "Party:", " Bob")
        assertEquals(listOf("Alice", "Bob"), reading.linesOf(TabWidget.PARTY))
        assertEquals(listOf("Alice", "Bob"), reading.partyMembers)
    }

    // ---------------------------------------------------------------
    // Tab list: layout quirks
    // ---------------------------------------------------------------

    /**
     * The column headings are repeated and must not be read as widgets.
     *
     * `Players (43)` appears again at the top of columns two, three and four. Each repeat
     * would otherwise start a fresh widget and every widget after it would be attributed to
     * the wrong one.
     */
    @Test
    fun `repeated column headings are dropped`() {
        val entries = MutableList(TabListLayout.SIZE) { "" }
        entries[0] = "Players (43)"
        entries[1] = " Alice"
        entries[20] = "Players (43)"
        entries[21] = "Commissions:"
        entries[22] = " Titanium: 40%"

        val normalised = TabListLayout.normalise(entries)
        assertEquals(1, normalised.count { it == "Players (43)" })

        val reading = TabListParser.parse(TabListSnapshot(entries))
        assertEquals(listOf("Titanium: 40%"), reading.linesOf(TabWidget.COMMISSIONS))
        assertEquals(listOf("Alice"), reading.players)
    }

    /** A repeat that is not at a column start is a genuine second widget, and is kept. */
    @Test
    fun `a heading away from a column start is not a duplicate`() {
        val entries = MutableList(TabListLayout.SIZE) { "" }
        entries[0] = "Players (43)"
        entries[5] = "Players (2)"

        assertEquals(2, TabListLayout.normalise(entries).count { it.startsWith("Players") })
    }

    @Test
    fun `entries past the board are not part of it`() {
        val entries = MutableList(TabListLayout.SIZE + 10) { "" }
        entries[TabListLayout.SIZE + 5] = "Commissions:"

        assertEquals(TabListLayout.SIZE, TabListLayout.normalise(entries).size)
        assertFalse(TabListParser.parse(TabListSnapshot(entries)).has(TabWidget.COMMISSIONS))
    }

    // ---------------------------------------------------------------
    // Tab list: players and party
    // ---------------------------------------------------------------

    @Test
    fun `player names lose their rank tags`() {
        val reading = tabList(
            "Players (3)",
            " §b[MVP§d+§b] lrg89",
            " §7nea89o",
            " §8[§b209§8] §b[MVP§d+§b] Throwpo",
        )
        assertEquals(listOf("lrg89", "nea89o", "Throwpo"), reading.players)
        assertEquals(3, reading.playerCount)
    }

    /** On a private island the visitors are counted separately, and both counts matter. */
    @Test
    fun `guests are counted alongside players`() {
        val reading = tabList("Players (2)", " Alice", " Bob", "Guests (1)", " Carol")
        assertEquals(3, reading.playerCount)
        assertEquals(listOf("Alice", "Bob", "Carol"), reading.players)
    }

    @Test
    fun `a line with no name in it is not a player`() {
        val reading = tabList("Players (1)", " Alice", " §8[§b209§8]")
        assertEquals(listOf("Alice"), reading.players)
    }

    @Test
    fun `the SkyBlock level is read off its own header`() {
        assertEquals(342, tabList("SB Level: [342] 4200").sbLevel)
    }

    @Test
    fun `an absent widget reads as absent, not as empty`() {
        val reading = tabList("Info", " Area: Hub")
        assertNull(reading.playerCount)
        assertEquals(emptyList<String>(), reading.partyMembers)
        assertEquals(Island.HUB, reading.island)
    }
}
