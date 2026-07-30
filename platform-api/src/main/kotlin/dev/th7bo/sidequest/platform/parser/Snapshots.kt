package dev.th7bo.sidequest.platform.parser

/**
 * What the scoreboard said, at one moment.
 *
 * A snapshot rather than a live view. Parsing runs against a value that cannot change
 * underneath it, and a test can hand over a recorded scoreboard with no game running —
 * which is the only way parser rules ever get properly tested.
 */
public data class ScoreboardSnapshot(
    /** The title, with formatting intact. */
    public val rawTitle: String,
    /** Lines top to bottom, with formatting intact. */
    public val rawLines: List<String>,
) {
    /** The title with formatting and stray characters removed. */
    public val title: String by lazy { HypixelText.clean(rawTitle) }

    /** Lines with formatting and stray characters removed. */
    public val lines: List<String> by lazy { rawLines.map(HypixelText::clean) }

    public val isEmpty: Boolean get() = rawTitle.isEmpty() && rawLines.isEmpty()

    public companion object {
        public val Empty: ScoreboardSnapshot = ScoreboardSnapshot("", emptyList())
    }
}

/**
 * What the tab list said, at one moment.
 *
 * Hypixel uses the tab list as a widget board: columns of `Area: …`, `Profile: …`,
 * `Server: …` entries alongside the actual player list. The parser treats it as text,
 * because that is what it is.
 */
public data class TabListSnapshot(
    /** Every entry, in display order, with formatting intact. */
    public val rawEntries: List<String>,
    public val rawHeader: String = "",
    public val rawFooter: String = "",
) {
    public val entries: List<String> by lazy { rawEntries.map(HypixelText::clean) }

    public val header: String by lazy { HypixelText.clean(rawHeader) }

    public val footer: String by lazy { HypixelText.clean(rawFooter) }

    public val isEmpty: Boolean get() = rawEntries.isEmpty()

    public companion object {
        public val Empty: TabListSnapshot = TabListSnapshot(emptyList())
    }
}

/**
 * Cleaning for text that came from Hypixel.
 *
 * Every parser goes through this, so what a pattern matches against is predictable. The
 * alternative — each parser stripping what it happens to remember — is how one rule ends
 * up matching and its neighbour silently not.
 */
public object HypixelText {

    /** Minecraft's formatting prefix. */
    private const val SECTION = '§'

    /**
     * Removes formatting codes, genuinely invisible characters and repeated spaces.
     *
     * **The private-use area is deliberately kept.** Hypixel's own texture pack replaced
     * many symbols with private-use glyphs — `\uE067` is the scoreboard's area marker,
     * `\uE010` is health — so they are content, not decoration. An earlier version of this
     * stripped the whole range as "padding" and would have deleted every one of them,
     * silently, leaving patterns that matched nothing.
     *
     * What is actually stripped is what occupies no space at all: zero-width characters
     * and the non-breaking spaces Hypixel pads with.
     */
    public fun clean(text: String): String {
        val stripped = stripFormatting(text)
        val builder = StringBuilder(stripped.length)
        for (character in stripped) {
            if (isInvisible(character)) continue
            builder.append(character)
        }
        return builder.toString().replace(REPEATED_SPACES, " ").trim()
    }

    /** Removes `§x` codes and leaves everything else alone. */
    public fun stripFormatting(text: String): String {
        if (SECTION !in text) return text
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == SECTION && index + 1 < text.length) {
                index += 2
                continue
            }
            builder.append(character)
            index++
        }
        return builder.toString()
    }

    /**
     * Removes `§r` and leaves every other code alone.
     *
     * For chat, where the count of resets is an artefact of how the message was assembled
     * rather than anything about the message. See [ChatMessage.formatted][
     * dev.th7bo.sidequest.platform.chat.ChatMessage.formatted] for why that matters enough
     * to normalise.
     */
    public fun withoutResets(text: String): String {
        if (SECTION !in text) return text
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == SECTION && index + 1 < text.length && (text[index + 1] == 'r' || text[index + 1] == 'R')) {
                index += 2
                continue
            }
            builder.append(character)
            index++
        }
        return builder.toString()
    }

    /**
     * Characters that occupy no visible space.
     *
     * Note what is *not* here: the private-use area. Those glyphs render as icons from
     * Hypixel's texture pack and carry meaning. See [clean].
     */
    private fun isInvisible(character: Char): Boolean = when (character) {
        '\u00A0' -> true // non-breaking space
        '\u200B', '\u200C', '\u200D' -> true // zero-width space, non-joiner, joiner
        '\uFEFF' -> true // byte order mark
        else -> false
    }

    private val REPEATED_SPACES = Regex(" {2,}")
}
