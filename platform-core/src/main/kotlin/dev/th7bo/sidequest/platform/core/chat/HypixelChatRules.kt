package dev.th7bo.sidequest.platform.core.chat

import dev.th7bo.sidequest.platform.chat.AuctionOutbidEvent
import dev.th7bo.sidequest.platform.chat.AuctionSoldEvent
import dev.th7bo.sidequest.platform.chat.ChatChannel
import dev.th7bo.sidequest.platform.chat.ChatMatch
import dev.th7bo.sidequest.platform.chat.ChatRule
import dev.th7bo.sidequest.platform.chat.DropRarity
import dev.th7bo.sidequest.platform.chat.DungeonCompletedEvent
import dev.th7bo.sidequest.platform.chat.HypixelNames
import dev.th7bo.sidequest.platform.chat.KuudraCompletedEvent
import dev.th7bo.sidequest.platform.chat.MatchTarget
import dev.th7bo.sidequest.platform.chat.PartyDisbandedEvent
import dev.th7bo.sidequest.platform.chat.PartyInviteEvent
import dev.th7bo.sidequest.platform.chat.PartyInviteExpiredEvent
import dev.th7bo.sidequest.platform.chat.PartyJoinedEvent
import dev.th7bo.sidequest.platform.chat.PartyKickedEvent
import dev.th7bo.sidequest.platform.chat.PartyLeaderChangedEvent
import dev.th7bo.sidequest.platform.chat.PartyLeaveReason
import dev.th7bo.sidequest.platform.chat.PartyMemberJoinedEvent
import dev.th7bo.sidequest.platform.chat.PartyMemberLeftEvent
import dev.th7bo.sidequest.platform.chat.PlayerChatEvent
import dev.th7bo.sidequest.platform.chat.RareDropEvent
import dev.th7bo.sidequest.platform.chat.TrophyCatchEvent
import dev.th7bo.sidequest.platform.chat.TrophyTier
import dev.th7bo.sidequest.platform.chat.SkillLevelUpEvent
import dev.th7bo.sidequest.platform.chat.chatRule
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.parser.HypixelText

/**
 * The chat rules the platform ships with.
 *
 * **Where these come from.** Every pattern and every fixture was taken from SkyHanni's
 * `data.chat.player`, `data.party`, `misc.raredropanimation`, `data.kuudra` and dungeon
 * pattern groups, and the fixtures are their `REGEX-TEST` lines *verbatim*. Years of
 * corrections live in those formats — Hypixel's chat is not documented anywhere and
 * rediscovering the exact shape of the guild rank suffix by observation would take the same
 * years. The implementation, the event model and the matching strategy are ours.
 *
 * **The fixtures are the point.** A pattern here without a fixture is a pattern nobody has
 * seen work, and `ChatRulesTest` names the two that are in that position so the gap is
 * visible rather than forgotten.
 *
 * **Nothing here assumes an icon or a colour it does not have to.** Hypixel's texture pack
 * has been moving symbols into private-use glyphs, so where a pattern could key off a symbol
 * it keys off the shape instead. Same reasoning as the scoreboard parsers.
 */
public object HypixelChatRules {

    /**
     * Every built-in rule.
     *
     * @param localPlayerName the client's own username, needed only to tell public chat from
     *   an unrelated system message that happens to contain a colon — see [publicChat].
     */
    public fun all(localPlayerName: () -> String? = { null }): List<ChatRule<*>> =
        channels(localPlayerName) + party() + drops() + progression() + completions() + auctions()

    // -- channels -----------------------------------------------------------

    private fun channels(localPlayerName: () -> String?): List<ChatRule<*>> = listOf(
        partyChat,
        coopChat,
        guildChat,
        privateMessage,
        publicChat(localPlayerName),
    )

    private val partyChat = chatRule(
        id = SqId.sidequest("chat.channel.party"),
        regex = """§9Party §8> (?<author>[^:]*)§f: (?<message>.*)""",
        fixtures = listOf(
            "§9Party §8> §b[MVP§d+§b] lrg89§f: peee",
            "§9Party §8> §7nea89o§f: peee",
        ),
    ) { match -> match.playerMessage(ChatChannel.PARTY) }

