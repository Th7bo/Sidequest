package dev.th7bo.sidequest.ui.state

/**
 * The single thread that owns the reactive graph, the node tree and input dispatch.
 *
 * In Minecraft this is the client thread; in tests it is the test thread. Nothing in
 * `ui-api` or `ui-core` knows which — it is bound on first use and checked on every
 * state read and write.
 *
 * There is no "mostly works from another thread" mode. Cross-thread updates go through
 * [UiScheduler.submit] and nothing else.
 */
public object UiThread {

    @Volatile
    private var owner: Thread? = null

    /** The bound thread, or null if nothing has touched the graph yet. */
    public val boundThread: Thread? get() = owner

    /** True if the calling thread owns the UI. */
    public val isCurrent: Boolean get() = owner === Thread.currentThread()

    /**
     * Binds the UI thread explicitly. Idempotent for the same thread.
     *
     * @throws IllegalStateException if a *different* thread is already bound. Rebinding
     * silently would turn a real threading bug into an intermittent one.
     */
    public fun bind(thread: Thread = Thread.currentThread()) {
        val current = owner
        check(current == null || current === thread) {
            "UI thread is already bound to '${current?.name}', cannot rebind to '${thread.name}'"
        }
        owner = thread
    }

    /**
     * Releases the binding. Intended for test teardown; calling this while a UI is live
     * is a bug.
     */
    public fun unbind() {
        owner = null
    }

    /**
     * Asserts the caller owns the UI thread, binding it on first use.
     *
     * @throws WrongThreadException if another thread already owns the UI.
     */
    public fun check() {
        val current = owner
        if (current == null) {
            owner = Thread.currentThread()
            return
        }
        if (current !== Thread.currentThread()) {
            throw WrongThreadException(current, Thread.currentThread())
        }
    }
}

/** Thrown when UI state is touched from a thread that does not own it. */
public class WrongThreadException(
    public val expected: Thread,
    public val actual: Thread,
) : IllegalStateException(
    "UI state may only be accessed from '${expected.name}', but was accessed from '${actual.name}'. " +
        "Use UiScheduler.submit { } to hand work back to the UI thread.",
)

/**
 * Hands work from a background thread to the UI thread.
 *
 * Implementations run submitted blocks at a well-defined point in the frame — never
 * re-entrantly in the middle of layout or paint.
 */
public fun interface UiScheduler {

    /**
     * Queues [block] to run on the UI thread at the start of the next frame.
     *
     * Safe to call from any thread. Returns immediately; [block] has not run yet.
     */
    public fun submit(block: () -> Unit)
}
