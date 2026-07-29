package dev.th7bo.sidequest.ui.core.hud.editor

import dev.th7bo.sidequest.ui.core.hud.HudElementNode
import dev.th7bo.sidequest.ui.core.hud.HudLayerNode
import dev.th7bo.sidequest.ui.core.undo.UndoStack
import dev.th7bo.sidequest.ui.core.undo.UndoableEdit
import dev.th7bo.sidequest.ui.geometry.Anchor
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.hud.HudPlacement
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.mutableStateOf

/** Which handle a resize or scale gesture grabbed. */
public enum class HudHandle(
    internal val horizontalFactor: Float,
    internal val verticalFactor: Float,
) {
    TOP_LEFT(0f, 0f),
    TOP_RIGHT(1f, 0f),
    BOTTOM_LEFT(0f, 1f),
    BOTTOM_RIGHT(1f, 1f),
    ;

    /** The corner diagonally opposite, which stays put during the gesture. */
    internal val opposite: HudHandle
        get() = when (this) {
            TOP_LEFT -> BOTTOM_RIGHT
            TOP_RIGHT -> BOTTOM_LEFT
            BOTTOM_LEFT -> TOP_RIGHT
            BOTTOM_RIGHT -> TOP_LEFT
        }
}

/**
 * A reversible change to a set of placements.
 *
 * One edit covers every element a gesture touched, so undoing a multi-selection drag
 * puts all of them back at once rather than one per press of Ctrl+Z. That is the whole
 * reason this is keyed by a map instead of holding a single element.
 */
public class HudPlacementEdit(
    private val layer: HudLayerNode,
    private val before: Map<UiId, HudPlacement>,
    private val after: Map<UiId, HudPlacement>,
    override val label: String,
    /**
     * Gestures merge while they are still running, so a drag is one undo entry rather
     * than one per mouse move. Null disables merging.
     */
    private val mergeKey: Any? = null,
) : UndoableEdit {

    override fun undo(): Unit = apply(before)

    override fun redo(): Unit = apply(after)

    private fun apply(placements: Map<UiId, HudPlacement>) {
        for ((id, placement) in placements) layer[id]?.setPlacement(placement)
    }

    override fun mergeWith(next: UndoableEdit): UndoableEdit? {
        if (mergeKey == null) return null
        if (next !is HudPlacementEdit || next.mergeKey != mergeKey) return null
        // Keep this edit's "before" and take the newer "after": the gesture as a whole
        // is what should be reversible, not each frame of it.
        return HudPlacementEdit(layer, before, next.after, label, mergeKey)
    }

    override fun toString(): String = "HudPlacementEdit($label, ${after.size} elements)"
}

/**
 * An editing session over a live [HudLayerNode].
 *
 * Deliberately separate from any rendering: the session is pure state and arithmetic, so
 * every acceptance criterion — cursor alignment under scale, reversible multi-selection,
 * placement surviving a resolution change — is assertable without a game or a renderer.
 * The editor screen is a view over this, not the other way round.
 */