    private val coopChat = chatRule(
        id = SqId.sidequest("chat.channel.coop"),
        regex = """§bCo-op > (?<author>[^:]+)§f: (?<message>.*)""",
        fixtures = listOf("§bCo-op > §7nea89o§f: hallooooo"),
    ) { match -> match.playerMessage(ChatChannel.COOP) }

    /**
     * Guild chat, with the guild rank tag that sits after the name.
     *
     * The rank is matched and discarded rather than left to the author group, because
     * `§e[Em]` would otherwise end up inside the name and a rank change would look like a
     * different player.
     */
    private val guildChat = chatRule(
        id = SqId.sidequest("chat.channel.guild"),
        regex = """§2Guild > (?<author>.+?) ?(?<guildRank>§.\[\w*])?§f: (?<message>.*)""",
        fixtures = listOf(
            "§2Guild > §b[MVP§d+§b] infave §e[Em]§f: CEMENT DRINKERS INCORPORATED",
            "§2Guild > §6⚔ §6[MVP§3++§6] RealBacklight§f: !warp",
            "§2Guild > §b[MVP§d+§b] lrg89 §e[Iron]§f: h",
            "§2Guild > §b[MVP§c+§b] B2Square1 §3[IRON]§f: §r700 to go",
            "§2Guild > §6[MVP§5++§6] Throwpo §3[IRON]§f: §rbat pet clueless",
        ),
    ) { match -> match.playerMessage(ChatChannel.GUILD) }

    /**
     * A private message, in either direction.
     *
     * Matched on the *plain* text: Hypixel's own fixtures for this one carry no colour codes
     * at all, and matching the words rather than the formatting is the safer of the two here
     * because `To`/`From` at the start of the line is already a strong anchor.
     *
     * `From stash:` is the counter-fixture that matters. Picking up items from the stash
     * produces a line with exactly this shape, and reporting it as a private message from a
     * player called "stash" is the kind of bug that gets noticed months later.
     */
    private val privateMessage = chatRule(
        id = SqId.sidequest("chat.channel.private"),
        regex = """(?!From stash: )(?<direction>From|To) (?<rank>\[[ዞ\w+]+] )?(?<author>[^:]*): (?<message>.*)""",
        target = MatchTarget.PLAIN,
        fixtures = listOf(
            "To nea89o: lol",
            "From nea89o: hiii",
            "To [MVP+] Eisengolem: Boop!",
            "From [MVP+] Eisengolem: Boop!",
        ),
        counterFixtures = listOf("From stash: Pufferfish", "From stash: Wheat"),
    ) { match ->
        val direction = if (match.require("direction") == "From") {
            ChatChannel.PRIVATE_INCOMING
        } else {
            ChatChannel.PRIVATE_OUTGOING
        }
        match.playerMessage(direction)
    }

    /**
     * Public chat, and NPC lines, which Hypixel formats the same way.
     *
     * This is the loosest shape on Hypixel — `name: message` describes an enormous number of
     * system messages too — so it carries two guards that the fixtures justify.
     *
     * **A SkyBlock chat line always shows the speaker's level or their rank.** Every observed
     * public message has either a `[58] ` level prefix or a bracketed rank, and no system
     * message of the form `§7Rewards§7: …` has either. Requiring one of them is what stops
     * this rule reporting Hypixel's own output as somebody talking.
     *
     * **A trailing format code, or it is our own message.** Hypixel emits the last colour
     * code before the colon on everybody else's messages but not on the player's own, which
     * is SkyHanni's observation and the only way to tell the two apart. Without a local
     * player name to compare against, an unformatted line is left unclassified rather than
     * guessed at.
     */
    private fun publicChat(localPlayerName: () -> String?) = chatRule(
        id = SqId.sidequest("chat.channel.public"),
        regex = """(?:\[(?<level>\d+)] )?(?<author>(?:[^ ] )?(?:(?:§.)?\[[^\]]+] )?[^ ]+?)(?<chatColor>§f|§7|): (?<message>.*)""",
        fixtures = listOf(
            "[58] §7nea89o§7: haiiiii",
            "[266] ♫ §b[MVP§d+§b] lrg89§f: a",
            "[302] ♫ [MVP+] lrg89: problematic",
        ),
    ) { match ->
        val author = match.require("author")
        val hasIdentity = match.group("level") != null || '[' in author
        val isOwnMessage = localPlayerName()?.let { HypixelText.stripFormatting(author).endsWith(it) } == true
        when {
            !hasIdentity -> null
            match.require("chatColor").isEmpty() && !isOwnMessage -> null
            "[NPC]" in author -> match.playerMessage(ChatChannel.NPC)
            else -> match.playerMessage(ChatChannel.ALL)
        }
    }

