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
     * Removes formatting codes, invisible characters and repeated spaces.
     *
     * Hypixel pads scoreboard lines with zero-width and other invisible characters to
     * force unique scoreboard entries — two lines that look identical must differ, or the
     * scoreboard drops one. Those characters are not part of the text and would otherwise
     * appear in the middle of anything a pattern tries to match.
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
     * Characters that occupy no visible space.
     *
     * Kept as a predicate rather than a set because Hypixel's padding characters change:
     * anything in the private-use area or a zero-width control is padding by definition,
     * whichever one they pick next.
     */
    private fun isInvisible(character: Char): Boolean = when (character) {
        '\u00A0' -> true // non-breaking space
        in '\uE000'..'\uF8FF' -> true // private use area, where the padding glyphs live
        '\u200B', '\u200C', '\u200D' -> true // zero-width space, non-joiner, joiner
        '\uFEFF' -> true // byte order mark
        else -> false
    }

    private val REPEATED_SPACES = Regex(" {2,}")
}
