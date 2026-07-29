package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.game.GameLifecycle
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

/**
 * The Minecraft side of [GameLifecycle].
 *
 * Fabric's callbacks have no removal, so this registers one callback of each kind for
 * the whole session and fans out to its own listener lists. That inversion is what makes
 * the platform's listeners removable — without it, "unregister" would be a lie and every
 * feature reload would leave another dead callback in the game's list.
 */
class MinecraftGameLifecycle(
    private val client: MinecraftGameClient,
) : GameLifecycle {

    private val tickListeners = ArrayList<() -> Unit>()
    private val joinListeners = ArrayList<(String?) -> Unit>()
    private val disconnectListeners = ArrayList<() -> Unit>()
    private val shutdownListeners = ArrayList<() -> Unit>()

    private var installed = false

    /** Hooks the game's callbacks. Called once, from the mod initializer. */
    fun install() {
        check(!installed) { "The lifecycle adapter is already installed" }
        installed = true

        ScreenState.install()

        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick {
                client.onTick()
                // Copied before iterating: a listener that unsubscribes itself while the
                // tick is dispatching is normal, and at 20 Hz a concurrent modification
                // would be found by a player rather than by a test.
                fire(tickListeners)
            },
        )

        ClientPlayConnectionEvents.JOIN.register(
            ClientPlayConnectionEvents.Join { _, _, _ ->
                val address = client.serverAddress
                joinListeners.toList().forEach { it(address) }
            },
        )

        ClientPlayConnectionEvents.DISCONNECT.register(
            ClientPlayConnectionEvents.Disconnect { _, _ -> fire(disconnectListeners) },
        )

        ClientLifecycleEvents.CLIENT_STOPPING.register(
            ClientLifecycleEvents.ClientStopping { fire(shutdownListeners) },
        )
    }

    override fun onClientTick(listener: () -> Unit): AutoCloseable = add(tickListeners, listener)

    override fun onJoin(listener: (String?) -> Unit): AutoCloseable = add(joinListeners, listener)

    override fun onDisconnect(listener: () -> Unit): AutoCloseable = add(disconnectListeners, listener)

    override fun onShutdown(listener: () -> Unit): AutoCloseable = add(shutdownListeners, listener)

    /** Live callbacks, so the in-game test can prove the platform unhooked itself. */
    fun listenerCount(): Int =
        tickListeners.size + joinListeners.size + disconnectListeners.size + shutdownListeners.size

    private fun fire(listeners: List<() -> Unit>) {
        listeners.toList().forEach { it() }
    }

    private fun <T> add(into: MutableList<T>, listener: T): AutoCloseable {
        into.add(listener)
        return AutoCloseable { into.remove(listener) }
    }
}
