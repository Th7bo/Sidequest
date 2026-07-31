package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.config.ButtonSetting
import dev.th7bo.sidequest.ui.config.Category
import dev.th7bo.sidequest.ui.config.ColorSetting
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.DropdownSetting
import dev.th7bo.sidequest.ui.config.FloatSliderSetting
import dev.th7bo.sidequest.ui.config.IntSliderSetting
import dev.th7bo.sidequest.ui.config.KeybindSetting
import dev.th7bo.sidequest.ui.config.ListSetting
import dev.th7bo.sidequest.ui.config.TextAreaSetting
import dev.th7bo.sidequest.ui.config.NoticeSetting
import dev.th7bo.sidequest.ui.config.Section
import dev.th7bo.sidequest.ui.config.Setting
import dev.th7bo.sidequest.ui.config.TextSetting
import dev.th7bo.sidequest.ui.config.ToggleSetting
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.component.ComponentRegistry
import dev.th7bo.sidequest.ui.core.focus.FocusManager
import dev.th7bo.sidequest.ui.core.search.SearchIndex
import dev.th7bo.sidequest.ui.core.search.SearchResult
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.core.virtualization.RowProvider
import dev.th7bo.sidequest.ui.core.virtualization.ScrollAlignment
import dev.th7bo.sidequest.ui.core.virtualization.VirtualListNode
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.mutableStateOf

/**
 * One entry in the scrolling settings list.
 *
 * Each row also carries which slice of its section's card it draws, so a card can span
 * several rows and still be virtualized.
 */
public sealed interface ConfigRow {

    /** Stable identity, so a row keeps its node across filtering and scrolling. */
    public val id: UiId

    /** Which part of the surrounding card this row paints. */
    public val segment: CardSegment

    /** Identity as the virtualizer sees it. */
    public val key: Any get() = id

    /** A section title, at the top of its card. */
    public class Header(
        public val section: Section,
        override val segment: CardSegment,
    ) : ConfigRow {
        override val id: UiId get() = section.id
    }

    /** A setting, with its control. */
    public class Entry(
        public val setting: Setting<*>,
        override val segment: CardSegment,
        /** Divider along the top edge, separating it from the row above. */
        public val showDivider: Boolean,
    ) : ConfigRow {
        override val id: UiId get() = setting.id
    }
}

/**
 * Turns a [ConfigScreen] into the flat, filtered row list the virtualized list consumes.
 *
 * Filtering happens here rather than in the list, so the list stays a general-purpose
 * virtualizer and the screen owns what "which rows are showing" means.
 */
