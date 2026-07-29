package dev.th7bo.sidequest.ui.components.hud

import dev.th7bo.sidequest.ui.components.Icons
import dev.th7bo.sidequest.ui.core.animation.AnimationHost
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.hud.HudElementNode
import dev.th7bo.sidequest.ui.core.hud.HudLayerNode
import dev.th7bo.sidequest.ui.core.hud.HudRegistry
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.extension.DuplicateRegistrationException
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.geometry.Anchor
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
import dev.th7bo.sidequest.ui.testkit.DrawCommand
import dev.th7bo.sidequest.ui.testkit.FakeTextMeasurer
import dev.th7bo.sidequest.ui.testkit.RecordingRenderer
import dev.th7bo.sidequest.ui.theme.DarkTheme
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.math.abs

/**
 * The Phase 4 acceptance criteria:
 *
 * - the mining XP HUD works,
 * - changing an anchor preserves the visible position,
 * - scaled hit testing passes,
 * - updating one value does not rebuild unrelated HUDs.
 */
class Phase4AcceptanceTest {

    private fun id(path: String) = UiId.of("sidequest", path)

    private lateinit var runtime: UiRuntime
    private lateinit var renderer: RecordingRenderer
    private lateinit var context: ComponentContext
    private lateinit var layer: HudLayerNode
    private lateinit var scope: RegistrationScope

    private val screen = Size(640f, 360f)

    private lateinit var currentXp: MutableUiState<Long>
    private lateinit var requiredXp: MutableUiState<Long>

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        renderer = RecordingRenderer(screen, FakeTextMeasurer())
        runtime = UiRuntime(DarkTheme).apply {
            viewport = screen
            prepare(renderer)
        }
        context = ComponentContext(DarkTheme, runtime.animations, runtime)
        scope = RegistrationScope(id("hud_test"))

        currentXp = mutableStateOf(28_450L, "currentXp")
        requiredXp = mutableStateOf(60_000L, "requiredXp")

        layer = HudLayerNode(id("hud_layer")) { screen }
        runtime.root = layer
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

    private fun miningDefinition(
        instanceId: String = "hud.mining_xp",
        anchor: Anchor = Anchor.BOTTOM_CENTER,
    ) = HudDefinition(
        id = id("hud.mining_xp"),
        title = constantState("Mining XP"),
        icon = Icons.gear,
        defaultAnchor = anchor,
        defaultOffset = Vec2(0f, -90f),
        defaultScale = 1f,
        scaleRange = 0.5f..2.5f,
        resizeMode = HudResizeMode.SCALE_ONLY,
    )

    private fun miningHud(
        instanceId: String = "hud.mining_xp",
        anchor: Anchor = Anchor.BOTTOM_CENTER,
    ): HudElementNode {
        val definition = miningDefinition(instanceId, anchor)
        val instance = HudInstance(id(instanceId), definition.id)
        val content = ProgressHudNode(
            id = id("$instanceId.content"),
            componentContext = context,
            title = constantState("Mining XP"),
            current = currentXp,
            maximum = requiredXp,
            subtitle = constantState("Lv. 42"),
            icon = Icons.gear,
        )
        val element = HudElementNode(instance, definition, {
            dev.th7bo.sidequest.ui.core.hud.HudContext(screen, 2f, 0f, context)
        }, content)
        layer.add(element)
        return element
    }

    // ---------------------------------------------------------------
    // Criterion 1: the mining XP HUD works
    // ---------------------------------------------------------------

    @Test
    fun `the mining xp hud draws its card, icon block, labels and bar`() {
        miningHud()
        frame()

        val texts = renderer.commandsOfType<dev.th7bo.sidequest.ui.testkit.DrawCommand.Text>()
            .map { it.content }
        assertTrue(texts.contains("Mining XP"), "the title, got $texts")
        assertTrue(texts.contains("Lv. 42"), "the level chip")
        // The current value is a run of its own so it can carry the accent colour.
        assertTrue(texts.any { it == "28,450" }, "grouped digits, got $texts")
        assertTrue(texts.any { it == "/ 60,000" }, "grouped digits, got $texts")

        val rounded = renderer.commandsOfType<dev.th7bo.sidequest.ui.testkit.DrawCommand.RoundedRect>()
        assertTrue(rounded.size >= 4, "card, icon block, bar track and fill")
        assertTrue(
            renderer.commandsOfType<dev.th7bo.sidequest.ui.testkit.DrawCommand.Border>().size >= 2,
            "the card and the icon block are both bordered",
        )
    }

