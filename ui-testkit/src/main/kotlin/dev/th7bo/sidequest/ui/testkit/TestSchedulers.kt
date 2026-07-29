package dev.th7bo.sidequest.ui.testkit

import dev.th7bo.sidequest.ui.state.UiScheduler
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs submitted work immediately, on whatever thread submitted it.
 *
 * Convenient, but it deliberately does *not* model the real contract — production work
 * is deferred to the next frame. Use it only where the deferral is irrelevant to what
 * is being tested; prefer [ManualScheduler] when ordering matters.
 */
public class ImmediateScheduler : UiScheduler {

    public var submissionCount: Int = 0
        private set

    override fun submit(block: () -> Unit) {
        submissionCount++
        block()
    }
}

/**
 * Queues work until [drain] is called, exactly as the real runtime defers it to the
 * start of the next frame.
 *
 * This is what makes "did that result come back on the UI thread, and only then?"
 * an assertable question rather than a hope.
 */
public class ManualScheduler : UiScheduler {

    private val queue = ConcurrentLinkedQueue<() -> Unit>()

    /** Blocks submitted and not yet run. */
    public val pending: Int get() = queue.size

    public val isEmpty: Boolean get() = queue.isEmpty()

    /** Threads that have submitted work, so tests can assert the crossing happened. */
    private val submittingThreads = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    public val submitterThreadNames: Set<String> get() = submittingThreads.toSet()

    override fun submit(block: () -> Unit) {
        submittingThreads.add(Thread.currentThread().name)
        queue.add(block)
    }

    /**
     * Runs everything queued, on the calling thread.
     *
     * Work submitted *during* the drain is picked up too, so a chain of callbacks
     * settles in one call.
     *
     * @return how many blocks ran.
     */
    public fun drain(): Int {
        var count = 0
        while (true) {
            val block = queue.poll() ?: return count
            block()
            count++
        }
    }

    /**
     * Drains repeatedly until [condition] holds or [timeoutMillis] elapses.
     *
     * For waiting on a background coroutine to hand a result back without sleeping for
     * a fixed guess.
     */
    public fun drainUntil(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        pollMillis: Long = DEFAULT_POLL_MILLIS,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            drain()
            if (condition()) return true
            Thread.sleep(pollMillis)
        }
        drain()
        return condition()
    }

    public fun clear() {
        queue.clear()
        submittingThreads.clear()
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_POLL_MILLIS = 2L
    }
}

/** Latch helper for waiting on background work in tests without arbitrary sleeps. */
public class Signal {

    private val latch = CountDownLatch(1)

    public fun fire() {
        latch.countDown()
    }

    public fun await(timeoutMillis: Long = 5_000): Boolean =
        latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
}
