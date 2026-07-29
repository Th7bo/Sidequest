package dev.th7bo.sidequest.platform.core.context

import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.core.parser.ScoreboardParser
import dev.th7bo.sidequest.platform.core.parser.TabListParser
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.parser.HypixelText
import dev.th7bo.sidequest.platform.parser.ScoreboardSnapshot
import dev.th7bo.sidequest.platform.parser.TabListSnapshot
import dev.th7bo.sidequest.platform.skyblock.ContextConfidence
import dev.th7bo.sidequest.platform.skyblock.ContextEvent
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.IslandChangedEvent
import dev.th7bo.sidequest.platform.skyblock.ProfileChangedEvent
import dev.th7bo.sidequest.platform.skyblock.ServerChangedEvent
import dev.th7bo.sidequest.platform.skyblock.ServerId
import dev.th7bo.sidequest.platform.skyblock.SkyBlockJoinEvent
import dev.th7bo.sidequest.platform.skyblock.SkyBlockLeaveEvent
import dev.th7bo.sidequest.platform.skyblock.SkyBlockProfile
import dev.th7bo.sidequest.platform.skyblock.SubLocation
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Location detection, against recorded Hypixel output.
 *
 * The fixtures are the point. Parser rules are the part of a SkyBlock mod most likely to
 * be quietly wrong, and the only way to know they work is to run them against what the
 * game actually sends — including the formatting codes and invisible padding that make
 * the lines look nothing like what a player sees.
 */
class GameContextTest {

    private lateinit var events: DefaultEventBus
    private lateinit var service: DefaultGameContextService
    private val seen = mutableListOf<String>()

    private val owner = OwnerId(SqId.sidequest("test"))

    @BeforeEach
    fun setUp() {
        events = DefaultEventBus(TestScheduler(), NoopLogger)
        service = DefaultGameContextService(events, NoopLogger)
        seen.clear()
        events.on<ContextEvent>(owner) { seen.add(it.eventName + ": " + it.describe()) }
    }

    // ---------------------------------------------------------------
    // Fixtures — as the game sends them, padding and all
    // ---------------------------------------------------------------

    private fun scoreboard(
        title: String = "§e§lSKYBLOCK",
        vararg lines: String,
    ) = ScoreboardSnapshot(title, lines.toList())

    private val dwarvenMinesScoreboard = scoreboard(
        "§e§lSKYBLOCK",
        "§707/16/24 §7mini123A",
        " §7⏣ §bDwarven Mines",
        "§eDaily Reward: §a§lREADY!",
        " ",
        "Purse: §65,000,000",
    )

    private val hubScoreboard = scoreboard(
        "§e§lSKYBLOCK",
        "§707/16/24 §7mini77B",
        " §7⏣ §bVillage",
        "Purse: §61,000",
    )

    private val riftScoreboard = scoreboard(
        "§e§lSKYBLOCK",
        "§707/16/24 §7mini99C",
        " §5ф §dWizard Tower",
        "Motes: §d1,234",
    )

    private fun tabList(vararg entries: String) = TabListSnapshot(entries.toList())

    private val dwarvenMinesTab = tabList(
        "§b§lArea: §fDwarven Mines",
        "§b§lServer: §fmini123A",
        "§b§lProfile: §fMango",
        "§a[MVP§c+§a] Th7bo",
    )

    // ---------------------------------------------------------------
    // Cleaning
    // ---------------------------------------------------------------

    @Test
    fun `formatting codes and invisible padding are removed`() {
        assertEquals("Purse: 5,000,000", HypixelText.clean("§7Purse: §65,000,000"))
        // Zero-width characters and non-breaking spaces are the padding that genuinely
        // occupies no space, and they land in the middle of a match if left in.
        assertEquals("Dwarven Mines", HypixelText.clean("§bDwarven\u200B Mines"))
        assertEquals("", HypixelText.clean("§7\uFEFF \u00A0 "))
    }

    @Test
    fun `cleaning collapses the runs of spaces Hypixel leaves behind`() {
        assertEquals("Area: Hub", HypixelText.clean("§bArea:   §fHub  "))
    }

    // ---------------------------------------------------------------
    // Scoreboard
    // ---------------------------------------------------------------

