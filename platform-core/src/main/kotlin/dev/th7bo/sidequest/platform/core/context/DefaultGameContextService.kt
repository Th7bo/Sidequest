package dev.th7bo.sidequest.platform.core.context

import dev.th7bo.sidequest.platform.core.parser.ScoreboardParser
import dev.th7bo.sidequest.platform.core.parser.TabListParser
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.parser.ScoreboardChangedEvent
import dev.th7bo.sidequest.platform.parser.ScoreboardSnapshot
import dev.th7bo.sidequest.platform.parser.TabListChangedEvent
import dev.th7bo.sidequest.platform.parser.TabListSnapshot
import dev.th7bo.sidequest.platform.parser.TabWidget
import dev.th7bo.sidequest.platform.skyblock.ActivityChangedEvent
import dev.th7bo.sidequest.platform.skyblock.ContextConfidence
import dev.th7bo.sidequest.platform.skyblock.GameContext
import dev.th7bo.sidequest.platform.skyblock.GameContextService
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.IslandChangedEvent
import dev.th7bo.sidequest.platform.skyblock.ProfileChangedEvent
import dev.th7bo.sidequest.platform.skyblock.ServerChangedEvent
import dev.th7bo.sidequest.platform.skyblock.ServerId
import dev.th7bo.sidequest.platform.skyblock.SkyBlockJoinEvent
import dev.th7bo.sidequest.platform.skyblock.SkyBlockLeaveEvent
import dev.th7bo.sidequest.platform.skyblock.SkyBlockProfile
import dev.th7bo.sidequest.platform.skyblock.SubLocation
import dev.th7bo.sidequest.platform.skyblock.SubLocationChangedEvent
import dev.th7bo.sidequest.platform.log.Logger

/**
 * Merges what the sources say into one context, and announces the changes.
 *
 * Two rules do most of the work here.
 *
 * **A source that says nothing changes nothing.** Every reading field is nullable, and
 * null means "did not say", not "empty". Without that, a tab list that has not populated
 * yet would clear the profile a second after the player joined — a class of bug that
 * looks like flickering state and is miserable to chase.
 *
 * **Disagreement lowers confidence rather than picking a winner.** The tab list names the
 * island and the scoreboard names the area within it; they update at different times, and
 * for a second after a server hop they describe different places. Features that act check
 * [GameContext.isReliable] and wait.
 */
