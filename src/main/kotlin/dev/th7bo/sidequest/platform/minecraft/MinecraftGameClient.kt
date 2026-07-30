package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.game.GameClient
import dev.th7bo.sidequest.platform.game.GameVersion
import dev.th7bo.sidequest.platform.game.PlayerVitals
import dev.th7bo.sidequest.platform.text.SqText
import net.minecraft.client.Minecraft
import java.util.UUID

/**
 * The Minecraft side of [GameClient].
 *
 * One of exactly two files in the mod that are allowed to know a Minecraft class exists
 * (the other is [MinecraftGameLifecycle]). Everything above the platform boundary is
 * written against the interface, so a version that renames `getConnection` changes this
 * file and nothing else.
 *
 * Deliberately thin. Every method is a translation and nothing more — the moment logic
 * appears here it becomes logic that cannot be tested without launching a game.
 */
class MinecraftGameClient(
    override val version: GameVersion,
) : GameClient {

    private val client: Minecraft get() = Minecraft.getInstance()

    override val isOnClientThread: Boolean get() = client.isSameThread

    override fun submitToClientThread(block: () -> Unit) {
        // Runs at the start of the next client tick, never re-entrantly inside a render
        // or tick already in progress.
        client.execute(block)
    }

    override val localPlayerId: UUID? get() = client.player?.uuid

    override val localPlayerName: String? get() = client.player?.gameProfile?.name

    override val isInGame: Boolean get() = client.level != null && client.player != null

    override val serverAddress: String?
        get() = client.currentServer?.ip ?: client.connection?.connection?.remoteAddress?.toString()

    /**
     * Whether a screen is open.
     *
     * Tracked rather than read: 26.x no longer exposes the current screen, so the
     * adapter follows the open/close events instead. See [ScreenState].
     */
    override val isScreenOpen: Boolean get() = ScreenState.isOpen

    /**
     * How the player is doing.
     *
     * Read fresh each time rather than cached, because everything asking is about to make a decision on it and
     * a health value from two seconds ago is exactly the value that gets somebody killed.
     *
     * `maxHealth` and not a constant: SkyBlock's maximum runs from 100 to tens of thousands, so an absolute
     * threshold would mean something different at each end. Damage is tracked over the last few ticks below —
     * the client has `hurtTime`, but it lasts ten ticks, which is short enough to miss between two reads.
     */
    override val vitals: PlayerVitals
        get() {
            val player = client.player ?: return PlayerVitals.Healthy
            val maximum = player.maxHealth
            return PlayerVitals(
                healthFraction = if (maximum <= 0f) 1f else (player.health / maximum).coerceIn(0f, 1f),
                isTakingDamage = tickCounter - lastHurtTick <= COMBAT_TICKS,
                // `isDeadOrDying` and not `isDead`: the death animation is still a moment nothing should cover,
                // and it is the part the player is looking at to see what killed them.
                isDead = player.isDeadOrDying,
            )
        }

    override fun sendClientMessage(text: SqText) {
        // Through the player, which is the client-side path — nothing is sent to the
        // server. With no player there is no chat to write to, and dropping the message
        // is better than the alternatives: this is only reachable on the title screen.
        client.player?.sendSystemMessage(text.toMinecraft())
    }

    override fun runServerCommand(command: String) {
        val connection = client.connection ?: return
        connection.sendCommand(command.removePrefix("/"))
    }

    override val tickCount: Long get() = tickCounter

    /**
     * Ticks since the client started.
     *
     * Counted here rather than read from Minecraft: what the game exposes is per-level
     * and resets when a world is left, which would make anything measuring elapsed time
     * across a server hop jump backwards.
     */
    internal var tickCounter: Long = 0
        private set

    /**
     * Whether the player has moved in the last few seconds.
     *
     * The only thing that separates "idle on a private island" from "building on one", and only the
     * client knows it. Compared against a squared threshold on the position rather than reading a
     * velocity: a player standing on a moving platform or in flowing water has a velocity and is not
     * doing anything.
     */
    internal val hasMovedRecently: Boolean
        get() = tickCounter - lastMovedTick <= STILL_TICKS

    private var lastMovedTick: Long = 0

    /** The last tick the player was seen hurt. See [vitals]. */
    private var lastHurtTick: Long = -COMBAT_TICKS
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastZ = 0.0

    internal fun onTick() {
        tickCounter++
        trackMovement()
        trackDamage()
    }

    /**
     * Remembers the last time the player was hurt.
     *
     * Polled rather than hooked, because a damage event would need a mixin and this does not. `hurtTime` counts
     * down from ten, so any non-zero reading means damage within half a second — sampled every tick, it cannot
     * be missed.
     */
    private fun trackDamage() {
        val player = client.player ?: return
        if (player.hurtTime > 0) lastHurtTick = tickCounter
    }

    private fun trackMovement() {
        val player = client.player ?: return
        val dx = player.x - lastX
        val dy = player.y - lastY
        val dz = player.z - lastZ
        if (dx * dx + dy * dy + dz * dz > MOVED_THRESHOLD_SQUARED) {
            lastMovedTick = tickCounter
        }
        lastX = player.x
        lastY = player.y
        lastZ = player.z
    }

    private companion object {
        /** How long a player has to be still before they count as idle. Five seconds. */
        const val STILL_TICKS = 100L

        /**
         * How far counts as moving, squared.
         *
         * Small, but not zero: a standing player's position wobbles by fractions of a block from
         * collision resolution alone, and a zero threshold would report constant movement.
         */
        const val MOVED_THRESHOLD_SQUARED = 0.0025

        /**
         * How long after being hurt a player still counts as in combat. Three seconds.
         *
         * Longer than `hurtTime`'s ten ticks on purpose: the question is "is something attacking me", and the
         * gap between two hits from the same mob is longer than the flash from one.
         */
        const val COMBAT_TICKS = 60L
    }
}
