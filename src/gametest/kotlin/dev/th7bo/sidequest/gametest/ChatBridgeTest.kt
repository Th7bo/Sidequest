package dev.th7bo.sidequest.gametest

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.platform.chat.ChatChannel
import dev.th7bo.sidequest.platform.chat.ChatDerivedEvent
import dev.th7bo.sidequest.platform.chat.ChatMessageEvent
import dev.th7bo.sidequest.platform.chat.PartyInviteEvent
import dev.th7bo.sidequest.platform.chat.PlayerChatEvent
import dev.th7bo.sidequest.platform.event.DispatchMode
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component

/**
 * Chat, from the wire to a typed event.
 *
 * The rules are covered headlessly against recorded fixtures, and that coverage is worth
 * exactly nothing if the string the adapter builds is not the string the fixtures were
 * written against. Two steps sit between the two and no headless test can see either: the
 * game's message pipeline, and turning a component tree back into `§` codes.
 *
 * That second one is where a mistake would hide. Every fixture in the suite would still pass
 * if `toLegacyFormatting` emitted the wrong colour code, or dropped the codes entirely, or
 * put them after the text — and every single rule in the mod would silently stop matching.
 * So this builds a component the way Hypixel builds one, sends it from the server so it
 * arrives over the network like a real message, and checks a typed event comes out.
 */
class ChatBridgeTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        context.waitTicks(SETTLE_TICKS)

        context.worldBuilder().create().use { singleplayer ->
            context.waitTicks(SETTLE_TICKS)

            val derived = mutableListOf<ChatDerivedEvent>()
            val lines = mutableListOf<ChatMessageEvent>()
            val events = Sidequest.platform.events

            // IMMEDIATE, so the events are collected on the thread that posted them and
            // there is no scheduler round trip to wait for.
            val subscriptions = listOf(
                events.on<ChatDerivedEvent>(OWNER, mode = DispatchMode.IMMEDIATE) { derived.add(it) },
                events.on<ChatMessageEvent>(OWNER, mode = DispatchMode.IMMEDIATE) { lines.add(it) },
            )

            try {
                // 1. A party message, built the way Hypixel builds it: one styled run per
                //    colour. Reaching the parser means the whole conversion held up.
                send(singleplayer, partyMessage())
                context.waitTicks(SETTLE_TICKS)

                check(lines.isNotEmpty()) { "No chat line reached the parser at all" }
                val raw = lines.last().message.raw
                check(raw == EXPECTED_PARTY_RAW) {
                    "The component rendered as '$raw', not '$EXPECTED_PARTY_RAW'"
                }

                val chat = derived.filterIsInstance<PlayerChatEvent>().lastOrNull()
                checkNotNull(chat) { "The party line was not classified: $raw" }
                check(chat.channel == ChatChannel.PARTY) { "Read the channel as ${chat.channel}" }
                check(chat.sender == "nea89o") { "Read the sender as '${chat.sender}'" }
                check(chat.content == "peee") { "Read the message as '${chat.content}'" }

                // 2. A party invite, with the accept command behind a click. The point of
                //    carrying the structure through: the wording changes, the command does
                //    not, and only a real component has a click event on it.
                derived.clear()
                send(singleplayer, partyInvite())
                context.waitTicks(SETTLE_TICKS)

                val invite = derived.filterIsInstance<PartyInviteEvent>().lastOrNull()
                checkNotNull(invite) { "The invite was not classified: ${lines.last().message.raw}" }
                check(invite.inviter == "STPREAPER") { "Read the inviter as '${invite.inviter}'" }
                check(invite.acceptCommand == ACCEPT_COMMAND) {
                    "Read the accept command as '${invite.acceptCommand}'"
                }

                // 3. The built-in patterns still do what their own fixtures say. Checked here
                //    as well as headlessly, because patterns can be replaced at runtime and a
                //    replacement has not been through the test suite.
                val failures = Sidequest.platform.chat.verifyFixtures()
                check(failures.isEmpty()) { "Chat patterns failed their fixtures: $failures" }
            } finally {
                subscriptions.forEach { it.cancel() }
            }
        }

        context.waitTicks(SETTLE_TICKS)
    }

    /**
     * `§9Party §8> §7nea89o§f: peee`, as a component.
     *
     * One run per colour, which is how a server assembles a message and the reason the
     * flattened string is not enough on its own.
     */
    private fun partyMessage(): Component = Component.empty()
        .append(Component.literal("Party ").withStyle(ChatFormatting.BLUE))
        .append(Component.literal("> ").withStyle(ChatFormatting.DARK_GRAY))
        .append(Component.literal("nea89o").withStyle(ChatFormatting.GRAY))
        .append(Component.literal(": peee").withStyle(ChatFormatting.WHITE))

    /** An invite prompt with a real click event on the accept run. */
    private fun partyInvite(): Component = Component.empty()
        .append(Component.literal("[MVP+] STPREAPER ").withStyle(ChatFormatting.AQUA))
        .append(
            Component.literal("has invited you to join their party!")
                .withStyle { style ->
                    style.withColor(ChatFormatting.YELLOW)
                        .withClickEvent(ClickEvent.RunCommand(ACCEPT_COMMAND))
                },
        )

    /**
     * Sends [message] from the server, so it arrives at the client over the network.
     *
     * Not `player.sendSystemMessage` on the client: that displays a message without it ever
     * passing through the receive pipeline, which is precisely the part being tested.
     */
    private fun send(
        singleplayer: net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext,
        message: Component,
    ) {
        singleplayer.server.runOnServer<RuntimeException> { server ->
            server.playerList.broadcastSystemMessage(message, false)
        }
    }

    private companion object {
        val OWNER = OwnerId(SqId.sidequest("gametest.chat"))
        const val SETTLE_TICKS = 10
        const val ACCEPT_COMMAND = "/party accept STPREAPER"
        const val EXPECTED_PARTY_RAW = "§9Party §8> §7nea89o§f: peee"
    }
}
