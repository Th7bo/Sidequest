package dev.th7bo.sidequest.ui.core.virtualization

import dev.th7bo.sidequest.ui.core.UiTestHarness
import dev.th7bo.sidequest.ui.core.UiTestHarness.Companion.id
import dev.th7bo.sidequest.ui.core.content.SurfaceNode
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VirtualListTest {

    private lateinit var harness: UiTestHarness

    /** Fixed-height rows, so index arithmetic in the assertions stays obvious. */
    private class FixedRows(
        override val rowCount: Int,
        private val rowHeight: Float = 20f,
    ) : RowProvider {

        var created = 0
            private set

        val createdIndices = ArrayList<Int>()

        override fun keyAt(index: Int): Any = "row_$index"

        override fun estimatedHeight(index: Int): Float = rowHeight

        override fun createRow(index: Int): UiNode {
            created++
            createdIndices.add(index)
            return SurfaceNode(id("row_$index")).apply {
                preferredSize = Size(100f, rowHeight)
                interactive = true
                focusable = true
            }
        }
    }

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        // 200 units tall = exactly 10 rows of 20 visible.
        harness = UiTestHarness(Size(200f, 200f))
    }

    @AfterEach
    fun tearDown() {
        harness.dispose()
        resetReactiveGraphForTesting()
    }

    private fun list(provider: RowProvider, overscan: Int = 2): VirtualListNode =
        VirtualListNode(id("list"), provider).apply {
            overscanRows = overscan
            harness.root = this
        }

    @Test
    fun `only the visible rows plus overscan are materialized`() {
        val provider = FixedRows(rowCount = 1000)
        val list = list(provider, overscan = 2)
        harness.frame()

        assertEquals(1000, list.registeredRowCount)
        // 10 visible + 2 overscan below (nothing above at the top).
        assertEquals(12, list.materializedRowCount)
        assertEquals(0..11, list.materializedRange)
        assertEquals(12, provider.created, "no row outside the window may be built")
    }

    @Test
    fun `a thousand rows cost the same as a dozen`() {
        // Capture everything before disposing: a disposed list has released its rows.
        val smallList = list(FixedRows(rowCount = 12))
        val smallMetrics = harness.frame()
        val smallMaterialized = smallList.materializedRowCount
        harness.dispose()

        resetReactiveGraphForTesting()
        harness = UiTestHarness(Size(200f, 200f))
        val largeList = list(FixedRows(rowCount = 1000))
        val largeMetrics = harness.frame()

        assertEquals(smallMaterialized, largeList.materializedRowCount)
        assertEquals(
            smallMetrics.nodesMeasured,
            largeMetrics.nodesMeasured,
            "measurement cost must not scale with the number of registered rows",
        )
        assertEquals(
            smallMetrics.nodesArranged,
            largeMetrics.nodesArranged,
            "nor may arrangement cost",
        )
    }

    @Test
    fun `scrolling recycles rows rather than growing the tree`() {
        val provider = FixedRows(rowCount = 500)
        val list = list(provider, overscan = 2)
        harness.frame()
        val initialCount = list.materializedRowCount

        list.scrollTo(1000f)
        harness.frame()

        assertEquals(
            initialCount + 2,
            list.materializedRowCount,
            "mid-list there is overscan on both sides, but the window stays bounded",
        )
        assertEquals(48..61, list.materializedRange)
    }

    @Test
    fun `a row keeps its node while it stays inside the window`() {
        val provider = FixedRows(rowCount = 100)
        val list = list(provider, overscan = 2)
        harness.frame()

        val rowFive = list.nodeForRow(5)
        assertNotNull(rowFive)

        // Scroll by one row: row 5 is still within the window.
        list.scrollTo(20f)
        harness.frame()

        assertSame(rowFive, list.nodeForRow(5), "a row in view must not be rebuilt")
    }

    @Test
    fun `a row that leaves and returns is rebuilt, not resurrected`() {
        val provider = FixedRows(rowCount = 100)
        val list = list(provider, overscan = 1)
        harness.frame()
        val original = list.nodeForRow(0)

        list.scrollTo(600f)
        harness.frame()
        assertNull(list.nodeForRow(0), "a row far outside the window must be released")

        list.scrollTo(0f)
        harness.frame()
        assertNotSame(original, list.nodeForRow(0))
    }

    @Test
    fun `content height and scroll bounds follow the row count`() {
        val provider = FixedRows(rowCount = 100, rowHeight = 20f)
        val list = list(provider)
        harness.frame()

        assertEquals(2000f, list.contentHeight)
        assertEquals(1800f, list.maxScrollOffset, "content minus the 200-unit viewport")
    }

    @Test
    fun `scrolling is clamped at both ends`() {
        val list = list(FixedRows(rowCount = 50))
        harness.frame()

        list.scrollTo(-100f)
        assertEquals(0f, list.scrollOffset)

        list.scrollTo(999_999f)
        assertEquals(list.maxScrollOffset, list.scrollOffset)
    }

    @Test
    fun `a list that fits does not scroll at all`() {
        val list = list(FixedRows(rowCount = 3))
        harness.frame()

        assertEquals(0f, list.maxScrollOffset)
        assertFalse(list.scrollBy(100f))
    }

    @Test
    fun `rows are positioned relative to the scroll offset`() {
        val list = list(FixedRows(rowCount = 100), overscan = 0)
        harness.frame()
        assertEquals(0f, list.nodeForRow(0)!!.bounds.y)

        list.scrollTo(50f)
        harness.frame()

        // Row 2 starts at 40; with the viewport scrolled to 50 it sits at -10.
        assertEquals(-10f, list.nodeForRow(2)!!.bounds.y)
    }

    @Test
    fun `scrollToRow brings an unmaterialized row into view`() {
        val list = list(FixedRows(rowCount = 1000))
        harness.frame()
        assertNull(list.nodeForRow(400), "the target starts far outside the window")

        list.scrollToRow(400, ScrollAlignment.START)
        harness.frame()

        assertNotNull(list.nodeForRow(400))
        assertTrue(list.isRowVisible(400))
        assertEquals(8000f, list.scrollOffset)
    }

    @Test
    fun `scrollToRow with NEAREST does nothing when the row is already visible`() {
        val list = list(FixedRows(rowCount = 100))
        harness.frame()

        assertFalse(list.scrollToRow(3, ScrollAlignment.NEAREST))
        assertEquals(0f, list.scrollOffset)
    }

    @Test
    fun `scrollToRow with NEAREST moves the minimum distance`() {
        val list = list(FixedRows(rowCount = 100))
        harness.frame()

        // Row 12 spans 240..260; the viewport ends at 200, so it must move by 60.
        list.scrollToRow(12, ScrollAlignment.NEAREST)
        assertEquals(60f, list.scrollOffset)
    }

    @Test
    fun `scrollToRow CENTER puts the row in the middle`() {
        val list = list(FixedRows(rowCount = 100))
        harness.frame()

        list.scrollToRow(50, ScrollAlignment.CENTER)
        // Row 50 spans 1000..1020; centring it in a 200-tall viewport starts at 910.
        assertEquals(910f, list.scrollOffset)
    }

    @Test
    fun `scrollToRow rejects an out-of-range index`() {
        val list = list(FixedRows(rowCount = 10))
        harness.frame()

        assertFalse(list.scrollToRow(-1))
        assertFalse(list.scrollToRow(10))
    }

    @Test
    fun `the wheel scrolls the list and the event is consumed`() {
        val list = list(FixedRows(rowCount = 100))
        harness.frame()

        val consumed = harness.runtime.input.scrolled(Vec2(50f, 50f), scrollX = 0f, scrollY = -2f)

        assertTrue(consumed)
        assertEquals(2 * VirtualListNode.DEFAULT_SCROLL_STEP, list.scrollOffset)
    }

    @Test
    fun `a list that cannot scroll leaves the wheel event for an outer scroller`() {
        list(FixedRows(rowCount = 2))
        harness.frame()

        assertFalse(
            harness.runtime.input.scrolled(Vec2(50f, 50f), scrollX = 0f, scrollY = -2f),
            "an unscrollable list must not swallow the wheel",
        )
    }

    @Test
    fun `hit testing only reaches materialized rows`() {
        val list = list(FixedRows(rowCount = 1000), overscan = 0)
        harness.frame()

        val hit = harness.runtime.input.hitTest(Vec2(50f, 50f))
        assertSame(list.nodeForRow(2), hit.last())

        // Far below the viewport there is no node to hit, and the list clips anyway.
        assertTrue(harness.runtime.input.hitTest(Vec2(50f, 500f)).isEmpty())
    }

    @Test
    fun `variable row heights are measured and cached`() {
        val provider = object : RowProvider {
            override val rowCount: Int get() = 50
            override fun keyAt(index: Int): Any = "row_$index"

            // Deliberately wrong estimate, so the test proves measurement wins.
            override fun estimatedHeight(index: Int): Float = 10f

            override fun createRow(index: Int): UiNode = SurfaceNode(id("row_$index")).apply {
                preferredSize = Size(100f, if (index % 2 == 0) 20f else 40f)
            }
        }
        val list = list(provider, overscan = 0)
        harness.frame()

        // Rows that have never been shown still contribute their estimate, which is the
        // documented trade-off: an unseen row costs scrollbar accuracy, not correctness.
        val beforeScrolling = list.contentHeight
        assertTrue(beforeScrolling < 50 * 30f, "unmeasured rows still use their estimate")

        // Walk the whole list so every row gets measured once.
        var guard = 0
        while (list.scrollBy(100f) && guard++ < 200) harness.frame()
        harness.frame()

        assertEquals(50 * 30f, list.contentHeight, "25 rows of 20 plus 25 of 40, once all are seen")
    }

    @Test
    fun `focus survives a focused row scrolling out of view and back`() {
        val provider = FixedRows(rowCount = 200)
        val list = list(provider, overscan = 1)
        harness.frame()

        val target = list.nodeForRow(2)!!
        assertTrue(harness.runtime.focus.requestFocus(target))
        val rememberedPath = harness.runtime.focus.focusedPath

        list.scrollTo(1000f)
        harness.frame()
        harness.runtime.focus.clearFocus()
        assertNull(list.nodeForRow(2), "the focused row was released")

        list.scrollTo(0f)
        harness.frame()

        assertTrue(harness.runtime.focus.restoreIfPossible())
        assertSame(list.nodeForRow(2), harness.runtime.focus.focused)
        assertEquals(rememberedPath, harness.runtime.focus.focusedPath)
    }

    @Test
    fun `an idle virtualized list does no layout work`() {
        list(FixedRows(rowCount = 1000))
        harness.frame()
        harness.frame()

        val idle = harness.frame()
        assertEquals(0, idle.nodesMeasured)
        assertEquals(0, idle.nodesArranged)
        assertTrue(harness.runtime.isIdle)
    }

    @Test
    fun `shrinking the viewport clamps a scroll offset that is now past the end`() {
        val list = list(FixedRows(rowCount = 20))
        harness.frame()
        list.scrollTo(list.maxScrollOffset)
        harness.frame()
        val atBottom = list.scrollOffset

        harness.runtime.viewport = Size(200f, 400f)
        harness.frame()

        assertTrue(list.scrollOffset < atBottom)
        assertEquals(list.maxScrollOffset, list.scrollOffset)
    }
}
