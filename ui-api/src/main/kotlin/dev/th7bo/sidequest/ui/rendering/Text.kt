package dev.th7bo.sidequest.ui.rendering

import dev.th7bo.sidequest.ui.geometry.Size

/**
 * How a run of text should be drawn. Purely visual — the colour comes from the theme
 * at draw time, so the same style can be reused across themes.
 */
public data class TextStyle(
    /** Multiplier over the host's base font size. */
    val scale: Float = 1f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    /** Drop shadow behind glyphs. Cheap in Minecraft; used for legibility over the world. */
    val shadow: Boolean = false,
    /** Line spacing multiplier, applied to the host's natural line height. */
    val lineHeight: Float = 1f,
) {
    public companion object {
        public val Default: TextStyle = TextStyle()
    }
}

/** What a piece of text *means*, so the theme can decide how it looks. */
public enum class TextRole {
    /** Screen and section titles. */
    TITLE,

    /** Setting names and primary labels. */
    LABEL,

    /** Ordinary prose. */
    BODY,

    /** Descriptions and de-emphasised detail. */
    SECONDARY,

    /** Smallest supporting text. */
    CAPTION,

    /** Monospaced, for ids and values. */
    MONO,
}

/** What to do when text does not fit. */
public enum class TextOverflow {
    /** Let it be clipped by the surrounding clip rect. */
    CLIP,

    /** Replace the tail with an ellipsis. */
    ELLIPSIS,

    /** Wrap onto further lines, up to the caller's line limit. */
    WRAP,
}

/**
 * The result of laying out a string: immutable, cacheable, and the only thing
 * [UiRenderer.text] accepts.
 *
 * Measuring is the expensive part of text rendering, so it happens once during layout
 * and the result is reused for as long as the string, style and width are unchanged.
 */
public class TextLayout(
    public val text: String,
    public val style: TextStyle,
    public val lines: List<Line>,
    public val size: Size,
    /** True if [TextOverflow.ELLIPSIS] or a line limit dropped content. */
    public val isTruncated: Boolean = false,
) {

    /** One laid-out line. [content] is the substring actually drawn, after truncation. */
    public class Line(
        public val content: String,
        public val width: Float,
        public val baselineOffset: Float,
    )

    override fun toString(): String =
        "TextLayout(${lines.size} line(s), $size${if (isTruncated) ", truncated" else ""})"
}

/**
 * Measures and lays out text.
 *
 * Implementations must cache: the same `(text, style, maxWidth)` triple is measured
 * many times per second across frames, and re-measuring it is one of the easiest ways
 * to blow the layout budget.
 */
public interface TextMeasurer {

    /**
     * Lays out [text].
     *
     * @param maxWidth available width in logical units, or `null` for unbounded.
     * @param maxLines hard cap on lines; the last line is truncated per [overflow].
     */
    public fun measure(
        text: String,
        style: TextStyle = TextStyle.Default,
        maxWidth: Float? = null,
        maxLines: Int = 1,
        overflow: TextOverflow = TextOverflow.CLIP,
    ): TextLayout

    /** The natural line height for [style], independent of any particular string. */
    public fun lineHeight(style: TextStyle = TextStyle.Default): Float
}
