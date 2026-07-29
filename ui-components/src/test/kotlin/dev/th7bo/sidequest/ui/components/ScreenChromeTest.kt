package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.binding.asBinding
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The screen chrome: category sidebar and search box.
 *
 * Without these, only the first category is reachable, so these are what make the
 * assembled screen usable rather than merely correct.
 */
class ScreenChromeTest {

    private fun id(path: String) = UiId.of("sidequest", path)

    private lateinit var harness: ConfigScreenHarness
    private lateinit var layout: ConfigScreenLayoutNode

    private fun buildScreen(): ConfigScreen = configScreen(id("main"), "Sidequest") {
        category(id("general"), "General") {
            section("Interface") {
                toggle(id("general.notifications"), "Notifications", mutableStateOf(true).asBinding())
                slider(id("general.duration"), "Duration", mutableStateOf(5).asBinding(), 1..60)
            }
        }
        category(id("chat"), "Chat") {
            section("Filters") {
                toggle(id("chat.spam_filter"), "Spam Filter", mutableStateOf(true).asBinding())
            }
        }
        category(id("hud"), "HUD") {
            section("Appearance") {
                toggle(id("hud.enabled"), "Enable HUD", mutableStateOf(true).asBinding())
            }
        }
    }

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        harness = ConfigScreenHarness(buildScreen(), Size(500f, 320f))
        layout = ConfigScreenLayoutNode(id("layout"), harness.controller, harness.context)
        harness.runtime.root = layout
        harness.frame()
    }

    @AfterEach
    fun tearDown() {
        harness.dispose()
        resetReactiveGraphForTesting()
    }

    private fun categoryButtons(): List<CategoryButtonNode> {
        val found = ArrayList<CategoryButtonNode>()
        fun walk(node: UiNode) {
            if (node is CategoryButtonNode) found.add(node)
            node.children.forEach(::walk)
        }
        walk(layout)
        return found
    }

    @Test
    fun `the sidebar lists every category`() {
        assertEquals(
            listOf("General", "Chat", "HUD"),
            categoryButtons().map { it.category.title.peek() },
        )
    }

    @Test
    fun `clicking a category switches the list to it`() {
        assertEquals(id("general"), harness.controller.activeCategory.value)

        val chatButton = categoryButtons().first { it.category.id == id("chat") }
        harness.runtime.input.pointerPressed(chatButton.absoluteBounds().center)
        harness.frame()

        assertEquals(id("chat"), harness.controller.activeCategory.value)
        assertTrue(harness.drawnText().any { it.contains("Spam Filter") })
        assertFalse(harness.drawnText().any { it.contains("Notifications") })
    }

    @Test
    fun `a category can be activated from the keyboard`() {
        val hudButton = categoryButtons().first { it.category.id == id("hud") }
        harness.runtime.focus.requestFocus(hudButton)

        harness.runtime.input.keyPressed(Key.ENTER)
        harness.frame()

        assertEquals(id("hud"), harness.controller.activeCategory.value)
    }

    @Test
    fun `switching category returns the list to the top`() {
        harness.controller.list.scrollTo(40f)
        harness.frame()

        val chatButton = categoryButtons().first { it.category.id == id("chat") }
        harness.runtime.input.pointerPressed(chatButton.absoluteBounds().center)
        harness.frame()

        assertEquals(0f, harness.controller.list.scrollOffset)
    }

    @Test
    fun `typing in the search box filters the list`() {
        harness.runtime.focus.requestFocus(layout.searchBox)

        "spam".forEach { harness.runtime.input.charTyped(it.code) }
        harness.frame()

        assertEquals("spam", layout.searchBox.query.value)
        assertEquals(id("chat"), harness.controller.activeCategory.value)
        assertTrue(harness.drawnText().any { it.contains("Spam Filter") })
    }

    @Test
    fun `backspace narrows the query back out`() {
        harness.runtime.focus.requestFocus(layout.searchBox)
        "spam".forEach { harness.runtime.input.charTyped(it.code) }
        harness.frame()

        repeat(2) { harness.runtime.input.keyPressed(Key.BACKSPACE) }
        harness.frame()

        assertEquals("sp", layout.searchBox.query.value)
    }

    @Test
    fun `escape clears the query rather than only dropping focus`() {
        harness.runtime.focus.requestFocus(layout.searchBox)
        "spam".forEach { harness.runtime.input.charTyped(it.code) }
        harness.frame()

        harness.runtime.input.keyPressed(Key.ESCAPE)
        harness.frame()

        assertEquals("", layout.searchBox.query.value)
        assertFalse(harness.controller.isFiltering)
    }

    @Test
    fun `a query matching nothing shows an empty state instead of a blank list`() {
        harness.runtime.focus.requestFocus(layout.searchBox)
        "zzzznotathing".forEach { harness.runtime.input.charTyped(it.code) }
        harness.frame()

        assertTrue(harness.controller.rows.isEmpty())
        assertFalse(harness.controller.list.isVisible, "the empty list must be hidden")
        assertTrue(
            harness.drawnText().any { it.contains("No settings match") },
            "the user must be told why the screen is empty",
        )
    }

    @Test
    fun `clearing a fruitless query brings the list back`() {
        harness.runtime.focus.requestFocus(layout.searchBox)
        "zzzz".forEach { harness.runtime.input.charTyped(it.code) }
        harness.frame()
        assertFalse(harness.controller.list.isVisible)

        harness.runtime.input.keyPressed(Key.ESCAPE)
        harness.frame()

        assertTrue(harness.controller.list.isVisible)
        assertTrue(harness.drawnText().any { it.contains("Notifications") })
    }

    @Test
    fun `the placeholder shows only while the query is empty`() {
        assertTrue(harness.drawnText().any { it.contains("Search settings") })

        harness.runtime.focus.requestFocus(layout.searchBox)
        harness.runtime.input.charTyped('a'.code)
        harness.frame()

        assertFalse(harness.drawnText().any { it.contains("Search settings") })
    }

    @Test
    fun `the sidebar and the search box are both keyboard reachable`() {
        val order = harness.runtime.focus.focusOrder()

        assertTrue(order.contains(layout.searchBox), "the search box must be in the tab order")
        assertTrue(
            order.any { it is CategoryButtonNode },
            "categories must be reachable without a mouse",
        )
    }

    @Test
    fun `the layout fills the viewport and draws its chrome`() {
        assertEquals(500f, layout.measuredSize.width)
        assertEquals(320f, layout.measuredSize.height)
        assertNotNull(harness.renderer.commands.firstOrNull())
        assertTrue(harness.drawnText().any { it == "General" }, "the sidebar must be drawn")
    }

    @Test
    fun `an idle assembled screen still does no layout work`() {
        harness.frames(2)
        val idle = harness.frame()

        assertEquals(0, idle.nodesMeasured)
        assertEquals(0, idle.nodesArranged)
    }
}
