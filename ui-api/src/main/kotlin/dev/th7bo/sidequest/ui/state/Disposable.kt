package dev.th7bo.sidequest.ui.state

/** Something that releases resources or registrations when it is no longer needed. */
public fun interface Disposable {
    public fun dispose()
}

/** A listener registration. Disposing it detaches the listener. */
public interface Subscription : Disposable {
    /** True once [dispose] has run. Disposing twice is a no-op, not an error. */
    public val isDisposed: Boolean
}

/**
 * Owns a set of [Disposable]s and releases them together.
 *
 * Every subscription and every registration in the framework requires a scope. There
 * is deliberately no way to observe state or register an extension without naming an
 * owner — that is what makes "unload this module and leave nothing behind" mechanical
 * rather than aspirational.
 *
 * Not thread safe; scopes belong to the UI thread like everything else in this package.
 */
public open class DisposableScope : Disposable {

    private val children = ArrayList<Disposable>()

    /** True once [dispose] has run. */
    public var isDisposed: Boolean = false
        private set

    /** The number of live registrations. Exposed for leak assertions in tests. */
    public val size: Int get() = children.size

    /**
     * Takes ownership of [disposable].
     *
     * @throws IllegalStateException if this scope has already been disposed — silently
     * dropping a registration into a dead scope is how leaks and ghost listeners start.
     */
    public fun register(disposable: Disposable): Disposable {
        check(!isDisposed) { "Cannot register into a disposed scope" }
        children.add(disposable)
        return disposable
    }

    /** Releases [disposable] early, without waiting for the whole scope to end. */
    public fun unregister(disposable: Disposable) {
        children.remove(disposable)
    }

    /**
     * Disposes every child, most recently registered first. Failures are collected so
     * that one bad disposer cannot strand the rest; the first is rethrown afterwards
     * with the others attached as suppressed exceptions.
     */
    override fun dispose() {
        if (isDisposed) return
        isDisposed = true

        var failure: Throwable? = null
        for (index in children.indices.reversed()) {
            try {
                children[index].dispose()
            } catch (throwable: Throwable) {
                if (failure == null) failure = throwable else failure.addSuppressed(throwable)
            }
        }
        children.clear()
        if (failure != null) throw failure
    }
}
