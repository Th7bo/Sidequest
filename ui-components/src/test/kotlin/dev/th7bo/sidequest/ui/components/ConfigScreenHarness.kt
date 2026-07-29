package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.core.animation.AnimationHost
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.component.ComponentRegistry
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.diagnostics.FrameMetrics
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.testkit.FakeTextMeasurer
import dev.th7bo.sidequest.ui.testkit.RecordingRenderer
import dev.th7bo.sidequest.ui.theme.DarkTheme
import dev.th7bo.sidequest.ui.theme.Theme

/**
 * A full configuration screen driven headlessly: registry, controls, virtualized list,
 * search and input, with nothing stubbed out except the renderer itself.
 */
class ConfigScreenHarness(
    screen: ConfigScreen,
    viewport: Size = Size(420f, 300f),
    theme: Theme = DarkTheme,
) {

    val textMeasurer: FakeTextMeasurer = FakeTextMeasurer()
    val renderer: RecordingRenderer = RecordingRenderer(viewport, textMeasurer)
    val runtime: UiRuntime = UiRuntime(theme)

    val registrationScope: RegistrationScope = RegistrationScope(UiId.of("sidequest", "standard"))
    val registry: ComponentRegistry = ComponentRegistry()

    val context: ComponentContext = ComponentContext(
        theme = theme,
        animations = runtime.animations,
        scheduler = runtime,
        isDevelopment = true,
    )

    val controller: ConfigScreenController

    init {
        runtime.viewport = viewport
        runtime.prepare(renderer)
        registry.registerStandardControls(registrationScope)

        controller = ConfigScreenController(screen, registry, context, runtime.focus)
        runtime.root = controller.list
    }

    val list get() = controller.list

    fun frame(deltaSeconds: Float = 1f / 60f): FrameMetrics {
        renderer.beginFrame(deltaSeconds)
        val metrics = runtime.frame(renderer, deltaSeconds)
        renderer.endFrame()
        return metrics
    }

    fun frames(count: Int): FrameMetrics {
        var metrics = frame()
        repeat(count - 1) { metrics = frame() }
        return metrics
    }

    /** Every text drawn this frame, in paint order. */
    fun drawnText(): List<String> = renderer.commands
        .filterIsInstance<dev.th7bo.sidequest.ui.testkit.DrawCommand.Text>()
        .map { it.content }

    fun dispose() {
        registrationScope.dispose()
        runtime.dispose()
    }
}
