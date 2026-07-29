package dev.th7bo.sidequest.ui.core.layout

import dev.th7bo.sidequest.ui.core.UiTestHarness
import dev.th7bo.sidequest.ui.core.UiTestHarness.Companion.id
import dev.th7bo.sidequest.ui.core.content.SurfaceNode
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.geometry.Alignment
import dev.th7bo.sidequest.ui.geometry.HorizontalAlignment
import dev.th7bo.sidequest.ui.geometry.Insets
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.VerticalAlignment
import dev.th7bo.sidequest.ui.geometry.dp
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LayoutTest {

    private lateinit var harness: UiTestHarness

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        harness = UiTestHarness()
    }

    @AfterEach
    fun tearDown() {
        harness.dispose()
        resetReactiveGraphForTesting()
    }

    private fun box(name: String, width: Float, height: Float) =
        SurfaceNode(id(name)).apply { preferredSize = Size(width, height) }

    @Test
    fun `column stacks children and applies spacing`() {
        val column = ColumnNode(id("column"), spacing = 4.dp)
        column.addChildren(
            box("a", 30f, 10f),
            box("b", 50f, 20f),
            box("c", 20f, 5f),
        )
        harness.root = column
        harness.frame()

        // Width is the widest child; height is the sum plus two gaps.
        assertEquals(Size(50f, 10f + 20f + 5f + 8f), column.measuredSize)
        assertEquals(Rect(0f, 0f, 30f, 10f), column.children[0].bounds)
        assertEquals(Rect(0f, 14f, 50f, 20f), column.children[1].bounds)
        assertEquals(Rect(0f, 38f, 20f, 5f), column.children[2].bounds)
    }

    @Test
    fun `row stacks horizontally and honours cross-axis alignment`() {
        val row = RowNode(id("row"), spacing = 2.dp)
        row.verticalAlignment = VerticalAlignment.CENTER
        row.addChildren(
            box("tall", 10f, 40f),
            box("short", 10f, 10f),
        )
        harness.root = row
        harness.frame()

        assertEquals(Size(22f, 40f), row.measuredSize)
        assertEquals(Rect(0f, 0f, 10f, 40f), row.children[0].bounds)
        // The short child is centred in the 40-unit tall row.
        assertEquals(Rect(12f, 15f, 10f, 10f), row.children[1].bounds)
    }

    @Test
    fun `column cross-axis alignment positions narrow children`() {
        val column = ColumnNode(id("column"))
        column.horizontalAlignment = HorizontalAlignment.END
        column.addChildren(box("wide", 100f, 10f), box("narrow", 20f, 10f))
        harness.root = column
        harness.frame()

        assertEquals(80f, column.children[1].bounds.x)
    }

    @Test
    fun `weighted children share the leftover space in proportion`() {
        val column = ColumnNode(id("column"))
        val fixed = box("fixed", 10f, 40f)
        val oneShare = box("one", 10f, 0f).apply { layoutWeight = 1f }
        val twoShares = box("two", 10f, 0f).apply { layoutWeight = 2f }
        column.addChildren(fixed, oneShare, twoShares)

        harness.runtime.viewport = Size(200f, 160f)
        harness.root = column
        harness.frame()

        // 160 available, 40 taken by the fixed child, 120 split 1:2.
        assertEquals(40f, oneShare.measuredSize.height)
        assertEquals(80f, twoShares.measuredSize.height)
        assertEquals(Rect(0f, 40f, 10f, 40f), oneShare.bounds)
        assertEquals(Rect(0f, 80f, 10f, 80f), twoShares.bounds)
    }

    @Test
    fun `a weighted spacer pushes siblings apart without any coordinate maths`() {
        val row = RowNode(id("row"))
        val left = box("left", 20f, 10f)
        val spacer = SpacerNode(id("spacer")).apply { layoutWeight = 1f }
        val right = box("right", 30f, 10f)
        row.addChildren(left, spacer, right)

        harness.runtime.viewport = Size(200f, 100f)
        harness.root = row
        harness.frame()

        assertEquals(0f, left.bounds.x)
        assertEquals(170f, right.bounds.x, "the right child must sit against the far edge")
    }

    @Test
    fun `weighted children fall back to intrinsic size in an unbounded axis`() {
        // A column measures its unweighted children with an unbounded main axis, so a
        // nested column has no finite leftover space to share out. Expanding to
        // infinity there would be a crash rather than a layout.
        val outer = ColumnNode(id("outer"))
        val inner = ColumnNode(id("inner"))
        val weighted = box("weighted", 10f, 25f).apply { layoutWeight = 1f }
        inner.addChild(weighted)
        outer.addChild(inner)

        harness.root = outer
        harness.frame()

        assertEquals(25f, weighted.measuredSize.height)
        assertEquals(25f, inner.measuredSize.height)
    }

    @Test
    fun `padding grows the parent and offsets the child`() {
        val padding = PaddingNode(id("padding"), Insets.all(6.dp))
        padding.addChild(box("child", 40f, 20f))
        harness.root = padding
        harness.frame()

        assertEquals(Size(52f, 32f), padding.measuredSize)
        assertEquals(Rect(6f, 6f, 40f, 20f), padding.children[0].bounds)
    }

    @Test
    fun `box overlays children at the chosen alignment`() {
        val box = BoxNode(id("box"), Alignment.Center)
        box.addChildren(box("backdrop", 100f, 50f), box("badge", 20f, 10f))
        harness.root = box
        harness.frame()

        assertEquals(Size(100f, 50f), box.measuredSize)
        assertEquals(Rect(0f, 0f, 100f, 50f), box.children[0].bounds)
        assertEquals(Rect(40f, 20f, 20f, 10f), box.children[1].bounds)
    }

    @Test
    fun `hidden children take no space and are not positioned`() {
        val column = ColumnNode(id("column"), spacing = 4.dp)
        val visible = box("visible", 10f, 10f)
        val hidden = box("hidden", 10f, 100f).apply { isVisible = false }
        val alsoVisible = box("also", 10f, 10f)
        column.addChildren(visible, hidden, alsoVisible)

        harness.root = column
        harness.frame()

        // Two visible children and one gap between them.
        assertEquals(24f, column.measuredSize.height)
        assertEquals(14f, alsoVisible.bounds.y)
    }

    @Test
    fun `fixed size node overrides the child's own measurement`() {
        val fixed = FixedSizeNode(id("fixed"), width = 64.dp, height = 16.dp)
        fixed.addChild(box("child", 200f, 200f))
        harness.root = fixed
        harness.frame()

        assertEquals(Size(64f, 16f), fixed.measuredSize)
    }

    @Test
    fun `text sizes itself from the measurer`() {
        val text = TextNode.of(id("label"), "Mining XP")
        harness.root = text
        harness.frame()

        // FakeTextMeasurer: 6 units per glyph, 9 units per line.
        assertEquals(Size(9 * 6f, 9f), text.measuredSize)
    }

    @Test
    fun `text wraps within the available width`() {
        val text = TextNode.of(id("label"), "one two three four five")
        text.maxLines = 4
        text.overflow = dev.th7bo.sidequest.ui.rendering.TextOverflow.WRAP

        val column = ColumnNode(id("column"))
        column.addChild(text)
        // 60 units of width is 10 glyphs per line.
        harness.runtime.viewport = Size(60f, 200f)
        harness.root = column
        harness.frame()

        assertEquals(3, text.measuredSize.height.toInt() / 9, "expected three wrapped lines")
    }

    @Test
    fun `a deeply nested tree resolves absolute bounds by accumulation`() {
        val outer = PaddingNode(id("outer"), Insets.all(10.dp))
        val middle = PaddingNode(id("middle"), Insets.all(5.dp))
        val leaf = box("leaf", 10f, 10f)
        middle.addChild(leaf)
        outer.addChild(middle)

        harness.root = outer
        harness.frame()

        assertEquals(Rect(15f, 15f, 10f, 10f), leaf.absoluteBounds())
    }

    @Test
    fun `fillCrossAxis stretches a child to the widest sibling`() {
        val column = ColumnNode(id("column"))
        val wide = box("wide", 80f, 10f)
        val bar = box("bar", 20f, 3f).apply { fillCrossAxis = true }
        column.addChildren(wide, bar)

        harness.root = column
        harness.frame()

        assertEquals(80f, bar.measuredSize.width, "bar should span the widest sibling")
        assertEquals(3f, bar.measuredSize.height, "stretching must not change the main axis")
        assertEquals(80f, column.measuredSize.width)
        assertEquals(13f, column.measuredSize.height)
    }

    @Test
    fun `fillCrossAxis leaves a child that is already the widest alone`() {
        val column = ColumnNode(id("column"))
        val bar = box("bar", 90f, 3f).apply { fillCrossAxis = true }
        column.addChildren(box("narrow", 40f, 10f), bar)

        harness.root = column
        harness.frame()

        assertEquals(90f, bar.measuredSize.width)
        assertEquals(90f, column.measuredSize.width)
    }

    @Test
    fun `fillCrossAxis works across the row axis too`() {
        val row = RowNode(id("row"))
        val tall = box("tall", 10f, 50f)
        val stripe = box("stripe", 4f, 8f).apply { fillCrossAxis = true }
        row.addChildren(tall, stripe)

        harness.root = row
        harness.frame()

        assertEquals(50f, stripe.measuredSize.height, "stripe should span the tallest sibling")
        assertEquals(4f, stripe.measuredSize.width)
    }
}
