package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.binding.asBinding
import dev.th7bo.sidequest.ui.config.ListSetting
import dev.th7bo.sidequest.ui.config.Option
import dev.th7bo.sidequest.ui.config.SettingMetadata
import dev.th7bo.sidequest.ui.config.SettingSerializers
import dev.th7bo.sidequest.ui.config.DropdownSetting
import dev.th7bo.sidequest.ui.config.TextAreaSetting
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.component.ComponentRegistry
import dev.th7bo.sidequest.ui.core.component.MissingComponentNode
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.testkit.FakeTextMeasurer
import dev.th7bo.sidequest.ui.testkit.RecordingRenderer
import dev.th7bo.sidequest.ui.theme.DarkTheme
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Phase 7: the standard controls the earlier phases left unimplemented.
 *
 * `TextAreaSetting` and `ListSetting` were modelled in phase 2 but had no registered
 * control, so both degraded to a placeholder. The first test here is the one that would
 * have caught that.
 */
class Phase7AcceptanceTest {

    private fun id(path: String) = UiId.of("sidequest", path)

    private lateinit var runtime: UiRuntime
    private lateinit var renderer: RecordingRenderer
    private lateinit var context: ComponentContext
    private lateinit var scope: RegistrationScope
    private lateinit var registry: ComponentRegistry

