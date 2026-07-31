package dev.th7bo.sidequest.ui.components.cinematic

import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Dp
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.TextLayout
import dev.th7bo.sidequest.ui.rendering.TextStyle
import dev.th7bo.sidequest.ui.rendering.ItemRef
import dev.th7bo.sidequest.ui.rendering.TextureRef
import dev.th7bo.sidequest.ui.rendering.Transform
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One thing to draw during a cinematic, with no idea what a cinematic is.
 *
 * Deliberately *not* the platform's `CinematicComponent`. The UI framework has no Minecraft and no SkyBlock on
 * its classpath and must keep it that way, so the mod translates one vocabulary into the other — the same
 * split, and for the same reason, as the two `Notification` types.
 */
public sealed interface StageElement {

    /** Black bars top and bottom. */
    public data class Letterbox(public val heightFraction: Float) : StageElement

    public data class Backdrop(public val colour: Int, public val opacity: Float) : StageElement

    public data class Title(public val text: String, public val colour: Int) : StageElement

    public data class Subtitle(public val text: String) : StageElement

    /** Counts up to [value] over the first part of the run. */
    public data class Number(
        public val value: Long,
        public val prefix: String,
        public val suffix: String,
    ) : StageElement {

        /**
         * What the counter reads at a step.
         *
         * On the element rather than in the node, so the measure pass and the paint pass cannot compute it
         * differently — which they did, and the number flashed because almost no painted string had been
         * measured.
         */
        internal fun textAt(step: Int): String =
            prefix + groupDigits(value * step.coerceIn(0, COUNT_STEPS_INTERNAL) / COUNT_STEPS_INTERNAL) + suffix
    }

    public data class Progress(public val fraction: Float, public val label: String) : StageElement

    /** A line that fades in partway through. */
    public data class Reveal(public val label: String, public val atFraction: Float) : StageElement

    /**
     * A picture, drawn large above the title.
     *
     * The whole reason a rare-drop cinematic is worth watching: a name tells you what dropped and the item
     * tells you at a glance. Deliberately a [TextureRef] rather than anything item-shaped — this module has
     * no Minecraft on its classpath, and resolving a drop's name to something drawable is the adapter's job.
     * Here it is a picture with a size.
     */
    public data class Image(
        public val texture: TextureRef,
        /** Side length, as a fraction of the viewport's *height*, so it scales with the screen. */
        public val sizeFraction: Float = DEFAULT_IMAGE_FRACTION,
        /** A bloom behind it, in this colour. See [Item.glow]. */
        public val glow: Int? = null,
    ) : StageElement

    /**
     * The item itself, in the same place [Image] would go.
     *
     * A separate element rather than a flag on [Image] because the host draws it by a different route — its
     * own item renderer, with the model and the shimmer and the skin — and the two cannot share a code path.
     * Preferred whenever the host knows the item, since a real inventory-slot rendering is what makes the
     * cinematic look like the game announcing a drop.
     */
    public data class Item(
        public val item: ItemRef,
        /** Side length, as a fraction of the viewport's *height*. Matches [Image] so the two are swappable. */
        public val sizeFraction: Float = DEFAULT_IMAGE_FRACTION,
        /**
         * A bloom behind it, in this colour. Null draws none.
         *
         * Carried by the item rather than sitting on its own element because the two are one thing on screen:
         * the glow is how rare this is, and an item and a halo that could be positioned separately would
         * eventually be positioned separately.
         */
        public val glow: Int? = null,
    ) : StageElement
}

/** How big a stage image is by default. A sixth of the height reads as "the thing" without covering the view. */
public const val DEFAULT_IMAGE_FRACTION: Float = 0.16f

/** Thousands separators. A seven-figure coin total is unreadable without them. */
internal fun groupDigits(value: Long): String =
    value.toString().reversed().chunked(3).joinToString(",").reversed()

/** Shared by [StageElement.Number] and the node, which must agree on every value the counter shows. */
internal const val COUNT_STEPS_INTERNAL: Int = 40

/**
 * Draws a cinematic over the game.
 *
 * Owns no timing of its own beyond [progress], which the sink advances from the render clock: a cinematic that
 * measured its own elapsed time from `System.currentTimeMillis` would drift against the frames it is drawn on,
 * and would keep running while the game was paused.
 *
 * Everything is laid out from the viewport at paint time rather than through the layout system. A cinematic is
 * one full-screen composition with no children to arrange and no reflow to do, and putting it through the
 * measure/arrange machinery would buy nothing but a slower frame.
 */
