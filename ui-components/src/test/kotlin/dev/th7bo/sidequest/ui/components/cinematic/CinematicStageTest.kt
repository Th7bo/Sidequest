package dev.th7bo.sidequest.ui.components.cinematic

import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.ItemRef
import dev.th7bo.sidequest.ui.rendering.TextureRef
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.testkit.DrawCommand
import dev.th7bo.sidequest.ui.testkit.FakeTextMeasurer
import dev.th7bo.sidequest.ui.testkit.RecordingRenderer
import dev.th7bo.sidequest.ui.theme.DarkTheme
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The cinematic stage.
 *
 * Written after a bug a person reported and no test had: the counting number flashed. It flashed because the
 * measure pass pre-measured a set of values and the paint pass computed its own, continuously, so almost no
 * painted string had a layout and the draw was skipped — leaving the number invisible on most frames.
 *
 * So the assertions here are mostly about **what is on screen on every frame**, not about what the node
 * intends. A test that asked the node what it meant to draw would have passed throughout.
 */
class CinematicStageTest {

    private val screen = Size(400f, 300f)

    private lateinit var renderer: RecordingRenderer
    private lateinit var runtime: UiRuntime
    private lateinit var context: ComponentContext
    private lateinit var stage: CinematicStageNode

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        renderer = RecordingRenderer(screen, FakeTextMeasurer())
        runtime = UiRuntime(DarkTheme).apply {
            viewport = screen
            prepare(renderer)
        }
        context = ComponentContext(DarkTheme, runtime.animations, runtime)
        // No explicit size: the node measures to whatever it is given, and as the runtime's root that is the
        // viewport — which is what it gets on the HUD too.
        stage = CinematicStageNode(UiId.of("test", "cinematic"), context)
        runtime.root = stage
    }

    @AfterEach
    fun tearDown() {
        runtime.dispose()
        resetReactiveGraphForTesting()
    }

    private fun frame(): List<DrawCommand> {
        renderer.beginFrame(FRAME_DELTA)
        runtime.frame(renderer, FRAME_DELTA)
        renderer.endFrame()
        return renderer.commands
    }

    private fun texts() = renderer.commandsOfType<DrawCommand.Text>().map { it.content }

    /**
     * The regression.
     *
     * Once the number has appeared it must be on screen on *every* subsequent frame. A frame that draws
     * nothing is the flash, and the reason it happened is that a painted string had never been measured — so
     * this walks the rest of the run one frame at a time and demands a number on each.
     */
    @Test
    fun `the counting number is drawn on every frame once it has appeared`() {
        stage.elements = listOf(StageElement.Number(1_234_567, prefix = "", suffix = " coins"))

        var appearedAt: Float? = null
        val missing = mutableListOf<Float>()
        var step = 0
        while (step <= FRAMES) {
            stage.progress = step.toFloat() / FRAMES
            frame()
            val drawn = texts().any { it.endsWith(" coins") }
            if (drawn && appearedAt == null) appearedAt = stage.progress
            if (appearedAt != null && !drawn) missing.add(stage.progress)
            step++
        }

        assertNotNull(appearedAt, "the number never appeared at all")
        assertTrue(
            missing.isEmpty(),
            "the number vanished at progress ${missing.take(10)} (${missing.size} frame(s)) " +
                "after appearing at $appearedAt",
        )
    }

    /**
     * It counts from where it appears, and it lands on the real total.
     *
     * Counting from the start of the cinematic rather than from its own entrance would make it fade in already
     * part-counted, which reads as having missed the beginning of it.
     */
    @Test
    fun `the number counts up from its entrance and arrives at its value`() {
        stage.elements = listOf(StageElement.Number(1_000, prefix = "", suffix = ""))

        // Just after it appears it is near zero — not already two fifths of the way up, which is what counting
        // from the start of the cinematic instead of from its own entrance would give.
        stage.progress = 0.21f
        frame()
        val opening = texts().single().replace(",", "").toInt()
        assertTrue(opening < 100, "it appeared already at $opening of 1,000")

        stage.progress = 0.35f
        frame()
        val midway = texts().single().replace(",", "").toInt()
        assertTrue(midway in 1..999, "midway read $midway")

        // Half way through the run is where the count finishes, and it must be exact — a counter that stops at
        // 999 reads as a bug in the number rather than as a flourish.
        stage.progress = 0.5f
        frame()
        assertEquals(listOf("1,000"), texts())

        stage.progress = 1f
        frame()
        assertEquals(listOf("1,000"), texts(), "it stays landed for the rest of the run")
    }

    /**
     * The number never moves backwards.
     *
     * Quantising is what fixed the flash, and the way to get quantising wrong is to round in a way that dips.
     * A counter that goes 400, 390, 420 is worse than one that flashes.
     */
    @Test
    fun `the count never goes backwards`() {
        stage.elements = listOf(StageElement.Number(10_000, prefix = "", suffix = ""))

        var previous = -1L
        var step = 0
        while (step <= FRAMES) {
            stage.progress = step.toFloat() / FRAMES
            frame()
            // Nothing before its entrance; from then on it only ever goes up.
            val shown = texts().singleOrNull()?.replace(",", "")?.toLong() ?: run { step++; continue }
            assertTrue(shown >= previous, "went from $previous to $shown at ${stage.progress}")
            previous = shown
            step++
        }
        assertTrue(previous == 10_000L, "it should have finished at 10,000, not $previous")
    }

    @Test
    fun `thousands are grouped`() {
        stage.elements = listOf(StageElement.Number(1_234_567, prefix = "+", suffix = " coins"))
        stage.progress = 1f

        frame()

        assertEquals(listOf("+1,234,567 coins"), texts())
    }

    // -- the entrance --------------------------------------------------------

    /**
     * Pieces arrive in turn rather than all at once.
     *
     * Everything appearing on one frame reads as a screenshot rather than as something happening. The frame
     * lands first, then the title, then what it is about.
     */
    @Test
    fun `the title arrives before the subtitle`() {
        stage.elements = listOf(
            StageElement.Title("RARE DROP", colour = 0xFFAA00),
            StageElement.Subtitle("from a chest"),
        )

        stage.progress = 0.10f
        frame()
        assertTrue("RARE DROP" in texts(), "the title should be in by now: ${texts()}")
        assertFalse("from a chest" in texts(), "the subtitle should not have started: ${texts()}")

        stage.progress = 0.30f
        frame()
        assertEquals(listOf("RARE DROP", "from a chest"), texts())
    }

    /** A reveal keeps the cue it was given. */
    @Test
    fun `a reveal waits for its own cue`() {
        stage.elements = listOf(StageElement.Reveal("+1 Hyperion", atFraction = 0.6f))

        stage.progress = 0.5f
        frame()
        assertTrue(texts().isEmpty())

        stage.progress = 0.8f
        frame()
        assertEquals(listOf("+1 Hyperion"), texts())
    }

    /**
     * Nothing has an entrance that outlasts the cinematic.
     *
     * A piece cued so late that it is still fading in when the whole thing fades out would never be readable —
     * which is a thing an author can write, so the check is that the *defaults* do not do it.
     */
    @Test
    fun `every default entrance completes well before the end`() {
        stage.elements = listOf(
            StageElement.Title("t", colour = 0),
            StageElement.Subtitle("s"),
            StageElement.Number(10, "", ""),
            StageElement.Progress(0.5f, "p"),
        )

        stage.progress = 0.5f
        frame()

        assertEquals(4, texts().size, "everything should be fully in by halfway: ${texts()}")
    }

    // -- the item ------------------------------------------------------------

    /**
     * A drop's picture, drawn under the words that introduce it.
     *
     * The announcement reads top to bottom: how rare it was, why, and then the thing itself. The picture
     * carries the meaning — the name says what dropped and the item says it at a glance — but it arrives
     * after the line that sets it up rather than competing with the headline for the top of the screen.
     */
    @Test
    fun `an image is drawn centred below the subtitle`() {
        stage.elements = listOf(
            StageElement.Image(TextureRef(UiId.of("minecraft", "item.diamond"))),
            StageElement.Title("RARE DROP", colour = 0xFFAA00),
            StageElement.Subtitle("+168% Magic Find"),
        )
        stage.progress = 0.5f

        frame()

        val image = renderer.commandsOfType<DrawCommand.Image>().single()
        val centreX = screen.width / 2f
        assertTrue(
            image.bounds.x < centreX && image.bounds.right > centreX,
            "it should straddle the centre, was ${image.bounds}",
        )
        val lowestText = renderer.commandsOfType<DrawCommand.Text>().maxOf { it.position.y }
        assertTrue(image.bounds.top >= lowestText, "the picture sits below the words, not over them")
    }

    /** Sized from the height, so an ultrawide screen does not get an enormous one. */
    @Test
    fun `an image is square and scales with the viewport height`() {
        stage.elements = listOf(StageElement.Image(TextureRef(UiId.of("minecraft", "item.diamond"))))
        stage.progress = 0.5f

        frame()

        val image = renderer.commandsOfType<DrawCommand.Image>().single()
        assertEquals(image.bounds.width, image.bounds.height, 0.01f, "square")
        assertEquals(screen.height * DEFAULT_IMAGE_FRACTION, image.bounds.height, 0.01f)
    }

    /**
     * The picture arrives first.
     *
     * It is what the cinematic is about, so it leads rather than fading in after the words describing it.
     */
    @Test
    fun `the image is on screen before the title`() {
        stage.elements = listOf(
            StageElement.Image(TextureRef(UiId.of("minecraft", "item.diamond"))),
            StageElement.Title("RARE DROP", colour = 0xFFAA00),
        )

        stage.progress = 0.02f
        frame()

        assertEquals(1, renderer.commandsOfType<DrawCommand.Image>().size, "the item leads")
        assertTrue(texts().isEmpty(), "the title has not started: ${texts()}")
    }

    /**
     * A real item goes exactly where a flat picture would.
     *
     * The two are chosen between at the last moment — the host draws the item it recognises and falls back to
     * a texture for the rest — so the swap has to be invisible. If they disagreed about the box, the same drop
     * would appear in two different places depending on whether a lookup had landed yet.
     */
    @Test
    fun `an item occupies the same box a picture would`() {
        stage.elements = listOf(StageElement.Image(TextureRef(UiId.of("minecraft", "item.diamond"))))
        stage.progress = 0.5f
        frame()
        val asPicture = renderer.commandsOfType<DrawCommand.Image>().single().bounds

        stage.elements = listOf(StageElement.Item(ItemRef("minecraft:player_head", skin = "ewogIC")))
        frame()

        val asItem = renderer.commandsOfType<DrawCommand.DrawItem>().single()
        assertEquals(asPicture, asItem.bounds)
        assertEquals("ewogIC", asItem.item.skin, "the skin has to survive, or every head draws blank")
    }

    // -- the frame -----------------------------------------------------------

    @Test
    fun `nothing is drawn when nothing is playing`() {
        stage.elements = emptyList()
        stage.progress = 0.5f

        assertTrue(frame().isEmpty())
    }

    @Test
    fun `the letterbox draws two bars`() {
        stage.elements = listOf(StageElement.Letterbox(0.12f))
        stage.progress = 0.5f

        frame()

        val bars = renderer.commandsOfType<DrawCommand.FillRect>()
        assertEquals(2, bars.size)
        // Top and bottom, not one tall one — a single rect would cover the middle of the screen.
        assertTrue(bars.any { it.bounds.y == 0f })
        assertTrue(bars.any { it.bounds.bottom == screen.height })
    }

    /** The opacity stack has to balance, or every later frame is dimmed by whatever was left pushed. */
    @Test
    fun `the opacity stack is balanced across a whole run`() {
        stage.elements = listOf(
            StageElement.Letterbox(0.12f),
            StageElement.Title("t", colour = 0),
            StageElement.Number(100, "", ""),
            StageElement.Reveal("r", atFraction = 0.5f),
        )

        var step = 0
        while (step <= FRAMES) {
            stage.progress = step.toFloat() / FRAMES
            // `endFrame` inside `frame()` asserts the stacks are empty, so this passing is the assertion.
            frame()
            step++
        }
    }

    private companion object {
        const val FRAME_DELTA = 1f / 60f

        /**
         * How many steps of progress are walked.
         *
         * 240 is four seconds at sixty frames a second — the default cinematic length, so this covers the run
         * at the resolution it is actually drawn at.
         */
        const val FRAMES = 240
    }
}
