package dev.th7bo.sidequest.platform.core.garden

/** What a spawn announcement said: how many, and where. */
public data class PestSpawn(public val amount: Int, public val plot: String)

/**
 * The chat line that says a pest arrived.
 *
 * **What sits between the article and the word is not a space.** Hypixel puts the pest's icon there, as a
 * private-use glyph, so the line contains `A \uE0xx Pest` rather than `A Pest`. This mod's cleaning keeps
 * private-use characters on purpose — they are content on Hypixel, not decoration — and collapses runs of
 * spaces to one, so neither "a space" nor "two spaces" describes what actually arrives.
 *
 * Hence the gap is matched as *whitespace and glyphs, one or more*: it holds however the line is cleaned,
 * which is what a pattern copied from another mod's conventions did not. That version never matched
 * anything, and nothing looked broken — the feature simply never fired.
 *
 * The anchor does real work too. Without it, somebody quoting the message in party chat sets it off for
 * everyone in the party. SkyHanni carries that case as an explicit counter-example and so does the test here.
 */
public object PestChat {

    /** The two announcements, one of which names a number. Paired with whether it does. */
    private val PATTERNS = listOf(
        Regex("""^\w+! A$GAP Pests? has appeared in (?:Plot - )?(?<plot>.*)!""") to false,
        Regex("""^\w+! (?<amount>\d+)$GAP Pests? have spawned in (?:Plot - )?(?<plot>.*)!""") to true,
    )

    /**
     * The space, or icon, or both, that separates the count from the word.
     *
     * Anything that is not a letter, repeated. Written this way rather than as an exact count of spaces so
     * that it holds whether the icon survived cleaning, was stripped, or was collapsed into the space
     * beside it — three outcomes that depend on the mod doing the cleaning rather than on Hypixel.
     */
    private const val GAP = """[^A-Za-z]*"""

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
