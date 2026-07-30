package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.platform.audio.SoundDefinition
import dev.th7bo.sidequest.platform.audio.SoundGroup
import dev.th7bo.sidequest.platform.audio.SoundPool
import dev.th7bo.sidequest.platform.audio.SoundRequest
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.game.GameClient
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.log.LogCategory
import dev.th7bo.sidequest.platform.log.LogLevel
import dev.th7bo.sidequest.platform.notification.NotificationAction
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.notification.NotificationPriority
import dev.th7bo.sidequest.platform.core.notification.notification
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.permission.Permission
import dev.th7bo.sidequest.platform.rule.Rule
import dev.th7bo.sidequest.platform.rule.RuleAction
import dev.th7bo.sidequest.platform.rule.RuleEngine
import dev.th7bo.sidequest.platform.rule.RuleEvaluation
import dev.th7bo.sidequest.platform.rule.RuleReset
import dev.th7bo.sidequest.platform.rule.RuleTrigger
import dev.th7bo.sidequest.platform.text.SqStyle
import dev.th7bo.sidequest.platform.text.SqText

/**
 * The commands that make the mod's own state visible.
 *
 * Everything in Sidequest is layered behind an interface, which is what makes it testable and also what
 * makes it invisible. A notification that does not appear could be a switched-off category, a deduplication,
 * a queue waiting for a safe moment, a missing sink, or a bug — and from the outside all five look the same.
 * These commands are how the difference gets seen.
 *
 * Written as a feature rather than as loose commands, so it obeys the same rules as everything else: every
 * command is registered through the context and disappears when the feature is switched off. A debug tool
 * that leaks registrations is a debug tool that makes the thing it is debugging worse.
 */
