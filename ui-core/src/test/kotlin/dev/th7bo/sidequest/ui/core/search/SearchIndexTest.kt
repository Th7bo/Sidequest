package dev.th7bo.sidequest.ui.core.search

import dev.th7bo.sidequest.ui.binding.asBinding
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.config.option
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Ranking is asserted exactly rather than loosely: "the right thing is somewhere in the
 * results" is not a useful guarantee for a search box.
 */
class SearchIndexTest {

    private fun id(path: String) = UiId.of("sidequest", path)

    private lateinit var index: SearchIndex
    private lateinit var screen: ConfigScreen
    private val notificationsVisible = mutableStateOf(true)

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()

        screen = configScreen(id("main"), "Sidequest") {
            category(id("general"), "General") {
                section("Interface") {
                    toggle(id("general.notifications"), "Notifications", mutableStateOf(true).asBinding())
                    toggle(
                        id("general.compact_mode"),
                        "Compact Mode",
                        mutableStateOf(false).asBinding(),
                        description = "Use a denser layout everywhere",
                    ) {
                        keywords("density", "spacing")
                    }
                    dropdown(
                        id("general.theme"),
                        "Theme",
                        mutableStateOf("dark").asBinding(),
                        listOf(
                            option("dark", "Midnight", "dark"),
                            option("light", "Daylight", "light"),
                        ),
                    )
                }
                section("Notifications") {
                    slider(
                        id("general.notification_duration"),
                        "Notification Duration",
                        mutableStateOf(5).asBinding(),
                        1..60,
                    )
                    toggle(
                        id("general.hidden_flag"),
                        "Hidden Flag",
                        mutableStateOf(false).asBinding(),
                    ) {
                        visibleWhen = notificationsVisible
                    }
                }
            }
            category(id("chat"), "Chat") {
                section("Filters") {
                    toggle(id("chat.spam_filter"), "Spam Filter", mutableStateOf(true).asBinding())
                }
            }
        }