    @Test
    fun `the scoreboard yields the area and the server`() {
        val reading = ScoreboardParser.parse(dwarvenMinesScoreboard)

        assertTrue(reading.isSkyBlock)
        assertEquals(SubLocation("Dwarven Mines"), reading.subLocation)
        assertEquals(ServerId("mini123A"), reading.serverId)
        assertFalse(reading.isGuest)
    }

    @Test
    fun `the Rift's own area symbol is recognised`() {
        // A different symbol and a different colour. Matching only the overworld one would
        // leave the Rift with no area at all.
        assertEquals(SubLocation("Wizard Tower"), ScoreboardParser.parse(riftScoreboard).subLocation)
    }

    @Test
    fun `the area symbol is not assumed, because Hypixel keeps changing it`() {
        // Hypixel's own texture pack moved the area marker to a private-use glyph. A
        // parser with the old character hardcoded would silently stop matching — no
        // error, just a feature that never fires again.
        val texturePack = scoreboard("§e§lSKYBLOCK", " §7 §bDwarven Mines")
        assertEquals(SubLocation("Dwarven Mines"), ScoreboardParser.parse(texturePack).subLocation)

        val anythingElse = scoreboard("§e§lSKYBLOCK", " §7◆ §bDwarven Mines")
        assertEquals(SubLocation("Dwarven Mines"), ScoreboardParser.parse(anythingElse).subLocation)
    }

    @Test
    fun `a private-use glyph is content, not padding`() {
        // Cleaning used to strip the whole private-use range as invisible padding, which
        // would have deleted every icon in Hypixel's texture pack.
        assertEquals(" 100/100", HypixelText.clean("§c §f100/100"))
    }

    @Test
    fun `a profile symbol Hypixel changes does not break the name`() {
        val known = tabList("§b§lProfile: §fMango ♲")
        val unknown = tabList("§b§lProfile: §fMango ")

        assertEquals(SkyBlockProfile("Mango"), TabListParser.parse(known).profile)
        assertEquals(
            SkyBlockProfile("Mango"),
            TabListParser.parse(unknown).profile,
            "a new game-mode symbol must fall outside the name, not break the match",
        )
    }

    @Test
    fun `a non-SkyBlock scoreboard reports not being in SkyBlock`() {
        val bedwars = scoreboard("§e§lBED WARS", "§7Map: §fVillage", "§7Players: §a8")
        assertFalse(ScoreboardParser.parse(bedwars).isSkyBlock)
    }

    @Test
    fun `an event title still counts as SkyBlock`() {
        // Hypixel rewrites the title for every event. A strict list would decide the
        // player had left SkyBlock every December.
        val seasonal = scoreboard("§e§lSKYBLOCK CO-OP", " §7⏣ §bVillage")
        assertTrue(ScoreboardParser.parse(seasonal).isSkyBlock)
    }

    @Test
    fun `visiting is read from the title`() {
        val guest = scoreboard("§e§lSKYBLOCK GUEST", " §7⏣ §bPrivate Island")
        assertTrue(ScoreboardParser.parse(guest).isGuest)
    }

    @Test
    fun `an empty scoreboard says nothing rather than guessing`() {
        val reading = ScoreboardParser.parse(ScoreboardSnapshot.Empty)
        assertFalse(reading.isSkyBlock)
        assertEquals(null, reading.subLocation)
    }

    // ---------------------------------------------------------------
    // Tab list
    // ---------------------------------------------------------------

    @Test
    fun `the tab list yields the island, server and profile`() {
        val reading = TabListParser.parse(dwarvenMinesTab)

        assertEquals(Island.DWARVEN_MINES, reading.island)
        assertEquals(ServerId("mini123A"), reading.serverId)
        assertEquals(SkyBlockProfile("Mango"), reading.profile)
    }

    @Test
    fun `a game-mode symbol is not part of the profile name`() {
        // Otherwise the same profile reads as two different ones depending on the mode.
        val ironman = tabList("§b§lProfile: §fMango ♲")
        assertEquals(SkyBlockProfile("Mango"), TabListParser.parse(ironman).profile)
    }

    @Test
    fun `the dungeon widget names the island differently`() {
        val inDungeon = tabList("§b§lDungeon: §fThe Catacombs", "§b§lServer: §fmini5X")
        val reading = TabListParser.parse(inDungeon)

        assertTrue(reading.isInDungeon)
        assertEquals(Island.CATACOMBS, reading.island)
    }

