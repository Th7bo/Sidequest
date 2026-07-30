package dev.th7bo.sidequest.platform.core.chat

import dev.th7bo.sidequest.platform.chat.AuctionOutbidEvent
import dev.th7bo.sidequest.platform.chat.AuctionSoldEvent
import dev.th7bo.sidequest.platform.chat.ChatChannel
import dev.th7bo.sidequest.platform.chat.ChatDerivedEvent
import dev.th7bo.sidequest.platform.chat.ChatMessage
import dev.th7bo.sidequest.platform.chat.DropRarity
import dev.th7bo.sidequest.platform.chat.DungeonCompletedEvent
import dev.th7bo.sidequest.platform.chat.HypixelNames
import dev.th7bo.sidequest.platform.chat.PartyInviteEvent
import dev.th7bo.sidequest.platform.chat.PartyJoinedEvent
import dev.th7bo.sidequest.platform.chat.PartyLeaderChangedEvent
import dev.th7bo.sidequest.platform.chat.PartyLeaveReason
import dev.th7bo.sidequest.platform.chat.PartyMemberJoinedEvent
import dev.th7bo.sidequest.platform.chat.PartyMemberLeftEvent
import dev.th7bo.sidequest.platform.chat.PlayerChatEvent
import dev.th7bo.sidequest.platform.chat.RareDropEvent
import dev.th7bo.sidequest.platform.chat.SkillLevelUpEvent
import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The built-in chat rules, against recorded Hypixel lines.
 *
 * Chat is the input most likely to be quietly wrong, and the only test worth anything is
 * one that runs a rule against what the game actually sent — formatting codes, stray
 * resets, texture-pack glyphs and all. Every fixture here came out of SkyHanni's
 * `REGEX-TEST` comments unchanged, so a failure means our matching is wrong, not that the
 * fixture was made up.
 */
class ChatRulesTest {

    private lateinit var events: DefaultEventBus
    private lateinit var parser: DefaultChatParser
    private val seen = mutableListOf<ChatDerivedEvent>()

    private val owner = OwnerId(SqId.sidequest("test"))

    @BeforeEach
    fun setUp() {
        events = DefaultEventBus(TestScheduler(), NoopLogger)
        parser = DefaultChatParser(events, NoopLogger)
        parser.registerAll(HypixelChatRules.all { LOCAL_PLAYER }, OwnerId.PLATFORM)
        seen.clear()
        events.on<ChatDerivedEvent>(owner) { seen.add(it) }
    }

    /** Feeds one line and returns everything the rules made of it. */
    private fun feed(raw: String): List<ChatDerivedEvent> {
        seen.clear()
        parser.onMessage(ChatMessage.of(raw))
        return seen.toList()
    }

    private inline fun <reified T : ChatDerivedEvent> one(raw: String): T {
        val events = feed(raw)
        assertEquals(1, events.size, "expected exactly one event from '$raw', got $events")
        val event = events.single()
        assertTrue(event is T) { "expected ${T::class.simpleName}, got ${event::class.simpleName}" }
        return event as T
    }

    // ---------------------------------------------------------------
    // Fixture self-check
    // ---------------------------------------------------------------

    @Test
    fun `every pattern matches its own fixtures`() {
        val failures = parser.verifyFixtures()
        assertTrue(failures.isEmpty()) { "fixture failures:\n" + failures.joinToString("\n") }
    }

    /**
     * The patterns nobody has a recorded line for.
     *
     * Asserted as an exact set rather than allowed generally, so adding an unverified
     * pattern is a deliberate act that fails this test until it is acknowledged. Both of
     * these come from SkyHanni without a `REGEX-TEST` of their own.
     */
    @Test
    fun `only the known-unverified patterns lack fixtures`() {
        val unverified = parser.patterns().filter { it.fixtures.isEmpty() }.map { it.id.value }.toSet()
        assertEquals(setOf("sidequest:chat.kuudra.complete"), unverified)
    }

    @Test
    fun `no pattern is left unanchored by accident`() {
        val unanchored = parser.patterns().filterNot { it.anchored }.map { it.id.value }
        assertEquals(emptyList<String>(), unanchored)
    }

    /**
     * No line is classified twice.
     *
     * The parser deliberately gives every rule a look rather than stopping at the first
     * match, which is only safe if the patterns do not overlap. This is the assertion that
     * makes that true rather than hoped for.
     */
    @Test
    fun `each fixture is classified by exactly one rule`() {
        for (pattern in parser.patterns()) {
            for (fixture in pattern.fixtures) {
                val matching = parser.patterns().filter { it.matchOrNull(ChatMessage.of(fixture)) != null }
                assertEquals(
                    listOf(pattern.id.value),
                    matching.map { it.id.value },
                    "'$fixture' should be matched only by ${pattern.id}",
                )
            }
        }
    }

