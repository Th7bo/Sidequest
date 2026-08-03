package dev.th7bo.sidequest.ui.components

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
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One choice on a radial menu. */
public data class RadialOption(
    public val id: String,
    public val label: String,
    /** ARGB. Radial menus are read by colour and position before they are read by word. */
    public val colour: Int,
)

/**
 * A ring of choices around the crosshair, picked by direction.
 *
 * The interaction a radial menu exists for: hold a key, push the mouse the way you want, let go. Nobody reads
 * one after the first few uses — the hand learns "danger is down-left" and the labels become confirmation
 * rather than navigation. Which is why the geometry is the important part and the text is not.
 *
 * **Selection is by angle alone, never by distance from the centre.** Pushing further in a direction cannot
 * change what is chosen, only how confident the choice is, and a menu that deselected when the mouse went too
 * far would punish exactly the decisive flick it is meant to reward. Distance decides one thing: whether
 * anything is selected at all, so that letting go without moving means "no choice".
 */
public class RadialMenuNode(
    id: UiId,
    private val componentContext: ComponentContext,
) : UiNode(id) {

    public var options: List<RadialOption> = emptyList()
        set(value) {
            field = value
            invalidateMeasure()
        }

    /**
     * Where the pointer is, relative to the centre of the ring.
     *
     * Set by whoever is driving it — for a menu opened on a held key that is the accumulated mouse movement,
     * not the cursor, because the cursor does not exist while the mouse is grabbed.
     */
    public var pointer: Vec2 = Vec2.Zero
        set(value) {
            field = value
            invalidatePaint()
        }

    /** How far through its opening animation, 0 to 1. Drives the scale and the fade. */
    public var progress: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidatePaint()
        }

    private val layouts = HashMap<String, TextLayout>()

    /**
     * Which option the pointer is on, or null when it is too near the centre to mean anything.
     *
     * The whole of the menu's logic, and deliberately a pure function of [pointer] and [options] so that a
     * test can drive it without a frame.
     */
    public val selected: RadialOption?
        get() {
            if (options.isEmpty()) return null
            val distance = hypot(pointer.x, pointer.y)
            // A menu that selected something at dead centre would choose for somebody who opened it by
            // accident and let go without moving. Releasing without pushing has to mean "never mind".
            if (distance < DEAD_ZONE) return null
            return options[sectorAt(angleOf(pointer), options.size)]
        }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        layouts.clear()
        for (option in options) {
            layouts[option.id] = context.textMeasurer.measure(
                option.label,
                TextStyle(scale = LABEL_SCALE, bold = true, shadow = true),
            )
        }
        return Size(
            if (constraints.hasBoundedWidth) constraints.maxWidth else 0f,
            if (constraints.hasBoundedHeight) constraints.maxHeight else 0f,
        )
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        if (options.isEmpty() || progress <= 0f) return

        val centre = Vec2(bounds.x + bounds.width / 2f, bounds.y + bounds.height / 2f)
        val radius = minOf(bounds.width, bounds.height) * RADIUS_FRACTION * eased(progress)
        val chosen = selected

        renderer.pushOpacity(progress)
        try {
            // The backdrop dims the world so the ring reads against whatever is behind it — which, in a
            // game, is anything at all.
            renderer.fillRect(bounds, Color.Black.withAlpha(BACKDROP_ALPHA))

            for ((index, option) in options.withIndex()) {
                val angle = sectorCentre(index, options.size)
                val isChosen = option.id == chosen?.id
                val push = if (isChosen) CHOSEN_PUSH else 1f
                val at = Vec2(
                    centre.x + cos(angle) * radius * push,
                    centre.y + sin(angle) * radius * push,
                )
                paintOption(renderer, option, at, isChosen)
            }

            // Drawn last so nothing covers it, and only once something is selected — a line pointing at
            // nothing would suggest the menu had chosen for you.
            if (chosen != null) paintPointer(renderer, centre, radius)
        } finally {
            renderer.popOpacity()
        }
    }

    private fun paintOption(renderer: UiRenderer, option: RadialOption, at: Vec2, isChosen: Boolean) {
        val size = if (isChosen) CHIP_SIZE * CHOSEN_SCALE else CHIP_SIZE
        val colour = Color(OPAQUE or option.colour)
        val box = Rect(at.x - size / 2f, at.y - size / 2f, size, size)

        renderer.roundedRect(box, Dp(size / 2f), colour.withAlpha(if (isChosen) 1f else UNCHOSEN_ALPHA))
        if (isChosen) {
            renderer.border(box, Dp(size / 2f), Dp(RING_WIDTH), Color.White)
        }

        val layout = layouts[option.id] ?: return
        // Under the chip rather than inside it: the labels are different lengths and centring them in a
        // circle would make the ring look lopsided at whichever one is longest.
        renderer.text(
            layout,
            Vec2(at.x - layout.size.width / 2f, at.y + size / 2f + LABEL_GAP),
            if (isChosen) Color.White else componentContext.theme.tokens.colors.textSecondary,
        )
    }

    /** A line from the centre towards the choice, so the gesture is visible as well as its result. */
    private fun paintPointer(renderer: UiRenderer, centre: Vec2, radius: Float) {
        val angle = angleOf(pointer)
        val length = radius * POINTER_LENGTH
        val steps = POINTER_STEPS
        for (step in 0 until steps) {
            val along = DEAD_ZONE + (length - DEAD_ZONE) * (step / (steps - 1f))
            val dot = Vec2(centre.x + cos(angle) * along, centre.y + sin(angle) * along)
            renderer.fillRect(
                Rect(dot.x - POINTER_DOT / 2f, dot.y - POINTER_DOT / 2f, POINTER_DOT, POINTER_DOT),
                Color.White.withAlpha(POINTER_ALPHA),
            )
        }
    }

    public companion object {

        /**
         * The angle of a vector, in screen coordinates.
         *
         * Plain `atan2`, so zero points *right* and angles increase clockwise — positive Y is downwards on a
         * screen. The quarter turn that puts the first option at the top lives in [sectorCentre] and
         * [sectorAt] instead, in one place, rather than being baked in here where it would have to be undone
         * every time somebody wanted a real angle.
         */
        internal fun angleOf(vector: Vec2): Float = atan2(vector.y, vector.x)

        /** Where the middle of a sector points. Index zero is straight up. */
        internal fun sectorCentre(index: Int, count: Int): Float =
            (-HALF_TURN / 2f) + TAU * index / count

        /**
         * Which sector an angle falls in.
         *
         * Half a sector of offset before the division, so a sector is centred on its own direction rather
         * than starting at it — pushing exactly at an option has to select that option, not sit on the
         * boundary between it and its neighbour.
         */
        internal fun sectorAt(angle: Float, count: Int): Int {
            if (count <= 0) return 0
            val sector = TAU / count
            val shifted = angle + HALF_TURN / 2f + sector / 2f
            val normalised = ((shifted % TAU) + TAU) % TAU
            return (normalised / sector).toInt().coerceIn(0, count - 1)
        }

        private fun eased(t: Float): Float = 1f - (1f - t) * (1f - t)

        private const val TAU = (2.0 * PI).toFloat()
        private const val HALF_TURN = PI.toFloat()

        /**
         * How far the pointer must move before anything is selected, in logical units.
         *
         * Small enough that a deliberate flick always registers, large enough that opening the menu and
         * letting go without meaning to picks nothing.
         */
        public const val DEAD_ZONE: Float = 14f

        private const val RADIUS_FRACTION = 0.22f
        private const val CHIP_SIZE = 26f
        private const val CHOSEN_SCALE = 1.35f
        private const val CHOSEN_PUSH = 1.08f
        private const val UNCHOSEN_ALPHA = 0.55f
        private const val RING_WIDTH = 2f
        private const val LABEL_SCALE = 1f
        private const val LABEL_GAP = 4f
        private const val BACKDROP_ALPHA = 0.35f

        private const val POINTER_LENGTH = 0.72f
        private const val POINTER_STEPS = 8
        private const val POINTER_DOT = 2.5f
        private const val POINTER_ALPHA = 0.8f

        private const val OPAQUE = 0xFF000000.toInt()
    }
}
