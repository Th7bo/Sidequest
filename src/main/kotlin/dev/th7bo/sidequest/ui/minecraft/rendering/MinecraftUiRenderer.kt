package dev.th7bo.sidequest.ui.minecraft.rendering

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
import dev.th7bo.sidequest.ui.rendering.TextureRef
import dev.th7bo.sidequest.ui.rendering.Transform
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Draws the framework's abstract commands with Minecraft's GUI renderer.
 *
 * The only class in the project that knows both sides. Everything above it works in
 * logical units and abstract colours; everything below is `GuiGraphicsExtractor`.
 *
 * The same source compiles for 26.1.2 and 26.2 without a single version conditional:
 * every method used here — `fill`, `enableScissor`, `pose`, `text`, `blit` — is
 * identical across both. Only `entity`, `sign` and `skin` differ, and none are used.
 *
 * A renderer instance is cheap and lives for one frame, because the
 * `GuiGraphicsExtractor` it wraps does.
 */
public class MinecraftUiRenderer(
    private val graphics: GuiGraphicsExtractor,
    private val font: Font,
    override val textMeasurer: TextMeasurer,
    override val frame: FrameInfo,
) : UiRenderer {

    /**
     * Scissor rectangles, innermost last.
     *
     * Minecraft's scissor is not a stack — `disableScissor` clears it outright — so
     * nesting is maintained here and the intersection is re-applied on pop. Without
     * this, closing an inner clip would un-clip the outer one.
     */
    private val clipStack = ArrayList<Rect>(DEFAULT_STACK_DEPTH)

    /** Multiplied into every colour, so a faded subtree fades its whole content. */
    private val opacityStack = ArrayList<Float>(DEFAULT_STACK_DEPTH)

    private var transformDepth = 0

    private val effectiveOpacity: Float
        get() {
            var value = 1f
            for (level in opacityStack) value *= level
            return value
        }

    /** Applies the opacity stack. Every draw goes through this. */
    private fun resolve(color: Color): Int {
        val opacity = effectiveOpacity
        return if (opacity >= 1f) color.argb else color.scaleAlpha(opacity).argb
    }

    // -- shapes -------------------------------------------------------------

    override fun fillRect(bounds: Rect, color: Color) {
        if (color.isTransparent || bounds.isEmpty) return
        graphics.fill(
            bounds.left.roundToInt(),
            bounds.top.roundToInt(),
            bounds.right.roundToInt(),
            bounds.bottom.roundToInt(),
            resolve(color),
        )
    }

    /**
     * A rounded rectangle, approximated by horizontal spans.
     *
     * Minecraft's GUI renderer has no rounded-rect primitive and no shader hook that is
     * stable across versions, so the corners are cut with per-row spans: the body is one
     * fill, and each corner row is a narrower fill. At the radii the design language uses
     * (4–10 units) that is a handful of extra quads and is visually indistinguishable
     * from a real rounded rect at GUI scale.
     */
    override fun roundedRect(bounds: Rect, radius: Dp, color: Color) {
        roundedRect(bounds, Corners.all(radius), color)
    }

    override fun roundedRect(bounds: Rect, corners: Corners, color: Color) {
        if (color.isTransparent || bounds.isEmpty) return

        val packed = resolve(color)
        val left = bounds.left.roundToInt()
        val top = bounds.top.roundToInt()
        val right = bounds.right.roundToInt()
        val bottom = bounds.bottom.roundToInt()

        val limit = min(bounds.width, bounds.height) / 2f
        val topLeft = clampCorner(corners.topLeft, limit)
        val topRight = clampCorner(corners.topRight, limit)
        val bottomLeft = clampCorner(corners.bottomLeft, limit)
        val bottomRight = clampCorner(corners.bottomRight, limit)

        val topBand = max(topLeft, topRight)
        val bottomBand = max(bottomLeft, bottomRight)

        if (topBand == 0 && bottomBand == 0) {
            graphics.fill(left, top, right, bottom, packed)
            return
        }

        // Middle band, full width.
        graphics.fill(left, top + topBand, right, bottom - bottomBand, packed)

        // Corner rows, each inset by its own circular profile.
        for (row in 0 until topBand) {
            graphics.fill(
                left + cornerInset(topLeft, row),
                top + row,
                right - cornerInset(topRight, row),
                top + row + 1,
                packed,
            )
        }
        for (row in 0 until bottomBand) {
            graphics.fill(
                left + cornerInset(bottomLeft, row),
                bottom - row - 1,
                right - cornerInset(bottomRight, row),
                bottom - row,
                packed,
            )
        }
    }

    /** A radius cannot exceed half the shorter side, or the corners would invert. */
    private fun clampCorner(radius: Dp, limit: Float): Int =
        min(radius.value, limit).coerceAtLeast(0f).roundToInt()

    /**
     * How far in from the edge row [rowFromEdge] of a [radius]-sized corner starts.
     *
     * Rows past the corner are flush with the edge, which is what lets two different
     * radii share one band.
     */
    private fun cornerInset(radius: Int, rowFromEdge: Int): Int {
        if (rowFromEdge >= radius) return 0
        val opposite = radius - rowFromEdge - 1
        return radius - sqrt((radius * radius - opposite * opposite).toFloat()).roundToInt()
    }

    override fun border(bounds: Rect, radius: Dp, width: Dp, color: Color) {
        if (color.isTransparent || bounds.isEmpty) return

        val packed = resolve(color)
        val thickness = max(1, width.value.roundToInt())
        val left = bounds.left.roundToInt()
        val top = bounds.top.roundToInt()
        val right = bounds.right.roundToInt()
        val bottom = bounds.bottom.roundToInt()
        val corner = min(radius.value, min(bounds.width, bounds.height) / 2f).roundToInt()

        // Horizontal edges, shortened by the corner radius so they do not overhang.
        graphics.fill(left + corner, top, right - corner, top + thickness, packed)
        graphics.fill(left + corner, bottom - thickness, right - corner, bottom, packed)
        // Vertical edges.
        graphics.fill(left, top + corner, left + thickness, bottom - corner, packed)
        graphics.fill(right - thickness, top + corner, right, bottom - corner, packed)

        if (corner <= 0) return
        // Corner arcs, one span per row.
        for (row in 0 until corner) {
            val inset = corner - sqrt((corner * corner - (corner - row - 1) * (corner - row - 1)).toFloat())
                .roundToInt()
            graphics.fill(left + inset, top + row, left + inset + thickness, top + row + 1, packed)
            graphics.fill(right - inset - thickness, top + row, right - inset, top + row + 1, packed)
            graphics.fill(left + inset, bottom - row - 1, left + inset + thickness, bottom - row, packed)
            graphics.fill(right - inset - thickness, bottom - row - 1, right - inset, bottom - row, packed)
        }
    }

    override fun gradient(bounds: Rect, gradient: Gradient, radius: Dp) {
        if (bounds.isEmpty) return

        // Minecraft's fillGradient is vertical two-stop only, so multi-stop and
        // horizontal gradients are drawn as bands between consecutive stops.
        val stops = gradient.stops
        for (index in 0 until stops.size - 1) {
            val from = stops[index]
            val to = stops[index + 1]
            when (gradient.direction) {
                Gradient.Direction.VERTICAL -> {
                    val startY = (bounds.top + bounds.height * from.position).roundToInt()
                    val endY = (bounds.top + bounds.height * to.position).roundToInt()
                    graphics.fillGradient(
                        bounds.left.roundToInt(),
                        startY,
                        bounds.right.roundToInt(),
                        endY,
                        resolve(from.color),
                        resolve(to.color),
                    )
                }
                Gradient.Direction.HORIZONTAL -> {
                    // No horizontal primitive exists; step it in narrow columns.
                    val startX = bounds.left + bounds.width * from.position
                    val endX = bounds.left + bounds.width * to.position
                    val span = max(1, (endX - startX).roundToInt())
                    for (column in 0 until span) {
                        val t = column.toFloat() / span
                        graphics.fill(
                            (startX + column).roundToInt(),
                            bounds.top.roundToInt(),
                            (startX + column).roundToInt() + 1,
                            bounds.bottom.roundToInt(),
                            resolve(from.color.lerp(to.color, t)),
                        )
                    }
                }
            }
        }
    }

    /**
     * A soft drop shadow, approximated by concentric fading outlines.
     *
     * Shadows are decorative — nothing in the framework relies on one for legibility —
     * so a cheap approximation is the right trade rather than a blur pass.
     */
    override fun shadow(bounds: Rect, radius: Dp, shadow: Shadow) {
        if (shadow.color.isTransparent || shadow.blurRadius <= 0f) return

        val steps = min(shadow.blurRadius.roundToInt(), MAX_SHADOW_STEPS)
        if (steps <= 0) return

        val base = bounds.translate(shadow.offset)
        for (step in steps downTo 1) {
            val spread = shadow.spread + step
            val fade = shadow.color.alphaFraction * (1f - step.toFloat() / (steps + 1)) / steps
            val ring = base.outset(
                dev.th7bo.sidequest.ui.geometry.Insets(spread, spread, spread, spread),
            )
            roundedRect(ring, Dp(radius.value + spread), shadow.color.withAlpha(fade))
        }
    }

    /**
     * Blur behind [bounds].
     *
     * Minecraft can only blur everything drawn below the current stratum, not an
     * arbitrary rectangle, so this promotes the stratum and blurs behind it. That means
     * blur is a whole-layer effect; a caller asking for two different blurred regions in
     * one frame gets one blur. Documented rather than silently approximated, and the
     * theme's `blurStrength = 0` disables it wholesale.
     */
    override fun blur(bounds: Rect, radius: Dp, strength: Float) {
        if (strength <= 0f) return
        graphics.nextStratum()
        graphics.blurBeforeThisStratum()
    }

    // -- content ------------------------------------------------------------

    /**
     * Draws a laid-out string.
     *
     * The style's scale is applied through the pose stack, because Minecraft's font has
     * one fixed size. Skipping this makes measured and drawn widths disagree: a caption
     * measured at 0.85× would be drawn at 1× and overflow whatever laid it out, and a
     * title at 1.15× would come out the same size as body text.
     */
    override fun text(layout: TextLayout, position: Vec2, color: Color) {
        val packed = resolve(color)
        val scale = layout.style.scale
        val lineHeight = font.lineHeight * layout.style.lineHeight

        if (scale == 1f) {
            drawLines(layout, position.x, position.y, lineHeight, packed)
            return
        }

        val pose = graphics.pose()
        pose.pushMatrix()
        try {
            pose.translate(position.x, position.y)
            pose.scale(scale, scale)
            // Inside the scaled space the origin is the text's own top-left, and the
            // line height is the font's unscaled one.
            drawLines(layout, 0f, 0f, font.lineHeight * layout.style.lineHeight, packed)
        } finally {
            pose.popMatrix()
        }
    }

    private fun drawLines(
        layout: TextLayout,
        originX: Float,
        originY: Float,
        lineHeight: Float,
        packed: Int,
    ) {
        for ((index, line) in layout.lines.withIndex()) {
            if (line.content.isEmpty()) continue
            graphics.text(
                font,
                line.content,
                originX.roundToInt(),
                (originY + lineHeight * index).roundToInt(),
                packed,
                layout.style.shadow,
            )
        }
    }

    override fun icon(icon: Icon, bounds: Rect, tint: Color) {
        blitTexture(icon.id, bounds, Rect(0f, 0f, 1f, 1f), tint)
    }

    override fun image(texture: TextureRef, bounds: Rect, tint: Color) {
        blitTexture(texture.id, bounds, texture.region, tint)
    }

    /**
     * Draws a real item, at whatever size the caller asked for.
     *
     * Minecraft draws an item at exactly one size — the sixteen-pixel inventory slot — so anything larger is a
     * scale around it. The matrix is pushed and popped here rather than by the caller, because getting the
     * translate-then-scale order wrong puts the item somewhere off screen and the caller has no way to know.
     *
     * **The opacity stack does not reach this.** Item rendering runs through the game's own pipeline with its
     * own lighting and has nowhere to take a tint, so an item inside a fading subtree stays solid. Nothing
     * currently fades one, and it is worth knowing before something tries.
     */
    override fun item(item: ItemRef, bounds: Rect) {
        if (bounds.isEmpty) return
        val stack = MinecraftItemStacks.stackFor(item) ?: return

        val scale = min(bounds.width, bounds.height) / SLOT_SIZE
        val pose = graphics.pose()
        pose.pushMatrix()
        // Centred within the box it was given, so a non-square box does not shift it off its own axis.
        pose.translate(
            bounds.left + (bounds.width - SLOT_SIZE * scale) / 2f,
            bounds.top + (bounds.height - SLOT_SIZE * scale) / 2f,
        )
        pose.scale(scale, scale)
        graphics.item(stack, 0, 0)
        pose.popMatrix()
    }

    /**
     * Resolves a texture id and blits it.
     *
     * The convention: `sidequest:gui.icon.gear` resolves to
     * `assets/sidequest/textures/gui/icon/gear.png`. Ids cannot contain slashes or a
     * file extension — the id grammar forbids both — so the adapter supplies them, which
     * also keeps every texture reference in the framework host-agnostic.
     */
    private fun blitTexture(
        id: dev.th7bo.sidequest.ui.ids.UiId,
        bounds: Rect,
        region: Rect,
        tint: Color,
    ) {
        if (bounds.isEmpty || tint.isTransparent) return
        val identifier = Identifier.fromNamespaceAndPath(
            id.namespace,
            "textures/" + id.path.replace('.', '/') + ".png",
        )

        // Argument order confirmed against the bytecode, which forwards to
        // `innerBlit(pipeline, id, x1, x2, y1, y2, u0, u1, v0, v1, tint)`:
        // the geometry is edges, *not* width and height, and the texture coordinates are
        // grouped by axis. Passing width/height here silently draws nothing, because the
        // resulting quad is inverted.
        graphics.blit(
            identifier,
            bounds.left.roundToInt(),
            bounds.top.roundToInt(),
            bounds.right.roundToInt(),
            bounds.bottom.roundToInt(),
            region.left,
            region.right,
            region.top,
            region.bottom,
        )
    }

    // -- state stacks -------------------------------------------------------

    override fun pushClip(bounds: Rect) {
        val effective = clipStack.lastOrNull()?.intersect(bounds) ?: bounds
        clipStack.add(effective)
        applyScissor(effective)
    }

    override fun popClip() {
        check(clipStack.isNotEmpty()) { "popClip without a matching pushClip" }
        clipStack.removeAt(clipStack.size - 1)
        val restored = clipStack.lastOrNull()
        if (restored == null) graphics.disableScissor() else applyScissor(restored)
    }

    private fun applyScissor(bounds: Rect) {
        graphics.enableScissor(
            bounds.left.roundToInt(),
            bounds.top.roundToInt(),
            bounds.right.roundToInt(),
            bounds.bottom.roundToInt(),
        )
    }

    override fun pushTransform(transform: Transform) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(transform.translateX, transform.translateY)
        if (transform.scaleX != 1f || transform.scaleY != 1f) {
            pose.scale(transform.scaleX, transform.scaleY)
        }
        transformDepth++
    }

    override fun popTransform() {
        check(transformDepth > 0) { "popTransform without a matching pushTransform" }
        graphics.pose().popMatrix()
        transformDepth--
    }

    override fun pushOpacity(opacity: Float) {
        opacityStack.add(opacity.coerceIn(0f, 1f))
    }

    override fun popOpacity() {
        check(opacityStack.isNotEmpty()) { "popOpacity without a matching pushOpacity" }
        opacityStack.removeAt(opacityStack.size - 1)
    }

    /**
     * Releases anything the frame left behind.
     *
     * A component that throws mid-paint would otherwise leave a scissor or a pose
     * pushed, corrupting every later frame — a bug that shows up far from its cause.
     */
    public fun endFrame() {
        while (transformDepth > 0) {
            graphics.pose().popMatrix()
            transformDepth--
        }
        if (clipStack.isNotEmpty()) {
            clipStack.clear()
            graphics.disableScissor()
        }
        opacityStack.clear()
    }

    /** True when the frame ended with balanced stacks. Checked by the screen in dev builds. */
    public val isBalanced: Boolean
        get() = clipStack.isEmpty() && opacityStack.isEmpty() && transformDepth == 0

    private companion object {
        const val DEFAULT_STACK_DEPTH = 8
        const val MAX_SHADOW_STEPS = 6

        /** The one size Minecraft draws an item at. Everything else is a scale around it. */
        const val SLOT_SIZE = 16f
    }
}
