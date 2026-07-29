package dev.th7bo.sidequest.ui.core.input

import dev.th7bo.sidequest.ui.core.UiTestHarness
import dev.th7bo.sidequest.ui.core.UiTestHarness.Companion.id
import dev.th7bo.sidequest.ui.core.content.SurfaceNode
import dev.th7bo.sidequest.ui.core.layout.BoxNode
import dev.th7bo.sidequest.ui.core.layout.ColumnNode
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.input.EventPhase
import dev.th7bo.sidequest.ui.input.PointerDownEvent
import dev.th7bo.sidequest.ui.input.PointerDragEvent
import dev.th7bo.sidequest.ui.input.PointerEvent
import dev.th7bo.sidequest.ui.input.ScrollEvent
import dev.th7bo.sidequest.ui.rendering.Transform
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

class InputTest {

    private lateinit var harness: UiTestHarness
    private lateinit var recorder: EventRecorder

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        harness = UiTestHarness(Size(400f, 300f))
        recorder = EventRecorder()
    }

    @AfterEach
    fun tearDown() {
        harness.dispose()
        resetReactiveGraphForTesting()
    }

    private fun surface(name: String, width: Float, height: Float) =
        SurfaceNode(id(name)).apply {
            preferredSize = Size(width, height)
            interactive = true
        }

    @Test
    fun `an event travels capture then target then bubble`() {
        val root = BoxNode(id("root")).apply { interactive = true }
        val middle = BoxNode(id("middle")).apply { interactive = true }
        val leaf = surface("leaf", 50f, 50f)
        middle.addChild(leaf)
        root.addChild(middle)

        root.onEvent = recorder.handler("root")
        middle.onEvent = recorder.handler("middle")
        leaf.onEvent = recorder.handler("leaf")

        harness.root = root
        harness.frame()

        harness.runtime.input.pointerPressed(Vec2(25f, 25f))

        assertEquals(
            listOf(
                "${EventPhase.CAPTURE}:root",
                "${EventPhase.CAPTURE}:middle",
                "${EventPhase.TARGET}:leaf",
                "${EventPhase.BUBBLE}:middle",
                "${EventPhase.BUBBLE}:root",
            ),
            recorder.trace(),
        )
    }

    @Test
    fun `consuming during capture stops the event reaching the target`() {
        val root = BoxNode(id("root")).apply { interactive = true }
        val leaf = surface("leaf", 50f, 50f)
        root.addChild(leaf)

        root.onEvent = recorder.consumingHandler("root")
        leaf.onEvent = recorder.handler("leaf")

        harness.root = root
        harness.frame()

        val consumed = harness.runtime.input.pointerPressed(Vec2(10f, 10f))

        assertTrue(consumed)
        assertEquals(listOf("${EventPhase.CAPTURE}:root"), recorder.trace())
    }

    @Test
    fun `consuming at the target stops the bubble phase`() {
        val root = BoxNode(id("root")).apply { interactive = true }
        val leaf = surface("leaf", 50f, 50f)
        root.addChild(leaf)

        root.onEvent = recorder.handler("root")
        leaf.onEvent = recorder.consumingHandler("leaf")

        harness.root = root
        harness.frame()
        harness.runtime.input.pointerPressed(Vec2(10f, 10f))

        assertEquals(
            listOf("${EventPhase.CAPTURE}:root", "${EventPhase.TARGET}:leaf"),
            recorder.trace(),
        )
    }

    @Test
    fun `the topmost overlapping child wins the hit`() {
        val box = BoxNode(id("root"))
        val below = surface("below", 100f, 100f)
        val above = surface("above", 100f, 100f)
        box.addChildren(below, above)

        harness.root = box
        harness.frame()

        val path = harness.runtime.input.hitTest(Vec2(50f, 50f))
        assertSame(above, path.last(), "the last-painted child must be hit first")
    }

    @Test
    fun `a click outside every node hits nothing`() {
        harness.root = surface("only", 20f, 20f)
        harness.frame()

        assertTrue(harness.runtime.input.hitTest(Vec2(300f, 300f)).isEmpty())
        assertFalse(harness.runtime.input.pointerPressed(Vec2(300f, 300f)))
    }

    @Test
    fun `a clipping container hides its children from hit testing`() {
        // A 100x20 viewport holding a 100x100 child: only the top slice is reachable.
        val clip = BoxNode(id("clip")).apply {
            clipsChildren = true
            interactive = true
            preferredSize = Size(100f, 20f)
        }
        clip.addChild(surface("child", 100f, 100f))

        harness.root = clip
        harness.frame()

        assertTrue(harness.runtime.input.hitTest(Vec2(50f, 10f)).isNotEmpty())
        assertTrue(
            harness.runtime.input.hitTest(Vec2(50f, 90f)).isEmpty(),
            "a point outside the clip must not hit the clipped child",
        )
    }

    @Test
    fun `hit testing a scaled subtree transforms the pointer correctly`() {
        val root = BoxNode(id("root"))
        val scaled = BoxNode(id("scaled")).apply {
            interactive = true
            transform = Transform.scale(2f)
        }
        val leaf = surface("leaf", 20f, 20f)
        scaled.addChild(leaf)
        root.addChild(scaled)

        harness.root = root
        harness.frame()

        // The leaf is 20x20 in local space, drawn at 2x, so it covers 0..40 on screen.
        assertSame(leaf, harness.runtime.input.hitTest(Vec2(30f, 30f)).lastOrNull())
        assertNull(
            harness.runtime.input.hitTest(Vec2(50f, 50f)).lastOrNull(),
            "a point beyond the scaled bounds must miss",
        )
    }

    @Test
    fun `a handler in a scaled subtree receives the position in its own space`() {
        val root = BoxNode(id("root"))
        val scaled = BoxNode(id("scaled")).apply { transform = Transform.scale(2f) }
        val leaf = surface("leaf", 20f, 20f)
        scaled.addChild(leaf)
        root.addChild(scaled)

        var localPosition: Vec2? = null
        leaf.onEvent = { event ->
            if (event is PointerDownEvent) localPosition = event.position
        }

        harness.root = root
        harness.frame()
        harness.runtime.input.pointerPressed(Vec2(30f, 30f))

        // 30 screen units inside a 2x scale is 15 local units.
        assertEquals(Vec2(15f, 15f), localPosition)
    }

    @Test
    fun `each node on the path sees the pointer in its own coordinates`() {
        val root = BoxNode(id("root")).apply { interactive = true }
        val inner = BoxNode(id("inner")).apply { interactive = true }
        val leaf = surface("leaf", 40f, 40f)
        inner.addChild(leaf)
        root.addChild(inner)

        val positions = LinkedHashMap<String, Vec2>()
        root.onEvent = { if (it is PointerEvent) positions["root"] = it.position }
        leaf.onEvent = { if (it is PointerEvent) positions["leaf"] = it.position }

        // Push the inner box away from the origin so the spaces genuinely differ.
        harness.root = root
        harness.frame()
        inner.bounds = inner.bounds.withPosition(Vec2(10f, 10f))

        harness.runtime.input.pointerPressed(Vec2(25f, 25f))

        assertEquals(Vec2(25f, 25f), positions["root"])
        assertEquals(Vec2(15f, 15f), positions["leaf"])
    }

    @Test
    fun `hover enter and exit fire as the pointer crosses nodes`() {
        val column = ColumnNode(id("root"))
        val first = surface("first", 100f, 20f)
        val second = surface("second", 100f, 20f)
        column.addChildren(first, second)

        harness.root = column
        harness.frame()

        harness.runtime.input.pointerMoved(Vec2(10f, 5f))
        assertTrue(first.isHovered)
        assertFalse(second.isHovered)

        harness.runtime.input.pointerMoved(Vec2(10f, 25f))
        assertFalse(first.isHovered, "leaving a node must clear its hover state")
        assertTrue(second.isHovered)

        harness.runtime.input.pointerMoved(Vec2(300f, 300f))
        assertFalse(second.isHovered)
    }

    @Test
    fun `pointer capture keeps drag events on the pressed node`() {
        val column = ColumnNode(id("root"))
        val knob = surface("knob", 100f, 20f).apply { capturesPointer = true }
        val other = surface("other", 100f, 20f)
        column.addChildren(knob, other)

        val drags = ArrayList<Vec2>()
        knob.onEvent = { if (it is PointerDragEvent) drags.add(it.position) }
        other.onEvent = recorder.handler("other")

        harness.root = column
        harness.frame()

        harness.runtime.input.pointerPressed(Vec2(10f, 10f))
        assertSame(knob, harness.runtime.input.pointerCaptureNode)

        // Drag well past the knob and over its sibling.
        harness.runtime.input.pointerMoved(Vec2(10f, 30f))
        harness.runtime.input.pointerMoved(Vec2(10f, 200f))

        assertEquals(2, drags.size, "the capturing node must keep receiving moves")
        assertTrue(recorder.events.isEmpty(), "the sibling must receive nothing during capture")

        harness.runtime.input.pointerReleased(Vec2(10f, 200f))
        assertNull(harness.runtime.input.pointerCaptureNode)
    }

    @Test
    fun `scroll is delivered to the node under the pointer`() {
        val column = ColumnNode(id("root"))
        val target = surface("target", 100f, 40f)
        column.addChild(target)

        var scrolled = 0f
        target.onEvent = { if (it is ScrollEvent) scrolled += it.scrollY }

        harness.root = column
        harness.frame()
        harness.runtime.input.scrolled(Vec2(10f, 10f), scrollX = 0f, scrollY = -3f)

        assertEquals(-3f, scrolled)
    }

    @Test
    fun `resetting the dispatcher clears hover and capture`() {
        val knob = surface("knob", 100f, 20f).apply { capturesPointer = true }
        harness.root = knob
        harness.frame()

        harness.runtime.input.pointerMoved(Vec2(10f, 10f))
        harness.runtime.input.pointerPressed(Vec2(10f, 10f))
        assertTrue(knob.isHovered)

        harness.runtime.input.reset()

        assertFalse(knob.isHovered)
        assertNull(harness.runtime.input.pointerCaptureNode)
    }
}
