package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.binding.bind
import dev.th7bo.sidequest.ui.config.MultiSelectSetting
import dev.th7bo.sidequest.ui.config.Option
import dev.th7bo.sidequest.ui.config.SettingMetadata
import dev.th7bo.sidequest.ui.config.SettingSerializers
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A popup with more options than fit.
 *
 * Forty islands made the picker as tall as the screen, because the surface grew to whatever its item stack
 * measured and nothing scrolled. The cap and the wheel live on [PopupSurfaceNode] rather than on the
 * multi-select, so a long dropdown gets both as well — a list of forty is a list of forty whichever control
 * opened it.
 */
class PopupScrollTest {

    private fun id(path: String) = UiId.of("sidequest", path)

    private lateinit var runtime: UiRuntime
    private lateinit var context: ComponentContext

    private class Holder(var chosen: List<String> = emptyList())

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

    private fun popupOf(count: Int): DropdownPopupNode<String> {
        val names = (1..count).map { "Island $it" }
        val setting = MultiSelectSetting(
            id = id("islands"),
            metadata = SettingMetadata(title = constantState("Islands")),
            binding = bind(Holder()::chosen),
            defaultValue = emptyList(),
            options = constantState(names.map { Option(it, constantState(it), it) }),
            elementSerializer = SettingSerializers.string,
        )
        return DropdownPopupNode(
            id = id("popup"),
            options = setting.options,
            isSearchable = true,
            isChosen = { setting.isChosen(it.value) },
            componentContext = context,
            onChoose = { setting.toggle(it.value) },
        )
    }

    private fun heightOf(popup: DropdownPopupNode<String>): Float {
        runtime.layout(popup)
        return popup.measuredSize.height
    }

    /**
     * The regression: a long list does not grow without bound.
     *
     * Forty options is the island list. The popup is a control on a screen, and one that covers the screen is
     * not a control.
     */
    @Test
    fun `a long list is capped rather than growing to fit`() {
        val short = heightOf(popupOf(4))
        val long = heightOf(popupOf(40))
        val longer = heightOf(popupOf(200))

        assertTrue(long > short, "it still grows while it fits")
        assertEquals(long, longer, "and stops growing once it does not")
        assertTrue(longer < 300f, "the popup must not fill a 300-unit viewport, was $longer")
    }

    @Test
    fun `a short list neither scrolls nor reserves a scrollbar`() {
        val popup = popupOf(3)
        heightOf(popup)

        assertFalse(popup.isScrollable)
        assertFalse(popup.scrollBy(50f), "there is nowhere to go")
        assertEquals(0f, popup.scrollOffset)
    }

    @Test
    fun `a long list scrolls and clamps at both ends`() {
        val popup = popupOf(40)
        heightOf(popup)

        assertTrue(popup.isScrollable)

        assertTrue(popup.scrollBy(30f))
        assertEquals(30f, popup.scrollOffset)

        // Past the end clamps rather than running off.
        popup.scrollBy(10_000f)
        val bottom = popup.scrollOffset
        assertFalse(popup.scrollBy(10f), "already at the bottom")

        popup.scrollBy(-10_000f)
        assertEquals(0f, popup.scrollOffset, "and back to the top")
        assertTrue(bottom > 0f)
    }

    /**
     * Filtering returns to the top.
     *
     * Scrolling to the end of forty and then typing a filter that matches two would otherwise leave the view
     * past the end of the new list — which looks exactly like a filter that matched nothing.
     */
    @Test
    fun `filtering a scrolled list returns it to the top`() {
        val popup = popupOf(40)
        heightOf(popup)
        popup.scrollBy(10_000f)
        assertTrue(popup.scrollOffset > 0f)

        popup.setQuery("Island 7")

        assertEquals(0f, popup.scrollOffset)
    }

    @Test
    fun `scrolling back to a full list keeps every option reachable`() {
        val popup = popupOf(40)
        heightOf(popup)

        assertEquals(40, popup.itemCount, "capping the height must not drop options")
        popup.scrollBy(10_000f)
        assertEquals(40, popup.itemCount)
    }
}
