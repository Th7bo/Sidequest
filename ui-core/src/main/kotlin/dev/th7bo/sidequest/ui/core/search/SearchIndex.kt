package dev.th7bo.sidequest.ui.core.search

import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.DropdownSetting
import dev.th7bo.sidequest.ui.config.NoticeSetting
import dev.th7bo.sidequest.ui.config.Setting
import dev.th7bo.sidequest.ui.ids.UiId

/** Why a setting matched, strongest first. Also the primary ranking key. */
public enum class MatchKind(public val baseScore: Int) {
    /** The query is exactly the title. */
    EXACT_TITLE(1000),

    /** The title starts with the query. */
    TITLE_PREFIX(900),

    /** A word within the title starts with the query. */
    TITLE_WORD_PREFIX(800),

    /** The title contains the query somewhere. */
    TITLE_CONTAINS(700),

    /** A search keyword matched exactly. */
    KEYWORD_EXACT(650),

    /** A keyword starts with the query. */
    KEYWORD_PREFIX(600),

    /** A dropdown option's label matched. */
    OPTION_LABEL(500),

    /** The category or section name matched. */
    LOCATION(400),

    /** The description contains the query. */
    DESCRIPTION(300),

    /** The stable id contains the query. */
    IDENTIFIER(200),

    /** The query's characters appear in order, but not contiguously. */
    FUZZY(100),
}

/** A highlighted span within a result's title. */
public data class Highlight(val start: Int, val endExclusive: Int) {
    init {
        require(start >= 0 && endExclusive >= start) { "Invalid highlight $start..$endExclusive" }
    }
}

/** One search hit. */
public class SearchResult(
    public val setting: Setting<*>,
    public val kind: MatchKind,
    public val score: Int,
    /** Spans within the title to emphasise. Empty when the match was elsewhere. */
    public val highlights: List<Highlight>,
    /** Human-readable trail, e.g. `General > Interface`. */
    public val categoryPath: String,
) {
    public val id: UiId get() = setting.id

    override fun toString(): String = "${setting.id} ($kind, $score)"
}

/**
 * The configuration search index.
 *
 * Entries are built once per setting and updated incrementally — the index is never
 * rebuilt from scratch on a keystroke, and never per frame.
 *
 * Ranking is tiered by [MatchKind] and deterministic: ties break on title length, then
 * on the stable id, so the same query always produces the same order. That is what
 * makes ranking testable rather than a matter of taste.
 */
public class SearchIndex {

    private val entries = LinkedHashMap<UiId, Entry>()

    public val size: Int get() = entries.size

    /** Adds or replaces the entry for [setting]. */
    public fun add(setting: Setting<*>) {
        // Notices carry no configuration worth finding, except dividers which carry none
        // at all; skipping them keeps the result list free of unclickable rows.
        if (setting is NoticeSetting && setting.kind == NoticeSetting.Kind.DIVIDER) return
        entries[setting.id] = Entry.of(setting)
    }

    /** Indexes every setting on [screen]. */
    public fun addAll(screen: ConfigScreen) {
        for (setting in screen.settings) add(setting)
    }

    public fun remove(id: UiId): Boolean = entries.remove(id) != null

    /**
     * Re-reads one setting's terms.
     *
     * Needed when a dynamic title, description or option list changes; costs one entry
     * rather than a full reindex.
     */
    public fun refresh(setting: Setting<*>) {
        if (entries.containsKey(setting.id)) add(setting)
    }

    public fun clear() {
        entries.clear()
    }

    /**
     * Runs [query] against the index.
     *
     * A blank query returns nothing rather than everything — "no filter" is the caller's
     * job to represent, and returning ten thousand results is never useful.
     */
    public fun search(query: String, limit: Int = DEFAULT_LIMIT): List<SearchResult> {
        val normalised = query.trim().lowercase()
        if (normalised.isEmpty()) return emptyList()

        val results = ArrayList<SearchResult>()
        for (entry in entries.values) {
            // A hidden setting cannot be navigated to, so offering it is a dead end.
            if (!entry.setting.isVisible.peek()) continue
            val match = entry.match(normalised) ?: continue
            results.add(match)
        }

        results.sortWith(RANKING)
        return if (results.size <= limit) results else results.subList(0, limit).toList()
    }

