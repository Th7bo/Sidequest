package dev.th7bo.sidequest.platform.event

/**
 * Client lifecycle events.
 *
 * These are the events this pass can actually produce, so they are the ones that exist.
 * The rest of the catalogue in the plan — SkyBlock, dungeon, achievement, social — is
 * defined alongside the service that emits it, because an event whose payload nobody
 * fills is a guess at a data model, and guesses are what migrations are made of.
 */
public sealed class GameEvent : SidequestEvent()

/** Joined a world or server. */
public class MinecraftJoinEvent(
    /** Null in singleplayer. */
    public val serverAddress: String?,
) : GameEvent() {
    override fun describe(): String = "join ${serverAddress ?: "singleplayer"}"
}

/** Left a world or server, for any reason including a kick. */
public class MinecraftDisconnectEvent : GameEvent()

/**
 * One client tick.
 *
 * Posted at 20 Hz, so it is the one event where listener cost matters. Prefer the
 * scheduler's [dev.th7bo.sidequest.platform.scheduler.Scheduler.every] for anything that
 * does not genuinely need every tick — subscribing here to run something once a second
 * means 19 wasted dispatches out of every 20.
 */
public class ClientTickEvent(
    public val tick: Long,
) : GameEvent() {
    override fun describe(): String = "tick $tick"
}

/** The client is shutting down. The last event of a session. */
public class ClientShutdownEvent : GameEvent()

/**
 * A feature was enabled or disabled at runtime.
 *
 * Posted by the registry so features can react to each other's availability without
 * holding references — an optional dependency observes this instead of null-checking a
 * singleton on every use.
 */
public class FeatureStateChangedEvent(
    public val featureId: dev.th7bo.sidequest.platform.id.SqId,
    public val isEnabled: Boolean,
) : SidequestEvent() {
    override fun describe(): String = "$featureId ${if (isEnabled) "enabled" else "disabled"}"
}
