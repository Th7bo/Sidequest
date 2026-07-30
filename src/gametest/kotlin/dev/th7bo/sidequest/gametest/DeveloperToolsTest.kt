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
                for (expected in listOf(
                    "sqstatus", "sqlog", "sqtest", "sqdiag", "sqchat", "sqboard", "sqrule", "sqcine", "sqmark",
                )) {
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

            /*
             * Tab completion, asked of the game's own dispatcher.
             *
             * Not of `CommandSpec.completions` — that would test a lambda. What is worth checking is the whole
             * path: that Fabric's merged command tree kept the suggestion provider, that the bridge scopes a
             * suggestion to the word being typed rather than replacing the whole argument, and that a bare
             * command offers nothing at all. None of it is reachable without a connected client.
             */
            context.runOnClient<RuntimeException> { client ->
                val dispatcher = checkNotNull(client.connection?.commands) { "no command dispatcher" }
                val source = client.connection!!.suggestionsProvider

                fun suggestions(input: String): List<String> =
                    dispatcher.getCompletionSuggestions(dispatcher.parse(input, source))
                        .join().list.map { it.text }

                // The first word.
                val testable = suggestions("sqtest ")
                for (expected in listOf("notify", "sound", "queue", "presence", "chat", "item", "rule")) {
                    check(expected in testable) { "/sqtest does not complete '$expected': $testable" }
                }

                // A partial word narrows, and the suggestion replaces that word only. `notification` is an
                // accepted alias that is deliberately not suggested, so this is exactly one entry.
                check(suggestions("sqtest no") == listOf("notify")) {
                    "/sqtest no completed to ${suggestions("sqtest no")}"
                }

                // Two levels deep: the verb, then a rule id derived from the engine.
                check("fire" in suggestions("sqrule ")) { "/sqrule does not complete its verbs" }
                check("dev.rule" in suggestions("sqrule fire ")) {
                    "/sqrule fire does not complete a registered rule: ${suggestions("sqrule fire ")}"
                }
                // `list` takes no rule, so offering one would be a wrong hint.
                check(suggestions("sqrule list ").isEmpty()) {
                    "/sqrule list offered ${suggestions("sqrule list ")}"
                }

                check("parser" in suggestions("sqlog ")) { "/sqlog does not complete its categories" }
                check("all" in suggestions("sqlog ")) { "/sqlog does not complete 'all'" }
                check("trace" in suggestions("sqlog parser ")) { "/sqlog does not complete its levels" }

                // A command that takes nothing must not advertise an argument, and must not swallow one.
                check(suggestions("sqdiag ").isEmpty()) { "/sqdiag offered ${suggestions("sqdiag ")}" }
                // Read through the reader and not through `exceptions`: Brigadier reports trailing input by
                // leaving it unconsumed, and only refuses it at execute time. Unconsumed input is therefore
                // the assertion — it is what makes the game reject the command instead of running it.
                check(dispatcher.parse("sqdiag nonsense", source).reader.canRead()) {
                    "/sqdiag consumed an argument it does not take, so a typo would run it silently"
                }
                check(!dispatcher.parse("sqdiag", source).reader.canRead()) { "/sqdiag no longer parses bare" }
                // And the opposite, so the check above cannot pass by nothing parsing at all.
                check(!dispatcher.parse("sqtest notify", source).reader.canRead()) {
                    "/sqtest no longer accepts an argument"
                }
            }

            /*
             * The cinematic runtime, on the real HUD.
             *
             * The headless tests drive the director against a fake sink; what they cannot reach is the sink
             * itself — whether the stage node exists once a world is loaded, whether the translation from the
             * platform's components produces something drawable, and whether the clock finishes a cinematic
             * rather than leaving the gate closed forever. All three are only true on a real client.
             */
            context.runOnClient<RuntimeException> {
                val cine = checkNotNull(Sidequest.platform.commands["sqcine"]) { "/sqcine is not registered" }
                cine.spec.handler(listOf("safety"))
                cine.spec.handler(listOf("play"))

                check(Sidequest.platform.cinematics.history().isNotEmpty()) {
                    "/sqcine play drew nothing: ${Sidequest.platform.cinematics.safety().explain()}"
                }
                // Playing means the gate is now closed behind it, which is the assertion that the sink and the
                // director agree about what is happening.
                check(!Sidequest.platform.cinematics.safety().isSafe) {
                    "a cinematic is playing but the gate says it is safe to start another"
                }
            }

            // Caught partway through, while the bars are in and the counter is still running. The one piece of
            // evidence a headless test cannot produce: that what was drawn is a cinematic and not an empty
            // screen with the gate closed behind it.
            context.waitTicks(MID_CINEMATIC_TICKS)
            context.takeScreenshot("cinematic_playing")

            // The clock has to actually finish it. A cinematic that never ends is a gate that never reopens,
            // and every later cinematic would be queued forever behind it.
            context.waitTicks(CINEMATIC_TICKS)
            context.runOnClient<RuntimeException> {
                val safety = Sidequest.platform.cinematics.safety()
                check(
                    dev.th7bo.sidequest.platform.cinematic.UnsafeReason.ALREADY_PLAYING !in safety.reasons,
                ) {
                    "the cinematic never finished; the gate is stuck at ${safety.explain()}"
                }
            }

            context.runOnClient<RuntimeException> {
                val cine = checkNotNull(Sidequest.platform.commands["sqcine"])
                for (verb in listOf("", "queue", "trace", "skip", "nonsense")) {
                    cine.spec.handler(listOfNotNull(verb.takeIf { it.isNotEmpty() }))
                }
                cine.spec.handler(listOf("replay", "dev.cinematic"))
                cine.spec.handler(listOf("replay", "no.such.cinematic"))
            }

            /*
             * Markers, from placement through to something drawn.
             *
             * The headless tests cover the service. What they cannot reach is the bridge: whether a marker
             * reaches the world overlay layer at all, and whether the id it derives is one `UiId` accepts —
             * which is exactly the shape of a bug that already shipped once, when notification UUIDs were
             * rejected by the same rules.
             */
            context.runOnClient<RuntimeException> { client ->
                val mark = checkNotNull(Sidequest.platform.commands["sqmark"]) { "/sqmark is not registered" }
                mark.spec.handler(listOf("clear"))
                mark.spec.handler(listOf("place", "waypoint"))

                val placed = Sidequest.platform.markers.all()
                check(placed.size == 1) { "expected one marker, got ${placed.map { it.marker.id }}" }
                val tracked = placed.single()
                check(tracked.distance != null) {
                    "the marker was placed at the player and has no distance; island is " +
                        "${tracked.marker.location.island} and the context says " +
                        "${Sidequest.platform.gameContext.context.island}"
                }
                check(tracked.isVisible) { "a marker at the player's feet is not visible" }
            }

            // The bridge runs off the platform tick, so it needs one to have happened.
            context.waitTicks(SETTLE_TICKS)
            context.runOnClient<RuntimeException> {
                check(Sidequest.markerOverlayCount() == 1) {
                    "the marker did not reach the world overlay layer: ${Sidequest.markerOverlayCount()} overlay(s)"
                }
            }

            context.runOnClient<RuntimeException> {
                val mark = checkNotNull(Sidequest.platform.commands["sqmark"])
                for (arguments in listOf(
                    listOf("list"),
                    listOf("route"),
                    listOf("place", "ping"),
                    listOf("place", "nonsense"),
                    listOf("ack", Sidequest.platform.markers.all().first().marker.id.take(8), "coming"),
                    listOf("ack", "no-such-marker"),
                    listOf("nonsense"),
                    emptyList(),
                )) {
                    mark.spec.handler(arguments)
                }
                mark.spec.handler(listOf("clear"))
                check(Sidequest.platform.markers.all().isEmpty()) { "/sqmark clear left markers behind" }
            }

            // And the overlays go with them. A bridge that adds but never removes leaves a waypoint on screen
            // forever, which is the failure mode nobody notices until an hour in.
            context.waitTicks(SETTLE_TICKS)
            context.runOnClient<RuntimeException> {
                check(Sidequest.markerOverlayCount() == 0) {
                    "${Sidequest.markerOverlayCount()} overlay(s) survived the markers being removed"
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

        /**
         * Long enough for the test cinematic to run out.
         *
         * Its duration is the four-second default and the clock runs off render frames, so this is generous
         * rather than exact — an assertion that has to be tight about frame timing is an assertion that fails
         * on somebody else's machine.
         */
        const val CINEMATIC_TICKS = 140

        /** Partway in: the bars have slid, the counter is mid-count, the reveal has landed. */
        const val MID_CINEMATIC_TICKS = 50
    }
}
