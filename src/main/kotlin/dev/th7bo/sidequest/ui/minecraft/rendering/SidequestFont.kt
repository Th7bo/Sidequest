package dev.th7bo.sidequest.ui.minecraft.rendering

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier

/**
 * The mod's own typeface.
 *
 * **The last thing that made every screen look like a Minecraft mod.** Corners can be smoothed and icons
 * redrawn, but text is most of what an interface *is*, and Minecraft's is a bitmap font on a fixed pixel
 * grid — it reads as pixel art at any size because it is one. No arrangement of panels around it was going
 * to stop a screen feeling like part of the game.
 *
 * So the mod ships Inter and asks for it per string. A font is chosen by a [Style], not by picking a
 * different `Font` object: the game resolves the glyph source from the style at draw time, which means the
 * *only* way to use another face is to attach it to the text itself. That is why both the renderer and the
 * measurer go through here — and why they must, since a string measured in one face and drawn in another
 * overflows whatever laid it out.
 *
 * Two files rather than one plus a bold flag. Minecraft's "bold" is a second pass drawn one pixel across,
 * which is a bitmap trick that looks like a printing fault on a proportional face; Inter has a real
 * semi-bold and it costs four hundred kilobytes to use it.
 */
internal object SidequestFont {

    /**
     * The displayed face uses native ten-pixel metrics. Smoothness comes from the TTF
     * provider's 16× raster oversampling, not from a second pose-stack downscale.
     * Keeping this at one is important: Minecraft advances glyphs before an external
     * matrix transform, and compact counters can otherwise draw characters into one
     * another even when their measured box is wide enough.
     */
    const val RENDER_SCALE: Float = 1f

    // 26.x wraps a font id in a `FontDescription`, since a style can now also point at a sprite rather
    // than a resource. `Resource` is the plain "this file" case and exists on both supported versions, so
    // this needs no conditional.
    private val regularId = FontDescription.Resource(Identifier.fromNamespaceAndPath(NAMESPACE, "ui"))
    private val boldId = FontDescription.Resource(Identifier.fromNamespaceAndPath(NAMESPACE, "ui_bold"))

    private val regular: Style = Style.EMPTY.withFont(regularId)

    /**
     * The bold face, with the game's own bold flag deliberately *off*.
     *
     * Leaving it on would draw Inter Semi-Bold twice, a pixel apart, on top of a face that is already bold —
     * which is exactly the smeared look this exists to get away from.
     */
    private val bold: Style = Style.EMPTY.withFont(boldId).withBold(false)

    fun style(isBold: Boolean): Style = if (isBold) bold else regular

    /** A string as something the game will draw in this typeface. */
    fun text(content: String, isBold: Boolean): Component =
        Component.literal(content).setStyle(style(isBold))

    /**
     * How tall a line is.
     *
     * Stated here rather than read from `Font.lineHeight`, which is a final field holding the *default*
     * font's nine pixels and knows nothing about the face actually being drawn. Layout that trusted it would
     * be spacing Inter as though it were the bitmap font.
     */
    const val LINE_HEIGHT: Float = 10f

    /** Line height in the font provider's native coordinate space. */
    const val NATIVE_LINE_HEIGHT: Float = LINE_HEIGHT / RENDER_SCALE

    /**
     * Kept derived so the renderer remains correct if native superscaling is ever
     * reintroduced. At the current native scale Minecraft's seven-pixel baseline needs
     * no compensation.
     */
    const val NATIVE_BASELINE_OFFSET: Float = 7f * (1f / RENDER_SCALE - 1f)

    private const val NAMESPACE = "sidequest"
}
