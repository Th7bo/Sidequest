package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.chat.ChatKind
import dev.th7bo.sidequest.platform.chat.ChatMessage
import dev.th7bo.sidequest.platform.core.chat.DefaultChatParser
import dev.th7bo.sidequest.platform.log.Logger
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.event.Event
import net.minecraft.resources.Identifier
import net.minecraft.network.chat.Component

/**
 * Feeds the game's chat into the parser.
 *
 * The whole of the adapter's chat responsibility: take a component, render it the way the
 * parsers expect, and hand it over. Nothing here decides what a line *means* — that is the
 * parser's job, and keeping the two apart is why the rules can be tested with no game
 * running.
 *
 * **Observing, never blocking.** These hooks could cancel a message, and this one does not.
 * Chat filtering is a feature with a user-facing switch, and building it into the bridge
 * would put it beyond the reach of the settings screen and beyond the reach of a test.
 */
class MinecraftChatBridge(
    private val parser: DefaultChatParser,
    private val log: Logger,
) {

    private var installed = false

    /** Our own phase, ordered before everybody else's, so a cancelled message is still seen. */
    private val OBSERVE_FIRST = Identifier.fromNamespaceAndPath("sidequest", "observe")

    fun install() {
        if (installed) return
        installed = true

        // Observed on the *allow* hook rather than the delivered one, and in a phase ahead of everyone
        // else's.
        //
        // **A message another mod hides is still a message that happened.** SkyHanni replaces several of
        // Hypixel's lines with tidier versions of its own, and does it by cancelling the original — which
        // on Fabric means the delivered hook never fires for it at all, because the allow event stops at
        // the first listener that says no. A pest spawning is the clearest case: the line is there, the
        // player sees a version of it, and a mod listening after the fact sees nothing.
        //
        // This never blocks anything. It looks and returns true, always, so ordering ahead of other mods
        // buys visibility and costs them nothing.
        ClientReceiveMessageEvents.ALLOW_GAME.addPhaseOrdering(OBSERVE_FIRST, Event.DEFAULT_PHASE)
        ClientReceiveMessageEvents.ALLOW_GAME.register(OBSERVE_FIRST) { message, overlay ->
            accept(message, if (overlay) ChatKind.OVERLAY else ChatKind.SYSTEM)
            true
        }
        // The signed-player hook stays as it was. Hypixel sends almost nothing this way, and a player's
        // typed message is not something another mod hides.
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            accept(message, ChatKind.PLAYER)
        }
        log.debug { "Chat bridge installed" }
    }

    /**
     * Hands one component to the parser.
     *
     * Failures are swallowed with a log line rather than propagated. This runs inside the
     * game's message pipeline, and an exception escaping here would break the player's chat
     * — a far worse outcome than a rule missing a line.
     */
    private fun accept(message: Component, kind: ChatKind) {
        try {
            parser.onMessage(
                ChatMessage.of(
                    raw = message.toLegacyFormatting(),
                    text = message.toSq(),
                    kind = kind,
                ),
            )
        } catch (thrown: Throwable) {
            log.error(thrown) { "Failed to parse a chat message" }
        }
    }
}
