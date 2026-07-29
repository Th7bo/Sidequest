package dev.th7bo.sidequest.platform.event

import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.lifecycle.Registration
import kotlin.reflect.KClass

/**
 * The one way features communicate.
 *
 * Features never call each other. A feature posts typed events and subscribes to typed
 * events, which is what stops the mod turning into a graph of direct references where
 * removing one feature breaks three others.
 *
 * Every subscription is owned. Unloading a feature drops its listeners without the
 * feature participating, so "I forgot to unregister" is not a failure mode that exists.
 */
public interface EventBus {

    /**
     * Registers [listener] for [type] and every subtype of it.
     *
     * Subtypes are included so a listener can take `SidequestEvent` to observe
     * everything, or a family base class to observe a category, without enumerating.
     */
    public fun <T : SidequestEvent> subscribe(
        type: KClass<T>,
        owner: OwnerId,
        priority: EventPriority = EventPriority.NORMAL,
        mode: DispatchMode = DispatchMode.MAIN,
        listener: (T) -> Unit,
    ): Registration

    /**
     * Posts [event] and returns it, so a caller can read what listeners decided.
     *
     * [DispatchMode.IMMEDIATE] listeners have all run by the time this returns; the
     * others have been handed to the scheduler. Posting from inside a listener is
     * allowed and dispatches immediately — the bus has no queue of its own, because a
     * queue would decouple cause from effect in exactly the traces meant to explain it.
     */
    public fun <T : SidequestEvent> post(event: T, source: EventSource = EventSource.GAME): T

    /** Everything registered by [owner] stops receiving events. */
    public fun unsubscribeAll(owner: OwnerId)

    /** Live listener count, for the inspector and for leak assertions in tests. */
    public fun listenerCount(): Int

    /** Most recent dispatches, oldest first. Bounded. */
    public fun trace(): List<EventTraceEntry>

    /** Turns tracing on or off. Off by default outside development. */
    public var isTracing: Boolean
}

/**
 * Registers a listener for [T], inferring the type.
 *
 * The form feature code uses: `bus.on<PlayerDeathEvent>(owner) { … }`.
 */
public inline fun <reified T : SidequestEvent> EventBus.on(
    owner: OwnerId,
    priority: EventPriority = EventPriority.NORMAL,
    mode: DispatchMode = DispatchMode.MAIN,
    noinline listener: (T) -> Unit,
): Registration = subscribe(T::class, owner, priority, mode, listener)
