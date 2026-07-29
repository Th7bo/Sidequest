package dev.th7bo.sidequest.ui.rendering

import dev.th7bo.sidequest.ui.geometry.Dp
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId

/**
 * Everything the framework knows how to draw.
 *
 * Configuration models, HUD definitions and components depend on this interface and
 * never on a Minecraft drawing class. `ui-minecraft` supplies the adapter; the testkit
 * supplies a recording implementation used by every test.
 *
 * All coordinates are logical units in the current transform space. Implementations
 * are called on the UI thread only.
 */
public interface UiRenderer {

    /** Timing and viewport information for the frame being drawn. */
    public val frame: FrameInfo

    /** Measures and lays out text. Implementations are expected to cache aggressively. */
    public val textMeasurer: TextMeasurer

    // -- shapes -------------------------------------------------------------

    public fun fillRect(bounds: Rect, color: Color)

    public fun roundedRect(bounds: Rect, radius: Dp, color: Color)

    /**
     * A rectangle with independently rounded corners.
     *
     * Needed to draw one surface across several nodes: a card split into a header row
     * and a run of setting rows rounds only its outermost corners, and each row draws
     * its own slice. Without per-corner control that card could not survive
     * virtualization, because it would have to be a single node.
     *
     * The default implementation falls back to a uniform radius, so an adapter that
     * does not care need not implement it.
     */
    public fun roundedRect(bounds: Rect, corners: Corners, color: Color) {
        roundedRect(bounds, corners.largest, color)
    }

    /** Stroked outline, drawn inside [bounds]. */
    public fun border(bounds: Rect, radius: Dp, width: Dp, color: Color)

    /** Stroked outline with independently rounded corners. See [roundedRect]. */
    public fun border(bounds: Rect, corners: Corners, width: Dp, color: Color) {
        border(bounds, corners.largest, width, color)
    }

    /**
     * Draws only the requested [edges] of an outline.
     *
     * A card slice draws its left and right edges but not the horizontal ones it shares
     * with the slice above and below.
     */
    public fun edges(bounds: Rect, edges: Edges, width: Dp, color: Color) {
        val thickness = width.value
        if (edges.top) fillRect(Rect(bounds.x, bounds.y, bounds.width, thickness), color)
        if (edges.bottom) {
            fillRect(Rect(bounds.x, bounds.bottom - thickness, bounds.width, thickness), color)
        }
        if (edges.left) fillRect(Rect(bounds.x, bounds.y, thickness, bounds.height), color)
        if (edges.right) {
            fillRect(Rect(bounds.right - thickness, bounds.y, thickness, bounds.height), color)
        }
    }

    public fun gradient(bounds: Rect, gradient: Gradient, radius: Dp = Dp.Zero)

    /**
     * A drop shadow cast by [bounds]. Implementations may approximate; shadows are
     * decorative and must never be required for legibility.
     */
    public fun shadow(bounds: Rect, radius: Dp, shadow: Shadow)

    /**
     * Blurs what is already drawn behind [bounds].
     *
     * Expensive. The theme's blur token exists so that this can be turned off wholesale
     * on low-end hardware, and animations must not resize a blurred region every frame.
     */
    public fun blur(bounds: Rect, radius: Dp, strength: Float)

    // -- content ------------------------------------------------------------

    /** Draws a previously measured layout with its top-left corner at [position]. */
    public fun text(layout: TextLayout, position: Vec2, color: Color)

    public fun icon(icon: Icon, bounds: Rect, tint: Color = Color.White)

    public fun image(texture: TextureRef, bounds: Rect, tint: Color = Color.White)

    // -- state stacks -------------------------------------------------------

    /**
     * Restricts drawing to the intersection of [bounds] and the current clip.
     * Every push must be matched by a [popClip].
     */
    public fun pushClip(bounds: Rect)

    public fun popClip()

    public fun pushTransform(transform: Transform)

    public fun popTransform()

    /** Multiplies subsequent alpha by [opacity], clamped to `0..1`. */
    public fun pushOpacity(opacity: Float)

    public fun popOpacity()
}

