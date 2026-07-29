package dev.th7bo.sidequest.ui.testkit

import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.rendering.TextLayout
import dev.th7bo.sidequest.ui.rendering.TextMeasurer
import dev.th7bo.sidequest.ui.rendering.TextOverflow
import dev.th7bo.sidequest.ui.rendering.TextStyle
import kotlin.math.max

/**
 * Deterministic text metrics: every glyph is [charWidth] wide and every line is
 * [baseLineHeight] tall, both scaled by the style.
 *
 * Fixed metrics are the point. Layout tests can then assert exact sizes without
 * depending on a font, and the same assertions hold on every machine.
 */
public class FakeTextMeasurer(
    private val charWidth: Float = 6f,
    private val baseLineHeight: Float = 9f,
) : TextMeasurer {

    /** Number of measure calls, so tests can assert the layout cache is working. */
    public var measureCount: Int = 0
        private set

    public fun resetCounters() {
        measureCount = 0
    }

    override fun lineHeight(style: TextStyle): Float = baseLineHeight * style.lineHeight * style.scale

    override fun measure(
        text: String,
        style: TextStyle,
        maxWidth: Float?,
        maxLines: Int,
        overflow: TextOverflow,
    ): TextLayout {
        measureCount++

        val glyphWidth = charWidth * style.scale
        val lineHeight = lineHeight(style)

        if (maxWidth == null || overflow != TextOverflow.WRAP) {
            return singleLine(text, style, glyphWidth, lineHeight, maxWidth, overflow)
        }

        val charsPerLine = max(1, (maxWidth / glyphWidth).toInt())
        val wrapped = wrap(text, charsPerLine)
        val truncated = wrapped.size > maxLines
        val kept = wrapped.take(maxLines)

        val lines = kept.mapIndexed { index, content ->
            TextLayout.Line(
                content = content,
                width = content.length * glyphWidth,
                baselineOffset = lineHeight * index + lineHeight * BASELINE_FRACTION,
            )
        }
        return TextLayout(
            text = text,
            style = style,
            lines = lines,
            size = Size(
                lines.maxOfOrNull { it.width } ?: 0f,
                lineHeight * lines.size,
            ),
            isTruncated = truncated,
        )
    }

    private fun singleLine(
        text: String,
        style: TextStyle,
        glyphWidth: Float,
        lineHeight: Float,
        maxWidth: Float?,
        overflow: TextOverflow,
    ): TextLayout {
        var content = text.substringBefore('\n')
        var truncated = content.length != text.length
        val naturalWidth = content.length * glyphWidth

        if (maxWidth != null && naturalWidth > maxWidth) {
            val fits = max(0, (maxWidth / glyphWidth).toInt())
            content = when (overflow) {
                TextOverflow.ELLIPSIS ->
                    if (fits <= ELLIPSIS.length) ELLIPSIS.take(fits)
                    else content.take(fits - ELLIPSIS.length) + ELLIPSIS
                else -> content.take(fits)
            }
            truncated = true
        }

        val width = content.length * glyphWidth
        return TextLayout(
            text = text,
            style = style,
            lines = listOf(TextLayout.Line(content, width, lineHeight * BASELINE_FRACTION)),
            size = Size(width, lineHeight),
            isTruncated = truncated,
        )
    }

    private fun wrap(text: String, charsPerLine: Int): List<String> {
        val result = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) {
                result.add("")
                continue
            }
            val line = StringBuilder()
            for (word in paragraph.split(' ')) {
                when {
                    line.isEmpty() -> line.append(word)
                    line.length + 1 + word.length <= charsPerLine -> line.append(' ').append(word)
                    else -> {
                        result.add(line.toString())
                        line.setLength(0)
                        line.append(word)
                    }
                }
            }
            if (line.isNotEmpty()) result.add(line.toString())
        }
        return result
    }

    private companion object {
        const val ELLIPSIS = "..."
        const val BASELINE_FRACTION = 0.8f
    }
}
