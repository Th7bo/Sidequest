package dev.th7bo.sidequest.ui.core.undo

import dev.th7bo.sidequest.ui.config.Setting
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.UiThread
import dev.th7bo.sidequest.ui.state.mutableStateOf

/**
 * One reversible change.
 *
 * Edits describe *what changed*, not how it was rendered, so the same edit type covers
 * a value written from a control, from a search result and from an import.
 */
public interface UndoableEdit {

    /** Shown in the undo menu, e.g. `Change Notification Duration`. */
    public val label: String

    public fun undo()

    public fun redo()

    /**
     * Folds [next] into this edit, or returns null if they must stay separate.
     *
     * This is what stops a drag from producing one undo entry per mouse move: the
     * gesture's edits all target the same value, so they collapse into a single entry
     * spanning the whole drag.
     */
    public fun mergeWith(next: UndoableEdit): UndoableEdit? = null
}

/** A value change on one setting. */
public class SettingEdit<T>(
    private val setting: Setting<T>,
    private val from: T,
    private val to: T,
    override val label: String = "Change ${setting.metadata.title.peek()}",
) : UndoableEdit {

    override fun undo() {
        setting.setUnchecked(from)
    }

    override fun redo() {
        setting.setUnchecked(to)
    }

    override fun mergeWith(next: UndoableEdit): UndoableEdit? {
        if (next !is SettingEdit<*> || next.setting.id != setting.id) return null
        @Suppress("UNCHECKED_CAST")
        return SettingEdit(setting, from, (next as SettingEdit<T>).to, label)
    }

    override fun toString(): String = "SettingEdit(${setting.id}: $from -> $to)"
}

/** A change to any observable value. Used for HUD placement, scroll positions and the like. */
public class StateEdit<T>(
    private val state: MutableUiState<T>,
    private val from: T,
    private val to: T,
    override val label: String,
    /** Distinguishes mergeable gestures; edits merge only when these match. */
    private val mergeKey: Any? = null,
) : UndoableEdit {

    override fun undo() {
        state.value = from
    }

    override fun redo() {
        state.value = to
    }

    override fun mergeWith(next: UndoableEdit): UndoableEdit? {
        if (next !is StateEdit<*> || mergeKey == null || next.mergeKey != mergeKey) return null
        @Suppress("UNCHECKED_CAST")
        return StateEdit(state, from, (next as StateEdit<T>).to, label, mergeKey)
    }
}

/** Several edits applied and reverted as one. */
public class CompoundEdit(
    override val label: String,
    private val edits: List<UndoableEdit>,
) : UndoableEdit {

    public val size: Int get() = edits.size

    /** Undone in reverse, so overlapping changes unwind in the order they were made. */
    override fun undo() {
        for (index in edits.indices.reversed()) edits[index].undo()
    }

    override fun redo() {
        for (edit in edits) edit.redo()
    }
}

/**
 * Undo and redo history for a configuration screen or editor.
 *
 * Also answers "are there unsaved changes?", by tracking where in the history the last
 * save happened. That single mechanism gives all three interaction modes the plan asks
 * for: immediate persistence saves after every edit, apply-and-cancel reverts to the
 * saved marker, and save-and-close moves the marker forward.
 *
 * Confined to the UI thread.
 */
