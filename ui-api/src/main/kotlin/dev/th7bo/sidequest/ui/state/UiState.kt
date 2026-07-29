package dev.th7bo.sidequest.ui.state

/**
 * A typed, observable value.
 *
 * Reading [value] inside a `derivedStateOf { }` computation records a dependency, so
 * the runtime knows what to recompute without re-running anything else. Reading it
 * outside a computation is an ordinary read.
 *
 * Prefer passing a `UiState` over passing an opaque `() -> T`: the state tells the
 * runtime *what it depends on*, a lambda does not.
 *
 * Confined to the UI thread — see [UiThread].
 */
public interface UiState<out T> {

    /** A human-readable name used in cycle reports and diagnostics. */
    public val debugName: String

    /** The current value. Records a dependency when read inside a derivation. */
    public val value: T

    /** The current value *without* recording a dependency. */
    public fun peek(): T

    /**
     * Registers [listener], invoked on the UI thread whenever the value changes by the
     * state's equality function. The listener does not fire on registration.
     *
     * Notifications are batched: within a [batch] block a listener fires at most once,
     * after all writes settle, and never for a change that ends up being a no-op.
     *
     * The returned [Subscription] is owned by [scope]; disposing either detaches it.
     */
    public fun observe(scope: DisposableScope, listener: (T) -> Unit): Subscription
}

/** A [UiState] that can also be written. */
public interface MutableUiState<T> : UiState<T> {

    /**
     * Reads or writes the value. Writing a value that is equivalent to the current one
     * is a complete no-op: no version bump, no invalidation, no notification.
     */
    override var value: T
}

/**
 * Thrown when a derivation depends on itself, directly or transitively.
 *
 * The message contains the full dependency path so the offending derivation can be
 * found without a debugger.
 */
public class StateCycleException(
    public val path: List<String>,
) : IllegalStateException("Dependency cycle in reactive state: ${path.joinToString(" -> ")}")
