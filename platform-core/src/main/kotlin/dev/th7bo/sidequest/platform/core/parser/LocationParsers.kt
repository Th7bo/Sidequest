package dev.th7bo.sidequest.platform.core.parser

import dev.th7bo.sidequest.platform.chat.HypixelNames
import dev.th7bo.sidequest.platform.parser.HypixelText
import dev.th7bo.sidequest.platform.parser.ScoreboardSnapshot
import dev.th7bo.sidequest.platform.parser.TabListLayout
import dev.th7bo.sidequest.platform.parser.TabListSnapshot
import dev.th7bo.sidequest.platform.parser.TabWidget
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.ProfileType
import dev.th7bo.sidequest.platform.skyblock.ServerId
import dev.th7bo.sidequest.platform.skyblock.SkyBlockProfile
import dev.th7bo.sidequest.platform.skyblock.SubLocation

/**
 * What the scoreboard could be read to say.
 *
 * Every field is nullable and null means "the scoreboard did not say", which is not the
 * same as "there is none". The context service needs that distinction: a scoreboard that
 * has not loaded yet must not overwrite a profile it already knows with an empty one.
 */
public data class ScoreboardReading(
    public val isSkyBlock: Boolean = false,
    public val subLocation: SubLocation? = null,
    public val serverId: ServerId? = null,
    /** True on someone else's island, from the `GUEST` scoreboard title. */
    public val isGuest: Boolean = false,
    /**
     * Dungeon floor as Hypixel words it — `F7`, `M3`, `E` — or null when not in one.
     *
     * Read off the area line's parenthesised suffix rather than from a dungeon-specific
     * pattern, so it costs nothing and cannot disagree with [subLocation].
     */
    public val dungeonFloor: String? = null,
    /** Kuudra tier, from the same suffix: `Kuudra's Hollow (T5)`. */
    public val kuudraTier: Int? = null,
    /** Game mode, when the scoreboard names one. */
    public val profileType: ProfileType? = null,
    /** Coins in the purse, or in the piggy bank — the scoreboard shows one or the other. */
    public val purse: Long? = null,
    public val bits: Long? = null,
    /** The SkyBlock date, e.g. `Early Spring 13th`. */
    public val date: String? = null,
    /** The SkyBlock time of day, e.g. `8:50am`. */
    public val timeOfDay: String? = null,
    /**
     * Every `Key: value` line on the board, cleaned.
     *
     * The escape hatch. A feature wanting something the fields above do not cover reads it
     * from here rather than growing its own scoreboard pattern — which is the thing this
     * whole layer exists to prevent.
     */
    public val values: Map<String, String> = emptyMap(),
) {

    /** A value by key, ignoring case. */
    public fun value(key: String): String? =
        values.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    /** A value by key as a number, with Hypixel's thousands separators removed. */
    public fun number(key: String): Long? = value(key)?.let(::parseAmount)

    public val isInDungeon: Boolean get() = dungeonFloor != null

    public val isInKuudra: Boolean get() = kuudraTier != null
}

/** What the tab list could be read to say. */
public data class TabListReading(
    public val island: Island? = null,
    public val serverId: ServerId? = null,
    public val profile: SkyBlockProfile? = null,
    /** True when the area widget said `Dungeon:` rather than `Area:`. */
    public val isInDungeon: Boolean = false,
    /**
     * Every widget on the board and the lines under it, headers included.
     *
     * The set of keys is itself the most useful thing here: a `Commissions:` widget means
     * the player is doing commissions, whatever the area line says. See [TabWidget].
     */
    public val widgets: Map<TabWidget, List<String>> = emptyMap(),
    /** Players Hypixel says are on this server, from `Players (N)` and `Guests (N)`. */
    public val playerCount: Int? = null,
    /** Player names in the list, tags stripped. Display names, not identities. */
    public val players: List<String> = emptyList(),
    /**
     * Party members named by the party widget, tags stripped.
     *
     * Extracted by shape — the name-shaped lines under the widget — rather than by a
     * pattern for how Hypixel decorates them. There is no recorded format for these lines,
     * and shape survives a decoration Hypixel adds where a format would not.
     */
    public val partyMembers: List<String> = emptyList(),
    public val sbLevel: Int? = null,
) {

    /** Whether a widget is on the board. The cheap form of an activity clue. */
    public fun has(widget: TabWidget): Boolean = widget in widgets

    /** A widget's value lines, header excluded. Empty when the widget is absent. */
    public fun linesOf(widget: TabWidget): List<String> = widgets[widget]?.drop(1) ?: emptyList()
}

