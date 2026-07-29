package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.Modifiers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Caret arithmetic, tested directly.
 *
 * Every interesting case here is a boundary — the first line, the last line, an empty
 * line, a caret sitting exactly on a newline — and driving those through a control would
 * hide which of the two is wrong when one breaks.
 */
class TextEditingTest {

    private val text = "alpha\nbet\n\ngamma"
    //                  012345 6789 10 11...
    //                  line 0: alpha  (0..5)
    //                  line 1: bet    (6..9)
    //                  line 2: ""     (10)
    //                  line 3: gamma  (11..16)

    // ---------------------------------------------------------------
    // Lines and columns
    // ---------------------------------------------------------------

    @Test
    fun `line boundaries are found from anywhere on the line`() {
        assertEquals(0, TextEditing.lineStartOf(text, 3))
        assertEquals(5, TextEditing.lineEndOf(text, 3))

        assertEquals(6, TextEditing.lineStartOf(text, 8))
        assertEquals(9, TextEditing.lineEndOf(text, 8))
    }

    @Test
    fun `a caret on a newline belongs to the line that newline ends`() {
        // Offset 5 is the '\n' after "alpha", so End there must not jump to the next line.
        assertEquals(0, TextEditing.lineStartOf(text, 5))
        assertEquals(5, TextEditing.lineEndOf(text, 5))
        assertEquals(5, TextEditing.columnOf(text, 5))
    }

    @Test
    fun `an empty line has a start equal to its end`() {
        assertEquals(10, TextEditing.lineStartOf(text, 10))
        assertEquals(10, TextEditing.lineEndOf(text, 10))
        assertEquals(0, TextEditing.columnOf(text, 10))
    }

    @Test
    fun `line index and count agree`() {
        assertEquals(0, TextEditing.lineIndexOf(text, 3))
        assertEquals(1, TextEditing.lineIndexOf(text, 8))
        assertEquals(3, TextEditing.lineIndexOf(text, text.length))
        assertEquals(4, TextEditing.lineCountOf(text))
        assertEquals(1, TextEditing.lineCountOf(""))
        assertEquals(2, TextEditing.lineCountOf("a\n"), "a trailing newline opens a line")
    }

    // ---------------------------------------------------------------
    // Vertical movement
    // ---------------------------------------------------------------

    @Test
    fun `up and down keep the column where the line is long enough`() {
        val caret = 13 // "gamma", column 2
        val up = TextEditing.up(text, caret, 2)
        assertEquals(2, TextEditing.lineIndexOf(text, up))

        val backDown = TextEditing.down(text, up, 2)
        assertEquals(caret, backDown)
    }

    @Test
    fun `passing through a short line does not drag the caret left`() {
        // From column 4 of "alpha", down through the empty line and back to "gamma"
        // must land on column 4 again — the desired column is what makes that work.
        var caret = 4
        caret = TextEditing.down(text, caret, 4)
        assertEquals(9, caret, "clamped to the end of 'bet'")
        caret = TextEditing.down(text, caret, 4)
        assertEquals(10, caret, "clamped to the empty line")
        caret = TextEditing.down(text, caret, 4)
        assertEquals(15, caret, "back to column 4 of 'gamma'")
        assertEquals(4, TextEditing.columnOf(text, caret))
    }

    @Test
    fun `up on the first line and down on the last stay put`() {
        assertEquals(3, TextEditing.up(text, 3, 3))
        assertEquals(text.length, TextEditing.down(text, text.length, 5))
    }

    // ---------------------------------------------------------------
    // Word boundaries
    // ---------------------------------------------------------------

    @Test
    fun `word movement skips whitespace and then the word`() {
        val words = "the quick  brown"
        assertEquals(11, TextEditing.wordStartBefore(words, words.length), "start of 'brown'")
        assertEquals(4, TextEditing.wordStartBefore(words, 11), "the run of spaces is crossed in one go")
        assertEquals(0, TextEditing.wordStartBefore(words, 4))
        assertEquals(0, TextEditing.wordStartBefore(words, 0), "and stops at the start")
    }

