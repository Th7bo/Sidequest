@file:JvmName("States")

package dev.th7bo.sidequest.ui.state

/**
 * Creates a writable state.
 *
 * @param initial the starting value.
 * @param debugName shown in cycle reports and diagnostics; worth setting on anything
 *   that participates in a non-trivial derivation.
 * @param equality decides whether a write is a real change. Defaults to `==`. Pass
 *   `{ _, _ -> false }` for values whose identity matters more than their equality,
 *   or a reference check for large structures that are expensive to compare.
 */
public fun <T> mutableStateOf(
    initial: T,
    debugName: String = "state",
    equality: (T, T) -> Boolean = defaultEquality(),
): MutableUiState<T> {
    UiThread.check()
    return SourceState(debugName, equality, initial)
}

/**
 * Creates a derivation. Dependencies are tracked automatically from the state reads
 * performed inside [compute], including conditional ones — a branch that is not taken
 * does not create a dependency.
 *
 * [compute] runs lazily, on read, and only when a dependency has genuinely changed.
 *
 * ```
 * val canSave = derivedStateOf { name.value.isNotBlank() && !readOnly.value }
 * ```
 *
 * @throws StateCycleException on read, if the derivation depends on itself.
 */
public fun <T> derivedStateOf(
    debugName: String = "derived",
    equality: (T, T) -> Boolean = defaultEquality(),
    compute: () -> T,
): UiState<T> {
    UiThread.check()
    return DerivedState(debugName, equality, compute)
}

/** A state that never changes. Observing it never fires. */
public fun <T> constantState(value: T): UiState<T> = ConstantState(value)

/** Single-source derivation. Cheaper to read than [derivedStateOf] and self-documenting. */
public fun <T, R> UiState<T>.map(
    debugName: String = "${this.debugName}.map",
    equality: (R, R) -> Boolean = defaultEquality(),
    transform: (T) -> R,
): UiState<R> = derivedStateOf(debugName, equality) { transform(value) }

/** Two-source derivation. */
public fun <A, B, R> combine(
    first: UiState<A>,
    second: UiState<B>,
    debugName: String = "combine",
    equality: (R, R) -> Boolean = defaultEquality(),
    transform: (A, B) -> R,
): UiState<R> = derivedStateOf(debugName, equality) { transform(first.value, second.value) }

/** Three-source derivation. */
public fun <A, B, C, R> combine(
    first: UiState<A>,
    second: UiState<B>,
    third: UiState<C>,
    debugName: String = "combine",
    equality: (R, R) -> Boolean = defaultEquality(),
    transform: (A, B, C) -> R,
): UiState<R> = derivedStateOf(debugName, equality) { transform(first.value, second.value, third.value) }

/** Logical negation of a boolean state. */
public operator fun UiState<Boolean>.not(): UiState<Boolean> = map("!$debugName") { !it }

/** Logical AND. Short-circuits, so a false [this] does not create a dependency on [other]. */
public infix fun UiState<Boolean>.and(other: UiState<Boolean>): UiState<Boolean> =
    derivedStateOf("$debugName && ${other.debugName}") { value && other.value }

/** Logical OR. Short-circuits. */
public infix fun UiState<Boolean>.or(other: UiState<Boolean>): UiState<Boolean> =
    derivedStateOf("$debugName || ${other.debugName}") { value || other.value }

/**
 * Groups writes so that listeners see one settled result instead of every intermediate
 * step. Nested batches flush once, when the outermost exits.
 *
 * ```
 * batch {
 *     width.value = 100f
 *     height.value = 50f
 * }   // an observer of `area` fires once here, not twice
 * ```
 */
public fun <R> batch(block: () -> R): R = ReactiveGraph.batch(block)

/**
 * Reads state without recording dependencies, even inside a derivation.
 *
 * Use when a derivation needs a value for its *result* but must not recompute when
 * that value changes.
 */
public fun <R> untracked(block: () -> R): R = ReactiveGraph.untracked(block)

/**
 * Test hook: clears graph-wide bookkeeping and unbinds the UI thread.
 *
 * Only meaningful between tests. Calling this while a UI is live will strand pending
 * notifications.
 */
public fun resetReactiveGraphForTesting() {
    ReactiveGraph.reset()
    UiThread.unbind()
}

/**
 * Structural equality, shared by every state that does not override it. A single
 * instance, reused via an unchecked cast, so creating a state allocates no lambda.
 */
@Suppress("UNCHECKED_CAST")
private fun <T> defaultEquality(): (T, T) -> Boolean = StructuralEquality as (T, T) -> Boolean

private val StructuralEquality: (Any?, Any?) -> Boolean = { a, b -> a == b }
