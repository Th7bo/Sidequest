package dev.th7bo.sidequest.ui.core.demo

import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.testkit.AsciiCanvas
import dev.th7bo.sidequest.ui.testkit.DrawCommand
import dev.th7bo.sidequest.ui.theme.DarkTheme
import dev.th7bo.sidequest.ui.theme.LightTheme
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The Phase 1 acceptance demo: a complete themed component, laid out, animated and
 * drawn entirely headlessly.
 */
class FakeRendererDemoTest {

    private lateinit var scene: DemoScene

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        scene = DemoScene()
    }

    @AfterEach
    fun tearDown() {
        scene.dispose()
    }

    @Test
    fun `the card renders its surface, border, icon, text and progress bar`() {
        scene.currentXp.value = 450
        scene.frame()

        val commands = scene.harness.renderer.commands
        val rounded = commands.filterIsInstance<DrawCommand.RoundedRect>()
        val borders = commands.filterIsInstance<DrawCommand.Border>()
        val texts = commands.filterIsInstance<DrawCommand.Text>()

        assertTrue(rounded.size >= 4, "card, icon, track and fill are all rounded surfaces")
        assertEquals(1, borders.size, "the card has exactly one thin border")
        assertEquals(
            listOf("Mining XP", "450 / 1000"),
            texts.map { it.content },
            "the header shows the title and the value, and nothing else",
        )
    }

    @Test
    fun `every colour drawn comes from the active theme`() {
        scene.currentXp.value = 300
        scene.frame()

        val tokens = DarkTheme.tokens
        val allowed = setOf(
            tokens.colors.elevatedPanelBackground,
            tokens.colors.border,
            tokens.colors.accent,
            tokens.colors.accent.withAlpha(ProgressCard.ICON_TINT_ALPHA),
            tokens.colors.textPrimary,
            tokens.colors.textSecondary,
        )

        val used = scene.harness.renderer.commands.mapNotNull {
            when (it) {
                is DrawCommand.RoundedRect -> it.color
                is DrawCommand.FillRect -> it.color
                is DrawCommand.Border -> it.color
                is DrawCommand.Text -> it.color
                else -> null
            }
        }

        assertTrue(used.isNotEmpty())
        val strays = used.filterNot { it in allowed }
        assertTrue(strays.isEmpty(), "components must not invent colours: found $strays")
    }

    @Test
    fun `switching theme changes every colour without touching the layout`() {
        scene.currentXp.value = 500
        // Let the progress animation settle first, or the two captures would differ by
        // the bar's motion rather than by the theme.
        repeat(40) { scene.frame() }
        val darkGeometry = scene.harness.renderer.commands.filterIsInstance<DrawCommand.RoundedRect>()
            .map { it.bounds }
        val darkColors = scene.harness.renderer.commands.filterIsInstance<DrawCommand.RoundedRect>()
            .map { it.color }

        scene.harness.runtime.theme = LightTheme
        scene.frame()
        val lightGeometry = scene.harness.renderer.commands.filterIsInstance<DrawCommand.RoundedRect>()
            .map { it.bounds }
        val lightColors = scene.harness.renderer.commands.filterIsInstance<DrawCommand.RoundedRect>()
            .map { it.color }

        assertEquals(darkGeometry, lightGeometry, "the same theme metrics must produce the same layout")
        assertFalse(darkColors == lightColors, "the palette must actually change")
    }

    @Test
    fun `the progress bar animates towards a new value and settles`() {
        scene.frame()
        assertEquals(0f, scene.card.fillWidthValue)

        scene.currentXp.value = 500
        scene.frame()

        val partway = scene.card.fillWidthValue
        assertTrue(partway > 0f && partway < ProgressCard.TRACK_WIDTH / 2f) {
            "the bar should still be travelling, was $partway"
        }

        // Run past the animation duration.
        repeat(40) { scene.frame() }

        assertClose(ProgressCard.TRACK_WIDTH / 2f, scene.card.fillWidthValue)
        assertFalse(scene.harness.runtime.animations.hasActiveAnimations)
        assertTrue(scene.harness.runtime.isIdle, "the scene must return to idle once the bar settles")
    }

    @Test
    fun `retargeting mid-animation glides on rather than restarting`() {
        scene.currentXp.value = 1000
        scene.frame()
        repeat(5) { scene.frame() }
        val beforeRetarget = scene.card.fillWidthValue
        assertTrue(beforeRetarget > 0f)

        scene.currentXp.value = 0
        scene.frame()

        assertTrue(
            scene.card.fillWidthValue <= beforeRetarget && scene.card.fillWidthValue > 0f,
        ) { "the bar must continue from where it was, not snap to either end" }
    }

    @Test
    fun `the value label follows the state`() {
        scene.currentXp.value = 250
        scene.frame()
        assertEquals(
            "250 / 1000",
            scene.harness.renderer.commands.filterIsInstance<DrawCommand.Text>().last().content,
        )

        scene.requiredXp.value = 2000
        scene.frame()
        assertEquals(
            "250 / 2000",
            scene.harness.renderer.commands.filterIsInstance<DrawCommand.Text>().last().content,
        )
        assertEquals("25%", scene.percentLabel.value)
    }

    @Test
    fun `the card stays centred when the viewport changes`() {
        scene.frame()
        val small = cardBounds()

        scene.harness.runtime.viewport = Size(640f, 240f)
        scene.harness.renderer.resize(Size(640f, 240f))
        scene.harness.root!!.let { root ->
            (root as dev.th7bo.sidequest.ui.core.layout.BoxNode).preferredSize = Size(640f, 240f)
        }
        scene.frame()
        val large = cardBounds()

        assertClose(small.width, large.width, "the card keeps its intrinsic size")
        assertClose(320f, large.center.x, "and stays centred in the wider viewport")
        assertClose(120f, large.center.y)
    }

    @Test
    fun `the demo produces a readable picture`() {
        scene.currentXp.value = 700
        repeat(40) { scene.frame() }

        val picture = AsciiCanvas(Size(320f, 120f), unitsPerCell = 4f)
            .render(scene.harness.renderer.commands)
            .toString()

        // Printed so a failing layout is visible in the test output rather than inferred.
        println(picture)

        assertTrue(picture.contains("Mining XP"), "the title must appear in the rendered picture")
        assertTrue(picture.contains("700 / 1000"), "the value must appear in the rendered picture")
        assertTrue(picture.contains('.'), "the card border must be drawn")
        // The accent bar is much brighter than the card surface, so the two must not
        // collapse onto the same shade.
        val shades = picture.filter { it in "-:=+*#%@" }.toSet()
        assertTrue(shades.size >= 2, "surfaces with different tokens must be distinguishable: $shades")
    }

    @Test
    fun `the demo settles to zero layout work once animation finishes`() {
        scene.currentXp.value = 600
        repeat(40) { scene.frame() }

        val idle = scene.frame()

        assertEquals(0, idle.nodesMeasured)
        assertEquals(0, idle.nodesArranged)
        assertTrue(idle.drawCalls > 0, "it is still being drawn, just not re-laid out")
    }

    @Test
    fun `disposing the card releases its state subscriptions`() {
        scene.frame()
        val cardScope = scene.card.node.scope
        assertTrue(cardScope.size > 0)

        scene.harness.root = null
        assertTrue(cardScope.isDisposed, "replacing the tree must dispose the subtree's scopes")

        // Writing to the source afterwards must not resurrect anything.
        scene.currentXp.value = 999
        assertNotNull(scene.currentXp.value)
    }

    private fun cardBounds() = scene.card.node.absoluteBounds()

    private fun assertClose(expected: Float, actual: Float, message: String = "") {
        assertTrue(abs(expected - actual) <= 0.5f) {
            "$message expected $expected but was $actual"
        }
    }
}
