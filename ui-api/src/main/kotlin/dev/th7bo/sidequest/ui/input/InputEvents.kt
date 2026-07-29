package dev.th7bo.sidequest.ui.input

import dev.th7bo.sidequest.ui.geometry.Vec2

/**
 * Where an event is in its journey through the tree.
 *
 * Events travel root-to-target in [CAPTURE], fire on the [TARGET], then travel
 * target-to-root in [BUBBLE]. A container that wants first refusal (a scroll view
 * claiming a drag, a modal swallowing clicks) handles the capture phase; everything
 * else handles bubble.
 */
public enum class EventPhase { CAPTURE, TARGET, BUBBLE }

public enum class MouseButton { LEFT, RIGHT, MIDDLE, OTHER }

/** Keyboard modifier bitset. */
@JvmInline
public value class Modifiers(public val bits: Int) {

    public val shift: Boolean get() = bits and SHIFT != 0
    public val control: Boolean get() = bits and CONTROL != 0
    public val alt: Boolean get() = bits and ALT != 0
    public val meta: Boolean get() = bits and META != 0

    public val isEmpty: Boolean get() = bits == 0

    public operator fun plus(other: Modifiers): Modifiers = Modifiers(bits or other.bits)

    public operator fun contains(other: Modifiers): Boolean = bits and other.bits == other.bits

    override fun toString(): String = buildList {
        if (control) add("Ctrl")
        if (alt) add("Alt")
        if (shift) add("Shift")
        if (meta) add("Meta")
    }.joinToString("+").ifEmpty { "None" }

    public companion object {
        public const val SHIFT: Int = 1
        public const val CONTROL: Int = 2
        public const val ALT: Int = 4
        public const val META: Int = 8

        public val None: Modifiers = Modifiers(0)
        public val Shift: Modifiers = Modifiers(SHIFT)
        public val Control: Modifiers = Modifiers(CONTROL)
        public val Alt: Modifiers = Modifiers(ALT)
        public val Meta: Modifiers = Modifiers(META)
    }
}

/**
 * A physical key, named independently of any host's key codes.
 *
 * The Minecraft adapter maps GLFW codes onto these; the testkit synthesises them
 * directly. [UNKNOWN] carries the raw host code so keybind capture can still record a
 * key the framework has no name for.
 */
public enum class Key {
    ENTER, ESCAPE, BACKSPACE, TAB, SPACE, DELETE, INSERT,
    ARROW_LEFT, ARROW_RIGHT, ARROW_UP, ARROW_DOWN,
    HOME, END, PAGE_UP, PAGE_DOWN,
    LEFT_SHIFT, RIGHT_SHIFT, LEFT_CONTROL, RIGHT_CONTROL,
    LEFT_ALT, RIGHT_ALT, LEFT_META, RIGHT_META,
    A, B, C, D, E, F, G, H, I, J, K, L, M,
    N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4,
    DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    MINUS, EQUALS, COMMA, PERIOD, SLASH, BACKSLASH,
    SEMICOLON, APOSTROPHE, GRAVE, LEFT_BRACKET, RIGHT_BRACKET,
    UNKNOWN,
    ;

    /** True for keys that only ever act as modifiers. */
    public val isModifier: Boolean
        get() = this in MODIFIER_KEYS

    private companion object {
        val MODIFIER_KEYS = setOf(
            LEFT_SHIFT, RIGHT_SHIFT, LEFT_CONTROL, RIGHT_CONTROL,
            LEFT_ALT, RIGHT_ALT, LEFT_META, RIGHT_META,
        )
    }
}

/**
 * Base class for everything the input system dispatches.
 *
 * Components never poll global mouse or keyboard state; they receive these. That is
 * what makes input testable without a window, and what makes scaled HUD hit testing
 * correct — the position on a pointer event has already been transformed into the
 * receiving node's local space.
 */
public sealed class InputEvent {

    /** Where this event currently is in its journey through the tree. */
    public var phase: EventPhase = EventPhase.TARGET
        private set

    /**
     * Set by the input dispatcher before each handler invocation.
     *
     * Components read [phase]; they must never write it. It is exposed as a function
     * rather than a setter so that a stray assignment cannot be mistaken for ordinary
     * property use.
     */
    public fun setPhaseForDispatch(newPhase: EventPhase) {
        phase = newPhase
    }

    /** True once a handler has claimed the event. */
    public var isConsumed: Boolean = false
        private set

    /**
     * Claims the event. Consumed events still complete the current phase's handler for
     * the current node, but are not offered to any further node.
     */
    public fun consume() {
        isConsumed = true
    }
}

/**
 * Base class for events that carry a position.
 *
 * [rootPosition] is fixed for the lifetime of the event; [position] is rewritten by the
 * dispatcher before each handler so that every node on the path sees the pointer in its
 * *own* coordinate space. That is what keeps the cursor aligned with a scaled subtree —
 * a handler compares against its local bounds and never has to know its own transform.
 */
public sealed class PointerEvent : InputEvent() {

    /** Position in the root's coordinate space. Constant throughout dispatch. */
    public abstract val rootPosition: Vec2

    /** Position in the local space of the node currently handling the event. */
    public var position: Vec2 = Vec2.Zero
        private set

    /** Set by the input dispatcher before each handler invocation. */
    public fun setPositionForDispatch(local: Vec2) {
        position = local
    }
}

public class PointerMoveEvent(
    override val rootPosition: Vec2,
    /** Movement since the previous move event, in root units. */
    public val delta: Vec2,
    public val modifiers: Modifiers = Modifiers.None,
) : PointerEvent()

public class PointerDownEvent(
    override val rootPosition: Vec2,
    public val button: MouseButton,
    public val modifiers: Modifiers = Modifiers.None,
    /** 1 for a single click, 2 for a double click, and so on. */
    public val clickCount: Int = 1,
) : PointerEvent()

public class PointerUpEvent(
    override val rootPosition: Vec2,
    public val button: MouseButton,
    public val modifiers: Modifiers = Modifiers.None,
) : PointerEvent()

/** Fired on the node that holds pointer capture while a button is held. */
public class PointerDragEvent(
    override val rootPosition: Vec2,
    public val delta: Vec2,
    public val button: MouseButton,
    public val modifiers: Modifiers = Modifiers.None,
) : PointerEvent()

public class ScrollEvent(
    override val rootPosition: Vec2,
    public val scrollX: Float,
    public val scrollY: Float,
    public val modifiers: Modifiers = Modifiers.None,
) : PointerEvent()

/** Sent when the pointer enters or leaves a node's bounds. Never consumed usefully. */
public class PointerEnterEvent(override val rootPosition: Vec2) : PointerEvent()

public class PointerExitEvent(override val rootPosition: Vec2) : PointerEvent()

public class KeyDownEvent(
    public val key: Key,
    public val modifiers: Modifiers = Modifiers.None,
    public val isRepeat: Boolean = false,
    /** The host's raw key code, retained for keybind capture. */
    public val rawCode: Int = -1,
) : InputEvent()

public class KeyUpEvent(
    public val key: Key,
    public val modifiers: Modifiers = Modifiers.None,
    public val rawCode: Int = -1,
) : InputEvent()

/** A typed character. Separate from [KeyDownEvent] because IME and layouts exist. */
public class CharTypedEvent(
    public val codePoint: Int,
    public val modifiers: Modifiers = Modifiers.None,
) : InputEvent() {

    public val char: Char get() = codePoint.toChar()
}

/** Fired when a node gains or loses focus. */
public class FocusEvent(public val gained: Boolean) : InputEvent()