public class ConfigRowProvider(
    private val screen: ConfigScreen,
    private val registry: ComponentRegistry,
    private val context: ComponentContext,
) : RowProvider {

    private var rows: List<ConfigRow> = emptyList()

    /** Currently displayed rows. Recomputed only when the category or filter changes. */
    public val currentRows: List<ConfigRow> get() = rows

    override val rowCount: Int get() = rows.size

    override fun keyAt(index: Int): Any = rows[index].key

    override fun estimatedHeight(index: Int): Float {
        val row = rows[index]
        val base = when (row) {
            is ConfigRow.Header -> ESTIMATED_HEADER_HEIGHT
            is ConfigRow.Entry -> ESTIMATED_ROW_HEIGHT
        }
        // The last slice carries the gap to the next card.
        return if (row.segment == CardSegment.BOTTOM) base + ESTIMATED_CARD_GAP else base
    }

    override fun createRow(index: Int): UiNode {
        val row = rows[index]
        val content = when (row) {
            is ConfigRow.Header -> SectionCardHeaderNode(
                section = row.section,
                componentContext = context,
                isCollapsed = if (row.section.isCollapsible) ({ isCollapsed(row.section) }) else null,
                onToggle = { toggleCollapsed(row.section) },
            )
            is ConfigRow.Entry -> registry.createNode(row.setting, context)
        }
        return CardSliceNode(
            id = row.id.child("card_slice"),
            segment = row.segment,
            showDivider = row is ConfigRow.Entry && row.showDivider,
            componentContext = context,
            content = content,
            // Only a *row* that ends a card. A collapsed section is a header alone, and a header already
            // pads itself to the full amount.
            squaresBottom = row is ConfigRow.Entry && row.segment.roundsBottom,
        )
    }

    /**
     * Which sections are folded away.
     *
     * Held here rather than on the [Section], because a section is a *description* of the screen and is
     * shared by every window that shows it — folding one in a screen must not fold it somewhere else. Seeded
     * from `startsCollapsed` the first time each section is seen.
     */
    private val collapsed = HashMap<UiId, Boolean>()

    private var isSearching = false

    private fun isCollapsed(section: Section): Boolean =
        collapsed.getOrPut(section.id) { section.startsCollapsed }

    /**
     * Called when a fold changes, so the screen can re-run its own rebuild.
     *
     * A callback rather than the provider rebuilding itself: it is a [RowProvider], not a node, so it cannot
     * invalidate the list it feeds — and reaching for the list from here would invert the ownership that
     * makes the provider testable without one.
     */
    public var onFoldChanged: (() -> Unit)? = null

    /** Folds a section by id. For tests, which have no pointer to click the header with. */
    public fun foldForTesting(sectionId: UiId) {
        collapsed[sectionId] = !(collapsed[sectionId] ?: false)
        onFoldChanged?.invoke()
    }

    private fun toggleCollapsed(section: Section) {
        collapsed[section.id] = !isCollapsed(section)
        onFoldChanged?.invoke()
    }

    private var lastCategory: Category? = null
    private var lastVisibleIds: Set<UiId>? = null

    /** Rebuilds the row list for [category], keeping only settings in [visibleIds] if given. */
    public fun rebuild(category: Category?, visibleIds: Set<UiId>? = null) {
        lastCategory = category
        lastVisibleIds = visibleIds
        isSearching = visibleIds != null
        val target = category ?: run { rows = emptyList(); return }

        val built = ArrayList<ConfigRow>()
        for (section in target.sections) {
            if (!section.visibleWhen.peek()) continue
            val settings = section.settings.filter { setting ->
                setting.isVisible.peek() && (visibleIds == null || setting.id in visibleIds)
            }
            // A section with nothing left after filtering contributes no header either.
            if (settings.isEmpty()) continue

            // Searching opens everything. A result hidden inside a folded section is a search that appears
            // to have found nothing, which is worse than no search at all.
            if (section.isCollapsible && isCollapsed(section) && !isSearching) {
                built.add(ConfigRow.Header(section, CardSegment.SINGLE))
                continue
            }

            built.add(ConfigRow.Header(section, CardSegment.TOP))
            settings.forEachIndexed { index, setting ->
                built.add(
                    ConfigRow.Entry(
                        setting = setting,
                        segment = if (index == settings.lastIndex) CardSegment.BOTTOM else CardSegment.MIDDLE,
                        // Every row is separated from what precedes it, including the
                        // header, exactly as the reference design does.
                        showDivider = true,
                    ),
                )
            }
        }
        rows = built
    }

    /** Index of the row for [settingId], or -1. */
    public fun indexOf(settingId: UiId): Int =
        rows.indexOfFirst { it is ConfigRow.Entry && it.setting.id == settingId }

    private companion object {
        const val ESTIMATED_HEADER_HEIGHT = 40f
        const val ESTIMATED_ROW_HEIGHT = 40f
        const val ESTIMATED_CARD_GAP = 12f
    }
}

/**
 * A complete configuration screen: category selection, a virtualized settings list, and
 * search that navigates into it.
 *
 * The list is the only thing that scrolls, and it materializes only what is visible —
 * so a screen with a thousand settings costs the same as one with thirty.
 */