public class UndoStack(
    /** Oldest entries are dropped past this many. */
    public val limit: Int = DEFAULT_LIMIT,
) {

    private val undoable = ArrayDeque<UndoableEdit>()
    private val redoable = ArrayDeque<UndoableEdit>()

    /** Net edits applied since the last [markSaved]. Negative after undoing past it. */
    private var stepsSinceSave = 0

    private val canUndoState = mutableStateOf(false, "canUndo")
    private val canRedoState = mutableStateOf(false, "canRedo")
    private val unsavedState = mutableStateOf(false, "hasUnsavedChanges")

    public val canUndo: UiState<Boolean> get() = canUndoState
    public val canRedo: UiState<Boolean> get() = canRedoState

    /** True while the history has moved away from the last saved point. */
    public val hasUnsavedChanges: UiState<Boolean> get() = unsavedState

    public val undoCount: Int get() = undoable.size
    public val redoCount: Int get() = redoable.size

    /** Label of the edit [undo] would revert. */
    public val nextUndoLabel: String? get() = undoable.lastOrNull()?.label

    /** Label of the edit [redo] would reapply. */
    public val nextRedoLabel: String? get() = redoable.lastOrNull()?.label

    /** Non-null while a gesture is open; its edits merge into one entry. */
    private var gestureLabel: String? = null
    private var gestureStarted = false

    /** Depth of open transactions. Edits are buffered rather than pushed. */
    private var transactionDepth = 0
    private var transactionLabel: String? = null
    private val transactionEdits = ArrayList<UndoableEdit>()

    /**
     * Records [edit].
     *
     * Inside a transaction the edit is buffered. During a gesture it merges into the
     * gesture's single entry. Otherwise it becomes a new entry, and the redo branch is
     * discarded — history is linear.
     */
    public fun push(edit: UndoableEdit) {
        UiThread.check()

        if (transactionDepth > 0) {
            transactionEdits.add(edit)
            return
        }

        if (gestureLabel != null) {
            pushMerging(edit)
            return
        }

        redoable.clear()
        addEntry(edit)
        stepsSinceSave++
        refresh()
    }

    /** Convenience for the common case of a setting change. */
    public fun <T> pushSettingChange(setting: Setting<T>, from: T, to: T) {
        if (from == to) return
        push(SettingEdit(setting, from, to))
    }

    private fun pushMerging(edit: UndoableEdit) {
        val top = undoable.lastOrNull()
        val merged = if (gestureStarted && top != null) top.mergeWith(edit) else null

        if (merged != null) {
            undoable.removeLast()
            undoable.addLast(merged)
        } else {
            redoable.clear()
            addEntry(edit)
            stepsSinceSave++
            gestureStarted = true
        }
        refresh()
    }

    private fun addEntry(edit: UndoableEdit) {
        undoable.addLast(edit)
        while (undoable.size > limit) {
            undoable.removeFirst()
            // The dropped edit can no longer be undone, so the saved point is
            // unreachable in that direction; clamp rather than pretend otherwise.
            if (stepsSinceSave > undoable.size) stepsSinceSave = undoable.size
        }
    }

    /**
     * Opens a gesture. Every edit pushed until [endGesture] collapses into one entry.
     *
     * ```
     * undo.beginGesture("Move HUD")
     * // ... one edit per mouse move ...
     * undo.endGesture()   // one undo entry for the whole drag
     * ```
     */
    public fun beginGesture(label: String) {
        UiThread.check()
        check(gestureLabel == null) { "A gesture ('$gestureLabel') is already open" }
        gestureLabel = label
        gestureStarted = false
    }

    public fun endGesture() {
        gestureLabel = null
        gestureStarted = false
    }

    /**
     * Ends the gesture and discards whatever entry it accumulated.
     *
     * For a gesture the user abandoned — Escape part-way through a drag — where the
     * caller has already restored the starting state. Leaving the entry in place would
     * make the next undo re-apply a change that was explicitly cancelled.
     */
    public fun abortGesture() {
        UiThread.check()
        if (gestureStarted && undoable.isNotEmpty()) {
            undoable.removeLast()
            if (stepsSinceSave > 0) stepsSinceSave--
        }
        gestureLabel = null
        gestureStarted = false
        refresh()
    }

    /** True while a gesture is collapsing edits. */
    public val isGestureActive: Boolean get() = gestureLabel != null

    /**
     * Groups everything pushed inside [block] into one [CompoundEdit].
     *
     * Used for bulk operations — reset a section, import a profile — so that undoing
     * them is one action rather than hundreds.
     */
    public fun <R> transaction(label: String, block: () -> R): R {
        UiThread.check()
        if (transactionDepth == 0) {
            transactionLabel = label
            transactionEdits.clear()
        }
        transactionDepth++
        try {
            return block()
        } finally {
            transactionDepth--
            if (transactionDepth == 0) commitTransaction()
        }
    }

    private fun commitTransaction() {
        val edits = transactionEdits.toList()
        transactionEdits.clear()
        val label = transactionLabel ?: "Bulk change"
        transactionLabel = null

        when (edits.size) {
            0 -> return
            1 -> push(edits.single())
            else -> push(CompoundEdit(label, edits))
        }
    }

    /** Reverts the most recent edit. */
    public fun undo(): Boolean {
        UiThread.check()
        val edit = undoable.removeLastOrNull() ?: return false
        edit.undo()
        redoable.addLast(edit)
        stepsSinceSave--
        refresh()
        return true
    }

    /** Reapplies the most recently undone edit. */
    public fun redo(): Boolean {
        UiThread.check()
        val edit = redoable.removeLastOrNull() ?: return false
        edit.redo()
        undoable.addLast(edit)
        stepsSinceSave++
        refresh()
        return true
    }

    /** Marks the current state as saved. [hasUnsavedChanges] becomes false. */
    public fun markSaved() {
        stepsSinceSave = 0
        refresh()
    }

    /**
     * Reverts everything back to the last saved point — the cancel half of
     * apply-and-cancel.
     *
     * @return the number of edits reverted.
     */
    public fun revertToSaved(): Int {
        UiThread.check()
        var reverted = 0
        while (stepsSinceSave > 0 && undo()) reverted++
        while (stepsSinceSave < 0 && redo()) reverted++
        return reverted
    }

    /** Drops all history without touching any value. */
    public fun clear() {
        undoable.clear()
        redoable.clear()
        stepsSinceSave = 0
        gestureLabel = null
        gestureStarted = false
        transactionDepth = 0
        transactionEdits.clear()
        refresh()
    }

    private fun refresh() {
        canUndoState.value = undoable.isNotEmpty()
        canRedoState.value = redoable.isNotEmpty()
        unsavedState.value = stepsSinceSave != 0
    }

    public companion object {
        public const val DEFAULT_LIMIT: Int = 200
    }
}
