package dev.th7bo.sidequest.platform.event

import dev.th7bo.sidequest.platform.id.SqId

/**
 * Base class for everything on the bus.
 *
 * A class rather than an interface so metadata cannot be forgotten: every event carries
 * when it happened and where it came from, which is what makes the trace in the
 * developer inspector worth reading and what lets the sync layer expire stale events
 * instead of replaying them into a session hours later.
 *
 * Events are values. An event instance must not be mutated by a listener except through
 * [Cancellable], and must not be retained past the dispatch that delivered it — hold the
 * data, not the event.
 */
public abstract class SidequestEvent {

    /** Stamped by the bus at post time. [EventMetadata.PENDING] before that. */
    public var metadata: EventMetadata = EventMetadata.PENDING
        private set

    /**
     * Called by the bus. Not by anything else.
     *
     * Public because the bus lives in another module, and enforced by a once-only check
     * rather than by visibility. That check is the more useful guarantee anyway: the real
     * mistake is re-posting an event instance, which would silently rewrite the timestamp
     * a listener already recorded and make two dispatches indistinguishable in the trace.
     */
    public fun stamp(metadata: EventMetadata) {
        check(this.metadata === EventMetadata.PENDING) {
            "$eventName has already been posted. Events are values — build a new one rather " +
                "than re-posting this instance."
        }
        this.metadata = metadata
    }

    /** Human-readable name used in traces and the inspector. */
    public open val eventName: String get() = this::class.simpleName ?: "Event"

    /** Extra detail for the trace. Override where a bare class name is not enough. */
    public open fun describe(): String = eventName
}

/** Where an event came from and when. */
public data class EventMetadata(
    /** Monotonically increasing within a session, so a trace can be ordered exactly. */
    public val sequence: Long,
    /** Wall clock, epoch milliseconds. For expiry and for anything shown to a user. */
    public val timestampMillis: Long,
    /** What produced it: a parser, an adapter, the backend, a simulation. */
    public val source: EventSource,
) {
    public companion object {
        /** Placeholder on an event that has not been posted yet. */
        public val PENDING: EventMetadata = EventMetadata(-1, 0, EventSource.UNKNOWN)
    }
}

/**
 * What produced an event.
 *
 * Recorded rather than inferred because it changes how an event should be treated: an
 * event that came from a simulation must never reach the backend, and one that arrived
 * *from* the backend must not be echoed back to it.
 */
public enum class EventSource {
    /** A Minecraft adapter: a lifecycle hook, a tick, an input. */
    GAME,

    /** A parser reading chat, the scoreboard or the tab list. */
    PARSER,

    /** Derived by the platform from other events, e.g. the game context service. */
    DERIVED,

    /** Arrived over the realtime protocol. Never re-published to the backend. */
    REMOTE,

    /** Produced by the developer inspector or a test. Never reaches the backend. */
    SIMULATED,

    UNKNOWN,
    ;

    /** Whether an event from this source may be forwarded to the backend. */
    public val isTrustedForSync: Boolean
        get() = this == GAME || this == PARSER || this == DERIVED
}

/**
 * An event a listener can stop.
 *
 * Deliberately opt-in. Most events are statements of fact — a player *did* die — and
 * making those cancellable would invite listeners to pretend otherwise. Cancellation is
 * for events that represent an intention the mod has not acted on yet.
 */
public interface Cancellable {
    public var isCancelled: Boolean
}

/**
 * Delivery order.
 *
 * Listeners at the same priority run in registration order, which makes dispatch
 * deterministic and therefore testable. Reach for a non-default priority only when the
 * ordering is load-bearing, and say why at the registration site.
 */
public enum class EventPriority {
    /** Observes or vetoes before anything acts. Monitoring and permission checks. */
    FIRST,
    EARLY,
    NORMAL,
    LATE,

    /**
     * Runs after everything else, including cancelled dispatches.
     *
     * For recording what happened. A listener here must not change anything: by this
     * point other listeners have already acted on the state it would be changing.
     */
    MONITOR,
    ;

    public companion object {
        /** In dispatch order. */
        public val ORDER: List<EventPriority> = entries.toList()
    }
}

/**
 * Which thread a listener runs on.
 *
 * The default is deliberately the strictest one. A listener that touches Minecraft state
 * from the wrong thread produces a crash that reproduces once a week and never in a test,
 * so the platform makes the safe choice unless a feature opts out explicitly.
 */
public enum class DispatchMode {
    /**
     * On the posting thread, inline.
     *
     * The only mode that can cancel an event, because cancellation has to be decided
     * before the post returns.
     */
    IMMEDIATE,

    /**
     * On the Minecraft client thread, at the next opportunity.
     *
     * Use for anything that touches the game: the world, the player, a screen, a sound.
     * Cannot cancel — the dispatch has already returned by the time it runs.
     */
    MAIN,

    /**
     * Off-thread.
     *
     * Use for IO, parsing and anything slow. Must not touch Minecraft state. Cannot
     * cancel.
     */
    ASYNC,
    ;

    /** Whether a listener in this mode is able to influence the dispatch. */
    public val canCancel: Boolean get() = this == IMMEDIATE
}

/** One entry in the bus's ring buffer, for the inspector and for debugging. */
public data class EventTraceEntry(
    public val metadata: EventMetadata,
    public val eventName: String,
    public val description: String,
    public val listenersInvoked: Int,
    public val wasCancelled: Boolean,
    public val durationNanos: Long,
    /** Listeners that threw, by owner. Empty in the normal case. */
    public val failures: List<EventFailure>,
)

/** A listener that threw. Recorded rather than propagated, so one feature cannot break another. */
public data class EventFailure(
    public val owner: SqId,
    public val eventName: String,
    public val thrown: Throwable,
)
