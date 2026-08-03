package dev.th7bo.sidequest.ui.components.background

import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * The colours a nebula is made of.
 *
 * Three, because that is what a sky needs: something for the empty parts, something for the clouds, and
 * something that catches the eye where they are thickest. More would be a palette editor rather than a
 * preference.
 */
public data class NebulaPalette(
    /** The empty sky. Nearly black, or the whole thing glows. */
    public val deep: Color,
    /** The body of the clouds. */
    public val cloud: Color,
    /** The brightest parts, where the clouds pile up. */
    public val highlight: Color,
) {
    public companion object {
        /** Violet, matching the mod's accent. */
        public val Default: NebulaPalette = NebulaPalette(
            deep = Color(0xFF0B0A14.toInt()),
            cloud = Color(0xFF4C3A8C.toInt()),
            highlight = Color(0xFFA78BFA.toInt()),
        )
    }
}

/**
 * Draws a drifting nebula.
 *
 * **A grid of flat cells, not a shader.** There is no shader in this framework's vocabulary and there should
 * not be — the whole point of the renderer interface is that a host implements a dozen primitives and gets
 * every screen. So the sky is value noise evaluated per cell and filled as a rectangle, which is one draw
 * call apiece and needs nothing but `fillRect`.
 *
 * The cell size is the one real trade. Smaller is smoother and costs linearly more rectangles; [CELL] is the
 * point where the bands stop being visible at ordinary GUI scales without the count running into five
 * figures. It is a title screen, so a couple of thousand quads a frame is affordable in a way it would not be
 * behind gameplay.
 *
 * Nothing here holds state. The caller passes the time, which is what makes reduced motion a matter of
 * passing the same number every frame rather than a second code path that can rot.
 */
public object NebulaPainter {

    /**
     * Paints the whole of [bounds].
     *
     * @param time seconds, for the drift. Pass a constant to freeze it.
     */
    public fun paint(
        renderer: UiRenderer,
        bounds: Rect,
        time: Float,
        palette: NebulaPalette = NebulaPalette.Default,
    ) {
        if (bounds.isEmpty) return

        // The empty sky, under everything. Also what fills any sliver the cell grid does not divide evenly.
        renderer.fillRect(bounds, palette.deep)

        val columns = (bounds.width / CELL).toInt().coerceAtLeast(1)
        val rows = (bounds.height / CELL).toInt().coerceAtLeast(1)
        val cellWidth = bounds.width / columns
        val cellHeight = bounds.height / rows

        for (row in 0 until rows) {
            for (column in 0 until columns) {
                // Sampled at the cell's centre rather than its corner, so the field is symmetric about the
                // grid instead of leaning up and to the left by half a cell.
                val u = (column + 0.5f) / columns
                val v = (row + 0.5f) / rows
                val density = density(u, v, time)
                if (density <= 0f) continue

                renderer.fillRect(
                    Rect(
                        bounds.x + column * cellWidth,
                        bounds.y + row * cellHeight,
                        // Rounded outward so neighbouring cells overlap by a fraction rather than leaving a
                        // hairline of the backdrop between them, which reads as a grid.
                        cellWidth + 1f,
                        cellHeight + 1f,
                    ),
                    colourAt(density, palette),
                )
            }
        }

        stars(renderer, bounds, time)
    }

    /**
     * How much cloud is at a point, from 0 to 1.
     *
     * Three octaves of value noise, each half the amplitude and twice the frequency of the last. One octave
     * is a lava lamp; three is enough structure to read as gas without looking like static.
     *
     * The octaves drift at different speeds and directions on purpose. Moving them together would slide the
     * whole picture across the screen like a texture; moving them apart makes the shape itself change, which
     * is what a cloud does.
     */
    public fun density(u: Float, v: Float, time: Float): Float {
        var total = 0f
        var amplitude = 1f
        var frequency = BASE_FREQUENCY
        var normaliser = 0f

        for (octave in 0 until OCTAVES) {
            val drift = time * DRIFT * (octave + 1)
            total += amplitude * noise(
                u * frequency + drift,
                v * frequency - drift * SHEAR,
                octave,
            )
            normaliser += amplitude
            amplitude *= 0.5f
            frequency *= 2f
        }

        val raw = total / normaliser
        // Everything below the floor is empty sky. Without it the clouds fill the frame evenly and there is
        // nothing for them to be clouds *against*.
        return ((raw - FLOOR) / (1f - FLOOR)).coerceIn(0f, 1f)
    }