    private class Entry(
        val setting: Setting<*>,
        val title: String,
        val lowerTitle: String,
        val description: String?,
        val keywords: List<String>,
        val optionLabels: List<String>,
        val location: String,
        val lowerLocation: String,
        val identifier: String,
    ) {

        fun match(query: String): SearchResult? {
            exactOrPrefix(query)?.let { return it }

            for (keyword in keywords) {
                if (keyword == query) return result(MatchKind.KEYWORD_EXACT, query, 0)
                if (keyword.startsWith(query)) return result(MatchKind.KEYWORD_PREFIX, query, 0)
            }

            if (optionLabels.any { it.contains(query) }) {
                return result(MatchKind.OPTION_LABEL, query, 0)
            }
            if (lowerLocation.contains(query)) {
                return result(MatchKind.LOCATION, query, 0)
            }
            if (description?.contains(query) == true) {
                return result(MatchKind.DESCRIPTION, query, 0)
            }
            if (identifier.contains(query)) {
                return result(MatchKind.IDENTIFIER, query, 0)
            }
            if (isSubsequence(query, lowerTitle)) {
                return result(MatchKind.FUZZY, query, 0)
            }
            return null
        }

        private fun exactOrPrefix(query: String): SearchResult? {
            if (lowerTitle == query) return result(MatchKind.EXACT_TITLE, query, 0)
            if (lowerTitle.startsWith(query)) return result(MatchKind.TITLE_PREFIX, query, 0)

            val wordStart = wordPrefixIndex(query)
            if (wordStart >= 0) return result(MatchKind.TITLE_WORD_PREFIX, query, wordStart)

            val contains = lowerTitle.indexOf(query)
            if (contains >= 0) return result(MatchKind.TITLE_CONTAINS, query, contains)
            return null
        }

        /** Index of a word in the title that starts with [query], or -1. */
        private fun wordPrefixIndex(query: String): Int {
            var index = lowerTitle.indexOf(query)
            while (index > 0) {
                if (!lowerTitle[index - 1].isLetterOrDigit()) return index
                index = lowerTitle.indexOf(query, index + 1)
            }
            return -1
        }

        private fun result(kind: MatchKind, query: String, titleOffset: Int): SearchResult {
            val highlights = when (kind) {
                MatchKind.EXACT_TITLE, MatchKind.TITLE_PREFIX ->
                    listOf(Highlight(0, query.length))
                MatchKind.TITLE_WORD_PREFIX, MatchKind.TITLE_CONTAINS ->
                    listOf(Highlight(titleOffset, titleOffset + query.length))
                MatchKind.FUZZY -> subsequenceHighlights(query, lowerTitle)
                else -> emptyList()
            }
            return SearchResult(setting, kind, kind.baseScore, highlights, location)
        }

        companion object {
            fun of(setting: Setting<*>): Entry {
                val title = setting.metadata.title.peek()
                val optionLabels = if (setting is DropdownSetting<*>) {
                    setting.options.peek().map { it.label.peek().lowercase() }
                } else {
                    emptyList()
                }
                val location = setting.location?.path ?: ""
                return Entry(
                    setting = setting,
                    title = title,
                    lowerTitle = title.lowercase(),
                    description = setting.metadata.description?.peek()?.lowercase(),
                    keywords = setting.metadata.keywords.map { it.lowercase() },
                    optionLabels = optionLabels,
                    location = location,
                    lowerLocation = location.lowercase(),
                    identifier = setting.id.value.lowercase(),
                )
            }

            /** True if every character of [needle] appears in [haystack], in order. */
            fun isSubsequence(needle: String, haystack: String): Boolean {
                if (needle.isEmpty()) return true
                var cursor = 0
                for (character in haystack) {
                    if (character == needle[cursor] && ++cursor == needle.length) return true
                }
                return false
            }

            fun subsequenceHighlights(needle: String, haystack: String): List<Highlight> {
                val spans = ArrayList<Highlight>(needle.length)
                var cursor = 0
                for ((index, character) in haystack.withIndex()) {
                    if (cursor < needle.length && character == needle[cursor]) {
                        spans.add(Highlight(index, index + 1))
                        cursor++
                    }
                }
                return if (cursor == needle.length) spans else emptyList()
            }
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 50

        /**
         * Score first, then the shorter title (a closer match to what was typed), then
         * the stable id. The final key guarantees a total order, so results never
         * reshuffle between identical queries.
         */
        val RANKING: Comparator<SearchResult> = compareByDescending<SearchResult> { it.score }
            .thenBy { it.setting.metadata.title.peek().length }
            .thenBy { it.setting.id.value }
    }
}