public class HudEditorSession(
    public val layer: HudLayerNode,
    private val screenSizeProvider: () -> Size,
    public val undo: UndoStack = UndoStack(),
    public val snapping: SnapEngine = SnapEngine(),
) {

    private val selectionState: MutableUiState<Set<UiId>> = mutableStateOf(emptySet(), "hud.editor.selection")
    private val guideState: MutableUiState<List<SnapGuide>> = mutableStateOf(emptyList(), "hud.editor.guides")
    private val safeAreaState: MutableUiState<Boolean> = mutableStateOf(true, "hud.editor.safeAreas")

    /** Instance ids of the currently selected elements. */
    public val selection: UiState<Set<UiId>> get() = selectionState

    /** Guides for the gesture in flight. Empty when nothing is snapping. */
    public val guides: UiState<List<SnapGuide>> get() = guideState

    /** Whether safe areas are shown and snapped to. */
    public val showSafeAreas: UiState<Boolean> get() = safeAreaState

    public val screenSize: Size get() = screenSizeProvider()

    public fun safeAreas(): List<SafeArea> =
        if (safeAreaState.peek()) SafeArea.vanilla(screenSize) else emptyList()

    public fun setShowSafeAreas(show: Boolean) {
        safeAreaState.value = show
    }

    // -- selection ----------------------------------------------------------

    public val selectedElements: List<HudElementNode>
        get() = selectionState.peek().mapNotNull { layer[it] }

    /** The selected elements a gesture may actually move. */
    private val movableSelection: List<HudElementNode>
        get() = selectedElements.filterNot { it.placement.peek().locked }

    public fun select(instanceId: UiId, additive: Boolean = false) {
        val current = selectionState.peek()
        selectionState.value = when {
            !additive -> setOf(instanceId)
            instanceId in current -> current - instanceId
            else -> current + instanceId
        }
        syncSelectionFlags()
    }

    public fun selectAll() {
        selectionState.value = layer.ordered.map { it.instance.instanceId }.toSet()
        syncSelectionFlags()
    }

    public fun clearSelection() {
        selectionState.value = emptySet()
        syncSelectionFlags()
    }

    /**
     * Mirrors the selection onto the elements themselves.
     *
     * Kept on the elements rather than read back from the session, because content that
     * wants to look different while selected should observe one state rather than hold a
     * reference to the whole editor.
     */
    private fun syncSelectionFlags() {
        val selected = selectionState.peek()
        for (element in layer.ordered) {
            element.setSelected(element.instance.instanceId in selected)
        }
    }

    /**
     * Marks every element as being edited, so content can swap in preview data.
     *
     * Called when the editor opens and again with false when it closes. Not inferred from
     * "a session exists": a session is also constructed by tests and by tooling that is
     * not showing anything to anybody.
     */
    public fun setEditing(editing: Boolean) {
        for (element in layer.ordered) element.setEditing(editing)
        if (!editing) clearSelection()
    }

    public fun isSelected(instanceId: UiId): Boolean = instanceId in selectionState.peek()

    /** The topmost element under [point], respecting z-order. */
    public fun elementAt(point: Vec2): HudElementNode? {
        val screen = screenSize
        return layer.ordered.lastOrNull { screenBounds(it, screen).contains(point) }
    }

    /** Selects whatever is under [point], or clears the selection on empty space. */
    public fun selectAt(point: Vec2, additive: Boolean = false): HudElementNode? {
        val hit = elementAt(point)
        if (hit == null) {
            if (!additive) clearSelection()
            return null
        }
        select(hit.instance.instanceId, additive)
        return hit
    }

    /** Every element inside [region], for a marquee selection. */
    public fun selectWithin(region: Rect, additive: Boolean = false) {
        val screen = screenSize
        val inside = layer.ordered
            .filter { region.contains(screenBounds(it, screen).center) }
            .map { it.instance.instanceId }
            .toSet()
        selectionState.value = if (additive) selectionState.peek() + inside else inside
        syncSelectionFlags()
    }

    // -- gestures -----------------------------------------------------------

    private class Gesture(
        val kind: String,
        val origin: Vec2,
        val before: Map<UiId, HudPlacement>,
        /** Per element, the pointer's offset from that element's top-left at grab time. */
        val grabOffsets: Map<UiId, Vec2>,
        val startSizes: Map<UiId, Size>,
        val handle: HudHandle?,
        val key: Any,
    )

    private var gesture: Gesture? = null

    public val isGesturing: Boolean get() = gesture != null

    /**
     * Starts dragging the selection.
     *
     * The pointer's offset within each element is captured now and preserved for the
     * whole drag. That is what keeps the cursor on the same spot of the element: the
     * arithmetic is entirely in screen space, so an element scaled 2x moves exactly as
     * far as the pointer does rather than twice as far.
     *
     * @return false if nothing movable is selected.
     */
    public fun beginDrag(pointer: Vec2): Boolean {
        val targets = movableSelection
        if (targets.isEmpty()) return false
        val screen = screenSize
        gesture = Gesture(
            kind = "Move",
            origin = pointer,
            before = targets.associate { it.instance.instanceId to it.placement.peek() },
            grabOffsets = targets.associate {
                it.instance.instanceId to (pointer - screenBounds(it, screen).position)
            },
            startSizes = targets.associate { it.instance.instanceId to it.scaledSize },
            handle = null,
            key = Any(),
        )
        undo.beginGesture("Move HUD")
        return true
    }

    /** Continues the drag. Snapping applies to the primary element; the rest follow it. */
    public fun updateDrag(pointer: Vec2): Unit = updateDrag(pointer, snap = true)

    public fun updateDrag(pointer: Vec2, snap: Boolean) {
        val active = gesture ?: return
        val screen = screenSize
        val targets = active.before.keys.mapNotNull { layer[it] }
        if (targets.isEmpty()) return

        // Snapping is resolved once, against the primary element, and the resulting
        // correction is applied to everything. Snapping each element independently would
        // tear a multi-selection apart the first time two of them snapped to different
        // lines.
        val primary = targets.first()
        val primaryId = primary.instance.instanceId
        val grab = active.grabOffsets[primaryId] ?: Vec2.Zero
        val freePosition = pointer - grab
        val primarySize = active.startSizes[primaryId] ?: primary.scaledSize

        val others = layer.ordered
            .filter { it.instance.instanceId !in active.before }
            .map { screenBounds(it, screen) }

        val result = if (snap) {
            snapping.snap(Rect.of(freePosition, primarySize), screen, others, safeAreas())
        } else {
            SnapResult(freePosition, emptyList())
        }
        guideState.value = result.guides

        val correction = result.position - freePosition
        for (element in targets) {
            val id = element.instance.instanceId
            val offset = active.grabOffsets[id] ?: continue
            element.moveTo(pointer - offset + correction, screen)
        }
        pushGesture(active, "Move HUD")
    }

    /**
     * Scales the selection from a corner handle.
     *
     * The opposite corner is held fixed, so the element grows away from the corner being
     * dragged rather than jumping. Scale is derived from the width ratio and clamped to
     * the definition's range.
     */
    public fun beginScale(pointer: Vec2, handle: HudHandle): Boolean {
        val targets = movableSelection.filter { it.definition.resizeMode.supportsScale }
        if (targets.isEmpty()) return false
        val screen = screenSize
        gesture = Gesture(
            kind = "Scale",
            origin = pointer,
            before = targets.associate { it.instance.instanceId to it.placement.peek() },
            grabOffsets = targets.associate {
                it.instance.instanceId to (pointer - screenBounds(it, screen).position)
            },
            startSizes = targets.associate { it.instance.instanceId to it.scaledSize },
            handle = handle,
            key = Any(),
        )
        undo.beginGesture("Scale HUD")
        return true
    }

    public fun updateScale(pointer: Vec2) {
        val active = gesture ?: return
        val handle = active.handle ?: return
        val screen = screenSize

        for ((id, startPlacement) in active.before) {
            val element = layer[id] ?: continue
            val startSize = active.startSizes[id] ?: continue
            if (startSize.width <= 0f) continue

            val startBounds = Rect.of(startPlacement.resolve(startSize, screen), startSize)
            val fixed = cornerOf(startBounds, handle.opposite)

            // Width ratio alone: the element keeps its aspect ratio, so driving scale
            // from one axis avoids a diagonal drag producing two different answers.
            val startWidth = (cornerOf(startBounds, handle).x - fixed.x)
            if (startWidth == 0f) continue
            val ratio = (pointer.x - fixed.x) / startWidth
            if (ratio <= 0f) continue

            element.rescale(startPlacement.scale * ratio)

            // Re-pin the fixed corner: rescaling changes the element's extent, and
            // without this the corner the user is not dragging would drift.
            val newSize = element.scaledSize
            val topLeft = Vec2(
                if (handle.opposite.horizontalFactor == 0f) fixed.x else fixed.x - newSize.width,
                if (handle.opposite.verticalFactor == 0f) fixed.y else fixed.y - newSize.height,
            )
            element.moveTo(topLeft, screen)
        }
        pushGesture(active, "Scale HUD")
    }

    /** Resizes the selection, changing the space the content lays out in. */
    public fun beginResize(pointer: Vec2, handle: HudHandle): Boolean {
        val targets = movableSelection.filter { it.definition.resizeMode.supportsResize }
        if (targets.isEmpty()) return false
        val screen = screenSize
        gesture = Gesture(
            kind = "Resize",
            origin = pointer,
            before = targets.associate { it.instance.instanceId to it.placement.peek() },
            grabOffsets = targets.associate {
                it.instance.instanceId to (pointer - screenBounds(it, screen).position)
            },
            startSizes = targets.associate { it.instance.instanceId to it.scaledSize },
            handle = handle,
            key = Any(),
        )
        undo.beginGesture("Resize HUD")
        return true
    }

    public fun updateResize(pointer: Vec2) {
        val active = gesture ?: return
        val handle = active.handle ?: return
        val screen = screenSize

        for ((id, startPlacement) in active.before) {
            val element = layer[id] ?: continue
            val startSize = active.startSizes[id] ?: continue
            val startBounds = Rect.of(startPlacement.resolve(startSize, screen), startSize)
            val fixed = cornerOf(startBounds, handle.opposite)

            val width = kotlin.math.abs(pointer.x - fixed.x)
            val height = kotlin.math.abs(pointer.y - fixed.y)
            element.resize(Size(width, height))

            val newSize = element.scaledSize
            val topLeft = Vec2(
                if (handle.opposite.horizontalFactor == 0f) fixed.x else fixed.x - newSize.width,
                if (handle.opposite.verticalFactor == 0f) fixed.y else fixed.y - newSize.height,
            )
            element.moveTo(topLeft, screen)
        }
        pushGesture(active, "Resize HUD")
    }

    /**
     * Ends the gesture in flight.
     *
     * Closing the undo gesture is what turns a drag's per-frame edits into the single
     * entry the user expects; leaving it open would merge the *next* gesture into this
     * one as well.
     */
    public fun endGesture() {
        if (gesture == null) return
        gesture = null
        guideState.value = emptyList()
        undo.endGesture()
    }

    /** Abandons the gesture and restores the placements it started from. */
    public fun cancelGesture() {
        val active = gesture ?: return
        for ((id, placement) in active.before) layer[id]?.setPlacement(placement)
        gesture = null
        guideState.value = emptyList()
        // The placements are already back where they started, so the entry the gesture
        // accumulated describes a change that no longer exists.
        undo.abortGesture()
    }

    private fun pushGesture(active: Gesture, label: String) {
        val after = active.before.keys.mapNotNull { id ->
            layer[id]?.let { id to it.placement.peek() }
        }.toMap()
        if (after == active.before) return
        undo.push(HudPlacementEdit(layer, active.before, after, label, active.key))
    }

    // -- discrete edits -----------------------------------------------------

    /** Moves the selection by a fixed amount, for arrow-key nudging. */
    public fun nudge(delta: Vec2) {
        edit("Nudge HUD") { it.copy(offset = it.offset + delta) }
    }

    public fun setAnchor(anchor: Anchor) {
        val screen = screenSize
        editElements("Change anchor") { element ->
            element.placement.peek().withAnchor(anchor, element.scaledSize, screen)
        }
    }

    public fun setScale(scale: Float) {
        editElements("Change scale") { element ->
            val range = element.definition.scaleRange
            element.placement.peek().withScale(scale, range)
        }
    }

    public fun setOpacity(opacity: Float) {
        edit("Change opacity") { it.copy(opacity = opacity.coerceIn(0f, 1f)) }
    }

    public fun setLocked(locked: Boolean) {
        // Locking is itself reversible, but it applies to locked elements too — being
        // unable to unlock something would be a trap.
        editAll("${if (locked) "Lock" else "Unlock"} HUD") { it.copy(locked = locked) }
    }

    public fun toggleLocked() {
        val allLocked = selectedElements.isNotEmpty() && selectedElements.all { it.placement.peek().locked }
        setLocked(!allLocked)
    }

    public fun bringToFront() {
        val top = layer.ordered.lastOrNull()?.placement?.peek()?.zIndex ?: 0
        var next = top + 1
        editElements("Bring to front") { it.placement.peek().copy(zIndex = next++) }
    }

    public fun sendToBack() {
        val bottom = layer.ordered.firstOrNull()?.placement?.peek()?.zIndex ?: 0
        var next = bottom - 1
        editElements("Send to back") { it.placement.peek().copy(zIndex = next--) }
    }

    public fun resetSelection() {
        editElements("Reset HUD") { it.definition.defaultPlacement() }
    }

    private fun edit(label: String, transform: (HudPlacement) -> HudPlacement) {
        editElements(label) { transform(it.placement.peek()) }
    }

    private fun editElements(label: String, transform: (HudElementNode) -> HudPlacement) {
        applyEdit(label, movableSelection, transform)
    }

    private fun editAll(label: String, transform: (HudPlacement) -> HudPlacement) {
        applyEdit(label, selectedElements) { transform(it.placement.peek()) }
    }

    private fun applyEdit(
        label: String,
        targets: List<HudElementNode>,
        transform: (HudElementNode) -> HudPlacement,
    ) {
        if (targets.isEmpty()) return
        val before = targets.associate { it.instance.instanceId to it.placement.peek() }
        for (element in targets) element.setPlacement(transform(element))
        val after = targets.associate { it.instance.instanceId to it.placement.peek() }
        if (before == after) return
        undo.push(HudPlacementEdit(layer, before, after, label))
    }

    // -- geometry -----------------------------------------------------------

    /** The on-screen rectangle of [element], accounting for scale. */
    public fun screenBounds(element: HudElementNode, screen: Size = screenSize): Rect {
        val size = element.scaledSize
        return Rect.of(element.placement.peek().resolve(size, screen), size)
    }

    /** The rectangle enclosing the whole selection, or null when nothing is selected. */
    public fun selectionBounds(): Rect? {
        val bounds = selectedElements.map { screenBounds(it) }
        if (bounds.isEmpty()) return null
        return bounds.reduce { a, b -> a.union(b) }
    }

    private fun cornerOf(bounds: Rect, handle: HudHandle): Vec2 = Vec2(
        bounds.x + bounds.width * handle.horizontalFactor,
        bounds.y + bounds.height * handle.verticalFactor,
    )
}
