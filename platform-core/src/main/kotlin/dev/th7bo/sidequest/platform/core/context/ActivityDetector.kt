package dev.th7bo.sidequest.platform.core.context

import dev.th7bo.sidequest.platform.core.parser.ScoreboardReading
import dev.th7bo.sidequest.platform.core.parser.TabListReading
import dev.th7bo.sidequest.platform.parser.TabWidget
import dev.th7bo.sidequest.platform.skyblock.Activity
import dev.th7bo.sidequest.platform.skyblock.ActivityReading
import dev.th7bo.sidequest.platform.skyblock.ContextConfidence
import dev.th7bo.sidequest.platform.skyblock.Island

/**
 * Works out what the player is doing.
 *
 * Two kinds of signal, and the order matters.
 *
 * **What the game says outright.** In a dungeon the scoreboard names the floor; in Kuudra it names
 * the tier. Those are not heuristics, and they are [ContextConfidence.CONFIRMED].
 *
 * **What the tab list implies.** A `Commissions:` widget appears only in the Dwarven Mines while
 * commissions are available; `Visitors: (3)` only in the Garden. A widget being present is a
 * stronger signal than the island alone, because it says what the island is *for* right now — but
 * it is still an inference, so it is [ContextConfidence.PROBABLE].
 *
 * **The island alone** is the weakest useful signal and is [ContextConfidence.GUESSED]: standing on
 * the Crimson Isle says nothing about whether the player is questing, fishing or passing through.
 *
 * Falling through all of that gives [Activity.UNKNOWN], which the plan asks for explicitly. An
 * invented activity is worse than an absent one, because features act on it.
 */
public object ActivityDetector {

    /**
     * Widgets that mean an activity outright.
     *
     * Each of these appears only where the activity happens. Ordered, because more than one can be
     * on the board at once — the Garden shows both `Visitors:` and `Composter:` — and the first
     * match wins.
     */
    private val WIDGET_SIGNALS: List<Pair<TabWidget, Activity>> = listOf(
        TabWidget.DUNGEON_PARTY to Activity.DUNGEONS,
        TabWidget.DUNGEON_PUZZLE to Activity.DUNGEONS,
        TabWidget.DUNGEON_STATS to Activity.DUNGEONS,
        TabWidget.VISITORS to Activity.GARDEN,
        TabWidget.COMPOSTER to Activity.GARDEN,
        TabWidget.PESTS to Activity.GARDEN,
        TabWidget.CROP_MILESTONE to Activity.GARDEN,
        TabWidget.COMMISSIONS to Activity.MINING,
        TabWidget.POWDER to Activity.MINING,
        TabWidget.CRYSTAL to Activity.MINING,
        TabWidget.FORGE to Activity.FORGE,
        TabWidget.TROPHY_FISH to Activity.FISHING,
        TabWidget.TRAPPER to Activity.CRIMSON_QUESTING,
        TabWidget.FACTION_QUESTS to Activity.CRIMSON_QUESTING,
        TabWidget.REPUTATION to Activity.CRIMSON_QUESTING,
        TabWidget.RIFT_INFO to Activity.RIFT,
    )

    /**
     * Islands whose whole purpose is one activity.
     *
     * The fallback, and deliberately short. Only islands where the answer is not in doubt are here:
     * a player in the Crystal Hollows is mining, and a player in the Hub could be doing anything.
     */
    private val ISLAND_SIGNALS: Map<Island, Activity> = mapOf(
        Island.CATACOMBS to Activity.DUNGEONS,
        Island.KUUDRA_ARENA to Activity.KUUDRA,
        Island.GARDEN to Activity.GARDEN,
        Island.GARDEN_GUEST to Activity.GARDEN,
        Island.DWARVEN_MINES to Activity.MINING,
        Island.CRYSTAL_HOLLOWS to Activity.MINING,
        Island.MINESHAFT to Activity.MINING,
        Island.DEEP_CAVERNS to Activity.MINING,
        Island.GOLD_MINES to Activity.MINING,
        Island.THE_FARMING_ISLANDS to Activity.FARMING,
        Island.BACKWATER_BAYOU to Activity.FISHING,
        Island.LOTUS_ATOLL to Activity.FISHING,
        Island.THE_PARK to Activity.FORAGING,
        Island.GALATEA to Activity.FORAGING,
        Island.TORRHUS_CANYON to Activity.FORAGING,
        Island.THE_RIFT to Activity.RIFT,
        Island.DARK_AUCTION to Activity.AUCTION,
    )

    /**
     * The verdict.
     *
     * @param isMoving whether the player has moved recently. The only thing separating "idle on a
     *   private island" from "building on a private island", and the client is the only thing that
     *   knows it.
     */
    public fun detect(
        island: Island,
        scoreboard: ScoreboardReading,
        tabList: TabListReading,
        isMoving: Boolean = true,
    ): ActivityReading {
        // 1. What the game states outright.
        scoreboard.kuudraTier?.let {
            return confirmed(Activity.KUUDRA, "scoreboard names tier T$it")
        }
        scoreboard.dungeonFloor?.let {
            return confirmed(Activity.DUNGEONS, "scoreboard names floor $it")
        }
        // The slayer widget is present all over SkyBlock; a quest *in progress* is what counts, and
        // the widget carries lines only while one is.
        if (tabList.linesOf(TabWidget.SLAYER).any { it.isNotBlank() }) {
            return confirmed(Activity.SLAYER, "slayer widget has a quest in it")
        }

        // 2. What the tab list implies.
        for ((widget, activity) in WIDGET_SIGNALS) {
            if (tabList.has(widget)) {
                return ActivityReading(activity, ContextConfidence.PROBABLE, "$widget widget present")
            }
        }

        // 3. The island alone.
        ISLAND_SIGNALS[island]?.let {
            return ActivityReading(it, ContextConfidence.GUESSED, "on ${island.displayName}")
        }

        // 4. Nothing said anything. Idle is only claimed where it can be told apart from busy.
        return when {
            !island.isRealIsland -> ActivityReading.Unknown
            !isMoving && island.isPersonalIsland ->
                ActivityReading(Activity.IDLE, ContextConfidence.GUESSED, "still, on a personal island")
            else -> ActivityReading(Activity.EXPLORING, ContextConfidence.GUESSED, "no activity signal")
        }
    }

    private fun confirmed(activity: Activity, reason: String) =
        ActivityReading(activity, ContextConfidence.CONFIRMED, reason)
}
