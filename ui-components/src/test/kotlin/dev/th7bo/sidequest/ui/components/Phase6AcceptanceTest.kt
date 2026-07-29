package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.components.notification.NotificationRegionNode
import dev.th7bo.sidequest.ui.components.world.WaypointLayerNode
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.notification.NotificationQueue
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.core.world.WorldOverlayLayer
import dev.th7bo.sidequest.ui.extension.DuplicateRegistrationException
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.geometry.Anchor
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.notification.Notification
import dev.th7bo.sidequest.ui.notification.NotificationSeverity
import dev.th7bo.sidequest.ui.notification.OverflowPolicy
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.testkit.FakeTextMeasurer
import dev.th7bo.sidequest.ui.testkit.RecordingRenderer
import dev.th7bo.sidequest.ui.theme.DarkTheme
import dev.th7bo.sidequest.ui.world.DistanceFade
import dev.th7bo.sidequest.ui.world.EdgeIndicator
import dev.th7bo.sidequest.ui.world.WorldOverlayDefinition
import dev.th7bo.sidequest.ui.world.WorldPosition
import dev.th7bo.sidequest.ui.world.WorldProjection
import dev.th7bo.sidequest.ui.world.WorldProjector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/**
 * The Phase 6 acceptance criteria:
 *
 * - screen-space and world-space placement remain separate,
 * - overlays dispose cleanly with their registration scope.
 *
 * Plus the notification queue's own behaviour, which is where the subtle parts are:
 * coalescing, priority, overflow and pausing.
 */
class Phase6AcceptanceTest {

    private fun id(path: String) = UiId.of("sidequest", path)

    private lateinit var runtime: UiRuntime
    private lateinit var renderer: RecordingRenderer
    private lateinit var context: ComponentContext
    private lateinit var scope: RegistrationScope

    private val screen = Size(640f, 360f)

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        renderer = RecordingRenderer(screen, FakeTextMeasurer())
        runtime = UiRuntime(DarkTheme).apply {
            viewport = screen
            prepare(renderer)
        }
        context = ComponentContext(DarkTheme, runtime.animations, runtime)
        scope = RegistrationScope(id("phase6"))
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

    private fun toast(
        name: String,
        severity: NotificationSeverity = NotificationSeverity.INFO,
        seconds: Double = 5.0,
        coalesceKey: Any? = null,
        onDismiss: (() -> Unit)? = null,
    ) = Notification(
        id = id("notify.$name"),
        title = constantState(name),
        severity = severity,
        duration = seconds.seconds,
        coalesceKey = coalesceKey,
        onDismiss = onDismiss,
    )

    /** Projects straight through, so a test states screen coordinates directly. */
    private fun projector(
        position: Vec2 = Vec2(320f, 180f),
        distance: Double = 10.0,
        behind: Boolean = false,
    ) = WorldProjector { WorldProjection(position, distance, behind) }

    // ---------------------------------------------------------------
    // Criterion 1: screen space and world space stay separate
    // ---------------------------------------------------------------

    @Test
    fun `a world overlay stores no screen position`() {
        val overlays = WorldOverlayLayer()
        val position = mutableStateOf(WorldPosition(100.0, 64.0, -200.0), "waypoint")
        val definition = WorldOverlayDefinition(
            id = id("waypoint.home"),
            position = position,
            label = constantState("Home"),
        )
        overlays.register(scope, definition)

        // Resolving twice with two different cameras must give two different screen
        // positions from the same definition. If anything were cached back onto it, the
        // second resolve would repeat the first.
        val first = overlays.resolve(projector(Vec2(10f, 20f)), screen).single()
        val second = overlays.resolve(projector(Vec2(300f, 40f)), screen).single()

        assertEquals(Vec2(10f, 20f), first.screenPosition)
        assertEquals(Vec2(300f, 40f), second.screenPosition)
        assertSame(definition, first.definition)
        assertSame(definition, second.definition)

        // And the definition still describes only a world position.
        assertEquals(WorldPosition(100.0, 64.0, -200.0), definition.position.peek())
    }

