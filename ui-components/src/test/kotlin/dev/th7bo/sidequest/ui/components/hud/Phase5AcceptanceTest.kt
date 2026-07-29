package dev.th7bo.sidequest.ui.components.hud

import dev.th7bo.sidequest.ui.components.Icons
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.hud.HudElementNode
import dev.th7bo.sidequest.ui.core.hud.HudLayerNode
import dev.th7bo.sidequest.ui.core.hud.editor.GuideAxis
import dev.th7bo.sidequest.ui.core.hud.editor.GuideKind
import dev.th7bo.sidequest.ui.core.hud.editor.HudEditorSession
import dev.th7bo.sidequest.ui.core.hud.editor.HudHandle
import dev.th7bo.sidequest.ui.core.hud.editor.SafeArea
import dev.th7bo.sidequest.ui.core.hud.editor.SnapEngine
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.geometry.Anchor
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.hud.HudDefinition
import dev.th7bo.sidequest.ui.hud.HudInstance
import dev.th7bo.sidequest.ui.hud.HudResizeMode
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.MutableUiState
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.abs

/**
 * The Phase 5 acceptance criteria:
 *
 * - drag cursor alignment stays correct at every scale,
 * - multi-selection transformations are reversible,
 * - placement survives resolution and GUI-scale changes.
 *
 * The session holds no rendering state, so all of this is assertable without a renderer
 * or a running game — which is the point of keeping the editor's arithmetic separate
 * from its chrome.
 */
class Phase5AcceptanceTest {

    private fun id(path: String) = UiId.of("sidequest", path)

    private lateinit var runtime: UiRuntime
    private lateinit var renderer: RecordingRenderer
    private lateinit var context: ComponentContext
    private lateinit var layer: HudLayerNode
    private lateinit var session: HudEditorSession

    private var screen = Size(640f, 360f)

    private lateinit var currentXp: MutableUiState<Long>

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        screen = Size(640f, 360f)
        renderer = RecordingRenderer(screen, FakeTextMeasurer())
        runtime = UiRuntime(DarkTheme).apply {
            viewport = screen
            prepare(renderer)
        }
        context = ComponentContext(DarkTheme, runtime.animations, runtime)
        currentXp = mutableStateOf(28_450L, "currentXp")

