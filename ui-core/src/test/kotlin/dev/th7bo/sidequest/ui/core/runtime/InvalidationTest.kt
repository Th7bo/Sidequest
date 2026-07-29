package dev.th7bo.sidequest.ui.core.runtime

import dev.th7bo.sidequest.ui.core.UiTestHarness
import dev.th7bo.sidequest.ui.core.UiTestHarness.Companion.id
import dev.th7bo.sidequest.ui.core.content.SurfaceNode
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.core.layout.ColumnNode
import dev.th7bo.sidequest.ui.core.layout.RowNode
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The Phase 1 acceptance criterion "no full-tree rebuild during idle frames", plus the
 * targeted-invalidation behaviour that makes it hold.
 */
class InvalidationTest {

    private lateinit var harness: UiTestHarness

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        harness = UiTestHarness(Size(400f, 300f))
    }

    @AfterEach
    fun tearDown() {
        harness.dispose()
        resetReactiveGraphForTesting()
    }

    private fun buildTree(rows: Int, labels: MutableList<TextNode> = ArrayList()): ColumnNode {
        val column = ColumnNode(id("root"))
        repeat(rows) { index ->
            val row = RowNode(id("row_$index"))
            val label = TextNode.of(id("label_$index"), "Setting $index")
            val swatch = SurfaceNode(id("swatch_$index")).apply { preferredSize = Size(10f, 10f) }
            row.addChildren(label, swatch)
            column.addChild(row)
            labels.add(label)
        }
        return column
    }

    @Test
    fun `an idle frame measures and arranges nothing`() {
        harness.root = buildTree(rows = 20)

        val first = harness.frame()
        assertTrue(first.nodesMeasured > 0, "the first frame must lay the tree out")

        val idle = harness.frame()

        assertEquals(0, idle.nodesMeasured, "an idle frame must not measure any node")
        assertEquals(0, idle.nodesArranged, "an idle frame must not arrange any node")
        assertTrue(harness.runtime.isIdle)
    }

    @Test
    fun `many idle frames in a row stay at zero layout work`() {
        harness.root = buildTree(rows = 50)
        harness.frame()

        repeat(30) {
            val metrics = harness.frame()
            assertEquals(0, metrics.nodesMeasured)
            assertEquals(0, metrics.nodesArranged)
        }
    }

    @Test
    fun `an idle frame still paints, because the host clears the screen every frame`() {
        harness.root = buildTree(rows = 5)
        harness.frame()

        val idle = harness.frame()

        assertEquals(0, idle.nodesMeasured)
        assertTrue(idle.nodesVisible > 0, "the tree must still be drawn")
        assertTrue(idle.drawCalls > 0)
    }

    @Test
    fun `changing one label remeasures only its own spine`() {
        val labels = ArrayList<TextNode>()
        val text = mutableStateOf("Setting 3")
        harness.root = buildTree(rows = 20, labels = labels)

        // Swap one label over to an observable source.
        labels[3].text = text
        harness.frame()

        val totalNodes = harness.frame().nodesMaterialized
        text.value = "Setting 3 (changed)"
        val metrics = harness.frame()

        // root column -> row -> label is three measures; the changed label's sibling and
        // the other 19 rows must be cache hits, not remeasures.
        assertEquals(3, metrics.nodesMeasured, "only the changed node's ancestor chain may remeasure")
        assertTrue(totalNodes > 40, "sanity: the tree really is large")
    }

    @Test
    fun `a change that does not alter the measured size still avoids touching siblings`() {
        val labels = ArrayList<TextNode>()
        val text = mutableStateOf("aaa")
        harness.root = buildTree(rows = 10, labels = labels)
        labels[0].text = text
        harness.frame()

        // Same length, so the measured size is identical, but the content differs.
        text.value = "bbb"
        val metrics = harness.frame()

        assertEquals(3, metrics.nodesMeasured)
    }

    @Test
    fun `invalidation stops at an already-dirty ancestor`() {
        val labels = ArrayList<TextNode>()
        val first = mutableStateOf("one")
        val second = mutableStateOf("two")
        val column = buildTree(rows = 6, labels = labels)
        labels[0].text = first
        labels[1].text = second
        harness.root = column
        harness.frame()

        first.value = "ONE"
        second.value = "TWO"
        val metrics = harness.frame()

        // Two rows plus the shared root: five measures, not the whole tree.
        assertEquals(5, metrics.nodesMeasured)
    }

    @Test
    fun `a resolution change invalidates the whole tree exactly once`() {
        harness.root = buildTree(rows = 4)
        val initial = harness.frame().nodesMaterialized

        harness.runtime.viewport = Size(800f, 600f)
        val resized = harness.frame()

        assertEquals(initial, resized.nodesMeasured, "every node must remeasure after a resize")
        assertEquals(0, harness.frame().nodesMeasured, "and then settle back to idle")
    }

    @Test
    fun `a theme change invalidates measurement because type metrics move`() {
        harness.root = buildTree(rows = 3)
        harness.frame()

        harness.runtime.theme = dev.th7bo.sidequest.ui.theme.LightTheme
        assertTrue(harness.frame().nodesMeasured > 0)
        assertEquals(0, harness.frame().nodesMeasured)
    }

    @Test
    fun `isIdle reports pending scheduled work`() {
        harness.root = buildTree(rows = 2)
        harness.frame()
        assertTrue(harness.runtime.isIdle)

        harness.runtime.submit { }
        assertFalse(harness.runtime.isIdle)

        harness.frame()
        assertTrue(harness.runtime.isIdle)
    }

    @Test
    fun `scheduled work runs on the ui thread at the start of a frame`() {
        harness.root = buildTree(rows = 1)
        harness.frame()

        val order = ArrayList<String>()
        harness.runtime.submit { order.add("first") }
        harness.runtime.submit { order.add("second") }

        assertEquals(emptyList<String>(), order, "submitted work must not run immediately")
        harness.frame()
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun `text layout is cached across frames`() {
        harness.root = buildTree(rows = 8)
        harness.frame()

        val afterFirstFrame = harness.textMeasurer.measureCount
        harness.frames(10)

        assertEquals(
            afterFirstFrame,
            harness.textMeasurer.measureCount,
            "idle frames must not re-measure text",
        )
    }
}