        index = SearchIndex().apply { addAll(screen) }
    }

    @AfterEach
    fun tearDown() {
        resetReactiveGraphForTesting()
    }

    @Test
    fun `every setting is indexed`() {
        assertEquals(screen.settingCount, index.size)
    }

    @Test
    fun `an exact title match outranks a prefix match`() {
        val results = index.search("notifications")

        assertEquals(id("general.notifications"), results[0].id)
        assertEquals(MatchKind.EXACT_TITLE, results[0].kind)
        // "Notification Duration" is a section-name and identifier match, ranked below.
        assertTrue(results.size > 1)
        assertTrue(results[1].score < results[0].score)
    }

    @Test
    fun `a prefix match outranks a mid-word match`() {
        val results = index.search("com")

        assertEquals(id("general.compact_mode"), results[0].id)
        assertEquals(MatchKind.TITLE_PREFIX, results[0].kind)
    }

    @Test
    fun `a word prefix outranks a plain contains`() {
        val results = index.search("mode")

        assertEquals(id("general.compact_mode"), results[0].id)
        assertEquals(
            MatchKind.TITLE_WORD_PREFIX,
            results[0].kind,
            "'Mode' begins a word in 'Compact Mode'",
        )
    }

    @Test
    fun `keywords are searchable`() {
        val results = index.search("density")

        assertEquals(id("general.compact_mode"), results[0].id)
        assertEquals(MatchKind.KEYWORD_EXACT, results[0].kind)
    }

    @Test
    fun `dropdown option labels are searchable`() {
        val results = index.search("midnight")

        assertEquals(id("general.theme"), results.single().id)
        assertEquals(MatchKind.OPTION_LABEL, results.single().kind)
    }

    @Test
    fun `descriptions are searchable and rank below titles`() {
        val results = index.search("denser")

        assertEquals(id("general.compact_mode"), results.single().id)
        assertEquals(MatchKind.DESCRIPTION, results.single().kind)
        assertTrue(results.single().score < MatchKind.TITLE_CONTAINS.baseScore)
    }

    @Test
    fun `the stable identifier is searchable`() {
        val results = index.search("spam_filter")

        assertEquals(id("chat.spam_filter"), results[0].id)
    }

    @Test
    fun `fuzzy matching is a last resort, not a first choice`() {
        // "cmp" is a subsequence of "compact mode" but a prefix of nothing.
        val results = index.search("cmp")

        assertEquals(id("general.compact_mode"), results.single().id)
        assertEquals(MatchKind.FUZZY, results.single().kind)
    }

    @Test
    fun `fuzzy matching does not match characters out of order`() {
        assertTrue(index.search("edom").none { it.id == id("general.compact_mode") })
    }

    @Test
    fun `results carry the category path for navigation`() {
        val result = index.search("notifications")[0]
        assertEquals("General > Interface", result.categoryPath)
    }

    @Test
    fun `title matches carry highlight spans`() {
        val result = index.search("mode")[0]
        val title = result.setting.metadata.title.peek()
        val highlight = result.highlights.single()

        assertEquals("Mode", title.substring(highlight.start, highlight.endExclusive))
    }

    @Test
    fun `an exact match highlights from the beginning`() {
        val result = index.search("notifications")[0]
        assertEquals(listOf(Highlight(0, "notifications".length)), result.highlights)
    }

    @Test
    fun `fuzzy matches highlight each matched character`() {
        val result = index.search("cmp").single()
        assertEquals(3, result.highlights.size)
        assertTrue(result.highlights.zipWithNext().all { (a, b) -> a.start < b.start })
    }

    @Test
    fun `hidden settings are excluded because they cannot be navigated to`() {
        assertEquals(1, index.search("hidden flag").size)

        notificationsVisible.value = false

        assertTrue(index.search("hidden flag").isEmpty())
    }

    @Test
    fun `a blank query returns nothing rather than everything`() {
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
    }

    @Test
    fun `search is case and whitespace insensitive`() {
        assertEquals(
            index.search("notifications").map { it.id },
            index.search("  NoTiFiCaTiOnS  ").map { it.id },
        )
    }

    @Test
    fun `ranking is deterministic across repeated queries`() {
        val first = index.search("o").map { it.id }
        repeat(5) {
            assertEquals(first, index.search("o").map { it.id }, "ranking must not reshuffle")
        }
    }

    @Test
    fun `equal-scoring results order by title length then id`() {
        val extraIndex = SearchIndex()
        val screenTwo = configScreen(id("other"), "Other") {
            category(id("cat"), "Cat") {
                section("S") {
                    // All three are TITLE_PREFIX matches for "zz". The first two have
                    // identical title lengths, so only the id can separate them.
                    toggle(id("z.long"), "Zz Very Long Title", mutableStateOf(false).asBinding())
                    toggle(id("z.beta"), "Zz Bbb", mutableStateOf(false).asBinding())
                    toggle(id("z.alpha"), "Zz Aaa", mutableStateOf(false).asBinding())
                }
            }
        }
        extraIndex.addAll(screenTwo)

        val results = extraIndex.search("zz")

        assertEquals(
            listOf(id("z.alpha"), id("z.beta"), id("z.long")),
            results.map { it.id },
            "shorter titles first; equal lengths break on the stable id",
        )
    }

    @Test
    fun `the result limit is respected`() {
        assertEquals(2, index.search("o", limit = 2).size)
    }

    @Test
    fun `removing an entry takes it out of the results`() {
        assertTrue(index.search("spam").isNotEmpty())

        assertTrue(index.remove(id("chat.spam_filter")))

        assertTrue(index.search("spam").isEmpty())
        assertFalse(index.remove(id("chat.spam_filter")), "removing twice reports no change")
    }

    @Test
    fun `refresh picks up a changed dynamic title without a full rebuild`() {
        val title = mutableStateOf("Original Name")
        val setting = dev.th7bo.sidequest.ui.config.ToggleSetting(
            id("dyn.flag"),
            dev.th7bo.sidequest.ui.config.SettingMetadata(title),
            mutableStateOf(false).asBinding(),
            false,
        )
        index.add(setting)
        assertEquals(1, index.search("original").size)

        title.value = "Renamed"
        index.refresh(setting)

        assertTrue(index.search("original").isEmpty())
        assertEquals(1, index.search("renamed").size)
    }

    @Test
    fun `refresh on an unindexed setting does not add it`() {
        val setting = dev.th7bo.sidequest.ui.config.ToggleSetting(
            id("never.indexed"),
            dev.th7bo.sidequest.ui.config.SettingMetadata(mutableStateOf("Never Indexed")),
            mutableStateOf(false).asBinding(),
            false,
        )
        val before = index.size

        index.refresh(setting)

        assertEquals(before, index.size)
    }

    @Test
    fun `dividers are not indexed because there is nowhere to navigate`() {
        val withDivider = configScreen(id("d"), "D") {
            category(id("dc"), "DC") {
                section("S") {
                    divider(id("dc.rule"))
                    toggle(id("dc.flag"), "Flag", mutableStateOf(false).asBinding())
                }
            }
        }
        val dividerIndex = SearchIndex().apply { addAll(withDivider) }

        assertEquals(1, dividerIndex.size)
    }

    @Test
    fun `searching five thousand settings stays well under the budget`() {
        val large = SearchIndex()
        val bulk = configScreen(id("bulk"), "Bulk") {
            category(id("bulk_cat"), "Bulk") {
                section("Everything") {
                    repeat(SETTING_COUNT) { index ->
                        toggle(
                            id("bulk.setting_$index"),
                            "Setting Number $index",
                            mutableStateOf(false).asBinding(),
                        )
                    }
                }
            }
        }
        large.addAll(bulk)
        assertEquals(SETTING_COUNT, large.size)

        // Warm up, then measure: the budget is about steady-state, not class loading.
        repeat(3) { large.search("setting number 4321") }

        val start = System.nanoTime()
        val results = large.search("setting number 4321")
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000.0

        assertEquals(id("bulk.setting_4321"), results[0].id)
        assertTrue(elapsedMillis < SEARCH_BUDGET_MILLIS) {
            "search over $SETTING_COUNT settings took ${elapsedMillis}ms, budget is ${SEARCH_BUDGET_MILLIS}ms"
        }
    }

    private companion object {
        const val SETTING_COUNT = 5000
        const val SEARCH_BUDGET_MILLIS = 50.0
    }
}