public class ConfigScreenController(
    public val screen: ConfigScreen,
    private val registry: ComponentRegistry,
    private val context: ComponentContext,
    private val focus: FocusManager,
) {

    public val searchIndex: SearchIndex = SearchIndex().apply { addAll(screen) }

    private val provider = ConfigRowProvider(screen, registry, context)
        .also { it.onFoldChanged = { rebuild() } }

    /** The scrolling list of rows. Attach this to a runtime, or nest it in a layout. */
    public val list: VirtualListNode = VirtualListNode(screen.id.child("list"), provider)

    private val activeCategoryState: MutableUiState<UiId?> =
        mutableStateOf(screen.categories.firstOrNull()?.id, "activeCategory")

    private val queryState: MutableUiState<String> = mutableStateOf("", "searchQuery")

    private val resultsState: MutableUiState<List<SearchResult>> =
        mutableStateOf(emptyList(), "searchResults")

    /**
     * Bumped every time the row list is rebuilt.
     *
     * Observers care about "the rows changed", which happens *after* the query and the
     * results have both settled. Watching those two directly means seeing the previous
     * row list, because `rebuild()` has not run yet.
     */
    private val rowsVersionState: MutableUiState<Int> = mutableStateOf(0, "rowsVersion")

    public val activeCategory: UiState<UiId?> get() = activeCategoryState
    public val searchQuery: UiState<String> get() = queryState
    public val searchResults: UiState<List<SearchResult>> get() = resultsState

    /** Changes whenever [rows] has been rebuilt. Observe this, not the query. */
    public val rowsChanged: UiState<Int> get() = rowsVersionState

    /** True while a search filter is narrowing the list. */
    public val isFiltering: Boolean get() = queryState.peek().isNotBlank()

    init {
        rebuild()
    }

    /** Switches the visible category and returns to the top. */
    public fun selectCategory(categoryId: UiId): Boolean {
        if (screen.category(categoryId) == null) return false
        if (activeCategoryState.peek() == categoryId) return true
        activeCategoryState.value = categoryId
        rebuild()
        list.scrollTo(0f)
        return true
    }

    /**
     * Applies a search query.
     *
     * A query that matches settings in another category switches to it, because a result
     * the user cannot see is not a result.
     */
    public fun search(query: String) {
        queryState.value = query
        val results = if (query.isBlank()) emptyList() else searchIndex.search(query)
        resultsState.value = results

        if (results.isEmpty()) {
            rebuild()
            return
        }

        // Show the category the best result lives in, then filter to the matches.
        results.first().setting.location?.category?.let { category ->
            if (activeCategoryState.peek() != category) activeCategoryState.value = category
        }
        rebuild()
    }

    /** Clears the filter, keeping the current category. */
    public fun clearSearch() {
        queryState.value = ""
        resultsState.value = emptyList()
        rebuild()
    }

    /**
     * Scrolls to [settingId], switching category and expanding the filter if needed.
     *
     * This is the "navigate directly to a setting" path: it works whether the row is
     * currently materialized, filtered out, or in a different category.
     */
    public fun navigateTo(settingId: UiId, alignment: ScrollAlignment = ScrollAlignment.CENTER): Boolean {
        val setting = screen[settingId] ?: return false
        val location = setting.location ?: return false

        if (activeCategoryState.peek() != location.category) {
            activeCategoryState.value = location.category
            rebuild()
        }

        var index = provider.indexOf(settingId)
        if (index < 0 && isFiltering) {
            // The target is hidden by the current filter; drop it rather than fail.
            clearSearch()
            index = provider.indexOf(settingId)
        }
        if (index < 0) return false

        list.scrollToRow(index, alignment)
        return true
    }

    /** Navigates to a search result and focuses its control. */
    public fun openResult(result: SearchResult): Boolean {
        if (!navigateTo(result.setting.id)) return false
        return true
    }

    /**
     * Moves focus to the control for [settingId], if its row is materialized.
     *
     * Returns false when the row is not currently built — the caller should
     * [navigateTo] and lay out a frame first.
     */
    public fun focusSetting(settingId: UiId): Boolean {
        val index = provider.indexOf(settingId)
        if (index < 0) return false
        val row = list.nodeForRow(index) ?: return false
        val focusable = firstFocusable(row) ?: return false
        return focus.requestFocus(focusable)
    }

    private fun firstFocusable(node: UiNode): UiNode? {
        if (node.focusable && node.isVisible) return node
        for (child in node.children) firstFocusable(child)?.let { return it }
        return null
    }

    /** Rows currently in the list. */
    public val rows: List<ConfigRow> get() = provider.currentRows

    private fun rebuild() {
        val category = activeCategoryState.peek()?.let(screen::category)

        // Whether to filter depends on the *query*, not on whether it matched. A query
        // that matches nothing must show nothing — falling back to the unfiltered list
        // would silently contradict what the user typed.
        val visibleIds = if (queryState.peek().isBlank()) {
            null
        } else {
            resultsState.peek().mapTo(HashSet()) { it.id }
        }

        provider.rebuild(category, visibleIds)
        list.invalidateRows()
        rowsVersionState.value = rowsVersionState.peek() + 1
    }

    /** Re-reads visibility rules. Call when a `visibleWhen` dependency changes. */
    public fun refreshVisibility() {
        rebuild()
    }
}

/**
 * Registers the built-in renderer for every phase-one setting type.
 *
 * Returns nothing special: everything is owned by [scope], so disposing it removes the
 * whole standard library and lets a consumer substitute their own.
 */
public fun ComponentRegistry.registerStandardControls(
    scope: dev.th7bo.sidequest.ui.extension.RegistrationScope,
) {
    register(scope, ToggleSetting::class) { setting, context ->
        SettingRowNode(setting, context, ToggleControlNode(setting, context))
    }
    register(scope, ButtonSetting::class) { setting, context ->
        SettingRowNode(setting, context, ButtonControlNode(setting, context))
    }
    register(scope, IntSliderSetting::class) { setting, context ->
        SettingRowNode(setting, context, IntSliderControlNode(setting, context))
    }
    register(scope, FloatSliderSetting::class) { setting, context ->
        SettingRowNode(setting, context, FloatSliderControlNode(setting, context))
    }
    register(scope, TextSetting::class) { setting, context ->
        SettingRowNode(setting, context, TextFieldControlNode(setting, context))
    }
    register(scope, KeybindSetting::class) { setting, context ->
        SettingRowNode(setting, context, KeybindControlNode(setting, context))
    }
    register(scope, DropdownSetting::class) { setting, context ->
        SettingRowNode(setting, context, DropdownControlNode(setting, context))
    }
    register(scope, ColorSetting::class) { setting, context ->
        SettingRowNode(setting, context, ColorControlNode(setting, context))
    }
    register(scope, TextAreaSetting::class) { setting, context ->
        SettingRowNode(setting, context, TextAreaControlNode(setting, context))
    }
    register(scope, ListSetting::class) { setting, context ->
        SettingRowNode(setting, context, ListControlNode(setting, context))
    }
    register(scope, NoticeSetting::class) { setting, context ->
        NoticeRowNode(setting, context)
    }
}