    @Test
    fun `an island Hypixel has added but we have not becomes unknown`() {
        // The failure mode that matters: features stand down on unknown, and would
        // misbehave on a wrong answer.
        val newIsland = tabList("§b§lArea: §fSome New Island")
        assertEquals(Island.UNKNOWN, TabListParser.parse(newIsland).island)
    }

    // ---------------------------------------------------------------
    // Merging
    // ---------------------------------------------------------------

    @Test
    fun `both sources agreeing is confirmed`() {
        service.onScoreboard(dwarvenMinesScoreboard)
        service.onTabList(dwarvenMinesTab)

        val context = service.context
        assertTrue(context.isInSkyBlock)
        assertEquals(Island.DWARVEN_MINES, context.island)
        assertEquals(SubLocation("Dwarven Mines"), context.subLocation)
        assertEquals(SkyBlockProfile("Mango"), context.profile)
        assertEquals(ContextConfidence.CONFIRMED, context.confidence)
        assertTrue(context.isReliable)
    }

    @Test
    fun `the scoreboard alone is only a guess`() {
        service.onScoreboard(dwarvenMinesScoreboard)

        assertEquals(ContextConfidence.GUESSED, service.context.confidence)
        assertFalse(service.context.isReliable, "features that act should wait for corroboration")
        assertEquals(Island.UNKNOWN, service.context.island, "the scoreboard never names the island")
    }

    @Test
    fun `a source that says nothing does not clear what is known`() {
        // The bug this prevents: a tab list that has not populated yet blanking the
        // profile a second after joining, which reads as flickering state.
        service.onScoreboard(dwarvenMinesScoreboard)
        service.onTabList(dwarvenMinesTab)
        assertEquals(SkyBlockProfile("Mango"), service.context.profile)

        service.onTabList(tabList("§a[MVP§c+§a] Th7bo"))

        assertEquals(SkyBlockProfile("Mango"), service.context.profile, "the widget went away, the profile did not")
        assertEquals(Island.DWARVEN_MINES, service.context.island)
    }

    @Test
    fun `leaving SkyBlock clears the island`() {
        service.onScoreboard(dwarvenMinesScoreboard)
        service.onTabList(dwarvenMinesTab)

        service.onScoreboard(scoreboard("§e§lBED WARS", "§7Map: §fVillage"))

        assertFalse(service.context.isInSkyBlock)
        assertEquals(Island.NONE, service.context.island, "a stale island must not survive")
    }

    @Test
    fun `visiting produces the guest variant of the island`() {
        service.onScoreboard(scoreboard("§e§lSKYBLOCK GUEST", " §7⏣ §bPrivate Island"))
        service.onTabList(tabList("§b§lArea: §fPrivate Island"))

        assertEquals(Island.PRIVATE_ISLAND_GUEST, service.context.island)
        assertTrue(service.context.isGuest)
    }

    // ---------------------------------------------------------------
    // Hypixel's own answer
    // ---------------------------------------------------------------

    @Test
    fun `the Mod API names the island and is believed outright`() {
        service.onHypixelLocation(isSkyBlock = true, islandApiName = "mining_3", serverName = "mini123A", isLobby = false)

        assertEquals(Island.DWARVEN_MINES, service.context.island)
        assertEquals(ServerId("mini123A"), service.context.serverId)
        assertEquals(
            ContextConfidence.CONFIRMED,
            service.context.confidence,
            "there is nothing to corroborate when the answer came from the server",
        )
    }

    @Test
    fun `Hypixel's answer beats the scraped one`() {
        // The scraped sources lag a server hop. When they disagree, the packet is right.
        service.onScoreboard(hubScoreboard)
        service.onTabList(tabList("§b§lArea: §fHub"))
        assertEquals(Island.HUB, service.context.island)

        service.onHypixelLocation(isSkyBlock = true, islandApiName = "mining_3", serverName = "mini9Z", isLobby = false)

        assertEquals(Island.DWARVEN_MINES, service.context.island)
    }

    @Test
    fun `a lobby is not SkyBlock even when the game type says so`() {
        service.onHypixelLocation(isSkyBlock = true, islandApiName = null, serverName = "lobby1", isLobby = true)

        assertFalse(service.context.isInSkyBlock)
    }