    /** Builds the event, or nothing when the author is not name-shaped. */
    private fun ChatMatch.playerMessage(channel: ChatChannel): PlayerChatEvent? {
        val display = require("author")
        val sender = HypixelNames.playerName(display) ?: return null
        return PlayerChatEvent(
            channel = channel,
            senderDisplayName = HypixelText.clean(display),
            sender = sender,
            content = HypixelText.clean(require("message")),
            message = message,
        )
    }

    // -- party --------------------------------------------------------------

    private fun party(): List<ChatRule<*>> = listOf(
        partyInvite,
        partyInviteExpired,
        partyJoined,
        partyMemberJoined,
        partyFinderJoined,
        partyMemberLeft,
        partyMemberKicked,
        partyMemberOffline,
        partyMemberDisconnected,
        partyDisbanded,
        partyKicked,
        partyTransferredOnLeave,
        partyTransferredVoluntarily,
    )

    /**
     * Somebody invited us.
     *
     * The accept command is read off the line's click action rather than assembled from the
     * name. Hypixel has reworded this prompt more than once and the command behind
     * `§a§lACCEPT` has not changed, so the click is the durable half.
     */
    private val partyInvite = chatRule(
        id = SqId.sidequest("chat.party.invite"),
        regex = """§.(?:\[.*].)?(?<player>\S+) §ehas invited you to join their party!""",
        fixtures = listOf(
            "§r§b[MVP§r§c+§r§b] STPREAPER §r§ehas invited you to join their party!",
            "§r§a[VIP] VrxyOwnsYou_ §r§ehas invited you to join their party!",
            "§r§7SkyLime1213 §r§ehas invited you to join their party!",
        ),
    ) { match ->
        val inviter = match.playerName("player") ?: return@chatRule null
        PartyInviteEvent(
            inviter = inviter,
            acceptCommand = match.message.commandStartingWith("/party accept")
                ?: match.message.commandStartingWith("/p accept"),
            message = match.message,
        )
    }

    private val partyInviteExpired = chatRule(
        id = SqId.sidequest("chat.party.invite_expired"),
        regex = """§eThe party invite from §.(?:\[.*].)?(?<player>\S+) §ehas expired\.""",
        fixtures = listOf(
            "§eThe party invite from §r§b[MVP§r§f+§r§b] OE07 §r§ehas expired.",
            "§eThe party invite from §r§a[VIP] VrxyOwnsYou_ §r§ehas expired.",
            "§eThe party invite from §r§7TMOffline96 §r§ehas expired.",
        ),
    ) { match ->
        match.playerName("player")?.let { PartyInviteExpiredEvent(it, match.message) }
    }

    private val partyJoined = chatRule(
        id = SqId.sidequest("chat.party.joined"),
        regex = """§eYou have joined (?<name>.*)'s? §eparty!""",
        fixtures = listOf("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!"),
    ) { match ->
        match.playerName("name")?.let { PartyJoinedEvent(it, match.message) }
    }

    private val partyMemberJoined = chatRule(
        id = SqId.sidequest("chat.party.member_joined"),
        regex = """(?<name>.*) §ejoined the party\.""",
        fixtures = listOf("§b[MVP§d+§b] Throwpo §ejoined the party."),
    ) { match ->
        match.playerName("name")?.let { PartyMemberJoinedEvent(it, match.message) }
    }

    /**
     * Somebody joined through Party Finder.
     *
     * One pattern for the dungeon and Kuudra variants: they differ only in the word
     * `dungeon` and in what the bracket holds, and the bracket's contents are not worth
     * matching precisely when a class name Hypixel adds would break it.
     */
    private val partyFinderJoined = chatRule(
        id = SqId.sidequest("chat.party.finder_joined"),
        regex = """§dParty Finder §f> (?<name>.*?) §ejoined the (?:dungeon )?group! \(.*\)""",
        fixtures = listOf("§dParty Finder §f> §bGhostsTM §ejoined the dungeon group! (§bArcher Level 9§e)"),
    ) { match ->
        match.playerName("name")?.let { PartyMemberJoinedEvent(it, match.message) }
    }

