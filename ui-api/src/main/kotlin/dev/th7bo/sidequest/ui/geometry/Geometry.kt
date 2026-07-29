package dev.th7bo.sidequest.ui.geometry

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A length in logical UI units.
 *
 * The framework lays out in a single logical unit space; the Minecraft adapter is the
 * only thing that multiplies by the GUI scale. `Dp` exists to make authored constants
 * type-safe (`8.dp`, not a bare `8f`), and shares its unit space with [Rect], [Size]
 * and [Vec2], so [value] converts freely.
 */
@Serializable
@JvmInline
public value class Dp(public val value: Float) : Comparable<Dp> {

    public operator fun plus(other: Dp): Dp = Dp(value + other.value)
    public operator fun minus(other: Dp): Dp = Dp(value - other.value)
    public operator fun times(factor: Float): Dp = Dp(value * factor)
    public operator fun div(divisor: Float): Dp = Dp(value / divisor)
    public operator fun unaryMinus(): Dp = Dp(-value)

    override fun compareTo(other: Dp): Int = value.compareTo(other.value)

    override fun toString(): String = "${value}dp"

    public companion object {
        public val Zero: Dp = Dp(0f)
    }
}

public val Int.dp: Dp get() = Dp(toFloat())
public val Float.dp: Dp get() = Dp(this)
public val Double.dp: Dp get() = Dp(toFloat())

/** A point or offset. */
@Serializable
public data class Vec2(val x: Float, val y: Float) {

    public operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)
    public operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)
    public operator fun times(factor: Float): Vec2 = Vec2(x * factor, y * factor)
    public operator fun div(divisor: Float): Vec2 = Vec2(x / divisor, y / divisor)
    public operator fun unaryMinus(): Vec2 = Vec2(-x, -y)

    override fun toString(): String = "($x, $y)"

    public companion object {
        public val Zero: Vec2 = Vec2(0f, 0f)
    }
}

/** A width and height. Never negative. */
@Serializable
public data class Size(val width: Float, val height: Float) {

    init {
        require(width >= 0f && height >= 0f) { "Size must not be negative, was ${width}x$height" }
    }

    public val isEmpty: Boolean get() = width == 0f || height == 0f

    public operator fun times(factor: Float): Size = Size(width * factor, height * factor)

    /** Grows by [insets] on all four sides. */
    public fun outset(insets: Insets): Size =
        Size(width + insets.horizontal, height + insets.vertical)

    /** Shrinks by [insets], clamped at zero. */
    public fun inset(insets: Insets): Size = Size(
        max(0f, width - insets.horizontal),
        max(0f, height - insets.vertical),
    )

    override fun toString(): String = "${width}x$height"

    public companion object {
        public val Zero: Size = Size(0f, 0f)
    }
}

/** An axis-aligned rectangle in logical units, positioned by its top-left corner. */
@Serializable
public data class Rect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {

    public val left: Float get() = x
    public val top: Float get() = y
    public val right: Float get() = x + width
    public val bottom: Float get() = y + height

    public val size: Size get() = Size(width, height)
    public val position: Vec2 get() = Vec2(x, y)
    public val center: Vec2 get() = Vec2(x + width / 2f, y + height / 2f)

    public val isEmpty: Boolean get() = width <= 0f || height <= 0f

    public operator fun contains(point: Vec2): Boolean =
        point.x >= x && point.x < right && point.y >= y && point.y < bottom

    public fun translate(offset: Vec2): Rect = Rect(x + offset.x, y + offset.y, width, height)

    public fun withSize(newSize: Size): Rect = Rect(x, y, newSize.width, newSize.height)

    public fun withPosition(newPosition: Vec2): Rect = Rect(newPosition.x, newPosition.y, width, height)

    /** Shrinks by [insets]; the result is clamped so it never inverts. */
    public fun inset(insets: Insets): Rect = Rect(
        x + insets.left,
        y + insets.top,
        max(0f, width - insets.horizontal),
        max(0f, height - insets.vertical),
    )

    /** Grows by [insets] on all four sides. */
    public fun outset(insets: Insets): Rect = Rect(
        x - insets.left,
        y - insets.top,
        width + insets.horizontal,
        height + insets.vertical,
    )