    @Test
    fun `the readout follows the data`() {
        miningHud()
        frame()

        currentXp.value = 31_000L
        frame()

        val texts = renderer.commandsOfType<dev.th7bo.sidequest.ui.testkit.DrawCommand.Text>()
            .map { it.content }
        assertTrue(texts.any { it == "31,000" }, "got $texts")
    }

    @Test
    fun `the bar animates towards the new value rather than snapping`() {
        val element = miningHud()
        val content = element.children.first() as ProgressHudNode
        frame()
        val start = content.fillFraction

        currentXp.value = 60_000L
        frame()

        assertTrue(content.fillFraction > start, "the bar should have started moving")
        assertTrue(content.fillFraction < 1f, "but not arrived instantly")

        repeat(40) { frame() }
        assertTrue(abs(content.fillFraction - 1f) < 0.01f, "and should settle at the target")
    }

    @Test
    fun `the hud is compact rather than a full-screen panel`() {
        val element = miningHud()
        frame()

        assertTrue(element.contentSize.width < screen.width / 2f) {
            "a HUD must stay compact, was ${element.contentSize}"
        }
        assertTrue(element.contentSize.height < 40f)
    }

    @Test
    fun `visibility rules are honoured without touching the render path`() {
        val visible = mutableStateOf(true)
        val definition = HudDefinition(
            id = id("hud.conditional"),
            title = constantState("Conditional"),
            visibleWhen = visible,
        )
        val element = HudElementNode(
            HudInstance(id("hud.conditional.a"), definition.id),
            definition,
            { dev.th7bo.sidequest.ui.core.hud.HudContext(screen, 2f, 0f, context) },
            ProgressHudNode(id("c.content"), context, constantState("C"), currentXp, requiredXp),
        )
        layer.add(element)
        frame()
        assertTrue(element.isVisible)

        visible.value = false
        frame()

        assertFalse(element.isVisible, "visibility is a dependency, not a render-time check")
    }

    // ---------------------------------------------------------------
    // Criterion 2: anchor changes preserve the visible position
    // ---------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Anchor::class)
    fun `re-anchoring never moves the element on screen`(target: Anchor) {
        val element = miningHud()
        frame()

        val before = element.placement.value.resolve(element.scaledSize, screen)

        element.reanchor(target, screen)
        frame()

        val after = element.placement.value.resolve(element.scaledSize, screen)

        assertClose(before.x, after.x, "x moved when re-anchoring to $target")
        assertClose(before.y, after.y, "y moved when re-anchoring to $target")
        assertEquals(target, element.placement.value.anchor)
    }

    @ParameterizedTest
    @EnumSource(Anchor::class)
    fun `placement keeps its distance from the anchored edge across resolutions`(anchor: Anchor) {
        val element = miningHud(anchor = anchor)
        frame()

        val small = Size(1280f, 720f)
        val large = Size(2560f, 1440f)
        val placement = element.placement.value
        val size = element.scaledSize

        val onSmall = placement.resolve(size, small)
        val onLarge = placement.resolve(size, large)

        // What must stay constant is the gap to the anchored corner, not the absolute
        // position — that is the entire reason placement is not stored in pixels.
        val gapSmall = gapToAnchor(anchor, onSmall, size, small)
        val gapLarge = gapToAnchor(anchor, onLarge, size, large)

        assertClose(gapSmall.x, gapLarge.x, "horizontal gap changed for $anchor")
        assertClose(gapSmall.y, gapLarge.y, "vertical gap changed for $anchor")
    }

