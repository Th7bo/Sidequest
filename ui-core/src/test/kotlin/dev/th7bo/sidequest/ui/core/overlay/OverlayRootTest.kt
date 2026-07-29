package dev.th7bo.sidequest.ui.core.overlay

import dev.th7bo.sidequest.ui.core.UiTestHarness
import dev.th7bo.sidequest.ui.core.UiTestHarness.Companion.id
import dev.th7bo.sidequest.ui.core.content.SurfaceNode
import dev.th7bo.sidequest.ui.core.layout.BoxNode
import dev.th7bo.sidequest.ui.core.layout.ColumnNode
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The overlay layer exists so a popup can escape its parent's clip and paint above its
 * siblings. Every test here is about one of those two properties, or about how a popup
 * goes away.
 */
class OverlayRootTest {

    private lateinit var harness: UiTestHarness
    private lateinit var root: OverlayRootNode
    private lateinit var anchor: SurfaceNode

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        harness = UiTestHarness(Size(300f, 200f))

        // A clipping container, exactly the situation a popup has to escape.
        anchor = SurfaceNode(id("anchor")).apply {
            preferredSize = Size(60f, 20f)
            interactive = true
            // Visible, so paint-order assertions have something to compare against.
            color = ANCHOR_COLOR
        }
        val clipped = BoxNode(id("clipped")).apply {
            clipsChildren = true
            preferredSize = Size(120f, 40f)
            addChild(ColumnNode(id("rows")).apply { addChild(anchor) })
        }
        root = OverlayRootNode(id("root"), clipped)
        harness.root = root
        harness.frame()
    }

    @AfterEach
    fun tearDown() {
        harness.dispose()
        resetReactiveGraphForTesting()
    }

    private fun popup(width: Float = 80f, height: Float = 60f): SurfaceNode =
        SurfaceNode(id("popup")).apply {
            preferredSize = Size(width, height)
            interactive = true
            color = Color.White
        }

    @Test
    fun `nothing is showing until something is shown`() {
        assertFalse(root.isShowingOverlay)
    }

    @Test
    fun `a popup escapes its anchor's clipping parent`() {
        val content = popup(height = 120f)
        root.show("dropdown", anchor, content)
        harness.frame()

        // The clip is only 40 units tall; the popup is 120 and starts below it.
        val bounds = content.absoluteBounds()
        assertTrue(bounds.bottom > 40f, "the popup must extend past the clipping parent")

        // And it is reachable, which a clipped child would not be.
        val hit = harness.runtime.input.hitTest(bounds.center)
        assertSame(content, hit.last())
    }

    @Test
    fun `a popup is hit tested before the content underneath it`() {
        val content = popup()
        root.show("dropdown", anchor, content)
        harness.frame()

        // A point over both the popup and the anchor must reach the popup.
        val overlap = content.absoluteBounds().center
        assertSame(content, harness.runtime.input.hitTest(overlap).last())
    }

    @Test
    fun `a popup paints after the content underneath it`() {
        val content = popup()
        root.show("dropdown", anchor, content)
        harness.frame()

        val fills = harness.renderer.commands
            .filterIsInstance<dev.th7bo.sidequest.ui.testkit.DrawCommand.FillRect>()

        val anchorIndex = fills.indexOfFirst { it.color == ANCHOR_COLOR }
        val popupIndex = fills.indexOfFirst { it.color == Color.White }

        assertTrue(anchorIndex >= 0, "the anchor should have been drawn")
        assertTrue(popupIndex >= 0, "the popup should have been drawn")
        assertTrue(popupIndex > anchorIndex, "the popup must be drawn after the content beneath it")
    }

    @Test
    fun `a popup is placed below its anchor by default`() {
        val content = popup()
        root.show("dropdown", anchor, content)
        harness.frame()

        assertTrue(
            content.absoluteBounds().y >= anchor.absoluteBounds().bottom,
            "the default placement is below the anchor",
        )
    }

    @Test
    fun `a popup that would not fit below flips above`() {
        // Put the anchor near the bottom, leaving no room underneath.
        anchor.bounds = anchor.bounds.withPosition(Vec2(0f, 180f))
        val content = popup(height = 80f)
        root.show("dropdown", anchor, content, OverlayPlacement.BELOW_START)
        harness.frame()

        assertTrue(
            content.absoluteBounds().bottom <= 200f,
            "a popup must not run off the bottom of the screen",
        )
    }

    @Test
    fun `a popup is clamped into the viewport rather than hanging off the side`() {
        anchor.bounds = anchor.bounds.withPosition(Vec2(280f, 0f))
        val content = popup(width = 100f)
        root.show("dropdown", anchor, content)
        harness.frame()

        val bounds = content.absoluteBounds()
        assertTrue(bounds.right <= 300f, "clamped to the right edge, was ${bounds.right}")
        assertTrue(bounds.x >= 0f)
    }

    @Test
    fun `end alignment lines the popup up with the anchor's right edge`() {
        anchor.bounds = anchor.bounds.withPosition(Vec2(100f, 0f))
        val content = popup(width = 40f)
        root.show("dropdown", anchor, content, OverlayPlacement.BELOW_END)
        harness.frame()

        assertEquals(anchor.absoluteBounds().right, content.absoluteBounds().right, 0.5f)
    }

    @Test
    fun `clicking outside dismisses and the click does not fall through`() {
        val content = popup()
        var underlyingClicks = 0
        anchor.onEvent = { underlyingClicks++ }

        root.show("dropdown", anchor, content)
        harness.frame()

        // Somewhere over neither the popup nor the anchor.
        harness.runtime.input.pointerPressed(Vec2(290f, 190f))

        assertFalse(root.isShowingOverlay)
        assertEquals(0, underlyingClicks, "the dismissing press must not also activate anything")
    }

    @Test
    fun `clicking inside the popup does not dismiss it`() {
        val content = popup()
        var chosen = 0
        content.onEvent = { chosen++ }

        root.show("dropdown", anchor, content)
        harness.frame()
        harness.runtime.input.pointerPressed(content.absoluteBounds().center)

        assertTrue(root.isShowingOverlay, "a press on the popup must not close it")
        assertTrue(chosen > 0)
    }

    @Test
    fun `a popup with outside-click dismissal disabled stays open`() {
        root.show("dropdown", anchor, popup(), dismissOnOutsideClick = false)
        harness.frame()

        harness.runtime.input.pointerPressed(Vec2(290f, 190f))

        assertTrue(root.isShowingOverlay)
    }

    @Test
    fun `escape closes the popup`() {
        root.show("dropdown", anchor, popup())
        harness.frame()
        harness.runtime.focus.requestFocus(anchor.also { it.focusable = true })

        harness.runtime.input.keyPressed(Key.ESCAPE)

        assertFalse(root.isShowingOverlay)
    }

    @Test
    fun `escape closes the topmost popup only`() {
        root.show("first", anchor, popup())
        root.show("second", anchor, popup())
        harness.frame()
        anchor.focusable = true
        harness.runtime.focus.requestFocus(anchor)

        harness.runtime.input.keyPressed(Key.ESCAPE)

        assertTrue(root.isShowingOverlay, "only one closes at a time")
    }

    @Test
    fun `showing the same key twice replaces rather than stacks`() {
        val first = popup()
        val second = popup()
        root.show("dropdown", anchor, first)
        root.show("dropdown", anchor, second)
        harness.frame()

        assertTrue(root.isShowingOverlay)
        // The replaced popup was disposed and detached.
        assertEquals(null, first.parent)
        assertSame(root, second.parent)
    }

    @Test
    fun `dismissal notifies the opener so it can update its own state`() {
        var dismissals = 0
        root.show("dropdown", anchor, popup(), onDismiss = { dismissals++ })
        harness.frame()

        root.dismiss("dropdown")

        assertEquals(1, dismissals)
        assertFalse(root.isShowingOverlay)
    }

    @Test
    fun `the handle reports and controls its own overlay`() {
        val handle = root.show("dropdown", anchor, popup())
        harness.frame()

        assertTrue(handle.isShowing)
        handle.dismiss()
        assertFalse(handle.isShowing)
        assertFalse(root.isShowingOverlay)
        // Dismissing twice is harmless.
        handle.dismiss()
    }

    @Test
    fun `dismissAll reports whether it did anything`() {
        assertFalse(root.dismissAll())

        root.show("a", anchor, popup())
        root.show("b", anchor, popup())
        assertTrue(root.dismissAll())
        assertFalse(root.isShowingOverlay)
    }

    @Test
    fun `the root is only a hit-test target while something is open`() {
        assertFalse(root.interactive)

        root.show("dropdown", anchor, popup())
        assertTrue(root.interactive, "it must catch outside clicks while open")

        root.dismiss("dropdown")
        assertFalse(root.interactive, "and get out of the way once closed")
    }

    @Test
    fun `content keeps working normally with no overlay open`() {
        var clicks = 0
        anchor.onEvent = { clicks++ }

        harness.runtime.input.pointerPressed(anchor.absoluteBounds().center)

        assertTrue(clicks > 0, "the overlay root must not intercept ordinary input")
    }

    @Test
    fun `an idle screen with a popup open still does no layout work`() {
        root.show("dropdown", anchor, popup())
        harness.frames(2)

        val idle = harness.frame()
        assertEquals(0, idle.nodesMeasured)
        assertEquals(0, idle.nodesArranged)
    }

    @Test
    fun `disposing the root releases its overlays`() {
        val content = popup()
        root.show("dropdown", anchor, content)
        harness.frame()

        harness.runtime.root = null

        assertTrue(content.scope.isDisposed)
    }

    // ---------------------------------------------------------------
    // A popup must not outlive the control that opened it.
    // ---------------------------------------------------------------

    @Test
    fun `a popup is dismissed when its anchor leaves the tree`() {
        val content = popup()
        root.show("dropdown", anchor, content)
        harness.frame()
        assertTrue(root.isShowingOverlay)

        // Exactly what switching category does: the virtualized list recycles the row
        // out of the tree. The anchor is never disposed, so lifetime alone would not
        // catch this — and the popup would be left floating at a stale position over
        // whatever content replaced it.
        val rows = anchor.parent!!
        rows.removeChild(anchor)
        harness.frame()

        assertFalse(root.isShowingOverlay, "a popup whose anchor left the tree must go too")
        assertTrue(content.scope.isDisposed, "and the popup's own scope must be released")
    }

    @Test
    fun `a detached anchor dismissal notifies the opener`() {
        var dismissed = false
        root.show("dropdown", anchor, popup(), onDismiss = { dismissed = true })
        harness.frame()

        anchor.parent!!.removeChild(anchor)
        harness.frame()

        assertTrue(dismissed, "the control must learn its popup closed so it can reset")
    }

    @Test
    fun `a popup is dismissed when its anchor is disposed`() {
        val content = popup()
        root.show("dropdown", anchor, content)
        harness.frame()

        // Disposal without detaching: `clearChildren` on an ancestor does both, but a
        // control disposed in place must take its popup down just the same.
        anchor.dispose()

        assertFalse(root.isShowingOverlay, "disposing the anchor must dismiss its popup")
        assertTrue(content.scope.isDisposed)
    }

    @Test
    fun `an unrelated anchor's popup survives`() {
        val other = SurfaceNode(id("other")).apply { preferredSize = Size(20f, 10f) }
        anchor.parent!!.addChild(other)
        harness.frame()

        root.show("kept", other, popup())
        harness.frame()

        anchor.parent!!.removeChild(anchor)
        harness.frame()

        assertTrue(root.isShowingOverlay, "only the detached anchor's popup should close")
    }

    @Test
    fun `dismissing normally releases the anchor registration`() {
        val before = anchor.scope.size

        repeat(3) {
            root.show("dropdown", anchor, popup())
            harness.frame()
            assertTrue(root.dismiss("dropdown"))
            harness.frame()
        }

        // Opening and closing repeatedly must not pile registrations onto the anchor:
        // a control the user clicks a hundred times would otherwise leak a hundred.
        assertEquals(before, anchor.scope.size, "anchor scope should not accumulate")
    }

    private companion object {
        val ANCHOR_COLOR: Color = Color.parse("#FF102030")
    }
}