    /** Cloud colour at a density: transparent, through the body, to the bright cores. */
    private fun colourAt(density: Float, palette: NebulaPalette): Color {
        val toward = ((density - CORE_AT) / (1f - CORE_AT)).coerceIn(0f, 1f)
        return palette.cloud.lerp(palette.highlight, toward).withAlpha(density * CLOUD_ALPHA)
    }

    /**
     * A scattering of stars, twinkling out of phase.
     *
     * Placed from their index rather than from a random source, so the sky is the same sky every time the
     * title screen opens. A field that reshuffled on every visit would be noticed immediately and read as a
     * fault rather than as stars.
     */
    private fun stars(renderer: UiRenderer, bounds: Rect, time: Float) {
        for (star in 0 until STARS) {
            val x = bounds.x + hash(star, 1) * bounds.width
            val y = bounds.y + hash(star, 2) * bounds.height
            val phase = hash(star, 3) * TAU
            val brightness = TWINKLE_BASE + TWINKLE_SWING * sin(time * TWINKLE_SPEED + phase)
            if (brightness <= 0f) continue

            val size = STAR_SIZE * (0.5f + hash(star, 4))
            renderer.fillRect(Rect(x, y, size, size), Color.White.withAlpha(brightness))
        }
    }

    // -- noise ---------------------------------------------------------------

    /**
     * Value noise: a hashed value at each lattice point, smoothly interpolated between.
     *
     * Written out rather than pulled in, because a dependency for twenty lines of arithmetic is a dependency
     * shipped into somebody's game — and this is the only place in the mod that needs it.
     */
    private fun noise(x: Float, y: Float, seed: Int): Float {
        val cellX = floor(x)
        val cellY = floor(y)
        val fractionX = smooth(x - cellX)
        val fractionY = smooth(y - cellY)

        val topLeft = lattice(cellX.toInt(), cellY.toInt(), seed)
        val topRight = lattice(cellX.toInt() + 1, cellY.toInt(), seed)
        val bottomLeft = lattice(cellX.toInt(), cellY.toInt() + 1, seed)
        val bottomRight = lattice(cellX.toInt() + 1, cellY.toInt() + 1, seed)

        val top = topLeft + (topRight - topLeft) * fractionX
        val bottom = bottomLeft + (bottomRight - bottomLeft) * fractionX
        return top + (bottom - top) * fractionY
    }

    /** Smoothstep. Linear interpolation alone leaves visible creases along the lattice lines. */
    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    private fun lattice(x: Int, y: Int, seed: Int): Float = hash(x * 374_761_393 + y * 668_265_263, seed)

    /**
     * A repeatable value in `0..1` for an index.
     *
     * The sine-and-take-the-fraction trick, which is not a good hash and does not need to be: it has to be
     * cheap, deterministic across runs, and free of visible structure at this scale. All three hold.
     */
    private fun hash(value: Int, seed: Int): Float {
        val mixed = sin(value * 12.9898f + seed * 78.233f) * 43_758.547f
        return abs(mixed - floor(mixed))
    }

    // -- the shape of it -----------------------------------------------------

    /**
     * How wide a cell is, in logical units.
     *
     * Eight. At four the bands are gone and the count doubles for a difference nobody sees; at sixteen the
     * grid starts to read as deliberate pixel art, which is a different look from the one intended.
     */
    private const val CELL = 8f

    private const val OCTAVES = 3

    /** How many lattice cells span the screen at the coarsest octave. */
    private const val BASE_FREQUENCY = 3f

    /** How fast the field drifts, in lattice cells a second. */
    private const val DRIFT = 0.012f

    /** How much the vertical drift differs from the horizontal, so the octaves shear past each other. */
    private const val SHEAR = 0.6f

    /** Below this, the sky is empty. The clouds need something to be clouds against. */
    private const val FLOOR = 0.42f

    /** Where the cloud colour starts giving way to the highlight. */
    private const val CORE_AT = 0.55f

    /** The densest cloud is still translucent, so the stars behind it are not simply gone. */
    private const val CLOUD_ALPHA = 0.85f

    private const val STARS = 140
    private const val STAR_SIZE = 1.4f

    private const val TWINKLE_BASE = 0.35f
    private const val TWINKLE_SWING = 0.3f
    private const val TWINKLE_SPEED = 0.8f

    private const val TAU = (2.0 * Math.PI).toFloat()
}
