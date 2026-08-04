package dev.th7bo.sidequest.ui.core.rendering

import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.dp
import dev.th7bo.sidequest.ui.rendering.Corners
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Renders what a corner actually looks like on a display, so a change to it can be *seen*.
 *
 * **Skipped unless asked for.** Set `SIDEQUEST_RASTER_PREVIEW` to a path and this writes a PNG.
 *
 * The important thing it models is the **GUI scale**. Minecraft's `fill` takes GUI pixels, and at a scale of
 * 3 one of those covers a three-by-three block of real ones. A preview drawn in GUI pixels therefore flatters
 * the result enormously — it was what made the first attempt look solved when on a real display the steps
 * were still three pixels tall. Every panel here is rasterised the way it would be and then blown up by the
 * same amount, so they are honest against each other.
 */
class RasterPreview {

    @Test
    fun `render a before and after`() {
        val target = System.getenv("SIDEQUEST_RASTER_PREVIEW")
        assumeTrue(target != null, "set SIDEQUEST_RASTER_PREVIEW to write the preview")

        val guiScale = 3
        val zoom = 4
        val panel = Rect(4f, 4f, 34f, 34f)
        val radius = 12f

        val cell = 42 * guiScale
        val image = BufferedImage(cell * 3 * zoom, cell * zoom, BufferedImage.TYPE_INT_RGB)
        val canvas = Canvas(image, zoom)
        canvas.background(0xFF0A0912.toInt())

        // 1. Whole-pixel spans in GUI pixels. What it looked like before any of this.
        canvas.offset(0, 0)
        staircase(canvas, panel, radius, guiScale)

        // 2. Anti-aliased, but still addressing GUI pixels. The first attempt: graded, same step size.
        canvas.offset(cell, 0)
        guiPixelAntiAliased(canvas, panel, radius, guiScale)

        // 3. Anti-aliased in physical pixels, which is what ships now.
        canvas.offset(cell * 2, 0)
        physicalPixelAntiAliased(canvas, panel, radius, guiScale)

        val file = File(target!!)
        file.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
    }

    /** The original renderer: each row inset by a rounded number of GUI pixels. */
    private fun staircase(canvas: Canvas, bounds: Rect, radius: Float, guiScale: Int) {
        val band = radius.roundToInt()
        val left = bounds.left.roundToInt()
        val top = bounds.top.roundToInt()
        val right = bounds.right.roundToInt()
        val bottom = bounds.bottom.roundToInt()

        canvas.fillGui(left, top + band, right, bottom - band, ACCENT, 1f, guiScale)
        for (row in 0 until band) {
            val opposite = band - row - 1
            val inset = band - sqrt((band * band - opposite * opposite).toFloat()).roundToInt()
            canvas.fillGui(left + inset, top + row, right - inset, top + row + 1, ACCENT, 1f, guiScale)
            canvas.fillGui(left + inset, bottom - row - 1, right - inset, bottom - row, ACCENT, 1f, guiScale)
        }
    }

    /** The shipping rasteriser, but addressing GUI pixels — the version that still looked stepped. */
    private fun guiPixelAntiAliased(canvas: Canvas, bounds: Rect, radius: Float, guiScale: Int) {
        for (row in RoundedRectRaster.rows(bounds, Corners.all(radius.dp))) {
            if (row.hasSolid) canvas.fillGui(row.solidLeft, row.top, row.solidRight, row.bottom, ACCENT, 1f, guiScale)
            if (row.leftCoverage > MIN) {
                canvas.fillGui(row.solidLeft - 1, row.top, row.solidLeft, row.bottom, ACCENT, row.leftCoverage, guiScale)
            }
            if (row.rightCoverage > MIN) {
                canvas.fillGui(row.solidRight, row.top, row.solidRight + 1, row.bottom, ACCENT, row.rightCoverage, guiScale)
            }
        }
    }

    /** The same rasteriser against a shape scaled into physical pixels, which is what the renderer now does. */
    private fun physicalPixelAntiAliased(canvas: Canvas, bounds: Rect, radius: Float, guiScale: Int) {
        val scaled = Rect(
            bounds.left * guiScale,
            bounds.top * guiScale,
            bounds.width * guiScale,
            bounds.height * guiScale,
        )
        for (row in RoundedRectRaster.rows(scaled, Corners.all((radius * guiScale).dp))) {
            if (row.hasSolid) canvas.fill(row.solidLeft, row.top, row.solidRight, row.bottom, ACCENT, 1f)
            if (row.leftCoverage > MIN) {
                canvas.fill(row.solidLeft - 1, row.top, row.solidLeft, row.bottom, ACCENT, row.leftCoverage)
            }
            if (row.rightCoverage > MIN) {
                canvas.fill(row.solidRight, row.top, row.solidRight + 1, row.bottom, ACCENT, row.rightCoverage)
            }
        }
    }

    /** A physical-pixel canvas, magnified so individual pixels are visible. */
    private class Canvas(private val image: BufferedImage, private val zoom: Int) {
        private var dx = 0
        private var dy = 0

        fun offset(x: Int, y: Int) {
            dx = x
            dy = y
        }

        fun background(color: Int) {
            for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, color)
        }

        /** A fill given in GUI pixels, expanded to the block of physical pixels it really covers. */
        fun fillGui(x1: Int, y1: Int, x2: Int, y2: Int, color: Int, alpha: Float, guiScale: Int) {
            fill(x1 * guiScale, y1 * guiScale, x2 * guiScale, y2 * guiScale, color, alpha)
        }

        fun fill(x1: Int, y1: Int, x2: Int, y2: Int, color: Int, alpha: Float) {
            for (py in y1 until y2) for (px in x1 until x2) blend(px + dx, py + dy, color, alpha)
        }

        private fun blend(x: Int, y: Int, color: Int, alpha: Float) {
            for (sy in 0 until zoom) for (sx in 0 until zoom) {
                val ix = x * zoom + sx
                val iy = y * zoom + sy
                if (ix !in 0 until image.width || iy !in 0 until image.height) continue
                image.setRGB(ix, iy, mix(image.getRGB(ix, iy), color, alpha))
            }
        }

        private fun mix(under: Int, over: Int, alpha: Float): Int {
            fun channel(shift: Int): Int {
                val a = (under shr shift) and 0xFF
                val b = (over shr shift) and 0xFF
                return (a + (b - a) * alpha).roundToInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }

    private companion object {
        const val ACCENT = 0xFFA855F7.toInt()
        const val MIN = 0.025f
    }
}