/**
 * Reads the SkyBlock scoreboard.
 *
 * The patterns describe what Hypixel actually emits, which was established from
 * SkyHanni's — years of corrections live in those formats and rediscovering them by
 * observation would take the same years. The implementation is ours.
 *
 * Every rule is conservative: a line that does not match contributes nothing rather than
 * a guess. A wrong location is worse than no location, because features act on the former
 * and stand down on the latter.
 */
public object ScoreboardParser {

    /**
     * The area line, e.g. ` §7⏣ §bVillage` or ` §5ф §dWizard Tower`.
     *
     * Matched on the *formatted* line: the shape — colour, symbol, space, colour, text —
     * is what identifies it, and a cleaned line has lost the codes that give it that
     * shape.
     *
     * **The symbol is matched as any single character, on purpose.** `⏣` for the
     * overworld and `ф` for the Rift are what it has been, but Hypixel's texture pack has
     * been moving symbols to private-use glyphs (`` for this one), and a hardcoded
     * list stops matching the day they finish. Nothing else on the scoreboard has this
     * shape, so the loose symbol costs no precision. Copied in spirit from SkyHanni, which
     * matches it the same way for the same reason.
     */
    private val AREA = Regex("""\s*§[0-9a-fk-orA-FK-OR](?<symbol>.) §[0-9a-fk-orA-FK-OR](?<area>.*)""")

    /** The server id, tucked on the end of the date line: `07/16/24 §7mini123A`. */
    private val SERVER_ID = Regex("""\d{2}/\d{2}/\d{2}\s+§[0-9a-fk-or](?<server>[\w]+)""")

    /**
     * Titles that mean SkyBlock.
     *
     * Matched loosely on purpose. Hypixel rewrites the title for every event — `SKYBLOCK
     * CO-OP`, and seasonal variants — and a strict list would silently decide the player
     * had left SkyBlock every December.
     */
    private val SKYBLOCK_TITLE = Regex("""SKYBLOCK|SB\b""", RegexOption.IGNORE_CASE)

    /**
     * The parenthesised suffix on an area name: `The Catacombs (F7)`, `Kuudra's Hollow (T5)`.
     *
     * One pattern for both because it is one shape. Reading the floor out of the area we
     * already parsed is cheaper than a second pass over the board, and it cannot disagree
     * with the area — which a separate dungeon pattern could, and would, one day.
     */
    private val AREA_SUFFIX = Regex("""(?<area>.*?)\s*\((?<tag>[^)]+)\)\s*""")

    /** `Kuudra's Hollow (T5)` — the tier inside the suffix. */
    private val KUUDRA_TIER = Regex("""T(?<tier>\d+)""")

    /** `Key: value`, on the cleaned line. The generic shape most of the board uses. */
    private val KEY_VALUE = Regex("""(?<key>[A-Za-z][A-Za-z' ]*?):\s*(?<value>.*)""")

    /** `Early Spring 13th`, `Winter 1st`. */
    private val DATE = Regex("""(?:(?:Late|Early) )?(?:Spring|Summer|Autumn|Winter) \d+(?:st|nd|rd|th)?.*""")

    /**
     * `8:50am`, sometimes with a weather or day/night symbol after it.
     *
     * The symbol is not matched. It has been `☽ ☀ ⚡ ☔` and Hypixel's texture pack is moving
     * those into private-use glyphs, so anything after the time is allowed through.
     */
    private val TIME_OF_DAY = Regex("""(?<time>\d{1,2}:\d{2}(?:am|pm))\b.*""")

    /**
     * The game-mode line: `♲ Ironman`, `☀ Stranded`, `Ⓑ Bingo`.
     *
     * Matched on the **word**, not the symbol. SkyHanni matches the symbols, and those are
     * exactly what Hypixel's texture pack has been replacing — the words have not changed.
     */
    private val PROFILE_TYPE = Regex("""(?:\W+\s*)?(?<mode>Ironman|Stranded|Bingo)\s*""")

