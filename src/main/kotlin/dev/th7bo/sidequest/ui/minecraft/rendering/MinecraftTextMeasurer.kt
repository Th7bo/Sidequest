package dev.th7bo.sidequest.ui.minecraft.rendering

import dev.th7bo.sidequest.ui.diagnostics.UiDiagnostics
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.rendering.TextLayout
import dev.th7bo.sidequest.ui.rendering.TextMeasurer
import dev.th7bo.sidequest.ui.rendering.TextOverflow
import dev.th7bo.sidequest.ui.rendering.TextStyle
import net.minecraft.client.gui.Font
import kotlin.math.max

/**
 * Measures text with Minecraft's font, and caches the results.
 *
 * Text measurement is the most repeated expensive operation in a configuration screen —
 * the same label is measured every time its row is laid out — so the cache is the point
 * of this class, not an optimisation bolted on.
 *
 * Entries are keyed by everything that can change the outcome: the string, the style,
 * the available width and the line limit. The cache is cleared on resource reload,
 * because a resource pack can change glyph widths.
 */
public class MinecraftTextMeasurer(
    private val font: Font,
    private val diagnostics: UiDiagnostics? = null,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : TextMeasurer {

    private data class CacheKey(
        val text: String,
        val scale: Float,
        val bold: Boolean,
        val lineHeight: Float,
        val maxWidth: Float,
        val maxLines: Int,
        val overflow: TextOverflow,
    )

    /**
     * Bounded LRU. Access-ordered so eviction drops what has not been laid out recently,
     * which for a scrolling list means rows that have left the viewport.
     */
    private val cache = object : LinkedHashMap<CacheKey, TextLayout>(
        INITIAL_CAPACITY,
        LOAD_FACTOR,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, TextLayout>?): Boolean =
            size > maxEntries
    }

    /** Entries currently held. Exposed for the diagnostics overlay. */
    public val cacheSize: Int get() = cache.size

    override fun lineHeight(style: TextStyle): Float = font.lineHeight * style.scale * style.lineHeight

    override fun measure(
        text: String,
        style: TextStyle,
        maxWidth: Float?,
        maxLines: Int,
        overflow: TextOverflow,
    ): TextLayout {
        val key = CacheKey(
            text = text,
            scale = style.scale,
            bold = style.bold,
            lineHeight = style.lineHeight,
            maxWidth = maxWidth ?: Float.NaN,
            maxLines = maxLines,
            overflow = overflow,
        )

        cache[key]?.let {
            diagnostics?.cacheHits++
            return it
        }

        diagnostics?.cacheMisses++
        diagnostics?.textLayouts++
        val layout = layOut(text, style, maxWidth, maxLines, overflow)
        cache[key] = layout
        return layout
    }

    private fun layOut(
        text: String,
        style: TextStyle,
        maxWidth: Float?,
        maxLines: Int,
        overflow: TextOverflow,
    ): TextLayout {
        val lineHeight = lineHeight(style)

        if (maxLines <= 1) {
            return singleLine(text, style, maxWidth, overflow, lineHeight)
        }

        // A newline always breaks, whatever the overflow mode. [TextOverflow] decides what
        // happens to a line too *wide* for the box, not whether the text has lines at all
        // — reading it as "only WRAP is multi-line" is what made the text area render its
        // first line and silently swallow the rest.
        val available = if (maxWidth == null) Float.POSITIVE_INFINITY else maxWidth / style.scale
        val broken = if (overflow == TextOverflow.WRAP && maxWidth != null) {
            wrap(text, available)
        } else {
            text.split('\n')
        }

        var truncated = broken.size > maxLines
        val kept = broken.take(maxLines).toMutableList()

        // Only WRAP moves the overflow onto the next line; the other modes shorten each
        // hard line in place.
        if (maxWidth != null && overflow != TextOverflow.WRAP) {
            for (index in kept.indices) {
                if (font.width(kept[index]) * style.scale <= maxWidth) continue
                kept[index] = shorten(kept[index], available, overflow)
                truncated = true
            }
        }

        val lines = kept.mapIndexed { index, content ->
            TextLayout.Line(
                content = content,
                width = font.width(content) * style.scale,
                baselineOffset = lineHeight * index + lineHeight * BASELINE_FRACTION,
            )
        }
        return TextLayout(
            text = text,
            style = style,
            lines = lines,
            size = Size(lines.maxOfOrNull { it.width } ?: 0f, lineHeight * max(1, lines.size)),
            isTruncated = truncated,
        )
    }

    private fun singleLine(
        text: String,
        style: TextStyle,
        maxWidth: Float?,
        overflow: TextOverflow,
        lineHeight: Float,
    ): TextLayout {
        var content = text.substringBefore('\n')
        var truncated = content.length != text.length
        var width = font.width(content) * style.scale

        if (maxWidth != null && width > maxWidth) {
            content = shorten(content, maxWidth / style.scale, overflow)
            width = font.width(content) * style.scale
            truncated = true
        }

        return TextLayout(
            text = text,
            style = style,
            lines = listOf(TextLayout.Line(content, width, lineHeight * BASELINE_FRACTION)),
            size = Size(width, lineHeight),
            isTruncated = truncated,
        )
    }

    /** Cuts [content] down to [availableWidth] unscaled units, honouring [overflow]. */
    private fun shorten(content: String, availableWidth: Float, overflow: TextOverflow): String =
        when (overflow) {
            TextOverflow.ELLIPSIS -> {
                val room = availableWidth - font.width(ELLIPSIS)
                if (room <= 0f) ELLIPSIS else font.plainSubstrByWidth(content, room.toInt()) + ELLIPSIS
            }
            else -> font.plainSubstrByWidth(content, availableWidth.toInt())
        }

    /** Greedy word wrap. Words longer than a line are hard-split rather than overflowing. */
    private fun wrap(text: String, availableWidth: Float): List<String> {
        val result = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) {
                result.add("")
                continue
            }
            val line = StringBuilder()
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                when {
                    font.width(candidate) <= availableWidth -> {
                        line.setLength(0)
                        line.append(candidate)
                    }
                    line.isEmpty() -> {
                        // A single word too wide for the line: split it rather than
                        // letting it run past the edge.
                        var remaining = word
                        while (remaining.isNotEmpty() && font.width(remaining) > availableWidth) {
                            val head = font.plainSubstrByWidth(remaining, availableWidth.toInt())
                            if (head.isEmpty()) break
                            result.add(head)
                            remaining = remaining.substring(head.length)
                        }
                        line.append(remaining)
                    }
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

    /**
     * Drops every cached layout.
     *
     * Called on resource reload: a resource pack can replace the font, which changes
     * every glyph width and would otherwise leave the screen laid out for the old one.
     */
    public fun invalidate() {
        cache.clear()
    }

    private companion object {
        const val ELLIPSIS = "..."
        const val BASELINE_FRACTION = 0.8f
        const val DEFAULT_MAX_ENTRIES = 4096
        const val INITIAL_CAPACITY = 256
        const val LOAD_FACTOR = 0.75f
    }
}
