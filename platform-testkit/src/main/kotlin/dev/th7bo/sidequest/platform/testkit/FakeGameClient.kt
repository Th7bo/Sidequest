package dev.th7bo.sidequest.platform.testkit

import dev.th7bo.sidequest.platform.game.GameClient
import dev.th7bo.sidequest.platform.game.GameLifecycle
import dev.th7bo.sidequest.platform.game.GameVersion
import dev.th7bo.sidequest.platform.game.PlayerVitals
import dev.th7bo.sidequest.platform.text.SqText
import java.util.UUID

/**
 * A client that is entirely under the test's control.
 *
 * This is the point of the [GameClient] interface: everything above it can be tested at
 * full speed with no game running, and the only code that needs a real client is the
 * adapter itself — which is what the in-game tests are for.
 */
public class FakeGameClient(
    override var version: GameVersion = GameVersion(26, 2),
    override var isOnClientThread: Boolean = true,
    override var localPlayerId: UUID? = UUID.fromString("00000000-0000-0000-0000-000000000001"),
    override var localPlayerName: String? = "Tester",
    override var isInGame: Boolean = true,
    override var serverAddress: String? = "mc.hypixel.net",
    override var isScreenOpen: Boolean = false,
    /** Healthy by default, so a test that does not care about vitals does not have to say so. */
    override var vitals: PlayerVitals = PlayerVitals.Healthy,
    override var tickCount: Long = 0,
) : GameClient {

    /** Blocks handed over while off-thread. Run them with [runSubmitted]. */
    public val submitted: ArrayDeque<() -> Unit> = ArrayDeque()

    /** Messages the mod tried to show locally. */
    public val clientMessages: MutableList<SqText> = ArrayList()

    /**
     * Commands the mod tried to send to the server.
     *
     * Worth asserting on: everything sent to Hypixel on the player's behalf should be
     * something a test decided to allow.
     */
    public val serverCommands: MutableList<String> = ArrayList()

    override fun submitToClientThread(block: () -> Unit) {
        if (isOnClientThread) block() else submitted.addLast(block)
    }

    override fun sendClientMessage(text: SqText) {
        clientMessages.add(text)
    }

    override fun runServerCommand(command: String) {
        serverCommands.add(command)
    }

    public fun runSubmitted() {
        while (submitted.isNotEmpty()) submitted.removeFirst()()
    }

    /** Plain text of everything shown locally, for readable assertions. */
    public fun clientMessageText(): List<String> = clientMessages.map { it.plainText }
}

/** A lifecycle whose callbacks the test fires by hand. */
public class FakeGameLifecycle : GameLifecycle {

    private val tickListeners = ArrayList<() -> Unit>()
    private val joinListeners = ArrayList<(String?) -> Unit>()
    private val disconnectListeners = ArrayList<() -> Unit>()
    private val shutdownListeners = ArrayList<() -> Unit>()

    override fun onClientTick(listener: () -> Unit): AutoCloseable = add(tickListeners, listener)

    override fun onJoin(listener: (String?) -> Unit): AutoCloseable = add(joinListeners, listener)

    override fun onDisconnect(listener: () -> Unit): AutoCloseable = add(disconnectListeners, listener)

    override fun onShutdown(listener: () -> Unit): AutoCloseable = add(shutdownListeners, listener)

    public fun fireTick(): Unit = tickListeners.toList().forEach { it() }

    public fun fireJoin(serverAddress: String? = "mc.hypixel.net"): Unit =
        joinListeners.toList().forEach { it(serverAddress) }

    public fun fireDisconnect(): Unit = disconnectListeners.toList().forEach { it() }

    public fun fireShutdown(): Unit = shutdownListeners.toList().forEach { it() }

    /** Live callbacks, so a test can prove the adapter unhooked itself. */
    public fun listenerCount(): Int =
        tickListeners.size + joinListeners.size + disconnectListeners.size + shutdownListeners.size

    private fun <T> add(into: MutableList<T>, listener: T): AutoCloseable {
        into.add(listener)
        return AutoCloseable { into.remove(listener) }
    }
}
