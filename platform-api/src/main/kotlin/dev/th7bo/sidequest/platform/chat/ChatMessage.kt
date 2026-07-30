package dev.th7bo.sidequest.platform.chat

import dev.th7bo.sidequest.platform.parser.HypixelText
import dev.th7bo.sidequest.platform.text.ClickAction
import dev.th7bo.sidequest.platform.text.ClickActionType
import dev.th7bo.sidequest.platform.text.SqText

/**
 * One chat line, at the moment it arrived.
 *
 * A value, like the scoreboard and tab list snapshots: parsing runs against something that
 * cannot change underneath it, and a test can replay a recorded line with no game running.
 * That is the only way chat rules ever get properly tested, and chat is the one input where
 * Hypixel rewords things without warning.
 *
 * Three views of the same text, because different rules genuinely need different ones —
 * see [formatted], [plain] and [clean]. Getting this wrong is subtle: a pattern written
 * against one view and matched against another fails silently and looks like Hypixel
 * changed a message.
 */
public data class ChatMessage(
    /** Exactly what arrived, formatting codes and all. Kept verbatim for logs and traces. */
    public val raw: String,
    /** What the line means structurally. Empty when a caller only had a string. */
    public val text: SqText = SqText.Empty,
    public val kind: ChatKind = ChatKind.SYSTEM,
) {

    /**
     * [raw] with `§r` removed and nothing else changed. **The canonical match target.**
     *
     * Colour has to survive, because it carries meaning: a drop is `§6§lRARE DROP!` and a
     * party line is `§9Party §8>`, and a rule matching the words alone would fire on a
     * player quoting them. Resets do not carry meaning — `§r` says "back to plain", and no
     * pattern keys off a run *lacking* a colour.
     *
     * Dropping them is what makes patterns predictable. How many `§r`s a component
     * serialises to depends on how the message was assembled, so the same visible line can
     * arrive as `§r§b[MVP§r§c+§r§b] Notch` or `§b[MVP§c+§b] Notch`. A pattern written
     * against one of those silently fails on the other, and that is the single most common
     * way a chat rule breaks.
     */
    public val formatted: String by lazy { HypixelText.withoutResets(raw) }

    /**
     * [raw] with formatting codes removed and **nothing else touched**.
     *
     * Leading whitespace survives, which several real patterns depend on: Hypixel centres
     * its banner lines with spaces, so the dungeon-completion line starts with a run of
     * them and the skill level-up line with exactly two. Trimming here would break both.
     */
    public val plain: String by lazy { HypixelText.stripFormatting(raw) }

    /**
     * [plain] normalised: invisible characters gone, runs of spaces collapsed, trimmed.
     *
     * For rules that care about the words rather than the layout, and for logs. Uses the
     * same cleaning as the scoreboard, so one pattern's idea of "cleaned" is every
     * pattern's.
     */
    public val clean: String by lazy { HypixelText.clean(raw) }

    /** Every click action in the line, in order. See [commands]. */
    public val clickActions: List<ClickAction>
        get() = text.runs().mapNotNull { it.clickAction }

    /**
     * Commands the line offers to run, in order.
     *
     * The most reliable thing about a Hypixel chat line. `/party accept Notch` behind an
     * invite prompt has outlived several rewordings of the prompt itself, so a rule that
     * keys off this keeps working when the copy changes.
     */
    public val commands: List<String>
        get() = clickActions
            .filter { it.type == ClickActionType.RUN_COMMAND || it.type == ClickActionType.SUGGEST_COMMAND }
            .map { it.value }

    /** The first command matching [prefix], if the line offers one. */
    public fun commandStartingWith(prefix: String): String? =
        commands.firstOrNull { it.startsWith(prefix, ignoreCase = true) }

    /** The text for a given [target]. */
    public fun textFor(target: MatchTarget): String = when (target) {
        MatchTarget.FORMATTED -> formatted
        MatchTarget.PLAIN -> plain
        MatchTarget.CLEAN -> clean
    }

    public companion object {
        /** Builds a message from a formatted string. The form fixtures and tests use. */
        public fun of(raw: String, kind: ChatKind = ChatKind.SYSTEM): ChatMessage =
            ChatMessage(raw = raw, kind = kind)

        /**
         * Builds a message from structured text plus the game's own formatted rendering.
         *
         * The adapter supplies [raw] rather than this deriving it, because only the game
         * knows which `§` code a given colour renders as, and a copy of that table in the
         * platform is a table that goes stale.
         */
        public fun of(raw: String, text: SqText, kind: ChatKind): ChatMessage =
            ChatMessage(raw = raw, text = text, kind = kind)
    }
}

/**
 * How a line reached the client.
 *
 * Worth keeping: Hypixel sends almost everything as a system message, so a line that
 * arrives as [PLAYER] genuinely was typed by somebody, which is a useful thing for a rule
 * to be able to insist on.
 */
public enum class ChatKind {
    /** A signed player message. */
    PLAYER,

    /** A system message. Nearly all of Hypixel's output. */
    SYSTEM,

    /** The action bar. Not chat, but it arrives the same way and features want it. */
    OVERLAY,
}

/** Which view of a [ChatMessage] a pattern matches against. */
public enum class MatchTarget {
    /** Codes intact, resets removed. The default; see [ChatMessage.formatted]. */
    FORMATTED,

    /** Codes removed, layout intact. See [ChatMessage.plain]. */
    PLAIN,

    /** Normalised and trimmed. See [ChatMessage.clean]. */
    CLEAN,
}

/**
 * Pulling a player name out of Hypixel's display names.
 *
 * A name arrives wrapped in whatever the player has equipped: a colour, a rank tag, a guild
 * tag, an emblem, a private-island tag, a level bracket. Every one of those is optional and
 * Hypixel keeps adding more, so this strips by *shape* — brackets, then anything that is not
 * name-shaped — rather than matching a list of known tags that would need editing every time
 * a cosmetic ships.
 */
public object HypixelNames {

    /** Minecraft's own constraint on a username. */
    private val NAME = Regex("[A-Za-z0-9_]{2,16}")

    /** `[MVP+]`, `[Buddy ツ]`, `[209]` — anything bracketed is a tag, never the name. */
    private val BRACKETED = Regex("""\[[^\]]*]""")

    /** Any run of whitespace, including the non-breaking spaces Hypixel pads with. */
    private val WHITESPACE = Regex("""[\s ]+""")

    /**
     * The player name in [displayName], or null when there is nothing name-shaped in it.
     *
     * Null rather than a best guess, on purpose. A rule acting on a wrong name would invite
     * the wrong person to a party or credit the wrong player with a drop, and both are worse
     * than doing nothing.
     */
    public fun playerName(displayName: String): String? {
        val withoutTags = BRACKETED.replace(HypixelText.stripFormatting(displayName), " ")
        return withoutTags
            .split(WHITESPACE)
            .asSequence()
            .map { it.removeSuffix("'s").removeSuffix("'") }
            .firstOrNull { NAME.matches(it) }
    }

    /** [playerName], or [displayName] cleaned, when nothing name-shaped is in it. */
    public fun playerNameOrRaw(displayName: String): String =
        playerName(displayName) ?: HypixelText.clean(displayName)

    /** Every player name in a list, as the party widgets print them: `a ●, b ●`. */
    public fun playerNames(list: String): List<String> =
        list.split(',', '●').mapNotNull { playerName(it) }
}