    public fun parse(snapshot: ScoreboardSnapshot): ScoreboardReading {
        if (snapshot.isEmpty) return ScoreboardReading()

        val title = snapshot.title
        val isSkyBlock = SKYBLOCK_TITLE.containsMatchIn(title)
        if (!isSkyBlock) return ScoreboardReading(isSkyBlock = false)

        var subLocation: SubLocation? = null
        var serverId: ServerId? = null

        for (raw in snapshot.rawLines) {
            if (subLocation == null) {
                AREA.matchEntire(raw)?.let { match ->
                    val area = HypixelText.clean(match.groups["area"]!!.value)
                    if (area.isNotEmpty()) subLocation = SubLocation(area)
                }
            }
            if (serverId == null) {
                SERVER_ID.find(raw)?.let { match ->
                    serverId = ServerId(match.groups["server"]!!.value)
                }
            }
            if (subLocation != null && serverId != null) break
        }

        val values = LinkedHashMap<String, String>()
        var profileType: ProfileType? = null
        var date: String? = null
        var timeOfDay: String? = null

        for (line in snapshot.lines) {
            KEY_VALUE.matchEntire(line)?.let { match ->
                // First wins. Hypixel does not repeat a key, and if it ever does, the top
                // of the board is the more current one.
                values.putIfAbsent(match.groups["key"]!!.value.trim(), match.groups["value"]!!.value.trim())
                return@let
            }
            if (date == null && DATE.matches(line)) date = line
            if (timeOfDay == null) {
                TIME_OF_DAY.matchEntire(line)?.let { timeOfDay = it.groups["time"]!!.value }
            }
            if (profileType == null) {
                PROFILE_TYPE.matchEntire(line)?.let { match ->
                    profileType = ProfileType.ofWording(match.groups["mode"]!!.value)
                }
            }
        }

        val suffix = subLocation?.name?.let { AREA_SUFFIX.matchEntire(it) }
        val tag = suffix?.groups?.get("tag")?.value
        val kuudraTier = tag?.let { KUUDRA_TIER.matchEntire(it)?.groups?.get("tier")?.value?.toIntOrNull() }

        return ScoreboardReading(
            isSkyBlock = true,
            subLocation = subLocation,
            serverId = serverId,
            // The title says GUEST while visiting, which is the only client-side signal
            // that the island is someone else's.
            isGuest = title.contains("GUEST", ignoreCase = true),
            // A Catacombs suffix is a floor; a Kuudra suffix is a tier. Nothing else on the
            // board wears one, so the area name is what tells them apart.
            dungeonFloor = tag?.takeIf { kuudraTier == null && suffix.isCatacombs() },
            kuudraTier = kuudraTier,
            profileType = profileType,
            purse = values.amount("Purse") ?: values.amount("Piggy"),
            bits = values.amount("Bits"),
            date = date,
            timeOfDay = timeOfDay,
            values = values,
        )
    }

    /** Whether the area the suffix was taken from is the Catacombs. */
    private fun MatchResult.isCatacombs(): Boolean =
        groups["area"]!!.value.contains("Catacombs", ignoreCase = true)

    private fun Map<String, String>.amount(key: String): Long? = this[key]?.let(::parseAmount)
}

/**
 * Reads Hypixel's tab-list widgets.
 *
 * The tab list is the more authoritative of the two for *which island* — it names it
 * outright, where the scoreboard only gives the area within it.
 *
 * It is also a structured board rather than a list of lines, and the structure is worth
 * having: which widgets are present says what the player is doing more reliably than any
 * single value. See [TabWidget].
 */
public object TabListParser {

    /** `Area: Dwarven Mines`, or `Dungeon: Catacombs` while in one. */
    private val AREA = Regex("""(?<kind>Area|Dungeon): (?<island>.+)""")

    /** `Server: mini123A` */
    private val SERVER = Regex("""Server: (?<server>\S+)""")

    /**
     * `Profile: Mango`, sometimes followed by a game-mode symbol.
     *
     * The symbols mark Ironman, Bingo and Stranded profiles — `♲ Ⓑ ☀` at the time of
     * writing. They are not part of the name, and leaving one in would make the same
     * profile read as two different ones depending on the mode.
     *
     * The name is captured as word characters and spaces rather than the trailer being
     * matched as a fixed symbol set, so a symbol Hypixel changes or adds falls outside the
     * capture instead of breaking the match. A profile name is `[A-Za-z]` in practice, so
     * this gives up nothing.
     */
    private val PROFILE = Regex("""Profile:\s*(?<profile>[\w][\w\s]*?)\s*\W*$""")