    private val partyMemberLeft = chatRule(
        id = SqId.sidequest("chat.party.member_left"),
        regex = """(?<name>.*) §ehas left the party\.""",
        fixtures = listOf("§7246sweets §ehas left the party."),
    ) { match -> match.memberLeft(PartyLeaveReason.LEFT) }

    private val partyMemberKicked = chatRule(
        id = SqId.sidequest("chat.party.member_kicked"),
        regex = """(?<name>.*) §ehas been removed from the party\.""",
        fixtures = listOf("§7riblets §ehas been removed from the party."),
    ) { match -> match.memberLeft(PartyLeaveReason.KICKED) }

    private val partyMemberOffline = chatRule(
        id = SqId.sidequest("chat.party.member_offline"),
        regex = """§eKicked (?<name>.*) because they were offline\.""",
        fixtures = listOf("§eKicked §b[MVP§d+§b] Throwpo§e because they were offline."),
    ) { match -> match.memberLeft(PartyLeaveReason.OFFLINE) }

    private val partyMemberDisconnected = chatRule(
        id = SqId.sidequest("chat.party.member_disconnected"),
        regex = """(?<name>.*) §ewas removed from your party because they disconnected\.""",
        fixtures = listOf("§b[MVP§d+§b] Throwpo §ewas removed from your party because they disconnected."),
    ) { match -> match.memberLeft(PartyLeaveReason.DISCONNECTED) }

    private fun ChatMatch.memberLeft(reason: PartyLeaveReason): PartyMemberLeftEvent? =
        playerName("name")?.let { PartyMemberLeftEvent(it, reason, message) }

    private val partyDisbanded = chatRule(
        id = SqId.sidequest("chat.party.disbanded"),
        regex = """(?<name>.*) §ehas disbanded the party!""",
        fixtures = listOf("§b[MVP§d+§b] Throwpo §ehas disbanded the party!"),
    ) { match -> PartyDisbandedEvent(match.playerName("name"), match.message) }

    private val partyKicked = chatRule(
        id = SqId.sidequest("chat.party.kicked"),
        regex = """§eYou have been kicked from the party by .* §e""",
        fixtures = listOf("§eYou have been kicked from the party by §b[MVP§d+§b] Throwpo §e"),
    ) { match -> PartyKickedEvent(match.message) }

    /**
     * Leadership moved because the leader left, and because they handed it over.
     *
     * Both matched on the plain text, which is how the lines were recorded. They are
     * counter-fixtures for each other: `because … left` and `by …` are the only difference,
     * and a pattern loose enough to catch both would report the wrong previous leader.
     */
    private val partyTransferredOnLeave = chatRule(
        id = SqId.sidequest("chat.party.transferred_on_leave"),
        regex = """The party was transferred to (?<newowner>.*) because (?<name>.*) left""",
        target = MatchTarget.PLAIN,
        fixtures = listOf("The party was transferred to [MVP+] CalMWolfs because [MVP+] Throwpo left"),
        counterFixtures = listOf("The party was transferred to [MVP+] Throwpo by [MVP+] CalMWolfs"),
    ) { match -> match.leaderChanged() }

    private val partyTransferredVoluntarily = chatRule(
        id = SqId.sidequest("chat.party.transferred"),
        regex = """The party was transferred to (?<newowner>.*) by (?<name>.*)""",
        target = MatchTarget.PLAIN,
        fixtures = listOf("The party was transferred to [MVP+] Throwpo by [MVP+] CalMWolfs"),
        counterFixtures = listOf("The party was transferred to [MVP+] CalMWolfs because [MVP+] Throwpo left"),
    ) { match -> match.leaderChanged() }

    private fun ChatMatch.leaderChanged(): PartyLeaderChangedEvent? =
        playerName("newowner")?.let { PartyLeaderChangedEvent(it, playerName("name"), message) }

    // -- drops --------------------------------------------------------------

    private fun drops(): List<ChatRule<*>> = listOf(bareDrop, parenthesizedDrop, petDrop, trophyFish)

    /**
     * Magic Find, read off whichever drop line carries it.
     *
     * Matched separately from the drop patterns and on the cleaned text, because the star is
     * a symbol Hypixel's texture pack has been moving and the amount is worth having even
     * when the icon around it changes. Anything between the percentage and the words is
     * allowed through for exactly that reason.
     */
    private val MAGIC_FIND = Regex("""\+(?<percent>[\d.]+)%[^)]*Magic Find""")