public class DefaultGameContextService(
    private val events: EventBus,
    private val log: Logger,
    /**
     * Whether the player has moved recently.
     *
     * Supplied by the adapter, because only the client knows. The single thing separating "idle on a
     * private island" from "building on one", and the activity detector says so rather than guessing.
     */
    private val isMoving: () -> Boolean = { true },
) : GameContextService {

    override var context: GameContext = GameContext.None
        private set

    private var lastScoreboard = ScoreboardSnapshot.Empty
    private var lastTabList = TabListSnapshot.Empty

    /**
     * The last parsed readings, kept so the board change events can diff against them.
     *
     * The diffs live here rather than in a separate poller because this is already the one
     * place both boards arrive. A second polling path would be a second thing to keep in
     * step, and the two could disagree about what "changed" means.
     */
    private var lastScoreboardLines: List<String> = emptyList()
    private var lastWidgets: Map<TabWidget, List<String>> = emptyMap()

    /** The tab list as [recompute] last read it, so the diff does not parse it a second time. */
    private var widgets: Map<TabWidget, List<String>> = emptyMap()

    /** Set from the client adapter. False means everything below is meaningless. */
    public fun setOnHypixel(isOnHypixel: Boolean) {
        if (context.isOnHypixel == isOnHypixel) return
        update(context.copy(isOnHypixel = isOnHypixel))
        if (!isOnHypixel) reset()
    }

    /**
     * Location as reported by Hypixel itself, over their Mod API.
     *
     * The best source there is: the server names the island rather than the client
     * inferring it, and it arrives when the player moves rather than when a widget
     * redraws. It therefore overrides the scraped island outright and pins confidence at
     * [ContextConfidence.CONFIRMED] — there is nothing to corroborate when the answer came
     * from the authority.
     *
     * Optional. Without the Mod API installed this is never called and the scraped
     * sources stand on their own, which is what the confidence levels exist to express.
     *
     * @param islandApiName Hypixel's own island id, e.g. `mining_3`.
     */
    public fun onHypixelLocation(
        isSkyBlock: Boolean,
        islandApiName: String?,
        serverName: String?,
        isLobby: Boolean,
    ) {
        authoritative = AuthoritativeLocation(
            isSkyBlock = isSkyBlock && !isLobby,
            island = islandApiName?.let(Island::ofApiName),
            serverId = serverName?.takeIf { it.isNotEmpty() }?.let(::ServerId),
        )
        if (islandApiName != null && authoritative?.island == Island.UNKNOWN) {
            // Worth saying out loud: it means Hypixel shipped an island the enum does not
            // have, and the fix is one line rather than a debugging session.
            log.warn { "Hypixel reported an island id we do not know: '$islandApiName'" }
        }
        recompute()
    }

    /** What the Mod API last said, or null when it is not installed. */
    private var authoritative: AuthoritativeLocation? = null

    private data class AuthoritativeLocation(
        val isSkyBlock: Boolean,
        val island: Island?,
        val serverId: ServerId?,
    )

    /** Feeds a new scoreboard. Cheap to call every tick — an unchanged snapshot returns early. */
    public fun onScoreboard(snapshot: ScoreboardSnapshot) {
        if (snapshot == lastScoreboard) return
        lastScoreboard = snapshot
        recompute()
        announceScoreboardChange(snapshot)
    }

    /** Feeds a new tab list. Same contract as [onScoreboard]. */
    public fun onTabList(snapshot: TabListSnapshot) {
        if (snapshot == lastTabList) return
        lastTabList = snapshot
        recompute()
        announceTabListChange(snapshot)
    }

    /**
     * Posts what changed on the sidebar, comparing cleaned lines.
     *
     * Cleaned, not raw: Hypixel animates the colours on several lines, so a raw diff reports
     * a change a few times a second on a board that says exactly the same thing.
     *
     * After [recompute], so a listener reading [context] during the event sees the state the
     * new lines describe rather than the state before them.
     */
    private fun announceScoreboardChange(snapshot: ScoreboardSnapshot) {
        val lines = snapshot.lines
        val added = lines - lastScoreboardLines.toSet()
        val removed = lastScoreboardLines - lines.toSet()
        lastScoreboardLines = lines
        if (added.isEmpty() && removed.isEmpty()) return
        events.post(ScoreboardChangedEvent(snapshot, added, removed), EventSource.PARSER)
    }

    /** Posts which widgets came, went, or changed inside. See [TabListChangedEvent]. */
    private fun announceTabListChange(snapshot: TabListSnapshot) {
        val added = widgets.keys - lastWidgets.keys
        val removed = lastWidgets.keys - widgets.keys
        val changed = widgets.keys.intersect(lastWidgets.keys)
            .filterTo(HashSet()) { widgets[it] != lastWidgets[it] }
        lastWidgets = widgets
        if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) return
        events.post(TabListChangedEvent(snapshot, added, removed, changed), EventSource.PARSER)
    }

    /** Clears everything. Called on disconnect, so a stale island cannot survive a session. */
    public fun reset() {
        lastScoreboard = ScoreboardSnapshot.Empty
        lastTabList = TabListSnapshot.Empty
        lastScoreboardLines = emptyList()
        lastWidgets = emptyMap()
        widgets = emptyMap()
        authoritative = null
        update(GameContext.None.copy(isOnHypixel = context.isOnHypixel))
    }

    /**
     * Renders both boards and both readings.
     *
     * Raw lines as well as cleaned: a line that looks right and does not match almost always
     * has an invisible character or a formatting code where nobody expected one, and only the
     * raw form shows that. The `§` is replaced with `&` so the output survives being printed
     * into chat, which would otherwise eat it as formatting.
     */
    override fun describeSources(): List<String> = buildList {
        val scoreboard = ScoreboardParser.parse(lastScoreboard)
        val tabList = TabListParser.parse(lastTabList)

        add("context: $context")
        add("scoreboard title: ${lastScoreboard.rawTitle.printable()}")
        add("scoreboard reading: $scoreboard")
        for ((index, raw) in lastScoreboard.rawLines.withIndex()) {
            add("  [$index] ${raw.printable()}")
        }
        add("tab list reading: island=${tabList.island} server=${tabList.serverId} " +
            "profile=${tabList.profile} players=${tabList.playerCount}")
        add("activity: ${context.activity}")
        add("tab widgets (${tabList.widgets.size}):")
        for ((widget, lines) in tabList.widgets) {
            add("  $widget: ${lines.drop(1).joinToString(" | ")}")
        }
        if (tabList.partyMembers.isNotEmpty()) add("party: ${tabList.partyMembers.joinToString(", ")}")
    }

    /** Formatting codes made visible, so a dump can be printed into chat unchanged. */
    private fun String.printable(): String = replace('§', '&')

    private fun recompute() {
        val scoreboard = ScoreboardParser.parse(lastScoreboard)
        val tabList = TabListParser.parse(lastTabList)
        widgets = tabList.widgets
        val fromServer = authoritative

        // Hypixel's own answer wins where it has one. Scraping only decides whether we are
        // in SkyBlock when nothing better has spoken.
        val isInSkyBlock = fromServer?.isSkyBlock ?: scoreboard.isSkyBlock
        if (!isInSkyBlock) {
            update(GameContext.None.copy(isOnHypixel = context.isOnHypixel))
            return
        }

        // Only the tab list names the island among the scraped sources, so it is the sole
        // fallback for that. Keeping the previous value when nothing says anything is what
        // stops the island blanking for the second between a hop and the widget
        // repopulating.
        val island = fromServer?.island
            ?: tabList.island
            ?: context.island.takeIf { it.isRealIsland }
            ?: Island.UNKNOWN
        val guestIsland = if (scoreboard.isGuest) island.guestVariant else island

        val next = GameContext(
            isOnHypixel = true,
            isInSkyBlock = true,
            island = guestIsland,
            subLocation = scoreboard.subLocation ?: context.subLocation,
            serverId = fromServer?.serverId ?: tabList.serverId ?: scoreboard.serverId ?: context.serverId,
            profile = tabList.profile ?: context.profile,
            isGuest = scoreboard.isGuest,
            // Only the scoreboard says these, and only while they apply, so "did not say"
            // and "not in one" are the same answer here — unlike the island, which has to
            // survive a widget reloading.
            dungeonFloor = scoreboard.dungeonFloor,
            kuudraTier = scoreboard.kuudraTier,
            profileType = scoreboard.profileType ?: context.profileType,
            activity = ActivityDetector.detect(guestIsland, scoreboard, tabList, isMoving = isMoving()),
            confidence = when {
                // Nothing to corroborate when the answer came from the authority.
                fromServer?.island != null -> ContextConfidence.CONFIRMED
                else -> confidenceOf(
                    hasIsland = tabList.island != null,
                    hasArea = scoreboard.subLocation != null,
                    hasProfile = tabList.profile != null,
                )
            },
        )
        update(next)
    }

    /**
     * How much of the context is corroborated.
     *
     * The island naming and the area line come from different widgets that Hypixel
     * updates independently, so both agreeing is meaningfully stronger than either alone.
     */
    private fun confidenceOf(hasIsland: Boolean, hasArea: Boolean, hasProfile: Boolean): ContextConfidence = when {
        hasIsland && hasArea && hasProfile -> ContextConfidence.CONFIRMED
        hasIsland -> ContextConfidence.PROBABLE
        hasArea -> ContextConfidence.GUESSED
        else -> ContextConfidence.NONE
    }

    /**
     * Applies [next] and announces what changed.
     *
     * Events are posted after the context has been swapped, so a listener reading
     * [context] during one sees the new value. A listener that saw the old value while
     * being told about the new one would be a trap nobody expects to step in.
     */
    private fun update(next: GameContext) {
        val previous = context
        if (previous == next) return
        context = next

        if (!previous.isInSkyBlock && next.isInSkyBlock) {
            log.info { "Entered SkyBlock on ${next.island.displayName}" }
            events.post(SkyBlockJoinEvent(next), EventSource.DERIVED)
        }
        if (previous.isInSkyBlock && !next.isInSkyBlock) {
            log.info { "Left SkyBlock" }
            events.post(SkyBlockLeaveEvent(next), EventSource.DERIVED)
        }

        if (previous.island != next.island) {
            events.post(IslandChangedEvent(previous.island, next.island, next), EventSource.DERIVED)
        }
        if (previous.serverId != next.serverId && next.serverId.isKnown) {
            events.post(ServerChangedEvent(previous.serverId, next.serverId, next), EventSource.DERIVED)
        }
        if (previous.profile != next.profile && next.profile.isKnown) {
            events.post(ProfileChangedEvent(previous.profile, next.profile, next), EventSource.DERIVED)
        }
        if (previous.activity.activity != next.activity.activity) {
            events.post(ActivityChangedEvent(previous.activity, next.activity, next), EventSource.DERIVED)
        }
        if (previous.subLocation != next.subLocation && next.subLocation.isKnown) {
            events.post(SubLocationChangedEvent(previous.subLocation, next.subLocation, next), EventSource.DERIVED)
        }
    }

}
