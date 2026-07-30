package dev.th7bo.sidequest.platform.game

import dev.th7bo.sidequest.platform.text.SqText
import java.util.UUID

/**
 * The client, as much of it as the platform is allowed to see.
 *
 * This interface is the entire boundary between Sidequest and Minecraft. Nothing above
 * it imports a Minecraft class; everything below it is a per-version adapter. When a
 * version renames a method, exactly one file changes.
 *
 * Deliberately small. Every method here is one the platform has a use for *now* —
 * the temptation is to mirror `Minecraft` wholesale, which recreates the coupling this
 * interface exists to prevent, just with an extra hop.
 *
 * Unless stated otherwise, every member must be read from the client thread.
 */
public interface GameClient {

    /** The running version. Compared against each feature's supported range. */
    public val version: GameVersion

    /** True on the Minecraft client thread. Safe to call from any thread. */
    public val isOnClientThread: Boolean

    /** Runs [block] on the client thread at the start of the next tick. Any thread. */
    public fun submitToClientThread(block: () -> Unit)

    /** The local player's UUID, or null before login. */
    public val localPlayerId: UUID?

    /** The local player's name, or null before login. Display only — never a key. */
    public val localPlayerName: String?

    /** True while connected to a world or server. */
    public val isInGame: Boolean

    /** The current server address, or null in singleplayer or on the title screen. */
    public val serverAddress: String?

    /** True while a screen is open, so a feature can hold back anything intrusive. */
    public val isScreenOpen: Boolean

    /**
     * How the player is doing right now.
     *
     * Here rather than on the game context because it is an observation of the *client*, not of SkyBlock: the
     * context knows what island the player is on by reading Hypixel's own output, and this is read straight off
     * the player entity. Anything deciding whether to interrupt needs both.
     */
    public val vitals: PlayerVitals

    /** Sends a message to the local chat. Client-side only; nothing is sent to the server. */
    public fun sendClientMessage(text: SqText)

    /**
     * Sends [command] to the server as if typed, without the leading slash.
     *
     * Restricted to the platform's own services. A feature that wants to run a command
     * asks the service that owns that interaction, so "what does this mod send to
     * Hypixel" has a finite, reviewable answer.
     */
    public fun runServerCommand(command: String)

    /** Ticks since the client started. For coarse timing that must follow game time. */
    public val tickCount: Long
}

/**
 * How the player is doing, as much of it as anything above needs.
 *
 * Exists because "is now a bad moment to interrupt" cannot be answered from the SkyBlock context alone. Being
 * in the Crimson Isle is not dangerous; being in the Crimson Isle on two hearts with something hitting you is.
 * A cinematic that covers the screen at that moment is the mod killing the player.
 *
 * Deliberately not a mirror of the player entity. Three readings, each one a question something actually asks.
 */
public data class PlayerVitals(
    /**
     * Health as a fraction of maximum, or 1 when there is no player.
     *
     * A fraction rather than the number, because SkyBlock's maximum health runs from 100 to tens of thousands
     * and no absolute threshold means the same thing at both ends.
     */
    public val healthFraction: Float = 1f,
    /**
     * True shortly after taking damage.
     *
     * The closest honest reading of "in combat" available client-side. Hypixel does not tell the client it is
     * in combat, and inferring it from nearby mobs would be a guess dressed as a fact.
     */
    public val isTakingDamage: Boolean = false,
    public val isDead: Boolean = false,
) {

    /** Below a quarter. The point at which a covered screen is a real risk rather than an annoyance. */
    public val isLowHealth: Boolean get() = healthFraction < LOW_HEALTH_FRACTION

    public companion object {
        public const val LOW_HEALTH_FRACTION: Float = 0.25f

        /** A player who is fine. The default, and what a headless test gets unless it says otherwise. */
        public val Healthy: PlayerVitals = PlayerVitals()
    }
}

/**
 * Client-side lifecycle, as events the platform can subscribe to.
 *
 * Separate from [GameClient] because the shapes differ: one is state to read, the other
 * is a stream to observe, and an adapter for the second is mostly wiring into whatever
 * hook the loader provides on that version.
 */
public interface GameLifecycle {

    /** Registers a per-tick callback. Returns a handle that removes it. */
    public fun onClientTick(listener: () -> Unit): AutoCloseable

    /** Joined a world or server. */
    public fun onJoin(listener: (serverAddress: String?) -> Unit): AutoCloseable

    /** Left a world or server, for any reason including a kick. */
    public fun onDisconnect(listener: () -> Unit): AutoCloseable

    /** The client is shutting down. Last chance to flush anything to disk. */
    public fun onShutdown(listener: () -> Unit): AutoCloseable
}