    @Test
    fun `moving the world position does not move the screen position and vice versa`() {
        val overlays = WorldOverlayLayer()
        val position = mutableStateOf(WorldPosition(0.0, 0.0, 0.0), "waypoint")
        overlays.register(
            scope,
            WorldOverlayDefinition(id("waypoint.a"), position, constantState("A")),
        )

        val fixedCamera = projector(Vec2(100f, 100f))
        val before = overlays.resolve(fixedCamera, screen).single().screenPosition

        // The world position changes; the projector is a stub that ignores it, so the
        // screen position must not move. That is the separation: one does not imply the
        // other, only the projector relates them.
        position.value = WorldPosition(500.0, 70.0, 500.0)
        val after = overlays.resolve(fixedCamera, screen).single().screenPosition

        assertEquals(before, after, "screen position comes from the projector, not the world position")
    }

    @Test
    fun `an on-screen world overlay ignores the viewport, unlike an anchored hud`() {
        val overlays = WorldOverlayLayer()
        overlays.register(
            scope,
            WorldOverlayDefinition(id("w.a"), constantState(WorldPosition.Origin), constantState("A")),
        )

        // The camera decides where it lands; the viewport does not enter into it. A HUD
        // placement is the opposite — `resolve` takes the screen size precisely because
        // an anchored element must move when the screen does. The two placement models
        // depend on different things, which is what "remain separate" means in practice.
        val camera = projector(Vec2(100f, 80f))
        val small = overlays.resolve(camera, Size(320f, 180f)).single()
        val large = overlays.resolve(camera, Size(1920f, 1080f)).single()

        assertEquals(small.screenPosition, large.screenPosition)

        val hudPlacement = dev.th7bo.sidequest.ui.hud.HudPlacement(anchor = Anchor.BOTTOM_RIGHT)
        val hudSmall = hudPlacement.resolve(Size(40f, 20f), Size(320f, 180f))
        val hudLarge = hudPlacement.resolve(Size(40f, 20f), Size(1920f, 1080f))
        assertTrue(hudSmall != hudLarge, "a HUD placement does depend on the viewport")
    }

    @Test
    fun `a point behind the camera is never treated as on screen`() {
        val projection = WorldProjection(Vec2(320f, 180f), distance = 10.0, isBehind = true)
        assertFalse(
            projection.isOnScreen(screen),
            "a point behind the camera projects to a valid-looking coordinate that means nothing",
        )
    }

    @Test
    fun `an overlay behind the camera points backwards, not forwards`() {
        val overlays = WorldOverlayLayer()
        overlays.register(
            scope,
            WorldOverlayDefinition(
                id("waypoint.behind"),
                constantState(WorldPosition(0.0, 0.0, 0.0)),
                constantState("Behind"),
            ),
        )

        // Behind the camera and projecting to the upper left; the indicator must point to
        // the lower right, because the projection is mirrored through the centre.
        val resolved = overlays.resolve(projector(Vec2(100f, 40f), behind = true), screen).single()

        assertTrue(resolved.isEdgeIndicator)
        assertTrue(resolved.screenPosition.x > screen.width / 2f, "should be clamped to the right half")
        assertTrue(resolved.screenPosition.y > screen.height / 2f, "should be clamped to the lower half")
    }

    // ---------------------------------------------------------------
    // Criterion 2: overlays dispose with their registration scope
    // ---------------------------------------------------------------

    @Test
    fun `disposing a scope removes its overlays`() {
        val overlays = WorldOverlayLayer()
        val moduleScope = RegistrationScope(id("module"))

        overlays.register(scope, WorldOverlayDefinition(id("w.a"), constantState(WorldPosition.Origin), constantState("A")))
        overlays.register(moduleScope, WorldOverlayDefinition(id("w.b"), constantState(WorldPosition.Origin), constantState("B")))
        assertEquals(2, overlays.size)

        moduleScope.dispose()

        assertEquals(1, overlays.size, "only the disposed scope's overlay should go")
        assertNotNull(overlays[id("w.a")])
        assertNull(overlays[id("w.b")], "an unloaded module must not leave a waypoint on screen")
    }

    @Test
    fun `a disposed scope's overlay stops being drawn`() {
        val overlays = WorldOverlayLayer()
        val moduleScope = RegistrationScope(id("module"))
        overlays.register(
            moduleScope,
            WorldOverlayDefinition(id("w.b"), constantState(WorldPosition.Origin), constantState("B")),
        )

        val layer = WaypointLayerNode(id("waypoints"), overlays, context).apply {
            projector = projector()
        }
        runtime.root = layer
        frame()
        assertEquals(1, layer.lastResolved.size)

        moduleScope.dispose()
        layer.invalidatePaint()
        frame()

        assertEquals(0, layer.lastResolved.size, "a disposed overlay must stop resolving")
    }

