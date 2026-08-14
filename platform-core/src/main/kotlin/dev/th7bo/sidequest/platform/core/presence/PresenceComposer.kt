package dev.th7bo.sidequest.platform.core.presence

import dev.th7bo.sidequest.platform.party.PartyState
import dev.th7bo.sidequest.platform.skyblock.Activity
import dev.th7bo.sidequest.platform.skyblock.GameContext
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.ProfileType

/**
 * Turns where the player is into what Discord shows.
 *
 * Pure: a context in, a presence out, no clock and no connection. Everything that decides *what somebody's
 * Discord profile says about them* is therefore testable without a Discord client, which matters more here
 * than anywhere else in the mod — a wrong string in a toast is seen by one person for five seconds, and a
 * wrong string here sits on a public profile until the next update.
 *
 * **Disclosure is enforced field by field, and it gates images as well as text.** An island the player has
 * chosen not to publish must not leak through its picture either, which is the mistake this shape exists to
 * make impossible: there is one place the island is read, and it is inside the same check as the name.
 */
public object PresenceComposer {

    /**
     * The presence for a moment, or null when there should be none at all.
     *
     * Null is not "show nothing interesting" — it is "take the presence down". Off Hypixel entirely, the mod
     * has no business claiming anything about what the player is doing.
     *
     * @param sessionStartedAtEpochSeconds when this session reached Hypixel, in epoch **seconds**.
     */
    public fun compose(
        context: GameContext,
        party: PartyState,
        disclosure: PresenceDisclosure,
        sessionStartedAtEpochSeconds: Long? = null,
    ): RichPresence? {
        if (!context.isOnHypixel) return null

        val startedAt = sessionStartedAtEpochSeconds.takeIf { disclosure.showElapsed }

        if (!context.isInSkyBlock) {
            return RichPresence(
                details = LOBBY,
                startedAtEpochSeconds = startedAt,
                largeImage = LOGO,
                largeText = HYPIXEL,
            )
        }

        val details = details(context, disclosure)
        return RichPresence(
            details = details,
            state = state(context, party, disclosure, details),
            startedAtEpochSeconds = startedAt,
            // Both gated on the island, so a hidden island cannot arrive as a picture of itself.
            largeImage = if (disclosure.showIsland && context.island.isRealIsland) {
                islandAsset(context.island)
            } else {
                LOGO
            },
            largeText = largeText(context, disclosure),
            smallImage = shownActivity(context, disclosure)?.let(::activityAsset),
            smallText = shownActivity(context, disclosure)?.displayName?.let(::clean),
        )
    }

    // -- the first line -------------------------------------------------------

    /**
     * What the player is doing.
     *
     * The dungeon floor and the Kuudra tier count as activity rather than as location, and are worded
     * without naming the island for that reason: "Dungeons · Floor 7" says what somebody is doing, and the
     * island line says where. Somebody who publishes their activity and not their island gets the floor;
     * somebody who publishes neither gets [SKYBLOCK].
     */
    private fun details(context: GameContext, disclosure: PresenceDisclosure): String {
        if (!disclosure.showActivity) return SKYBLOCK

        context.dungeonFloor?.let { return join(Activity.DUNGEONS.displayName, floorLabel(it)) }
        context.kuudraTier?.let { return join(Activity.KUUDRA.displayName, "Tier $it") }

        return shownActivity(context, disclosure)?.displayName ?: SKYBLOCK
    }

    /**
     * Hypixel's floor tag as words.
     *
     * `F7`, `M3` and `E` are what the scoreboard writes and what the parser keeps — see
     * [dev.th7bo.sidequest.platform.core.parser.ScoreboardParser]. They are fine on a scoreboard where the
     * reader is already looking at a dungeon, and cryptic on a profile where they are not.
     *
     * Anything unrecognised passes through untouched. Hypixel adding a floor shape should read oddly, not
     * disappear.
     */
    internal fun floorLabel(floor: String): String {
        val tag = floor.trim()
        if (tag.equals(ENTRANCE_TAG, ignoreCase = true)) return "Entrance"
        val number = tag.drop(1).toIntOrNull() ?: return tag
        return when (tag.first().uppercaseChar()) {
            'F' -> "Floor $number"
            'M' -> "Master $number"
            else -> tag
        }
    }

    // -- the second line ------------------------------------------------------

