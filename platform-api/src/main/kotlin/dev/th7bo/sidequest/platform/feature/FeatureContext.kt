package dev.th7bo.sidequest.platform.feature

import dev.th7bo.sidequest.platform.command.CommandRegistry
import dev.th7bo.sidequest.platform.command.CommandSpec
import dev.th7bo.sidequest.platform.event.DispatchMode
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventPriority
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.game.GameVersion
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.lifecycle.Registration
import dev.th7bo.sidequest.platform.lifecycle.RegistrationScope
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.scheduler.Debounced
import dev.th7bo.sidequest.platform.scheduler.Scheduler
import dev.th7bo.sidequest.platform.scheduler.SchedulerThread
import dev.th7bo.sidequest.platform.scheduler.Throttled
import kotlin.time.Duration

/**
 * Everything a running feature is allowed to touch.
 *
 * This is the only handle a feature gets, and it is deliberately narrow. A feature
 * cannot reach Minecraft, the filesystem, the network or another feature through it —
 * those arrive as services, each with its own boundary. What it can do is listen,
 * schedule, log and register commands, and every one of those is recorded against the
 * feature so that disabling it undoes the lot.
 *
 * The context is invalidated when the feature is disabled. Using it afterwards throws
 * rather than silently registering into a dead scope.
 */
public interface FeatureContext {

    public val descriptor: FeatureDescriptor

    public val owner: OwnerId get() = descriptor.owner

    /** The Minecraft version actually running. Already known to be in range. */
    public val gameVersion: GameVersion

    /** Prefixed with the feature id, so a log line always says who wrote it. */
    public val log: Logger

    /** Cancelled wholesale on disable. Add anything the helpers below do not cover. */
    public val scope: RegistrationScope

    // -- events -------------------------------------------------------------

    /** The bus, for posting. Prefer [listen] over subscribing directly. */
    public val events: EventBus

    /** Subscribes for as long as this feature is enabled. */
    public fun <T : SidequestEvent> listen(
        type: kotlin.reflect.KClass<T>,
        priority: EventPriority = EventPriority.NORMAL,
        mode: DispatchMode = DispatchMode.MAIN,
        listener: (T) -> Unit,
    ): Registration

    /** Posts an event. Source defaults to [EventSource.DERIVED] — it came from a feature. */
    public fun <T : SidequestEvent> post(event: T, source: EventSource = EventSource.DERIVED): T =
        events.post(event, source)

    // -- scheduling ---------------------------------------------------------

    /** The scheduler, for anything the helpers below do not cover. */
    public val scheduler: Scheduler

    public fun onMain(block: () -> Unit): Registration

    public fun async(block: suspend () -> Unit): Registration

    public fun after(
        delay: Duration,
        thread: SchedulerThread = SchedulerThread.MAIN,
        block: () -> Unit,
    ): Registration

    public fun every(
        period: Duration,
        initialDelay: Duration = period,
        thread: SchedulerThread = SchedulerThread.MAIN,
        block: () -> Unit,
    ): Registration

    public fun debounce(
        delay: Duration,
        thread: SchedulerThread = SchedulerThread.MAIN,
        block: () -> Unit,
    ): Debounced

    public fun throttle(
        interval: Duration,
        thread: SchedulerThread = SchedulerThread.MAIN,
        block: () -> Unit,
    ): Throttled

    // -- commands -----------------------------------------------------------

    /** The registry, for anything [command] does not cover. */
    public val commands: CommandRegistry

    /** Registers a client command for as long as this feature is enabled. */
    public fun command(spec: CommandSpec): Registration
}

/** Subscribes to [T], inferring the type. The form feature code uses. */
public inline fun <reified T : SidequestEvent> FeatureContext.listen(
    priority: EventPriority = EventPriority.NORMAL,
    mode: DispatchMode = DispatchMode.MAIN,
    noinline listener: (T) -> Unit,
): Registration = listen(T::class, priority, mode, listener)

/** Registers a command with a name and a handler. */
public fun FeatureContext.command(
    name: String,
    description: String = "",
    handler: (List<String>) -> Unit,
): Registration = command(CommandSpec(name, description = description, handler = handler))
