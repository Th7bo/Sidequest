package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.Modifiers

/**
 * Caret arithmetic over a plain string.
 *
 * Pure functions on `(text, caret)` with no node, no state and no side effects, because
 * every interesting case here is an off-by-one at a boundary — the last line, an empty
 * line, a caret sitting exactly on a newline — and those are worth testing directly rather
 * than through a control.
 *
 * Offsets are indices *between* characters: `0` is before the first character and
 * `text.length` is after the last. A caret on a newline belongs to the line the newline
 * ends, which is what makes End at the end of a line idempotent.
 */
public object TextEditing {

    /** Offset of the first character on the line containing [caret]. */
    public fun lineStartOf(text: String, caret: Int): Int {
        val clamped = caret.coerceIn(0, text.length)
        val previous = text.lastIndexOf('\n', clamped - 1)
        return if (previous < 0) 0 else previous + 1
    }

    /** Offset just past the last character on the line containing [caret]. */
    public fun lineEndOf(text: String, caret: Int): Int {
        val clamped = caret.coerceIn(0, text.length)
        val next = text.indexOf('\n', clamped)
        return if (next < 0) text.length else next
    }

    /** Zero-based column of [caret] within its line. */
    public fun columnOf(text: String, caret: Int): Int =
        caret.coerceIn(0, text.length) - lineStartOf(text, caret)

    /** Zero-based index of the line containing [caret]. */
    public fun lineIndexOf(text: String, caret: Int): Int {
        val clamped = caret.coerceIn(0, text.length)
        var count = 0
        for (index in 0 until clamped) if (text[index] == '\n') count++
        return count
    }

    /** How many lines [text] has. Trailing newline means a final empty line. */
    public fun lineCountOf(text: String): Int = text.count { it == '\n' } + 1

    /**
     * The offset one line above [caret], as close to [desiredColumn] as that line allows.
     *
     * [desiredColumn] rather than the current column, so passing through a short line does
     * not permanently pull the caret left — the behaviour every editor has and whose
     * absence is immediately noticeable.
     *
     * @return the same offset when already on the first line.
     */
    public fun up(text: String, caret: Int, desiredColumn: Int): Int {
        val start = lineStartOf(text, caret)
        if (start == 0) return caret
        val previousStart = lineStartOf(text, start - 1)
        val previousEnd = start - 1
        return (previousStart + desiredColumn).coerceAtMost(previousEnd)
    }

    /** The offset one line below [caret]. @return the same offset on the last line. */
    public fun down(text: String, caret: Int, desiredColumn: Int): Int {
        val end = lineEndOf(text, caret)
        if (end >= text.length) return caret
        val nextStart = end + 1
        val nextEnd = lineEndOf(text, nextStart)
        return (nextStart + desiredColumn).coerceAtMost(nextEnd)
    }

    /**
     * Where Ctrl+Left goes: back over any whitespace, then back over the word before it.
     *
     * A newline counts as whitespace, so Ctrl+Backspace at the start of a line joins it to
     * the one above and takes that line's last word with it — the same as a desktop editor.
     */
    public fun wordStartBefore(text: String, caret: Int): Int {
        var index = caret.coerceIn(0, text.length)
        while (index > 0 && text[index - 1].isWordSeparator()) index--
        while (index > 0 && !text[index - 1].isWordSeparator()) index--
        return index
    }

    /** Where Ctrl+Right goes: forward over the current word, then over trailing whitespace. */
    public fun wordEndAfter(text: String, caret: Int): Int {
        var index = caret.coerceIn(0, text.length)
        while (index < text.length && !text[index].isWordSeparator()) index++
        while (index < text.length && text[index].isWordSeparator()) index++
        return index
    }

    /** Whitespace and punctuation both end a word, which is what makes Ctrl+← useful in a path. */
    private fun Char.isWordSeparator(): Boolean = isWhitespace() || (!isLetterOrDigit() && this != '_')
}