        layer = HudLayerNode(id("hud_layer")) { screen }
        runtime.root = layer
        session = HudEditorSession(layer, { screen })
    }

    @AfterEach
    fun tearDown() {
        runtime.dispose()
        resetReactiveGraphForTesting()
    }

    private fun frame(delta: Float = 1f / 60f) = run {
        renderer.beginFrame(delta)
        val metrics = runtime.frame(renderer, delta)
        renderer.endFrame()
        metrics
    }

    private fun hud(
        name: String,
        anchor: Anchor = Anchor.TOP_LEFT,
        offset: Vec2 = Vec2.Zero,
        resizeMode: HudResizeMode = HudResizeMode.SCALE_ONLY,
    ): HudElementNode {
        val definition = HudDefinition(
            id = id("hud.$name"),
            title = constantState(name),
            icon = Icons.gear,
            defaultAnchor = anchor,
            defaultOffset = offset,
            scaleRange = 0.25f..4f,
            resizeMode = resizeMode,
        )
        val instance = HudInstance(id("instance.$name"), definition.id)
        val content = ProgressHudNode(
            id = id("$name.content"),
            componentContext = context,
            title = constantState(name),
            current = currentXp,
            maximum = constantState(60_000L),
            subtitle = constantState("Lv. 42"),
            icon = Icons.gear,
        )
        val element = HudElementNode(instance, definition) { content }
        layer.add(element)
        return element
    }

    private fun assertClose(expected: Float, actual: Float, message: String = "") {
        assertTrue(abs(expected - actual) < TOLERANCE) { "$message: expected $expected but was $actual" }
    }

    // ---------------------------------------------------------------
    // Criterion 1: drag cursor alignment at every scale
    // ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(floats = [0.5f, 1f, 1.5f, 2f, 3.5f])
    fun `the cursor keeps its grip on the element at any scale`(scale: Float) {
        val element = hud("mining", offset = Vec2(50f, 50f))
        element.rescale(scale)
        frame()
        session.select(element.instance.instanceId)

        // Grab a point three quarters across and a third down the element.
        val start = session.screenBounds(element)
        val grip = Vec2(start.x + start.width * 0.75f, start.y + start.height / 3f)
        val gripFraction = Vec2(
            (grip.x - start.x) / start.width,
            (grip.y - start.y) / start.height,
        )

        session.snapping.threshold = 0f
        assertTrue(session.beginDrag(grip))

        for (target in listOf(Vec2(200f, 120f), Vec2(340f, 210f), Vec2(90f, 60f))) {
            session.updateDrag(target)
            frame()

            val bounds = session.screenBounds(element)
            // The same fraction of the element must still be under the cursor. If the
            // gesture divided or multiplied by the element scale anywhere, this drifts
            // proportionally to the scale — which is exactly the bug being guarded.
            assertClose(target.x, bounds.x + bounds.width * gripFraction.x, "grip x at ${scale}x")
            assertClose(target.y, bounds.y + bounds.height * gripFraction.y, "grip y at ${scale}x")
        }
        session.endGesture()
    }

    @Test
    fun `a drag moves the element exactly as far as the pointer travelled`() {
        val element = hud("mining", offset = Vec2(40f, 40f))
        element.rescale(2.5f)
        frame()
        session.select(element.instance.instanceId)
        session.snapping.threshold = 0f

        val before = session.screenBounds(element).position
        val from = Vec2(100f, 100f)
        session.beginDrag(from)
        session.updateDrag(from + Vec2(37f, -23f))
        frame()
        session.endGesture()

        val after = session.screenBounds(element).position
        assertClose(37f, after.x - before.x, "horizontal travel")
        assertClose(-23f, after.y - before.y, "vertical travel")
    }

    @ParameterizedTest
    @EnumSource(Anchor::class)
    fun `dragging works from every anchor`(anchor: Anchor) {
        val element = hud("mining", anchor = anchor)
        frame()
        session.select(element.instance.instanceId)
        session.snapping.threshold = 0f

        val start = session.screenBounds(element)
        val grip = start.center
        session.beginDrag(grip)
        session.updateDrag(Vec2(300f, 180f))
        frame()
        session.endGesture()

        val bounds = session.screenBounds(element)
        assertClose(300f, bounds.center.x, "centre x under cursor for $anchor")
        assertClose(180f, bounds.center.y, "centre y under cursor for $anchor")
    }

    // ---------------------------------------------------------------
    // Criterion 2: multi-selection transformations are reversible
    // ---------------------------------------------------------------

    @Test
    fun `undoing a multi-selection drag restores every element at once`() {
        val a = hud("a", offset = Vec2(20f, 20f))
        val b = hud("b", offset = Vec2(200f, 20f))
        val c = hud("c", offset = Vec2(20f, 200f))
        frame()

        val before = listOf(a, b, c).map { session.screenBounds(it) }

        session.selectAll()
        session.snapping.threshold = 0f
        session.beginDrag(session.screenBounds(a).center)
        session.updateDrag(session.screenBounds(a).center + Vec2(60f, 45f))
        frame()
        session.endGesture()

        val moved = listOf(a, b, c).map { session.screenBounds(it) }
        for (index in moved.indices) {
            assertClose(60f, moved[index].x - before[index].x, "element $index moved together")
            assertClose(45f, moved[index].y - before[index].y, "element $index moved together")
        }

        // One entry for the whole gesture, not one per element and not one per frame.
        assertEquals(1, session.undo.undoCount, "a drag is one undo entry")

        session.undo.undo()
        frame()
        val restored = listOf(a, b, c).map { session.screenBounds(it) }
        for (index in restored.indices) {
            assertClose(before[index].x, restored[index].x, "element $index restored")
            assertClose(before[index].y, restored[index].y, "element $index restored")
        }
    }

    @Test
    fun `redo reapplies a multi-selection drag`() {
        val a = hud("a", offset = Vec2(20f, 20f))
        val b = hud("b", offset = Vec2(200f, 20f))
        frame()

        session.selectAll()
        session.snapping.threshold = 0f
        session.beginDrag(session.screenBounds(a).center)
        session.updateDrag(session.screenBounds(a).center + Vec2(30f, 30f))
        frame()
        session.endGesture()

        val moved = listOf(a, b).map { session.screenBounds(it) }

        session.undo.undo()
        frame()
        session.undo.redo()
        frame()

        val again = listOf(a, b).map { session.screenBounds(it) }
        for (index in again.indices) {
            assertClose(moved[index].x, again[index].x, "element $index reapplied")
            assertClose(moved[index].y, again[index].y, "element $index reapplied")
        }
    }

    @Test
    fun `a multi-selection nudge is one reversible entry`() {
        val a = hud("a", offset = Vec2(20f, 20f))
        val b = hud("b", offset = Vec2(200f, 20f))
        frame()
        session.selectAll()

        val before = listOf(a, b).map { session.screenBounds(it).position }
        session.nudge(Vec2(4f, 0f))
        frame()

        assertEquals(1, session.undo.undoCount)
        session.undo.undo()
        frame()

        val after = listOf(a, b).map { session.screenBounds(it).position }
        assertEquals(before, after, "the nudge must reverse for both elements")
    }

    @Test
    fun `cancelling a gesture restores the starting placements`() {
        val element = hud("mining", offset = Vec2(30f, 30f))
        frame()
        session.select(element.instance.instanceId)
        val before = session.screenBounds(element).position

        session.beginDrag(session.screenBounds(element).center)
        session.updateDrag(Vec2(400f, 300f))
        frame()
        session.cancelGesture()
        frame()

        val after = session.screenBounds(element).position
        assertClose(before.x, after.x, "cancel restores x")
        assertClose(before.y, after.y, "cancel restores y")
        assertFalse(session.undo.canUndo.peek(), "a cancelled gesture leaves no undo entry")
    }

    @Test
    fun `scaling is reversible`() {
        val element = hud("mining", offset = Vec2(40f, 40f))
        frame()
        session.select(element.instance.instanceId)
        val before = element.placement.peek().scale

        session.setScale(2f)
        frame()
        assertClose(2f, element.placement.peek().scale)

        session.undo.undo()
        frame()
        assertClose(before, element.placement.peek().scale)
    }

    // ---------------------------------------------------------------
    // Criterion 3: placement survives resolution and GUI-scale changes
    // ---------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Anchor::class)
    fun `a dragged placement keeps its edge distance across resolutions`(anchor: Anchor) {
        val element = hud("mining", anchor = anchor)
        frame()
        session.select(element.instance.instanceId)
        session.snapping.threshold = 0f

        session.beginDrag(session.screenBounds(element).center)
        session.updateDrag(Vec2(220f, 150f))
        frame()
        session.endGesture()

        val placement = element.placement.peek()
        val size = element.scaledSize
        val original = placement.resolve(size, screen)
        val gap = gapToAnchor(anchor, original, size, screen)

        for (other in listOf(Size(1920f, 1080f), Size(854f, 480f), Size(320f, 240f))) {
            val moved = placement.resolve(size, other)
            val otherGap = gapToAnchor(anchor, moved, size, other)
            assertClose(gap.x, otherGap.x, "horizontal gap on $other for $anchor")
            assertClose(gap.y, otherGap.y, "vertical gap on $other for $anchor")
        }
    }

    @Test
    fun `a gui-scale change preserves the edited placement`() {
        val element = hud("mining", anchor = Anchor.BOTTOM_RIGHT)
        frame()
        session.select(element.instance.instanceId)
        session.snapping.threshold = 0f
        session.beginDrag(session.screenBounds(element).center)
        session.updateDrag(Vec2(500f, 300f))
        frame()
        session.endGesture()

        val placement = element.placement.peek()
        val size = element.scaledSize

        // Halving the GUI scale doubles the logical viewport.
        val doubled = Size(screen.width * 2f, screen.height * 2f)
        val before = placement.resolve(size, screen)
        val after = placement.resolve(size, doubled)

        assertClose(screen.width - before.right(size), doubled.width - after.right(size), "right gap")
        assertClose(screen.height - before.bottom(size), doubled.height - after.bottom(size), "bottom gap")
    }

    private fun Vec2.right(size: Size) = x + size.width
    private fun Vec2.bottom(size: Size) = y + size.height

    private fun gapToAnchor(anchor: Anchor, position: Vec2, size: Size, screenSize: Size): Vec2 {
        val x = when (anchor.horizontalFactor) {
            0f -> position.x
            1f -> screenSize.width - (position.x + size.width)
            else -> position.x + size.width / 2f - screenSize.width / 2f
        }
        val y = when (anchor.verticalFactor) {
            0f -> position.y
            1f -> screenSize.height - (position.y + size.height)
            else -> position.y + size.height / 2f - screenSize.height / 2f
        }
        return Vec2(x, y)
    }

    // ---------------------------------------------------------------
    // Preview data
    // ---------------------------------------------------------------

    @Test
    fun `content sees the editing flag, and gets it back when the editor closes`() {
        val element = hud("mining")
        frame()
        assertFalse(element.isEditing.peek(), "not editing until the editor says so")

        session.setEditing(true)
        assertTrue(element.isEditing.peek())

        session.setEditing(false)
        assertFalse(element.isEditing.peek(), "live data must come back when the editor closes")
    }

    @Test
    fun `previewed swaps to the sample only while editing`() {
        val live = mutableStateOf(0L, "live")
        val editing = mutableStateOf(false, "editing")
        val shown = dev.th7bo.sidequest.ui.hud.previewed(live, 28_450L, editing, "test.preview")

        assertEquals(0L, shown.peek(), "live data outside the editor")

        editing.value = true
        assertEquals(28_450L, shown.peek(), "a sample while editing, so the card is legible")

        live.value = 999L
        assertEquals(28_450L, shown.peek(), "the sample wins while editing")

        editing.value = false
        assertEquals(999L, shown.peek(), "and the live value comes back, up to date")
    }

    @Test
    fun `the selection flag reaches the element`() {
        val a = hud("a")
        val b = hud("b")
        frame()

        session.select(a.instance.instanceId)
        assertTrue(a.isSelected.peek())
        assertFalse(b.isSelected.peek())

        session.select(b.instance.instanceId)
        assertFalse(a.isSelected.peek(), "selecting b must deselect a")
        assertTrue(b.isSelected.peek())

        session.clearSelection()
        assertFalse(b.isSelected.peek())
    }

    @Test
    fun `closing the editor also clears the selection`() {
        val element = hud("mining")
        frame()
        session.setEditing(true)
        session.select(element.instance.instanceId)

        session.setEditing(false)

        assertTrue(session.selection.peek().isEmpty())
        assertFalse(element.isSelected.peek(), "a stale selection would outline a HUD during play")
    }

    // ---------------------------------------------------------------
    // Selection
    // ---------------------------------------------------------------

    @Test
    fun `clicking selects the topmost element under the pointer`() {
        val below = hud("below", offset = Vec2(40f, 40f))
        val above = hud("above", offset = Vec2(45f, 45f))
        above.update { it.copy(zIndex = 5) }
        frame()

        val overlap = session.screenBounds(above).position + Vec2(4f, 4f)
        assertSame(above, session.selectAt(overlap), "z-order decides which element is picked")
    }

    @Test
    fun `clicking empty space clears the selection`() {
        val element = hud("mining", offset = Vec2(20f, 20f))
        frame()
        session.select(element.instance.instanceId)

        assertNull(session.selectAt(Vec2(600f, 340f)))
        assertTrue(session.selection.peek().isEmpty())
    }

    @Test
    fun `shift-clicking adds to and removes from the selection`() {
        val a = hud("a", offset = Vec2(20f, 20f))
        val b = hud("b", offset = Vec2(220f, 20f))
        frame()

        session.select(a.instance.instanceId)
        session.select(b.instance.instanceId, additive = true)
        assertEquals(2, session.selection.peek().size)

        session.select(b.instance.instanceId, additive = true)
        assertEquals(setOf(a.instance.instanceId), session.selection.peek(), "shift toggles")
    }

    @Test
    fun `a marquee selects everything whose centre it covers`() {
        val a = hud("a", offset = Vec2(10f, 10f))
        val b = hud("b", offset = Vec2(400f, 300f))
        frame()

        session.selectWithin(Rect(0f, 0f, 200f, 120f))
        assertEquals(setOf(a.instance.instanceId), session.selection.peek())
        assertFalse(session.isSelected(b.instance.instanceId))
    }

    // ---------------------------------------------------------------
    // Locking, z-order
    // ---------------------------------------------------------------

    @Test
    fun `a locked element cannot be dragged`() {
        val element = hud("mining", offset = Vec2(30f, 30f))
        frame()
        session.select(element.instance.instanceId)
        session.setLocked(true)

        val before = session.screenBounds(element).position
        assertFalse(session.beginDrag(before + Vec2(5f, 5f)), "a locked element refuses a drag")

        session.updateDrag(Vec2(400f, 300f))
        frame()
        assertEquals(before, session.screenBounds(element).position)
    }

    @Test
    fun `a locked element can still be unlocked`() {
        val element = hud("mining")
        frame()
        session.select(element.instance.instanceId)
        session.setLocked(true)
        assertTrue(element.placement.peek().locked)

        session.toggleLocked()
        assertFalse(element.placement.peek().locked, "locking must not be a one-way door")
    }

    @Test
    fun `bring to front and send to back reorder the layer`() {
        val a = hud("a")
        val b = hud("b")
        frame()

        session.select(a.instance.instanceId)
        session.bringToFront()
        assertSame(a, layer.ordered.last(), "a should now be on top")

        session.select(b.instance.instanceId)
        session.bringToFront()
        assertSame(b, layer.ordered.last())

        session.select(b.instance.instanceId)
        session.sendToBack()
        assertSame(b, layer.ordered.first())
    }

    @Test
    fun `z-order changes are reversible`() {
        val a = hud("a")
        hud("b")
        frame()

        session.select(a.instance.instanceId)
        val before = a.placement.peek().zIndex
        session.bringToFront()
        session.undo.undo()

        assertEquals(before, a.placement.peek().zIndex)
    }

    // ---------------------------------------------------------------
    // Snapping, guides, safe areas
    // ---------------------------------------------------------------

    @Test
    fun `dragging near the screen centre snaps and reports a guide`() {
        val element = hud("mining", offset = Vec2(10f, 10f))
        frame()
        session.select(element.instance.instanceId)
        session.snapping.threshold = 6f

        val size = element.scaledSize
        // Aim the element's centre three units left of the screen's centre line.
        val target = Vec2(screen.width / 2f - 3f, screen.height / 2f)
        session.beginDrag(session.screenBounds(element).center)
        session.updateDrag(target)
        frame()

        val bounds = session.screenBounds(element)
        assertClose(screen.width / 2f, bounds.center.x, "should snap to the centre line")

        val guides = session.guides.peek()
        assertTrue(
            guides.any { it.axis == GuideAxis.VERTICAL && it.kind == GuideKind.CENTER },
            "a snap must explain itself with a guide, got $guides",
        )
        session.endGesture()
        assertTrue(session.guides.peek().isEmpty(), "guides clear when the gesture ends")
    }

    @Test
    fun `snapping can be suspended for the gesture`() {
        val element = hud("mining", offset = Vec2(10f, 10f))
        frame()
        session.select(element.instance.instanceId)
        session.snapping.threshold = 6f

        val target = Vec2(screen.width / 2f - 3f, screen.height / 2f)
        session.beginDrag(session.screenBounds(element).center)
        session.updateDrag(target, snap = false)
        frame()

        assertClose(target.x, session.screenBounds(element).center.x, "unsnapped drag lands where asked")
        assertTrue(session.guides.peek().isEmpty())
    }

    @Test
    fun `an element snaps to another element's edge`() {
        val fixed = hud("fixed", offset = Vec2(300f, 40f))
        val moving = hud("moving", offset = Vec2(20f, 200f))
        frame()

        session.select(moving.instance.instanceId)
        session.snapping.threshold = 6f

        val fixedBounds = session.screenBounds(fixed)
        val movingSize = moving.scaledSize
        // Aim the moving element's left edge two units off the fixed element's left edge.
        val target = Vec2(fixedBounds.x + 2f + movingSize.width / 2f, 250f)
        session.beginDrag(session.screenBounds(moving).center)
        session.updateDrag(target)
        frame()

        assertClose(fixedBounds.x, session.screenBounds(moving).x, "left edges should align")
        assertTrue(session.guides.peek().any { it.kind == GuideKind.ELEMENT })
    }

    @Test
    fun `each axis snaps independently`() {
        val engine = SnapEngine(threshold = 5f)
        val dragged = Rect(100f, 358f, 40f, 20f)

        // Horizontally free, vertically two units from the bottom edge.
        val result = engine.snap(dragged, Size(640f, 380f))

        assertClose(100f, result.position.x, "x should not move")
        assertClose(360f, result.position.y, "y should snap to the bottom edge")
        assertEquals(1, result.guides.size, "only the axis that snapped gets a guide")
    }

    @Test
    fun `safe areas are offered as snap targets`() {
        val areas = SafeArea.vanilla(screen)
        assertTrue(areas.any { it.id == "hotbar" })

        val hotbar = areas.first { it.id == "hotbar" }
        val engine = SnapEngine(threshold = 5f, snapToEdges = false, snapToCenter = false)
        val dragged = Rect(200f, hotbar.bounds.y - 22f, 40f, 20f)

        val result = engine.snap(dragged, screen, safeAreas = areas)
        assertClose(hotbar.bounds.y, result.position.y + dragged.height, "should sit on the hotbar's top edge")
        assertTrue(result.guides.any { it.kind == GuideKind.SAFE_AREA })
    }

    @Test
    fun `safe areas can be turned off`() {
        session.setShowSafeAreas(false)
        assertTrue(session.safeAreas().isEmpty())
    }

    @Test
    fun `a zero threshold disables snapping entirely`() {
        val engine = SnapEngine(threshold = 0f)
        val dragged = Rect(1f, 1f, 40f, 20f)
        val result = engine.snap(dragged, screen)

        assertEquals(dragged.position, result.position)
        assertFalse(result.didSnap)
    }

    // ---------------------------------------------------------------
    // Scaling and resizing by handle
    // ---------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(HudHandle::class)
    fun `scaling by a handle holds the opposite corner still`(handle: HudHandle) {
        val element = hud("mining", offset = Vec2(120f, 90f))
        frame()
        session.select(element.instance.instanceId)

        val before = session.screenBounds(element)
        val fixedCorner = Vec2(
            before.x + before.width * (1f - handle.horizontalFactorForTest()),
            before.y + before.height * (1f - handle.verticalFactorForTest()),
        )

        val grabbed = Vec2(
            before.x + before.width * handle.horizontalFactorForTest(),
            before.y + before.height * handle.verticalFactorForTest(),
        )
        assertTrue(session.beginScale(grabbed, handle))
        // Drag the handle a quarter of the width further from the fixed corner.
        val direction = if (handle.horizontalFactorForTest() == 1f) 1f else -1f
        session.updateScale(grabbed + Vec2(before.width * 0.25f * direction, 0f))
        frame()
        session.endGesture()

        val after = session.screenBounds(element)
        val fixedAfter = Vec2(
            after.x + after.width * (1f - handle.horizontalFactorForTest()),
            after.y + after.height * (1f - handle.verticalFactorForTest()),
        )
        assertClose(fixedCorner.x, fixedAfter.x, "the opposite corner must not move ($handle)")
        assertClose(fixedCorner.y, fixedAfter.y, "the opposite corner must not move ($handle)")
        assertTrue(after.width > before.width, "the element should have grown")
    }

    @Test
    fun `a scale gesture is one reversible entry`() {
        val element = hud("mining", offset = Vec2(120f, 90f))
        frame()
        session.select(element.instance.instanceId)
        val before = element.placement.peek()

        val bounds = session.screenBounds(element)
        val corner = Vec2(bounds.right, bounds.bottom)
        session.beginScale(corner, HudHandle.BOTTOM_RIGHT)
        session.updateScale(corner + Vec2(20f, 20f))
        session.updateScale(corner + Vec2(40f, 40f))
        frame()
        session.endGesture()

        assertEquals(1, session.undo.undoCount, "the whole gesture is one entry")
        session.undo.undo()
        frame()
        assertEquals(before, element.placement.peek())
    }

    @Test
    fun `a scale-only hud refuses a resize gesture`() {
        val element = hud("mining", resizeMode = HudResizeMode.SCALE_ONLY)
        frame()
        session.select(element.instance.instanceId)

        assertFalse(session.beginResize(Vec2.Zero, HudHandle.BOTTOM_RIGHT))
    }

    @Test
    fun `resizing changes the laid-out size and is clamped`() {
        val element = hud("panel", offset = Vec2(50f, 50f), resizeMode = HudResizeMode.RESIZE_ONLY)
        frame()
        session.select(element.instance.instanceId)

        val bounds = session.screenBounds(element)
        val corner = Vec2(bounds.right, bounds.bottom)
        assertTrue(session.beginResize(corner, HudHandle.BOTTOM_RIGHT))
        session.updateResize(Vec2(bounds.x + 150f, bounds.y + 90f))
        frame()
        session.endGesture()

        val size = element.placement.peek().size
        assertNotNull(size)
        assertClose(150f, size!!.width, "width follows the pointer")
        assertClose(90f, size.height, "height follows the pointer")

        // Below the definition's minimum, the size clamps rather than inverting.
        session.beginResize(corner, HudHandle.BOTTOM_RIGHT)
        session.updateResize(Vec2(bounds.x + 1f, bounds.y + 1f))
        frame()
        session.endGesture()
        val clamped = element.placement.peek().size!!
        assertTrue(clamped.width >= HudDefinition.MIN_DIMENSION, "width clamped to the minimum")
        assertTrue(clamped.height >= HudDefinition.MIN_DIMENSION, "height clamped to the minimum")
    }

    // ---------------------------------------------------------------
    // Inspector edits
    // ---------------------------------------------------------------

    @Test
    fun `changing the anchor from the inspector does not move the element`() {
        val element = hud("mining", anchor = Anchor.TOP_LEFT, offset = Vec2(80f, 60f))
        frame()
        session.select(element.instance.instanceId)

        val before = session.screenBounds(element).position
        session.setAnchor(Anchor.BOTTOM_RIGHT)
        frame()

        val after = session.screenBounds(element).position
        assertClose(before.x, after.x, "re-anchoring must not move the element")
        assertClose(before.y, after.y, "re-anchoring must not move the element")
        assertEquals(Anchor.BOTTOM_RIGHT, element.placement.peek().anchor)
    }

    @Test
    fun `reset restores the definition defaults and is reversible`() {
        val element = hud("mining", offset = Vec2(40f, 40f))
        frame()
        session.select(element.instance.instanceId)
        session.setScale(2f)
        session.nudge(Vec2(30f, 30f))
        frame()

        session.resetSelection()
        frame()
        assertEquals(element.definition.defaultPlacement(), element.placement.peek())

        session.undo.undo()
        frame()
        assertClose(2f, element.placement.peek().scale, "undo brings the edited state back")
    }

    @Test
    fun `opacity is clamped and reversible`() {
        val element = hud("mining")
        frame()
        session.select(element.instance.instanceId)

        session.setOpacity(5f)
        assertClose(1f, element.placement.peek().opacity)

        session.setOpacity(0.4f)
        assertClose(0.4f, element.placement.peek().opacity)
        session.undo.undo()
        assertClose(1f, element.placement.peek().opacity)
    }

    @Test
    fun `an empty selection is a no-op rather than an error`() {
        hud("mining")
        frame()

        session.nudge(Vec2(5f, 5f))
        session.setScale(2f)
        session.bringToFront()
        session.resetSelection()

        assertFalse(session.undo.canUndo.peek(), "nothing selected means nothing to undo")
        assertFalse(session.beginDrag(Vec2.Zero))
    }

    private fun HudHandle.horizontalFactorForTest(): Float =
        if (this == HudHandle.TOP_RIGHT || this == HudHandle.BOTTOM_RIGHT) 1f else 0f

    private fun HudHandle.verticalFactorForTest(): Float =
        if (this == HudHandle.BOTTOM_LEFT || this == HudHandle.BOTTOM_RIGHT) 1f else 0f

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