    @Test
    fun `dragging then re-anchoring preserves the dragged position`() {
        val element = miningHud()
        frame()

        val dropped = Vec2(410f, 120f)
        element.moveTo(dropped, screen)
        frame()

        assertClose(dropped.x, element.placement.value.resolve(element.scaledSize, screen).x)
        assertClose(dropped.y, element.placement.value.resolve(element.scaledSize, screen).y)

        element.reanchor(Anchor.TOP_RIGHT, screen)
        val after = element.placement.value.resolve(element.scaledSize, screen)

        assertClose(dropped.x, after.x, "re-anchoring moved a dragged HUD")
        assertClose(dropped.y, after.y)
    }

    @Test
    fun `a gui-scale change keeps the element against its edge`() {
        val element = miningHud(anchor = Anchor.BOTTOM_RIGHT)
        frame()

        // A GUI-scale change arrives as a different logical screen size.
        val atScaleTwo = Size(640f, 360f)
        val atScaleThree = Size(427f, 240f)
        val placement = element.placement.value
        val size = element.scaledSize

        val two = placement.resolve(size, atScaleTwo)
        val three = placement.resolve(size, atScaleThree)

        assertClose(atScaleTwo.width - two.x - size.width, atScaleThree.width - three.x - size.width)
        assertClose(atScaleTwo.height - two.y - size.height, atScaleThree.height - three.y - size.height)
    }

    // ---------------------------------------------------------------
    // Criterion 3: scaled hit testing
    // ---------------------------------------------------------------

    @Test
    fun `a scaled hud is hit at its scaled bounds`() {
        val element = miningHud(anchor = Anchor.TOP_LEFT)
        element.update { it.copy(offset = Vec2.Zero) }
        frame()

        val unscaled = element.contentSize
        element.rescale(2f)
        frame()

        assertEquals(unscaled * 2f, element.scaledSize)

        // Make the element itself a hit target, as the editor does.
        element.interactive = true

        // A point inside the scaled bounds but outside the unscaled ones must hit.
        val beyondUnscaled = Vec2(unscaled.width * 1.5f, unscaled.height * 0.5f)
        assertSame(
            element,
            runtime.input.hitTest(beyondUnscaled).lastOrNull(),
            "a 2x HUD must be hittable across its scaled area",
        )

        val beyondScaled = Vec2(unscaled.width * 2.5f, unscaled.height * 0.5f)
        assertTrue(
            runtime.input.hitTest(beyondScaled).isEmpty(),
            "and not beyond it",
        )
    }

    @Test
    fun `a handler inside a scaled hud receives the pointer in its own space`() {
        val element = miningHud(anchor = Anchor.TOP_LEFT)
        element.update { it.copy(offset = Vec2.Zero) }
        element.interactive = true
        element.rescale(2f)
        frame()

        val received = ArrayList<Vec2>()
        element.onEvent = { event ->
            if (event is dev.th7bo.sidequest.ui.input.PointerDownEvent) received.add(event.position)
        }

        runtime.input.pointerPressed(Vec2(20f, 10f))

        // 20 screen units into a 2x element is 10 local units.
        assertEquals(1, received.size, "the scaled element should have been hit")
        assertClose(10f, received.first().x)
        assertClose(5f, received.first().y)
    }