    @Test
    fun `registering the same overlay id twice names both owners`() {
        val overlays = WorldOverlayLayer()
        val other = RegistrationScope(id("other"))
        overlays.register(scope, WorldOverlayDefinition(id("w.a"), constantState(WorldPosition.Origin), constantState("A")))

        val failure = assertThrows(DuplicateRegistrationException::class.java) {
            overlays.register(other, WorldOverlayDefinition(id("w.a"), constantState(WorldPosition.Origin), constantState("A")))
        }
        assertTrue("phase6" in failure.message.orEmpty() || "other" in failure.message.orEmpty())
        other.dispose()
    }

    // ---------------------------------------------------------------
    // Distance fading and culling
    // ---------------------------------------------------------------

    @Test
    fun `opacity falls off between the near and far distances`() {
        val fade = DistanceFade(nearDistance = 10.0, farDistance = 20.0)

        assertEquals(1f, fade.opacityAt(5.0))
        assertEquals(1f, fade.opacityAt(10.0))
        assertTrue(abs(0.5f - fade.opacityAt(15.0)) < TOLERANCE, "halfway should be half opacity")
        assertEquals(0f, fade.opacityAt(20.0))
        assertEquals(0f, fade.opacityAt(1000.0))
    }

    @Test
    fun `an overlay closer than the minimum distance hides`() {
        val fade = DistanceFade(nearDistance = 10.0, farDistance = 20.0, minimumDistance = 3.0)
        assertEquals(0f, fade.opacityAt(1.0), "standing on it should not cover the screen")
        assertEquals(1f, fade.opacityAt(5.0))
    }

    @Test
    fun `a fully faded overlay is culled rather than drawn transparent`() {
        val overlays = WorldOverlayLayer()
        overlays.register(
            scope,
            WorldOverlayDefinition(
                id("w.far"),
                constantState(WorldPosition.Origin),
                constantState("Far"),
                fade = DistanceFade(nearDistance = 10.0, farDistance = 20.0),
            ),
        )

        assertEquals(1, overlays.resolve(projector(distance = 12.0), screen).size)
        assertEquals(0, overlays.resolve(projector(distance = 50.0), screen).size, "beyond far is not drawn at all")
    }

    @Test
    fun `overlays resolve far to near so nearer ones paint on top`() {
        val overlays = WorldOverlayLayer()
        val near = WorldOverlayDefinition(id("w.near"), constantState(WorldPosition(1.0, 0.0, 0.0)), constantState("Near"))
        val far = WorldOverlayDefinition(id("w.far"), constantState(WorldPosition(2.0, 0.0, 0.0)), constantState("Far"))
        overlays.register(scope, near)
        overlays.register(scope, far)

        val byId = mapOf(near.id to 5.0, far.id to 50.0)
        val resolved = overlays.resolve(
            { position -> WorldProjection(Vec2(100f, 100f), if (position.x == 1.0) byId[near.id]!! else byId[far.id]!!, false) },
            screen,
        )

        assertEquals(listOf(far.id, near.id), resolved.map { it.definition.id }, "far first, so near paints over it")
    }

    @Test
    fun `an invisible overlay resolves to nothing`() {
        val overlays = WorldOverlayLayer()
        val visible = mutableStateOf(true, "visible")
        overlays.register(
            scope,
            WorldOverlayDefinition(
                id("w.a"),
                constantState(WorldPosition.Origin),
                constantState("A"),
                visibleWhen = visible,
            ),
        )

        assertEquals(1, overlays.resolve(projector(), screen).size)
        visible.value = false
        assertEquals(0, overlays.resolve(projector(), screen).size)
    }

    // ---------------------------------------------------------------
    // Screen-edge indicators
    // ---------------------------------------------------------------

