package dev.th7bo.sidequest.platform.core.context

import dev.th7bo.sidequest.platform.core.parser.ScoreboardParser
import dev.th7bo.sidequest.platform.core.parser.TabListParser
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.parser.ScoreboardSnapshot
import dev.th7bo.sidequest.platform.parser.TabListSnapshot
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
) : GameContextService {

    override var context: GameContext = GameContext.None
        private set

    private var lastScoreboard = ScoreboardSnapshot.Empty
    private var lastTabList = TabListSnapshot.Empty

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
    }

    /** Feeds a new tab list. Same contract as [onScoreboard]. */
    public fun onTabList(snapshot: TabListSnapshot) {
        if (snapshot == lastTabList) return
        lastTabList = snapshot
        recompute()
    }

    /** Clears everything. Called on disconnect, so a stale island cannot survive a session. */
    public fun reset() {
        lastScoreboard = ScoreboardSnapshot.Empty
        lastTabList = TabListSnapshot.Empty
        authoritative = null
        update(GameContext.None.copy(isOnHypixel = context.isOnHypixel))
    }

    private fun recompute() {
        val scoreboard = ScoreboardParser.parse(lastScoreboard)
        val tabList = TabListParser.parse(lastTabList)
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
        if (previous.subLocation != next.subLocation && next.subLocation.isKnown) {
            events.post(SubLocationChangedEvent(previous.subLocation, next.subLocation, next), EventSource.DERIVED)
        }
    }

}
