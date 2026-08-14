package dev.th7bo.sidequest.platform.core.presence

/**
 * What Discord shows on somebody's profile.
 *
 * Deliberately Discord's own vocabulary rather than the mod's: two text lines, two images with tooltips, and
 * a clock. Mapping [dev.th7bo.sidequest.platform.skyblock.GameContext] onto it is [PresenceComposer]'s job,
 * and keeping that mapping in one place is what lets the whole of it be tested without a Discord client
 * anywhere near the test.
 *
 * Every field is optional because every field is a *disclosure*. This is the widest audience anything in the
 * mod publishes to — the group's backend reaches a friend group, this reaches everybody on somebody's Discord
 * friend list — so nothing lands here that a setting did not allow.
 */
public data class RichPresence(
    /** The first line, under the application name. What the player is doing. */
    public val details: String? = null,

    /** The second line. Where they are, and who with. */
    public val state: String? = null,

    /**
     * Epoch **seconds** — not milliseconds — for the "elapsed" clock, or null for no clock.
     *
     * The unit is the whole of the field. Milliseconds here produce a timer counting from somewhere in the
     * year 55000, which renders as a plausible-looking number and is wrong by fifty thousand years.
     *
     * Confirmed against a live Discord rather than taken on faith: sending `1786711130` came back echoed as
     * `1786711130000`, so Discord reads seconds and normalises to milliseconds itself. See `DiscordLiveTest`.
     */
    public val startedAtEpochSeconds: Long? = null,

    /** Asset key of the large image, as uploaded to the Discord application. */
    public val largeImage: String? = null,

    /** Tooltip on the large image. */
    public val largeText: String? = null,

    /** Asset key of the small image, overlaid on the corner of the large one. */
    public val smallImage: String? = null,

    /** Tooltip on the small image. */
    public val smallText: String? = null,
)

/**
 * What the player has agreed to put on their Discord profile.
 *
 * Separate from [dev.th7bo.sidequest.platform.permission.Permission], which governs what the *group* sees.
 * The two are not the same audience and must not share a switch: somebody can reasonably tell six friends
 * where they are and not want it on a profile their coworkers can read.
 *
 * The defaults are what somebody who turned this on would expect to see, with the two personal details —
 * the profile name and anything naming another player — left off.
 */
public data class PresenceDisclosure(
    /** The island, the area within it, and the island's picture. */
    public val showIsland: Boolean = true,

    /** What the player is doing, including the dungeon floor and the Kuudra tier. */
    public val showActivity: Boolean = true,

    /**
     * The SkyBlock profile's name and game mode.
     *
     * Off by default. People name their profiles things they would not choose as a public label, and unlike
     * an island it says nothing about what they are doing.
     */
    public val showProfile: Boolean = false,

    /**
     * How many people are in the party.
     *
     * A count, never names. Naming somebody else would publish *their* whereabouts to an audience they have
     * no relationship with and never agreed to — see [PresenceComposer], which has no path to a name.
     */
    public val showParty: Boolean = true,

    /** The elapsed clock, counting from when this session reached Hypixel. */
    public val showElapsed: Boolean = true,
) {
    public companion object {
        /** Nothing but "playing SkyBlock". What every switch turned off leaves. */
        public val Minimal: PresenceDisclosure = PresenceDisclosure(
            showIsland = false,
            showActivity = false,
            showProfile = false,
            showParty = false,
            showElapsed = false,
        )
    }
}

/**
 * Everything the presence feature reads from the configuration, as one value.
 *
 * One snapshot rather than five suppliers, because the settings are read on the client thread and acted on
 * from a background one: a value that could change halfway through composing a presence would produce a line
 * that was never any single setting.
 */
public data class PresenceSettings(
    public val isEnabled: Boolean = false,

    /**
     * The Discord application this presence belongs to.
     *
     * There is no default and there cannot be one. An application id names somebody's registered Discord
     * application — its name is the bold line on the profile card, and its uploaded art is what every asset
     * key resolves against — so shipping one would put this group's activity under a stranger's application
     * name. Blank means the feature stays off, which is the honest behaviour rather than a broken one.
     */
    public val applicationId: String = "",

    public val disclosure: PresenceDisclosure = PresenceDisclosure(),
) {
    /** Whether there is enough here to try connecting at all. */
    public val isUsable: Boolean get() = isEnabled && applicationId.isNotBlank()
}
