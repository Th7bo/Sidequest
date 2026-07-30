package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.chat.ChatKind
import dev.th7bo.sidequest.platform.chat.ChatMessage
import dev.th7bo.sidequest.platform.core.chat.DefaultChatParser
import dev.th7bo.sidequest.platform.log.Logger
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
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

    fun install() {
        if (installed) return
        installed = true

        // Two hooks because the game distinguishes them, and the distinction is worth
        // keeping: a signed player message genuinely was typed by somebody, where a system
        // message is Hypixel talking. Nearly everything on Hypixel arrives as the latter.
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            accept(message, if (overlay) ChatKind.OVERLAY else ChatKind.SYSTEM)
        }
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
