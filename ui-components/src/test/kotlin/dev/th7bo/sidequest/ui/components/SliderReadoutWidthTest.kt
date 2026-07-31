package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.binding.bind
import dev.th7bo.sidequest.ui.config.IntSliderSetting
import dev.th7bo.sidequest.ui.config.SettingMetadata
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.testkit.FakeTextMeasurer
import dev.th7bo.sidequest.ui.testkit.RecordingRenderer
import dev.th7bo.sidequest.ui.theme.DarkTheme
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A slider keeps the same width whatever its value reads.
 *
 * The row hands whatever the control does not use to the description beside it, so a control that grew or
 * shrank with its readout **re-wrapped the paragraph next to it while the slider was being dragged**. Going
 * from `85%` to `100%` is two pixels of readout and one line of reflowed text — which is what a screenshot
 * of the sound settings showed.
 */
class SliderReadoutWidthTest {

    private fun id(path: String) = UiId.of("sidequest", path)

    private lateinit var runtime: UiRuntime
    private lateinit var context: ComponentContext

    private class Holder(var value: Int = 0)

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        val renderer = RecordingRenderer(Size(400f, 300f), FakeTextMeasurer())
        runtime = UiRuntime(DarkTheme).apply {
            viewport = Size(400f, 300f)
            prepare(renderer)
        }
        context = ComponentContext(DarkTheme, runtime.animations, runtime)
    }

    @AfterEach
    fun tearDown() {
        runtime.dispose()
        resetReactiveGraphForTesting()
    }

    /**
     * The setting and its control, kept together.
     *
     * The value has to be written through the *setting* rather than through the backing property: a mirror
     * binding only re-reads its getter when asked, so poking the holder leaves the readout showing the old
     * text — and a width test where the text never changes passes without testing anything. The first draft
     * of this file did exactly that.
     */
    private class Slider(val setting: IntSliderSetting, val control: IntSliderControlNode)

    private fun sliderOf(format: (Int) -> String): Slider {
        val setting = IntSliderSetting(
            id = id("slider"),
            metadata = SettingMetadata(title = constantState("Slider")),
            binding = bind(Holder()::value),
            defaultValue = 0,
            range = 0..100,
            format = format,
        )
        return Slider(setting, IntSliderControlNode(setting, context))
    }

    private fun widthAt(slider: Slider, value: Int): Float {
        slider.setting.setUnchecked(value)
        slider.control.invalidateMeasure()
        runtime.layout(slider.control)
        return slider.control.measuredSize.width
    }

    /** What the readout actually says at each value, so a width test cannot pass on unchanging text. */
    private fun readouts(slider: Slider, vararg values: Int): Set<String> = values.mapTo(HashSet()) { value ->
        slider.setting.setUnchecked(value)
        slider.setting.format(slider.setting.value)
    }

    /**
     * The regression, in the exact shape that was reported.
     *
     * A percentage is the worst case in a config screen: `0%` is two characters and `100%` is four, and the
     * jump happens right at the end of the drag.
     */
    @Test
    fun `a percentage slider is the same width at every value`() {
        val slider = sliderOf { "$it%" }

        val widths = listOf(0, 5, 50, 85, 99, 100).map { widthAt(slider, it) }
        // The premise: the readout really does change, or this test proves nothing.
        assertEquals(setOf("0%", "5%", "50%", "85%", "99%", "100%"), readouts(slider, 0, 5, 50, 85, 99, 100))

        assertEquals(1, widths.distinct().size, "widths were $widths — the row beside it reflows")
    }

    /** A format whose shortest output is a word, which is what the volume sliders do at zero. */
    @Test
    fun `a slider whose zero reads as a word is still stable`() {
        val slider = sliderOf { if (it <= 0) "off" else "$it%" }

        val widths = listOf(0, 1, 50, 100).map { widthAt(slider, it) }

        assertEquals(1, widths.distinct().size, "widths were $widths")
    }

    /**
     * Reserving width must not clip a readout wider than every sample.
     *
     * The samples are ends and middle, so a format that peaks elsewhere is possible — it must come out too
     * wide rather than cut off.
     */
    @Test
    fun `a readout wider than every sample still fits`() {
        // 51 is not one of the sampled points, so nothing reserved room for it.
        val slider = sliderOf { if (it == 51) "a very long readout indeed" else "$it%" }

        val ordinary = widthAt(slider, 10)
        val unusual = widthAt(slider, 51)

        assertTrue(unusual > ordinary, "an unexpected readout must widen the control rather than be clipped")
    }

    @Test
    fun `the reserved width covers the widest sample and not merely the first`() {
        val narrow = sliderOf { "x" }
        val wide = sliderOf { "$it%" }

        assertTrue(
            widthAt(wide, 0) > widthAt(narrow, 0),
            "a slider reading '0%' must still reserve room for '100%'",
        )
    }
}