    @Test
    fun `a scaled hud stays anchored, and paint agrees with hit testing`() {
        // Anchored away from the origin on both axes: at (0, 0) a position-scaling bug
        // is invisible, because zero times any scale is still zero. That is exactly how
        // one survived here until the in-game capture showed a 1.6x HUD sliding off the
        // bottom of the screen.
        val element = miningHud(anchor = Anchor.BOTTOM_RIGHT)
        element.update { it.copy(offset = Vec2(-40f, -90f)) }
        element.interactive = true
        frame()

        element.rescale(2f)
        frame()

        // The gap to the anchored edges must be exactly the offset, whatever the scale.
        val size = element.scaledSize
        val painted = element.absoluteBounds().position
        assertClose(40f, screen.width - (painted.x + size.width), "gap to the right edge")
        assertClose(90f, screen.height - (painted.y + size.height), "gap to the bottom edge")

        // The transform handed to the renderer must map the element's local origin to
        // that same position. This is the assertion that actually covers the paint path:
        // `absoluteBounds` and `hitTest` both read the arrange result, which was never
        // wrong — only the composition order pushed to the renderer was.
        val pushed = renderer.commands
            .filterIsInstance<DrawCommand.PushTransform>()
            .map { it.transform }
            .firstOrNull { it.scaleX == 2f }
        assertNotNull(pushed, "a 2x element should push a scaling transform")
        val mapped = pushed!!.apply(Vec2.Zero)
        assertClose(painted.x, mapped.x, "painted origin x")
        assertClose(painted.y, mapped.y, "painted origin y")

        // And the pointer must land where the element was drawn.
        val inside = Vec2(painted.x + size.width * 0.5f, painted.y + size.height * 0.5f)
        assertSame(
            element,
            runtime.input.hitTest(inside).lastOrNull(),
            "the centre of the painted element must hit it",
        )
        assertTrue(
            runtime.input.hitTest(Vec2(painted.x - 8f, inside.y)).isEmpty(),
            "and a point left of its painted edge must not",
        )
    }

    @Test
    fun `scaling is clamped to the definition's range`() {
        val element = miningHud()
        element.rescale(99f)
        assertEquals(2.5f, element.placement.value.scale)

        element.rescale(0.01f)
        assertEquals(0.5f, element.placement.value.scale)
    }

    @Test
    fun `a hud that does not support scaling refuses to scale`() {
        val definition = HudDefinition(
            id = id("hud.fixed"),
            title = constantState("Fixed"),
            resizeMode = HudResizeMode.NONE,
        )
        val element = HudElementNode(
            HudInstance(id("hud.fixed.a"), definition.id),
            definition,
            { dev.th7bo.sidequest.ui.core.hud.HudContext(screen, 2f, 0f, context) },
            ProgressHudNode(id("f.content"), context, constantState("F"), currentXp, requiredXp),
        )
        layer.add(element)

        element.rescale(2f)

        assertEquals(1f, element.placement.value.scale)
    }

    // ---------------------------------------------------------------
    // Criterion 4: one value update does not rebuild unrelated HUDs
    // ---------------------------------------------------------------

    @Test
    fun `updating one hud's data does not re-measure another`() {
        val otherCurrent = mutableStateOf(10L)
        val otherMax = mutableStateOf(100L)

        miningHud("hud.a")

        val definitionB = HudDefinition(id("hud.b"), constantState("B"))
        val elementB = HudElementNode(
            HudInstance(id("hud.b.instance"), definitionB.id),
            definitionB,
            { dev.th7bo.sidequest.ui.core.hud.HudContext(screen, 2f, 0f, context) },
            ProgressHudNode(id("b.content"), context, constantState("B"), otherCurrent, otherMax),
        )
        layer.add(elementB)

        // Settle both, including their entry animations.
        repeat(40) { frame() }
        assertEquals(0, frame().nodesMeasured, "the layer should be idle before the test")

        currentXp.value = 40_000L
        val metrics = frame()

        // Only the changed HUD's own spine remeasures. The layer itself is on that spine;
        // the other element is not.
        assertTrue(metrics.nodesMeasured > 0, "the changed HUD must remeasure")
        assertTrue(
            metrics.nodesMeasured < 12,
            "one HUD changing must not remeasure the other, measured ${metrics.nodesMeasured}",
        )
    }

    @Test
    fun `an idle hud layer does no layout work`() {
        miningHud("hud.a")
        miningHud("hud.b")
        repeat(40) { frame() }

        val idle = frame()

        assertEquals(0, idle.nodesMeasured)
        assertEquals(0, idle.nodesArranged)
        assertTrue(runtime.isIdle)
    }

