package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.binding.bind
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.component.ComponentRegistry
import dev.th7bo.sidequest.ui.core.focus.FocusManager
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.mutableStateOf
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
 * Settings appearing and disappearing with their condition.
 *
 * `ConfigScreenController.refreshVisibility()` existed and **nothing ever called it**, which produced a bug
 * with a memorable shape: a setting hidden *while the screen was open* still had a materialized node and
 * simply stopped drawing, so it looked like the feature worked. A setting hidden when the screen was *built*
 * was filtered out of the row list and nothing ever put it back.
 *
 * So the case that appeared to work was the one where the feature was doing nothing, and the case that was
 * broken was the one it existed for.
 */
class ConfigVisibilityTest {

    private fun id(path: String) = UiId.of("sidequest", path)

    private lateinit var runtime: UiRuntime
    private lateinit var context: ComponentContext
    private lateinit var scope: RegistrationScope
    private lateinit var registry: ComponentRegistry
    private lateinit var focus: FocusManager

    private class Holder {
        var master = true
        var dependent = false
    }

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        val renderer = RecordingRenderer(Size(400f, 300f), FakeTextMeasurer())
        runtime = UiRuntime(DarkTheme).apply {
            viewport = Size(400f, 300f)
            prepare(renderer)
        }
        context = ComponentContext(DarkTheme, runtime.animations, runtime)
        scope = RegistrationScope(id("visibility"))
        registry = ComponentRegistry().apply { registerStandardControls(scope) }
        focus = FocusManager()
    }

    @AfterEach
    fun tearDown() {
        scope.dispose()
        runtime.dispose()
        resetReactiveGraphForTesting()
    }

    /** A screen where one setting is shown only while [gate] is true, mirroring the real config. */
    private fun screenOf(holder: Holder, gate: dev.th7bo.sidequest.ui.state.MutableUiState<Boolean>): ConfigScreen =
        configScreen(id("config"), "Test") {
            category(id("c"), "Category") {
                section("Section", id = id("c.s")) {
                    toggle(
                        id = id("c.s.master"),
                        title = "Master",
                        value = bind(
                            get = { holder.master },
                            set = { holder.master = it; gate.value = it },
                            debugName = "master",
                        ),
                    )
                    toggle(id("c.s.dependent"), "Dependent", bind(holder::dependent)) {
                        visibleWhen = gate
                    }
                }
            }
        }

    private fun visibleIds(controller: ConfigScreenController) =
        controller.provider.currentRows.filterIsInstance<ConfigRow.Entry>().map { it.setting.id }

    /**
     * The reported case: built while the gate is *off*, then turned on.
     *
     * The dependent setting was never in the row list, and no rebuild followed the change — so it stayed
     * absent no matter how many times the master was toggled.
     */
    @Test
    fun `a setting hidden at build time appears when its condition becomes true`() {
        val holder = Holder().apply { master = false }
        val gate = mutableStateOf(false, "gate")
        val controller = ConfigScreenController(screenOf(holder, gate), registry, context, focus)

        assertEquals(listOf(id("c.s.master")), visibleIds(controller), "the premise: it starts hidden")

        gate.value = true

        assertTrue(id("c.s.dependent") in visibleIds(controller), "turning it on has to bring the row back")
    }

    @Test
    fun `a setting shown at build time disappears when its condition becomes false`() {
        val gate = mutableStateOf(true, "gate")
        val controller = ConfigScreenController(screenOf(Holder(), gate), registry, context, focus)

        assertEquals(2, visibleIds(controller).size)

        gate.value = false

        assertEquals(listOf(id("c.s.master")), visibleIds(controller))
    }

    @Test
    fun `it survives being toggled repeatedly`() {
        val gate = mutableStateOf(false, "gate")
        val controller = ConfigScreenController(screenOf(Holder(), gate), registry, context, focus)

        repeat(5) {
            gate.value = true
            assertEquals(2, visibleIds(controller).size, "on")
            gate.value = false
            assertEquals(1, visibleIds(controller).size, "off")
        }
    }

    /** Disposing releases the subscriptions, so a closed screen stops rebuilding itself. */
    @Test
    fun `disposing stops the screen reacting`() {
        val gate = mutableStateOf(false, "gate")
        val controller = ConfigScreenController(screenOf(Holder(), gate), registry, context, focus)

        controller.dispose()
        gate.value = true

        assertEquals(listOf(id("c.s.master")), visibleIds(controller), "a disposed screen does not rebuild")
    }
}
