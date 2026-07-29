@file:JvmName("Bindings")

package dev.th7bo.sidequest.ui.binding

import dev.th7bo.sidequest.ui.state.DisposableScope
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.Subscription
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.UiThread
import dev.th7bo.sidequest.ui.state.map
import dev.th7bo.sidequest.ui.state.mutableStateOf
import kotlin.reflect.KMutableProperty0

/**
 * A typed two-way connection between a control and the value it edits.
 *
 * A binding is *read* through [state], so anything holding one participates in the
 * reactive graph, and *written* through [set]. Controls never touch the underlying
 * configuration object directly.
 */
public interface Binding<T> {

    /** The observable read side. */
    public val state: UiState<T>

    /** False for bindings that expose a computed or read-only value. */
    public val isWritable: Boolean

    /** Convenience for `state.value`. Records a dependency like any other state read. */
    public val value: T get() = state.value

    /**
     * Writes a new value.
     *
     * @throws BindingException if [isWritable] is false. Writes to a read-only binding
     * fail loudly rather than being silently dropped.
     */
    public fun set(value: T)

    /** Convenience for `state.observe`. */
    public fun observe(scope: DisposableScope, listener: (T) -> Unit): Subscription =
        state.observe(scope, listener)
}

/**
 * A binding over a value the framework does not own, and therefore cannot be notified
 * about. [refresh] re-reads the source and publishes any change.
 */
public interface RefreshableBinding<T> : Binding<T> {

    /** Re-reads the backing getter. Cheap and safe to call once per frame at most. */
    public fun refresh()
}

/**
 * Raised when a binding is used in a way it cannot support.
 *
 * Binding failures are never swallowed: a control bound to something it cannot write
 * is a programming error that must surface during development, not a silently
 * ineffective widget.
 */
public class BindingException(
    public val bindingName: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException("Binding '$bindingName': $message", cause)

// ---------------------------------------------------------------------------
// Factories
// ---------------------------------------------------------------------------

/** Exposes a [MutableUiState] as a binding. The cheapest and most direct form. */
public fun <T> MutableUiState<T>.asBinding(): Binding<T> = StateBinding(this)

/** Exposes a read-only state as a binding. Writing to it throws. */
public fun <T> UiState<T>.asReadOnlyBinding(): Binding<T> = ReadOnlyStateBinding(this)

/**
 * Binds an external getter/setter pair.
 *
 * The framework cannot observe an arbitrary getter, so this creates an observable
 * mirror seeded from [get]. Writes go to [set] *and* the mirror. If the underlying
 * value can also change behind the framework's back, call [RefreshableBinding.refresh].
 *
 * ```
 * bind(get = config::enabled, set = config::setEnabled)
 * ```
 */
public fun <T> bind(
    get: () -> T,
    set: (T) -> Unit,
    debugName: String = "binding",
): RefreshableBinding<T> {
    UiThread.check()
    return MirrorBinding(debugName, get, set)
}

/**
 * Binds a mutable property reference. Property references are resolved by the compiler,
 * not by runtime reflection, so this is safe outside hot paths and allocates nothing
 * per read.
 *
 * ```
 * bind(config::notificationDuration)
 * ```
 */
public fun <T> bind(property: KMutableProperty0<T>): RefreshableBinding<T> =
    bind(get = property::get, set = property::set, debugName = property.name)

/**
 * Transforms a binding in both directions.
 *
 * [to] converts the source value for display, [from] converts an edited value back.
 * The pair must round-trip: `from(to(v)) == v` for every value the control can produce.
 */
public fun <T, R> Binding<T>.map(
    to: (T) -> R,
    from: (R) -> T,
    debugName: String = "${state.debugName}.map",
): Binding<R> = MappedBinding(this, to, from, debugName)

// ---------------------------------------------------------------------------
// Implementations
// ---------------------------------------------------------------------------

private class StateBinding<T>(private val backing: MutableUiState<T>) : Binding<T> {
    override val state: UiState<T> get() = backing
    override val isWritable: Boolean get() = true
    override fun set(value: T) {
        backing.value = value
    }
}

private class ReadOnlyStateBinding<T>(override val state: UiState<T>) : Binding<T> {
    override val isWritable: Boolean get() = false
    override fun set(value: T): Nothing =
        throw BindingException(state.debugName, "is read-only and cannot be written")
}

private class MirrorBinding<T>(
    private val debugName: String,
    private val get: () -> T,
    private val set: (T) -> Unit,
) : RefreshableBinding<T> {

    private val mirror: MutableUiState<T> = mutableStateOf(readSource(), debugName)

    override val state: UiState<T> get() = mirror
    override val isWritable: Boolean get() = true

    override fun set(value: T) {
        UiThread.check()
        try {
            set.invoke(value)
        } catch (throwable: Throwable) {
            throw BindingException(debugName, "setter threw while writing '$value'", throwable)
        }
        // Read back rather than trusting the write: a setter that clamps or normalises
        // its input must not leave the UI showing a value the model never accepted.
        mirror.value = readSource()
    }

    override fun refresh() {
        UiThread.check()
        mirror.value = readSource()
    }

    private fun readSource(): T = try {
        get.invoke()
    } catch (throwable: Throwable) {
        throw BindingException(debugName, "getter threw", throwable)
    }
}

private class MappedBinding<T, R>(
    private val source: Binding<T>,
    to: (T) -> R,
    private val from: (R) -> T,
    debugName: String,
) : Binding<R> {

    override val state: UiState<R> = source.state.map(debugName) { to(it) }

    override val isWritable: Boolean get() = source.isWritable

    override fun set(value: R) {
        if (!isWritable) {
            throw BindingException(state.debugName, "underlying binding is read-only")
        }
        source.set(from(value))
    }
}
