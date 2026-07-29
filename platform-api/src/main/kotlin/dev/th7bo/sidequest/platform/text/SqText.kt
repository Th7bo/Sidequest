package dev.th7bo.sidequest.platform.text

import kotlinx.serialization.Serializable

/**
 * Formatted text, independent of Minecraft's component classes.
 *
 * Needed in both directions. Outward, so a feature can build a chat message without
 * importing a game class. Inward, so the chat parser sees structure — the click event on
 * a party invite, the hover text on an item — instead of the flattened string that
 * throws away exactly the information that makes matching reliable.
 *
 * A tree of runs, each with its own style, matching how Minecraft models text without
 * committing to how it represents it.
 */
@Serializable
public data class SqText(
    public val content: String = "",
    public val style: SqStyle = SqStyle.None,
    public val children: List<SqText> = emptyList(),
    /** What clicking does, if anything. Preserved because parsers key off it. */
    public val clickAction: ClickAction? = null,
    /** Tooltip text, if any. Item lore arrives here. */
    public val hoverText: SqText? = null,
) {

    /** This node and its children as one string, with no formatting. */
    public val plainText: String
        get() = buildString { appendPlainTo(this) }

    private fun appendPlainTo(builder: StringBuilder) {
        builder.append(content)
        for (child in children) child.appendPlainTo(builder)
    }

    /** Every run in order, flattened. For matching that cares about styling. */
    public fun runs(): List<SqText> = buildList { collectRuns(this) }

    private fun collectRuns(into: MutableList<SqText>) {
        if (content.isNotEmpty()) into.add(this)
        for (child in children) child.collectRuns(into)
    }

    public operator fun plus(other: SqText): SqText = copy(children = children + other)

    public companion object {
        public val Empty: SqText = SqText()

        public fun of(content: String, style: SqStyle = SqStyle.None): SqText =
            SqText(content = content, style = style)

        /** Joins [parts] under an empty root, the way a component chain would render. */
        public fun join(vararg parts: SqText): SqText = SqText(children = parts.toList())
    }
}

@Serializable
public data class SqStyle(
    /** ARGB. Null means "inherit", which is not the same as white. */
    public val color: Int? = null,
    public val bold: Boolean = false,
    public val italic: Boolean = false,
    public val underlined: Boolean = false,
    public val strikethrough: Boolean = false,
    public val obfuscated: Boolean = false,
) {
    public companion object {
        public val None: SqStyle = SqStyle()
    }
}

/**
 * What clicking a run does.
 *
 * Kept as data because it is evidence, not just behaviour: a chat line offering a party
 * invite is identified far more reliably by the command behind it than by its wording,
 * which changes with every Hypixel copy edit.
 */
@Serializable
public data class ClickAction(
    public val type: ClickActionType,
    public val value: String,
)

@Serializable
public enum class ClickActionType {
    RUN_COMMAND,
    SUGGEST_COMMAND,
    OPEN_URL,
    COPY_TO_CLIPBOARD,
    CHANGE_PAGE,
    OTHER,
}
