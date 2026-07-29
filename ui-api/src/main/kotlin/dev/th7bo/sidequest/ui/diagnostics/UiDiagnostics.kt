package dev.th7bo.sidequest.ui.diagnostics

/**
 * Per-frame instrumentation.
 *
 * These counters are how the performance budgets are verified rather than asserted.
 * `nodesMeasured` in particular is the idle-frame contract: a frame in which nothing
 * changed must measure zero nodes.
 */
public data class FrameMetrics(
    val frameIndex: Long = 0,

    /** Flushing scheduled work and reactive notifications. */
    val updateMillis: Double = 0.0,

    /** Measure plus arrange. */
    val layoutMillis: Double = 0.0,

    /** Walking the tree and emitting draw commands. */
    val renderPrepareMillis: Double = 0.0,

    /** Definitions that exist. */
    val nodesRegistered: Int = 0,

    /** Definitions with a live node. */
    val nodesMaterialized: Int = 0,

    /** Nodes actually measured this frame. Zero on an idle frame. */
    val nodesMeasured: Int = 0,

    /** Nodes repositioned this frame. */
    val nodesArranged: Int = 0,

    /** Materialized nodes intersecting the viewport. */
    val nodesVisible: Int = 0,

    /** Visible nodes that opted into input. */
    val hitTestCandidates: Int = 0,

    val drawCalls: Int = 0,
    val textLayouts: Int = 0,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
) {

    public val totalMillis: Double get() = updateMillis + layoutMillis + renderPrepareMillis

    public val cacheHitRate: Double
        get() = (cacheHits + cacheMisses).let { if (it == 0) 1.0 else cacheHits.toDouble() / it }

    override fun toString(): String = buildString {
        append("frame #").append(frameIndex)
        append(" update=").append(format(updateMillis))
        append("ms layout=").append(format(layoutMillis))
        append("ms render=").append(format(renderPrepareMillis))
        append("ms | measured=").append(nodesMeasured)
        append(" arranged=").append(nodesArranged)
        append(" visible=").append(nodesVisible)
        append("/").append(nodesMaterialized)
        append(" draws=").append(drawCalls)
    }

    private fun format(value: Double): String = ((value * 1000).toLong() / 1000.0).toString()
}

/**
 * Mutable collector the runtime writes into during a frame.
 *
 * Kept deliberately allocation-free: fields are reset per frame and [snapshot] is the
 * only thing that produces an object.
 */
public class UiDiagnostics {

    /** When false the runtime skips instrumentation entirely. Off in production builds. */
    public var enabled: Boolean = true

    public var frameIndex: Long = 0
        private set

    public var updateMillis: Double = 0.0
    public var layoutMillis: Double = 0.0
    public var renderPrepareMillis: Double = 0.0

    public var nodesRegistered: Int = 0
    public var nodesMaterialized: Int = 0
    public var nodesMeasured: Int = 0
    public var nodesArranged: Int = 0
    public var nodesVisible: Int = 0
    public var hitTestCandidates: Int = 0
    public var drawCalls: Int = 0
    public var textLayouts: Int = 0
    public var cacheHits: Int = 0
    public var cacheMisses: Int = 0

    private var lastFrame: FrameMetrics = FrameMetrics()

    /** The metrics of the most recently completed frame. */
    public val previousFrame: FrameMetrics get() = lastFrame

    /** Resets the per-frame counters. Called by the runtime at the start of a frame. */
    public fun beginFrame() {
        frameIndex++
        updateMillis = 0.0
        layoutMillis = 0.0
        renderPrepareMillis = 0.0
        nodesMeasured = 0
        nodesArranged = 0
        nodesVisible = 0
        hitTestCandidates = 0
        drawCalls = 0
        textLayouts = 0
        cacheHits = 0
        cacheMisses = 0
    }

    /** Publishes the frame's counters as an immutable snapshot. */
    public fun endFrame(): FrameMetrics {
        lastFrame = snapshot()
        return lastFrame
    }

    public fun snapshot(): FrameMetrics = FrameMetrics(
        frameIndex = frameIndex,
        updateMillis = updateMillis,
        layoutMillis = layoutMillis,
        renderPrepareMillis = renderPrepareMillis,
        nodesRegistered = nodesRegistered,
        nodesMaterialized = nodesMaterialized,
        nodesMeasured = nodesMeasured,
        nodesArranged = nodesArranged,
        nodesVisible = nodesVisible,
        hitTestCandidates = hitTestCandidates,
        drawCalls = drawCalls,
        textLayouts = textLayouts,
        cacheHits = cacheHits,
        cacheMisses = cacheMisses,
    )

    /** Times [block] in milliseconds, skipping the clock reads when disabled. */
    public inline fun <R> time(accumulate: (Double) -> Unit, block: () -> R): R {
        if (!enabled) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            accumulate((System.nanoTime() - start) / 1_000_000.0)
        }
    }
}