    public fun parse(snapshot: TabListSnapshot): TabListReading {
        if (snapshot.isEmpty) return TabListReading()

        val entries = TabListLayout.normalise(snapshot.entries)
        val widgets = splitIntoWidgets(entries)

        var island: Island? = null
        var isInDungeon = false
        var serverId: ServerId? = null
        var profile: SkyBlockProfile? = null

        for (entry in entries) {
            if (island == null) {
                AREA.matchEntire(entry)?.let { match ->
                    isInDungeon = match.groups["kind"]!!.value == "Dungeon"
                    val name = match.groups["island"]!!.value.trim()
                    // An unrecognised name resolves to UNKNOWN rather than being dropped:
                    // "Hypixel added an island" and "the widget is missing" are different
                    // situations and the context service treats them differently.
                    island = if (isInDungeon) Island.CATACOMBS else Island.ofDisplayName(name)
                }
            }
            if (serverId == null) {
                SERVER.matchEntire(entry)?.let { serverId = ServerId(it.groups["server"]!!.value) }
            }
            if (profile == null) {
                PROFILE.matchEntire(entry)?.let { match ->
                    val name = match.groups["profile"]!!.value.trim()
                    if (name.isNotEmpty()) profile = SkyBlockProfile(name)
                }
            }
        }

        return TabListReading(
            island = island,
            serverId = serverId,
            profile = profile,
            isInDungeon = isInDungeon,
            widgets = widgets,
            playerCount = countPlayers(widgets),
            players = namesUnder(widgets, TabWidget.PLAYER_LIST) + namesUnder(widgets, TabWidget.GUESTS),
            partyMembers = namesUnder(widgets, TabWidget.PARTY),
            sbLevel = widgets.headerGroup(TabWidget.SB_LEVEL, "level")?.toIntOrNull(),
        )
    }

    /**
     * Groups the entries into widgets.
     *
     * A widget owns every line from its header up to the next header. Lines before the
     * first header belong to no widget and are dropped — the board always opens with one,
     * and anything before it is padding.
     */
    private fun splitIntoWidgets(entries: List<String>): Map<TabWidget, List<String>> {
        val widgets = LinkedHashMap<TabWidget, MutableList<String>>()
        var current: MutableList<String>? = null

        for (entry in entries) {
            val widget = TabWidget.ofHeader(entry)
            if (widget != null) {
                // A repeat of a widget already seen extends it rather than replacing it.
                // Hypixel splits a long widget across a column boundary and repeats the
                // header, and dropping the earlier half would lose half a party.
                current = widgets.getOrPut(widget) { ArrayList() }
                // Only the first header is kept, so the repeat does not show up in the
                // middle of the widget's own values.
                if (current.isEmpty()) current.add(entry)
                continue
            }
            if (entry.isEmpty()) continue
            current?.add(entry)
        }

        return widgets
    }

    /**
     * Players Hypixel says are here.
     *
     * The counts are summed rather than taken from one widget: on a private island the
     * visitors are counted separately, and the interesting number is how many people are
     * present.
     */
    private fun countPlayers(widgets: Map<TabWidget, List<String>>): Int? {
        val counts = listOfNotNull(
            widgets.headerGroup(TabWidget.PLAYER_LIST, "amount")?.toIntOrNull(),
            widgets.headerGroup(TabWidget.GUESTS, "amount")?.toIntOrNull(),
        )
        return if (counts.isEmpty()) null else counts.sum()
    }

    /** Name-shaped lines under a widget, tags stripped, in order and without duplicates. */
    private fun namesUnder(widgets: Map<TabWidget, List<String>>, widget: TabWidget): List<String> {
        val lines = widgets[widget]?.drop(1) ?: return emptyList()
        return lines.mapNotNull { HypixelNames.playerName(it) }.distinct()
    }

    /** A named group from a widget's own header line. */
    private fun Map<TabWidget, List<String>>.headerGroup(widget: TabWidget, group: String): String? {
        val header = this[widget]?.firstOrNull() ?: return null
        return widget.header.matchEntire(header)?.groups?.get(group)?.value
    }
}

/**
 * Reads one of Hypixel's numbers.
 *
 * They arrive with thousands separators and occasionally a `+n` gain in brackets after
 * them, which is not part of the amount.
 */
internal fun parseAmount(text: String): Long? {
    val head = text.substringBefore('(').trim().replace(",", "")
    // Anything left that is not a digit means this was not a plain amount — an abbreviated
    // `1.5k`, or a value that only looks numeric. Null beats a number off by a thousand.
    return if (head.isNotEmpty() && head.all { it.isDigit() }) head.toLongOrNull() else null
}
