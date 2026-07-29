package dev.th7bo.sidequest.platform.testkit

import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.lifecycle.Registration
import dev.th7bo.sidequest.platform.scheduler.Debounced
import dev.th7bo.sidequest.platform.scheduler.Scheduler
import dev.th7bo.sidequest.platform.scheduler.SchedulerThread
import dev.th7bo.sidequest.platform.scheduler.Throttled
import kotlin.time.Duration

/**
 * A scheduler driven by the test, not by a clock.
 *
 * Nothing runs until [advance] or [runPending] is called, so a test asserts *what* was
 * scheduled and *when* it fires without sleeping. A real scheduler in a test buys
 * flakiness and nothing else.
 *
 * [isOnMainThread] is true by default: the code under test is nearly always simulating
 * something that happens on the client thread, and a test that has to remember to say so
 * is a test that will forget.
 */
public class TestScheduler(
    override var isOnMainThread: Boolean = true,
) : Scheduler {

    private class Scheduled(
        val owner: OwnerId,
        var dueAtMillis: Long,
        val period: Duration?,
        val thread: SchedulerThread,
        val block: () -> Unit,
    ) {
        var isCancelled: Boolean = false
    }

    private val scheduled = ArrayList<Scheduled>()

    /** The clock the scheduler reads. Only [advance] moves it. */
    public var nowMillis: Long = 0
        private set

    /** Blocks handed to [onMain] while off-thread, awaiting [runPending]. */
    private val mainQueue = ArrayDeque<Pair<OwnerId, () -> Unit>>()

    /** Everything that has run, in order, for assertions about ordering. */
    public val executed: MutableList<String> = ArrayList()

    override fun onMain(owner: OwnerId, block: () -> Unit): Registration {
        if (isOnMainThread) {
            block()
            return Registration.None
        }
        val entry = owner to block
        mainQueue.addLast(entry)
        return Registration { mainQueue.remove(entry) }
    }

    /**
     * Runs an `async` block inline.
     *
     * Deliberately not on another thread: a test wants determinism, and the code under
     * test must not care which thread it got. Anything that genuinely needs concurrency
     * should be tested with `runTest` and a real dispatcher instead.
     */
    override fun async(owner: OwnerId, block: suspend () -> Unit): Registration {
        kotlinx.coroutines.runBlocking { block() }
        return Registration.None
    }

    override fun after(
        owner: OwnerId,
        delay: Duration,
        thread: SchedulerThread,
        block: () -> Unit,
    ): Registration = add(Scheduled(owner, nowMillis + delay.inWholeMilliseconds, null, thread, block))

    override fun every(
        owner: OwnerId,
        period: Duration,
        initialDelay: Duration,
        thread: SchedulerThread,
        block: () -> Unit,
    ): Registration = add(
        Scheduled(owner, nowMillis + initialDelay.inWholeMilliseconds, period, thread, block),
    )

    override fun debounce(
        owner: OwnerId,
        delay: Duration,
        thread: SchedulerThread,
        block: () -> Unit,
    ): Debounced = TestDebounced(owner, delay, block)

    override fun throttle(
        owner: OwnerId,
        interval: Duration,
        thread: SchedulerThread,
        block: () -> Unit,
    ): Throttled = TestThrottled(interval, block)

    override fun cancelAll(owner: OwnerId) {
        scheduled.filter { it.owner == owner }.forEach { it.isCancelled = true }
        scheduled.removeAll { it.owner == owner }
        mainQueue.removeAll { it.first == owner }
    }

    override fun jobCount(): Int = scheduled.count { !it.isCancelled } + mainQueue.size

    /** Moves the clock and runs everything that comes due, repeats included. */
    public fun advance(duration: Duration) {
        val target = nowMillis + duration.inWholeMilliseconds
        while (true) {
            val next = scheduled.filter { !it.isCancelled && it.dueAtMillis <= target }
                .minByOrNull { it.dueAtMillis } ?: break
            nowMillis = next.dueAtMillis
            if (next.period == null) {
                scheduled.remove(next)
            } else {
                next.dueAtMillis += next.period.inWholeMilliseconds
            }
            next.block()
        }
        nowMillis = target
    }

    /** Runs blocks queued for the client thread while [isOnMainThread] was false. */
    public fun runPending() {
        while (mainQueue.isNotEmpty()) mainQueue.removeFirst().second()
    }

    private fun add(entry: Scheduled): Registration {
        scheduled.add(entry)
        return Registration {
            entry.isCancelled = true
            scheduled.remove(entry)
        }
    }

    private inner class TestDebounced(
        private val owner: OwnerId,
        private val delay: Duration,
        private val block: () -> Unit,
    ) : Debounced {

        private var pending: Registration? = null

        override var isPending: Boolean = false
            private set

        override fun trigger() {
            pending?.cancel()
            isPending = true
            pending = after(owner, delay) {
                isPending = false
                block()
            }
        }

        override fun flush() {
            if (!isPending) return
            discard()
            block()
        }

        override fun discard() {
            pending?.cancel()
            pending = null
            isPending = false
        }

        override fun cancel(): Unit = discard()
    }

    private inner class TestThrottled(
        private val interval: Duration,
        private val block: () -> Unit,
    ) : Throttled {

        private var lastRunMillis: Long? = null

        override var droppedCount: Int = 0
            private set

        override fun trigger(): Boolean {
            val last = lastRunMillis
            if (last != null && nowMillis - last < interval.inWholeMilliseconds) {
                droppedCount++
                return false
            }
            lastRunMillis = nowMillis
            block()
            return true
        }

        override fun cancel() {
            // Nothing outstanding: a throttle holds no pending work by design.
        }
    }
}