public class CinematicStageNode(
    id: UiId,
    private val componentContext: ComponentContext,
) : UiNode(id) {

    /** What to draw. Empty means nothing is playing, and the node paints nothing at all. */
    public var elements: List<StageElement> = emptyList()
        set(value) {
            field = value
            // Measure, not just paint: the text layouts are built during the measure pass, because that is the
            // only pass handed a `TextMeasurer`. Invalidating only the paint would draw the previous
            // cinematic's words.
            invalidateMeasure()
        }

    /**
     * How far through, from 0 to 1.
     *
     * Drives the fades, the counter and the reveals. Set every frame by whoever owns the playback.
     */
    public var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidatePaint()
        }

    /** True while something is playing. Cheaper for a caller to read than the list. */
    public val isActive: Boolean get() = elements.isNotEmpty()

    /**
     * Laid-out text, keyed by what produced it.
     *
     * Built during measure because that is the pass with a [dev.th7bo.sidequest.ui.rendering.TextMeasurer], and
     * measuring text is the expensive part of drawing it — a cinematic redrawing at sixty frames a second must
     * not re-measure its title sixty times a second.
     */
    private val layouts = HashMap<String, TextLayout>()

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        layouts.clear()
        for (element in elements) {
            when (element) {
                is StageElement.Title -> layout(context, element.text, TITLE_SCALE, bold = true)
                is StageElement.Subtitle -> layout(context, element.text, SUBTITLE_SCALE)
                is StageElement.Progress -> if (element.label.isNotEmpty()) layout(context, element.label, 1f)
                is StageElement.Reveal -> layout(context, element.label, 1f)
                is StageElement.Number -> {
                    // Every string the counter will ever show, produced by the same function the paint pass
                    // uses. That shared function is the whole point: measuring a set of values and painting
                    // values computed a different way meant the paint almost never found a layout, so the
                    // number vanished on most frames and flashed.
                    for (step in 0..COUNT_STEPS) {
                        layout(context, element.textAt(step), NUMBER_SCALE)
                    }
                }
                // Nothing to measure: an image is sized from the viewport at paint time, and the bars are
                // fractions of it.
                is StageElement.Image, is StageElement.Item,
                is StageElement.Letterbox, is StageElement.Backdrop,
                -> Unit
            }
        }
        // The whole viewport. A cinematic is not laid out against anything, so an unbounded constraint means
        // the parent decides — which for the HUD root is the screen.
        return Size(
            if (constraints.hasBoundedWidth) constraints.maxWidth else 0f,
            if (constraints.hasBoundedHeight) constraints.maxHeight else 0f,
        )
    }

    private fun layout(context: LayoutContext, content: String, scale: Float, bold: Boolean = false) {
        if (content.isEmpty()) return
        layouts.getOrPut(key(content, scale, bold)) {
            context.textMeasurer.measure(content, TextStyle(scale = scale, bold = bold, shadow = true))
        }
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        if (elements.isEmpty()) return

        // The envelope for the composition as a whole — it fades in, holds, and fades out together.
        renderer.pushOpacity(envelope(progress))
        try {
            for (element in elements) {
                // And each piece arrives in its own turn on top of that. Everything appearing at once reads as
                // a screenshot rather than as something happening; the frame lands first, then the title, then
                // what it is about. The opacity stack multiplies, so this composes with the envelope above.
                val entrance = entranceOf(element)
                if (entrance <= 0f) continue

                if (entrance >= 1f) {
                    draw(renderer, bounds, element)
                } else {
                    renderer.pushOpacity(entrance)
                    try {
                        draw(renderer, bounds, element)
                    } finally {
                        renderer.popOpacity()
                    }
                }
            }
        } finally {
            renderer.popOpacity()
        }
    }

    /**
     * How far into its own entrance this element is, from 0 to 1.
     *
     * A reveal keeps the cue it was given; everything else takes the order below, which is the order somebody
     * reads a cinematic in.
     */
    private fun entranceOf(element: StageElement): Float {
        val at = when (element) {
            // The frame is the cinematic starting. It has no entrance of its own — the slide is its entrance.
            is StageElement.Letterbox, is StageElement.Backdrop -> 0f
            is StageElement.Title -> TITLE_AT
            is StageElement.Subtitle -> SUBTITLE_AT
            // First of everything: the picture is what the cinematic is about, so it arrives before
            // the words describing it.
            is StageElement.Image, is StageElement.Item -> 0f
            is StageElement.Number -> NUMBER_AT
            is StageElement.Progress -> PROGRESS_AT
            is StageElement.Reveal -> element.atFraction
        }
        return entranceOf(at)
    }

    /** The same, for a cue already known. Split out so a draw can ask how far along its own entrance is. */
    private fun entranceOf(at: Float): Float {
        if (at <= 0f) return 1f
        return ((progress - at) / ENTRANCE).coerceIn(0f, 1f)
    }

    /**
     * Where the thing that dropped goes: square, centred, sitting above the title.
     *
     * Sized off the height rather than the width so an ultrawide screen does not get an enormous one. Shared
     * by [StageElement.Image] and [StageElement.Item] so that swapping a flat texture for a real item does not
     * move it — which it would if each computed its own box.
     */
    private fun showcase(bounds: Rect, sizeFraction: Float): Rect {
        val side = bounds.height * sizeFraction
        return Rect(
            bounds.x + (bounds.width - side) / 2f,
            bounds.y + bounds.height * IMAGE_Y - side / 2f,
            side,
            side,
        )
    }

    /**
     * Everything behind the item: the bloom, and the burst it lands with.
     *
     * Drawn before the item and never over it. The item is the thing being announced, and an effect that
     * covered it would be decoration winning over the subject.
     */
    private fun flourish(renderer: UiRenderer, box: Rect, glow: Int?) {
        if (glow == null) return
        val colour = Color(OPAQUE or glow)
        bloom(renderer, box, colour)
        burst(renderer, box, colour)
    }

    /**
     * A soft halo in the drop's own colour.
     *
     * Built from concentric discs rather than a radial gradient, because there is no radial primitive — the
     * host's gradient is a vertical two-stop fill. Overlapping translucent discs accumulate into a falloff
     * that is close enough, and costs a handful of rounded rectangles.
     *
     * It breathes rather than sitting still. A static halo reads as a graphic pasted behind the item; one that
     * moves, even slightly, reads as light coming off it.
     */
    private fun bloom(renderer: UiRenderer, box: Rect, colour: Color) {
        // Grows in with the item and then breathes. Held at full size while the item was still arriving, it
        // read as a halo waiting for something to fill it.
        val breath = pop((progress / ITEM_POP).coerceIn(0f, 1f)) *
            (1f + BLOOM_BREATH * sin(progress * BLOOM_CYCLES * TAU))
        if (breath <= 0f) return
        val centre = Vec2(box.x + box.width / 2f, box.y + box.height / 2f)

        for (ring in 0 until BLOOM_RINGS) {
            // Outermost first, so the inner discs stack on top and the middle ends up brightest.
            val spread = BLOOM_OUTER - (BLOOM_OUTER - BLOOM_INNER) * (ring / (BLOOM_RINGS - 1f))
            val radius = box.width * spread * breath / 2f
            renderer.roundedRect(
                Rect(centre.x - radius, centre.y - radius, radius * 2f, radius * 2f),
                Dp(radius),
                colour.withAlpha(BLOOM_ALPHA),
            )
        }
    }

    /**
     * Motes thrown outward as the item lands, then fading.
     *
     * Deterministic: each mote's direction comes from its index, so the burst is the same shape every time
     * rather than flickering to a new arrangement each frame. There is no particle system here and there
     * should not be — this is a dozen squares on a circle whose radius is a function of progress.
     */
    private fun burst(renderer: UiRenderer, box: Rect, colour: Color) {
        val age = ((progress - BURST_AT) / BURST_LIFE).coerceIn(0f, 1f)
        if (age <= 0f || age >= 1f) return

        val centre = Vec2(box.x + box.width / 2f, box.y + box.height / 2f)
        // Fast out, slowing down — the shape of something thrown rather than something travelling.
        val eased = 1f - (1f - age) * (1f - age)
        val distance = box.width * (BURST_FROM + (BURST_TO - BURST_FROM) * eased)
        val size = box.width * BURST_SIZE * (1f - age)

        for (mote in 0 until BURST_MOTES) {
            // Two interleaved rings at different radii, so it does not read as a clock face.
            val angle = mote * TAU / BURST_MOTES
            val reach = distance * (if (mote % 2 == 0) 1f else BURST_INNER_RING)
            val x = centre.x + cos(angle) * reach
            val y = centre.y + sin(angle) * reach
            renderer.fillRect(
                Rect(x - size / 2f, y - size / 2f, size, size),
                colour.withAlpha(BURST_ALPHA * (1f - age)),
            )
        }
    }

    /**
     * Draws [content] scaled about the centre of [box], overshooting on the way in.
     *
     * The item arrives rather than appearing. It cannot fade — the host's item rendering has nowhere to take
     * an opacity, as the renderer notes — so the entrance has to be motion, and a small overshoot is what
     * makes it read as landing instead of growing.
     */
    private fun popped(renderer: UiRenderer, box: Rect, content: () -> Unit) {
        // Driven straight off the run rather than off an entrance cue, because the item has no cue: it leads
        // the cinematic and is on screen from the first frame. The growth *is* its entrance.
        val scale = pop((progress / ITEM_POP).coerceIn(0f, 1f))
        if (scale <= 0f) return

        val centre = Vec2(box.x + box.width / 2f, box.y + box.height / 2f)
        // A slow drift once it has settled, so the item is never completely still.
        val drift = box.height * BOB_AMPLITUDE * sin(progress * BOB_CYCLES * TAU)

        renderer.pushTransform(Transform.translation(Vec2(0f, drift)))
        renderer.pushTransform(Transform.scale(scale, origin = centre))
        try {
            content()
        } finally {
            renderer.popTransform()
            renderer.popTransform()
        }
    }

    private fun draw(renderer: UiRenderer, bounds: Rect, element: StageElement) {
        when (element) {
            is StageElement.Backdrop -> renderer.fillRect(
                bounds,
                Color(OPAQUE or element.colour).withAlpha(element.opacity),
            )

            is StageElement.Letterbox -> {
                val height = bounds.height * element.heightFraction
                // Slid in from off-screen rather than faded: a bar that fades looks like a rendering fault,
                // and the slide is what reads as "something is about to happen".
                val shown = height * slide(progress)
                val black = Color.Black
                renderer.fillRect(Rect.of(Vec2(bounds.x, bounds.y), Size(bounds.width, shown)), black)
                renderer.fillRect(
                    Rect.of(Vec2(bounds.x, bounds.bottom - shown), Size(bounds.width, shown)),
                    black,
                )
            }

            is StageElement.Image -> showcase(bounds, element.sizeFraction).let { box ->
                flourish(renderer, box, element.glow)
                popped(renderer, box) { renderer.image(element.texture, box, Color.White) }
            }

            is StageElement.Item -> showcase(bounds, element.sizeFraction).let { box ->
                flourish(renderer, box, element.glow)
                popped(renderer, box) { renderer.item(element.item, box) }
            }

            // Bold, because the title *is* the rarity — `VERY RARE DROP` — and Hypixel writes it bold in
            // chat. Matching that is what makes the cinematic read as the game announcing something rather
            // than as the mod captioning it.
            is StageElement.Title -> text(
                renderer,
                bounds,
                element.text,
                Color(OPAQUE or element.colour),
                scale = TITLE_SCALE,
                // Rises into place rather than appearing at it. The fade alone reads as a caption switching
                // on; a few pixels of travel reads as the words arriving.
                y = bounds.height * TITLE_Y + rise(TITLE_AT, bounds),
                bold = true,
            )

            is StageElement.Subtitle -> text(
                renderer,
                bounds,
                element.text,
                componentContext.theme.tokens.colors.textSecondary,
                scale = SUBTITLE_SCALE,
                y = bounds.height * SUBTITLE_Y + rise(SUBTITLE_AT, bounds),
            )

            is StageElement.Number -> {
                // Counted up over the first half, so it has landed by the time the reveals start. A counter
                // still running at the end reads as the cinematic being cut off.
                //
                // Quantised to the measured steps rather than interpolated continuously, which is both the fix
                // for the flashing and the better look: the number ticks up in readable increments instead of
                // blurring through values nobody can see.
                text(
                    renderer,
                    bounds,
                    element.textAt(countStep(progress, NUMBER_AT)),
                    componentContext.theme.tokens.colors.accent,
                    scale = NUMBER_SCALE,
                    y = bounds.height * NUMBER_Y,
                )
            }

            is StageElement.Progress -> {
                val width = bounds.width * BAR_WIDTH
                val left = bounds.x + (bounds.width - width) / 2f
                val top = bounds.y + bounds.height * BAR_Y
                val track = Rect.of(Vec2(left, top), Size(width, BAR_HEIGHT))
                renderer.roundedRect(track, Dp(BAR_HEIGHT / 2f), componentContext.theme.tokens.colors.panelBackground)
                renderer.roundedRect(
                    Rect.of(
                        Vec2(left, top),
                        Size(
                            width * element.fraction.coerceIn(0f, 1f) * countFraction(progress, PROGRESS_AT),
                            BAR_HEIGHT,
                        ),
                    ),
                    Dp(BAR_HEIGHT / 2f),
                    componentContext.theme.tokens.colors.accent,
                )
                if (element.label.isNotEmpty()) {
                    text(
                        renderer,
                        bounds,
                        element.label,
                        componentContext.theme.tokens.colors.textSecondary,
                        scale = 1f,
                        y = bounds.height * BAR_LABEL_Y,
                    )
                }
            }

            is StageElement.Reveal -> {
                // No cut-off here: the entrance above already faded it in, and a hard test would make it pop
                // into place at full opacity a moment after starting to fade.
                // Each reveal keeps its own place, found from its position in the list, so they stack down the
                // screen rather than drawing over each other.
                val index = elements.filterIsInstance<StageElement.Reveal>().indexOf(element)
                text(
                    renderer,
                    bounds,
                    element.label,
                    componentContext.theme.tokens.colors.textPrimary,
                    scale = 1f,
                    y = bounds.height * (REVEAL_Y + index * REVEAL_SPACING) + rise(element.atFraction, bounds),
                )
            }
        }
    }

    /**
     * How far below its resting place a line still is, for something whose entrance began at [at].
     *
     * Falls to zero as the entrance completes, so a line travels only while it is fading in and is exactly
     * where the layout says once it has arrived. Measured as a fraction of the viewport, like every other
     * vertical distance here — a fixed pixel offset would be a different amount of travel at every GUI scale.
     */
    private fun rise(at: Float, bounds: Rect): Float =
        bounds.height * RISE * (1f - entranceOf(at))

    /** Draws one centred line at [y], scaled. */
    private fun text(
        renderer: UiRenderer,
        bounds: Rect,
        content: String,
        colour: Color,
        scale: Float,
        y: Float,
        bold: Boolean = false,
    ) {
        if (content.isEmpty()) return
        // Whatever was measured. A missing layout means the string changed since the last measure pass, which
        // is a dropped frame of text rather than a wrong one — and the next pass has it.
        val layout = layouts[key(content, scale, bold)] ?: return
        renderer.text(
            layout,
            Vec2(bounds.x + (bounds.width - layout.size.width) / 2f, bounds.y + y),
            colour,
        )
    }

    private companion object {

        /** One key for the measured-layout cache. Scale is part of it: the same words at two sizes differ. */
        fun key(content: String, scale: Float, bold: Boolean): String =
            (if (bold) "b" else "") + "$scale/$content"

        /**
         * Fade in, hold, fade out.
         *
         * A trapezoid rather than a curve because the hold is the part that matters: the player has to be able
         * to *read* it, and an ease that spends the whole run moving never sits still long enough.
         */
        fun envelope(progress: Float): Float = when {
            progress < FADE -> progress / FADE
            progress > 1f - FADE -> (1f - progress) / FADE
            else -> 1f
        }.coerceIn(0f, 1f)

        /** Bars slide fully in over the fade and stay. */
        fun slide(progress: Float): Float = (progress / FADE).coerceIn(0f, 1f)

        /**
         * Grows past its size and settles back.
         *
         * The overshoot is the whole effect: something that eases to exactly its final size reads as growing,
         * and something that goes slightly too far and comes back reads as landing. Roughly eight percent at
         * the peak — enough to notice, not enough to look like a bug.
         */
        fun pop(t: Float): Float {
            val back = t - 1f
            return 1f + OVERSHOOT_CUBIC * back * back * back + OVERSHOOT_SQUARE * back * back
        }

        private const val OVERSHOOT_SQUARE = 1.70158f
        private const val OVERSHOOT_CUBIC = OVERSHOOT_SQUARE + 1f

        /**
         * Which counting step this progress falls on.
         *
         * The one place a counter's value is decided, used by both the measure pass and the paint pass.
         * Computing it two different ways is what made the number flash: the measured strings and the painted
         * ones did not match, so almost every frame found no layout and drew nothing.
         *
         * Counted from [from] — the point at which the element appears — rather than from the start of the
         * cinematic. Otherwise a counter that fades in a fifth of the way through would arrive already a fifth
         * counted, which reads as having missed the beginning of it.
         */
        fun countStep(progress: Float, from: Float): Int {
            val span = COUNT_END - from
            if (span <= 0f) return COUNT_STEPS
            return (((progress - from) / span).coerceIn(0f, 1f) * COUNT_STEPS).toInt()
        }

        /** Bars fill over the same span as a counter counts, from the point they appear. */
        fun countFraction(progress: Float, from: Float): Float =
            countStep(progress, from).toFloat() / COUNT_STEPS

        const val FADE = 0.15f
        const val COUNT_END = 0.5f

        const val TITLE_SCALE = 2f
        const val SUBTITLE_SCALE = 1.2f
        const val NUMBER_SCALE = 1.6f

        /*
         * Every vertical position is a fraction of the viewport, and that is a fix rather than a preference.
         *
         * These were fractions mixed with absolute pixel offsets, which agree at one size and nowhere else: the
         * fractions scale with the viewport and the offsets do not, so the progress label landed on top of the
         * first reveal. A real client screenshot caught it — nothing headless would have, because nothing
         * headless renders at the player's GUI scale.
         */
        const val TITLE_Y = 0.30f
        const val SUBTITLE_Y = 0.40f

        /**
         * Where the item sits, centred on this fraction of the height.
         *
         * Below the words rather than above them, which is the order the announcement actually reads in: the
         * headline says how rare it was, the subtitle says why, and then the thing itself appears above its
         * own name. Above the title it was competing with the headline for the top of the screen.
         *
         * It occupies the band [NUMBER_Y] and [BAR_Y] sit in, and would overlap either. Nothing composes them
         * together — a drop cinematic has no counter and no progress bar — but a cinematic that wanted both a
         * picture and a running total would need a layout, not a nudge to this number.
         */
        const val IMAGE_Y = 0.53f

        const val NUMBER_Y = 0.48f
        const val BAR_Y = 0.56f
        const val BAR_LABEL_Y = 0.595f
        const val REVEAL_Y = 0.66f

        const val BAR_WIDTH = 0.4f
        const val BAR_HEIGHT = 6f

        /** Gap between reveals, as a fraction of the viewport. See the note above. */
        const val REVEAL_SPACING = 0.035f

        /**
         * How many values a counter passes through.
         *
         * Every one is measured up front, so this is also the number of distinct strings the number can show.
         * Forty over two seconds is twenty a second — smooth enough to read as counting, coarse enough that
         * each value is on screen for more than one frame.
         */
        const val COUNT_STEPS = COUNT_STEPS_INTERNAL

        /**
         * When each piece arrives, as a fraction of the run, and how long its fade takes.
         *
         * The order somebody reads a cinematic in: the frame, then what it is, then the detail.
         */
        const val TITLE_AT = 0.04f
        const val SUBTITLE_AT = 0.14f
        const val NUMBER_AT = 0.20f
        const val PROGRESS_AT = 0.26f
        const val ENTRANCE = 0.10f

        // -- the item's own animation ----------------------------------------

        /** How much of the run the item spends growing into place. */
        const val ITEM_POP = 0.12f

        /** How far a line of text travels on its way in, as a fraction of the viewport's height. */
        const val RISE = 0.022f

        /** A full turn, for the two things here that go round in a circle. */
        const val TAU = (2.0 * PI).toFloat()

        /** How far the item drifts once settled, as a fraction of its own height, and how often. */
        const val BOB_AMPLITUDE = 0.035f
        const val BOB_CYCLES = 2f

        /**
         * The halo.
         *
         * Discs from [BLOOM_OUTER] down to [BLOOM_INNER] times the item's width, each at [BLOOM_ALPHA]. They
         * overlap, so the alpha accumulates towards the middle rather than each ring being visible.
         */
        const val BLOOM_RINGS = 6
        const val BLOOM_OUTER = 2.4f
        const val BLOOM_INNER = 0.8f
        const val BLOOM_ALPHA = 0.10f

        /** How much the halo breathes, and how many times over the run. */
        const val BLOOM_BREATH = 0.06f
        const val BLOOM_CYCLES = 1.5f

        /**
         * The burst that lands with the item.
         *
         * It starts just after the pop finishes — the motes should look thrown *by* the landing, not present
         * before it — and is gone within a fifth of the run, because a burst that lingers is confetti.
         */
        const val BURST_AT = ITEM_POP
        const val BURST_LIFE = 0.20f
        const val BURST_MOTES = 12
        const val BURST_FROM = 0.35f
        const val BURST_TO = 1.30f
        const val BURST_INNER_RING = 0.72f
        const val BURST_SIZE = 0.07f
        const val BURST_ALPHA = 0.85f

        /** Alpha bits for a colour given as 0xRRGGBB. */
        const val OPAQUE = 0xFF000000.toInt()
    }
}
