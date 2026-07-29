package dev.th7bo.sidequest.platform.scheduler

import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.lifecycle.Registration
import kotlin.time.Duration

/**
 * All timing and threading in the mod.
 *
 * Features never touch a thread, a timer or a coroutine dispatcher directly. Everything
 * they schedule is owned, so unloading a feature cancels its in-flight work instead of
 * letting a repeating task keep firing against state that has been torn down — the
 * single most common way a mod like this leaks.
 *
 * Implementations must guarantee that [onMain] work runs on the Minecraft client thread
 * and that nothing else does.
 */
public interface Scheduler {

    /** True when the caller is already on the Minecraft client thread. */
    public val isOnMainThread: Boolean

    /**
     * Runs [block] on the client thread.
     *
     * Runs inline when the caller is already there, so a listener that is already on the
     * right thread does not pay a frame of latency to find that out.
     */
    public fun onMain(owner: OwnerId, block: () -> Unit): Registration

    /** Runs [block] off-thread. Must not touch Minecraft state. */
    public fun async(owner: OwnerId, block: suspend () -> Unit): Registration

    /** Runs [block] once, after [delay]. */
    public fun after(
        owner: OwnerId,
        delay: Duration,
        thread: SchedulerThread = SchedulerThread.MAIN,
        block: () -> Unit,
    ): Registration

    /**
     * Runs [block] every [period] until cancelled.
     *
     * A run that overruns its period does not stack: the next run is skipped rather than
     * queued, because a slow repeating task that queues turns a hitch into a spiral.
     */
    public fun every(
        owner: OwnerId,
        period: Duration,
        initialDelay: Duration = period,
        thread: SchedulerThread = SchedulerThread.MAIN,
        block: () -> Unit,
    ): Registration

    /**
     * Coalesces rapid calls: [block] runs [delay] after the *last* call.
     *
     * For reacting to something that changes in bursts — a scoreboard rewriting several
     * lines in one tick should produce one recomputation, not five.
     */
    public fun debounce(
        owner: OwnerId,
        delay: Duration,
        thread: SchedulerThread = SchedulerThread.MAIN,
        block: () -> Unit,
    ): Debounced

    /**
     * Rate-limits calls: [block] runs at most once per [interval].
     *
     * The first call runs immediately; calls within the interval are dropped, not queued.
     * For anything with a cost per invocation — a sound, a network write, a toast.
     */
    public fun throttle(
        owner: OwnerId,
        interval: Duration,
        thread: SchedulerThread = SchedulerThread.MAIN,
        block: () -> Unit,
    ): Throttled

    /** Cancels everything [owner] scheduled. Called by the feature registry on unload. */
    public fun cancelAll(owner: OwnerId)

    /** Live job count, for the inspector and for leak assertions in tests. */
    public fun jobCount(): Int
}

/** Where scheduled work runs. */
public enum class SchedulerThread {
    /** The Minecraft client thread. Required for anything touching the game. */
    MAIN,

    /** A background thread. Required for anything slow. */
    ASYNC,
}

/** Handle for a debounced call site. */
public interface Debounced : Registration {
    /** Restarts the delay. */
    public fun trigger()

    /** Runs now if a call is pending, and clears it. */
    public fun flush()

    /** Drops a pending call without running it. */
    public fun discard()

    public val isPending: Boolean
}

/** Handle for a throttled call site. */
public interface Throttled : Registration {
    /** Runs if the interval has elapsed; otherwise drops the call. @return whether it ran. */
    public fun trigger(): Boolean

    /** Number of calls dropped so far. Surfaced rather than hidden. */
    public val droppedCount: Int
}