    private fun ChatMatch.magicFind(): Double? =
        MAGIC_FIND.find(message.clean)?.groups?.get("percent")?.value?.toDoubleOrNull()

    /**
     * `RARE DROP! <item>`, the form with the item named directly.
     *
     * Rare crops share the shape and are reported at [DropRarity.RARE]: `RARE CROP!` is the
     * garden's wording for the same tier, not a tier of its own.
     */
    private val bareDrop = chatRule(
        id = SqId.sidequest("chat.drop.bare"),
        regex = """(?:§.)*(?<kind>RARE) (?:DROP|CROP)! (?:§.)*(?<item>[^(§\n]+?)(?:(?:§.)*\s*x(?<amount>\d+))?\s*(?:(?:§.)*\(.*)?""",
        fixtures = listOf(
            "§r§6§lRARE DROP! §r§6§lEnchanted Book §r§b(+208% ✯ Magic Find)",
            "§r§6§lRARE DROP! §r§fWither Cloak Sword",
            "§r§6§lRARE DROP! §r§5Tarantula Talisman §r§b(+100% ✯ Magic Find)",
            "§r§6§lRARE DROP! §r§6§lEnchanted Hay Bale x3 §r§b(+94.5☀)",
            "§r§6§lRARE CROP! §r§6§lCropie §r§b(+39.5☀)",
            "§6§lRARE DROP! §6§lEnchanted Red Mushroom Block §8x3 §r§b(+94.5☀)",
        ),
        counterFixtures = listOf(
            "§9§lVERY RARE DROP!  §r§7(§r§f§r§5Revenant Catalyst§r§7) §r§b(+158% ✯ Magic Find)",
            "§6§lPET DROP! §r§5Baby Yeti §r§b(+168% ✯ Magic Find)",
        ),
    ) { match -> match.drop(DropRarity.RARE) }

    /**
     * The parenthesised form, `VERY RARE DROP! (<item>)`.
     *
     * Every tier above plain rare uses it, plus `PRAY TO RNGESUS DROP!`, which is Hypixel's
     * own wording for the rarest tier.
     */
    private val parenthesizedDrop = chatRule(
        id = SqId.sidequest("chat.drop.parenthesized"),
        regex = """(?:§.)*(?<kind>(?:VERY |CRAZY |INSANE )?RARE|PRAY TO RNGESUS) DROP! +(?:§.)*\((?:§.)*(?:(?<amount>\d+)x )?(?:§.)*(?<item>[^§()\n]+?)\s*(?:§.)*\).*""",
        fixtures = listOf(
            "§b§lRARE DROP! §r§7(§r§f§r§72x §r§f§r§9Foul Flesh§r§7) §r§b(+158% §r§b✯ Magic Find§r§b)",
            "§b§lRARE DROP! §r§7(§r§f§r§9Revenant Viscera§r§7) §r§b(+158% §r§b✯ Magic Find§r§b)",
            "§9§lVERY RARE DROP!  §r§7(§r§f§r§5Revenant Catalyst§r§7) §r§b(+158% ✯ Magic Find)",
            "§d§lCRAZY RARE DROP!  §r§7(§r§f§r§fPocket Espresso Machine§r§7) §r§b(+158% ✯ Magic Find)",
            "VERY RARE DROP! (Revenant Shard) (+158% ✯ Magic Find)",
            "CRAZY RARE DROP! (Smite VI) (+158% ✯ Magic Find)",
            "INSANE RARE DROP! (Judgement Core) (+158% ✯ Magic Find)",
            "§5§lPRAY TO RNGESUS DROP!  §r§7(§r§f§r§5Warden Heart§r§7) §r§b(+158% ✯ Magic Find)",
        ),
        counterFixtures = listOf("§r§6§lRARE DROP! §r§fWither Cloak Sword"),
    ) { match -> match.drop(DropRarity.ofWording(match.require("kind")) ?: DropRarity.RARE) }