/**
 * Caret and editing behaviour, shared by the single-line field and the text area.
 *
 * The two controls differ in how they *write* — one validates, the other does not — so the
 * write is a parameter and everything else lives here. Without this the caret rules would
 * be implemented twice and would drift, which is exactly how the text area ended up with
 * no arrow keys at all while the field had them.
 */
public class TextEditor(
    private val text: () -> String,
    private val maxLength: () -> Int,
    /**
     * Writes the value. Returning false leaves the caret where it was, so a rejected edit
     * does not desynchronise the caret from the text.
     */
    private val commit: (String) -> Boolean,
    /** Whether Enter inserts a newline and the caret can move between lines. */
    public val isMultiline: Boolean,
) {

    /** Offset within the value. Runtime state, never persisted. */
    public var caret: Int = text().length
        private set

    /**
     * The column vertical movement aims for, or -1 when there is none.
     *
     * Reset by every horizontal move and every edit, so it only survives a run of Up/Down.
     */
    private var desiredColumn: Int = -1

    /** Re-clamps after the value changed underneath the caret. */
    public fun clampToText() {
        caret = caret.coerceIn(0, text().length)
    }

    public fun moveTo(offset: Int) {
        caret = offset.coerceIn(0, text().length)
        desiredColumn = -1
    }

    public fun insert(fragment: String): Boolean {
        val current = text()
        if (current.length + fragment.length > maxLength()) return false
        val next = current.substring(0, caret) + fragment + current.substring(caret)
        if (!commit(next)) return false
        caret += fragment.length
        desiredColumn = -1
        return true
    }

    /** Deletes before the caret: one character, or back to the previous word with [word]. */
    public fun backspace(word: Boolean): Boolean {
        if (caret == 0) return false
        val current = text()
        val from = if (word) TextEditing.wordStartBefore(current, caret) else caret - 1
        if (!commit(current.substring(0, from) + current.substring(caret))) return false
        caret = from
        desiredColumn = -1
        return true
    }

    /** Deletes after the caret: one character, or up to the next word with [word]. */
    public fun delete(word: Boolean): Boolean {
        val current = text()
        if (caret >= current.length) return false
        val to = if (word) TextEditing.wordEndAfter(current, caret) else caret + 1
        if (!commit(current.substring(0, caret) + current.substring(to))) return false
        desiredColumn = -1
        return true
    }

    /**
     * Interprets a navigation or deletion key.
     *
     * @return true if it was handled and should be consumed. Anything not listed here —
     *   printable characters, Escape, Tab — is deliberately left to the caller.
     */
    public fun handleKey(key: Key, modifiers: Modifiers): Boolean {
        val current = text()
        val word = modifiers.control

        when (key) {
            Key.ARROW_LEFT -> {
                moveTo(if (word) TextEditing.wordStartBefore(current, caret) else caret - 1)
                return true
            }

            Key.ARROW_RIGHT -> {
                moveTo(if (word) TextEditing.wordEndAfter(current, caret) else caret + 1)
                return true
            }

            Key.ARROW_UP -> {
                if (!isMultiline) return false
                // The column is captured before the move and kept for the next one, so a
                // run of Up/Down through short lines returns to where it started.
                val column = rememberColumn(current)
                caret = TextEditing.up(current, caret, column)
                return true
            }

            Key.ARROW_DOWN -> {
                if (!isMultiline) return false
                val column = rememberColumn(current)
                caret = TextEditing.down(current, caret, column)
                return true
            }

            // Ctrl+Home/End reach the whole value; alone they reach the line, which for a
            // single-line control is the same thing.
            Key.HOME -> {
                moveTo(if (word || !isMultiline) 0 else TextEditing.lineStartOf(current, caret))
                return true
            }

            Key.END -> {
                moveTo(
                    if (word || !isMultiline) current.length else TextEditing.lineEndOf(current, caret),
                )
                return true
            }

            Key.BACKSPACE -> {
                backspace(word)
                return true
            }

            Key.DELETE -> {
                delete(word)
                return true
            }

            else -> return false
        }
    }

    private fun rememberColumn(current: String): Int {
        if (desiredColumn < 0) desiredColumn = TextEditing.columnOf(current, caret)
        return desiredColumn
    }
}