class DeveloperTools(
    private val client: GameClient,
    /** Turns a log category up or down. Owned by the platform, not by a feature. */
    private val setLogLevel: (LogCategory, LogLevel) -> Unit,
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("dev.tools"),
        displayName = "Developer tools",
        category = FeatureCategory.DEVELOPER,
        description = "Commands that show and exercise the mod's own state",
    )

    private lateinit var context: FeatureContext

    override fun onEnable(context: FeatureContext) {
        this.context = context
        registerTestSounds(context)

        context.command("sqstatus", "Everything the mod currently believes") { status() }

        /*
         * The completions are the documentation.
         *
         * A developer command whose vocabulary is only discoverable by running it wrong is a command nobody
         * uses: the whole cost of `/sqtest` is remembering that `notify` is spelled `notify` and not
         * `notification`. Every list below is derived from the thing it names — the log categories from the
         * enum, the rule ids from the engine — so a completion cannot drift from what the command accepts.
         */
        context.command(
            name = "sqlog",
            description = "Turns a log category up or down",
            usage = "<category|all> <level>",
            completions = { done ->
                when (done.size) {
                    0 -> LOG_TARGETS
                    1 -> LogLevel.entries.map { it.name.lowercase() }
                    else -> emptyList()
                }
            },
        ) { logLevel(it) }

        context.command(
            name = "sqtest",
            description = "Exercises a feature",
            usage = "<what>",
            completions = { done -> if (done.isEmpty()) TESTABLE else emptyList() },
        ) { test(it) }

        context.command(
            name = "sqrule",
            description = "Inspects and fires rules",
            usage = "[list|show|fire|reset|trace] [rule]",
            completions = { done ->
                when {
                    done.isEmpty() -> RULE_VERBS
                    // Only the verbs that name a rule. Offering ids after `list` would suggest that `list`
                    // takes one, which is the kind of wrong hint that is worse than no hint.
                    done.size == 1 && done.first().lowercase() in RULE_VERBS_WITH_ID -> ruleNames()
                    else -> emptyList()
                }
            },
        ) { rule(it) }

        registerTestRule(context)
    }

    // -- status --------------------------------------------------------------

    /**
     * One screen of everything.
     *
     * The first command to run when something is wrong, and deliberately one command rather than six: the
     * useful debugging question is almost never about one subsystem, it is "which of these does not say what
     * I expect".
     */
    private fun status() {
        val gameContext = context.gameContext.context
        val party = context.party.party

        heading("Sidequest status")
        line("version", "${context.gameVersion} · protocol ${dev.th7bo.sidequest.protocol.Protocol.VERSION}")

        heading("Where you are")
        line("hypixel", gameContext.isOnHypixel.yesNo())
        line("skyblock", gameContext.isInSkyBlock.yesNo())
        line("island", "${gameContext.island.displayName} (${gameContext.subLocation})")
        line("server", gameContext.serverId.toString())
        line("profile", "${gameContext.profile} · ${gameContext.profileType.displayName}")
        gameContext.dungeonFloor?.let { line("dungeon", it) }
        gameContext.kuudraTier?.let { line("kuudra", "T$it") }
        line("confidence", gameContext.confidence.name)
        // Both, because they answer different questions and the difference has bitten once already.
        line("busy / demanding", "${gameContext.isBusy.yesNo()} / ${gameContext.isDemanding.yesNo()}")

        heading("Activity")
        line("activity", gameContext.activity.activity.displayName)
        line("why", "${gameContext.activity.confidence} — ${gameContext.activity.reason.ifEmpty { "nothing said" }}")

        heading("Party")
        if (!party.isInParty) {
            line("party", "not in one")
        } else {
            line("members", "${party.size} · ${party.members.joinToString(", ")}")
            line("confidence", party.confidence.name)
        }
        context.party.readyCheck?.let { check ->
            line("ready check", "${check.responses.count { it.value.name == "READY" }}/${check.responses.size}")
        }

        heading("Backend")
        val backend = dev.th7bo.sidequest.Sidequest.platform.backend
        if (backend == null) {
            line("backend", "not configured")
        } else {
            line("state", backend.state.name)
            line("clock offset", "${backend.serverTime.offsetMillis}ms (round trip ${backend.serverTime.roundTripMillis}ms)")
            val realtime = dev.th7bo.sidequest.Sidequest.platform.realtime
            line("realtime", if (realtime?.isConnected == true) "connected, at ${realtime.lastSequence}" else "not connected")
            if (realtime?.hasResumeGap == true) {
                // Worth its own line: it means the client has a hole it cannot fill from the stream.
                line("resume gap", "yes — some history was missed")
            }
        }

        heading("Parsers")
        val chat = context.chat
        line("chat patterns", "${chat.patterns().size}, tracing ${chat.isDebugLogging.onOff()}")
        line(
            "chat lines",
            "${chat.stats.received} seen · ${chat.stats.classified} classified · " +
                "${chat.stats.duplicates} duplicate · ${chat.stats.failures} failed",
        )

        heading("Players")
        line("known", "${context.players.all().size}")
        line("friends", "${context.players.customFriends().size}")

        heading("Notifications and sound")
        line("in history", "${context.notifications.history().size}")
        line("held", "${context.notifications.queued().size}")
        line("serious mode", context.notifications.settings.seriousMode.onOff())
        line("sound serious mode", context.sounds.settings.seriousMode.onOff())

        heading("Permissions")
        val me = client.localPlayerId?.let { dev.th7bo.sidequest.platform.player.PlayerId.of(it) }
        if (me == null) {
            line("permissions", "not in a world")
        } else {
            line("your role", context.permissions.roleOf(me).displayName)
            // Only the disclosures. What the *group* may do is not something a status command can usefully
            // list, and what you have agreed to reveal is the thing worth checking before a session.
            for (permission in Permission.DISCLOSURES) {
                line(permission.displayName, context.permissions.sharesWithAnybody(permission).onOff())
            }
        }
    }

    // -- log levels ----------------------------------------------------------

    /**
     * Turns a category up or down at runtime.
     *
     * The alternative is a rebuild to change a log level, which in practice means nobody changes one and the
     * debug lines that were carefully written are never read.
     */
    private fun logLevel(arguments: List<String>) {
        if (arguments.size < 2) {
            heading("Log categories")
            tell(LogCategory.entries.joinToString(", ") { it.name.lowercase() })
            heading("Levels")
            tell(LogLevel.entries.joinToString(", ") { it.name.lowercase() })
            tell("Usage: /sqlog <category> <level>   ·   /sqlog all debug")
            return
        }

        val levelName = arguments[1].uppercase()
        val level = LogLevel.entries.firstOrNull { it.name == levelName }
        if (level == null) {
            error("'$levelName' is not a level. One of: ${LogLevel.entries.joinToString(", ") { it.name.lowercase() }}")
            return
        }

        if (arguments[0].equals("all", ignoreCase = true)) {
            LogCategory.entries.forEach { setLogLevel(it, level) }
            tell("Every category is now at $levelName.")
            return
        }

        val categoryName = arguments[0].uppercase()
        val category = LogCategory.entries.firstOrNull { it.name == categoryName }
        if (category == null) {
            error("'$categoryName' is not a category. One of: ${LogCategory.entries.joinToString(", ") { it.name.lowercase() }}")
            return
        }

        setLogLevel(category, level)
        tell("$categoryName is now at $levelName.")
    }

    // -- exercising things ---------------------------------------------------

    /**
     * Fires a feature on demand.
     *
     * The point is not that a developer cannot reason about the code — it is that half of what these
     * subsystems do only happens in conditions that are awkward to arrange. Nobody wants to enter a dungeon
     * to check that a notification gets held, or wait for a rare drop to see what its toast looks like.
     */
    private fun test(arguments: List<String>) {
        when (arguments.firstOrNull()?.lowercase()) {
            "notify", "notification" -> testNotifications()
            "sound", "sounds" -> testSounds()
            "queue" -> testQueue()
            "presence" -> testPresence()
            "chat" -> testChatRules()
            "item" -> testItem()
            "rule", "rules" -> testRules()
            else -> {
                heading("What can be tested")
                // From the same list the completions come from, so the two cannot disagree about what exists.
                tell(TESTABLE.joinToString(" · "))
                tell("Usage: /sqtest <what>")
            }
        }
    }

    /** One notification of each category, so their defaults and their look can be compared side by side. */
    private fun testNotifications() {
        heading("Notifications")
        for (category in NotificationCategory.entries) {
            val delivery = context.notifications.notify(
                notification(
                    category = category,
                    title = "${category.displayName} test",
                    subtitle = "priority NORMAL, ${category.defaultDurationMillis}ms",
                ),
            )
            // The *outcome*, not "sent". A category that is off, a queue, a deduplication and a delivery all
            // look identical from outside, and this is the line that tells them apart.
            line(category.name.lowercase(), delivery.name)
        }

        val withAction = notification(
            category = NotificationCategory.ALERT,
            title = "Urgent, with an action",
            subtitle = "Click it",
            priority = NotificationPriority.URGENT,
        ).copy(
            actions = listOf(NotificationAction("ok", "OK") { tell("The notification action ran.") }),
        )
        line("urgent + action", context.notifications.notify(withAction).name)
    }

    /** Every group, so a mute or a volume setting can be heard rather than inferred. */
    private fun testSounds() {
        heading("Sounds")
        for (group in SoundGroup.entries) {
            val result = context.sounds.play(
                SoundRequest(testSoundFor(group), respectCooldown = false),
            )
            line(group.displayName, result.name)
        }
        line("pool (x3)", (1..3).joinToString(" ") { context.sounds.playPool(TEST_POOL).name })
        line("missing, with fallback", context.sounds.play(MISSING_SOUND).name)
    }

    /**
     * Shows what the busy policy does without needing a dungeon.
     *
     * Serious mode is the switch that produces the same effect as being busy, so it stands in for one — and
     * the release path is the same code either way.
     */
    private fun testQueue() {
        heading("Queue-until-safe")
        val settings = context.notifications.settings
        val manager = context.notifications as dev.th7bo.sidequest.platform.core.notification.DefaultNotificationManager

        manager.update(settings.copy(seriousMode = true))
        repeat(3) { index ->
            context.notifications.notify(
                notification(NotificationCategory.PROGRESSION, "Held #${index + 1}", groupingKey = "test"),
            )
        }
        line("held", "${context.notifications.queued().size}")

        manager.update(settings.copy(seriousMode = false))
        line("after release", "${context.notifications.queued().size} held, grouped into one toast")
    }

    private fun testPresence() {
        heading("Presence")
        val backend = dev.th7bo.sidequest.Sidequest.platform.backend
        if (backend == null) {
            error("No backend is configured, so there is nobody to tell.")
            return
        }
        line("state", backend.state.name)
        // What *would* be sent, field by field, so a surprising presence is traceable to a disclosure rather
        // than to a bug.
        line("share online", context.permissions.sharesWithAnybody(Permission.VIEW_ONLINE_STATUS).onOff())
        line("share activity", context.permissions.sharesWithAnybody(Permission.VIEW_ACTIVITY).onOff())
        line("share island", context.permissions.sharesWithAnybody(Permission.VIEW_ISLAND).onOff())
        line("share position", context.permissions.sharesWithAnybody(Permission.VIEW_EXACT_POSITION).onOff())
        tell("Anything switched off above is left out before the message is even encoded.")
    }

    /** Runs the built-in chat rules against their own recorded fixtures, in game. */
    private fun testChatRules() {
        heading("Chat rules")
        val failures = context.chat.verifyFixtures()
        if (failures.isEmpty()) {
            tell("All ${context.chat.patterns().size} pattern(s) match their own recorded lines.")
        } else {
            error("${failures.size} pattern(s) failed their fixtures:")
            failures.take(5).forEach { tell("  $it") }
        }
    }

    /** Reads whatever is in hand, which is the one thing a headless test cannot do. */
    private fun testItem() {
        heading("Held item")
        val item = dev.th7bo.sidequest.Sidequest.heldItemSnapshot()
        if (item == null) {
            error("Nothing in hand, or not in a world.")
            return
        }
        line("summary", item.summary())
        line("minecraft id", item.minecraftId)
        line("skyblock id", item.skyblockId ?: "—")
        line("uuid", item.itemUuid ?: "— (not a unique item)")
        line("rarity", item.rarity?.displayName ?: "—")
        line("upgrades", item.upgrades.summary().ifEmpty { "stock" })
        line("enchantments", item.enchantments.entries.take(4).joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "—" })
        line("extra keys", item.extra.keys.joinToString(", ").ifEmpty { "—" })
    }

    /**
     * Drives the test rule through its tiers, on the real path.
     *
     * Posted on the bus rather than handed to [RuleEngine.evaluate], and that is the whole point of this
     * particular test: `evaluate` skips the trigger index and the subscription, which is exactly the part most
     * likely to be wrong. A rule that fires when called directly and never fires in play is the bug this
     * catches.
     */
    private fun testRules() {
        heading("Rules")
        // Every subject, not just this player's: a developer running this twice wants the same output both
        // times, and progress left under a stale uuid would change it.
        context.rules.resetEverySubject(TEST_RULE)
        // Three, because the rule's tiers are 1 and 3: the middle one should be a skip with a reason, which is
        // the more interesting half of the output.
        repeat(3) { context.post(RuleTestEvent(), EventSource.DERIVED) }

        for (evaluation in context.rules.trace().take(3).reversed()) {
            when (evaluation) {
                is RuleEvaluation.Fired -> line(
                    "fired",
                    "tier ${evaluation.outcome.tier ?: "—"} at progress ${evaluation.outcome.progress}",
                )
                is RuleEvaluation.Skipped -> line("skipped", evaluation.reason)
            }
        }
        val progress = context.rules.progressOf(TEST_RULE)
        line("progress", "${progress.progress}, fired ${progress.firings}x, tiers ${progress.awardedTiers}")
        tell("Two toasts and two sounds mean the notify and sound handlers are wired.")
    }

    // -- rules ---------------------------------------------------------------

    /**
     * Inspects rules, and fires one on demand.
     *
     * Firing by hand is the part that cannot be done any other way: a rule about a rare drop is a rule nobody
     * can trigger deliberately, and "does this rule work" is otherwise a question answered by playing for a
     * week. What it reports is the *reason*, so an unfired rule names the condition that stopped it.
     */
    private fun rule(arguments: List<String>) {
        val id = arguments.getOrNull(1)?.let { name ->
            context.rules.rules().firstOrNull { it.id.value.endsWith(name, ignoreCase = true) }
        }
        when (arguments.firstOrNull()?.lowercase()) {
            null, "list" -> listRules()
            "trace" -> traceRules()
            "show" -> if (id == null) error("No such rule. /sqrule list") else showRule(id)
            "fire" -> if (id == null) error("No such rule. /sqrule list") else fireRule(id)
            "reset" -> if (id == null) {
                error("No such rule. /sqrule list")
            } else {
                context.rules.resetEverySubject(id.id)
                tell("Reset ${id.id} for every subject.")
            }
            else -> {
                heading("Rules")
                tell(
                    RULE_VERBS.joinToString(" · ") { verb ->
                        if (verb in RULE_VERBS_WITH_ID) "$verb <rule>" else verb
                    },
                )
            }
        }
    }

    /**
     * Rule ids, without the namespace.
     *
     * The short form, because every rule in the mod is `sidequest:` and a completion list where every entry
     * shares a ten-character prefix completes nothing. The lookup accepts a suffix, so what is suggested is
     * what works.
     */
    private fun ruleNames(): List<String> = context.rules.rules().map { it.id.value.substringAfter(':') }

    private fun listRules() {
        val rules = context.rules.rules()
        heading("Rules (${rules.size})")
        if (rules.isEmpty()) {
            tell("None registered. Features contribute rules as they are built.")
            return
        }
        for (rule in rules) {
            val progress = context.rules.progressOf(rule.id)
            // Hidden rules are listed here and nowhere a player would see, because the whole point of a hidden
            // rule is that it is a surprise — and the whole point of this command is that it is not.
            val flags = buildList {
                if (!rule.isEnabled) add("off")
                if (rule.isHidden) add("hidden")
            }.joinToString(",")
            line(
                rule.id.value,
                "${rule.trigger} · ${progress.progress}${rule.tiers.lastOrNull()?.let { "/$it" } ?: ""}" +
                    " · ${progress.firings}x" + if (flags.isEmpty()) "" else " · $flags",
            )
        }
    }

    private fun showRule(rule: dev.th7bo.sidequest.platform.rule.Rule) {
        heading(rule.displayName.ifEmpty { rule.id.value })
        val progress = context.rules.progressOf(rule.id)
        line("id", rule.id.value)
        line("trigger", rule.trigger.toString())
        line("condition", rule.condition.toString())
        line("actions", rule.actions.joinToString(", ") { it.kind }.ifEmpty { "none" })
        line("tiers", rule.tiers.joinToString(", ").ifEmpty { "—" })
        line("cooldown", if (rule.cooldown == kotlin.time.Duration.ZERO) "none" else rule.cooldown.toString())
        line("max firings", rule.maxFirings?.toString() ?: "unlimited")
        line("reset", rule.reset.name.lowercase())
        line("progress", "${progress.progress}, fired ${progress.firings}x")
        line("awarded tiers", progress.awardedTiers.joinToString(", ").ifEmpty { "—" })
    }

    /**
     * Fires a rule against a stand-in event.
     *
     * The event is synthetic, and the caveat below says so rather than leaving it implied: a condition that
     * reads the event — a phrase, an item, a number — cannot hold against an event that carries none of them,
     * so a skip here is not proof the rule is broken. What it *does* prove is everything else: the state
     * checks, the context conditions, and the action handlers.
     */
    private fun fireRule(rule: dev.th7bo.sidequest.platform.rule.Rule) {
        heading("Firing ${rule.id}")
        val evaluation = context.rules.evaluate(rule.id, RuleTestEvent())
        when (evaluation) {
            null -> error("The engine does not know that rule.")
            is RuleEvaluation.Fired -> {
                line("result", "fired")
                line("tier", evaluation.outcome.tier?.toString() ?: "—")
                line("progress", evaluation.outcome.progress.toString())
                line("firings", evaluation.outcome.firings.toString())
            }
            is RuleEvaluation.Skipped -> {
                line("result", "did not fire")
                line("because", evaluation.reason)
            }
        }
        tell("The event was a stand-in, so conditions about text or items could not hold.")
    }

    private fun traceRules() {
        val trace = context.rules.trace()
        heading("Recent evaluations (${trace.size})")
        if (trace.isEmpty()) {
            tell("Nothing evaluated yet. Rules only wake for their own trigger.")
            return
        }
        for (evaluation in trace.take(TRACE_LINES)) {
            when (evaluation) {
                is RuleEvaluation.Fired -> line(evaluation.rule.id.value, "fired at ${evaluation.outcome.progress}")
                is RuleEvaluation.Skipped -> line(evaluation.rule.id.value, evaluation.reason)
            }
        }
    }

    // -- test fixtures -------------------------------------------------------

    /**
     * One rule that exercises the engine end to end.
     *
     * Registered by the developer feature rather than by the platform, so switching the feature off takes it
     * with it. Tiered on purpose: a tiered rule is the one shape where the engine does something a feature
     * could not trivially do itself, so it is the shape worth having a test for.
     */
    private fun registerTestRule(context: FeatureContext) {
        context.rules.register(
            Rule(
                id = TEST_RULE,
                displayName = "Developer test rule",
                description = "Fires at 1 and 3 progress. Driven by /sqtest rule.",
                trigger = RuleTrigger(RuleTestEvent::class.java),
                tiers = listOf(1, 3),
                actions = listOf(
                    RuleAction.AddProgress(),
                    RuleAction.Notify(
                        title = "{rule} · tier {tier}",
                        subtitle = "progress {progress}, firing {firings}",
                        // Not DEBUG, which is DISABLED by default: a test whose notification is switched off
                        // proves nothing and looks like a broken handler. Somebody typing /sqtest rule wants
                        // to see the toast.
                        category = NotificationCategory.PROGRESSION.name,
                    ),
                    RuleAction.PlaySound(testSoundFor(SoundGroup.INTERFACE)),
                    // Deliberately unhandled: proves that a rule with an action nobody handles still does the
                    // rest, which is the difference between half-supported and broken.
                    RuleAction.GrantCurrency(10),
                ),
                reset = RuleReset.ON_DISCONNECT,
            ),
        )
    }

    /** The stand-in event. Exists only so a rule has something to be triggered by on demand. */
    private class RuleTestEvent : SidequestEvent() {
        override fun describe(): String = "a developer test trigger"
    }


    /**
     * Registers a sound per group, plus a pool and a deliberately broken one.
     *
     * Vanilla sounds, so nothing depends on an asset having been downloaded — the point of `/sqtest sound` is
     * to check the *manager*, and a test that needs a working asset pipeline tests two things at once.
     */
    private fun registerTestSounds(context: FeatureContext) {
        context.sounds.register(SoundDefinition(FALLBACK_SOUND, "minecraft:ui.button.click", group = SoundGroup.INTERFACE))

        for (group in SoundGroup.entries) {
            context.sounds.register(
                SoundDefinition(
                    id = testSoundFor(group),
                    resource = when (group) {
                        SoundGroup.INTERFACE -> "minecraft:ui.button.click"
                        SoundGroup.EFFECTS -> "minecraft:entity.player.levelup"
                        SoundGroup.SOUNDBOARD -> "minecraft:entity.villager.yes"
                        SoundGroup.FUN -> "minecraft:entity.cat.ambient"
                    },
                    group = group,
                ),
            )
        }

        val poolMembers = listOf("minecraft:block.note_block.harp", "minecraft:block.note_block.bell", "minecraft:block.note_block.flute")
            .mapIndexed { index, resource ->
                val id = SqId.sidequest("dev.pool_$index")
                context.sounds.register(SoundDefinition(id, resource, group = SoundGroup.EFFECTS))
                id
            }
        context.sounds.registerPool(SoundPool(TEST_POOL, poolMembers))

        // Points at nothing, so the fallback path can be heard rather than reasoned about.
        context.sounds.register(
            SoundDefinition(MISSING_SOUND, "sidequest:does_not_exist", fallbackId = FALLBACK_SOUND),
        )
    }

    private fun testSoundFor(group: SoundGroup) = SqId.sidequest("dev.sound_${group.name.lowercase()}")

    // -- output --------------------------------------------------------------

    private fun heading(text: String) {
        client.sendClientMessage(SqText.of("§8§m                    §r §f§l$text §8§m                    "))
    }

    private fun line(label: String, value: String) {
        client.sendClientMessage(
            SqText.join(
                SqText.of("  $label ", SqStyle(color = LABEL)),
                SqText.of(value, SqStyle(color = VALUE)),
            ),
        )
    }

    private fun tell(text: String) {
        client.sendClientMessage(SqText.of(text, SqStyle(color = VALUE)))
    }

    private fun error(text: String) {
        client.sendClientMessage(SqText.of(text, SqStyle(color = ERROR)))
    }

    private fun Boolean.yesNo() = if (this) "yes" else "no"

    private fun Boolean.onOff() = if (this) "on" else "off"

    private companion object {
        val TEST_POOL = SqId.sidequest("dev.pool")
        val MISSING_SOUND = SqId.sidequest("dev.missing")
        val FALLBACK_SOUND = SqId.sidequest("dev.fallback")
        val TEST_RULE = SqId.sidequest("dev.rule")

        /**
         * What `/sqtest` accepts, and what it completes.
         *
         * One list for both. The dispatch below also answers to a few plurals, which are deliberately *not*
         * suggested: an alias exists to forgive a typo, and completing both spellings implies a difference.
         */
        val TESTABLE = listOf("notify", "sound", "queue", "presence", "chat", "item", "rule")

        val RULE_VERBS = listOf("list", "show", "fire", "reset", "trace")

        /** The verbs whose second word is a rule. See the completion above. */
        val RULE_VERBS_WITH_ID = setOf("show", "fire", "reset")

        /** Every log category, plus the one word that is not a category. */
        val LOG_TARGETS: List<String> = LogCategory.entries.map { it.name.lowercase() } + "all"

        /** How much of the rule trace `/sqrule trace` prints. A chat window holds about this much. */
        const val TRACE_LINES = 12

        const val LABEL = 0x9CA3AF
        const val VALUE = 0xE5E7EB
        const val ERROR = 0xF87171
    }
}