    private val screen = Size(400f, 300f)

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        renderer = RecordingRenderer(screen, FakeTextMeasurer())
        runtime = UiRuntime(DarkTheme).apply {
            viewport = screen
            prepare(renderer)
        }
        context = ComponentContext(DarkTheme, runtime.animations, runtime)
        scope = RegistrationScope(id("phase7"))
        registry = ComponentRegistry().apply { registerStandardControls(scope) }
    }

    @AfterEach
    fun tearDown() {
        scope.dispose()
        runtime.dispose()
        resetReactiveGraphForTesting()
    }

    private fun frame(delta: Float = 1f / 60f) = run {
        renderer.beginFrame(delta)
        val metrics = runtime.frame(renderer, delta)
        renderer.endFrame()
        metrics
    }

    private fun textArea(initial: String = "", visibleLines: Int = 3, maxLength: Int = 100): TextAreaSetting =
        TextAreaSetting(
            id = id("notes"),
            metadata = SettingMetadata(constantState("Notes")),
            binding = mutableStateOf(initial, "notes").asBinding(),
            defaultValue = "",
            placeholder = constantState("Anything you like"),
            visibleLines = visibleLines,
            maxLength = maxLength,
        )

    private fun stringList(
        initial: List<String> = listOf("alpha", "beta"),
        reorderable: Boolean = true,
        maxItems: Int = Int.MAX_VALUE,
        create: (() -> String)? = { "new" },
    ): ListSetting<String> = ListSetting(
        id = id("entries"),
        metadata = SettingMetadata(constantState("Entries")),
        binding = mutableStateOf(initial, "entries").asBinding(),
        defaultValue = emptyList(),
        elementSerializer = SettingSerializers.string,
        itemLabel = { it },
        createItem = create,
        isReorderable = reorderable,
        maxItems = maxItems,
    )

    private fun dropdown(searchable: Boolean, vararg labels: String): DropdownSetting<String> = DropdownSetting(
        id = id("choice"),
        metadata = SettingMetadata(constantState("Choice")),
        binding = mutableStateOf(labels.first(), "choice").asBinding(),
        defaultValue = labels.first(),
        options = constantState(labels.map { Option(it.lowercase(), constantState(it), it) }),
        isSearchable = searchable,
    )

    /** Focuses [node] and returns it, so keystrokes reach it the way they would in game. */
    private fun <T : UiNode> focused(node: T): T {
        runtime.root = node
        frame()
        check(runtime.focus.requestFocus(node)) { "${node.id} refused focus" }
        return node
    }

    private fun type(text: String) {
        for (character in text) runtime.input.charTyped(character.code)
    }

    private fun press(key: Key) {
        runtime.input.keyPressed(key)
    }

    /** Walks a subtree looking for a node of a given type. */
    private inline fun <reified T> find(root: UiNode): T? {
        var found: T? = null
        root.forEachInTree { if (found == null && it is T) found = it }
        return found
    }

    // ---------------------------------------------------------------
    // The gap: settings with no registered control
    // ---------------------------------------------------------------

    @Test
    fun `every phase-one setting type has a real control, not a placeholder`() {
        val settings = listOf(textArea(), stringList())

        for (setting in settings) {
            val node = registry.createNode(setting, context)
            assertNotNull(node, "${setting::class.simpleName} should build something")
            assertFalse(
                find<MissingComponentNode>(node) != null,
                "${setting::class.simpleName} fell through to the missing-component placeholder",
            )
        }
    }

    // ---------------------------------------------------------------
    // Text area
    // ---------------------------------------------------------------

    @Test
    fun `a text area sizes to its declared line count, not to its content`() {
        val setting = textArea(visibleLines = 4)
        val control = focused(TextAreaControlNode(setting, context))
        val empty = control.measuredSize.height

        press(Key.SPACE)
        type("x".repeat(20))
        frame()

        assertEquals(empty, control.measuredSize.height, "the row must not jump as the value is typed")
    }

    @Test
    fun `enter inserts a newline rather than committing`() {
        val setting = textArea()
        val control = focused(TextAreaControlNode(setting, context))
        press(Key.SPACE)

        type("a")
        press(Key.ENTER)
        type("b")

        assertEquals("a\nb", setting.value, "Enter is a newline in a text area")
        assertEquals(2, control.lineCount)
        assertTrue(control.isEditing, "and it does not stop editing")
    }

    @Test
    fun `backspace removes the character before the caret`() {
        val setting = textArea(initial = "abc")
        val control = focused(TextAreaControlNode(setting, context))
        press(Key.SPACE)

        press(Key.BACKSPACE)
        assertEquals("ab", setting.value)
    }

    @Test
    fun `a text area refuses input past its maximum length`() {
        val setting = textArea(initial = "abcde", maxLength = 5)
        val control = focused(TextAreaControlNode(setting, context))
        press(Key.SPACE)

        type("f")
        assertEquals("abcde", setting.value, "the limit must hold rather than truncating afterwards")
    }

    @Test
    fun `escape stops editing`() {
        val control = focused(TextAreaControlNode(textArea(), context))
        press(Key.SPACE)
        assertTrue(control.isEditing)

        press(Key.ESCAPE)
        assertFalse(control.isEditing)
    }

    @Test
    fun `a text area ignores typing when it is not editing`() {
        val setting = textArea()
        focused(TextAreaControlNode(setting, context))

        type("x")
        assertEquals("", setting.value, "a control that was never focused must not capture keystrokes")
    }

    // ---------------------------------------------------------------
    // Editable and reorderable list
    // ---------------------------------------------------------------

    @Test
    fun `a list builds one row per entry`() {
        val setting = stringList(listOf("a", "b", "c"))
        val control = ListControlNode(setting, context)
        runtime.root = control
        frame()

        assertEquals(3, control.rowCount)
    }

    @Test
    fun `adding and removing rebuilds the rows`() {
        val setting = stringList(listOf("a"))
        val control = ListControlNode(setting, context)
        runtime.root = control
        frame()
        assertEquals(1, control.rowCount)

        setting.add("b")
        frame()
        assertEquals(2, control.rowCount)

        setting.removeAt(0)
        frame()
        assertEquals(1, control.rowCount)
        assertEquals(listOf("b"), setting.value)
    }

    @Test
    fun `move reorders and is refused at the ends`() {
        val setting = stringList(listOf("a", "b", "c"))

        assertTrue(setting.move(0, 2))
        assertEquals(listOf("b", "c", "a"), setting.value)

        assertFalse(setting.move(0, 0), "moving somewhere is not a move")
        assertFalse(setting.move(0, 5), "and neither is moving off the end")
    }

    @Test
    fun `a non-reorderable list refuses to move`() {
        val setting = stringList(listOf("a", "b"), reorderable = false)
        assertFalse(setting.move(0, 1))
        assertEquals(listOf("a", "b"), setting.value)
    }

    @Test
    fun `a full list refuses to add`() {
        val setting = stringList(listOf("a", "b"), maxItems = 2)
        assertFalse(setting.add("c"))
        assertEquals(2, setting.value.size)
    }

    @Test
    fun `a list with no createItem shows no add row`() {
        val setting = stringList(listOf("a"), create = null)
        val control = ListControlNode(setting, context)
        runtime.root = control
        frame()

        assertEquals(1, control.rowCount)
        assertEquals(1, control.children.first().children.size, "no add button when items cannot be created")
    }

    @Test
    fun `list entries contribute to search`() {
        val setting = stringList(listOf("diamond pickaxe", "netherite hoe"))
        assertTrue(
            setting.searchTerms().any { it.contains("netherite") },
            "a list's contents should be findable, got ${setting.searchTerms()}",
        )
    }

    // ---------------------------------------------------------------
    // Searchable dropdown
    // ---------------------------------------------------------------

    @Test
    fun `a searchable dropdown filters as you type`() {
        val setting = dropdown(true, "Apple", "Apricot", "Banana", "Cherry")
        val popup = DropdownPopupNode(id("popup"), setting, context) {}
        runtime.root = popup
        frame()
        assertEquals(4, popup.itemCount)

        popup.setQuery("ap")
        frame()

        assertEquals(
            listOf("Apple", "Apricot"),
            popup.visibleOptions.map { it.label.peek() },
            "matching is a case-insensitive substring",
        )
    }

    @Test
    fun `typing drives the same filter a test does`() {
        val setting = dropdown(true, "Apple", "Banana")
        val popup = focused(DropdownPopupNode(id("popup"), setting, context) {})

        type("b")
        assertEquals(listOf("Banana"), popup.visibleOptions.map { it.label.peek() })

        press(Key.BACKSPACE)
        assertEquals(2, popup.itemCount, "backspace widens the result again")
    }

    @Test
    fun `a filter matching nothing says so rather than showing an empty box`() {
        val setting = dropdown(true, "Apple", "Banana")
        val popup = DropdownPopupNode(id("popup"), setting, context) {}
        runtime.root = popup

        popup.setQuery("zzz")
        frame()

        assertEquals(0, popup.itemCount, "no options match")
        assertNotNull(popup.itemAt(0), "but something is still drawn, so silence does not look like a bug")
    }

    @Test
    fun `enter takes the only remaining match`() {
        val setting = dropdown(true, "Apple", "Banana")
        var chosen: Option<String>? = null
        val popup = focused(DropdownPopupNode(id("popup"), setting, context) { chosen = it })

        popup.setQuery("ban")
        press(Key.ENTER)

        assertEquals("Banana", chosen?.label?.peek())
    }

    @Test
    fun `enter does nothing while several options still match`() {
        val setting = dropdown(true, "Apple", "Apricot")
        var chosen: Option<String>? = null
        val popup = focused(DropdownPopupNode(id("popup"), setting, context) { chosen = it })

        popup.setQuery("ap")
        press(Key.ENTER)

        assertEquals(null, chosen, "choosing arbitrarily between two matches would be worse than doing nothing")
    }

    @Test
    fun `a non-searchable dropdown ignores typing entirely`() {
        val setting = dropdown(false, "Apple", "Banana")
        val popup = DropdownPopupNode(id("popup"), setting, context) {}
        runtime.root = popup
        frame()

        // Not focusable at all when it is not searchable, so typing cannot reach it.
        assertFalse(runtime.focus.requestFocus(popup))
        type("b")
        assertEquals(2, popup.itemCount)
        assertEquals("", popup.query.peek())
    }

    @Test
    fun `the selected index follows the filtered list, not the full one`() {
        val setting = dropdown(true, "Apple", "Banana", "Cherry")
        setting.setUnchecked("Cherry")
        val popup = DropdownPopupNode(id("popup"), setting, context) {}

        assertEquals(2, popup.selectedIndex())
        popup.setQuery("c")
        assertEquals(0, popup.selectedIndex(), "Cherry is now the first visible option")
    }

    // ---------------------------------------------------------------
    // Tabs
    // ---------------------------------------------------------------

    private fun tabs(count: Int = 3, built: MutableList<Int> = mutableListOf()): TabsNode = TabsNode(
        id("tabs"),
        (0 until count).map { index ->
            TabDefinition(id("tab_$index"), constantState("Tab $index")) {
                built.add(index)
                SettingRowNodeStub(id("tab_body_$index"))
            }
        },
        context,
    )

    /** A trivial leaf, so a tab body is something concrete without dragging in a setting. */
    private class SettingRowNodeStub(id: UiId) : UiNode(id) {
        override fun measureSelf(
            constraints: dev.th7bo.sidequest.ui.geometry.Constraints,
            context: dev.th7bo.sidequest.ui.core.tree.LayoutContext,
        ): Size = Size(20f, 10f)
    }

    @Test
    fun `only the selected tab's content is built`() {
        val built = mutableListOf<Int>()
        val node = tabs(built = built)
        runtime.root = node
        frame()

        assertEquals(listOf(0), built, "building every tab up front pays for screens nobody opened")
        assertTrue(node.isBuilt(0))
        assertFalse(node.isBuilt(2))
    }

    @Test
    fun `selecting a tab builds it once and keeps it`() {
        val built = mutableListOf<Int>()
        val node = tabs(built = built)
        runtime.root = node

        node.select(1)
        frame()
        node.select(0)
        frame()
        node.select(1)
        frame()

        assertEquals(listOf(0, 1), built, "a revisited tab must not be rebuilt")
    }

    @Test
    fun `only the selected tab's content is in the tree`() {
        val node = tabs()
        runtime.root = node
        frame()
        val content = node.children[1]
        assertEquals(1, content.children.size, "exactly one body at a time")

        node.select(2)
        frame()
        assertEquals(1, content.children.size)
    }

    @Test
    fun `arrow keys move between tabs and stop at the ends`() {
        val node = focused(tabs())

        press(Key.ARROW_RIGHT)
        assertEquals(1, node.selected.peek())

        press(Key.ARROW_LEFT)
        press(Key.ARROW_LEFT)
        assertEquals(0, node.selected.peek(), "clamped rather than wrapping")
    }

    // ---------------------------------------------------------------
    // Expandable panel
    // ---------------------------------------------------------------

    @Test
    fun `a collapsed panel does not build its content`() {
        var built = 0
        val panel = expandablePanel(id("panel"), "Advanced", context) {
            built++
            SettingRowNodeStub(id("panel_body"))
        }
        runtime.root = panel
        frame()

        assertEquals(0, built, "a closed section must cost nothing")
        assertFalse(panel.isContentBuilt)
    }

    @Test
    fun `expanding builds the content once`() {
        var built = 0
        val panel = expandablePanel(id("panel"), "Advanced", context) {
            built++
            SettingRowNodeStub(id("panel_body"))
        }
        runtime.root = panel

        panel.setExpanded(true)
        frame()
        panel.setExpanded(false)
        frame()
        panel.setExpanded(true)
        frame()

        assertEquals(1, built, "reopening must reuse the content, keeping its state")
    }

    @Test
    fun `a collapsed panel is only as tall as its header`() {
        val panel = expandablePanel(id("panel"), "Advanced", context) {
            SettingRowNodeStub(id("panel_body"))
        }
        runtime.root = panel
        frame()
        val collapsed = panel.measuredSize.height

        panel.setExpanded(true)
        frame()

        assertTrue(panel.measuredSize.height > collapsed, "expanding should make it taller")

        panel.setExpanded(false)
        frame()
        assertEquals(collapsed, panel.measuredSize.height, "and collapsing should give the space back")
    }

    @Test
    fun `a collapsed panel detaches its body rather than hiding it`() {
        lateinit var body: UiNode
        val panel = expandablePanel(id("panel"), "Advanced", context, isExpanded = true) {
            SettingRowNodeStub(id("panel_body")).also { body = it }
        }
        runtime.root = panel
        frame()
        assertSame(panel, body.parent, "the body is in the tree while open")

        panel.setExpanded(false)
        frame()

        // Detached, not hidden. An invisible child still sits in the tree being walked;
        // a detached one costs nothing, which is what makes a screen of closed sections
        // cheap rather than merely quiet.
        assertEquals(null, body.parent, "the body should be off the tree while collapsed")
        assertEquals(1, panel.children.size)
        assertTrue(body.isVisible, "and it is detached rather than flagged invisible")
    }

    @Test
    fun `enter and space toggle the panel`() {
        val panel = expandablePanel(id("panel"), "Advanced", context) {
            SettingRowNodeStub(id("panel_body"))
        }
        focused(panel)

        press(Key.ENTER)
        assertTrue(panel.isExpanded.peek())

        press(Key.SPACE)
        assertFalse(panel.isExpanded.peek())
    }
}
