package dev.th7bo.sidequest.ui.core.runtime

import dev.th7bo.sidequest.ui.core.UiTestHarness
import dev.th7bo.sidequest.ui.core.UiTestHarness.Companion.id
import dev.th7bo.sidequest.ui.core.content.SurfaceNode
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.core.layout.ColumnNode
import dev.th7bo.sidequest.ui.core.layout.RowNode
import dev.th7bo.sidequest.ui.core.virtualization.RowProvider
import dev.th7bo.sidequest.ui.core.virtualization.VirtualListNode
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The Phase 3 acceptance criterion "resolution and GUI-scale changes pass tests".
 *
 * Minecraft hands screens an already GUI-scaled viewport, so both a resolution change
 * and a GUI-scale change reach the framework the same way: as a new viewport size. That
 * is exactly why they can be tested here, with no Minecraft in the room.
 */
class ViewportAdaptationTest {

    private lateinit var harness: UiTestHarness

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        harness = UiTestHarness(Size(1920f, 1080f))
    }

    @AfterEach
    fun tearDown() {
        harness.dispose()
        resetReactiveGraphForTesting()
    }

    private fun contentTree(): ColumnNode {
        val column = ColumnNode(id("root"))
        repeat(6) { index ->
            val row = RowNode(id("row_$index"))
            row.addChild(TextNode.of(id("label_$index"), "Setting $index"))
            val filler = SurfaceNode(id("fill_$index")).apply { layoutWeight = 1f }
            row.addChild(filler)
            column.addChild(row)
        }
        return column
    }

    @ParameterizedTest
    @CsvSource("1280,720", "1920,1080", "2560,1440", "3440,1440", "640,480")
    fun `layout adapts to every supported resolution`(width: Int, height: Int) {
        harness.root = contentTree()
        harness.frame()

        harness.runtime.viewport = Size(width.toFloat(), height.toFloat())
        harness.renderer.resize(Size(width.toFloat(), height.toFloat()))
        harness.frame()

        val root = harness.root!!
        assertTrue(root.measuredSize.width <= width.toFloat())
        assertTrue(root.measuredSize.height <= height.toFloat())
        // A weighted child must actually consume the extra width, not leave it unused.
        val firstRow = root.children.first()
        assertEquals(width.toFloat(), firstRow.measuredSize.width, 0.5f)
    }

    @Test
    fun `an ultrawide viewport lays out without clipping content`() {
        harness.root = contentTree()
        harness.frame()

        harness.runtime.viewport = Size(3440f, 1440f)
        harness.frame()

        assertEquals(3440f, harness.root!!.children.first().measuredSize.width, 0.5f)
    }

    @Test
    fun `a resolution change re-measures once and then settles`() {
        harness.root = contentTree()
        harness.frame()

        harness.runtime.viewport = Size(1280f, 720f)
        val resized = harness.frame()
        assertTrue(resized.nodesMeasured > 0, "a resize must re-measure")

        assertEquals(0, harness.frame().nodesMeasured, "and then go idle again")
    }

    @Test
    fun `setting the same viewport again does no work`() {
        harness.root = contentTree()
        harness.frame()
        harness.frame()

        harness.runtime.viewport = Size(1920f, 1080f)

        assertEquals(0, harness.frame().nodesMeasured, "an unchanged viewport must not invalidate")
    }

    /**
     * A GUI-scale change divides the logical viewport: at scale 2 a 1920-pixel window
     * presents a 960-unit viewport, which is what a Minecraft screen receives.
     */
    @ParameterizedTest
    @CsvSource("1,1920,1080", "2,960,540", "3,640,360", "4,480,270")
    fun `a gui-scale change is just a smaller viewport`(scale: Int, expectedWidth: Int, expectedHeight: Int) {
        harness.root = contentTree()
        harness.frame()

        val scaled = Size(1920f / scale, 1080f / scale)
        assertEquals(expectedWidth.toFloat(), scaled.width, 0.5f)
        assertEquals(expectedHeight.toFloat(), scaled.height, 0.5f)

        harness.runtime.viewport = scaled
        harness.frame()

        assertEquals(scaled.width, harness.root!!.children.first().measuredSize.width, 0.5f)
    }

    @Test
    fun `a virtualized list re-materializes for a taller viewport`() {
        val provider = object : RowProvider {
            override val rowCount: Int get() = 500
            override fun keyAt(index: Int): Any = "row_$index"
            override fun estimatedHeight(index: Int): Float = 20f
            override fun createRow(index: Int): UiNode =
                SurfaceNode(id("row_$index")).apply { preferredSize = Size(100f, 20f) }
        }
        val list = VirtualListNode(id("list"), provider).apply { overscanRows = 1 }
        harness.runtime.viewport = Size(400f, 200f)
        harness.root = list
        harness.frame()
        val atSmall = list.materializedRowCount

        // Doubling the height must show roughly twice as many rows.
        harness.runtime.viewport = Size(400f, 400f)
        harness.frame()

        assertTrue(
            list.materializedRowCount > atSmall,
            "a taller viewport must show more rows, was $atSmall then ${list.materializedRowCount}",
        )
        assertTrue(list.materializedRowCount < 30, "but still not the whole list")
    }

    @Test
    fun `scroll position is clamped when a viewport grows past the content`() {
        val provider = object : RowProvider {
            override val rowCount: Int get() = 15
            override fun keyAt(index: Int): Any = "row_$index"
            override fun estimatedHeight(index: Int): Float = 20f
            override fun createRow(index: Int): UiNode =
                SurfaceNode(id("row_$index")).apply { preferredSize = Size(100f, 20f) }
        }
        val list = VirtualListNode(id("list"), provider)
        harness.runtime.viewport = Size(400f, 100f)
        harness.root = list
        harness.frame()

        list.scrollTo(list.maxScrollOffset)
        harness.frame()
        assertTrue(list.scrollOffset > 0f)

        // Everything now fits, so there is nowhere left to scroll.
        harness.runtime.viewport = Size(400f, 600f)
        harness.frame()

        assertEquals(0f, list.scrollOffset)
        assertEquals(0f, list.maxScrollOffset)
    }

    @Test
    fun `text is re-laid-out when the available width shrinks`() {
        val text = TextNode.of(id("long"), "a fairly long label that will need truncating")
        text.overflow = dev.th7bo.sidequest.ui.rendering.TextOverflow.ELLIPSIS
        val column = ColumnNode(id("root"))
        column.addChild(text)

        harness.runtime.viewport = Size(600f, 200f)
        harness.root = column
        harness.frame()
        val wide = text.measuredSize.width

        harness.runtime.viewport = Size(120f, 200f)
        harness.frame()

        assertTrue(text.measuredSize.width < wide, "the label must shrink to fit")
        assertTrue(text.measuredSize.width <= 120f)
    }
}
