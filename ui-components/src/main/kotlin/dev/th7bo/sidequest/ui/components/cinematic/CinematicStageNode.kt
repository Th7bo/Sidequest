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
import dev.th7bo.sidequest.ui.rendering.UiRenderer

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
    ) : StageElement

    public data class Progress(public val fraction: Float, public val label: String) : StageElement

    /** A line that fades in partway through. */
    public data class Reveal(public val label: String, public val atFraction: Float) : StageElement
}

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
                is StageElement.Title -> layout(context, element.text, TITLE_SCALE)
                is StageElement.Subtitle -> layout(context, element.text, SUBTITLE_SCALE)
                is StageElement.Progress -> if (element.label.isNotEmpty()) layout(context, element.label, 1f)
                is StageElement.Reveal -> layout(context, element.label, 1f)
                is StageElement.Number -> {
                    // Every width the counter passes through, so a number growing from 1 to 1,000,000 does not
                    // measure a new string on the frame it gains a digit.
                    for (step in 0..COUNT_STEPS) {
                        val shown = element.value * step / COUNT_STEPS
                        layout(context, element.prefix + format(shown) + element.suffix, NUMBER_SCALE)
                    }
                }
                is StageElement.Letterbox, is StageElement.Backdrop -> Unit
            }
        }
        // The whole viewport. A cinematic is not laid out against anything, so an unbounded constraint means
        // the parent decides — which for the HUD root is the screen.
        return Size(
            if (constraints.hasBoundedWidth) constraints.maxWidth else 0f,
            if (constraints.hasBoundedHeight) constraints.maxHeight else 0f,
        )
    }

    private fun layout(context: LayoutContext, content: String, scale: Float) {
        if (content.isEmpty()) return
        layouts.getOrPut(key(content, scale)) {
            context.textMeasurer.measure(content, TextStyle(scale = scale, shadow = true))
        }
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        if (elements.isEmpty()) return

        // One opacity for the whole composition, so the pieces fade together rather than each doing its own
        // thing and arriving at slightly different times.
        val opacity = envelope(progress)
        renderer.pushOpacity(opacity)
        try {
            for (element in elements) draw(renderer, bounds, element)
        } finally {
            renderer.popOpacity()
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

            is StageElement.Title -> text(
                renderer,
                bounds,
                element.text,
                Color(OPAQUE or element.colour),
                scale = TITLE_SCALE,
                y = bounds.height * TITLE_Y,
            )

            is StageElement.Subtitle -> text(
                renderer,
                bounds,
                element.text,
                componentContext.theme.tokens.colors.textSecondary,
                scale = SUBTITLE_SCALE,
                y = bounds.height * SUBTITLE_Y,
            )

            is StageElement.Number -> {
                // Counted up over the first half, so it has landed by the time the reveals start. A counter
                // still running at the end reads as the cinematic being cut off.
                val shown = (element.value * countFraction(progress)).toLong()
                text(
                    renderer,
                    bounds,
                    element.prefix + format(shown) + element.suffix,
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
                        Size(width * element.fraction.coerceIn(0f, 1f) * countFraction(progress), BAR_HEIGHT),
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
                if (progress < element.atFraction) return
                // Each reveal keeps its own place, found from its position in the list, so they stack down the
                // screen rather than drawing over each other.
                val index = elements.filterIsInstance<StageElement.Reveal>().indexOf(element)
                text(
                    renderer,
                    bounds,
                    element.label,
                    componentContext.theme.tokens.colors.textPrimary,
                    scale = 1f,
                    y = bounds.height * (REVEAL_Y + index * REVEAL_SPACING),
                )
            }
        }
    }

    /** Draws one centred line at [y], scaled. */
    private fun text(
        renderer: UiRenderer,
        bounds: Rect,
        content: String,
        colour: Color,
        scale: Float,
        y: Float,
    ) {
        if (content.isEmpty()) return
        // Whatever was measured. A missing layout means the string changed since the last measure pass, which
        // is a dropped frame of text rather than a wrong one — and the next pass has it.
        val layout = layouts[key(content, scale)] ?: return
        renderer.text(
            layout,
            Vec2(bounds.x + (bounds.width - layout.size.width) / 2f, bounds.y + y),
            colour,
        )
    }

    private companion object {

        /** One key for the measured-layout cache. Scale is part of it: the same words at two sizes differ. */
        fun key(content: String, scale: Float): String = "$scale/$content"

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

        /** Counters and bars finish by halfway, so the reveals land against something settled. */
        fun countFraction(progress: Float): Float = (progress / COUNT_END).coerceIn(0f, 1f)

        /** Thousands separators. A seven-figure coin total is unreadable without them. */
        fun format(value: Long): String = value.toString()
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()

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
        const val NUMBER_Y = 0.48f
        const val BAR_Y = 0.56f
        const val BAR_LABEL_Y = 0.595f
        const val REVEAL_Y = 0.66f

        const val BAR_WIDTH = 0.4f
        const val BAR_HEIGHT = 6f

        /** Gap between reveals, as a fraction of the viewport. See the note above. */
        const val REVEAL_SPACING = 0.035f

        /** How many widths of a counting number are measured up front. See [measureSelf]. */
        const val COUNT_STEPS = 20

        /** Alpha bits for a colour given as 0xRRGGBB. */
        const val OPAQUE = 0xFF000000.toInt()
    }
}
