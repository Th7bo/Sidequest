package dev.th7bo.sidequest.platform.core.garden

/** What a spawn announcement said: how many, and where. */
public data class PestSpawn(public val amount: Int, public val plot: String)

/**
 * The chat line that says a pest arrived.
 *
 * **The double space is not a typo.** Hypixel writes the pest's icon between the article and the word, and
 * the icon is a private-use character that cleaning strips — leaving two spaces where a reader expects one.
 * A pattern written from how the line *reads* rather than from what it *contains* silently never matches,
 * and nothing about the mod looks broken; the feature just never fires.
 *
 * The anchor does real work too. Without it, somebody quoting the message in party chat sets it off for
 * everyone in the party. SkyHanni carries that case as an explicit counter-example and so does the test here.
 */
public object PestChat {

    /** The two announcements, one of which names a number. Paired with whether it does. */
    private val PATTERNS = listOf(
        Regex("""^\w+! A {2}Pest has appeared in (?:Plot - )?(?<plot>.*)!""") to false,
        Regex("""^\w+! (?<amount>\d+) {2}Pests? have spawned in (?:Plot - )?(?<plot>.*)!""") to true,
    )

    /** What [message] announces, or null when it announces no pests. Cleaned text only. */
    public fun spawn(message: String): PestSpawn? {
        for ((pattern, counts) in PATTERNS) {
            val match = pattern.matchEntire(message.trim()) ?: continue
            val plot = match.groups["plot"]?.value?.trim().orEmpty()
            if (plot.isEmpty()) return null
            return PestSpawn(
                amount = if (counts) match.groups["amount"]?.value?.toIntOrNull() ?: 1 else 1,
                plot = plot,
            )
        }
        return null
    }

    /** Whether [message] announces a pest at all. */
    public fun isSpawn(message: String): Boolean = spawn(message) != null
}
