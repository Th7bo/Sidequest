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
 * Renders what the corners actually look like, so a change to them can be *seen*.
 *
 * **Skipped unless asked for.** Set `SIDEQUEST_RASTER_PREVIEW` to a path and this writes a PNG comparing the
 * old whole-pixel corners with the anti-aliased ones. It exists because everything else about this change is
 * numbers, and "the corners look smooth now" is not a claim numbers can settle.
 *
 * The new side goes through the real [RoundedRectRaster]; only the *old* behaviour is reimplemented here,
 * which is the right way round — the thing being demonstrated is the code that ships.
 */
class RasterPreview {

    @Test
    fun `render a before and after`() {
        val target = System.getenv("SIDEQUEST_RASTER_PREVIEW")
        assumeTrue(target != null, "set SIDEQUEST_RASTER_PREVIEW to write the preview")

        val scale = 14
        val image = BufferedImage(WIDTH * scale, HEIGHT * scale, BufferedImage.TYPE_INT_RGB)
        val canvas = Canvas(image, scale)
        canvas.background(0xFF11141F.toInt())

        val panel = Rect(3f, 3f, 40f, 40f)
        val radius = 12f

        // Old: whole-pixel spans, which is what made it look like pixel art.
        canvas.offset(0, 0)
        drawStaircase(canvas, panel, radius, 0xFF8B5CF6.toInt())

        // New: the same shape through the shipping rasteriser.
        canvas.offset(44, 0)
        drawSmooth(canvas, panel, radius, 0xFF8B5CF6.toInt())

        val file = File(target!!)
        file.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
    }

    /** The old renderer's corner: each row inset by a *rounded* number of pixels. */
    private fun drawStaircase(canvas: Canvas, bounds: Rect, radius: Float, color: Int) {
        val left = bounds.left.roundToInt()
        val top = bounds.top.roundToInt()
        val right = bounds.right.roundToInt()
        val bottom = bounds.bottom.roundToInt()
        val band = radius.roundToInt()

        canvas.fill(left, top + band, right, bottom - band, color, 1f)
        for (row in 0 until band) {
            val opposite = band - row - 1
            val inset = band - sqrt((band * band - opposite * opposite).toFloat()).roundToInt()
            canvas.fill(left + inset, top + row, right - inset, top + row + 1, color, 1f)
            canvas.fill(left + inset, bottom - row - 1, right - inset, bottom - row, color, 1f)
        }
    }

    /** The shipping path: solid span plus two partially covered edge pixels. */
    private fun drawSmooth(canvas: Canvas, bounds: Rect, radius: Float, color: Int) {
        for (row in RoundedRectRaster.rows(bounds, Corners.all(radius.dp))) {
            if (row.hasSolid) canvas.fill(row.solidLeft, row.top, row.solidRight, row.bottom, color, 1f)
            if (row.leftCoverage > 0.025f) {
                canvas.fill(row.solidLeft - 1, row.top, row.solidLeft, row.bottom, color, row.leftCoverage)
            }
            if (row.rightCoverage > 0.025f) {
                canvas.fill(row.solidRight, row.top, row.solidRight + 1, row.bottom, color, row.rightCoverage)
            }
        }
    }

    /** A blown-up pixel grid, so one GUI pixel is a visible square. */
    private class Canvas(private val image: BufferedImage, private val scale: Int) {
        private var dx = 0
        private var dy = 0

        fun offset(x: Int, y: Int) {
            dx = x
            dy = y
        }

        fun background(color: Int) {
            for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, color)
        }

        fun fill(x1: Int, y1: Int, x2: Int, y2: Int, color: Int, alpha: Float) {
            for (py in y1 until y2) for (px in x1 until x2) blend(px + dx, py + dy, color, alpha)
        }

        private fun blend(x: Int, y: Int, color: Int, alpha: Float) {
            for (sy in 0 until scale) for (sx in 0 until scale) {
                val ix = x * scale + sx
                val iy = y * scale + sy
                if (ix !in 0 until image.width || iy !in 0 until image.height) continue
                val existing = image.getRGB(ix, iy)
                image.setRGB(ix, iy, mix(existing, color, alpha))
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
        const val WIDTH = 88
        const val HEIGHT = 44
    }
}