    public fun overlaps(other: Rect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    /** The overlapping region, or an empty rect at the origin of [this] if disjoint. */
    public fun intersect(other: Rect): Rect {
        val newLeft = max(left, other.left)
        val newTop = max(top, other.top)
        val newRight = min(right, other.right)
        val newBottom = min(bottom, other.bottom)
        if (newRight <= newLeft || newBottom <= newTop) return Rect(newLeft, newTop, 0f, 0f)
        return Rect(newLeft, newTop, newRight - newLeft, newBottom - newTop)
    }

    /** The smallest rect containing both. */
    public fun union(other: Rect): Rect {
        if (isEmpty) return other
        if (other.isEmpty) return this
        val newLeft = min(left, other.left)
        val newTop = min(top, other.top)
        return Rect(newLeft, newTop, max(right, other.right) - newLeft, max(bottom, other.bottom) - newTop)
    }

    override fun toString(): String = "Rect($x, $y, ${width}x$height)"

    public companion object {
        public val Zero: Rect = Rect(0f, 0f, 0f, 0f)

        public fun of(position: Vec2, size: Size): Rect =
            Rect(position.x, position.y, size.width, size.height)

        public fun fromEdges(left: Float, top: Float, right: Float, bottom: Float): Rect =
            Rect(left, top, max(0f, right - left), max(0f, bottom - top))
    }
}

/** Padding or margin on four sides. */
@Serializable
public data class Insets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {

    public val horizontal: Float get() = left + right
    public val vertical: Float get() = top + bottom

    public operator fun plus(other: Insets): Insets = Insets(
        left + other.left,
        top + other.top,
        right + other.right,
        bottom + other.bottom,
    )

