package dev.th7bo.sidequest.ui.components.hud

import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.hud.editor.GuideAxis
import dev.th7bo.sidequest.ui.core.hud.editor.GuideKind
import dev.th7bo.sidequest.ui.core.hud.editor.HudEditorSession
import dev.th7bo.sidequest.ui.core.hud.editor.HudHandle
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.input.InputEvent
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.KeyDownEvent
import dev.th7bo.sidequest.ui.input.MouseButton
import dev.th7bo.sidequest.ui.input.PointerDownEvent
import dev.th7bo.sidequest.ui.input.PointerDragEvent
import dev.th7bo.sidequest.ui.input.PointerUpEvent
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.Corners
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.geometry.dp

/**
 * The editor's chrome: safe areas, snap guides, selection outlines and handles, plus the
 * pointer and keyboard routing that drives [HudEditorSession].
 *
 * Draws nothing that belongs to a HUD — the elements underneath render themselves. This
 * node only adds what is true of the *editing session*, which is why it can be dropped
 * on top of the live layer rather than replacing it with a preview.
 */
public class HudEditorOverlayNode(
    id: UiId,
    private val session: HudEditorSession,
    private val componentContext: ComponentContext,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    /** What a press started, so the drag handler knows which gesture to continue. */
    private enum class Mode { NONE, MOVE, SCALE, RESIZE, MARQUEE }

    private var mode = Mode.NONE
    private var marqueeOrigin: Vec2 = Vec2.Zero
    private var marquee: Rect? = null

    /** Set while a modal popup owns input, so the editor ignores stray drags. */
    public var isInputEnabled: Boolean = true

    init {
        interactive = true
        focusable = true
        capturesPointer = true

        session.selection.observe(scope) { invalidatePaint() }
        session.guides.observe(scope) { invalidatePaint() }
        session.showSafeAreas.observe(scope) { invalidatePaint() }
    }

    // The overlay fills whatever it is given: it is a lens over the whole screen.
    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size = Size(
        constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: 0f,
        constraints.maxHeight.takeIf { constraints.hasBoundedHeight } ?: 0f,
    )

    // -- input --------------------------------------------------------------

    override fun onInputEvent(event: InputEvent) {
        if (!isInputEnabled) return
        when (event) {
            is PointerDownEvent -> onPress(event)
            is PointerDragEvent -> onDrag(event)
            is PointerUpEvent -> onRelease(event)
            is KeyDownEvent -> onKey(event)
            else -> Unit
        }
    }

    private fun onPress(event: PointerDownEvent) {
        if (event.button != MouseButton.LEFT) return
        val point = event.rootPosition

        // Handles first: they overhang the element's edge, so a press near a corner has
        // to mean "resize" rather than "start dragging from just inside the corner".
        val handle = handleAt(point)
        if (handle != null) {
            val element = session.selectedElements.firstOrNull()
            val started = when {
                element == null -> false
                // Alt is the modifier for resizing where a HUD supports both, matching
                // the convention that the plain gesture is the more common one.
                event.modifiers.alt && element.definition.resizeMode.supportsResize ->
                    session.beginResize(point, handle).also { if (it) mode = Mode.RESIZE }
                element.definition.resizeMode.supportsScale ->
                    session.beginScale(point, handle).also { if (it) mode = Mode.SCALE }
                element.definition.resizeMode.supportsResize ->
                    session.beginResize(point, handle).also { if (it) mode = Mode.RESIZE }
                else -> false
            }
            if (started) {
                event.consume()
                return
            }
        }

        val hit = session.elementAt(point)
        if (hit == null) {
            if (!event.modifiers.shift) session.clearSelection()
            mode = Mode.MARQUEE
            marqueeOrigin = point
            marquee = Rect.of(point, Size.Zero)
            event.consume()
            invalidatePaint()
            return
        }

        // Pressing an already-selected element keeps the whole selection, so a
        // multi-selection can be dragged without shift-clicking every member again.
        if (!session.isSelected(hit.instance.instanceId)) {
            session.select(hit.instance.instanceId, additive = event.modifiers.shift)
        } else if (event.modifiers.shift) {
            session.select(hit.instance.instanceId, additive = true)
        }

        if (session.beginDrag(point)) mode = Mode.MOVE
        event.consume()
    }

    private fun onDrag(event: PointerDragEvent) {
        val point = event.rootPosition
        when (mode) {
            // Holding Alt suspends snapping, for placement the grid would fight.
            Mode.MOVE -> session.updateDrag(point, snap = !event.modifiers.alt)
            Mode.SCALE -> session.updateScale(point)
            Mode.RESIZE -> session.updateResize(point)
            Mode.MARQUEE -> {
                marquee = Rect.of(marqueeOrigin, Size.Zero).union(Rect.of(point, Size.Zero))
                session.selectWithin(marquee!!, additive = event.modifiers.shift)
                invalidatePaint()
            }
            Mode.NONE -> return
        }
        event.consume()
    }

    private fun onRelease(event: PointerUpEvent) {
        if (mode == Mode.NONE) return
        session.endGesture()
        mode = Mode.NONE
        marquee = null
        event.consume()
        invalidatePaint()
    }

    private fun onKey(event: KeyDownEvent) {
        val step = if (event.modifiers.shift) COARSE_NUDGE else FINE_NUDGE
        when (event.key) {
            Key.ARROW_LEFT -> session.nudge(Vec2(-step, 0f))
            Key.ARROW_RIGHT -> session.nudge(Vec2(step, 0f))
            Key.ARROW_UP -> session.nudge(Vec2(0f, -step))
            Key.ARROW_DOWN -> session.nudge(Vec2(0f, step))
            Key.ESCAPE -> {
                // Escape cancels a gesture if one is running; only otherwise does it
                // reach the screen and close the editor.
                if (mode != Mode.NONE) {
                    session.cancelGesture()
                    mode = Mode.NONE
                } else if (session.selection.peek().isNotEmpty()) {
                    session.clearSelection()
                } else {
                    return
                }
            }
            Key.A -> if (event.modifiers.control) session.selectAll() else return
            Key.Z -> if (event.modifiers.control) {
                if (event.modifiers.shift) session.undo.redo() else session.undo.undo()
            } else {
                return
            }
            Key.Y -> if (event.modifiers.control) session.undo.redo() else return
            Key.L -> session.toggleLocked()
            else -> return
        }
        event.consume()
    }

    private fun handleAt(point: Vec2): HudHandle? {
        val bounds = session.selectionBounds() ?: return null
        return HudHandle.entries.firstOrNull { handle ->
            handleRect(bounds, handle).contains(point)
        }
    }

    private fun handleRect(bounds: Rect, handle: HudHandle): Rect = Rect(
        bounds.x + bounds.width * handle.horizontalFactorFor() - HANDLE / 2f,
        bounds.y + bounds.height * handle.verticalFactorFor() - HANDLE / 2f,
        HANDLE,
        HANDLE,
    )

    // -- painting -----------------------------------------------------------

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors

        if (session.showSafeAreas.peek()) {
            for (area in session.safeAreas()) {
                renderer.roundedRect(area.bounds, Corners.all(tokens.radii.small), SAFE_AREA_FILL)
                renderer.border(
                    area.bounds,
                    Corners.all(tokens.radii.small),
                    tokens.metrics.borderWidth,
                    SAFE_AREA_LINE,
                )
                context.diagnostics.drawCalls += 2
            }
        }

        // Every unselected element gets a faint outline, so the editor shows what is
        // there to be moved rather than only what is already selected.
        for (element in session.layer.ordered) {
            if (session.isSelected(element.instance.instanceId)) continue
            val rect = session.screenBounds(element)
            renderer.border(rect, Corners.all(tokens.radii.medium), tokens.metrics.borderWidth, IDLE_OUTLINE)
            context.diagnostics.drawCalls++
        }

        for (guide in session.guides.peek()) {
            paintGuide(renderer, guide, bounds, context)
        }

        for (element in session.selectedElements) {
            val rect = session.screenBounds(element)
            val locked = element.placement.peek().locked
            renderer.border(
                rect,
                Corners.all(tokens.radii.medium),
                tokens.metrics.focusRingWidth,
                if (locked) LOCKED_OUTLINE else palette.accent,
            )
            context.diagnostics.drawCalls++
        }

        // Handles come from the selection as a whole, so a multi-selection scales as one
        // group rather than showing four sets of corners.
        session.selectionBounds()?.let { selection ->
            val anyLocked = session.selectedElements.any { it.placement.peek().locked }
            if (!anyLocked) {
                for (handle in HudHandle.entries) {
                    val rect = handleRect(selection, handle)
                    renderer.roundedRect(rect, Corners.all(tokens.radii.small), palette.accent)
                    renderer.border(
                        rect,
                        Corners.all(tokens.radii.small),
                        tokens.metrics.borderWidth,
                        palette.panelBackground,
                    )
                    context.diagnostics.drawCalls += 2
                }
            }
        }

        marquee?.let { region ->
            renderer.roundedRect(region, Corners.all(tokens.radii.small), palette.accent.withAlpha(MARQUEE_FILL))
            renderer.border(region, Corners.all(tokens.radii.small), tokens.metrics.borderWidth, palette.accent)
            context.diagnostics.drawCalls += 2
        }
    }

    private fun paintGuide(
        renderer: UiRenderer,
        guide: dev.th7bo.sidequest.ui.core.hud.editor.SnapGuide,
        bounds: Rect,
        context: RenderContext,
    ) {
        val color = when (guide.kind) {
            GuideKind.CENTER -> GUIDE_CENTER
            GuideKind.EDGE -> GUIDE_EDGE
            GuideKind.ELEMENT -> GUIDE_ELEMENT
            GuideKind.SAFE_AREA -> SAFE_AREA_LINE
        }
        val line = when (guide.axis) {
            GuideAxis.VERTICAL -> Rect(guide.position - GUIDE_WIDTH / 2f, 0f, GUIDE_WIDTH, bounds.height)
            GuideAxis.HORIZONTAL -> Rect(0f, guide.position - GUIDE_WIDTH / 2f, bounds.width, GUIDE_WIDTH)
        }
        renderer.fillRect(line, color)
        context.diagnostics.drawCalls++
    }

    private fun HudHandle.horizontalFactorFor(): Float = when (this) {
        HudHandle.TOP_LEFT, HudHandle.BOTTOM_LEFT -> 0f
        HudHandle.TOP_RIGHT, HudHandle.BOTTOM_RIGHT -> 1f
    }

    private fun HudHandle.verticalFactorFor(): Float = when (this) {
        HudHandle.TOP_LEFT, HudHandle.TOP_RIGHT -> 0f
        HudHandle.BOTTOM_LEFT, HudHandle.BOTTOM_RIGHT -> 1f
    }

    public companion object {
        /** Handle hit area and drawn size, in GUI units. */
        public const val HANDLE: Float = 6f

        private const val FINE_NUDGE = 1f
        private const val COARSE_NUDGE = 10f
        private const val GUIDE_WIDTH = 1f
        private const val MARQUEE_FILL = 0.15f

        private val SAFE_AREA_FILL = Color.parse("#14FF5555")
        private val SAFE_AREA_LINE = Color.parse("#66FF5555")
        private val IDLE_OUTLINE = Color.parse("#33FFFFFF")
        private val LOCKED_OUTLINE = Color.parse("#AAFFAA33")
        private val GUIDE_CENTER = Color.parse("#CC00E5FF")
        private val GUIDE_EDGE = Color.parse("#CCFFFFFF")
        private val GUIDE_ELEMENT = Color.parse("#CC55FF99")
    }
}
