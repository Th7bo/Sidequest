package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.core.rendering.RoundedRectRaster
import dev.th7bo.sidequest.ui.geometry.Dp
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.Corners
import dev.th7bo.sidequest.ui.rendering.FrameInfo
import dev.th7bo.sidequest.ui.rendering.Gradient
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.rendering.ItemRef
import dev.th7bo.sidequest.ui.rendering.Shadow
import dev.th7bo.sidequest.ui.rendering.TextLayout
import dev.th7bo.sidequest.ui.rendering.TextMeasurer
import dev.th7bo.sidequest.ui.rendering.TextStyle
import dev.th7bo.sidequest.ui.rendering.TextureRef
import dev.th7bo.sidequest.ui.rendering.Transform
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import java.awt.Color as AwtColor
import java.awt.Font as AwtFont
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * A [UiRenderer] that draws into an image, so a component can be looked at without a game.
 *
 * Built after shipping two visual changes that were reasoned about rather than seen — one of which was
 * wrong in a way a single glance would have caught. Anything drawn from primitives can now be rendered to a
 * PNG and inspected.
 *
 * It rasterises through the same [RoundedRectRaster] the real renderer uses, at a supersampled resolution,
 * Java2D cannot load Minecraft's generated bitmap atlas, so text uses a compact monospaced approximation;
 * screenshots from the client game test remain the authority for final typography.
 */
