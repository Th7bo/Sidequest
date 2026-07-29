package dev.th7bo.sidequest.platform.core.scheduler

import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.lifecycle.Registration
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.scheduler.Debounced
import dev.th7bo.sidequest.platform.scheduler.Scheduler
import dev.th7bo.sidequest.platform.scheduler.SchedulerThread
import dev.th7bo.sidequest.platform.scheduler.Throttled
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * The scheduler.
 *
 * Everything scheduled belongs to an owner and lives in that owner's [CoroutineScope],
 * so cancelling a feature is one `cancel()` rather than a walk over handles the feature
 * was trusted to keep. That is the whole design: ownership is structural, not
 * bookkeeping.
 *
 * Work bound for the client thread goes through [mainThreadExecutor], which the
 * Minecraft adapter supplies. Nothing here knows what a client thread is — it knows only
 * that some work must go somewhere specific, which is what lets the whole scheduler be
 * tested without a game.
 */
public class DefaultScheduler(
    /** Runs a block on the Minecraft client thread. Supplied by the adapter. */
    private val mainThreadExecutor: (() -> Unit) -> Unit,
    /** True when the caller is already on the client thread. */
    private val onMainThreadCheck: () -> Boolean,
    private val log: Logger,
    /** Where `async` work runs. Overridden in tests for determinism. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Scheduler {

    private class OwnerScope(owner: OwnerId, dispatcher: CoroutineDispatcher) {
        // Supervisor: one failed job must not cancel its siblings. A feature with a
        // repeating task and a one-off request should not lose the repeating task
        // because a single request threw.
        val job = SupervisorJob()
        val scope = CoroutineScope(job + dispatcher + CoroutineName(owner.toString()))
    }

    private val owners = ConcurrentHashMap<OwnerId, OwnerScope>()

    /** Live handles, for [jobCount] and for leak assertions. */
    private val live = ConcurrentHashMap.newKeySet<Registration>()

    override val isOnMainThread: Boolean get() = onMainThreadCheck()

    private fun scopeFor(owner: OwnerId): CoroutineScope =
        owners.computeIfAbsent(owner) { OwnerScope(it, ioDispatcher) }.scope

    override fun onMain(owner: OwnerId, block: () -> Unit): Registration {
        // Already on the client thread: run inline. Deferring would cost a tick of
        // latency for no gain, and would reorder work relative to the caller.
        if (isOnMainThread) {
            runGuarded(owner, block)
            return Registration.None
        }

        val handle = CancellableBlock(owner, block)
        track(handle)
        mainThreadExecutor {
            if (handle.claim()) runGuarded(owner, block)
            untrack(handle)
        }
        return handle
    }

    override fun async(owner: OwnerId, block: suspend () -> Unit): Registration {
        val job = scopeFor(owner).launch {
            try {
                block()
            } catch (thrown: Throwable) {
                if (thrown is kotlinx.coroutines.CancellationException) throw thrown
                log.error(thrown) { "$owner failed in an async job" }
            }
        }
        return job.asRegistration()
    }

    override fun after(
        owner: OwnerId,
        delay: Duration,
        thread: SchedulerThread,
        block: () -> Unit,
    ): Registration {
        val job = scopeFor(owner).launch {
            delay(delay)
            runOn(owner, thread, block)
        }
        return job.asRegistration()
    }

    override fun every(
        owner: OwnerId,
        period: Duration,
        initialDelay: Duration,
        thread: SchedulerThread,
        block: () -> Unit,
    ): Registration {
        require(period.isPositive()) { "A repeating period must be positive, was $period" }

        val job = scopeFor(owner).launch {
            delay(initialDelay)
            while (isActive) {
                // Awaited, not fired and forgotten: a run that overruns its period delays
                // the next one instead of stacking. A slow task that queued would turn a
                // hitch into a spiral that never recovers.
                runOnAwaiting(owner, thread, block)
                delay(period)
            }
        }
        return job.asRegistration()
    }

    override fun debounce(
        owner: OwnerId,
        delay: Duration,
        thread: SchedulerThread,
        block: () -> Unit,
    ): Debounced = DebouncedImpl(owner, delay, thread, block).also { track(it) }

    override fun throttle(
        owner: OwnerId,
        interval: Duration,
        thread: SchedulerThread,
        block: () -> Unit,
    ): Throttled = ThrottledImpl(owner, interval, thread, block).also { track(it) }

    override fun cancelAll(owner: OwnerId) {
        owners.remove(owner)?.scope?.cancel("Owner $owner was torn down")
        // Handles that are not coroutines — a pending `onMain`, a debounce — are not in
        // the scope, so they are swept separately.
        live.filterIsInstance<OwnedRegistration>()
            .filter { it.owner == owner }
            .forEach { it.cancel() }
    }

    override fun jobCount(): Int = live.size

    // -- internals ----------------------------------------------------------

    private fun runOn(owner: OwnerId, thread: SchedulerThread, block: () -> Unit) {
        when (thread) {
            SchedulerThread.MAIN -> onMain(owner, block)
            SchedulerThread.ASYNC -> runGuarded(owner, block)
        }
    }

    /** Like [runOn] but waits for the block to finish, so a repeat cannot overlap itself. */
    private suspend fun runOnAwaiting(owner: OwnerId, thread: SchedulerThread, block: () -> Unit) {
        when (thread) {
            SchedulerThread.ASYNC -> runGuarded(owner, block)
            SchedulerThread.MAIN -> {
                val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                onMain(owner) {
                    try {
                        block()
                    } finally {
                        done.complete(Unit)
                    }
                }
                done.await()
            }
        }
    }

    /** Runs [block], turning a failure into a log line rather than into someone else's crash. */
    private fun runGuarded(owner: OwnerId, block: () -> Unit) {
        try {
            block()
        } catch (thrown: Throwable) {
            if (thrown is kotlinx.coroutines.CancellationException) throw thrown
            log.error(thrown) { "$owner failed in a scheduled task" }
        }
    }

    private fun track(registration: Registration) {
        live.add(registration)
    }

    private fun untrack(registration: Registration) {
        live.remove(registration)
    }

    private fun Job.asRegistration(): Registration {
        val handle = object : Registration {
            override fun cancel() {
                this@asRegistration.cancel()
            }
        }
        track(handle)
        invokeOnCompletion { untrack(handle) }
        return handle
    }

    /** A registration that remembers whose it is, so [cancelAll] can find it. */
    private interface OwnedRegistration : Registration {
        val owner: OwnerId
    }

    /** A one-shot block that can be cancelled before the executor gets to it. */
    private class CancellableBlock(
        override val owner: OwnerId,
        @Suppress("unused") private val block: () -> Unit,
    ) : OwnedRegistration {

        private val claimed = java.util.concurrent.atomic.AtomicBoolean(false)

        /** @return true if this call won the right to run it. */
        fun claim(): Boolean = claimed.compareAndSet(false, true)

        override fun cancel() {
            claimed.set(true)
        }
    }

    private inner class DebouncedImpl(
        override val owner: OwnerId,
        private val delay: Duration,
        private val thread: SchedulerThread,
        private val block: () -> Unit,
    ) : Debounced, OwnedRegistration {

        private var pending: Job? = null

        override val isPending: Boolean get() = pending?.isActive == true

        override fun trigger() {
            pending?.cancel()
            pending = scopeFor(owner).launch {
                delay(this@DebouncedImpl.delay)
                runOn(owner, thread, block)
            }
        }

        override fun flush() {
            if (!isPending) return
            discard()
            runOn(owner, thread, block)
        }

        override fun discard() {
            pending?.cancel()
            pending = null
        }

        override fun cancel() {
            discard()
            untrack(this)
        }
    }

    private inner class ThrottledImpl(
        override val owner: OwnerId,
        private val interval: Duration,
        private val thread: SchedulerThread,
        private val block: () -> Unit,
    ) : Throttled, OwnedRegistration {

        private var lastRunNanos: Long? = null

        override var droppedCount: Int = 0
            private set

        override fun trigger(): Boolean {
            val now = System.nanoTime()
            val last = lastRunNanos
            if (last != null && now - last < interval.inWholeNanoseconds) {
                droppedCount++
                return false
            }
            lastRunNanos = now
            runOn(owner, thread, block)
            return true
        }

        override fun cancel() {
            untrack(this)
        }
    }
}
