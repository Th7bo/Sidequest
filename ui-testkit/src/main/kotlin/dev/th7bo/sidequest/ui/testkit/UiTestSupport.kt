package dev.th7bo.sidequest.ui.testkit

import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.input.CharTypedEvent
import dev.th7bo.sidequest.ui.input.EventPhase
import dev.th7bo.sidequest.ui.input.InputEvent
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.KeyDownEvent
import dev.th7bo.sidequest.ui.input.KeyUpEvent
import dev.th7bo.sidequest.ui.input.Modifiers
import dev.th7bo.sidequest.ui.input.MouseButton
import dev.th7bo.sidequest.ui.input.PointerDownEvent
import dev.th7bo.sidequest.ui.input.PointerDragEvent
import dev.th7bo.sidequest.ui.input.PointerEnterEvent
import dev.th7bo.sidequest.ui.input.PointerEvent
import dev.th7bo.sidequest.ui.input.PointerExitEvent
import dev.th7bo.sidequest.ui.input.PointerMoveEvent
import dev.th7bo.sidequest.ui.input.PointerUpEvent
import dev.th7bo.sidequest.ui.input.ScrollEvent
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * JUnit extension that gives each test a clean reactive graph and a UI thread bound to
 * the test thread.
 *
 * Without it, one test's pending notifications or thread binding leak into the next,
 * which turns a real ordering bug into an intermittent failure somewhere unrelated.
 *
 * ```
 * @ExtendWith(UiTestExtension::class)
 * class MyTest { ... }
 * ```
 */
public class UiTestExtension : BeforeEachCallback, AfterEachCallback {

    override fun beforeEach(context: ExtensionContext) {
        resetReactiveGraphForTesting()
    }

    override fun afterEach(context: ExtensionContext) {
        resetReactiveGraphForTesting()
    }
}

/**
 * Records every event a node receives, so tests can assert on propagation order,
 * phases and consumption instead of on side effects.
 */
public class EventRecorder {

    private val recorded = ArrayList<Entry>()

    public val events: List<Entry> get() = recorded

    /** A short readable trace, e.g. `["CAPTURE:root", "TARGET:button"]`. */
    public fun trace(): List<String> = recorded.map { "${it.phase}:${it.label}" }

    /**
     * Records that [label] received [event].
     *
     * The phase and position are snapshotted here rather than read back later: the
     * dispatcher reuses one event object across the whole path, so a lazily-read phase
     * would report the *last* phase for every entry.
     */
    public fun record(label: String, event: InputEvent) {
        recorded.add(Entry(label, event, event.phase, (event as? PointerEvent)?.position))
    }

    /** Returns a handler that records under [label]. */
    public fun handler(label: String): (InputEvent) -> Unit = { record(label, it) }

    /** Returns a handler that records then consumes, for testing propagation stops. */
    public fun consumingHandler(label: String): (InputEvent) -> Unit = {
        record(label, it)
        it.consume()
    }

    public fun clear() {
        recorded.clear()
    }

    public class Entry(
        public val label: String,
        public val event: InputEvent,
        /** The phase at the moment of delivery, not the event's current phase. */
        public val phase: EventPhase,
        /** The local position at the moment of delivery, for pointer events. */
        public val position: Vec2?,
    ) {
        override fun toString(): String = "$phase:$label <- ${describe(event)}"
    }

    public companion object {
        /** A stable short name for an event, used in assertions and failure messages. */
        public fun describe(event: InputEvent): String = when (event) {
            is PointerMoveEvent -> "move${event.position}"
            is PointerDownEvent -> "down${event.position}/${event.button}"
            is PointerUpEvent -> "up${event.position}/${event.button}"
            is PointerDragEvent -> "drag${event.position}"
            is ScrollEvent -> "scroll(${event.scrollX},${event.scrollY})"
            is PointerEnterEvent -> "enter"
            is PointerExitEvent -> "exit"
            is KeyDownEvent -> "keyDown/${event.key}"
            is KeyUpEvent -> "keyUp/${event.key}"
            is CharTypedEvent -> "char/${event.char}"
            else -> event::class.simpleName ?: "event"
        }
    }
}

/** Shorthand constructors so tests read as intent rather than as boilerplate. */
public object Input {

    public fun at(x: Float, y: Float): Vec2 = Vec2(x, y)

    public val left: MouseButton get() = MouseButton.LEFT
    public val right: MouseButton get() = MouseButton.RIGHT

    public val shift: Modifiers get() = Modifiers.Shift
    public val control: Modifiers get() = Modifiers.Control

    public val tab: Key get() = Key.TAB
    public val escape: Key get() = Key.ESCAPE
    public val enter: Key get() = Key.ENTER
}
