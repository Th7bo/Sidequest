package dev.th7bo.sidequest.platform.parser

/**
 * Hypixel's tab list is a board of widgets, and these are the ones we read.
 *
 * The structure is not obvious from looking at it. The tab list is a flat list of 80
 * entries laid out as four columns of twenty, and Hypixel fills it with *widgets*: a header
 * line naming the widget, followed by its value lines, until the next header. Which widgets
 * are present is itself information — a `Commissions:` widget means the player is in the
 * Dwarven Mines whatever the area line says, and `Visitors: (3)` means the Garden.
 *
 * **The header patterns come from SkyHanni's `tab.widgetcomponent.enum` group.** They are
 * unfixtured there and unfixtured here: Hypixel documents none of this, and the patterns are
 * the accumulated result of people reading real tab lists for years. What *is* tested here is
 * the splitting algorithm, which is ours — see `TabListParserTest`.
 *
 * Only widgets something reads, or plans to read, are listed. The full set is around sixty
 * and most of them are values no feature has asked for; adding one is a line.
 */
public enum class TabWidget(pattern: String) {

    /** `Players (43)`. Repeated at the top of each column — see [TabListLayout]. */
    PLAYER_LIST("""Players \((?<amount>\d+)\)"""),

    /** Carries nothing. Present because it is a column header and has to be recognised. */
    INFO("""Info"""),

    /** `Area: Dwarven Mines`, or `Dungeon: Catacombs` while in one. */
    AREA("""(?<kind>Area|Dungeon): (?<island>.*)"""),

    SERVER("""Server: (?<serverid>.*)"""),

    /** `Profile: Mango`, sometimes with a game-mode symbol after it. */
    PROFILE("""Profile: (?<profile>[\w\s]+)(?:.*)?"""),

    SB_LEVEL("""SB Level: \[(?<level>\d+)] (?<xp>\d+).*"""),

    BANK("""Bank: (?<amount>[^§]+)(?: / (?<personal>.*))?"""),

    GEMS("""Gems: (?<gems>.*)"""),

    /** Visitors on someone else's island. Counted alongside [PLAYER_LIST]. */
    GUESTS("""Guests \((?<amount>\d+)\)"""),

    COOP("""Coop .*"""),

    ISLAND("""Island"""),

    /** The party widget. Its lines are the members. */
    PARTY("""Party:.*"""),

    /** The dungeon party widget, which counts rather than names. */
    DUNGEON_PARTY("""Party \((?<amount>\d+)\)"""),

    DUNGEON_PUZZLE("""Puzzles: \((?<amount>\d+)\)"""),

    DUNGEON_STATS("""Opened Rooms: (?<opened>\d+)"""),

    // -- activity clues -----------------------------------------------------
    // Present only where the player is. Worth more than the area line for telling
    // "in the Dwarven Mines" from "doing commissions in the Dwarven Mines".

    SKILLS("""Skills: ?(?<avg>[\d.]*)"""),

    STATS("""Stats:"""),

    SLAYER("""Slayer:"""),

    COMMISSIONS("""Commissions:"""),

    POWDER("""Powders:"""),

    CRYSTAL("""Crystals:"""),

    FORGE("""Forges:"""),

    TRAPPER("""Trapper:"""),

    VISITORS("""Visitors: \((?<count>\d+)\)"""),

    COMPOSTER("""Composter:"""),

    GARDEN_LEVEL("""Garden Level: (?<level>.*)"""),

    PESTS("""Pests:"""),

    CROP_MILESTONE("""Crop Milestones:"""),

    JACOB_CONTEST("""Jacob's Contest:.*"""),

    TROPHY_FISH("""Trophy Fish:"""),

    /** The Rift's own info widget. Its presence is the clearest Rift signal there is. */
    RIFT_INFO("""Good to know:"""),

    FACTION_QUESTS("""Faction Quests:"""),

    REPUTATION("""(?<faction>Barbarian|Mage) Reputation:"""),

    DAILY_QUESTS("""Daily Quests:"""),

    COLLECTION("""Collection:"""),

    BESTIARY("""Bestiary:"""),

    ESSENCE("""Essence:.*"""),

    TIMERS("""Timers:"""),

    MINION("""Minions: (?<used>\d+)/(?<max>\d+)"""),

    ACTIVE_EFFECTS("""Active Effects: \((?<amount>\d+)\)"""),

    EVENT("""Event: (?<event>.*)"""),

    ELECTION("""Election: (?<time>.*)"""),

    FIRE_SALE("""Fire Sales: .*"""),

    UNCLAIMED_CHESTS("""Unclaimed chests: (?<amount>\d+)"""),

    PICKAXE_COOLDOWN("""Pickaxe Ability:"""),

    FROZEN_CORPSES("""Frozen Corpses:"""),
    ;

    /**
     * Matches the widget's header line.
     *
     * Leading whitespace is allowed because Hypixel indents some headers, and the whole
     * line has to match so a value line mentioning "Party" is not mistaken for the header
     * of one.
     */
    public val header: Regex = Regex("""\s*(?:$pattern)""")

    /** Whether [line] is this widget's header. */
    public fun matchesHeader(line: String): Boolean = header.matches(line)

    public companion object {
        /** The widget [line] is the header of, or null. */
        public fun ofHeader(line: String): TabWidget? = entries.firstOrNull { it.matchesHeader(line) }
    }
}

/**
 * The shape of Hypixel's tab list, and the two quirks that come with it.
 *
 * Both matter enough to name. A parser that does not know about them reads a widget's lines
 * as belonging to the previous widget, which is the kind of failure that produces a party
 * with a `Players (43)` member in it.
 */
public object TabListLayout {

    /** Four columns of twenty. Entries past this are not part of the board. */
    public const val SIZE: Int = 80

    /** Where each column starts. Hypixel repeats a header at each of these. */
    public val COLUMN_STARTS: List<Int> = listOf(0, 20, 40, 60)

    /**
     * Removes the repeated column headers and anything past the board.
     *
     * `Players (N)` and `Info` appear again at the top of each column, as a heading rather
     * than as a new widget. Left in, each repeat starts a fresh widget and every widget
     * after it is attributed to the wrong one. The first of each is kept — that one is the
     * real header.
     */
    public fun normalise(entries: List<String>): List<String> {
        val board = if (entries.size > SIZE) entries.take(SIZE) else entries
        var seenPlayerList = false
        var seenInfo = false
        val dropped = HashSet<Int>()

        for (index in COLUMN_STARTS) {
            val line = board.getOrNull(index) ?: continue
            when {
                TabWidget.PLAYER_LIST.matchesHeader(line) ->
                    if (seenPlayerList) dropped.add(index) else seenPlayerList = true

                TabWidget.INFO.matchesHeader(line) ->
                    if (seenInfo) dropped.add(index) else seenInfo = true
            }
        }

        return if (dropped.isEmpty()) board else board.filterIndexed { index, _ -> index !in dropped }
    }
}
