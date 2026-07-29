package dev.th7bo.sidequest.ui.core.hud.editor

import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import kotlin.math.abs

/** Which axis a guide runs along. */
public enum class GuideAxis { VERTICAL, HORIZONTAL }

/** Why a guide appeared. Drawn differently so the source is readable at a glance. */
public enum class GuideKind {
    /** A screen edge. */
    EDGE,

    /** The screen's horizontal or vertical midline. */
    CENTER,

    /** An edge or centre of another HUD element. */
    ELEMENT,

    /** The boundary of a region the game already uses. */
    SAFE_AREA,
}

/**
 * A line the dragged element snapped to.
 *
 * Emitted only while a snap is active, so the editor draws a guide exactly when the
 * snap is doing something — a guide that is always on teaches the user nothing.
 */
public data class SnapGuide(
    public val axis: GuideAxis,
    /** Position along the guide's normal: an x for [GuideAxis.VERTICAL], else a y. */
    public val position: Float,
    public val kind: GuideKind,
)

/**
 * A region the game already draws in, which a HUD should not be dropped on top of.
 *
 * Advisory rather than enforced: a player who wants a HUD over the hotbar is allowed to
 * put it there. What the editor does is snap to the boundary and draw it, so landing on
 * top of the hotbar is a choice rather than an accident.
 */
public data class SafeArea(
    public val id: String,
    public val bounds: Rect,
) {
    public companion object {
        /**
         * The vanilla regions, in GUI units, for a screen of [screenSize].
         *
         * Derived from the screen size rather than hard-coded, because the hotbar is
         * centred and the chat box grows from the bottom-left.
         */
        public fun vanilla(screenSize: Size): List<SafeArea> {
            val width = screenSize.width
            val height = screenSize.height
            return listOf(
                // Hotbar plus the health and hunger rows above it.
                SafeArea(
                    "hotbar",
                    Rect(width / 2f - HOTBAR_HALF_WIDTH, height - HOTBAR_HEIGHT, HOTBAR_HALF_WIDTH * 2f, HOTBAR_HEIGHT),
                ),
                // Where chat opens.
                SafeArea("chat", Rect(0f, height - CHAT_HEIGHT, CHAT_WIDTH, CHAT_HEIGHT)),
                // The effect icons stack down from the top-right.
                SafeArea("effects", Rect(width - EFFECT_WIDTH, 0f, EFFECT_WIDTH, EFFECT_HEIGHT)),
            )
        }

        private const val HOTBAR_HALF_WIDTH = 91f
        private const val HOTBAR_HEIGHT = 40f
        private const val CHAT_WIDTH = 160f
        private const val CHAT_HEIGHT = 90f
        private const val EFFECT_WIDTH = 52f
        private const val EFFECT_HEIGHT = 52f
    }
}

/** A snapped position and the guides that explain it. */
public data class SnapResult(
    public val position: Vec2,
    public val guides: List<SnapGuide>,
) {
    public val didSnap: Boolean get() = guides.isNotEmpty()
}

/**
 * Turns a free-dragged position into a snapped one.
 *
 * Each axis is resolved independently and takes the single nearest candidate within
 * [threshold]. Independence matters: an element can be centred horizontally while still
 * hugging the bottom edge, which a combined "nearest point" search would not produce.
 *
 * Candidates are compared in screen space, so the result does not depend on how the
 * dragged element is scaled — which is what keeps snapping consistent with dragging.
 */