    private val petDrop = chatRule(
        id = SqId.sidequest("chat.drop.pet"),
        regex = """(?:§.)*PET DROP! (?:§.)*§(?<rarityColor>.)(?<item>[^§(\n]+?)\s*(?:(?:§.)*\(.*)?""",
        fixtures = listOf(
            "§6§lPET DROP! §r§5Baby Yeti §r§b(+168% ✯ Magic Find)",
            "§6§lPET DROP! §r§6Rat",
        ),
    ) { match -> match.drop(DropRarity.PET) }

    /**
     * `TROPHY FISH! You caught a <fish> <GOLD>`.
     *
     * **Deliberately not anchored on Hypixel's leading glyph.** SkyHanni's own source spells that symbol two
     * different ways in two files — `\uE02A` in one and `⛃` in another — because Hypixel replaced its
     * decorative icons when it shipped its own texture pack, and one of the two was never updated. The words
     * are stable and the icons are not, so this matches on the words and treats whatever precedes them as
     * formatting.
     *
     * That is the general rule for this file: an icon in a Hypixel message is decoration, and a pattern that
     * requires one is a pattern that breaks on their next resource-pack update.
     *
     * The prefix allows anything that is not a word character, which is lenient enough for any glyph they
     * pick and still strict enough to refuse a player quoting the line — a name and a rank tag are letters,
     * so `[MVP+] Someone: TROPHY FISH! ...` does not match.
     */
    private val trophyFish = chatRule(
        id = SqId.sidequest("chat.drop.trophy_fish"),
        regex = """(?:§.|[^\w\n])*TROPHY (?:FISH|FROG)! (?:§.)*You caught an? (?:§.)*(?<item>[^§\n]+?) (?:§.)*(?<tier>BRONZE|SILVER|GOLD|DIAMOND)(?:§.)*!.*""",
        fixtures = listOf(
            // SkyHanni's own fixtures, kept verbatim so a divergence is visible in a diff.
            "§6\uE02A §r§6§lTROPHY FISH! §r§fYou caught a §r§9Lavahorse §r§6§lGOLD§r§f!",
            "§6\uE02A §r§6§lTROPHY FISH! §r§fYou caught a §r§5Soul Fish §r§8§lBRONZE§r§f!",
            "§6\uE02A §r§6§lTROPHY FISH! §r§fYou caught a §r§fBlobfish §r§7§lSILVER§r§f!",
            // The same line with the older glyph, which is the case the leniency exists for.
            "§6⛃ §r§6§lTROPHY FISH! §r§fYou caught a §r§9Mana Ray §r§8§lBRONZE§r§f!",
            "§2♔ §r§2§lTROPHY FROG! §r§fYou caught an §r§aExploding Frog §r§7§lSILVER§r§f!",
            "§2♔ §r§2§lTROPHY FROG! §r§fYou caught a §r§fCommon Frog §r§b§lDIAMOND§r§f!",
        ),
        counterFixtures = listOf(
            // Somebody else's first catch is a global broadcast, not your catch. Announcing it as yours
            // would be the same class of bug as levelling up because a friend quoted the message.
            "§2§lRIBBIT! §r§7§r§b[MVP§r§a+§r§b] Th7bo§r§f§r§e caught their first §r§b§lDIAMOND §r§fCommon Frog§r§e!",
            "§6§lPET DROP! §r§5Baby Yeti §r§b(+168% ✯ Magic Find)",
        ),
    ) { match ->
        val item = HypixelText.clean(match.require("item"))
        if (item.isEmpty()) {
            null
        } else {
            TrophyCatchEvent(
                fishName = item,
                tier = TrophyTier.ofWording(match.require("tier")) ?: TrophyTier.BRONZE,
                message = match.message,
            )
        }
    }

    private fun ChatMatch.drop(rarity: DropRarity): RareDropEvent? {
        val item = HypixelText.clean(require("item"))
        if (item.isEmpty()) return null
        return RareDropEvent(
            itemName = item,
            rarity = rarity,
            amount = int("amount") ?: 1,
            magicFindPercent = magicFind(),
            message = message,
        )
    }

    // -- progression --------------------------------------------------------

    private fun progression(): List<ChatRule<*>> = listOf(skillLevelUp)