    // ---------------------------------------------------------------
    // Channels
    // ---------------------------------------------------------------

    @Test
    fun `party chat names the sender without their rank`() {
        val event = one<PlayerChatEvent>("§9Party §8> §b[MVP§d+§b] lrg89§f: peee")
        assertEquals(ChatChannel.PARTY, event.channel)
        assertEquals("lrg89", event.sender)
        assertEquals("[MVP+] lrg89", event.senderDisplayName)
        assertEquals("peee", event.content)
    }

    @Test
    fun `guild chat drops the guild rank from the name`() {
        val event = one<PlayerChatEvent>("§2Guild > §b[MVP§d+§b] lrg89 §e[Iron]§f: h")
        assertEquals(ChatChannel.GUILD, event.channel)
        assertEquals("lrg89", event.sender)
        assertEquals("h", event.content)
    }

    @Test
    fun `guild chat survives an emblem in front of the rank`() {
        val event = one<PlayerChatEvent>("§2Guild > §6⚔ §6[MVP§3++§6] RealBacklight§f: !warp")
        assertEquals("RealBacklight", event.sender)
    }

    @Test
    fun `coop chat is its own channel`() {
        assertEquals(ChatChannel.COOP, one<PlayerChatEvent>("§bCo-op > §7nea89o§f: hallooooo").channel)
    }

    @Test
    fun `private messages carry their direction`() {
        assertEquals(ChatChannel.PRIVATE_INCOMING, one<PlayerChatEvent>("From nea89o: hiii").channel)
        assertEquals(ChatChannel.PRIVATE_OUTGOING, one<PlayerChatEvent>("To nea89o: lol").channel)
    }

    /** The bug this prevents: reporting a stash pickup as a message from a player named "stash". */
    @Test
    fun `stash pickups are not private messages`() {
        assertEquals(emptyList<ChatDerivedEvent>(), feed("From stash: Pufferfish"))
    }

    @Test
    fun `public chat needs a level or a rank to count as chat`() {
        val event = one<PlayerChatEvent>("[58] §7nea89o§7: haiiiii")
        assertEquals(ChatChannel.ALL, event.channel)
        assertEquals("nea89o", event.sender)
        assertEquals("haiiiii", event.content)
    }

    /**
     * A system message shaped like chat is left alone.
     *
     * `§7Rewards§7: §b…` has the same shape as a public message and is not one. The level
     * or rank requirement is what tells them apart.
     */
    @Test
    fun `a system message shaped like chat is not classified`() {
        assertEquals(emptyList<ChatDerivedEvent>(), feed("§7Rewards§7: §b3 items"))
        assertEquals(emptyList<ChatDerivedEvent>(), feed("§eProfile: §aMango"))
    }

    /** Hypixel omits the trailing colour code on the player's own messages, and only those. */
    @Test
    fun `our own unformatted message is still recognised`() {
        val event = one<PlayerChatEvent>("[302] ♫ [MVP+] $LOCAL_PLAYER: problematic")
        assertEquals(ChatChannel.ALL, event.channel)
        assertEquals(LOCAL_PLAYER, event.sender)
    }

    @Test
    fun `someone else's unformatted message is left unclassified`() {
        assertEquals(emptyList<ChatDerivedEvent>(), feed("[302] ♫ [MVP+] lrg89: problematic"))
    }

    // ---------------------------------------------------------------
    // Party
    // ---------------------------------------------------------------

    @Test
    fun `a party invite names the inviter`() {
        val event = one<PartyInviteEvent>("§r§b[MVP§r§c+§r§b] STPREAPER §r§ehas invited you to join their party!")
        assertEquals("STPREAPER", event.inviter)
        // No structured text was supplied, so there is no click to read a command off.
        assertNull(event.acceptCommand)
    }

    @Test
    fun `an invite from an unranked player is read the same way`() {
        assertEquals(
            "SkyLime1213",
            one<PartyInviteEvent>("§r§7SkyLime1213 §r§ehas invited you to join their party!").inviter,
        )
    }

    @Test
    fun `joining a party names the leader`() {
        assertEquals("Throwpo", one<PartyJoinedEvent>("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!").leader)
    }

