package dev.th7bo.sidequest.ui.core.focus

import dev.th7bo.sidequest.ui.core.UiTestHarness
import dev.th7bo.sidequest.ui.core.UiTestHarness.Companion.id
import dev.th7bo.sidequest.ui.core.content.SurfaceNode
import dev.th7bo.sidequest.ui.core.layout.ColumnNode
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.input.FocusEvent
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.Modifiers
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.testkit.EventRecorder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FocusTest {

    private lateinit var harness: UiTestHarness
    private lateinit var column: ColumnNode
    private lateinit var first: SurfaceNode
    private lateinit var second: SurfaceNode
    private lateinit var third: SurfaceNode

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        harness = UiTestHarness(Size(200f, 200f))

        column = ColumnNode(id("root"))
        first = field("first")
        second = field("second")
        third = field("third")
        // A non-focusable decoration between two fields must be skipped by Tab.
        val decoration = SurfaceNode(id("decoration")).apply { preferredSize = Size(100f, 4f) }
        column.addChildren(first, decoration, second, third)

        harness.root = column
        harness.frame()
    }

    @AfterEach
    fun tearDown() {
        harness.dispose()
        resetReactiveGraphForTesting()
    }

    private fun field(name: String) = SurfaceNode(id(name)).apply {
        preferredSize = Size(100f, 20f)
        interactive = true
        focusable = true
    }

    private val focus get() = harness.runtime.focus
    private val input get() = harness.runtime.input

    @Test
    fun `focus order skips non-focusable nodes`() {
        assertEquals(listOf(first, second, third), focus.focusOrder())
    }

    @Test
    fun `tab walks forward and wraps at the end`() {
        input.keyPressed(Key.TAB)
        assertSame(first, focus.focused)

        input.keyPressed(Key.TAB)
        assertSame(second, focus.focused)

        input.keyPressed(Key.TAB)
        assertSame(third, focus.focused)

        input.keyPressed(Key.TAB)
        assertSame(first, focus.focused, "Tab must wrap around")
    }

    @Test
    fun `shift tab walks backward and wraps at the start`() {
        input.keyPressed(Key.TAB, Modifiers.Shift)
        assertSame(third, focus.focused, "the first Shift+Tab lands on the last field")

        input.keyPressed(Key.TAB, Modifiers.Shift)
        assertSame(second, focus.focused)
    }

    @Test
    fun `focus flags and events follow the focused node`() {
        val events = ArrayList<Boolean>()
        first.onEvent = { if (it is FocusEvent) events.add(it.gained) }
        second.onEvent = { if (it is FocusEvent) events.add(it.gained) }

        focus.requestFocus(first)
        assertTrue(first.isFocused)

        focus.requestFocus(second)
        assertFalse(first.isFocused, "the previous holder must be unfocused")
        assertTrue(second.isFocused)

        assertEquals(listOf(true, false, true), events, "gained, lost, gained")
    }

    @Test
    fun `a non-focusable node is refused rather than silently accepted`() {
        val decoration = column.children[1]
        assertFalse(focus.requestFocus(decoration))
        assertNull(focus.focused)
    }

    @Test
    fun `a hidden node cannot take focus`() {
        second.isVisible = false
        assertFalse(focus.requestFocus(second))
    }

    @Test
    fun `clicking a focusable node focuses it`() {
        input.pointerPressed(Vec2(10f, 10f))
        assertSame(first, focus.focused)

        input.pointerPressed(Vec2(10f, 34f))
        assertSame(second, focus.focused)
    }

    @Test
    fun `clicking outside every node clears focus`() {
        focus.requestFocus(second)
        input.pointerPressed(Vec2(180f, 190f))
        assertNull(focus.focused)
    }

    @Test
    fun `escape clears focus`() {
        focus.requestFocus(second)
        assertTrue(input.keyPressed(Key.ESCAPE))
        assertNull(focus.focused)
    }

    @Test
    fun `a focused node receives key and character events`() {
        val keys = ArrayList<String>()
        second.onEvent = { keys.add(dev.th7bo.sidequest.ui.testkit.EventRecorder.describe(it)) }
        focus.requestFocus(second)

        input.keyPressed(Key.A)
        input.charTyped('a'.code)

        assertTrue(keys.contains("keyDown/A"))
        assertTrue(keys.contains("char/a"))
    }

    @Test
    fun `key events capture down to the focused node and bubble back up`() {
        val recorder = EventRecorder()
        column.onEvent = recorder.handler("root")
        second.onEvent = recorder.handler("field")
        focus.requestFocus(second)
        recorder.clear()

        input.keyPressed(Key.A)

        // The root is an ancestor of the target, so it legitimately sees the event
        // twice: once on the way down and once on the way back up.
        assertEquals(
            listOf("CAPTURE:root", "TARGET:field", "BUBBLE:root"),
            recorder.trace(),
        )
    }

    @Test
    fun `an ancestor can swallow a key before the focused node sees it`() {
        val recorder = EventRecorder()
        column.onEvent = recorder.consumingHandler("root")
        second.onEvent = recorder.handler("field")
        focus.requestFocus(second)
        recorder.clear()

        assertTrue(input.keyPressed(Key.A))
        assertEquals(listOf("CAPTURE:root"), recorder.trace())
    }

    @Test
    fun `focus survives being discarded and rematerialized at the same path`() {
        focus.requestFocus(second)
        val rememberedPath = focus.focusedPath
        assertEquals(listOf(id("root"), id("second")), rememberedPath)

        // Simulate virtualization discarding the row and recreating it later.
        column.removeChild(second)
        focus.clearFocus()
        assertNull(focus.focused)

        val recreated = field("second")
        column.addChild(recreated)
        harness.frame()

        assertTrue(focus.restoreIfPossible())
        assertSame(recreated, focus.focused, "focus must return to the node with the same identity")
    }

    @Test
    fun `restore does nothing when the path no longer resolves`() {
        focus.requestFocus(second)
        column.removeChild(second)
        focus.clearFocus()

        assertFalse(focus.restoreIfPossible())
        assertNull(focus.focused)
    }

    @Test
    fun `replacing the tree resets focus`() {
        focus.requestFocus(second)
        harness.root = ColumnNode(id("replacement"))

        assertNull(focus.focused)
        assertTrue(focus.focusedPath.isEmpty())
    }
}
