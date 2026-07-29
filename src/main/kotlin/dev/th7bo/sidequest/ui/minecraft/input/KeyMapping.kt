package dev.th7bo.sidequest.ui.minecraft.input

import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.Modifiers
import dev.th7bo.sidequest.ui.input.MouseButton
import org.lwjgl.glfw.GLFW

/**
 * Translates GLFW input codes into the framework's host-independent types.
 *
 * The framework deliberately knows nothing about GLFW, so this is the only place the
 * two vocabularies meet. [toKey] falls back to [Key.UNKNOWN] rather than throwing: a
 * keyboard layout can produce codes no build anticipated, and keybind capture still has
 * to record them by their raw code.
 */
public object KeyMapping {

    private val BY_GLFW_CODE: Map<Int, Key> = buildMap {
        put(GLFW.GLFW_KEY_ENTER, Key.ENTER)
        put(GLFW.GLFW_KEY_KP_ENTER, Key.ENTER)
        put(GLFW.GLFW_KEY_ESCAPE, Key.ESCAPE)
        put(GLFW.GLFW_KEY_BACKSPACE, Key.BACKSPACE)
        put(GLFW.GLFW_KEY_TAB, Key.TAB)
        put(GLFW.GLFW_KEY_SPACE, Key.SPACE)
        put(GLFW.GLFW_KEY_DELETE, Key.DELETE)
        put(GLFW.GLFW_KEY_INSERT, Key.INSERT)

        put(GLFW.GLFW_KEY_LEFT, Key.ARROW_LEFT)
        put(GLFW.GLFW_KEY_RIGHT, Key.ARROW_RIGHT)
        put(GLFW.GLFW_KEY_UP, Key.ARROW_UP)
        put(GLFW.GLFW_KEY_DOWN, Key.ARROW_DOWN)

        put(GLFW.GLFW_KEY_HOME, Key.HOME)
        put(GLFW.GLFW_KEY_END, Key.END)
        put(GLFW.GLFW_KEY_PAGE_UP, Key.PAGE_UP)
        put(GLFW.GLFW_KEY_PAGE_DOWN, Key.PAGE_DOWN)

        put(GLFW.GLFW_KEY_LEFT_SHIFT, Key.LEFT_SHIFT)
        put(GLFW.GLFW_KEY_RIGHT_SHIFT, Key.RIGHT_SHIFT)
        put(GLFW.GLFW_KEY_LEFT_CONTROL, Key.LEFT_CONTROL)
        put(GLFW.GLFW_KEY_RIGHT_CONTROL, Key.RIGHT_CONTROL)
        put(GLFW.GLFW_KEY_LEFT_ALT, Key.LEFT_ALT)
        put(GLFW.GLFW_KEY_RIGHT_ALT, Key.RIGHT_ALT)
        put(GLFW.GLFW_KEY_LEFT_SUPER, Key.LEFT_META)
        put(GLFW.GLFW_KEY_RIGHT_SUPER, Key.RIGHT_META)

        // Letters and digits are contiguous in GLFW, so they map by offset.
        for (offset in 0..25) {
            put(GLFW.GLFW_KEY_A + offset, Key.entries[Key.A.ordinal + offset])
        }
        for (offset in 0..9) {
            put(GLFW.GLFW_KEY_0 + offset, Key.entries[Key.DIGIT_0.ordinal + offset])
        }
        for (offset in 0..11) {
            put(GLFW.GLFW_KEY_F1 + offset, Key.entries[Key.F1.ordinal + offset])
        }

        put(GLFW.GLFW_KEY_MINUS, Key.MINUS)
        put(GLFW.GLFW_KEY_EQUAL, Key.EQUALS)
        put(GLFW.GLFW_KEY_COMMA, Key.COMMA)
        put(GLFW.GLFW_KEY_PERIOD, Key.PERIOD)
        put(GLFW.GLFW_KEY_SLASH, Key.SLASH)
        put(GLFW.GLFW_KEY_BACKSLASH, Key.BACKSLASH)
        put(GLFW.GLFW_KEY_SEMICOLON, Key.SEMICOLON)
        put(GLFW.GLFW_KEY_APOSTROPHE, Key.APOSTROPHE)
        put(GLFW.GLFW_KEY_GRAVE_ACCENT, Key.GRAVE)
        put(GLFW.GLFW_KEY_LEFT_BRACKET, Key.LEFT_BRACKET)
        put(GLFW.GLFW_KEY_RIGHT_BRACKET, Key.RIGHT_BRACKET)
    }

    /** Maps a GLFW key code, or [Key.UNKNOWN] for anything unrecognised. */
    public fun toKey(glfwCode: Int): Key = BY_GLFW_CODE[glfwCode] ?: Key.UNKNOWN

    /** Maps a GLFW modifier bitmask onto the framework's. */
    public fun toModifiers(glfwModifiers: Int): Modifiers {
        var bits = 0
        if (glfwModifiers and GLFW.GLFW_MOD_SHIFT != 0) bits = bits or Modifiers.SHIFT
        if (glfwModifiers and GLFW.GLFW_MOD_CONTROL != 0) bits = bits or Modifiers.CONTROL
        if (glfwModifiers and GLFW.GLFW_MOD_ALT != 0) bits = bits or Modifiers.ALT
        if (glfwModifiers and GLFW.GLFW_MOD_SUPER != 0) bits = bits or Modifiers.META
        return Modifiers(bits)
    }

    /** Maps a GLFW mouse button index. */
    public fun toMouseButton(glfwButton: Int): MouseButton = when (glfwButton) {
        GLFW.GLFW_MOUSE_BUTTON_LEFT -> MouseButton.LEFT
        GLFW.GLFW_MOUSE_BUTTON_RIGHT -> MouseButton.RIGHT
        GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> MouseButton.MIDDLE
        else -> MouseButton.OTHER
    }
}
