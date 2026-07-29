package dev.th7bo.sidequest.platform.scheduler

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How to retry something that failed.
 *
 * Lives here rather than in the backend client because the same policy governs asset
 * downloads, the realtime connection and queued writes. One implementation means one
 * place where "how hard do we hammer a homelab that is down" is decided.
 */
public data class RetryPolicy(
    /** Total attempts including the first. 1 means no retry. */
    public val maxAttempts: Int = 4,
    public val initialDelay: Duration = 250.milliseconds,
    /** Each delay is the previous one times this. */
    public val multiplier: Double = 2.0,
    public val maxDelay: Duration = 30.seconds,
    /**
     * Random fraction added to each delay, in `0..1`.
     *
     * Without it, every client that lost the connection at the same moment retries at the
     * same moment, and a backend that fell over under load gets to fall over again.
     */
    public val jitter: Double = 0.2,
    /** Attempts that exceed this are abandoned rather than left hanging. */
    public val attemptTimeout: Duration? = null,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1, was $maxAttempts" }
        require(multiplier >= 1.0) { "multiplier must be at least 1, was $multiplier" }
        require(jitter in 0.0..1.0) { "jitter must be in 0..1, was $jitter" }
    }

    /** Delay before attempt [attempt], counting the first attempt as 1. */
    public fun delayBefore(attempt: Int, random: Random = Random.Default): Duration {
        require(attempt >= 2) { "There is no delay before the first attempt" }
        val exponent = (attempt - 2).coerceAtMost(MAX_EXPONENT)
        val base = initialDelay * multiplier.pow(exponent)
        val capped = if (base > maxDelay) maxDelay else base
        return capped + capped * (random.nextDouble() * jitter)
    }

    public companion object {
        /** Beyond this the delay is capped anyway, and the multiplication overflows. */
        private const val MAX_EXPONENT = 16

        /** For work that must not be retried, e.g. a non-idempotent write. */
        public val None: RetryPolicy = RetryPolicy(maxAttempts = 1)
    }
}

/** Every attempt failed. Carries the last failure, with the earlier ones suppressed. */
public class RetriesExhaustedException(
    public val attempts: Int,
    cause: Throwable,
) : Exception("Gave up after $attempts attempt(s)", cause)

/**
 * Runs [block], retrying per [policy].
 *
 * [isRetryable] decides what is worth retrying: a timeout is, a rejected token is not.
 * The default retries everything except cancellation, which must always propagate — a
 * feature unloading mid-request has to actually stop, not retry three more times first.
 */
public suspend fun <T> withRetry(
    policy: RetryPolicy = RetryPolicy(),
    random: Random = Random.Default,
    isRetryable: (Throwable) -> Boolean = { true },
    block: suspend (attempt: Int) -> T,
): T {
    var lastFailure: Throwable? = null

    for (attempt in 1..policy.maxAttempts) {
        if (attempt > 1) delay(policy.delayBefore(attempt, random))

        try {
            val timeout = policy.attemptTimeout
            return if (timeout == null) block(attempt) else withTimeout(timeout) { block(attempt) }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // A timeout on one attempt is a failure of that attempt; a cancellation from
            // outside is the caller telling us to stop. Only the former may be retried.
            if (cancellation !is TimeoutCancellationException) throw cancellation
            lastFailure = record(lastFailure, cancellation)
        } catch (thrown: Throwable) {
            if (!isRetryable(thrown)) throw thrown
            lastFailure = record(lastFailure, thrown)
        }
    }

    throw RetriesExhaustedException(policy.maxAttempts, lastFailure ?: IllegalStateException("No attempts ran"))
}

/** Keeps the most recent failure as the cause, with earlier ones attached. */
private fun record(previous: Throwable?, current: Throwable): Throwable {
    if (previous != null) current.addSuppressed(previous)
    return current
}
