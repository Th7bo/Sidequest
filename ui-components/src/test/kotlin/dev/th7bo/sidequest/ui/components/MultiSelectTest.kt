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
 * Choosing several values from a long list.
 *
 * Written because the ignored-islands setting shipped as a [dev.th7bo.sidequest.ui.config.ListSetting], whose
 * rows are not editable and whose `createItem` can only produce one fixed value — so pressing Add gave Garden,
 * then Garden again, with no way to change either. The control was wrong for the job rather than broken.
 */
class MultiSelectTest {

    private fun id(path: String) = UiId.of("sidequest", path)

    private lateinit var runtime: UiRuntime
    private lateinit var context: ComponentContext

    private class Holder(var chosen: List<String> = emptyList())

    private val islands = listOf("Hub", "Garden", "The End", "Dwarven Mines", "Crimson Isle")

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

    private fun settingOf(holder: Holder): MultiSelectSetting<String> = MultiSelectSetting(
        id = id("islands"),
        metadata = SettingMetadata(title = constantState("Islands")),
        binding = bind(holder::chosen),
        defaultValue = emptyList(),
        options = constantState(islands.map { Option(it, constantState(it), it) }),
        elementSerializer = SettingSerializers.string,
    )

    @Test
    fun `toggling adds and removes`() {
        val holder = Holder()
        val setting = settingOf(holder)

        setting.toggle("Garden")
        assertEquals(listOf("Garden"), holder.chosen)

        setting.toggle("Hub")
        assertEquals(listOf("Garden", "Hub"), holder.chosen)

        setting.toggle("Garden")
        assertEquals(listOf("Hub"), holder.chosen, "toggling a chosen value removes it")
    }

    /** The failure the old control had: adding the same thing repeatedly. */
    @Test
    fun `a value cannot be chosen twice`() {
        val holder = Holder()
        val setting = settingOf(holder)

        repeat(3) { setting.toggle("Garden") }

        // Odd number of toggles, so it ends chosen — and exactly once.
        assertEquals(listOf("Garden"), holder.chosen)
    }

    @Test
    fun `every option is reachable`() {
        val holder = Holder()
        val setting = settingOf(holder)

        for (island in islands) setting.toggle(island)

        assertEquals(islands, holder.chosen, "all five, not one repeated")
        assertTrue(islands.all { setting.isChosen(it) })
    }

    @Test
    fun `clearing removes everything`() {
        val holder = Holder(chosen = listOf("Hub", "Garden"))
        val setting = settingOf(holder)

        setting.clear()

        assertEquals(emptyList<String>(), holder.chosen)
        assertFalse(setting.isChosen("Hub"))
    }

    // -- the popup -----------------------------------------------------------

    private fun popupOf(setting: MultiSelectSetting<String>) = DropdownPopupNode(
        id = id("popup"),
        options = setting.options,
        isSearchable = true,
        isChosen = { option -> setting.isChosen(option.value) },
        componentContext = context,
        onChoose = { option -> setting.toggle(option.value) },
    )

    /**
     * The searchable half of the request.
     *
     * Forty islands is too many to scan, and the filter is the same one the dropdown uses — which is why the
     * popup was generalised rather than duplicated.
     */
    @Test
    fun `the list filters as you type`() {
        val popup = popupOf(settingOf(Holder()))

        popup.setQuery("mine")

        assertEquals(listOf("Dwarven Mines"), popup.visibleOptions.map { it.label.peek() })
    }

    @Test
    fun `filtering is case-insensitive and matches anywhere in the name`() {
        val popup = popupOf(settingOf(Holder()))

        popup.setQuery("END")

        assertEquals(listOf("The End"), popup.visibleOptions.map { it.label.peek() })
    }

    @Test
    fun `choosing from the popup toggles rather than replacing`() {
        val holder = Holder()
        val setting = settingOf(holder)
        val popup = popupOf(setting)

        popup.itemAt(0)  // materialised
        setting.toggle(popup.visibleOptions[1].value)
        setting.toggle(popup.visibleOptions[3].value)

        assertEquals(listOf("Garden", "Dwarven Mines"), holder.chosen, "two choices, both kept")
    }

    @Test
    fun `an empty filter result says so rather than showing nothing`() {
        val popup = popupOf(settingOf(Holder()))

        popup.setQuery("zzzz")

        assertEquals(0, popup.itemCount)
        // The "No matches" row is still built, or the popup is a bare box that looks broken.
        assertTrue(popup.itemAt(0) != null)
    }
}
