package dev.th7bo.sidequest.ui.testkit

import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.Transform
import kotlin.math.roundToInt

/**
 * Rasterises recorded draw commands into a character grid.
 *
 * Not a pixel-accurate renderer and not trying to be. Its job is to make a headless
 * frame *visible* — in a failing test's output, or in the fake-renderer demo — so that
 * "the layout is wrong" can be seen rather than inferred from a list of rectangles.
 */
public class AsciiCanvas(
    private val size: Size,
    /** Logical units per character cell. Larger values give a coarser picture. */
    private val unitsPerCell: Float = 8f,
) {

    private val columns: Int = (size.width / unitsPerCell).roundToInt().coerceAtLeast(1)
    private val rows: Int = (size.height / unitsPerCell).roundToInt().coerceAtLeast(1)
    private val grid: Array<CharArray> = Array(rows) { CharArray(columns) { EMPTY } }

    /** Draws [commands] onto the grid, in order, honouring transforms and clips. */
    public fun render(commands: List<DrawCommand>): AsciiCanvas = apply {
        val transforms = ArrayList<Transform>()
        val clips = ArrayList<Rect>()

        for (command in commands) {
            val transform = transforms.fold(Transform.Identity) { acc, next -> acc.then(next) }
            val clip = clips.lastOrNull()

            when (command) {
                is DrawCommand.FillRect -> fill(command.bounds, transform, clip, shadeOf(command.color))
                is DrawCommand.RoundedRect -> fill(command.bounds, transform, clip, shadeOf(command.color))
                is DrawCommand.GradientFill -> fill(command.bounds, transform, clip, GRADIENT)
                is DrawCommand.Border -> outline(command.bounds, transform, clip)
                is DrawCommand.DrawIcon -> fill(command.bounds, transform, clip, ICON)
                is DrawCommand.Image -> fill(command.bounds, transform, clip, ICON)
                is DrawCommand.Text -> write(command, transform, clip)

                is DrawCommand.PushTransform -> transforms.add(command.transform)
                DrawCommand.PopTransform -> transforms.removeLastOrNull()
                is DrawCommand.PushClip ->
                    clips.add(clip?.intersect(map(command.bounds, transform)) ?: map(command.bounds, transform))
                DrawCommand.PopClip -> clips.removeLastOrNull()

                is DrawCommand.ShadowCast, is DrawCommand.Blur,
                is DrawCommand.PushOpacity, DrawCommand.PopOpacity,
                -> Unit
            }
        }
    }

    private fun map(bounds: Rect, transform: Transform): Rect {
        if (transform == Transform.Identity) return bounds
        val topLeft = transform.apply(bounds.position)
        return Rect(topLeft.x, topLeft.y, bounds.width * transform.scaleX, bounds.height * transform.scaleY)
    }

    private fun fill(bounds: Rect, transform: Transform, clip: Rect?, glyph: Char) {
        forEachCell(map(bounds, transform), clip) { row, column ->
            grid[row][column] = glyph
        }
    }

    private fun outline(bounds: Rect, transform: Transform, clip: Rect?) {
        val mapped = map(bounds, transform)
        val cells = cellsOf(mapped, clip) ?: return
        for (row in cells.topRow..cells.bottomRow) {
            for (column in cells.leftColumn..cells.rightColumn) {
                val onEdge = row == cells.topRow || row == cells.bottomRow ||
                    column == cells.leftColumn || column == cells.rightColumn
                if (onEdge) grid[row][column] = BORDER
            }
        }
    }

    private fun write(command: DrawCommand.Text, transform: Transform, clip: Rect?) {
        val origin = transform.apply(command.position)
        var row = (origin.y / unitsPerCell).roundToInt()
        for (line in command.content.split('\n')) {
            if (row !in 0 until rows) {
                row++
                continue
            }
            var column = (origin.x / unitsPerCell).roundToInt()
            for (character in line) {
                if (column in 0 until columns && insideClip(column, row, clip)) {
                    grid[row][column] = character
                }
                column++
            }
            row++
        }
    }

    private inline fun forEachCell(bounds: Rect, clip: Rect?, action: (Int, Int) -> Unit) {
        val cells = cellsOf(bounds, clip) ?: return
        for (row in cells.topRow..cells.bottomRow) {
            for (column in cells.leftColumn..cells.rightColumn) action(row, column)
        }
    }

    private fun cellsOf(bounds: Rect, clip: Rect?): CellRange? {
        val effective = clip?.intersect(bounds) ?: bounds
        if (effective.isEmpty) return null

        val left = (effective.left / unitsPerCell).roundToInt().coerceIn(0, columns - 1)
        val right = ((effective.right / unitsPerCell).roundToInt() - 1).coerceIn(0, columns - 1)
        val top = (effective.top / unitsPerCell).roundToInt().coerceIn(0, rows - 1)
        val bottom = ((effective.bottom / unitsPerCell).roundToInt() - 1).coerceIn(0, rows - 1)
        if (right < left || bottom < top) return null
        return CellRange(left, top, right, bottom)
    }

    private fun insideClip(column: Int, row: Int, clip: Rect?): Boolean {
        if (clip == null) return true
        val x = column * unitsPerCell
        val y = row * unitsPerCell
        return x >= clip.left && x < clip.right && y >= clip.top && y < clip.bottom
    }

    /** The grid as text, framed so trailing spaces stay visible. */
    override fun toString(): String = buildString {
        append('+').append("-".repeat(columns)).append("+\n")
        for (row in grid) {
            append('|').append(String(row)).append("|\n")
        }
        append('+').append("-".repeat(columns)).append('+')
    }

    private class CellRange(
        val leftColumn: Int,
        val topRow: Int,
        val rightColumn: Int,
        val bottomRow: Int,
    )

    /**
     * Maps a colour onto a shade so that surfaces with different tokens are
     * distinguishable — otherwise every panel, track and accent fill looks identical
     * and the picture stops being useful.
     */
    private fun shadeOf(color: Color): Char {
        if (color.isTransparent) return EMPTY
        val luminance = (RED_WEIGHT * color.red + GREEN_WEIGHT * color.green + BLUE_WEIGHT * color.blue) / 255f
        val perceived = luminance * color.alphaFraction
        val index = (perceived * (SHADES.length - 1)).roundToInt().coerceIn(0, SHADES.length - 1)
        return SHADES[index]
    }

    private companion object {
        const val EMPTY = ' '
        const val GRADIENT = '='
        const val BORDER = '.'
        const val ICON = '@'

        /** Dark to light. */
        const val SHADES = "-:=+*#%@"

        const val RED_WEIGHT = 0.2126f
        const val GREEN_WEIGHT = 0.7152f
        const val BLUE_WEIGHT = 0.0722f
    }
}