    @Test
    fun `members joining and leaving are distinguished by reason`() {
        assertEquals("Throwpo", one<PartyMemberJoinedEvent>("§b[MVP§d+§b] Throwpo §ejoined the party.").member)
        assertEquals(PartyLeaveReason.LEFT, one<PartyMemberLeftEvent>("§7246sweets §ehas left the party.").reason)
        assertEquals(
            PartyLeaveReason.KICKED,
            one<PartyMemberLeftEvent>("§7riblets §ehas been removed from the party.").reason,
        )
        assertEquals(
            PartyLeaveReason.OFFLINE,
            one<PartyMemberLeftEvent>("§eKicked §b[MVP§d+§b] Throwpo§e because they were offline.").reason,
        )
        assertEquals(
            PartyLeaveReason.DISCONNECTED,
            one<PartyMemberLeftEvent>(
                "§b[MVP§d+§b] Throwpo §ewas removed from your party because they disconnected.",
            ).reason,
        )
    }

    @Test
    fun `party finder joins count as members joining`() {
        assertEquals(
            "GhostsTM",
            one<PartyMemberJoinedEvent>(
                "§dParty Finder §f> §bGhostsTM §ejoined the dungeon group! (§bArcher Level 9§e)",
            ).member,
        )
    }

    /**
     * The two transfer lines are told apart.
     *
     * They differ only in `because … left` versus `by …`, and confusing them would report
     * the new leader as the one who left.
     */
    @Test
    fun `a voluntary transfer is not a transfer on leave`() {
        val voluntary = one<PartyLeaderChangedEvent>("The party was transferred to [MVP+] Throwpo by [MVP+] CalMWolfs")
        assertEquals("Throwpo", voluntary.newLeader)
        assertEquals("CalMWolfs", voluntary.previousLeader)

        val onLeave =
            one<PartyLeaderChangedEvent>("The party was transferred to [MVP+] CalMWolfs because [MVP+] Throwpo left")
        assertEquals("CalMWolfs", onLeave.newLeader)
        assertEquals("Throwpo", onLeave.previousLeader)
    }

    // ---------------------------------------------------------------
    // Drops
    // ---------------------------------------------------------------

    @Test
    fun `a bare rare drop reads the item and the magic find`() {
        val event = one<RareDropEvent>("§r§6§lRARE DROP! §r§6§lEnchanted Book §r§b(+208% ✯ Magic Find)")
        assertEquals("Enchanted Book", event.itemName)
        assertEquals(DropRarity.RARE, event.rarity)
        assertEquals(1, event.amount)
        assertEquals(208.0, event.magicFindPercent)
    }

    @Test
    fun `a drop with no magic find reports none rather than zero`() {
        val event = one<RareDropEvent>("§r§6§lRARE DROP! §r§fWither Cloak Sword")
        assertEquals("Wither Cloak Sword", event.itemName)
        assertNull(event.magicFindPercent)
    }

    @Test
    fun `a stacked drop reads its amount`() {
        val event = one<RareDropEvent>("§6§lRARE DROP! §6§lEnchanted Red Mushroom Block §8x3 §r§b(+94.5☀)")
        assertEquals("Enchanted Red Mushroom Block", event.itemName)
        assertEquals(3, event.amount)
    }

    /** A rare crop is the garden's wording for the same tier, not a tier of its own. */
    @Test
    fun `a rare crop is a rare drop`() {
        val event = one<RareDropEvent>("§r§6§lRARE CROP! §r§6§lCropie §r§b(+39.5☀)")
        assertEquals("Cropie", event.itemName)
        assertEquals(DropRarity.RARE, event.rarity)
    }

    @Test
    fun `the parenthesised form reads every rarity Hypixel uses`() {
        assertEquals(
            DropRarity.VERY_RARE,
            one<RareDropEvent>("§9§lVERY RARE DROP!  §r§7(§r§f§r§5Revenant Catalyst§r§7) §r§b(+158% ✯ Magic Find)")
                .rarity,
        )
        assertEquals(DropRarity.CRAZY_RARE, one<RareDropEvent>("CRAZY RARE DROP! (Smite VI) (+158% ✯ Magic Find)").rarity)
        assertEquals(
            DropRarity.INSANE_RARE,
            one<RareDropEvent>("INSANE RARE DROP! (Judgement Core) (+158% ✯ Magic Find)").rarity,
        )
        assertEquals(
            DropRarity.PRAY_TO_RNGESUS,
            one<RareDropEvent>("§5§lPRAY TO RNGESUS DROP!  §r§7(§r§f§r§5Warden Heart§r§7) §r§b(+158% ✯ Magic Find)")
                .rarity,
        )
    }

