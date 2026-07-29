package dev.th7bo.sidequest.ui.core.runtime

import dev.th7bo.sidequest.ui.core.animation.AnimationHost
import dev.th7bo.sidequest.ui.core.focus.FocusManager
import dev.th7bo.sidequest.ui.core.input.InputDispatcher
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.diagnostics.FrameMetrics
import dev.th7bo.sidequest.ui.diagnostics.UiDiagnostics
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.rendering.TextMeasurer
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.state.Disposable
import dev.th7bo.sidequest.ui.state.DisposableScope
import dev.th7bo.sidequest.ui.state.UiScheduler
import dev.th7bo.sidequest.ui.state.UiThread
import dev.th7bo.sidequest.ui.theme.Theme
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Drives one UI tree: scheduling, animation, layout, paint and input.
 *
 * A frame runs **update**, then **layout**, then **paint**, with input dispatched
 * between frames. Layout is skipped entirely when nothing is dirty, so an idle frame
 * measures and arranges zero nodes.
 *
 * Paint still runs every frame, because Minecraft clears the screen every frame. The
 * contract is that the *tree is not rebuilt* on an idle frame, not that nothing is
 * drawn.
 *
 * Also serves as the [UiScheduler]: work submitted from a background thread is queued
 * and drained at the start of the update phase, never re-entrantly inside layout.
 */
public class UiRuntime(
    theme: Theme,
    public val diagnostics: UiDiagnostics = UiDiagnostics(),
) : UiScheduler, Disposable {

    /** Registrations owned by the runtime itself. */
    public val scope: DisposableScope = DisposableScope()

    public val focus: FocusManager = FocusManager()

    public val animations: AnimationHost = AnimationHost()

    public val input: InputDispatcher = InputDispatcher(focus) { root }

    /** Swapping themes invalidates measurement everywhere, since type metrics change. */
    public var theme: Theme = theme
        set(value) {
            if (field === value) return
            field = value
            invalidateEverything()
        }

    /** The tree's root. Replacing it disposes the previous tree. */
    public var root: UiNode? = null
        set(value) {
            if (field === value) return
            input.reset()
            focus.reset()
            field?.dispose()
            field = value
        }

    /**
     * The logical area the root is laid out into.
     *
     * Assigning a new viewport is how a resolution or GUI-scale change enters the
     * framework; nothing else needs to know it happened.
     */
    public var viewport: Size = Size(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        set(value) {
            if (field == value) return
            field = value
            invalidateEverything()
        }

    private val pendingWork = ConcurrentLinkedQueue<() -> Unit>()

    private var textMeasurer: TextMeasurer? = null

    init {
        focus.attach { root }
    }

    /**
     * Queues [block] to run on the UI thread at the start of the next frame.
     * Safe to call from any thread.
     */
    override fun submit(block: () -> Unit) {
        pendingWork.add(block)
    }

    /**
     * True when a frame would perform no layout work.
     *
     * Cheap: invalidation propagates to the root, so a clean root means a clean tree.
     */
    public val isIdle: Boolean
        get() = pendingWork.isEmpty() &&
            !animations.hasActiveAnimations &&
            root?.let { !it.needsMeasure && !it.needsArrange } != false

    /**
     * Supplies the renderer's text measurer before the first frame, for hosts that need
     * a measured size before anything is drawn. Optional: [frame] does this itself.
     */
    public fun prepare(renderer: UiRenderer) {
        textMeasurer = renderer.textMeasurer
    }

    /**
     * Runs one frame against [renderer].
     *
     * @param deltaSeconds time since the previous frame, used to advance animations.
     * @return metrics for the frame just completed.
     */
    public fun frame(renderer: UiRenderer, deltaSeconds: Float): FrameMetrics {
        UiThread.check()
        diagnostics.beginFrame()
        textMeasurer = renderer.textMeasurer

        diagnostics.time({ diagnostics.updateMillis += it }) {
            drainPendingWork()
            animations.tick(deltaSeconds, theme.tokens.accessibility.reducedMotion)
        }

        val currentRoot = root
        if (currentRoot != null) {
            diagnostics.time({ diagnostics.layoutMillis += it }) {
                layout(currentRoot)
            }
            diagnostics.time({ diagnostics.renderPrepareMillis += it }) {
                val context = RenderContext(diagnostics, theme, Rect.of(Vec2.Zero, viewport))
                currentRoot.paint(renderer, Vec2.Zero, context)
            }
            if (diagnostics.enabled) {
                // Counting materialized nodes is a tree walk, so it is a diagnostics-only
                // cost rather than something every production frame pays.
                diagnostics.nodesMaterialized = countNodes(currentRoot)
                diagnostics.nodesRegistered = diagnostics.nodesMaterialized
            }
        }

        return diagnostics.endFrame()
    }

    /**
     * Measures and arranges the tree, but only if the root is dirty.
     *
     * Invalidation always propagates to the root, so this check cannot miss a dirty
     * descendant, and a clean tree costs two boolean reads.
     */
    public fun layout(node: UiNode) {
        if (!node.needsMeasure && !node.needsArrange) return

        val context = LayoutContext(diagnostics, requireTextMeasurer(), theme)
        val constraints = Constraints(
            maxWidth = viewport.width,
            maxHeight = viewport.height,
        )
        val size = node.measure(constraints, context)
        node.arrange(Rect.of(Vec2.Zero, size), context)
    }

    /** Forces a full remeasure. Used for theme and viewport changes. */
    private fun invalidateEverything() {
        root?.forEachInTree { it.invalidateMeasure() }
    }

    private fun requireTextMeasurer(): TextMeasurer = checkNotNull(textMeasurer) {
        "No TextMeasurer available. Call UiRuntime.prepare(renderer) before laying out " +
            "outside of a frame."
    }

    private fun drainPendingWork() {
        while (true) {
            val work = pendingWork.poll() ?: return
            work.invoke()
        }
    }

    private fun countNodes(node: UiNode): Int {
        var total = 1
        for (child in node.children) total += countNodes(child)
        return total
    }

    override fun dispose() {
        input.reset()
        focus.reset()
        animations.clear()
        pendingWork.clear()
        root = null
        scope.dispose()
    }

    private companion object {
        const val DEFAULT_WIDTH = 1920f
        const val DEFAULT_HEIGHT = 1080f
    }
}
