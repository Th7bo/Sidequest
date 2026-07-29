package dev.th7bo.sidequest.ui.rendering

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A packed straight-alpha ARGB colour.
 *
 * Stored as an `Int` in a value class, so passing colours around allocates nothing —
 * which matters because a frame resolves thousands of them.
 */
@Serializable(with = ColorSerializer::class)
@JvmInline
public value class Color(public val argb: Int) {

    public val alpha: Int get() = (argb ushr 24) and 0xFF
    public val red: Int get() = (argb ushr 16) and 0xFF
    public val green: Int get() = (argb ushr 8) and 0xFF
    public val blue: Int get() = argb and 0xFF

    public val alphaFraction: Float get() = alpha / 255f

    /** True if the colour cannot contribute anything to the frame. */
    public val isTransparent: Boolean get() = alpha == 0

    /** Replaces the alpha channel. [fraction] is clamped to `0..1`. */
    public fun withAlpha(fraction: Float): Color {
        val clamped = (fraction.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return Color((argb and 0x00FFFFFF) or (clamped shl 24))
    }

    /** Multiplies the existing alpha, for compositing under an opacity stack. */
    public fun scaleAlpha(factor: Float): Color = withAlpha(alphaFraction * factor)

    /** Linear interpolation in straight ARGB space. [t] is clamped to `0..1`. */
    public fun lerp(other: Color, t: Float): Color {
        val amount = t.coerceIn(0f, 1f)
        return argb(
            lerpChannel(alpha, other.alpha, amount),
            lerpChannel(red, other.red, amount),
            lerpChannel(green, other.green, amount),
            lerpChannel(blue, other.blue, amount),
        )
    }

    /** `#AARRGGBB`. */
    public fun toHexString(): String = "#%08X".format(argb)

    override fun toString(): String = toHexString()

    public companion object {
        public val Transparent: Color = Color(0)
        public val Black: Color = rgb(0, 0, 0)
        public val White: Color = rgb(255, 255, 255)

        /** Opaque colour from 0..255 channels. */
        public fun rgb(red: Int, green: Int, blue: Int): Color = argb(255, red, green, blue)

        /** Colour from 0..255 channels, values outside the range are clamped. */
        public fun argb(alpha: Int, red: Int, green: Int, blue: Int): Color = Color(
            (alpha.coerceIn(0, 255) shl 24) or
                (red.coerceIn(0, 255) shl 16) or
                (green.coerceIn(0, 255) shl 8) or
                blue.coerceIn(0, 255),
        )

        /**
         * Parses `#RGB`, `#RRGGBB` or `#AARRGGBB`, with or without the leading `#`.
         *
         * @throws IllegalArgumentException on anything else — a malformed colour in a
         * theme file is a bug worth surfacing, not something to silently render black.
         */
        public fun parse(value: String): Color {
            val hex = value.removePrefix("#")
            return when (hex.length) {
                3 -> rgb(
                    hex[0].digitToInt(16) * 17,
                    hex[1].digitToInt(16) * 17,
                    hex[2].digitToInt(16) * 17,
                )
                6 -> Color(0xFF000000.toInt() or hex.toInt(16))
                8 -> Color(hex.toLong(16).toInt())
                else -> throw IllegalArgumentException(
                    "Invalid colour '$value': expected #RGB, #RRGGBB or #AARRGGBB",
                )
            }
        }

        private fun lerpChannel(from: Int, to: Int, t: Float): Int =
            (from + (to - from) * t + 0.5f).toInt()
    }
}

internal object ColorSerializer : KSerializer<Color> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.th7bo.sidequest.ui.Color", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Color) {
        encoder.encodeString(value.toHexString())
    }

    override fun deserialize(decoder: Decoder): Color = Color.parse(decoder.decodeString())
}