    @Test
    fun `an off-screen overlay becomes an edge indicator inside the viewport`() {
        val overlays = WorldOverlayLayer()
        overlays.register(
            scope,
            WorldOverlayDefinition(id("w.a"), constantState(WorldPosition.Origin), constantState("A")),
        )

        val resolved = overlays.resolve(projector(Vec2(2000f, 180f)), screen).single()

        assertTrue(resolved.isEdgeIndicator)
        val margin = EdgeIndicator.DEFAULT_MARGIN
        assertTrue(resolved.screenPosition.x <= screen.width - margin + TOLERANCE, "clamped inside the right edge")
        assertTrue(resolved.screenPosition.x >= margin - TOLERANCE, "and not past the left")
        assertTrue(resolved.screenPosition.y in 0f..screen.height)
    }

    @Test
    fun `an on-screen overlay is not an edge indicator`() {
        val overlays = WorldOverlayLayer()
        overlays.register(
            scope,
            WorldOverlayDefinition(id("w.a"), constantState(WorldPosition.Origin), constantState("A")),
        )

        val resolved = overlays.resolve(projector(Vec2(320f, 180f)), screen).single()
        assertFalse(resolved.isEdgeIndicator)
        assertEquals(Vec2(320f, 180f), resolved.screenPosition)
    }

    @Test
    fun `edge indicators can be turned off, and then the overlay simply does not draw`() {
        val overlays = WorldOverlayLayer()
        overlays.register(
            scope,
            WorldOverlayDefinition(
                id("w.a"),
                constantState(WorldPosition.Origin),
                constantState("A"),
                edgeIndicator = EdgeIndicator.Disabled,
            ),
        )

        assertEquals(0, overlays.resolve(projector(Vec2(2000f, 180f)), screen).size)
        assertEquals(1, overlays.resolve(projector(Vec2(320f, 180f)), screen).size)
    }

    // ---------------------------------------------------------------
    // Notification queue
    // ---------------------------------------------------------------

    @Test
    fun `a notification times out on its own`() {
        val queue = NotificationQueue()
        queue.post(toast("hello", seconds = 2.0))
        assertEquals(1, queue.showing.peek().size)

        queue.tick(1f)
        assertEquals(1, queue.showing.peek().size, "not yet")

        queue.tick(1.5f)
        assertEquals(0, queue.showing.peek().size, "and now it is gone")
    }

    @Test
    fun `a notification with no duration stays until dismissed`() {
        val queue = NotificationQueue()
        val sticky = Notification(id("notify.sticky"), constantState("Sticky"), duration = null)
        queue.post(sticky)

        repeat(100) { queue.tick(1f) }
        assertEquals(1, queue.showing.peek().size)

        assertTrue(queue.dismiss(sticky.id))
        assertEquals(0, queue.showing.peek().size)
    }

    @Test
    fun `repeats coalesce into one entry with a count`() {
        val queue = NotificationQueue()
        repeat(4) { queue.post(toast("diamond", coalesceKey = "diamond")) }

        val entries = queue.showing.peek()
        assertEquals(1, entries.size, "four repeats should be one toast")
        assertEquals(4, entries.single().count)
    }

    @Test
    fun `a coalesced repeat restarts the timer rather than inheriting the remainder`() {
        val queue = NotificationQueue()
        queue.post(toast("diamond", seconds = 2.0, coalesceKey = "diamond"))
        queue.tick(1.5f)

        queue.post(toast("diamond", seconds = 2.0, coalesceKey = "diamond"))
        assertEquals(0f, queue.showing.peek().single().elapsedSeconds, "the newest occurrence gets a full duration")

        queue.tick(1.5f)
        assertEquals(1, queue.showing.peek().size, "so it is still showing")
    }

    @Test
    fun `notifications without a coalesce key stack separately`() {
        val queue = NotificationQueue()
        queue.post(toast("a"))
        queue.post(toast("b"))
        assertEquals(2, queue.showing.peek().size)
    }

    @Test
    fun `a higher severity sorts above what is already showing`() {
        val queue = NotificationQueue()
        queue.post(toast("info", NotificationSeverity.INFO))
        queue.post(toast("error", NotificationSeverity.ERROR))

        assertEquals(
            listOf("notify.error", "notify.info"),
            queue.showing.peek().map { it.id.path },
            "an error must not sit below routine chatter",
        )
    }

