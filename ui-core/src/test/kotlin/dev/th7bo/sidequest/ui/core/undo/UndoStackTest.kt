package dev.th7bo.sidequest.ui.core.undo

import dev.th7bo.sidequest.ui.binding.asBinding
import dev.th7bo.sidequest.ui.config.SettingMetadata
import dev.th7bo.sidequest.ui.config.ToggleSetting
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UndoStackTest {

    private fun id(path: String) = UiId.of("sidequest", path)

    private lateinit var undo: UndoStack
    private lateinit var offset: MutableUiState<Float>

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        undo = UndoStack()
        offset = mutableStateOf(0f, "offset")
    }

    @AfterEach
    fun tearDown() {
        resetReactiveGraphForTesting()
    }

    private fun toggle(path: String, initial: Boolean = false): ToggleSetting {
        val backing = mutableStateOf(initial)
        return ToggleSetting(id(path), SettingMetadata(constantState(path)), backing.asBinding(), initial)
    }

    private fun move(from: Float, to: Float, key: Any? = "drag") =
        StateEdit(offset, from, to, "Move HUD", key)

    @Test
    fun `undo and redo restore the surrounding values`() {
        val setting = toggle("flag")
        setting.setUnchecked(true)
        undo.pushSettingChange(setting, from = false, to = true)

        assertTrue(undo.canUndo.value)
        assertFalse(undo.canRedo.value)

        assertTrue(undo.undo())
        assertFalse(setting.value)
        assertTrue(undo.canRedo.value)

        assertTrue(undo.redo())
        assertTrue(setting.value)
    }

    @Test
    fun `undo and redo report failure when there is nothing to do`() {
        assertFalse(undo.undo())
        assertFalse(undo.redo())
    }

    @Test
    fun `a no-op change is not recorded`() {
        val setting = toggle("flag")
        undo.pushSettingChange(setting, from = false, to = false)

        assertEquals(0, undo.undoCount)
        assertFalse(undo.canUndo.value)
    }

    @Test
    fun `a new edit discards the redo branch`() {
        val first = toggle("first")
        val second = toggle("second")

        undo.pushSettingChange(first, false, true)
        undo.undo()
        assertTrue(undo.canRedo.value)

        undo.pushSettingChange(second, false, true)

        assertFalse(undo.canRedo.value, "history is linear")
        assertEquals(0, undo.redoCount)
    }

    @Test
    fun `labels describe what undo and redo would do`() {
        val setting = toggle("flag")
        undo.pushSettingChange(setting, false, true)

        assertEquals("Change flag", undo.nextUndoLabel)
        assertNull(undo.nextRedoLabel)

        undo.undo()
        assertEquals("Change flag", undo.nextRedoLabel)
        assertNull(undo.nextUndoLabel)
    }

    // -- gestures -----------------------------------------------------------

    @Test
    fun `a drag collapses into one undo entry`() {
        undo.beginGesture("Move HUD")
        // One edit per mouse move, as a real drag would produce.
        undo.push(move(0f, 10f))
        undo.push(move(10f, 25f))
        undo.push(move(25f, 40f))
        undo.push(move(40f, 55f))
        undo.endGesture()

        assertEquals(1, undo.undoCount, "a drag must not produce one entry per frame")

        offset.value = 55f
        undo.undo()
        assertEquals(0f, offset.value, "undoing must return to where the drag started")
    }

    @Test
    fun `redoing a collapsed drag jumps to the end position`() {
        undo.beginGesture("Move HUD")
        undo.push(move(0f, 10f))
        undo.push(move(10f, 90f))
        undo.endGesture()

        undo.undo()
        assertEquals(0f, offset.value)

        undo.redo()
        assertEquals(90f, offset.value)
    }

    @Test
    fun `two separate drags stay separate`() {
        undo.beginGesture("Move HUD")
        undo.push(move(0f, 10f))
        undo.endGesture()

        undo.beginGesture("Move HUD")
        undo.push(move(10f, 20f))
        undo.endGesture()

        assertEquals(2, undo.undoCount)
    }

    @Test
    fun `edits with different merge keys do not collapse into each other`() {
        undo.beginGesture("Move")
        undo.push(move(0f, 10f, key = "hud_a"))
        undo.push(move(10f, 20f, key = "hud_b"))
        undo.endGesture()

        assertEquals(2, undo.undoCount, "different targets must not merge")
    }

    @Test
    fun `an unkeyed edit never merges`() {
        undo.beginGesture("Move")
        undo.push(move(0f, 10f, key = null))
        undo.push(move(10f, 20f, key = null))
        undo.endGesture()

        assertEquals(2, undo.undoCount)
    }

    @Test
    fun `opening a gesture twice is rejected`() {
        undo.beginGesture("first")
        assertThrows(IllegalStateException::class.java) { undo.beginGesture("second") }
    }

    @Test
    fun `edits after a gesture ends are separate again`() {
        undo.beginGesture("Move HUD")
        undo.push(move(0f, 10f))
        undo.endGesture()
        assertFalse(undo.isGestureActive)

        undo.push(move(10f, 20f))
        assertEquals(2, undo.undoCount)
    }

    // -- transactions -------------------------------------------------------

    @Test
    fun `a bulk change is one undo entry`() {
        val settings = List(5) { toggle("flag_$it") }

        undo.transaction("Reset Section") {
            for (setting in settings) {
                setting.setUnchecked(true)
                undo.pushSettingChange(setting, false, true)
            }
        }

        assertEquals(1, undo.undoCount)
        assertEquals("Reset Section", undo.nextUndoLabel)

        undo.undo()
        assertTrue(settings.none { it.value }, "the whole bulk change reverts together")

        undo.redo()
        assertTrue(settings.all { it.value })
    }

    @Test
    fun `a transaction with one edit does not wrap it needlessly`() {
        val setting = toggle("flag")
        undo.transaction("Bulk") { undo.pushSettingChange(setting, false, true) }

        assertEquals("Change flag", undo.nextUndoLabel, "a single edit keeps its own label")
    }

    @Test
    fun `an empty transaction records nothing`() {
        undo.transaction("Nothing") { }
        assertEquals(0, undo.undoCount)
    }

    @Test
    fun `nested transactions commit once, at the outermost exit`() {
        val settings = List(4) { toggle("flag_$it") }

        undo.transaction("Outer") {
            undo.pushSettingChange(settings[0], false, true)
            undo.transaction("Inner") {
                undo.pushSettingChange(settings[1], false, true)
                undo.pushSettingChange(settings[2], false, true)
            }
            undo.pushSettingChange(settings[3], false, true)
        }

        assertEquals(1, undo.undoCount)
        assertEquals("Outer", undo.nextUndoLabel)
    }

    @Test
    fun `a compound edit undoes in reverse order`() {
        val order = ArrayList<String>()
        val edits = listOf("a", "b", "c").map { name ->
            object : UndoableEdit {
                override val label: String get() = name
                override fun undo() {
                    order.add("undo-$name")
                }

                override fun redo() {
                    order.add("redo-$name")
                }
            }
        }

        val compound = CompoundEdit("Bulk", edits)
        compound.undo()
        compound.redo()

        assertEquals(
            listOf("undo-c", "undo-b", "undo-a", "redo-a", "redo-b", "redo-c"),
            order,
        )
    }

    // -- unsaved changes / apply-cancel -------------------------------------

    @Test
    fun `unsaved changes track distance from the last save`() {
        val setting = toggle("flag")
        assertFalse(undo.hasUnsavedChanges.value)

        undo.pushSettingChange(setting, false, true)
        assertTrue(undo.hasUnsavedChanges.value)

        undo.markSaved()
        assertFalse(undo.hasUnsavedChanges.value)
    }

    @Test
    fun `undoing back to the saved point clears the unsaved flag`() {
        val setting = toggle("flag")
        undo.markSaved()
        undo.pushSettingChange(setting, false, true)
        assertTrue(undo.hasUnsavedChanges.value)

        undo.undo()

        assertFalse(undo.hasUnsavedChanges.value, "back where we saved, so nothing is unsaved")
    }

    @Test
    fun `undoing past the saved point counts as unsaved again`() {
        val first = toggle("first")
        val second = toggle("second")
        undo.pushSettingChange(first, false, true)
        undo.markSaved()
        undo.pushSettingChange(second, false, true)

        undo.undo()
        assertFalse(undo.hasUnsavedChanges.value)

        undo.undo()
        assertTrue(undo.hasUnsavedChanges.value, "we are now behind the saved state")
    }

    @Test
    fun `cancel reverts every change made since the last save`() {
        val settings = List(3) { toggle("flag_$it") }
        undo.markSaved()

        for (setting in settings) {
            setting.setUnchecked(true)
            undo.pushSettingChange(setting, false, true)
        }

        val reverted = undo.revertToSaved()

        assertEquals(3, reverted)
        assertTrue(settings.none { it.value })
        assertFalse(undo.hasUnsavedChanges.value)
    }

    @Test
    fun `cancel after undoing past the save point redoes forward to it`() {
        val first = toggle("first")
        val second = toggle("second")
        first.setUnchecked(true)
        undo.pushSettingChange(first, false, true)
        second.setUnchecked(true)
        undo.pushSettingChange(second, false, true)
        undo.markSaved()

        undo.undo()
        undo.undo()
        assertTrue(undo.hasUnsavedChanges.value)

        undo.revertToSaved()

        assertTrue(first.value)
        assertTrue(second.value)
        assertFalse(undo.hasUnsavedChanges.value)
    }

    @Test
    fun `cancel with nothing to revert is a no-op`() {
        undo.markSaved()
        assertEquals(0, undo.revertToSaved())
    }

    // -- limits and clearing ------------------------------------------------

    @Test
    fun `the history is bounded and drops the oldest entries`() {
        val bounded = UndoStack(limit = 5)
        repeat(10) { index ->
            val setting = toggle("flag_$index")
            bounded.pushSettingChange(setting, false, true)
        }

        assertEquals(5, bounded.undoCount)
        assertEquals("Change flag_9", bounded.nextUndoLabel, "the newest entry survives")
    }

    @Test
    fun `clear drops history without touching any value`() {
        val setting = toggle("flag")
        setting.setUnchecked(true)
        undo.pushSettingChange(setting, false, true)

        undo.clear()

        assertEquals(0, undo.undoCount)
        assertFalse(undo.canUndo.value)
        assertFalse(undo.hasUnsavedChanges.value)
        assertTrue(setting.value, "clearing history must not revert anything")
    }

    @Test
    fun `undo state is observable`() {
        val setting = toggle("flag")
        val seen = ArrayList<Boolean>()
        val scope = dev.th7bo.sidequest.ui.state.DisposableScope()
        undo.canUndo.observe(scope) { seen.add(it) }

        undo.pushSettingChange(setting, false, true)
        undo.undo()

        assertEquals(listOf(true, false), seen)
        scope.dispose()
    }
}