public class SnapEngine(
    public var threshold: Float = DEFAULT_THRESHOLD,
    public var snapToEdges: Boolean = true,
    public var snapToCenter: Boolean = true,
    public var snapToElements: Boolean = true,
    public var snapToSafeAreas: Boolean = true,
) {

    private class Candidate(val position: Float, val kind: GuideKind)

    /**
     * @param dragged where the element would land with no snapping.
     * @param others the on-screen rectangles of every element not being dragged.
     */
    public fun snap(
        dragged: Rect,
        screenSize: Size,
        others: List<Rect> = emptyList(),
        safeAreas: List<SafeArea> = emptyList(),
    ): SnapResult {
        if (threshold <= 0f) return SnapResult(dragged.position, emptyList())

        val vertical = ArrayList<Candidate>(CANDIDATE_CAPACITY)
        val horizontal = ArrayList<Candidate>(CANDIDATE_CAPACITY)

        if (snapToEdges) {
            vertical.add(Candidate(0f, GuideKind.EDGE))
            vertical.add(Candidate(screenSize.width, GuideKind.EDGE))
            horizontal.add(Candidate(0f, GuideKind.EDGE))
            horizontal.add(Candidate(screenSize.height, GuideKind.EDGE))
        }
        if (snapToCenter) {
            vertical.add(Candidate(screenSize.width / 2f, GuideKind.CENTER))
            horizontal.add(Candidate(screenSize.height / 2f, GuideKind.CENTER))
        }
        if (snapToElements) {
            for (other in others) {
                vertical.add(Candidate(other.x, GuideKind.ELEMENT))
                vertical.add(Candidate(other.center.x, GuideKind.ELEMENT))
                vertical.add(Candidate(other.right, GuideKind.ELEMENT))
                horizontal.add(Candidate(other.y, GuideKind.ELEMENT))
                horizontal.add(Candidate(other.center.y, GuideKind.ELEMENT))
                horizontal.add(Candidate(other.bottom, GuideKind.ELEMENT))
            }
        }
        if (snapToSafeAreas) {
            for (area in safeAreas) {
                vertical.add(Candidate(area.bounds.x, GuideKind.SAFE_AREA))
                vertical.add(Candidate(area.bounds.right, GuideKind.SAFE_AREA))
                horizontal.add(Candidate(area.bounds.y, GuideKind.SAFE_AREA))
                horizontal.add(Candidate(area.bounds.bottom, GuideKind.SAFE_AREA))
            }
        }

        val guides = ArrayList<SnapGuide>(2)

        // The dragged element offers three lines per axis; whichever gets closest to a
        // candidate wins, and the position shifts by that difference.
        val x = resolve(
            edges = floatArrayOf(dragged.x, dragged.center.x, dragged.right),
            offsets = floatArrayOf(0f, dragged.width / 2f, dragged.width),
            candidates = vertical,
        )
        x?.let { guides.add(SnapGuide(GuideAxis.VERTICAL, it.line, it.kind)) }

        val y = resolve(
            edges = floatArrayOf(dragged.y, dragged.center.y, dragged.bottom),
            offsets = floatArrayOf(0f, dragged.height / 2f, dragged.height),
            candidates = horizontal,
        )
        y?.let { guides.add(SnapGuide(GuideAxis.HORIZONTAL, it.line, it.kind)) }

        return SnapResult(
            Vec2(x?.origin ?: dragged.x, y?.origin ?: dragged.y),
            guides,
        )
    }

    private class Resolved(val origin: Float, val line: Float, val kind: GuideKind)

    private fun resolve(edges: FloatArray, offsets: FloatArray, candidates: List<Candidate>): Resolved? {
        var best: Resolved? = null
        var bestDistance = threshold

        for (index in edges.indices) {
            for (candidate in candidates) {
                val distance = abs(candidate.position - edges[index])
                if (distance > bestDistance) continue
                // Ties go to the earlier candidate, so an edge beats an element line
                // sitting on top of it and the guide names the more meaningful one.
                if (distance == bestDistance && best != null) continue
                bestDistance = distance
                best = Resolved(
                    origin = candidate.position - offsets[index],
                    line = candidate.position,
                    kind = candidate.kind,
                )
            }
        }
        return best
    }

    public companion object {
        public const val DEFAULT_THRESHOLD: Float = 4f
        private const val CANDIDATE_CAPACITY = 12
    }
}