    /**
     * `  §b§lSKILL LEVEL UP §3Farming §827➜§328`.
     *
     * The two leading spaces are Hypixel's indentation inside its banner, and the pattern
     * **requires** them: unindented, the same words are a player quoting them, and a rule
     * that levelled somebody up because a friend typed it in party chat would be worse than
     * one that occasionally misses.
     */
    private val skillLevelUp = chatRule(
        id = SqId.sidequest("chat.skill.level_up"),
        regex = """\s+(?:§.)*SKILL LEVEL UP (?:§.)*(?<skill>\w+) (?:§.)*(?<old>\d+)➜(?:§.)*(?<new>\d+)""",
        counterFixtures = listOf("SKILL LEVEL UP Farming 27➜28"),
        fixtures = listOf("  §r§b§lSKILL LEVEL UP §3Farming §827➜§328"),
    ) { match ->
        val old = match.int("old") ?: return@chatRule null
        val new = match.int("new") ?: return@chatRule null
        SkillLevelUpEvent(match.require("skill"), old, new, match.message)
    }

    // -- completions --------------------------------------------------------

    private fun completions(): List<ChatRule<*>> = listOf(dungeonComplete, kuudraComplete)

    /**
     * The dungeon completion banner, matched colourlessly.
     *
     * Anchored to the run of centring spaces Hypixel pads the banner with, which is what
     * separates it from a player typing the floor name. `Entrance` is kept as Hypixel words
     * it rather than mapped to a floor number, because it is not one.
     */
    private val dungeonComplete = chatRule(
        id = SqId.sidequest("chat.dungeon.complete"),
        regex = """\s+(?<master>Master Mode )?The Catacombs - (?:Floor (?<floor>[IV]{1,3})|(?<entrance>Entrance))\s*""",
        target = MatchTarget.PLAIN,
        fixtures = listOf(
            "                                 Master Mode The Catacombs - Floor V",
        ),
    ) { match ->
        DungeonCompletedEvent(
            floor = match.group("floor")?.let { "Floor $it" } ?: "Entrance",
            isMasterMode = match.group("master") != null,
            message = match.message,
        )
    }

    /**
     * `KUUDRA DOWN!`
     *
     * **Unverified.** Taken from SkyHanni's `data.kuudra` group, which ships it without a
     * `REGEX-TEST` of its own, so there is no recorded line to hold it to. Left as they wrote
     * it rather than tightened, and named in `ChatRulesTest` so the gap stays visible.
     */
    private val kuudraComplete = chatRule(
        id = SqId.sidequest("chat.kuudra.complete"),
        regex = """§.\s*(?:§.)*KUUDRA DOWN!""",
    ) { match -> KuudraCompletedEvent(match.message) }

    // -- auctions -----------------------------------------------------------

    private fun auctions(): List<ChatRule<*>> = listOf(auctionOutbid, auctionSold)

    private val auctionOutbid = chatRule(
        id = SqId.sidequest("chat.auction.outbid"),
        regex = """§6\[Auction] (?<bidder>.*?)§eoutbid you by §.(?<amount>[\d,]+) coins §efor (?<item>.*?)§e§lCLICK""",
        fixtures = listOf(
            "§6[Auction] §aMrBaiacu §eoutbid you by §659,083 coins §efor §fFiredust Dagger §e§lCLICK",
        ),
    ) { match ->
        AuctionOutbidEvent(
            bidder = match.playerName("bidder") ?: return@chatRule null,
            itemName = HypixelText.clean(match.require("item")),
            coins = match.coins("amount") ?: return@chatRule null,
            message = match.message,
        )
    }

    /**
     * One of our auctions sold.
     *
     * Matched colourlessly. The line was recorded without codes, and the words are specific
     * enough on their own that giving up the colour costs nothing here.
     */
    private val auctionSold = chatRule(
        id = SqId.sidequest("chat.auction.sold"),
        regex = """\[Auction] (?<buyer>\w+) bought (?<item>.+?) for (?<amount>[\d,]+) coins.*""",
        target = MatchTarget.PLAIN,
        fixtures = listOf("[Auction] EuropaPlus bought Atmospheric Filter for 2,650,000 coins §lCLICK"),
    ) { match ->
        AuctionSoldEvent(
            buyer = match.playerName("buyer") ?: return@chatRule null,
            itemName = HypixelText.clean(match.require("item")),
            coins = match.coins("amount") ?: return@chatRule null,
            message = match.message,
        )
    }

    /** A coin amount, which Hypixel prints with thousands separators. */
    private fun ChatMatch.coins(name: String): Long? = group(name)?.replace(",", "")?.toLongOrNull()
}