internal class PreviewRenderer(
    private val image: BufferedImage,
    /** How many image pixels one logical unit covers. The renderer's own physical-pixel trick, made explicit. */
    private val scale: Int,
) : UiRenderer {

    override val frame: FrameInfo = FrameInfo(
        viewport = Rect(0f, 0f, image.width / scale.toFloat(), image.height / scale.toFloat()),
        deltaSeconds = 0f,
        frameIndex = 0,
        guiScale = scale.toFloat(),
    )

    private val regularFont: AwtFont = AwtFont(AwtFont.MONOSPACED, AwtFont.PLAIN, 1)
    private val boldFont: AwtFont = AwtFont(AwtFont.MONOSPACED, AwtFont.BOLD, 1)

    private val graphics = image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    }

    private fun fontFor(style: TextStyle): AwtFont =
        (if (style.bold) boldFont else regularFont).deriveFont(FONT_SIZE * style.scale * scale)

    override val textMeasurer: TextMeasurer = object : TextMeasurer {
        override fun measure(
            text: String,
            style: TextStyle,
            maxWidth: Float?,
            maxLines: Int,
            overflow: dev.th7bo.sidequest.ui.rendering.TextOverflow,
        ): TextLayout {
            val height = lineHeight(style)
            val lines = text.split('\n').take(maxLines).map { content ->
                TextLayout.Line(content, widthOf(content, style), height)
            }
            return TextLayout(
                text = text,
                style = style,
                lines = lines,
                size = dev.th7bo.sidequest.ui.geometry.Size(
                    lines.maxOfOrNull { it.width } ?: 0f,
                    height * maxOf(1, lines.size),
                ),
            )
        }

        override fun lineHeight(style: TextStyle): Float = LINE_HEIGHT * style.scale
    }

    /** Measured with the same face and size the drawing uses, or the two disagree and text overruns. */
    private fun widthOf(text: String, style: TextStyle): Float =
        graphics.getFontMetrics(fontFor(style)).stringWidth(text).toFloat() / scale

    fun clear(color: Color) {
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, color.argb)
    }

    override fun fillRect(bounds: Rect, color: Color) {
        roundedRect(bounds, Corners.None, color)
    }

    override fun roundedRect(bounds: Rect, radius: Dp, color: Color) {
        roundedRect(bounds, Corners.all(radius), color)
    }

    override fun roundedRect(bounds: Rect, corners: Corners, color: Color) {
        if (color.isTransparent || bounds.isEmpty) return
        for (row in RoundedRectRaster.rows(bounds.scaled(), corners.scaled())) {
            if (row.hasSolid) span(row.solidLeft, row.top, row.solidRight, row.bottom, color, 1f)
            if (row.leftCoverage > MIN_COVERAGE) {
                span(row.solidLeft - 1, row.top, row.solidLeft, row.bottom, color, row.leftCoverage)
            }
            if (row.rightCoverage > MIN_COVERAGE) {
                span(row.solidRight, row.top, row.solidRight + 1, row.bottom, color, row.rightCoverage)
            }
        }
    }

    override fun border(bounds: Rect, radius: Dp, width: Dp, color: Color) {
        border(bounds, Corners.all(radius), width, color)
    }

    override fun border(bounds: Rect, corners: Corners, width: Dp, color: Color) {
        if (color.isTransparent || bounds.isEmpty) return
        val thickness = (width.value * scale).coerceAtLeast(1f)
        val outer = RoundedRectRaster.rows(bounds.scaled(), corners.scaled())
        val innerBounds = bounds.scaled().inset(
            dev.th7bo.sidequest.ui.geometry.Insets(thickness, thickness, thickness, thickness),
        )
        val inner = if (innerBounds.isEmpty) {
            emptyMap()
        } else {
            RoundedRectRaster.rows(innerBounds, corners.scaled().inset(thickness)).let(RoundedRectRaster::byScanline)
        }

        for (row in outer) {
            for (y in row.top until row.bottom) {
                val hole = inner[y]
                if (hole == null || !hole.hasSolid) {
                    if (row.hasSolid) span(row.solidLeft, y, row.solidRight, y + 1, color, 1f)
                } else {
                    span(row.solidLeft, y, hole.solidLeft, y + 1, color, 1f)
                    span(hole.solidRight, y, row.solidRight, y + 1, color, 1f)
                    if (hole.leftCoverage > MIN_COVERAGE) {
                        span(hole.solidLeft - 1, y, hole.solidLeft, y + 1, color, 1f - hole.leftCoverage)
                    }
                    if (hole.rightCoverage > MIN_COVERAGE) {
                        span(hole.solidRight, y, hole.solidRight + 1, y + 1, color, 1f - hole.rightCoverage)
                    }
                }
                if (row.leftCoverage > MIN_COVERAGE) {
                    span(row.solidLeft - 1, y, row.solidLeft, y + 1, color, row.leftCoverage)
                }
                if (row.rightCoverage > MIN_COVERAGE) {
                    span(row.solidRight, y, row.solidRight + 1, y + 1, color, row.rightCoverage)
                }
            }
        }
    }

    override fun gradient(bounds: Rect, gradient: Gradient, radius: Dp) {
        gradient.stops.firstOrNull()?.let { roundedRect(bounds, radius, it.color) }
    }

    override fun shadow(bounds: Rect, radius: Dp, shadow: Shadow) = Unit

    override fun blur(bounds: Rect, radius: Dp, strength: Float) = Unit

    override fun text(layout: TextLayout, position: Vec2, color: Color) {
        val font = fontFor(layout.style)
        graphics.font = font
        graphics.color = AwtColor(color.argb, true)
        val metrics = graphics.getFontMetrics(font)
        for ((index, line) in layout.lines.withIndex()) {
            if (line.content.isEmpty()) continue
            val y = (position.y + LINE_HEIGHT * layout.style.scale * index) * scale + metrics.ascent
            graphics.drawString(line.content, position.x * scale, y)
        }
    }

    override fun icon(icon: Icon, bounds: Rect, tint: Color) = Unit

    override fun image(texture: TextureRef, bounds: Rect, tint: Color) = Unit

    override fun item(item: ItemRef, bounds: Rect) = Unit

    override fun pushClip(bounds: Rect) = Unit

    override fun popClip() = Unit

    override fun pushTransform(transform: Transform) = Unit

    override fun popTransform() = Unit

    override fun pushOpacity(opacity: Float) = Unit

    override fun popOpacity() = Unit

    // -- putting pixels down --------------------------------------------------

    private fun Rect.scaled(): Rect = Rect(left * scale, top * scale, width * scale, height * scale)

    private fun Corners.scaled(): Corners = Corners(
        topLeft = Dp(topLeft.value * scale),
        topRight = Dp(topRight.value * scale),
        bottomRight = Dp(bottomRight.value * scale),
        bottomLeft = Dp(bottomLeft.value * scale),
    )

    private fun span(x1: Int, y1: Int, x2: Int, y2: Int, color: Color, coverage: Float) {
        val alpha = color.alphaFraction * coverage
        if (alpha <= 0f) return
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                if (x !in 0 until image.width || y !in 0 until image.height) continue
                image.setRGB(x, y, blend(image.getRGB(x, y), color, alpha))
            }
        }
    }

    private fun blend(under: Int, over: Color, alpha: Float): Int {
        fun channel(shift: Int, source: Int): Int {
            val a = (under shr shift) and 0xFF
            val b = (source shr shift) and 0xFF
            return (a + (b - a) * alpha).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or
            (channel(16, over.argb) shl 16) or
            (channel(8, over.argb) shl 8) or
            channel(0, over.argb)
    }

    private companion object {
        const val MIN_COVERAGE = 0.02f

        /** Matches `SidequestFont.LINE_HEIGHT`, which is what the shipped renderer lays out with. */
        const val LINE_HEIGHT = 9f

        const val FONT_SIZE = 9f
    }
}
