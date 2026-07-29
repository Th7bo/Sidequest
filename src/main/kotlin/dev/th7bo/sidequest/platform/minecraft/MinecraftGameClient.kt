package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.game.GameClient
import dev.th7bo.sidequest.platform.game.GameVersion
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

    internal fun onTick() {
        tickCounter++
    }
}
