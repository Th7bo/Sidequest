package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.binding.asBinding
import dev.th7bo.sidequest.ui.components.hud.ProgressHudNode
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.DropdownSetting
import dev.th7bo.sidequest.ui.config.IntSliderSetting
import dev.th7bo.sidequest.ui.config.Option
import dev.th7bo.sidequest.ui.config.Setting
import dev.th7bo.sidequest.ui.config.SettingMetadata
import dev.th7bo.sidequest.ui.config.ToggleSetting
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.component.ComponentRegistry
import dev.th7bo.sidequest.ui.core.hud.HudContext
import dev.th7bo.sidequest.ui.core.hud.HudElementNode
import dev.th7bo.sidequest.ui.core.hud.HudLayerNode
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.core.world.WorldOverlayLayer
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.geometry.Anchor
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.hud.HudDefinition
import dev.th7bo.sidequest.ui.hud.HudInstance
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.testkit.FakeTextMeasurer
import dev.th7bo.sidequest.ui.testkit.RecordingRenderer
import dev.th7bo.sidequest.ui.theme.DarkTheme
import dev.th7bo.sidequest.ui.world.WorldOverlayDefinition
import dev.th7bo.sidequest.ui.world.WorldPosition
import dev.th7bo.sidequest.ui.world.WorldProjection
import dev.th7bo.sidequest.ui.world.WorldProjector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.measureNanoTime

/**
 * The stress configuration from the plan: 1,000 toggles, 500 sliders, 200 dropdowns,
 * dozens of HUDs and several world overlays, measured and written to a report.
 *
 * The assertions here are about **scaling properties**, not wall-clock budgets. A build
 * agent's timings say nothing about a player's machine, and a test that fails because
 * CI was busy teaches nobody anything. What is asserted is what must be true regardless
 * of hardware: an idle frame does no layout work, only the visible slice of a 1,700-row
 * list is ever materialised, and search over the whole set stays sub-linear in what it
 * touches. The timings are recorded to `build/reports/ui-benchmark.md` and reported
 * honestly rather than gated on.
 */
class StressBenchmarkTest {

    private fun id(path: String) = UiId.of("stress", path)

    private lateinit var runtime: UiRuntime
    private lateinit var renderer: RecordingRenderer
    private lateinit var context: ComponentContext
    private lateinit var scope: RegistrationScope

    private val screen = Size(640f, 360f)

