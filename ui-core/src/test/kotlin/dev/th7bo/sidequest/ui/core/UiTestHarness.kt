package dev.th7bo.sidequest.ui.core

import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.diagnostics.FrameMetrics
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.testkit.FakeTextMeasurer
import dev.th7bo.sidequest.ui.testkit.RecordingRenderer
import dev.th7bo.sidequest.ui.theme.DarkTheme
import dev.th7bo.sidequest.ui.theme.Theme

/**
 * A runtime wired to the recording renderer, so a whole UI can be driven headlessly.
 *
 * Frames advance by an explicit delta rather than by wall clock, which is what makes
 * animation assertions deterministic.
 */
class UiTestHarness(
    viewport: Size = Size(320f, 180f),
    theme: Theme = DarkTheme,
) {

    val textMeasurer: FakeTextMeasurer = FakeTextMeasurer()
    val renderer: RecordingRenderer = RecordingRenderer(viewport, textMeasurer)
    val runtime: UiRuntime = UiRuntime(theme)

    init {
        runtime.viewport = viewport
        runtime.prepare(renderer)
    }

    var root: UiNode?
        get() = runtime.root
        set(value) {
            runtime.root = value
        }

    /** Runs one frame and returns its metrics. */
    fun frame(deltaSeconds: Float = FRAME_DELTA): FrameMetrics {
        renderer.beginFrame(deltaSeconds)
        val metrics = runtime.frame(renderer, deltaSeconds)
        renderer.endFrame()
        return metrics
    }

    /** Runs [count] frames, returning the metrics of the last one. */
    fun frames(count: Int, deltaSeconds: Float = FRAME_DELTA): FrameMetrics {
        var metrics = frame(deltaSeconds)
        repeat(count - 1) { metrics = frame(deltaSeconds) }
        return metrics
    }

    /** A layout context matching the one the runtime would build. */
    fun layoutContext(): LayoutContext =
        LayoutContext(runtime.diagnostics, textMeasurer, runtime.theme)

    fun dispose() {
        runtime.dispose()
    }

    companion object {
        const val FRAME_DELTA: Float = 1f / 60f

        /** Short helper so test trees read as structure rather than as id boilerplate. */
        fun id(path: String): UiId = UiId.of("test", path)
    }
}