    @Test
    fun `moving one hud does not re-measure any hud`() {
        val a = miningHud("hud.a")
        miningHud("hud.b")
        repeat(40) { frame() }

        a.moveTo(Vec2(10f, 10f), screen)
        val metrics = frame()

        assertEquals(0, metrics.nodesMeasured, "moving is an arrange, never a measure")
        assertTrue(metrics.nodesArranged > 0)
    }

    // ---------------------------------------------------------------
    // Registry, instances, z-order
    // ---------------------------------------------------------------

    @Test
    fun `the registry rejects a duplicate definition, naming both owners`() {
        val registry = HudRegistry()
        val other = RegistrationScope(id("other"))
        registry.register(scope, miningDefinition()) { _, _ -> ProgressHudNode(id("x"), context, constantState("x"), currentXp, requiredXp) }

        val failure = assertThrows(DuplicateRegistrationException::class.java) {
            registry.register(other, miningDefinition()) { _, _ ->
                ProgressHudNode(id("y"), context, constantState("y"), currentXp, requiredXp)
            }
        }
        assertEquals(scope.owner, failure.existingOwner)
        assertEquals(other.owner, failure.attemptedOwner)
        other.dispose()
    }

    @Test
    fun `disposing a scope removes its definitions and renderers`() {
        val registry = HudRegistry()
        registry.register(scope, miningDefinition()) { _, _ ->
            ProgressHudNode(id("x"), context, constantState("x"), currentXp, requiredXp)
        }
        assertEquals(1, registry.size)
        assertNotNull(registry.rendererFor(id("hud.mining_xp")))

        scope.dispose()

        assertEquals(0, registry.size)
        assertEquals(null, registry.rendererFor(id("hud.mining_xp")))
    }

    @Test
    fun `several instances of one definition keep independent placements`() {
        val first = miningHud("hud.timer_a")
        val second = miningHud("hud.timer_b")
        frame()

        first.moveTo(Vec2(10f, 10f), screen)
        second.moveTo(Vec2(200f, 100f), screen)
        frame()

        assertClose(10f, first.placement.value.resolve(first.scaledSize, screen).x)
        assertClose(200f, second.placement.value.resolve(second.scaledSize, screen).x)
    }

    @Test
    fun `z-order determines draw order`() {
        val back = miningHud("hud.back")
        val front = miningHud("hud.front")

        back.update { it.copy(zIndex = 0) }
        front.update { it.copy(zIndex = 5) }

        assertEquals(listOf(back, front), layer.ordered)

        front.update { it.copy(zIndex = -1) }
        assertEquals(listOf(front, back), layer.ordered)
    }

    @Test
    fun `removing an instance takes it off the layer and disposes it`() {
        val element = miningHud("hud.temp")
        frame()
        assertEquals(1, layer.elementCount)

        assertTrue(layer.remove(id("hud.temp")))

        assertEquals(0, layer.elementCount)
        assertTrue(element.scope.isDisposed)
        assertFalse(layer.remove(id("hud.temp")), "removing twice reports no change")
    }

    @Test
    fun `reset restores the definition's defaults`() {
        val element = miningHud()
        element.moveTo(Vec2(5f, 5f), screen)
        element.rescale(2f)

        element.reset()

        assertEquals(Anchor.BOTTOM_CENTER, element.placement.value.anchor)
        assertEquals(Vec2(0f, -90f), element.placement.value.offset)
        assertEquals(1f, element.placement.value.scale)
    }

    private fun gapToAnchor(anchor: Anchor, position: Vec2, size: Size, screenSize: Size): Vec2 {
        val elementPoint = Vec2(
            position.x + size.width * anchor.horizontalFactor,
            position.y + size.height * anchor.verticalFactor,
        )
        return Vec2(
            elementPoint.x - screenSize.width * anchor.horizontalFactor,
            elementPoint.y - screenSize.height * anchor.verticalFactor,
        )
    }

    private fun assertClose(expected: Float, actual: Float, message: String = "") {
        assertTrue(abs(expected - actual) <= 0.01f) {
            "$message expected $expected but was $actual"
        }
    }
}
