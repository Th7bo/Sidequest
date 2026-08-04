package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.binding.asBinding
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.ToggleSetting
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.config.option
import dev.th7bo.sidequest.ui.core.virtualization.ScrollAlignment
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.Modifiers
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The four Phase 2 acceptance criteria, demonstrated end to end through a real screen
 * rather than at the layer each feature happens to live in.
 */
class Phase2AcceptanceTest {

    /** Generous upper bound on materialized rows: the point is that it does not grow. */
    private val BOUNDED_ROW_LIMIT = 40

    private fun id(path: String) = UiId.of("sidequest", path)

    private lateinit var harness: ConfigScreenHarness
    private lateinit var toggleStates: List<MutableUiState<Boolean>>

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
    }

    @AfterEach
    fun tearDown() {
        if (::harness.isInitialized) harness.dispose()
        resetReactiveGraphForTesting()
    }

    /** A screen with [toggleCount] toggles plus a handful of other control types. */
    private fun buildScreen(toggleCount: Int): ConfigScreen {
        val states = ArrayList<MutableUiState<Boolean>>(toggleCount)
        val screen = configScreen(id("main"), "Sidequest") {
            category(id("general"), "General") {
                section("Interface") {
                    slider(id("general.duration"), "Notification Duration", mutableStateOf(5).asBinding(), 1..60)
                    dropdown(
                        id("general.theme"),
                        "Theme",
                        mutableStateOf("dark").asBinding(),
                        listOf(option("dark", "Midnight", "dark"), option("light", "Daylight", "light")),
                    )
                    textField(id("general.name"), "Display Name", mutableStateOf("player").asBinding())
                }
                section("Bulk") {
                    repeat(toggleCount) { index ->
                        val state = mutableStateOf(false)
                        states.add(state)
                        toggle(id("general.flag_$index"), "Feature Flag $index", state.asBinding())
                    }
                }
            }
            category(id("chat"), "Chat") {
                section("Filters") {
                    toggle(id("chat.spam_filter"), "Spam Filter", mutableStateOf(true).asBinding())
                    toggle(id("chat.hide_joins"), "Hide Join Messages", mutableStateOf(false).asBinding())
                }
            }
        }
        toggleStates = states
        return screen
    }

    // ---------------------------------------------------------------
    // Criterion 1: a 1,000-toggle screen remains responsive
    // ---------------------------------------------------------------

    @Test
    fun `a thousand-toggle screen materializes only what is on screen`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 1000))
        harness.frame()

        assertEquals(1005, harness.controller.screen.settingCount)
        // Two section headers plus 1003 settings in the General category.
        assertTrue(harness.controller.rows.size > 1000)

        assertTrue(
            harness.list.materializedRowCount < 30,
            "only the visible window may be built, but ${harness.list.materializedRowCount} rows were",
        )
    }

    @Test
    fun `layout cost does not scale with the number of settings`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 30))
        val smallMetrics = harness.frame()
        val smallMaterialized = harness.list.materializedRowCount
        harness.dispose()
        resetReactiveGraphForTesting()

        harness = ConfigScreenHarness(buildScreen(toggleCount = 1000))
        val largeMetrics = harness.frame()

        assertEquals(smallMaterialized, harness.list.materializedRowCount)
        assertEquals(
            smallMetrics.nodesMeasured,
            largeMetrics.nodesMeasured,
            "measuring a 1000-setting screen must cost what a 30-setting one costs",
        )
    }

    @Test
    fun `an idle thousand-toggle screen does no layout work`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 1000))
        harness.frames(3)

        val idle = harness.frame()

        assertEquals(0, idle.nodesMeasured)
        assertEquals(0, idle.nodesArranged)
        assertTrue(harness.runtime.isIdle)
    }

    @Test
    fun `scrolling a thousand-toggle screen keeps the tree bounded`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 1000))
        harness.frame()

        // Scroll through a large part of the list, tracking the worst case rather than
        // comparing against the count at the top — at the top there is overscan on one
        // side only, so mid-list is legitimately larger.
        var worst = harness.list.materializedRowCount
        repeat(40) {
            harness.list.scrollBy(200f)
            harness.frame()
            worst = maxOf(worst, harness.list.materializedRowCount)
        }

        assertTrue(
            worst < BOUNDED_ROW_LIMIT,
            "the materialized window must stay bounded while scrolling, peaked at $worst",
        )
        assertTrue(
            worst < harness.controller.rows.size / 10,
            "and must be a small fraction of the ${harness.controller.rows.size} registered rows",
        )
        assertTrue(harness.list.scrollOffset > 0f)
    }

    @Test
    fun `toggling one setting re-measures a bounded number of nodes`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 1000))
        harness.frames(3)

        toggleStates[0].value = true
        val metrics = harness.frame()

        assertTrue(
            metrics.nodesMeasured < 20,
            "one change must not re-measure the screen, but measured ${metrics.nodesMeasured}",
        )
    }

    // ---------------------------------------------------------------
    // Criterion 2: search navigation works with virtualization
    // ---------------------------------------------------------------

    @Test
    fun `searching filters the list down to the matches`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 200))
        harness.frame()
        val unfiltered = harness.controller.rows.size

        harness.controller.search("Feature Flag 42")
        harness.frame()

        assertTrue(harness.controller.rows.size < unfiltered)
        assertTrue(harness.controller.searchResults.value.isNotEmpty())
        assertEquals(
            id("general.flag_42"),
            harness.controller.searchResults.value.first().id,
        )
    }

    @Test
    fun `navigating to a result deep in the list scrolls it into view and builds it`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 1000))
        harness.frame()

        val target = id("general.flag_800")
        assertEquals(-1, materializedIndexOf(target), "the target starts unbuilt")

        assertTrue(harness.controller.navigateTo(target, ScrollAlignment.CENTER))
        harness.frame()

        val index = harness.controller.rows.indexOfFirst {
            it is ConfigRow.Entry && it.setting.id == target
        }
        assertTrue(index >= 0)
        assertTrue(harness.list.isRowVisible(index), "the target row must be on screen")
        assertNotNull(harness.list.nodeForRow(index), "and must now be materialized")
    }

    @Test
    fun `navigating across categories switches category first`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 50))
        harness.frame()
        assertEquals(id("general"), harness.controller.activeCategory.value)

        assertTrue(harness.controller.navigateTo(id("chat.spam_filter")))
        harness.frame()

        assertEquals(id("chat"), harness.controller.activeCategory.value)
        assertTrue(
            harness.drawnText().any { it.contains("Spam Filter") },
            "the target must actually be drawn after navigating",
        )
    }

    @Test
    fun `searching a setting in another category switches to it`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 50))
        harness.frame()

        harness.controller.search("spam filter")
        harness.frame()

        assertEquals(id("chat"), harness.controller.activeCategory.value)
        assertTrue(harness.drawnText().any { it.contains("Spam Filter") })
    }

    @Test
    fun `navigating to a setting hidden by the current filter drops the filter`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 100))
        harness.frame()

        harness.controller.search("Feature Flag 1")
        harness.frame()
        assertTrue(harness.controller.isFiltering)

        // The duration slider does not match the filter at all.
        assertTrue(harness.controller.navigateTo(id("general.duration")))
        harness.frame()

        assertFalse(harness.controller.isFiltering, "the filter must give way to the target")
        assertTrue(harness.drawnText().any { it.contains("Notification Duration") })
    }

    @Test
    fun `clearing the search restores the full list`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 100))
        harness.frame()
        val unfiltered = harness.controller.rows.size

        harness.controller.search("Feature Flag 7")
        harness.frame()
        harness.controller.clearSearch()
        harness.frame()

        assertEquals(unfiltered, harness.controller.rows.size)
    }

    @Test
    fun `a search result can be focused once its row is materialized`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 500))
        harness.frame()

        val target = id("general.flag_300")
        harness.controller.navigateTo(target, ScrollAlignment.CENTER)
        harness.frame()

        assertTrue(harness.controller.focusSetting(target), "the control must be focusable")
        assertNotNull(harness.runtime.focus.focused)
    }

    @Test
    fun `filtering does not rebuild rows that survive the filter`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 100))
        harness.frame()

        // "Feature Flag 1" also matches 10-19 and 100, so row 1 stays in the list.
        harness.controller.search("Feature Flag 1")
        harness.frame()

        val firstIndex = harness.controller.rows.indexOfFirst { it is ConfigRow.Entry }
        val node = harness.list.nodeForRow(firstIndex)
        assertNotNull(node)

        harness.frame()
        assertSame(node, harness.list.nodeForRow(firstIndex), "an unchanged row keeps its node")
    }

    private fun materializedIndexOf(settingId: UiId): Int {
        val index = harness.controller.rows.indexOfFirst {
            it is ConfigRow.Entry && it.setting.id == settingId
        }
        return if (index >= 0 && harness.list.nodeForRow(index) != null) index else -1
    }

    // ---------------------------------------------------------------
    // Criterion 3: keyboard navigation works
    // ---------------------------------------------------------------

    @Test
    fun `tab moves focus between controls in visual order`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 5))
        harness.frame()

        val order = harness.runtime.focus.focusOrder()
        assertTrue(order.size >= 3, "several controls should be focusable")

        harness.runtime.input.keyPressed(Key.TAB)
        val first = harness.runtime.focus.focused
        assertNotNull(first)

        harness.runtime.input.keyPressed(Key.TAB)
        assertNotSame(first, harness.runtime.focus.focused)
    }

    @Test
    fun `shift tab walks backwards`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 5))
        harness.frame()

        harness.runtime.input.keyPressed(Key.TAB)
        harness.runtime.input.keyPressed(Key.TAB)
        val second = harness.runtime.focus.focused

        harness.runtime.input.keyPressed(Key.TAB, Modifiers.Shift)
        assertNotSame(second, harness.runtime.focus.focused)
    }

    @Test
    fun `space activates a focused toggle without any mouse involvement`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 5))
        harness.frame()

        val toggleNode = harness.runtime.focus.focusOrder()
            .filterIsInstance<ToggleControlNode>()
            .first()
        harness.runtime.focus.requestFocus(toggleNode)

        val setting = harness.controller.screen[id("general.flag_0")] as ToggleSetting
        val before = setting.value

        harness.runtime.input.keyPressed(Key.SPACE)

        assertEquals(!before, setting.value, "Space must toggle the focused control")
    }

    @Test
    fun `enter activates a focused control too`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 5))
        harness.frame()

        val toggleNode = harness.runtime.focus.focusOrder()
            .filterIsInstance<ToggleControlNode>()
            .first()
        harness.runtime.focus.requestFocus(toggleNode)
        val setting = harness.controller.screen[id("general.flag_0")] as ToggleSetting

        harness.runtime.input.keyPressed(Key.ENTER)

        assertTrue(setting.value)
    }

    @Test
    fun `arrow keys adjust a focused slider`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 5))
        harness.frame()

        val slider = harness.runtime.focus.focusOrder()
            .filterIsInstance<IntSliderControlNode>()
            .first()
        harness.runtime.focus.requestFocus(slider)

        val setting = harness.controller.screen.typed<Int>(id("general.duration"))!!
        val before = setting.value

        harness.runtime.input.keyPressed(Key.ARROW_RIGHT)
        assertEquals(before + 1, setting.value)

        harness.runtime.input.keyPressed(Key.ARROW_LEFT)
        assertEquals(before, setting.value)
    }

    @Test
    fun `home and end jump a slider to its bounds`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 5))
        harness.frame()

        val slider = harness.runtime.focus.focusOrder()
            .filterIsInstance<IntSliderControlNode>()
            .first()
        harness.runtime.focus.requestFocus(slider)
        val setting = harness.controller.screen.typed<Int>(id("general.duration"))!!

        harness.runtime.input.keyPressed(Key.END)
        assertEquals(60, setting.value)

        harness.runtime.input.keyPressed(Key.HOME)
        assertEquals(1, setting.value)
    }

    @Test
    fun `arrow keys cycle a focused dropdown without opening it`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 5))
        harness.frame()

        val dropdown = harness.runtime.focus.focusOrder()
            .filterIsInstance<DropdownControlNode<*>>()
            .first()
        harness.runtime.focus.requestFocus(dropdown)
        val setting = harness.controller.screen.typed<String>(id("general.theme"))!!

        harness.runtime.input.keyPressed(Key.ARROW_DOWN)

        assertEquals("light", setting.value)
        assertFalse(dropdown.isOpen, "cycling must not require opening the list")
    }

    @Test
    fun `typing edits a focused text field and validation still runs`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 5))
        harness.frame()

        val field = harness.runtime.focus.focusOrder()
            .filterIsInstance<TextFieldControlNode>()
            .first()
        harness.runtime.focus.requestFocus(field)
        val setting = harness.controller.screen.typed<String>(id("general.name"))!!

        harness.runtime.input.charTyped('!'.code)

        assertTrue(setting.value.contains('!'))

        harness.runtime.input.keyPressed(Key.BACKSPACE)
        assertFalse(setting.value.contains('!'))
    }

    @Test
    fun `a text field navigates and deletes by word`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 5))
        harness.frame()

        val field = harness.runtime.focus.focusOrder()
            .filterIsInstance<TextFieldControlNode>()
            .first()
        harness.runtime.focus.requestFocus(field)
        val setting = harness.controller.screen.typed<String>(id("general.name"))!!
        setting.set("two words")
        harness.frame()

        harness.runtime.input.keyPressed(Key.END)
        assertEquals(setting.value.length, field.caret)

        // Ctrl+Backspace takes the whole word, the way it does outside the game.
        harness.runtime.input.keyPressed(Key.BACKSPACE, dev.th7bo.sidequest.ui.input.Modifiers.Control)
        assertEquals("two ", setting.value)

        harness.runtime.input.keyPressed(Key.HOME)
        assertEquals(0, field.caret)

        harness.runtime.input.keyPressed(Key.DELETE)
        assertEquals("wo ", setting.value, "Delete removes forwards without moving the caret")
        assertEquals(0, field.caret)
    }

    @Test
    fun `escape reaches the host as soon as there is nothing left to dismiss`() {
        // Reported from the game as having to press Escape several times to close the
        // screen. The host closes when the framework reports the press unhandled, so this
        // counts presses: each one that is claimed has to have a visible effect.
        harness = ConfigScreenHarness(buildScreen(toggleCount = 5))
        harness.frame()

        val field = harness.runtime.focus.focusOrder()
            .filterIsInstance<TextFieldControlNode>()
            .first()
        harness.runtime.focus.requestFocus(field)
        harness.runtime.input.keyPressed(Key.ENTER)
        assertTrue(field.isEditing)

        assertTrue(
            harness.runtime.input.keyPressed(Key.ESCAPE),
            "the first press stops editing, which is visible, so it is claimed",
        )
        assertFalse(field.isEditing)

        assertFalse(
            harness.runtime.input.keyPressed(Key.ESCAPE),
            "the second has nothing left to dismiss and belongs to the screen",
        )
    }

    @Test
    fun `escape closes immediately when a control is merely focused`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 5))
        harness.frame()

        harness.runtime.input.keyPressed(Key.TAB)
        assertNotNull(harness.runtime.focus.focused)

        assertFalse(
            harness.runtime.input.keyPressed(Key.ESCAPE),
            "a focus ring is not something the player asked to dismiss",
        )
    }

    @Test
    fun `escape clears focus`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 5))
        harness.frame()

        harness.runtime.input.keyPressed(Key.TAB)
        assertNotNull(harness.runtime.focus.focused)

        harness.runtime.input.keyPressed(Key.ESCAPE)
        assertEquals(null, harness.runtime.focus.focused)
    }

    @Test
    fun `a disabled control refuses keyboard activation`() {
        val enabled = mutableStateOf(false)
        val flag = mutableStateOf(false)
        val screen = configScreen(id("main"), "Sidequest") {
            category(id("general"), "General") {
                section("Interface") {
                    toggle(id("general.locked"), "Locked", flag.asBinding()) {
                        enabledWhen = enabled
                    }
                }
            }
        }
        harness = ConfigScreenHarness(screen)
        harness.frame()

        val control = harness.runtime.focus.focusOrder().filterIsInstance<ToggleControlNode>().first()
        harness.runtime.focus.requestFocus(control)

        harness.runtime.input.keyPressed(Key.SPACE)
        assertFalse(flag.value, "a disabled control must not respond")

        enabled.value = true
        harness.runtime.input.keyPressed(Key.SPACE)
        assertTrue(flag.value)
    }

    // ---------------------------------------------------------------
    // Cross-cutting: the screen actually renders
    // ---------------------------------------------------------------

    @Test
    fun `the screen draws its section headers, labels and controls`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 5))
        harness.frame()

        val text = harness.drawnText()
        assertTrue(text.any { it.contains("Interface") }, "section header")
        assertTrue(text.any { it.contains("Notification Duration") }, "setting label")
        assertTrue(harness.recorder.commands.isNotEmpty())
    }

    @Test
    fun `clicking a toggle row flips the setting`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 5))
        harness.frame()

        val control = harness.runtime.focus.focusOrder().filterIsInstance<ToggleControlNode>().first()
        val bounds = control.absoluteBounds()
        val setting = harness.controller.screen[id("general.flag_0")] as ToggleSetting
        val before = setting.value

        harness.runtime.input.pointerPressed(bounds.center)

        assertEquals(!before, setting.value)
    }

    @Test
    fun `a toggle's knob animates rather than snapping`() {
        harness = ConfigScreenHarness(buildScreen(toggleCount = 5))
        harness.frame()

        val control = harness.runtime.focus.focusOrder().filterIsInstance<ToggleControlNode>().first()
        assertEquals(0f, control.knobPosition)

        val setting = harness.controller.screen[id("general.flag_0")] as ToggleSetting
        setting.toggle()
        harness.frame()

        assertTrue(control.knobPosition > 0f && control.knobPosition < 1f) {
            "the knob should be mid-travel, was ${control.knobPosition}"
        }

        harness.frames(20)
        assertEquals(1f, control.knobPosition)
    }
}