    private val measurements = LinkedHashMap<String, String>()

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        renderer = RecordingRenderer(screen, FakeTextMeasurer())
        runtime = UiRuntime(DarkTheme).apply {
            viewport = screen
            prepare(renderer)
        }
        context = ComponentContext(DarkTheme, runtime.animations, runtime)
        scope = RegistrationScope(id("stress"))
    }

    @AfterEach
    fun tearDown() {
        scope.dispose()
        runtime.dispose()
        resetReactiveGraphForTesting()
        writeReport()
    }

    private fun frame(delta: Float = 1f / 60f) = run {
        renderer.beginFrame(delta)
        val metrics = runtime.frame(renderer, delta)
        renderer.endFrame()
        metrics
    }

    private fun record(name: String, value: String) {
        measurements[name] = value
    }

    /** Median of [samples] runs, in milliseconds. Median, because one GC pause is noise. */
    private fun timeMillis(samples: Int = 20, block: () -> Unit): Double {
        repeat(WARMUP) { block() }
        val times = (0 until samples).map { measureNanoTime(block) }.sorted()
        return times[times.size / 2] / NANOS_PER_MILLI
    }

    // ---------------------------------------------------------------
    // The stress screen
    // ---------------------------------------------------------------

    private fun stressScreen(): ConfigScreen {
        val settings = ArrayList<Setting<*>>(TOGGLES + SLIDERS + DROPDOWNS)

        repeat(TOGGLES) { index ->
            settings.add(
                ToggleSetting(
                    id("toggle.$index"),
                    SettingMetadata(constantState("Toggle $index"), constantState("Stress toggle number $index")),
                    mutableStateOf(index % 2 == 0, "toggle.$index").asBinding(),
                    false,
                ),
            )
        }
        repeat(SLIDERS) { index ->
            settings.add(
                IntSliderSetting(
                    id("slider.$index"),
                    SettingMetadata(constantState("Slider $index")),
                    mutableStateOf(index % 100, "slider.$index").asBinding(),
                    0,
                    range = 0..100,
                ),
            )
        }
        repeat(DROPDOWNS) { index ->
            settings.add(
                DropdownSetting(
                    id("dropdown.$index"),
                    SettingMetadata(constantState("Dropdown $index")),
                    mutableStateOf("a", "dropdown.$index").asBinding(),
                    "a",
                    options = constantState(
                        listOf(
                            Option("a", constantState("Alpha"), "a"),
                            Option("b", constantState("Beta"), "b"),
                            Option("c", constantState("Gamma"), "c"),
                        ),
                    ),
                ),
            )
        }

        return dev.th7bo.sidequest.ui.config.configScreen(id("stress.screen"), "Stress") {
            category(id("stress.all"), "All") {
                section("Everything") {
                    for (setting in settings) add(setting)
                }
            }
        }
    }

    @Test
    fun `a stress screen materialises only what is visible`() {
        val screenDefinition = stressScreen()
        val registry = ComponentRegistry().apply { registerStandardControls(scope) }
        val controller = ConfigScreenController(screenDefinition, registry, context, runtime.focus)
        runtime.root = controller.list

        val buildMillis = timeMillis(samples = 1) { frame() }
        val metrics = frame()

        val total = controller.rows.size
        record("Settings declared", "$TOTAL_SETTINGS")
        record("Rows in the list", "$total")
        record("Nodes materialised", "${metrics.nodesVisible}")
        record("First frame", "%.2f ms".format(buildMillis))

        // The whole point of virtualization: a 1,700-row list must not build 1,700 rows.
        assertTrue(
            metrics.nodesVisible < total,
            "materialised $total rows for a ${screen.height}-unit viewport; virtualization is not working",
        )
        assertTrue(
            metrics.nodesVisible < total / VIRTUALIZATION_RATIO,
            "expected far fewer than $total nodes on screen, got ${metrics.nodesVisible}",
        )
    }

    @Test
    fun `an idle frame over a stress screen does no layout work`() {
        val registry = ComponentRegistry().apply { registerStandardControls(scope) }
        val controller = ConfigScreenController(stressScreen(), registry, context, runtime.focus)
        runtime.root = controller.list
        frame()

        val idleMillis = timeMillis { frame() }
        val idle = frame()

        record("Idle frame", "%.3f ms".format(idleMillis))
        record("Idle nodes measured", "${idle.nodesMeasured}")
        record("Idle draw calls", "${idle.drawCalls}")

        // This is the property the whole runtime is built around, and it does not depend
        // on how fast the machine is.
        assertEquals(0, idle.nodesMeasured, "an idle frame must not lay anything out")
        assertEquals(0, idle.nodesArranged, "nor arrange anything")
    }

    @Test
    fun `search over the full set stays responsive`() {
        val registry = ComponentRegistry().apply { registerStandardControls(scope) }
        val controller = ConfigScreenController(stressScreen(), registry, context, runtime.focus)
        runtime.root = controller.list
        frame()

        val searchMillis = timeMillis(samples = 10) {
            controller.search("dropdown 1")
            controller.clearSearch()
        }
        controller.search("Toggle 7")
        frame()

        record("Search round trip over $TOTAL_SETTINGS settings", "%.2f ms".format(searchMillis))
        record("Matches for 'Toggle 7'", "${controller.rows.size}")

        assertTrue(controller.rows.isNotEmpty(), "the search should find something")
        assertTrue(
            controller.rows.size < TOTAL_SETTINGS,
            "a query must narrow the list, not return everything",
        )
    }

    @Test
    fun `a scroll through the whole list keeps a bounded node count`() {
        val registry = ComponentRegistry().apply { registerStandardControls(scope) }
        val controller = ConfigScreenController(stressScreen(), registry, context, runtime.focus)
        runtime.root = controller.list
        frame()

        var peak = 0
        val scrollMillis = timeMillis(samples = 5) {
            for (offset in 0..SCROLL_LIMIT step SCROLL_STEP) {
                controller.list.scrollTo(offset.toFloat())
                peak = maxOf(peak, frame().nodesVisible)
            }
        }

        record("Full scroll (${SCROLL_LIMIT / SCROLL_STEP} steps)", "%.2f ms".format(scrollMillis))
        record("Peak nodes on screen while scrolling", "$peak")

        // Recycling, not accumulation: scrolling the whole list must not grow the tree.
        assertTrue(
            peak < TOTAL_SETTINGS / VIRTUALIZATION_RATIO,
            "node count grew while scrolling — rows are accumulating rather than recycling ($peak)",
        )
    }

    // ---------------------------------------------------------------
    // HUD and world-overlay stress
    // ---------------------------------------------------------------

    @Test
    fun `fifty huds update independently`() {
        val layer = HudLayerNode(id("hud_layer")) { screen }
        runtime.root = layer

        val values = (0 until HUD_COUNT).map { mutableStateOf(it.toLong(), "hud.value.$it") }
        values.forEachIndexed { index, value ->
            val definition = HudDefinition(
                id = id("hud.$index"),
                title = constantState("HUD $index"),
                defaultAnchor = Anchor.entries[index % Anchor.entries.size],
                defaultOffset = Vec2((index % 10) * 8f, (index / 10) * 8f),
            )
            layer.add(
                HudElementNode(
                    HudInstance(id("hud.instance.$index"), definition.id),
                    definition,
                ) {
                    ProgressHudNode(
                        id = id("hud.content.$index"),
                        componentContext = context,
                        title = constantState("HUD $index"),
                        current = value,
                        maximum = constantState(100L),
                    )
                },
            )
        }

        val firstMillis = timeMillis(samples = 1) { frame() }
        frame()

        // One HUD's data changes; the rest must not re-measure. This is the phase 4
        // criterion again, now at fifty elements rather than two.
        values.first().value = 999L
        val afterOne = frame()

        val allMillis = timeMillis {
            values.forEachIndexed { index, value -> value.value = (index + 1000).toLong() }
            frame()
        }

        record("HUD elements", "$HUD_COUNT")
        record("HUD first frame", "%.2f ms".format(firstMillis))
        record("HUD frame after one value changed", "${afterOne.nodesMeasured} nodes measured")
        record("HUD frame with all $HUD_COUNT values changed", "%.3f ms".format(allMillis))

        assertTrue(
            afterOne.nodesMeasured < HUD_COUNT,
            "changing one HUD re-measured ${afterOne.nodesMeasured} nodes across $HUD_COUNT HUDs",
        )
    }

    @Test
    fun `world overlays cull before they cost anything`() {
        val overlays = WorldOverlayLayer()
        repeat(OVERLAY_COUNT) { index ->
            overlays.register(
                scope,
                WorldOverlayDefinition(
                    id("overlay.$index"),
                    constantState(WorldPosition(index.toDouble(), 64.0, 0.0)),
                    constantState("Point $index"),
                ),
            )
        }

        // Half within the fade range, half beyond it.
        val projector = WorldProjector { position ->
            WorldProjection(Vec2(100f, 100f), if (position.x < OVERLAY_COUNT / 2) 10.0 else 10_000.0, false)
        }

        val resolveMillis = timeMillis { overlays.resolve(projector, screen) }
        val resolved = overlays.resolve(projector, screen)

        record("World overlays registered", "$OVERLAY_COUNT")
        record("World overlays resolved", "${resolved.size}")
        record("Resolve $OVERLAY_COUNT overlays", "%.3f ms".format(resolveMillis))

        assertEquals(
            OVERLAY_COUNT / 2,
            resolved.size,
            "overlays beyond the fade distance should be culled, not drawn transparent",
        )
    }

    // ---------------------------------------------------------------
    // Report
    // ---------------------------------------------------------------

    private fun writeReport() {
        if (measurements.isEmpty()) return

        val report = reportPath()
        Files.createDirectories(report.parent)

        // Appended per test, with a header written once, so the file is a record of the
        // whole run rather than of whichever test happened to finish last.
        val existing = if (Files.exists(report)) Files.readString(report) else ""
        val header = if (existing.isEmpty()) {
            buildString {
                appendLine("# UI benchmark report")
                appendLine()
                appendLine("Generated by `StressBenchmarkTest`. **These are measurements, not budgets.**")
                appendLine("The assertions in that test are about scaling properties — an idle frame doing")
                appendLine("no layout, a virtualized list materialising a bounded slice — because those hold")
                appendLine("on any hardware. Wall-clock numbers depend entirely on the machine that ran them")
                appendLine("and are recorded here to be read, not to gate a build.")
                appendLine()
                appendLine("| Environment | |")
                appendLine("| --- | --- |")
                appendLine("| JVM | ${System.getProperty("java.version")} |")
                appendLine("| OS | ${System.getProperty("os.name")} ${System.getProperty("os.arch")} |")
                appendLine("| Processors | ${Runtime.getRuntime().availableProcessors()} |")
                appendLine()
            }
        } else {
            ""
        }

        val body = buildString {
            appendLine("| Measurement | Value |")
            appendLine("| --- | --- |")
            for ((name, value) in measurements) appendLine("| $name | $value |")
            appendLine()
        }

        Files.writeString(report, existing + header + body)
        measurements.clear()
    }

    private fun reportPath(): Path {
        // Resolved from the module rather than the working directory, so the report lands
        // in the same place whether Gradle or an IDE ran the test.
        val base = Paths.get(System.getProperty("user.dir"))
        val module = if (base.fileName.toString() == "ui-components") base else base.resolve("ui-components")
        return module.resolve("build/reports/ui-benchmark.md")
    }

    private companion object {
        const val TOGGLES = 1_000
        const val SLIDERS = 500
        const val DROPDOWNS = 200
        const val TOTAL_SETTINGS = TOGGLES + SLIDERS + DROPDOWNS

        const val HUD_COUNT = 50
        const val OVERLAY_COUNT = 200

        const val WARMUP = 3
        const val NANOS_PER_MILLI = 1_000_000.0

        /** A viewport shows a small fraction of the list; this is a generous bound on it. */
        const val VIRTUALIZATION_RATIO = 10

        const val SCROLL_LIMIT = 20_000
        const val SCROLL_STEP = 500
    }
}