/** Per-corner radii. */
public data class Corners(
    val topLeft: Dp = Dp.Zero,
    val topRight: Dp = Dp.Zero,
    val bottomRight: Dp = Dp.Zero,
    val bottomLeft: Dp = Dp.Zero,
) {

    /** Used by adapters that only support a uniform radius. */
    public val largest: Dp
        get() = maxOf(topLeft.value, topRight.value, bottomRight.value, bottomLeft.value).let(::Dp)

    public val isSquare: Boolean get() = largest.value <= 0f

    public companion object {
        public val None: Corners = Corners()

        public fun all(radius: Dp): Corners = Corners(radius, radius, radius, radius)

        /** Rounded along the top only — the first slice of a card. */
        public fun top(radius: Dp): Corners = Corners(topLeft = radius, topRight = radius)

        /** Rounded along the bottom only — the last slice of a card. */
        public fun bottom(radius: Dp): Corners = Corners(bottomRight = radius, bottomLeft = radius)
    }
}

/** Which sides of an outline to draw. */
public data class Edges(
    val top: Boolean = false,
    val right: Boolean = false,
    val bottom: Boolean = false,
    val left: Boolean = false,
) {
    public companion object {
        public val All: Edges = Edges(top = true, right = true, bottom = true, left = true)

        /** The two vertical sides — what a middle card slice draws. */
        public val Sides: Edges = Edges(right = true, left = true)
    }
}

/** Frame timing and viewport, supplied by the host each frame. */
public data class FrameInfo(
    /** Logical size of the drawable area. */
    val viewport: Rect,
    /** Seconds since the previous frame, already clamped to something sane. */
    val deltaSeconds: Float,
    /** Monotonic frame counter since the UI started. */
    val frameIndex: Long,
    /** Host GUI scale. The framework lays out in logical units and does not apply this. */
    val guiScale: Float = 1f,
)

/** An affine transform limited to translation and scale — enough for HUD placement. */
public data class Transform(
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
) {

    /** Maps a point from local space into parent space. */
    public fun apply(point: Vec2): Vec2 =
        Vec2(point.x * scaleX + translateX, point.y * scaleY + translateY)

    /**
     * Maps a point from parent space back into local space.
     *
     * This is what makes hit testing correct for a scaled HUD: the pointer arrives in
     * screen space and must be un-transformed before it is compared against local
     * bounds.
     */
    public fun invert(point: Vec2): Vec2 =
        Vec2((point.x - translateX) / scaleX, (point.y - translateY) / scaleY)

    public fun then(other: Transform): Transform = Transform(
        translateX = other.translateX + translateX * other.scaleX,
        translateY = other.translateY + translateY * other.scaleY,
        scaleX = scaleX * other.scaleX,
        scaleY = scaleY * other.scaleY,
    )

    public companion object {
        public val Identity: Transform = Transform()

        public fun translation(offset: Vec2): Transform = Transform(offset.x, offset.y)

        public fun scale(factor: Float, origin: Vec2 = Vec2.Zero): Transform = Transform(
            translateX = origin.x - origin.x * factor,
            translateY = origin.y - origin.y * factor,
            scaleX = factor,
            scaleY = factor,
        )
    }
}

/** A drop shadow. */
public data class Shadow(
    val color: Color,
    val offset: Vec2 = Vec2.Zero,
    val blurRadius: Float = 8f,
    val spread: Float = 0f,
) {
    public companion object {
        public val None: Shadow = Shadow(Color.Transparent, blurRadius = 0f)
    }
}

/** A colour ramp. Stops must be sorted by position and cover `0..1`. */
public data class Gradient(
    val stops: List<Stop>,
    val direction: Direction = Direction.VERTICAL,
) {

    init {
        require(stops.size >= 2) { "A gradient needs at least two stops" }
        require(stops.zipWithNext().all { (a, b) -> a.position <= b.position }) {
            "Gradient stops must be sorted by position"
        }
    }

    public data class Stop(val position: Float, val color: Color) {
        init {
            require(position in 0f..1f) { "Gradient stop position must be in 0..1, was $position" }
        }
    }

    public enum class Direction { HORIZONTAL, VERTICAL }

    public companion object {
        public fun linear(
            from: Color,
            to: Color,
            direction: Direction = Direction.VERTICAL,
        ): Gradient = Gradient(listOf(Stop(0f, from), Stop(1f, to)), direction)
    }
}

/**
 * A drawable symbol, resolved by the host.
 *
 * Icons are referenced by id rather than by texture so that a theme or resource pack
 * can swap them, and so a missing icon can render a placeholder instead of crashing.
 */
public data class Icon(
    val id: UiId,
    /** Intrinsic size in logical units, used when a caller does not specify bounds. */
    val intrinsicSize: Float = 16f,
)

/** A host texture reference. Opaque to the framework. */
public data class TextureRef(
    val id: UiId,
    /** Sub-region in normalised `0..1` texture coordinates. */
    val region: Rect = Rect(0f, 0f, 1f, 1f),
)
