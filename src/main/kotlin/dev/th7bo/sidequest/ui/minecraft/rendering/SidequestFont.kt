package dev.th7bo.sidequest.ui.minecraft.rendering

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style

/**
 * Centralizes use of Minecraft's native font so measuring and drawing always share the
 * same style and metrics. No custom font provider or filtered glyph atlas is involved.
 */
internal object SidequestFont {

    /**
     * Minecraft's bitmap face is rendered on its native pixel grid.
     */
    const val RENDER_SCALE: Float = 1f

    fun style(isBold: Boolean): Style = Style.EMPTY.withBold(isBold)

    /** A string as something the game will draw in this typeface. */
    fun text(content: String, isBold: Boolean): Component =
        Component.literal(content).setStyle(style(isBold))

    /**
     * Minecraft's default font occupies a native nine-pixel line.
     */
    const val LINE_HEIGHT: Float = 9f

    /** Line height in the font's native coordinate space. */
    const val NATIVE_LINE_HEIGHT: Float = LINE_HEIGHT / RENDER_SCALE

    /**
     * Native text already draws from the expected logical origin.
     */
    const val NATIVE_BASELINE_OFFSET: Float = 0f

}
