package dev.th7bo.sidequest.ui.state

/**
 * Shared machinery for source and derived states: versioning, the dependent set and
 * batched listener notification.
 */
internal abstract class StateNode<T>(
    final override val debugName: String,
    @JvmField protected val equality: (T, T) -> Boolean,
) : UiState<T> {

    /**
     * Bumped only when the value actually changes. Derivations compare the versions
     * they recorded against these to decide whether recomputation is necessary.
     */
    @JvmField
    var version: Long = ReactiveGraph.nextVersion()

    /** Derivations that read this node during their last computation. */
    @JvmField
    val dependents: LinkedHashSet<DerivedState<*>> = LinkedHashSet()

    private var listeners: ArrayList<ListenerSubscription<T>>? = null

    /** Last value handed to listeners, or [ReactiveGraph.Unset] before the first one. */
    private var lastNotified: Any? = ReactiveGraph.Unset

    /** Evaluates without recording a dependency on the caller. */
    abstract fun currentValue(): T

    final override fun peek(): T {
        UiThread.check()
        return currentValue()
    }

    final override fun observe(scope: DisposableScope, listener: (T) -> Unit): Subscription {
        UiThread.check()
        val list = listeners ?: ArrayList<ListenerSubscription<T>>(INITIAL_LISTENERS).also { listeners = it }
        // Evaluating here does double duty: it seeds the change baseline, and for a
        // derivation it forces dependency registration so invalidation can reach us.
        if (list.isEmpty()) lastNotified = currentValue()
        val subscription = ListenerSubscription(this, listener, scope)
        list.add(subscription)
        scope.register(subscription)
        return subscription
    }

    fun removeListener(subscription: ListenerSubscription<T>) {
        listeners?.remove(subscription)
    }

    @Suppress("UNCHECKED_CAST")
    fun notifyListeners() {
        val list = listeners
        if (list.isNullOrEmpty()) return

        val current = currentValue()
        val previous = lastNotified
        if (previous !== ReactiveGraph.Unset && equality(previous as T, current)) return
        lastNotified = current

        // Snapshot: a listener may dispose itself or add another.
        for (subscription in list.toList()) {
            if (!subscription.isDisposed) subscription.invoke(current)
        }
    }

    private companion object {
        const val INITIAL_LISTENERS = 2
    }
}

internal class ListenerSubscription<T>(
    private var node: StateNode<T>?,
    private var listener: ((T) -> Unit)?,
    private var scope: DisposableScope?,
) : Subscription {

    override var isDisposed: Boolean = false
        private set

    fun invoke(value: T) {
        listener?.invoke(value)
    }

    override fun dispose() {
        if (isDisposed) return
        isDisposed = true
        node?.removeListener(this)
        scope?.unregister(this)
        // Drop every reference: a retained lambda is how an unloaded module keeps its
        // classloader alive.
        node = null
        listener = null
        scope = null
    }
}

/** A writable root of the graph. */
internal class SourceState<T>(
    debugName: String,
    equality: (T, T) -> Boolean,
    initial: T,
) : StateNode<T>(debugName, equality), MutableUiState<T> {

    private var stored: T = initial

    override var value: T
        get() {
            UiThread.check()
            ReactiveGraph.recordRead(this)
            return stored
        }
        set(newValue) {
            UiThread.check()
            if (equality(stored, newValue)) return
            stored = newValue
            version = ReactiveGraph.nextVersion()
            ReactiveGraph.invalidate(this)
        }

    override fun currentValue(): T = stored
}

/** A computed node. Lazily recomputed, exactly as often as it has to be. */
internal class DerivedState<T>(
    debugName: String,
    equality: (T, T) -> Boolean,
    private val compute: () -> T,
) : StateNode<T>(debugName, equality) {

    private var cached: Any? = ReactiveGraph.Unset
    private var stale: Boolean = true

    /** Source node to the version it had when this derivation last read it. */
    private val dependencies = LinkedHashMap<StateNode<*>, Long>()

    override val value: T
        get() {
            UiThread.check()
            val result = currentValue()
            // Recorded after evaluating, so the consumer captures our settled version.
            ReactiveGraph.recordRead(this)
            return result
        }

    /** @return true if this call is what made the node stale. */
    fun markStale(): Boolean {
        if (stale) return false
        stale = true
        return true
    }

    fun addDependency(node: StateNode<*>) {
        dependencies[node] = node.version
        node.dependents.add(this)
    }

    @Suppress("UNCHECKED_CAST")
    override fun currentValue(): T {
        if (!stale && cached !== ReactiveGraph.Unset) return cached as T
        if (cached !== ReactiveGraph.Unset && !dependenciesChanged()) {
            stale = false
            return cached as T
        }
        return recompute()
    }

    private fun dependenciesChanged(): Boolean {
        if (dependencies.isEmpty()) return false
        for ((node, seenVersion) in dependencies) {
            // Settle derived dependencies first: their version moves only when they
            // recompute to a genuinely different value, so this is what turns an
            // "A changed" write into "nothing downstream of A actually changed".
            node.currentValue()
            if (node.version != seenVersion) return true
        }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    private fun recompute(): T {
        val stack = ReactiveGraph.evaluationStack
        val existing = stack.indexOf(this)
        if (existing >= 0) {
            throw StateCycleException(stack.subList(existing, stack.size).map { it.debugName } + debugName)
        }

        detachDependencies()
        stack.add(this)
        val computed = try {
            compute()
        } finally {
            stack.removeAt(stack.size - 1)
        }

        stale = false
        val previous = cached
        if (previous === ReactiveGraph.Unset || !equality(previous as T, computed)) {
            cached = computed
            version = ReactiveGraph.nextVersion()
        }
        return computed
    }

    private fun detachDependencies() {
        for (node in dependencies.keys) node.dependents.remove(this)
        dependencies.clear()
    }
}

/** A value that never changes; observing it can never fire. */
internal class ConstantState<T>(private val constant: T) : UiState<T> {

    override val debugName: String get() = "constant"

    override val value: T get() = constant

    override fun peek(): T = constant

    override fun observe(scope: DisposableScope, listener: (T) -> Unit): Subscription = InertSubscription

    private object InertSubscription : Subscription {
        override val isDisposed: Boolean get() = true
        override fun dispose() = Unit
    }
}