    @Test
    fun `a parenthesised drop reads the item out of the brackets`() {
        val event = one<RareDropEvent>(
            "§b§lRARE DROP! §r§7(§r§f§r§72x §r§f§r§9Foul Flesh§r§7) §r§b(+158% §r§b✯ Magic Find§r§b)",
        )
        assertEquals("Foul Flesh", event.itemName)
        assertEquals(2, event.amount)
        assertEquals(158.0, event.magicFindPercent)
    }

    @Test
    fun `a pet drop is its own rarity`() {
        val event = one<RareDropEvent>("§6§lPET DROP! §r§5Baby Yeti §r§b(+168% ✯ Magic Find)")
        assertEquals("Baby Yeti", event.itemName)
        assertEquals(DropRarity.PET, event.rarity)
        assertEquals(168.0, event.magicFindPercent)
    }

    // ---------------------------------------------------------------
    // Progression and completion
    // ---------------------------------------------------------------

    @Test
    fun `a skill level-up reads both levels`() {
        val event = one<SkillLevelUpEvent>("  §r§b§lSKILL LEVEL UP §3Farming §827➜§328")
        assertEquals("Farming", event.skill)
        assertEquals(27, event.oldLevel)
        assertEquals(28, event.newLevel)
    }

    /**
     * The indentation is Hypixel's banner. Unindented, the same words are somebody talking.
     *
     * The party-chat case still produces a [PlayerChatEvent] — somebody did say something —
     * and what must not happen is a second, fabricated level-up alongside it.
     */
    @Test
    fun `a player quoting the level-up wording does not level anybody up`() {
        assertEquals(emptyList<ChatDerivedEvent>(), feed("SKILL LEVEL UP Farming 27➜28"))

        val quoted = feed("§9Party §8> §7nea89o§f: SKILL LEVEL UP Farming 27➜28")
        assertEquals(1, quoted.size)
        assertTrue(quoted.single() is PlayerChatEvent) { quoted.toString() }
    }

    @Test
    fun `a dungeon completion reads the floor and the mode`() {
        val event = one<DungeonCompletedEvent>("                                 Master Mode The Catacombs - Floor V")
        assertEquals("Floor V", event.floor)
        assertTrue(event.isMasterMode)
    }

    // ---------------------------------------------------------------
    // Auctions
    // ---------------------------------------------------------------

    @Test
    fun `being outbid reads the bidder, the item and the coins`() {
        val event = one<AuctionOutbidEvent>(
            "§6[Auction] §aMrBaiacu §eoutbid you by §659,083 coins §efor §fFiredust Dagger §e§lCLICK",
        )
        assertEquals("MrBaiacu", event.bidder)
        assertEquals("Firedust Dagger", event.itemName)
        assertEquals(59_083L, event.coins)
    }

    @Test
    fun `a sale reads the buyer and the price`() {
        val event = one<AuctionSoldEvent>("[Auction] EuropaPlus bought Atmospheric Filter for 2,650,000 coins §lCLICK")
        assertEquals("EuropaPlus", event.buyer)
        assertEquals("Atmospheric Filter", event.itemName)
        assertEquals(2_650_000L, event.coins)
    }

    // ---------------------------------------------------------------
    // Name extraction
    // ---------------------------------------------------------------

    @Test
    fun `names are found behind every tag Hypixel puts in front of them`() {
        assertEquals("lrg89", HypixelNames.playerName("§b[MVP§d+§b] lrg89"))
        assertEquals("lrg89", HypixelNames.playerName("♫ §c[Buddy ツ] §b[MVP§d+§b] lrg89"))
        assertEquals("lrg89", HypixelNames.playerName("§8[§b209§8] §b[MVP§d+§b] lrg89"))
        assertEquals("Vinc1x", HypixelNames.playerName("§6§lᛝ §r§7Vinc1x§7§7"))
        assertEquals("nea89o", HypixelNames.playerName("§7nea89o"))
        assertEquals("Throwpo", HypixelNames.playerName("§b[MVP§d+§b] Throwpo's"))
    }

    @Test
    fun `nothing name-shaped means no name, not a guess`() {
        assertNull(HypixelNames.playerName("§8[§b209§8]"))
        assertNull(HypixelNames.playerName(""))
    }

    private companion object {
        const val LOCAL_PLAYER = "Th7bo"
    }
}