    @Test
    fun `an island id Hypixel adds resolves to unknown rather than a wrong guess`() {
        service.onHypixelLocation(isSkyBlock = true, islandApiName = "some_new_mode", serverName = "mini1A", isLobby = false)

        assertEquals(Island.UNKNOWN, service.context.island)
        assertTrue(service.context.isInSkyBlock, "still in SkyBlock — just somewhere we do not know")
    }

    @Test
    fun `without the Mod API the scraped sources still work`() {
        // The fallback has to keep standing on its own, or the optional dependency
        // quietly becomes a required one.
        service.onScoreboard(dwarvenMinesScoreboard)
        service.onTabList(dwarvenMinesTab)

        assertEquals(Island.DWARVEN_MINES, service.context.island)
        assertEquals(ContextConfidence.CONFIRMED, service.context.confidence)
    }

    // ---------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------

    @Test
    fun `entering SkyBlock is announced once`() {
        service.onScoreboard(dwarvenMinesScoreboard)
        service.onTabList(dwarvenMinesTab)
        service.onScoreboard(hubScoreboard)

        assertEquals(1, seen.count { it.startsWith("SkyBlockJoinEvent") })
    }

    @Test
    fun `an island change is announced with both sides`() {
        service.onScoreboard(dwarvenMinesScoreboard)
        service.onTabList(dwarvenMinesTab)
        seen.clear()

        service.onTabList(tabList("§b§lArea: §fHub", "§b§lServer: §fmini77B"))

        val change = seen.single { it.startsWith("IslandChangedEvent") }
        assertTrue(change.contains("Dwarven Mines -> Hub"), "got: $change")
    }

    @Test
    fun `a server hop within one island is a server change, not an island change`() {
        // The distinction anything caching per-instance state depends on.
        service.onScoreboard(hubScoreboard)
        service.onTabList(tabList("§b§lArea: §fHub", "§b§lServer: §fmini77B"))
        seen.clear()

        service.onTabList(tabList("§b§lArea: §fHub", "§b§lServer: §fmini88C"))

        assertTrue(seen.any { it.startsWith("ServerChangedEvent") })
        assertFalse(seen.any { it.startsWith("IslandChangedEvent") })
    }

    @Test
    fun `leaving SkyBlock is announced`() {
        service.onScoreboard(dwarvenMinesScoreboard)
        service.onTabList(dwarvenMinesTab)
        seen.clear()

        service.reset()

        assertTrue(seen.any { it.startsWith("SkyBlockLeaveEvent") })
    }

    @Test
    fun `a listener sees the new context while being told about the change`() {
        // A listener handed the new value while reading the old one from the service
        // would be a trap nobody expects.
        var islandDuringEvent: Island? = null
        events.on<IslandChangedEvent>(owner) { islandDuringEvent = service.context.island }

        service.onScoreboard(dwarvenMinesScoreboard)
        service.onTabList(dwarvenMinesTab)

        assertEquals(Island.DWARVEN_MINES, islandDuringEvent)
    }

    @Test
    fun `an unchanged snapshot announces nothing`() {
        service.onScoreboard(dwarvenMinesScoreboard)
        service.onTabList(dwarvenMinesTab)
        seen.clear()

        repeat(20) {
            service.onScoreboard(dwarvenMinesScoreboard)
            service.onTabList(dwarvenMinesTab)
        }

        assertTrue(seen.isEmpty(), "this runs every tick; an unchanged board must cost nothing")
    }

    @Test
    fun `the profile is announced when it first becomes known`() {
        service.onScoreboard(dwarvenMinesScoreboard)
        service.onTabList(dwarvenMinesTab)

        assertTrue(seen.any { it.startsWith("ProfileChangedEvent") && it.contains("Mango") })
    }

    // ---------------------------------------------------------------
    // The question features actually ask
    // ---------------------------------------------------------------

    @Test
    fun `hazardous islands are flagged so nothing interrupts a run`() {
        service.onScoreboard(scoreboard("§e§lSKYBLOCK", " §7⏣ §bThe Catacombs"))
        service.onTabList(tabList("§b§lDungeon: §fThe Catacombs"))

        assertTrue(service.context.isHazardous, "a cinematic here costs someone the run")
        assertTrue(service.context.isBusy)
    }

    @Test
    fun `an ordinary island is neither busy nor hazardous`() {
        service.onScoreboard(hubScoreboard)
        service.onTabList(tabList("§b§lArea: §fHub"))

        assertFalse(service.context.isBusy)
        assertFalse(service.context.isHazardous)
    }
}
