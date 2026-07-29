package dev.th7bo.sidequest.platform.core.event

import dev.th7bo.sidequest.platform.event.Cancellable
import dev.th7bo.sidequest.platform.event.DispatchMode
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventFailure
import dev.th7bo.sidequest.platform.event.EventMetadata
import dev.th7bo.sidequest.platform.event.EventPriority
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.event.EventTraceEntry
import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.lifecycle.Registration
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.scheduler.Scheduler
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

/**
 * The event bus.
 *
 * Two decisions shape the implementation:
 *
 * **Listeners are looked up by concrete event class, and the result is cached.** A post
 * has to find listeners registered for the event's own type *and* for every supertype,
 * which is a walk up the class hierarchy. Doing that on a 20 Hz tick event is exactly
 * the kind of cost that does not show up in a profile as one hot spot but as everything
 * being slightly slower, so the resolved list is memoised and invalidated on subscribe.
 *
 * **A listener that throws is recorded, not propagated.** One feature must not be able
 * to break another, and a post is usually made by an adapter with no useful way to
 * handle someone else's failure. Failures land in the trace and the log, where they can
 * be seen, rather than unwinding through Minecraft's tick loop.
 */
public class DefaultEventBus(
    private val scheduler: Scheduler,
    private val log: Logger,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Dispatches kept for the inspector. */
    private val traceCapacity: Int = DEFAULT_TRACE_CAPACITY,
) : EventBus {

    private class Entry(
        val type: KClass<out SidequestEvent>,
        val owner: OwnerId,
        val priority: EventPriority,
        val mode: DispatchMode,
        val listener: (SidequestEvent) -> Unit,
        /** Registration order within a priority, so dispatch is deterministic. */
        val sequence: Long,
    ) {
        @Volatile
        var isActive: Boolean = true
    }

    // Copy-on-write: dispatch reads this constantly and subscription is rare. It also
    // makes subscribing from inside a listener safe, which features do — a feature that
    // reacts to a join by listening for what follows would otherwise be a concurrent
    // modification.
    private val entries = CopyOnWriteArrayList<Entry>()

    /** Resolved listener lists per concrete event class. Dropped whenever entries change. */
    @Volatile
    private var dispatchCache: Map<KClass<out SidequestEvent>, List<Entry>> = emptyMap()

    private val sequence = AtomicLong(0)
    private val registrationOrder = AtomicLong(0)

    private val traceBuffer = ArrayDeque<EventTraceEntry>(traceCapacity)

    override var isTracing: Boolean = false

    @Suppress("UNCHECKED_CAST")
    override fun <T : SidequestEvent> subscribe(
        type: KClass<T>,
        owner: OwnerId,
        priority: EventPriority,
        mode: DispatchMode,
        listener: (T) -> Unit,
    ): Registration {
        val entry = Entry(
            type = type,
            owner = owner,
            priority = priority,
            mode = mode,
            listener = listener as (SidequestEvent) -> Unit,
            sequence = registrationOrder.getAndIncrement(),
        )
        entries.add(entry)
        dispatchCache = emptyMap()

        return Registration {
            // Marked inactive as well as removed: a dispatch already in flight holds a
            // snapshot of the list, and a listener cancelled by an earlier listener in
            // the same dispatch must not still be called.
            entry.isActive = false
            if (entries.remove(entry)) dispatchCache = emptyMap()
        }
    }

    override fun <T : SidequestEvent> post(event: T, source: EventSource): T {
        event.stamp(
            EventMetadata(
                sequence = sequence.getAndIncrement(),
                timestampMillis = clock(),
                source = source,
            ),
        )

        val listeners = listenersFor(event::class)
        if (listeners.isEmpty()) {
            if (isTracing) record(event, 0, false, 0, emptyList())
            return event
        }

        val startedAt = System.nanoTime()
        var invoked = 0
        var failures: MutableList<EventFailure>? = null

        for (entry in listeners) {
            if (!entry.isActive) continue

            // A cancelled event still reaches MONITOR listeners: they exist to record
            // what happened, and "it was cancelled" is part of what happened.
            if (event is Cancellable && event.isCancelled && entry.priority != EventPriority.MONITOR) {
                continue
            }

            invoked++
            try {
                dispatch(entry, event)
            } catch (thrown: Throwable) {
                val failure = EventFailure(entry.owner.value, event.eventName, thrown)
                (failures ?: ArrayList<EventFailure>().also { failures = it }).add(failure)
                log.error(thrown) { "${entry.owner} threw while handling ${event.eventName}" }
            }
        }

        if (isTracing) {
            record(
                event = event,
                listeners = invoked,
                cancelled = event is Cancellable && event.isCancelled,
                durationNanos = System.nanoTime() - startedAt,
                failures = failures.orEmpty(),
            )
        }
        return event
    }

    private fun dispatch(entry: Entry, event: SidequestEvent) {
        when (entry.mode) {
            DispatchMode.IMMEDIATE -> entry.listener(event)

            DispatchMode.MAIN ->
                // Inline when already on the client thread. Bouncing through the
                // scheduler regardless would delay by a tick and, worse, reorder MAIN
                // listeners relative to IMMEDIATE ones for no benefit.
                if (scheduler.isOnMainThread) {
                    entry.listener(event)
                } else {
                    scheduler.onMain(entry.owner) { deliverLater(entry, event) }
                }

            DispatchMode.ASYNC -> scheduler.async(entry.owner) { deliverLater(entry, event) }
        }
    }

    /**
     * Delivers outside the posting call.
     *
     * Failures cannot propagate to the poster from here — it has long returned — so they
     * are logged at the point they happen instead.
     */
    private fun deliverLater(entry: Entry, event: SidequestEvent) {
        if (!entry.isActive) return
        try {
            entry.listener(event)
        } catch (thrown: Throwable) {
            log.error(thrown) { "${entry.owner} threw while handling ${event.eventName} off the posting thread" }
        }
    }

    /**
     * Listeners for [type], including those registered for its supertypes.
     *
     * Ordered by priority, then registration order. Memoised per concrete class.
     */
    private fun listenersFor(type: KClass<out SidequestEvent>): List<Entry> {
        dispatchCache[type]?.let { return it }

        val javaType = type.java
        val resolved = entries
            .filter { it.type.java.isAssignableFrom(javaType) }
            .sortedWith(compareBy({ it.priority.ordinal }, { it.sequence }))

        // Rebuilt rather than mutated: the map is read without synchronisation, so it has
        // to be replaced atomically. Two threads racing here recompute the same answer.
        dispatchCache = dispatchCache + (type to resolved)
        return resolved
    }

    override fun unsubscribeAll(owner: OwnerId) {
        val removed = entries.filter { it.owner == owner }
        if (removed.isEmpty()) return
        removed.forEach { it.isActive = false }
        entries.removeAll(removed)
        dispatchCache = emptyMap()
    }

    override fun listenerCount(): Int = entries.size

    override fun trace(): List<EventTraceEntry> = synchronized(traceBuffer) { traceBuffer.toList() }

    private fun record(
        event: SidequestEvent,
        listeners: Int,
        cancelled: Boolean,
        durationNanos: Long,
        failures: List<EventFailure>,
    ) {
        val entry = EventTraceEntry(
            metadata = event.metadata,
            eventName = event.eventName,
            description = event.describe(),
            listenersInvoked = listeners,
            wasCancelled = cancelled,
            durationNanos = durationNanos,
            failures = failures,
        )
        synchronized(traceBuffer) {
            if (traceBuffer.size >= traceCapacity) traceBuffer.removeFirst()
            traceBuffer.addLast(entry)
        }
    }

    public companion object {
        public const val DEFAULT_TRACE_CAPACITY: Int = 256
    }
}
