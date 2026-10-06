package dev.th7bo.sidequest.platform.core.garden

/** The two times the Garden can be set to. */
public enum class GardenTime(public val displayName: String) {
    DAY("Day"),
    NIGHT("Night"),
}

/**
 * The chat lines this mod reads to know what the Garden's time is and when a pest died.
 *
 * Anchored for the same reason [PestChat] is: somebody quoting either line in party chat must not change what
 * the mod thinks the time is, or set off the alarm for everybody who can see it.
 */
public object GardenTimeChat {

    /** `Your garden time has been set to Day!` — Hypixel's own confirmation, cleaned. */
    private val TIME_SET = Regex("""^Your garden time has been set to (?<time>Day|Night)!$""")

    /**
     * The reward line every pest kill prints: `You received 7x Enchanted Potato for killing a Locust!`.
     *
     * The reward, not the kill, is what Hypixel announces — and it is the one line that arrives exactly once
     * per pest, whichever way the pest died.
     */
    private val PEST_KILLED = Regex("""^You received .+ for killing an? (?<pest>.+?)!$""")

    /** The time [message] says the Garden was set to, or null when it says nothing of the sort. */
    public fun timeSet(message: String): GardenTime? =
        when (TIME_SET.matchEntire(message.trim())?.groups?.get("time")?.value) {
            "Day" -> GardenTime.DAY
            "Night" -> GardenTime.NIGHT
            else -> null
        }

    /** The pest [message] says was killed, or null when it reports no kill. */
    public fun pestKilled(message: String): String? =
        PEST_KILLED.matchEntire(message.trim())?.groups?.get("pest")?.value?.trim()?.takeIf { it.isNotEmpty() }
}
