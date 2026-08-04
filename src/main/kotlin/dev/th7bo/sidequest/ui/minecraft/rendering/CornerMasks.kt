package dev.th7bo.sidequest.ui.minecraft.rendering

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import kotlin.math.sqrt

/**
 * Anti-aliased corner arcs, baked once into a texture instead of rasterised every frame.
 *
 * **Why this exists: the honest version cost the frame rate.** Slicing a corner into per-display-pixel rows
 * gives a genuinely smooth curve, and at a GUI scale of 3 it also gives about two hundred and fifty quads for
 * a single rounded rectangle — against fifteen for the staircase it replaced. A screen of panels is tens of
 * thousands of draws, and it showed: eighteen frames a second.
 *
 * The curve does not change between frames, so computing it every frame was always the wrong shape of
 * solution. Here it is computed once per radius into a small alpha mask, and a rounded rectangle becomes four
 * blits and a handful of rectangles — cheaper than the staircase ever was, and smooth.
 *
 * The mask is **white with a varying alpha**, which is what lets one texture serve every colour: the blit
 * tint multiplies through, so the alpha carries the coverage and the tint carries the paint. It also
 * sidesteps a question I could not answer from the bytecode — whether `setPixel` wants ARGB or ABGR — because
 * white is the same either way.
 *
 * A full disc rather than a quadrant, so the four corners are four offsets into one texture. The blit
 * overload that takes a tint has no way to flip its source, and four separate textures per radius would be
 * four times the objects for no gain.
 */
internal object CornerMasks {

    private val cache = HashMap<Int, Identifier?>()

    /**
     * The mask for a corner of [radius] physical pixels, or null when there should not be one.
     *
     * Null is a real answer and callers must handle it: a radius of zero has no arc, one beyond
     * [MAX_RADIUS] is past anything an interface asks for, and a failure to build the texture at all should
     * degrade to the slow-but-correct path rather than take the screen down.
     */
    fun forRadius(radius: Int): Identifier? {
        if (radius <= 0 || radius > MAX_RADIUS) return null
        return cache.getOrPut(radius) { build(radius) }
    }

    private val rings = HashMap<Long, Identifier?>()

    /**
     * The mask for an *outlined* corner — an arc of a ring rather than a filled quadrant.
     *
     * Borders were the last thing still rasterising a curve every frame, and they were the most expensive
     * one left: a stroke's corner bands are several quads per display-pixel row, so an outline cost more
     * than the panel it went around. Every glyph drawn as a ring or a frame paid it too.
     */
    fun forRing(radius: Int, thickness: Int): Identifier? {
        if (radius <= 0 || radius > MAX_RADIUS || thickness <= 0) return null
        val key = radius.toLong() shl KEY_SHIFT or thickness.toLong()
        return rings.getOrPut(key) { buildRing(radius, thickness) }
    }

    private fun buildRing(radius: Int, thickness: Int): Identifier? = runCatching {
        val size = radius * 2
        val image = NativeImage(size, size, false)
        val inner = (radius - thickness).coerceAtLeast(0).toFloat()
        val centre = radius.toFloat()

        for (y in 0 until size) {
            for (x in 0 until size) {
                val alpha = (ringCoverage(x, y, centre, radius.toFloat(), inner) * MAX_ALPHA)
                    .toInt().coerceIn(0, MAX_ALPHA)
                image.setPixel(x, y, (alpha shl ALPHA_SHIFT) or WHITE)
            }
        }

        val identifier = Identifier.fromNamespaceAndPath(NAMESPACE, "ring_mask_${radius}_$thickness")
        Minecraft.getInstance().textureManager.register(identifier, DynamicTexture({ "sidequest ring" }, image))
        identifier
    }.getOrNull()

    /** How much of one pixel lies between the two radii. */
    private fun ringCoverage(x: Int, y: Int, centre: Float, outer: Float, inner: Float): Float {
        var covered = 0
        for (sy in 0 until SAMPLES) {
            for (sx in 0 until SAMPLES) {
                val dx = x + (sx + HALF) / SAMPLES - centre
                val dy = y + (sy + HALF) / SAMPLES - centre
                val distance = sqrt(dx * dx + dy * dy)
                if (distance <= outer && distance >= inner) covered++
            }
        }
        return covered.toFloat() / (SAMPLES * SAMPLES)
    }

    private fun build(radius: Int): Identifier? = runCatching {
        val size = radius * 2
        val image = NativeImage(size, size, false)

        val centre = radius.toFloat()
        for (y in 0 until size) {
            for (x in 0 until size) {
                val alpha = (coverage(x, y, centre, radius.toFloat()) * MAX_ALPHA).toInt().coerceIn(0, MAX_ALPHA)
                // White, so the blit's tint supplies the colour and this supplies only the shape.
                image.setPixel(x, y, (alpha shl ALPHA_SHIFT) or WHITE)
            }
        }

        val identifier = Identifier.fromNamespaceAndPath(NAMESPACE, "corner_mask_$radius")
        Minecraft.getInstance().textureManager.register(identifier, DynamicTexture({ "sidequest corner" }, image))
        identifier
    }.getOrNull()

    /**
     * How much of one pixel lies inside the disc.
     *
     * Sampled on a grid within the pixel rather than solved analytically. The exact area of a circle-square
     * intersection is a page of case analysis for a result nobody could tell apart from this, and this runs
     * once per radius for the lifetime of the game.
     */
    private fun coverage(x: Int, y: Int, centre: Float, radius: Float): Float {
        var inside = 0
        for (sy in 0 until SAMPLES) {
            for (sx in 0 until SAMPLES) {
                val px = x + (sx + HALF) / SAMPLES
                val py = y + (sy + HALF) / SAMPLES
                val dx = px - centre
                val dy = py - centre
                if (sqrt(dx * dx + dy * dy) <= radius) inside++
            }
        }
        return inside.toFloat() / (SAMPLES * SAMPLES)
    }

    private const val NAMESPACE = "sidequest"

    /**
     * The largest arc worth baking, in physical pixels.
     *
     * A pill-shaped control at a high GUI scale is the biggest thing that asks, and it is well inside this.
     * The cap exists so that a caller passing something absurd allocates a small texture rather than a
     * enormous one — and falls back to the rasteriser, which is slow but has no such ceiling.
     */
    private const val MAX_RADIUS = 96

    /** Samples per axis within a pixel. Sixteen sub-samples is smoother than an eight-bit alpha can show. */
    private const val SAMPLES = 4

    private const val HALF = 0.5f
    private const val MAX_ALPHA = 255
    private const val ALPHA_SHIFT = 24

    /** Radius in the high bits, thickness in the low ones, so one map keys both. */
    private const val KEY_SHIFT = 16
    private const val WHITE = 0x00FFFFFF
}
