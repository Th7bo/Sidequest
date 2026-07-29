package dev.th7bo.sidequest.platform.core.scheduler

import dev.th7bo.sidequest.platform.core.log.LoggerFactory
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.log.LogCategory
import dev.th7bo.sidequest.platform.scheduler.RetriesExhaustedException
import dev.th7bo.sidequest.platform.scheduler.RetryPolicy
import dev.th7bo.sidequest.platform.scheduler.SchedulerThread
import dev.th7bo.sidequest.platform.scheduler.withRetry
import dev.th7bo.sidequest.platform.testkit.RecordingLogSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerTest {

    private val owner = OwnerId(SqId.sidequest("test"))
    private val other = OwnerId(SqId.sidequest("other"))

    private val sink = RecordingLogSink()
    private val log = LoggerFactory(sink).create(LogCategory.PLATFORM, SqId.sidequest("scheduler"))

    /** Blocks the fake client thread has been handed, run by [drainMain]. */
    private val mainQueue = ArrayDeque<() -> Unit>()
    private var onMainThread = false

    private fun drainMain() {
        while (mainQueue.isNotEmpty()) mainQueue.removeFirst()()
    }

    /**
     * Builds a scheduler on the test dispatcher and guarantees it is torn down.
     *
     * The teardown is not optional. `runTest` drives the dispatcher to idle once the body
     * returns, and a repeating job never becomes idle — leaving one running hangs the
     * whole test run instead of failing one test, which is a considerably worse way to
     * find out.
     */
    private fun TestScope.withScheduler(block: (DefaultScheduler) -> Unit) {
        val scheduler = DefaultScheduler(
            mainThreadExecutor = { mainQueue.addLast(it) },
            onMainThreadCheck = { onMainThread },
            log = log,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            block(scheduler)
        } finally {
            scheduler.cancelAll(owner)
            scheduler.cancelAll(other)
        }
    }

    // ---------------------------------------------------------------
    // Main-thread dispatch
    // ---------------------------------------------------------------

    @Test
    fun `onMain runs inline when already on the client thread`() = runTest {
        onMainThread = true
        withScheduler { scheduler ->
            var ran = false

            scheduler.onMain(owner) { ran = true }

            assertTrue(ran, "deferring would cost a tick of latency and reorder work for nothing")
            assertTrue(mainQueue.isEmpty())
        }
    }

    @Test
    fun `onMain defers when called off-thread`() = runTest {
        onMainThread = false
        withScheduler { scheduler ->
            var ran = false

            scheduler.onMain(owner) { ran = true }

            assertFalse(ran, "it must not run on the posting thread")
            drainMain()
            assertTrue(ran)
        }
    }

    @Test
    fun `a deferred block cancelled before it runs does not run`() = runTest {
        onMainThread = false
        withScheduler { scheduler ->
            var ran = false

            scheduler.onMain(owner) { ran = true }.cancel()
            drainMain()

            assertFalse(ran)
        }
    }

    @Test
    fun `a failure in scheduled work is logged, not thrown at the client thread`() = runTest {
        onMainThread = true
        withScheduler { scheduler ->
            scheduler.onMain(owner) { error("boom") }

            assertEquals(1, sink.errors().size, "a feature must not be able to crash the tick loop")
        }
    }

    // ---------------------------------------------------------------
    // Delays and repeats
    // ---------------------------------------------------------------

    @Test
    fun `after runs once, when it is due`() = runTest {
        withScheduler { scheduler ->
            var runs = 0

            scheduler.after(owner, 500.milliseconds, SchedulerThread.ASYNC) { runs++ }

            advanceTimeBy(499)
            assertEquals(0, runs)
            advanceTimeBy(2)
            assertEquals(1, runs)
            advanceTimeBy(5_000)
            assertEquals(1, runs, "once means once")
        }
    }

    @Test
    fun `every repeats on its period`() = runTest {
        withScheduler { scheduler ->
            var runs = 0

            scheduler.every(owner, 100.milliseconds, thread = SchedulerThread.ASYNC) { runs++ }

            advanceTimeBy(350)
            assertEquals(3, runs)
        }
    }

    @Test
    fun `cancelling a repeat stops it`() = runTest {
        withScheduler { scheduler ->
            var runs = 0
            val handle = scheduler.every(owner, 100.milliseconds, thread = SchedulerThread.ASYNC) { runs++ }

            advanceTimeBy(250)
            handle.cancel()
            advanceTimeBy(1_000)

            assertEquals(2, runs)
        }
    }

    // ---------------------------------------------------------------
    // Ownership — the reason any of this is owned
    // ---------------------------------------------------------------

    @Test
    fun `cancelAll stops one owner's work and leaves the rest`() = runTest {
        withScheduler { scheduler ->
            var mine = 0
            var theirs = 0
            scheduler.every(owner, 100.milliseconds, thread = SchedulerThread.ASYNC) { mine++ }
            scheduler.every(other, 100.milliseconds, thread = SchedulerThread.ASYNC) { theirs++ }

            advanceTimeBy(150)
            scheduler.cancelAll(owner)
            advanceTimeBy(500)

            assertEquals(1, mine, "a repeating task outliving its feature is the classic mod leak")
            assertTrue(theirs > 1, "and one feature's teardown must not touch another's work")
        }
    }

    @Test
    fun `cancelAll also drops work queued for the client thread`() = runTest {
        onMainThread = false
        withScheduler { scheduler ->
            var ran = false

            scheduler.onMain(owner) { ran = true }
            scheduler.cancelAll(owner)
            drainMain()

            assertFalse(ran)
        }
    }

    @Test
    fun `jobCount returns to zero once everything is cancelled`() = runTest {
        withScheduler { scheduler ->
            scheduler.every(owner, 1.seconds, thread = SchedulerThread.ASYNC) {}
            scheduler.after(owner, 1.seconds, SchedulerThread.ASYNC) {}
            assertEquals(2, scheduler.jobCount())

            scheduler.cancelAll(owner)
            advanceUntilIdle()

            assertEquals(0, scheduler.jobCount(), "a non-zero count after teardown is a leak")
        }
    }

    @Test
    fun `one owner's failure does not cancel its own other jobs`() = runTest {
        // Supervisor scope: a feature with a repeating task and a one-off request should
        // not lose the repeating task because the request threw.
        withScheduler { scheduler ->
            var runs = 0
            scheduler.every(owner, 100.milliseconds, thread = SchedulerThread.ASYNC) { runs++ }
            scheduler.async(owner) { error("unrelated failure") }

            advanceTimeBy(250)

            assertEquals(2, runs)
        }
    }

    // ---------------------------------------------------------------
    // Debounce and throttle
    // ---------------------------------------------------------------

    @Test
    fun `debounce coalesces a burst into one run`() = runTest {
        withScheduler { scheduler ->
            var runs = 0
            val debounced = scheduler.debounce(owner, 100.milliseconds, SchedulerThread.ASYNC) { runs++ }

            repeat(5) {
                debounced.trigger()
                advanceTimeBy(20)
            }
            assertEquals(0, runs, "still settling")

            advanceTimeBy(200)
            assertEquals(1, runs, "a scoreboard rewriting five lines should cost one recomputation")
        }
    }

    @Test
    fun `flush runs a pending debounce now`() = runTest {
        withScheduler { scheduler ->
            var runs = 0
            val debounced = scheduler.debounce(owner, 1.seconds, SchedulerThread.ASYNC) { runs++ }

            debounced.trigger()
            advanceTimeBy(10)
            debounced.flush()

            assertEquals(1, runs)
            assertFalse(debounced.isPending)
        }
    }

    @Test
    fun `throttle runs the first call and drops the rest`() = runTest {
        withScheduler { scheduler ->
            var runs = 0
            val throttled = scheduler.throttle(owner, 1.seconds, SchedulerThread.ASYNC) { runs++ }

            assertTrue(throttled.trigger())
            assertFalse(throttled.trigger())
            assertFalse(throttled.trigger())

            assertEquals(1, runs)
            assertEquals(2, throttled.droppedCount, "drops are counted, not hidden")
        }
    }

    // ---------------------------------------------------------------
    // Retry
    // ---------------------------------------------------------------

    @Test
    fun `retry gives up after the configured attempts`() = runTest {
        var attempts = 0
        val thrown = assertThrows<RetriesExhaustedException> {
            withRetry(RetryPolicy(maxAttempts = 3, initialDelay = 1.milliseconds)) {
                attempts++
                error("still down")
            }
        }

        assertEquals(3, attempts)
        assertEquals(3, thrown.attempts)
    }

    @Test
    fun `retry stops as soon as it succeeds`() = runTest {
        var attempts = 0
        val result = withRetry(RetryPolicy(initialDelay = 1.milliseconds)) {
            attempts++
            if (attempts < 3) error("not yet")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `a failure the caller calls permanent is not retried`() = runTest {
        var attempts = 0
        assertThrows<IllegalArgumentException> {
            withRetry(
                policy = RetryPolicy(initialDelay = 1.milliseconds),
                isRetryable = { it !is IllegalArgumentException },
            ) {
                attempts++
                throw IllegalArgumentException("the token is wrong, and will still be wrong")
            }
        }
        assertEquals(1, attempts, "retrying a permanent failure is just a slower failure")
    }

    @Test
    fun `backoff grows and is capped`() {
        val policy = RetryPolicy(
            initialDelay = 100.milliseconds,
            multiplier = 2.0,
            maxDelay = 1.seconds,
            jitter = 0.0,
        )

        assertEquals(100.milliseconds, policy.delayBefore(2))
        assertEquals(200.milliseconds, policy.delayBefore(3))
        assertEquals(400.milliseconds, policy.delayBefore(4))
        assertEquals(1.seconds, policy.delayBefore(9), "capped rather than growing without bound")
    }

    @Test
    fun `jitter spreads retries out`() {
        // Without it every client that dropped at the same moment retries at the same
        // moment, and a backend that fell over gets to fall over again.
        val policy = RetryPolicy(initialDelay = 100.milliseconds, jitter = 0.5)
        val delays = (1..20).map { policy.delayBefore(2, Random(it)) }.toSet()

        assertTrue(delays.size > 1, "jitter produced identical delays: $delays")
        assertTrue(delays.all { it >= 100.milliseconds && it <= 150.milliseconds })
    }
}