    public companion object {
        public val Zero: Insets = Insets()

        public fun all(value: Dp): Insets =
            Insets(value.value, value.value, value.value, value.value)

        public fun symmetric(horizontal: Dp = Dp.Zero, vertical: Dp = Dp.Zero): Insets =
            Insets(horizontal.value, vertical.value, horizontal.value, vertical.value)

        public fun of(
            left: Dp = Dp.Zero,
            top: Dp = Dp.Zero,
            right: Dp = Dp.Zero,
            bottom: Dp = Dp.Zero,
        ): Insets = Insets(left.value, top.value, right.value, bottom.value)
    }
}

/**
 * The size range a parent offers a child during measurement.
 *
 * `Float.POSITIVE_INFINITY` for a maximum means "unbounded" — a child in an unbounded
 * axis must report its intrinsic size rather than expanding.
 */
public data class Constraints(
    val minWidth: Float = 0f,
    val maxWidth: Float = Float.POSITIVE_INFINITY,
    val minHeight: Float = 0f,
    val maxHeight: Float = Float.POSITIVE_INFINITY,
) {

    init {
        require(minWidth >= 0f && minHeight >= 0f) { "Constraint minimums must not be negative: $this" }
        require(maxWidth >= minWidth) { "maxWidth ($maxWidth) < minWidth ($minWidth)" }
        require(maxHeight >= minHeight) { "maxHeight ($maxHeight) < minHeight ($minHeight)" }
    }

    public val hasBoundedWidth: Boolean get() = maxWidth != Float.POSITIVE_INFINITY
    public val hasBoundedHeight: Boolean get() = maxHeight != Float.POSITIVE_INFINITY

    /** Clamps [size] into this range. */
    public fun constrain(size: Size): Size = Size(
        size.width.coerceIn(minWidth, maxWidth),
        size.height.coerceIn(minHeight, maxHeight),
    )

    /** Same maxima, no minima — what a parent offers a child that may be smaller. */
    public fun loosen(): Constraints = copy(minWidth = 0f, minHeight = 0f)

    /** Reduces the available space by [insets], for a parent that pads its child. */
    public fun deflate(insets: Insets): Constraints = Constraints(
        minWidth = max(0f, minWidth - insets.horizontal),
        maxWidth = if (hasBoundedWidth) max(0f, maxWidth - insets.horizontal) else maxWidth,
        minHeight = max(0f, minHeight - insets.vertical),
        maxHeight = if (hasBoundedHeight) max(0f, maxHeight - insets.vertical) else maxHeight,
    )

    public companion object {
        /** Anything goes. */
        public val Unbounded: Constraints = Constraints()

        /** Exactly this size, no choice. */
        public fun tight(size: Size): Constraints =
            Constraints(size.width, size.width, size.height, size.height)

        /** At most this size. */
        public fun atMost(size: Size): Constraints =
            Constraints(0f, size.width, 0f, size.height)
    }
}

/** Where a child sits inside spare space on one axis. */
public enum class HorizontalAlignment(public val factor: Float) {
    START(0f),
    CENTER(0.5f),
    END(1f),
}

/** Where a child sits inside spare space on one axis. */
public enum class VerticalAlignment(public val factor: Float) {
    TOP(0f),
    CENTER(0.5f),
    BOTTOM(1f),
}

/** Two-axis alignment for overlay-style containers. */
public data class Alignment(
    val horizontal: HorizontalAlignment,
    val vertical: VerticalAlignment,
) {
    /** Positions a [child] of the given size inside [container]. */
    public fun align(child: Size, container: Size): Vec2 = Vec2(
        (container.width - child.width) * horizontal.factor,
        (container.height - child.height) * vertical.factor,
    )

    public companion object {
        public val TopStart: Alignment = Alignment(HorizontalAlignment.START, VerticalAlignment.TOP)
        public val TopCenter: Alignment = Alignment(HorizontalAlignment.CENTER, VerticalAlignment.TOP)
        public val TopEnd: Alignment = Alignment(HorizontalAlignment.END, VerticalAlignment.TOP)
        public val CenterStart: Alignment = Alignment(HorizontalAlignment.START, VerticalAlignment.CENTER)
        public val Center: Alignment = Alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        public val CenterEnd: Alignment = Alignment(HorizontalAlignment.END, VerticalAlignment.CENTER)
        public val BottomStart: Alignment = Alignment(HorizontalAlignment.START, VerticalAlignment.BOTTOM)
        public val BottomCenter: Alignment = Alignment(HorizontalAlignment.CENTER, VerticalAlignment.BOTTOM)
        public val BottomEnd: Alignment = Alignment(HorizontalAlignment.END, VerticalAlignment.BOTTOM)
    }
}

/**
 * A screen-edge anchor for HUD placement.
 *
 * Placement persists an anchor plus an offset rather than absolute pixels, so an
 * element keeps its relationship to the edge it was placed against when the resolution
 * or GUI scale changes. [serializedId] is what goes on disk — never the ordinal.
 */
@Serializable(with = AnchorSerializer::class)
public enum class Anchor(
    public val serializedId: String,
    public val horizontalFactor: Float,
    public val verticalFactor: Float,
) {
    TOP_LEFT("top_left", 0f, 0f),
    TOP_CENTER("top_center", 0.5f, 0f),
    TOP_RIGHT("top_right", 1f, 0f),
    CENTER_LEFT("center_left", 0f, 0.5f),
    CENTER("center", 0.5f, 0.5f),
    CENTER_RIGHT("center_right", 1f, 0.5f),
    BOTTOM_LEFT("bottom_left", 0f, 1f),
    BOTTOM_CENTER("bottom_center", 0.5f, 1f),
    BOTTOM_RIGHT("bottom_right", 1f, 1f);

    /**
     * The absolute top-left position of an element of [elementSize] placed at [offset]
     * from this anchor on a screen of [screenSize].
     *
     * The element's own anchor-corresponding corner is what [offset] is measured from,
     * so a `BOTTOM_RIGHT` element at offset `(-10, -10)` sits 10 units in from the
     * bottom-right corner at every resolution.
     */
    public fun resolve(offset: Vec2, elementSize: Size, screenSize: Size): Vec2 = Vec2(
        screenSize.width * horizontalFactor - elementSize.width * horizontalFactor + offset.x,
        screenSize.height * verticalFactor - elementSize.height * verticalFactor + offset.y,
    )

    /**
     * The inverse of [resolve]: the offset that would place an element of
     * [elementSize] at absolute [position].
     *
     * Re-anchoring without moving an element is `newAnchor.offsetFor(currentPosition,
     * size, screen)` — which is why changing an anchor in the editor does not make the
     * HUD jump.
     */
    public fun offsetFor(position: Vec2, elementSize: Size, screenSize: Size): Vec2 = Vec2(
        position.x - screenSize.width * horizontalFactor + elementSize.width * horizontalFactor,
        position.y - screenSize.height * verticalFactor + elementSize.height * verticalFactor,
    )

    public companion object {
        private val BY_ID: Map<String, Anchor> = entries.associateBy { it.serializedId }

        /** Parses a persisted id. Null for unknown values rather than throwing. */
        public fun fromSerializedId(id: String): Anchor? = BY_ID[id]

        /** The anchor whose corner is nearest to [position] on [screenSize]. */
        public fun nearestTo(position: Vec2, elementSize: Size, screenSize: Size): Anchor {
            val center = Vec2(
                position.x + elementSize.width / 2f,
                position.y + elementSize.height / 2f,
            )
            return entries.minBy { anchor ->
                val anchorPoint = Vec2(
                    screenSize.width * anchor.horizontalFactor,
                    screenSize.height * anchor.verticalFactor,
                )
                abs(center.x - anchorPoint.x) + abs(center.y - anchorPoint.y)
            }
        }
    }
}

/**
 * Persists an [Anchor] by its [Anchor.serializedId] rather than by its constant name.
 *
 * The default enum serializer writes the constant name, which ties the on-disk format to
 * a Kotlin identifier: renaming `TOP_LEFT` would silently invalidate every saved layout.
 * `serializedId` exists precisely so the two can move independently, and this is what
 * makes the type actually honour it.
 */
public object AnchorSerializer : KSerializer<Anchor> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.th7bo.sidequest.ui.geometry.Anchor", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Anchor) {
        encoder.encodeString(value.serializedId)
    }

    override fun deserialize(decoder: Decoder): Anchor {
        val id = decoder.decodeString()
        return Anchor.fromSerializedId(id)
            ?: throw SerializationException("Unknown anchor '$id'")
    }
}