    /**
     * Where the player is, and how many they are with.
     *
     * [details] is passed in so the island can be left out when the first line has already said it — "Kuudra
     * · Tier 4" above "Kuudra" is a line wasted repeating itself, and the island whose name matches an
     * activity is exactly the case where the second line has something better to say.
     */
    private fun state(
        context: GameContext,
        party: PartyState,
        disclosure: PresenceDisclosure,
        details: String,
    ): String? {
        val pieces = buildList {
            if (disclosure.showIsland) {
                val island = context.island.displayName
                    .takeIf { context.island.isRealIsland && it.isNotEmpty() && it !in details }
                island?.let(::add)

                val area = context.subLocation.name.trim()
                if (area.isNotEmpty() && !area.equals(island, ignoreCase = true) && area !in details) add(area)
            }
            // A count, and there is deliberately no branch here that could reach a name.
            if (disclosure.showParty && party.isInParty && party.size > 1) add("Party of ${party.size}")
        }
        return clean(pieces.joinToString(SEPARATOR))
    }

    // -- the images -----------------------------------------------------------

    /** The island's picture, and the profile if it may be named. */
    private fun largeText(context: GameContext, disclosure: PresenceDisclosure): String? {
        val place = context.island.displayName
            .takeIf { disclosure.showIsland && context.island.isRealIsland && it.isNotEmpty() }
            ?: SKYBLOCK

        if (!disclosure.showProfile || !context.profile.isKnown) return clean(place)

        val mode = context.profileType.takeIf { it != ProfileType.NORMAL }?.displayName
        val profile = if (mode == null) context.profile.name else "${context.profile.name} ($mode)"
        return clean(join(place, profile))
    }

    /**
     * The activity worth publishing, or null.
     *
     * Reliable readings only. Activity detection is a pile of heuristics and a guess is fine to *display* —
     * the codebase says so — but a guess here is a claim on a profile that outlives the moment that produced
     * it, and "Fishing" over somebody's name while they are in a boss fight is worse than saying nothing.
     */
    private fun shownActivity(context: GameContext, disclosure: PresenceDisclosure): Activity? {
        if (!disclosure.showActivity) return null
        val reading = context.activity
        if (!reading.isReliable) return null
        return reading.activity.takeIf { it != Activity.UNKNOWN }
    }

    /**
     * The asset key for an island, derived rather than tabulated.
     *
     * From [Island.serializedId], which is already the stable identifier that survives the enum being
     * renamed or reordered. A hand-written table would be a second list of sixty islands to keep in step
     * with the first, and the failure — one island quietly showing the wrong picture — is the kind nobody
     * reports.
     *
     * Uploading these to the Discord application is optional. An unknown key shows no image, which is why
     * every line of text above stands on its own.
     */
    public fun islandAsset(island: Island): String = "island_${island.serializedId}"

    /** The asset key for an activity. Derived from the enum for the same reason as [islandAsset]. */
    public fun activityAsset(activity: Activity): String = "activity_${activity.name.lowercase()}"

    /** The application's own icon, for when there is nothing more specific to show. */
    public const val LOGO: String = "sidequest"

    // -- text hygiene ---------------------------------------------------------

    private fun join(first: String, second: String): String = "$first$SEPARATOR$second"

    /**
     * A line Discord will accept, or null.
     *
     * Two jobs. The length cap is Discord's; a longer line is rejected outright rather than truncated by
     * them, which would lose the whole update rather than the tail of one string. The minimum is Discord's
     * too and is the stranger of the two: a single-character `state` is refused, so an area that shrank to
     * one letter would take the entire presence down with it.
     */
    private fun clean(text: String?): String? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.length < MINIMUM_LENGTH) return null
        return trimmed.take(MAXIMUM_LENGTH)
    }

    /** What separates two things on one line. A middle dot, which Discord renders and a hyphen would not. */
    private const val SEPARATOR = " · "

    private const val LOBBY = "In a Hypixel lobby"
    private const val HYPIXEL = "Hypixel"
    private const val SKYBLOCK = "SkyBlock"

    /** The dungeon entrance's tag. One letter, unlike every other floor. */
    private const val ENTRANCE_TAG = "E"

    private const val MINIMUM_LENGTH = 2
    private const val MAXIMUM_LENGTH = 128
}