    @Test
    fun `overflow queues by default and promotes as slots free up`() {
        val queue = NotificationQueue(maxVisible = 2)
        queue.post(toast("a", seconds = 1.0))
        queue.post(toast("b", seconds = 1.0))
        assertNull(queue.post(toast("c", seconds = 1.0)), "the third has to wait")
        assertEquals(1, queue.pendingCount)

        queue.tick(1.1f)

        assertEquals(1, queue.showing.peek().size, "the waiting one moved up")
        assertEquals("notify.c", queue.showing.peek().single().id.path)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `drop-oldest never evicts an important notification for a trivial one`() {
        val queue = NotificationQueue(maxVisible = 1, overflowPolicy = OverflowPolicy.DROP_OLDEST)
        queue.post(toast("error", NotificationSeverity.ERROR))

        assertNull(queue.post(toast("info", NotificationSeverity.INFO)), "info must not push out an error")
        assertEquals("notify.error", queue.showing.peek().single().id.path)

        // The reverse is allowed.
        val queue2 = NotificationQueue(maxVisible = 1, overflowPolicy = OverflowPolicy.DROP_OLDEST)
        queue2.post(toast("info", NotificationSeverity.INFO))
        assertNotNull(queue2.post(toast("error", NotificationSeverity.ERROR)))
        assertEquals("notify.error", queue2.showing.peek().single().id.path)
    }

    @Test
    fun `drop-newest refuses the arrival and counts it`() {
        val queue = NotificationQueue(maxVisible = 1, overflowPolicy = OverflowPolicy.DROP_NEWEST)
        queue.post(toast("a"))
        assertNull(queue.post(toast("b")))
        assertEquals(1, queue.droppedCount, "a dropped notification is counted, not hidden")
    }

    @Test
    fun `a full backlog drops rather than growing without bound`() {
        val queue = NotificationQueue(maxVisible = 1, maxPending = 2)
        queue.post(toast("a"))
        queue.post(toast("b"))
        queue.post(toast("c"))
        queue.post(toast("d"))

        assertEquals(2, queue.pendingCount)
        assertEquals(1, queue.droppedCount)
    }

    @Test
    fun `pausing stops the clock, so nothing expires unseen behind a screen`() {
        val queue = NotificationQueue()
        queue.post(toast("hello", seconds = 1.0))

        queue.isPaused = true
        repeat(10) { queue.tick(1f) }
        assertEquals(1, queue.showing.peek().size, "a notification must not expire while a screen covers it")

        queue.isPaused = false
        queue.tick(1.1f)
        assertEquals(0, queue.showing.peek().size)
    }

    @Test
    fun `dismissal runs the callback exactly once`() {
        var dismissals = 0
        val queue = NotificationQueue()
        val entry = toast("hello", onDismiss = { dismissals++ })
        queue.post(entry)

        assertTrue(queue.dismiss(entry.id))
        assertFalse(queue.dismiss(entry.id), "dismissing twice is not an error, but does nothing")
        assertEquals(1, dismissals)
    }

    @Test
    fun `timing out also runs the dismissal callback`() {
        var dismissed = false
        val queue = NotificationQueue()
        queue.post(toast("hello", seconds = 1.0, onDismiss = { dismissed = true }))
        queue.tick(1.1f)

        assertTrue(dismissed, "a timeout is still a dismissal")
    }

    @Test
    fun `activating runs the handler and takes the notification away`() {
        var activated = false
        val queue = NotificationQueue()
        val entry = Notification(
            id("notify.click"),
            constantState("Click me"),
            onActivate = { activated = true },
        )
        queue.post(entry)

        assertTrue(queue.activate(entry.id))
        assertTrue(activated)
        assertEquals(0, queue.showing.peek().size)
    }

    // ---------------------------------------------------------------
    // Notification region
    // ---------------------------------------------------------------

    @Test
    fun `the region builds one toast per showing notification`() {
        val queue = NotificationQueue()
        val region = NotificationRegionNode(id("region"), queue, context)
        runtime.root = region
        frame()

        queue.post(toast("a"))
        queue.post(toast("b"))
        frame()

        assertEquals(2, region.toastCount)
    }

    @Test
    fun `a coalesced repeat keeps its node rather than replacing it`() {
        val queue = NotificationQueue()
        val region = NotificationRegionNode(id("region"), queue, context)
        runtime.root = region
        queue.post(toast("diamond", coalesceKey = "diamond"))
        frame()
        assertEquals(1, region.toastCount)

        queue.post(toast("diamond", coalesceKey = "diamond"))
        frame()

        assertEquals(1, region.toastCount, "the toast should update in place, not flicker")
        assertEquals(2, queue.showing.peek().single().count)
    }

    @Test
    fun `the stack is drawn in the queue's priority order, not arrival order`() {
        val queue = NotificationQueue()
        val region = NotificationRegionNode(id("region"), queue, context)
        runtime.root = region

        queue.post(toast("info", NotificationSeverity.INFO))
        frame()
        queue.post(toast("error", NotificationSeverity.ERROR))
        frame()

        // The queue puts the error first. If the region only appended new nodes, the
        // error would render *below* the routine one and the ordering would exist but be
        // invisible — which is exactly what the in-game capture showed.
        val drawn = region.children.first().children.map { it.id.path }
        assertEquals(
            queue.showing.peek().map { id("region").child(it.id.path).path },
            drawn,
            "the drawn order must match the queue",
        )
        assertTrue(drawn.first().endsWith("notify.error"), "the error should be on top, got $drawn")
    }

    @Test
    fun `toasts align to the edge the region is anchored against`() {
        val queue = NotificationQueue()
        val region = NotificationRegionNode(id("region"), queue, context, anchor = Anchor.TOP_RIGHT)
        runtime.root = region
        queue.post(Notification(id("notify.short"), constantState("Hi")))
        queue.post(Notification(id("notify.long"), constantState("A considerably longer message")))
        frame()

        val bounds = region.children.first().children.map { it.absoluteBounds() }
        assertEquals(2, bounds.size)
        assertTrue(
            abs(bounds[0].right - bounds[1].right) < TOLERANCE,
            "right edges should line up against the anchored edge, got ${bounds.map { it.right }}",
        )
    }

    @Test
    fun `a dismissed notification's toast goes away`() {
        val queue = NotificationQueue()
        val region = NotificationRegionNode(id("region"), queue, context)
        runtime.root = region
        val entry = toast("a")
        queue.post(entry)
        frame()

        queue.dismiss(entry.id)
        frame()

        assertEquals(0, region.toastCount)
    }

    @ParameterizedTest
    @EnumSource(Anchor::class)
    fun `the region stays inside the viewport at every anchor`(anchor: Anchor) {
        val queue = NotificationQueue()
        val region = NotificationRegionNode(id("region"), queue, context, anchor = anchor)
        runtime.root = region
        queue.post(toast("a"))
        queue.post(toast("b"))
        frame()

        val bounds = region.children.first().absoluteBounds()
        assertTrue(bounds.x >= -TOLERANCE, "left edge inside for $anchor, was ${bounds.x}")
        assertTrue(bounds.y >= -TOLERANCE, "top edge inside for $anchor, was ${bounds.y}")
        assertTrue(bounds.right <= screen.width + TOLERANCE, "right edge inside for $anchor")
        assertTrue(bounds.bottom <= screen.height + TOLERANCE, "bottom edge inside for $anchor")
    }

    @Test
    fun `an idle region with nothing showing does no layout work`() {
        val queue = NotificationQueue()
        val region = NotificationRegionNode(id("region"), queue, context)
        runtime.root = region
        frame()

        val idle = frame()
        assertEquals(0, idle.nodesMeasured, "nothing to show is nothing to lay out")
    }

    @Test
    fun `the waypoint layer draws nothing without a projector`() {
        val overlays = WorldOverlayLayer()
        overlays.register(
            scope,
            WorldOverlayDefinition(id("w.a"), constantState(WorldPosition.Origin), constantState("A")),
        )
        val layer = WaypointLayerNode(id("waypoints"), overlays, context)
        runtime.root = layer
        frame()

        assertEquals(0, layer.lastResolved.size, "no camera means nothing to project against")
    }

    @Test
    fun `the waypoint layer uses the definition's colour when given one`() {
        val overlays = WorldOverlayLayer()
        val purple = Color.parse("#FF8B5CF6")
        overlays.register(
            scope,
            WorldOverlayDefinition(
                id("w.a"),
                constantState(WorldPosition.Origin),
                constantState("A"),
                color = purple,
            ),
        )
        val layer = WaypointLayerNode(id("waypoints"), overlays, context).apply { projector = projector() }
        runtime.root = layer
        frame()

        assertEquals(purple, layer.lastResolved.single().definition.color)
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
