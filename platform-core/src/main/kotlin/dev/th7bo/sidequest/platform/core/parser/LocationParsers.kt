package dev.th7bo.sidequest.platform.core.parser

import dev.th7bo.sidequest.platform.parser.HypixelText
import dev.th7bo.sidequest.platform.parser.ScoreboardSnapshot
import dev.th7bo.sidequest.platform.parser.TabListSnapshot
import dev.th7bo.sidequest.platform.skyblock.Island
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
)

/** What the tab list could be read to say. */
public data class TabListReading(
    public val island: Island? = null,
    public val serverId: ServerId? = null,
    public val profile: SkyBlockProfile? = null,
    /** True when the area widget said `Dungeon:` rather than `Area:`. */
    public val isInDungeon: Boolean = false,
)

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
     * been moving symbols to private-use glyphs (`` for this one), and a hardcoded
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

        return ScoreboardReading(
            isSkyBlock = true,
            subLocation = subLocation,
            serverId = serverId,
            // The title says GUEST while visiting, which is the only client-side signal
            // that the island is someone else's.
            isGuest = title.contains("GUEST", ignoreCase = true),
        )
    }
}

/**
 * Reads Hypixel's tab-list widgets.
 *
 * The tab list is the more authoritative of the two for *which island* — it names it
 * outright, where the scoreboard only gives the area within it.
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

        var island: Island? = null
        var isInDungeon = false
        var serverId: ServerId? = null
        var profile: SkyBlockProfile? = null

        for (entry in snapshot.entries) {
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

        return TabListReading(island, serverId, profile, isInDungeon)
    }
}
