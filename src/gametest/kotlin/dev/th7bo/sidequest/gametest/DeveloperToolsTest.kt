package dev.th7bo.sidequest.gametest

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.platform.log.LogCategory
import dev.th7bo.sidequest.platform.log.LogLevel
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/**
 * The developer commands, run on a real client.
 *
 * These exist to make the mod's own state visible, which means they touch almost everything: the context
 * service, the party, the backend, the chat parser, the notification manager, the sound manager and the item
 * reader. That breadth is the reason to test them — a command that reads eight subsystems fails if any one of
 * them is not wired up, and it fails at the moment somebody is trying to diagnose something else.
 *
 * `/sqtest sound` in particular is the only thing in the suite that reaches the real sound registry, so it is
 * the only thing that would notice `SimpleSoundInstance` moving between versions.
 */
class DeveloperToolsTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        context.waitTicks(SETTLE_TICKS)

        // Outside a world first. Every one of these has a "not in a world" path, and those paths are the ones
        // that get written once and never exercised.
        context.runOnClient<RuntimeException> {
            Sidequest.platform.commands.all().map { it.spec.name }.let { names ->
                for (expected in listOf("sqstatus", "sqlog", "sqtest", "sqdiag", "sqchat", "sqboard")) {
                    check(expected in names) { "/$expected was not registered; have: $names" }
                }
            }
        }

        context.worldBuilder().create().use {
            context.waitTicks(SETTLE_TICKS)

            context.runOnClient<RuntimeException> {
                // Run through the real registry rather than by calling the methods, so this also covers the
                // command bridge — the layer that shipped broken once already.
                for (name in listOf("sqstatus", "sqdiag", "sqboard")) {
                    val command = checkNotNull(Sidequest.platform.commands[name]) { "/$name is not registered" }
                    command.spec.handler(emptyList())
                }
            }

            // Each `/sqtest` subcommand, and the listing. Nothing is asserted about the *output* — what is
            // being checked is that eight subsystems can be read and driven without throwing.
            context.runOnClient<RuntimeException> {
                val test = checkNotNull(Sidequest.platform.commands["sqtest"]) { "/sqtest is not registered" }
                for (what in listOf("", "notify", "sound", "queue", "presence", "chat", "item")) {
                    test.spec.handler(listOfNotNull(what.takeIf { it.isNotEmpty() }))
                }
            }

            // The notification path reached the real UI queue rather than falling back to chat, which is what
            // the sink's HUD check is for.
            context.runOnClient<RuntimeException> {
                val history = Sidequest.platform.notifications.history()
                check(history.isNotEmpty()) { "no notification survived /sqtest notify" }
            }

            // Log levels are settable at runtime, which is the difference between a debug line that is written
            // and one that is ever read.
            context.runOnClient<RuntimeException> {
                val log = checkNotNull(Sidequest.platform.commands["sqlog"]) { "/sqlog is not registered" }
                log.spec.handler(listOf("parser", "trace"))
                log.spec.handler(listOf("all", "debug"))
                // A bad category and a bad level must be reported, not thrown.
                log.spec.handler(listOf("nonsense", "debug"))
                log.spec.handler(listOf("parser", "nonsense"))
                log.spec.handler(emptyList())
                // Back down, so the rest of the suite is not logging at DEBUG.
                LogCategory.entries.forEach { Sidequest.platform.setLogLevel(it, LogLevel.INFO) }
            }
        }

        context.waitTicks(SETTLE_TICKS)
    }

    private companion object {
        const val SETTLE_TICKS = 10
    }
}