    @Test
    fun `word movement forwards ends past the trailing whitespace`() {
        val words = "the quick  brown"
        assertEquals(4, TextEditing.wordEndAfter(words, 0))
        assertEquals(11, TextEditing.wordEndAfter(words, 4))
        assertEquals(words.length, TextEditing.wordEndAfter(words, 11))
    }

    @Test
    fun `punctuation ends a word`() {
        val path = "config/values.json"
        assertEquals(14, TextEditing.wordStartBefore(path, path.length))
    }

    @Test
    fun `a newline counts as whitespace, so word deletion joins lines`() {
        assertEquals(6, TextEditing.wordStartBefore("alpha\nbet", 9))
        assertEquals(0, TextEditing.wordStartBefore("alpha\nbet", 6), "from column 0 it takes the line above")
    }

    // ---------------------------------------------------------------
    // The editor
    // ---------------------------------------------------------------

    private fun editor(initial: String, multiline: Boolean = true, maxLength: Int = 1000): Pair<TextEditor, () -> String> {
        var value = initial
        val editor = TextEditor(
            text = { value },
            maxLength = { maxLength },
            commit = { value = it; true },
            isMultiline = multiline,
        )
        return editor to { value }
    }

    @Test
    fun `ctrl backspace deletes a whole word`() {
        val (editor, value) = editor("delete this word")
        assertTrue(editor.handleKey(Key.BACKSPACE, Modifiers.Control))
        assertEquals("delete this ", value())
        assertEquals(value().length, editor.caret)
    }

    @Test
    fun `ctrl delete removes forwards`() {
        val (editor, value) = editor("delete this word")
        editor.moveTo(0)
        assertTrue(editor.handleKey(Key.DELETE, Modifiers.Control))
        assertEquals("this word", value())
        assertEquals(0, editor.caret, "the caret does not move when deleting forwards")
    }

    @Test
    fun `plain backspace still removes one character`() {
        val (editor, value) = editor("abc")
        editor.handleKey(Key.BACKSPACE, Modifiers.None)
        assertEquals("ab", value())
    }

    @Test
    fun `home and end reach the line, and with control the whole value`() {
        val (editor, _) = editor("alpha\nbet")
        editor.moveTo(7)

        editor.handleKey(Key.HOME, Modifiers.None)
        assertEquals(6, editor.caret)

        editor.handleKey(Key.END, Modifiers.None)
        assertEquals(9, editor.caret)

        editor.handleKey(Key.HOME, Modifiers.Control)
        assertEquals(0, editor.caret)

        editor.handleKey(Key.END, Modifiers.Control)
        assertEquals(9, editor.caret)
    }

    @Test
    fun `a single-line editor refuses vertical movement`() {
        val (editor, _) = editor("alpha\nbet", multiline = false)
        editor.moveTo(2)
        assertFalse(editor.handleKey(Key.ARROW_UP, Modifiers.None))
        assertFalse(editor.handleKey(Key.ARROW_DOWN, Modifiers.None))
        assertEquals(2, editor.caret)
    }

    @Test
    fun `a refused write leaves the caret where it was`() {
        var value = "abc"
        val editor = TextEditor(
            text = { value },
            maxLength = { 1000 },
            commit = { false },
            isMultiline = false,
        )
        editor.moveTo(1)

        assertFalse(editor.insert("x"))
        assertEquals(1, editor.caret, "a caret past a character that was never inserted is a desync")
        assertEquals("abc", value)
    }

    @Test
    fun `insertion past the maximum length is refused whole`() {
        val (editor, value) = editor("abcde", maxLength = 5)
        assertFalse(editor.insert("f"))
        assertEquals("abcde", value(), "the limit holds rather than truncating afterwards")
    }

    @Test
    fun `an unhandled key is reported as unhandled`() {
        val (editor, _) = editor("abc")
        assertFalse(editor.handleKey(Key.ENTER, Modifiers.None), "Enter belongs to the control")
        assertFalse(editor.handleKey(Key.ESCAPE, Modifiers.None))
        assertFalse(editor.handleKey(Key.TAB, Modifiers.None), "Tab must still reach focus traversal")
    }
}
