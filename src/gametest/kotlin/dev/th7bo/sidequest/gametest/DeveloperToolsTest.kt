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
                for (expected in listOf("sqstatus", "sqlog", "sqtest", "sqdiag", "sqchat", "sqboard", "sqrule")) {
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

            /*
             * The action path, end to end.
             *
             * A HUD toast cannot be clicked — nothing delivers input to the live HUD and the cursor is grabbed
             * during play — so actions are offered as a clickable chat line running a hidden client command.
             * This drives that command the way the chat click does and checks the action actually ran.
             */
            context.runOnClient<RuntimeException> {
                var ran = false
                val withAction = dev.th7bo.sidequest.platform.core.notification.notification(
                    category = dev.th7bo.sidequest.platform.notification.NotificationCategory.ALERT,
                    title = "Gametest action",
                    priority = dev.th7bo.sidequest.platform.notification.NotificationPriority.URGENT,
                    id = "gametest-action",
                ).copy(
                    actions = listOf(
                        dev.th7bo.sidequest.platform.notification.NotificationAction("go", "Go") { ran = true },
                    ),
                )
                Sidequest.platform.notifications.notify(withAction)

                val action = checkNotNull(Sidequest.platform.commands["sqaction"]) {
                    "/sqaction is not registered, so a notification's chat action would do nothing"
                }
                action.spec.handler(listOf("gametest-action", "go"))
                check(ran) { "the notification action did not run" }

                // And a click on something that has gone must not throw.
                action.spec.handler(listOf("no-such-notification", "go"))
                action.spec.handler(listOf("gametest-action"))
            }

            /*
             * The rule engine, on the real path.
             *
             * The headless tests drive the engine directly; what they cannot check is the wiring in
             * `SidequestPlatform` — that the engine is subscribed to the *live* bus, and that its action
             * handlers reach the real notification and sound managers. Both of those live above the engine, so
             * a passing unit suite says nothing about them.
             */
            context.runOnClient<RuntimeException> {
                val before = Sidequest.platform.notifications.history().size
                val test = checkNotNull(Sidequest.platform.commands["sqtest"])
                test.spec.handler(listOf("rule"))

                val ruleId = dev.th7bo.sidequest.platform.id.SqId.sidequest("dev.rule")
                val progress = Sidequest.platform.rules.progressOf(ruleId)
                // Two tiers, so two firings. One would mean the tier logic did not see the second crossing;
                // three would mean it re-awarded one.
                check(progress.firings == 2) {
                    // The diagnostics are in the message rather than in a log: a gametest failure is read
                    // once, in CI output, and "wanted 2 got 0" without the registry and the trace is a
                    // failure that has to be reproduced before it can be understood.
                    "the test rule fired ${progress.firings} time(s) on the live bus, wanted 2. " +
                        "registered: ${Sidequest.platform.rules.rules().map { it.id.value }}; " +
                        "trace: ${Sidequest.platform.rules.trace().map { it.toString().take(120) }}; " +
                        "listeners: ${Sidequest.platform.listenerCount()}"
                }
                check(progress.progress == 3) { "progress was ${progress.progress}, wanted 3" }

                // The notify handler is registered by the platform, not the engine. This is the only place that
                // is checked at all.
                val after = Sidequest.platform.notifications.history().size
                check(after > before) {
                    "a rule fired but no notification reached the manager, so the notify handler is not wired"
                }
            }

            // The inspection commands, including the paths for a rule that does not exist.
            context.runOnClient<RuntimeException> {
                val rule = checkNotNull(Sidequest.platform.commands["sqrule"]) { "/sqrule is not registered" }
                rule.spec.handler(emptyList())
                rule.spec.handler(listOf("list"))
                rule.spec.handler(listOf("trace"))
                rule.spec.handler(listOf("show", "dev.rule"))
                rule.spec.handler(listOf("fire", "dev.rule"))
                rule.spec.handler(listOf("reset", "dev.rule"))
                rule.spec.handler(listOf("show", "no.such.rule"))
                rule.spec.handler(listOf("fire"))
                rule.spec.handler(listOf("nonsense"))

                // The reset above is the assertion: state a command claims to have cleared must be cleared.
                val ruleId = dev.th7bo.sidequest.platform.id.SqId.sidequest("dev.rule")
                check(Sidequest.platform.rules.progressOf(ruleId).progress == 0) {
                    "/sqrule reset left progress behind"
                }
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
