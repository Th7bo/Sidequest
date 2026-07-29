package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.core.context.DefaultGameContextService
import dev.th7bo.sidequest.platform.log.Logger
import net.fabricmc.loader.api.FabricLoader
import net.hypixel.data.type.GameType
import net.hypixel.modapi.HypixelModAPI
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket
import kotlin.jvm.optionals.getOrNull

/**
 * Location straight from Hypixel, over their own Mod API.
 *
 * The server sends a packet naming the game type, the island mode and the server
 * instance. That is authoritative in a way scraping never is: no formatting to strip, no
 * icon that changes with a texture pack, and it arrives the moment the player is moved
 * rather than whenever the scoreboard happens to redraw.
 *
 * **Optional.** The classes come from the `hypixel-mod-api` mod, and everything that
 * touches them sits behind [isAvailable]. Without it the context service falls back to
 * the scoreboard and tab list, at lower confidence — which is exactly what the confidence
 * levels are for.
 */
class HypixelModApiSource(
    private val context: DefaultGameContextService,
    private val log: Logger,
) {

    private var installed = false

    /**
     * Hooks the location packet.
     *
     * Failures are caught rather than propagated: this is an optional enhancement, and a
     * version mismatch in someone else's mod must not stop Sidequest from loading.
     */
    fun install() {
        if (installed) return
        if (!isAvailable) {
            log.info { "The Hypixel Mod API is not installed; falling back to reading the scoreboard" }
            return
        }

        try {
            val api = HypixelModAPI.getInstance()
            api.subscribeToEventPacket(ClientboundLocationPacket::class.java)
            api.createHandler(ClientboundLocationPacket::class.java, ::onLocation)
            installed = true
            log.info { "Using the Hypixel Mod API for location" }
        } catch (thrown: Throwable) {
            log.warn(thrown) { "Could not hook the Hypixel Mod API; falling back to reading the scoreboard" }
        }
    }

    /**
     * The server told us where the player is.
     *
     * `mode` is the island's api name — the same identifier [Island.ofApiName] resolves,
     * which is why the enum carries Hypixel's names rather than ones we made up.
     */
    private fun onLocation(packet: ClientboundLocationPacket) {
        val serverType = packet.serverType.getOrNull()
        val isSkyBlock = serverType == GameType.SKYBLOCK

        context.onHypixelLocation(
            isSkyBlock = isSkyBlock,
            islandApiName = packet.mode.getOrNull(),
            serverName = packet.serverName,
            isLobby = packet.lobbyName.getOrNull() != null,
        )
    }

    companion object {
        private const val MOD_ID = "hypixel-modapi"

        /** Whether the Mod API mod is present. Checked before any of its classes are touched. */
        val isAvailable: Boolean by lazy { FabricLoader.getInstance().isModLoaded(MOD_ID) }
    }
}
