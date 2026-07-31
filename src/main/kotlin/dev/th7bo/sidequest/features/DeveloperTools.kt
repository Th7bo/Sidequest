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
import dev.th7bo.sidequest.platform.parser.HypixelText
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
import dev.th7bo.sidequest.cosmetic.NametagDecorator
import dev.th7bo.sidequest.platform.cinematic.Cinematic
import dev.th7bo.sidequest.platform.core.preview.PreviewData
import dev.th7bo.sidequest.platform.cosmetic.CosmeticSettings
import dev.th7bo.sidequest.platform.cosmetic.CosmeticSlot
import dev.th7bo.sidequest.platform.log.ErrorId
import dev.th7bo.sidequest.platform.notification.Notification
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import dev.th7bo.sidequest.platform.cinematic.CinematicComponent
import dev.th7bo.sidequest.platform.cinematic.CinematicDisposition
import dev.th7bo.sidequest.platform.cinematic.CinematicPolicy
import dev.th7bo.sidequest.platform.marker.Acknowledgement
import dev.th7bo.sidequest.platform.marker.Marker
import dev.th7bo.sidequest.platform.marker.MarkerKind
import dev.th7bo.sidequest.platform.skyblock.SqLocation
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

        context.command(
            name = "sqcine",
            description = "Inspects and plays cinematics",
            usage = "[play|safety|queue|skip|replay|trace] [id]",
            completions = { done ->
                when {
                    done.isEmpty() -> CINE_VERBS
                    done.size == 1 && done.first().lowercase() == "replay" -> cinematicNames()
                    else -> emptyList()
                }
            },
        ) { cine(it) }

        context.command(
            name = "sqmark",
            description = "Places and inspects markers",
            usage = "[place|list|route|clear|ack] [kind|id]",
            completions = { done ->
                when {
                    done.isEmpty() -> MARK_VERBS
                    done.size == 1 && done.first().lowercase() in setOf("place", "clear") ->
                        MarkerKind.entries.map { it.name.lowercase() }
                    done.size == 1 && done.first().lowercase() == "ack" -> markerNames()
                    done.size == 2 && done.first().lowercase() == "ack" ->
                        Acknowledgement.entries.map { it.name.lowercase() }
                    else -> emptyList()
                }
            },
        ) { mark(it) }

        context.command(
            name = "sqerr",
            description = "What has gone wrong, grouped",
            usage = "[list|show|clear] [id]",
            completions = { done ->
                when {
                    done.isEmpty() -> ERROR_VERBS
                    done.size == 1 && done.first().lowercase() == "show" ->
                        context.errors.recent().map { it.id.value }
                    else -> emptyList()
                }
            },
        ) { errors(it) }

        context.command(
            name = "sqcos",
            description = "Inspects and tries on cosmetics",
            usage = "[list|wear|remove|preview|resolve|settings] [id|slot]",
            completions = { done ->
                when {
                    done.isEmpty() -> COSMETIC_VERBS
                    done.size == 1 && done.first().lowercase() == "wear" -> cosmeticNames()
                    done.size == 1 && done.first().lowercase() == "remove" ->
                        CosmeticSlot.entries.map { it.name.lowercase() }
                    done.size == 1 && done.first().lowercase() == "settings" -> COSMETIC_SETTINGS
                    done.size == 1 && done.first().lowercase() in setOf("dress", "resolve") ->
                        context.players.all().map { it.username }
                    else -> emptyList()
                }
            },
        ) { cosmetics(it) }

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

        heading("Assets")
        val assetStats = context.assets.stats()
        line("resident", "${assetStats.entries} · ${assetStats.bytes} bytes")
        line("hits / misses", "${assetStats.hits} / ${assetStats.misses}")
        line("evictions", "${assetStats.evictions}")

        heading("Cosmetics")
        val cosmeticSettings = context.cosmetics.settings
        line("registered", "${context.cosmetics.definitions().size}")
        line("switched on", cosmeticSettings.isEnabled.yesNo())
        if (cosmeticSettings.isEnabled) {
            line(
                "showing",
                listOfNotNull(
                    "skins".takeIf { cosmeticSettings.showAppearanceOverrides },
                    "effects".takeIf { cosmeticSettings.showEffects },
                    "jokes".takeIf { cosmeticSettings.showJokeCosmetics },
                ).ifEmpty { listOf("nothing") }.joinToString(", "),
            )
            if (cosmeticSettings.reducedAnimation) line("animation", "reduced")
            if (cosmeticSettings.hiddenSlots.isNotEmpty()) {
                line("hidden slots", cosmeticSettings.hiddenSlots.joinToString { it.displayName })
            }
        }
        if (me != null) {
            val worn = context.cosmetics.resolve(me)
            line("you are wearing", "${worn.shown.size} shown, ${worn.hidden.size} hidden")
        }

        // Last, because it is the section somebody scrolls to find and the end of the output is where the
        // chat window already is.
        heading("Problems")
        val problems = context.errors.summary()
        line("this session", problems.toString())
        for (record in context.errors.recent(limit = PROBLEM_LINES)) {
            line(record.id.toString(), "${record.category}/${record.owner} ${record.message.take(PROBLEM_WIDTH)}")
        }
        if (!problems.isClean) tell("  /sqerr show <id> for the detail")
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
            "drop" -> simulateRareDrop()
            "trophy" -> simulateTrophy()
            "achievement" -> simulateAchievement()
            "death" -> simulateDeath()
            "dungeon" -> simulateRunEnd("dungeon")
            "kuudra" -> simulateRunEnd("kuudra")
            "ping" -> simulatePing()
            "waypoint" -> simulateWaypoint()
            "cosmetic" -> cosmetics(listOf("preview"))
            "offline" -> simulateOffline()
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

    // -- markers -------------------------------------------------------------

    /**
     * Places markers where the player is standing, and says what became of them.
     *
     * `place` is the only way to exercise the whole path without dying, being pinged or walking somewhere — and
     * the listing prints each marker's *island* alongside its distance, because "it is not drawing" and "it is
     * on another island" are the same symptom and different bugs.
     */
    private fun mark(arguments: List<String>) {
        when (arguments.firstOrNull()?.lowercase()) {
            null, "list" -> markList()
            "place" -> markPlace(arguments.getOrNull(1))
            "route" -> markRoute()
            "clear" -> markClear(arguments.getOrNull(1))
            "ack" -> markAck(arguments.getOrNull(1), arguments.getOrNull(2))
            else -> {
                heading("Markers")
                tell(MARK_VERBS.joinToString(" · "))
            }
        }
    }

    private fun markPlace(kindName: String?) {
        val kind = MarkerKind.entries.firstOrNull { it.name.equals(kindName, ignoreCase = true) }
            ?: MarkerKind.WAYPOINT
        val here = dev.th7bo.sidequest.Sidequest.localPosition()
        if (here == null) {
            error("Not in a world, so there is nowhere to put it.")
            return
        }

        val placed = context.markers.place(
            Marker(
                id = "",
                kind = kind,
                location = SqLocation(
                    island = context.gameContext.context.island,
                    position = here,
                    profile = context.gameContext.context.profile,
                ),
                label = "${kind.displayName} test",
                // Small enough that walking a few blocks away and back demonstrates arrival, which is the one
                // behaviour a command cannot show by printing.
                arrivalRadius = TEST_ARRIVAL_RADIUS,
            ),
        )
        heading("Placed")
        line("id", placed.id)
        line("kind", "${placed.kind.displayName} · ${placed.lifetime?.toString() ?: "permanent"}")
        line("island", placed.location.island.displayName)
        line("visible", context.markers[placed.id]?.isVisible?.yesNo() ?: "no")
    }

    private fun markList() {
        val all = context.markers.all()
        heading("Markers (${all.size})")
        if (all.isEmpty()) {
            tell("None. /sqmark place <kind>")
            return
        }
        for (tracked in all) {
            line(
                tracked.marker.id.take(ID_WIDTH),
                buildString {
                    append(tracked.marker.kind.name.lowercase())
                    append(" · ")
                    // The island always, because "not drawing" and "on another island" look identical.
                    append(tracked.marker.location.island.displayName)
                    append(" · ")
                    append(tracked.distance?.let { "${it.toInt()}m" } ?: "elsewhere")
                    if (!tracked.isVisible) append(" · hidden")
                    if (tracked.hasArrived) append(" · arrived")
                    tracked.marker.remaining(System.currentTimeMillis())?.let { append(" · $it left") }
                },
            )
        }
    }

    private fun markRoute() {
        val route = context.markers.route()
        heading("Route (${route.size})")
        if (route.isEmpty()) tell("Nothing routed.")
        for (tracked in route) {
            line("${tracked.marker.routeOrder}", tracked.marker.label.ifEmpty { tracked.marker.id })
        }
    }

    private fun markClear(kindName: String?) {
        val kind = MarkerKind.entries.firstOrNull { it.name.equals(kindName, ignoreCase = true) }
        if (kind == null) {
            var removed = 0
            for (tracked in context.markers.all()) {
                if (context.markers.remove(tracked.marker.id)) removed++
            }
            tell("Removed $removed marker(s).")
            return
        }
        tell("Removed ${context.markers.removeAll(kind)} ${kind.displayName} marker(s).")
    }

    private fun markAck(markerId: String?, answer: String?) {
        val tracked = markerId?.let { name ->
            context.markers.all().firstOrNull { it.marker.id.startsWith(name, ignoreCase = true) }
        }
        if (tracked == null) {
            error("No such marker. /sqmark list")
            return
        }
        val acknowledgement = Acknowledgement.entries.firstOrNull { it.name.equals(answer, ignoreCase = true) }
            ?: Acknowledgement.SEEN
        val me = client.localPlayerId?.let { dev.th7bo.sidequest.platform.player.PlayerId.of(it) }
        if (me == null) {
            error("Not in a world.")
            return
        }
        context.markers.acknowledge(tracked.marker.id, me, acknowledgement)
        line("acknowledged", "${tracked.marker.id.take(ID_WIDTH)} as $acknowledgement")
    }

    private fun markerNames(): List<String> = context.markers.all().map { it.marker.id.take(ID_WIDTH) }

    // -- cinematics ----------------------------------------------------------

    /**
     * Drives the cinematic runtime.
     *
     * `safety` is the one that earns its place. A cinematic that does not appear has nine possible causes that
     * look identical from the player's chair, and this prints every one that currently applies — which is a
     * question nothing else can answer, because the answer changes second to second.
     */
    private fun cine(arguments: List<String>) {
        when (arguments.firstOrNull()?.lowercase()) {
            null, "safety" -> cineSafety()
            "play" -> cinePlay()
            "queue" -> cineQueue()
            "skip" -> {
                context.cinematics.skip()
                tell("Skipped, if anything was playing.")
            }
            "replay" -> {
                val id = arguments.getOrNull(1)?.let { name ->
                    context.cinematics.history().firstOrNull { it.id.value.endsWith(name, ignoreCase = true) }
                }
                if (id == null) {
                    error("Nothing by that name has played. /sqcine queue")
                } else {
                    line("replay", context.cinematics.replay(id.id)?.describe() ?: "not found")
                }
            }
            "trace" -> cineTrace()
            else -> {
                heading("Cinematics")
                tell(CINE_VERBS.joinToString(" · "))
            }
        }
    }

    private fun cineSafety() {
        heading("Cinematic safety")
        val safety = context.cinematics.safety()
        line("safe now", safety.isSafe.yesNo())
        line("why not", if (safety.isSafe) "—" else safety.explain())
        // Refused is a different thing from unsafe, and the difference decides whether waiting will help.
        line("refused", safety.isRefused.yesNo())
        line("waiting", "${context.cinematics.queued().size}")
        line("played this session", "${context.cinematics.history().size}")
    }

    /** Plays one built here, so the whole runtime can be seen without waiting for a rare drop. */
    private fun cinePlay() {
        heading("Test cinematic")
        val disposition = context.cinematics.submit(
            Cinematic(
                id = TEST_CINEMATIC,
                title = "Developer cinematic",
                // SHOW_ANYWAY, because somebody who typed this asked for it. The gate still refuses when there
                // is nowhere to draw, which is the distinction worth demonstrating.
                policy = CinematicPolicy.SHOW_ANYWAY,
                components = listOf(
                    CinematicComponent.Letterbox(),
                    CinematicComponent.Background(),
                    CinematicComponent.Title("RARE DROP", colour = 0xFFAA00),
                    CinematicComponent.Subtitle("Developer cinematic"),
                    CinematicComponent.AnimatedNumber(1_234_567, suffix = " coins"),
                    CinematicComponent.ProgressBar(0.7f, "collection"),
                    CinematicComponent.RewardReveal("+1 Hyperion"),
                    CinematicComponent.Sound(testSoundFor(SoundGroup.INTERFACE)),
                    // Deliberately undrawable, to show that it degrades rather than failing.
                    CinematicComponent.Particles(SqId.sidequest("dev.sparkle")),
                ),
            ),
        )
        line("result", disposition.describe())
        if (disposition !is CinematicDisposition.Played) {
            tell("Run /sqcine safety to see what stopped it.")
        }
    }

    private fun cineQueue() {
        val waiting = context.cinematics.queued()
        heading("Waiting (${waiting.size})")
        if (waiting.isEmpty()) tell("Nothing held.")
        for (queued in waiting) {
            line(
                queued.cinematic.id.value,
                "${queued.cinematic.priority}" +
                    (if (queued.count > 1) " ×${queued.count}" else "") +
                    " · expires in ${queued.cinematic.expiry}",
            )
        }
        val history = context.cinematics.history()
        if (history.isNotEmpty()) {
            heading("Played (${history.size})")
            for (past in history.take(TRACE_LINES)) line(past.id.value, past.title.ifEmpty { "—" })
        }
    }

    private fun cineTrace() {
        val trace = context.cinematics.trace()
        heading("Recent dispositions (${trace.size})")
        if (trace.isEmpty()) tell("Nothing submitted yet.")
        for (disposition in trace.take(TRACE_LINES)) {
            line(disposition.cinematic.id.value, disposition.describe())
        }
    }

    /** What became of it, in a few words. The whole point of the disposition type. */
    private fun CinematicDisposition.describe(): String = when (this) {
        is CinematicDisposition.Played -> "played"
        is CinematicDisposition.Compacted -> "shown as a notification"
        is CinematicDisposition.Queued -> "queued at $position: $reason"
        is CinematicDisposition.Merged -> "merged into $into (×$count)"
        is CinematicDisposition.Logged -> "logged only: $reason"
        is CinematicDisposition.Dropped -> "dropped: $reason"
    }

    private fun cinematicNames(): List<String> =
        context.cinematics.history().map { it.id.value.substringAfter(':') }.distinct()

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

    // -- simulations ---------------------------------------------------------

    /*
     * Each of these stands in for something that is genuinely awkward to arrange.
     *
     * The point is not that the code cannot be read. It is that the *interesting* part of these features is
     * how they look and feel, and half of them only occur in conditions nobody wants to reproduce on demand:
     * a rare drop is once a week, a Kuudra clear needs four other people, and dying repeatedly to check a
     * death marker gets old fast.
     *
     * They go through the real path — the cinematic director, the marker service, the notification manager —
     * rather than drawing anything themselves, so what they show is what would actually happen. A simulation
     * that bypassed the safety gate would be a simulation of something the mod never does.
     */

    /**
     * A rare drop, from the line Hypixel would actually send.
     *
     * Goes in at the *chat parser* rather than at the cinematic, which is the difference between a simulation
     * and a demonstration. Everything downstream is the real thing: the pattern has to match, the drop event
     * has to carry the right item, the rarity threshold and the ignore list have to let it through, and the
     * director has to decide the moment is safe. A version that submitted a cinematic directly — which is
     * what this was — proved that cinematics work, which was never in doubt.
     *
     * Cycles through the tiers so repeated runs exercise more than one branch, and the line is one of the
     * pattern's own fixtures so it cannot drift from what the parser is tested against.
     */
    private fun simulateRareDrop() {
        val line = DROP_LINES[dropIndex % DROP_LINES.size]
        dropIndex++
        dev.th7bo.sidequest.Sidequest.platform.simulateChatLine(line)
        // Cleaned rather than escaped. Echoing `&9&l…` shows the codes as literal text, which is unreadable
        // and also says nothing useful: what matters is the line the parser sees, not how it was coloured.
        tell("Sent: " + HypixelText.clean(line))
        tell("If nothing appeared: /sqdrops for the threshold, /sqcine safety for the gate.")
    }

    /** Which fixture the next `/sqtest drop` uses. */
    private var dropIndex = 0

    /** A trophy catch, through the same door. */
    private fun simulateTrophy() {
        dev.th7bo.sidequest.Sidequest.platform.simulateChatLine(TROPHY_LINE)
        tell("Sent a trophy catch.")
    }

    private fun simulateAchievement() {
        context.cinematics.submit(PreviewData.slowAchievement)
        context.notifications.notify(
            notification(
                category = NotificationCategory.PROGRESSION,
                title = "Achievement unlocked",
                subtitle = "Catacombs Floor 7 — first clear",
                priority = NotificationPriority.HIGH,
            ),
        )
        tell("Played a long cinematic and posted its toast.")
    }

    /**
     * A death, as far as the marker system is concerned.
     *
     * Places the marker where you stand, which is what a real death does — and is why this is here rather
     * than in a headless test: whether a death marker is *useful* depends on where it ends up relative to
     * where you respawn, and only a real world can show that.
     */
    private fun simulateDeath() {
        val position = dev.th7bo.sidequest.Sidequest.localPosition()
        if (position == null) {
            error("Not in a world.")
            return
        }
        context.markers.place(
            Marker(
                id = "dev.death",
                kind = MarkerKind.DEATH,
                location = SqLocation(
                    island = context.gameContext.context.island,
                    position = position,
                    profile = context.gameContext.context.profile,
                ),
                label = "You died here",
            ),
        )
        tell("Placed a death marker where you are standing.")
    }

    /**
     * The end of a run, from the line the server sends.
     *
     * Through the parser like the drop, so what is exercised is the pattern, the derived event and whatever
     * listens for it — rather than a cinematic composed here that nothing in the mod would ever build.
     */
    private fun simulateRunEnd(kind: String) {
        val line = when (kind) {
            "dungeon" -> DUNGEON_LINE
            else -> KUUDRA_LINE
        }
        dev.th7bo.sidequest.Sidequest.platform.simulateChatLine(line)
        tell("Sent: " + HypixelText.clean(line))
        if (kind == "kuudra") {
            // Said out loud rather than left implicit: the pattern this exercises has no recorded line of
            // its own, so a pass here is this command agreeing with itself.
            tell("Note: the Kuudra pattern has no recorded fixture, so this only proves the two agree.")
        }
    }

    private fun simulatePing() {
        val position = dev.th7bo.sidequest.Sidequest.localPosition()
        if (position == null) {
            error("Not in a world.")
            return
        }
        // Ten blocks ahead of where you are, so it is something to look at rather than something you are
        // standing inside — a marker at your own feet demonstrates nothing about how one reads at distance.
        context.markers.place(
            Marker(
                id = "dev.ping",
                kind = MarkerKind.PING,
                location = SqLocation(
                    island = context.gameContext.context.island,
                    position = SqPosition(position.x, position.y, position.z + PING_DISTANCE),
                    profile = context.gameContext.context.profile,
                ),
                label = "look here",
            ),
        )
        tell("Pinged ${PING_DISTANCE.toInt()} blocks away. Turn around to see the edge indicator.")
    }

    /** A field of them, spread over distance so fading, ordering and edge indicators all do something. */
    private fun simulateWaypoint() {
        val position = dev.th7bo.sidequest.Sidequest.localPosition()
        if (position == null) {
            error("Not in a world.")
            return
        }
        val island = context.gameContext.context.island
        var placed = 0
        for (marker in PreviewData.markerField(island, position)) {
            context.markers.place(marker)
            placed++
        }
        tell("Placed $placed markers. /sqmark list · /sqmark clear waypoint")
    }

    /**
     * What the mod looks like with no backend.
     *
     * Worth a command because "works offline" is the state most of this group will actually be in most of the
     * time — the server is in somebody's cupboard — and it is the state least likely to be tested by
     * accident.
     */
    private fun simulateOffline() {
        val backend = dev.th7bo.sidequest.Sidequest.platform.backend
        if (backend == null) {
            tell("Already offline: no backend is configured. This is the state to check features in.")
            return
        }
        heading("Offline behaviour")
        line("backend", backend.state.name)
        tell("Nothing here disconnects you — stop the server to test it for real.")
        tell("What should keep working: markers, rules, cinematics, notifications, sounds, cosmetics you own.")
    }

    /** Registered cosmetic ids, for completion. Derived, so it cannot drift from what `wear` accepts. */
    private fun cosmeticNames(): List<String> = context.cosmetics.definitions().map { it.id.toString() }

    // -- problems ------------------------------------------------------------

    /**
     * What has gone wrong, by kind rather than by occurrence.
     *
     * The id is the useful part. A person can read "SQ-4F2A9C" out of chat and it names the *bug* rather than
     * one hit of it, so it can be looked up, counted, and recognised as one already known about.
     */
    private fun errors(arguments: List<String>) {
        when (arguments.firstOrNull()?.lowercase()) {
            null, "list" -> {
                val summary = context.errors.summary()
                heading("Problems")
                if (summary.isClean) {
                    tell("Nothing has gone wrong this session.")
                    return
                }
                line("summary", summary.toString())
                for (record in context.errors.recent()) {
                    line(
                        record.id.toString(),
                        "x${record.count} ${record.category}/${record.owner} ${record.message.take(PROBLEM_WIDTH)}",
                    )
                }
                tell("/sqerr show <id> for the detail")
            }

            "show" -> {
                val wanted = arguments.getOrNull(1)
                if (wanted == null) {
                    error("Which one? /sqerr show <id>")
                    return
                }
                // Accepted with or without the SQ- prefix, because that is how it is displayed and somebody
                // reading it back will type what they saw.
                val id = ErrorId(wanted.removePrefix("SQ-").removePrefix("sq-").uppercase())
                val record = context.errors.get(id)
                if (record == null) {
                    error("No problem with that id. /sqerr list")
                    return
                }
                heading(record.id.toString())
                line("category", "${record.category} / ${record.owner}")
                line("seen", "${record.count} time(s)")
                line("first", java.time.Instant.ofEpochMilli(record.firstSeenMillis).toString())
                line("last", java.time.Instant.ofEpochMilli(record.lastSeenMillis).toString())
                line("message", record.message)
                record.cause?.let { line("cause", it) }
            }

            "clear" -> {
                context.errors.clear()
                tell("Cleared.")
            }

            else -> error("Usage: /sqerr [${ERROR_VERBS.joinToString("|")}] [id]")
        }
    }

    // -- cosmetics -----------------------------------------------------------

    /**
     * Tries cosmetics on, and explains what is not showing.
     *
     * `resolve` is the one that earns its place. Everything about this feature that can surprise somebody —
     * a friends-only cosmetic on a stranger, an aura losing a conflict, an asset that has not arrived — looks
     * identical from outside: nothing appears. This prints the reason for each.
     */
    private fun cosmetics(arguments: List<String>) {
        when (arguments.firstOrNull()?.lowercase()) {
            null, "list" -> {
                heading("Cosmetics")
                val all = context.cosmetics.definitions()
                if (all.isEmpty()) {
                    tell("None registered. /sqcos preview loads a sample set.")
                    return
                }
                for (cosmetic in all.sortedBy { it.slot.ordinal }) {
                    val worn = context.cosmetics.loadout()[cosmetic.slot]?.cosmeticId == cosmetic.id
                    line(
                        (if (worn) "* " else "  ") + cosmetic.slot.name.lowercase(),
                        "${cosmetic.displayName} · ${cosmetic.rarity.displayName} · ${cosmetic.id}",
                    )
                }
            }

            "preview" -> {
                // The sample set exists because arranging every awkward case by hand — a conflicting pair, a
                // timed one, one whose asset is missing — is most of an afternoon.
                var added = 0
                for (cosmetic in PreviewData.cosmetics) {
                    context.cosmetics.register(cosmetic)
                    added++
                }
                context.cosmetics.wear(PreviewData.fullLoadout(System.currentTimeMillis()))
                tell("Registered $added preview cosmetics and put on a full loadout. /sqcos resolve")
            }

            "wear" -> {
                val wanted = arguments.getOrNull(1)
                if (wanted == null) {
                    error("Which one? /sqcos wear <id>")
                    return
                }
                val cosmetic = context.cosmetics.definitions().firstOrNull { it.id.toString() == wanted }
                if (cosmetic == null) {
                    error("No such cosmetic. /sqcos list")
                    return
                }
                val refusal = context.cosmetics.equip(cosmetic.slot, cosmetic.id)
                if (refusal != null) error(refusal) else tell("Wearing ${cosmetic.displayName}.")
            }

            "remove" -> {
                val slotName = arguments.getOrNull(1)?.uppercase()
                val slot = CosmeticSlot.entries.firstOrNull { it.name == slotName }
                if (slot == null) {
                    error("Which slot? One of: ${CosmeticSlot.entries.joinToString { it.name.lowercase() }}")
                    return
                }
                context.cosmetics.unequip(slot)
                tell("Removed whatever was in ${slot.displayName}.")
            }

            "dress" -> dress(arguments.getOrNull(1))

            "resolve" -> resolveCosmetics(arguments.getOrNull(1))

            "settings" -> cosmeticSettings(arguments.drop(1))

            else -> error("Usage: /sqcos [${COSMETIC_VERBS.joinToString("|")}] [id|slot]")
        }
    }

    /**
     * Puts the preview loadout on somebody else, as if it had arrived from them.
     *
     * The only way to actually *look* at a nametag cosmetic. Goes through `setRemoteLoadout`, which is the
     * same door the realtime stream uses, so what appears is what would appear if they really were wearing
     * it — rather than a special case that proves nothing about the real path.
     */
    private fun dress(who: String?) {
        if (who == null) {
            error("Who? /sqcos dress <player>")
            return
        }
        val target = context.players.resolveUsername(who)
        if (target == null) {
            error("Never seen anybody called '$who'. They have to be in your tab list.")
            return
        }
        for (cosmetic in PreviewData.cosmetics) context.cosmetics.register(cosmetic)
        context.cosmetics.setRemoteLoadout(target.id, PreviewData.fullLoadout(System.currentTimeMillis()))
        tell("Dressed ${target.displayName}. Look at them — /sqcos resolve ${target.username} to see why anything is missing.")
    }

    /** Prints what would be drawn for somebody, and why the rest would not be. */
    private fun resolveCosmetics(who: String?) {
        val subject = when (who) {
            null -> client.localPlayerId?.let { dev.th7bo.sidequest.platform.player.PlayerId.of(it) }
            else -> context.players.resolveUsername(who)?.id
        }
        if (subject == null) {
            error(if (who == null) "Not in a world." else "Never seen anybody called '$who'.")
            return
        }

        val resolution = context.cosmetics.resolve(subject)
        val name = context.players.byId(subject)?.displayName ?: subject.toString()
        heading("Cosmetics on $name")

        // The nametag as it would be drawn. Printed because **you cannot see your own** — Minecraft's
        // `shouldShowName` excludes the camera entity, so the player somebody is most likely to be testing
        // on is the one they cannot look at.
        val nametag = NametagDecorator.preview(resolution, name)
        if (nametag == null) {
            line("nametag", "unchanged — nothing worn contributes text")
        } else {
            line("nametag", nametag)
            if (subject == client.localPlayerId?.let { dev.th7bo.sidequest.platform.player.PlayerId.of(it) }) {
                tell("  (your own nametag is never drawn for you — look at somebody else, or /sqcos dress them)")
            }
        }
        if (resolution.shown.isEmpty()) {
            line("shown", "nothing")
        } else {
            for (shown in resolution.shown) {
                line(
                    shown.cosmetic.slot.name.lowercase(),
                    shown.cosmetic.displayName +
                        (if (shown.isFallback) " (standing in)" else "") +
                        (if (shown.isAnimated) " · animated" else ""),
                )
            }
        }
        if (resolution.hidden.isEmpty()) return
        heading("Not showing")
        for (hidden in resolution.hidden) {
            line(hidden.slot.name.lowercase(), "${hidden.cosmeticId} — ${hidden.reason.explanation}" +
                (hidden.detail?.let { ": $it" } ?: ""))
        }
    }

    private fun cosmeticSettings(arguments: List<String>) {
        val current = context.cosmetics.settings
        when (arguments.firstOrNull()?.lowercase()) {
            null -> {
                heading("Cosmetic settings")
                line("enabled", current.isEnabled.onOff())
                line("skins and capes", current.showAppearanceOverrides.onOff())
                line("effects", current.showEffects.onOff())
                line("jokes", current.showJokeCosmetics.onOff())
                line("reduced animation", current.reducedAnimation.onOff())
                tell("/sqcos settings <${COSMETIC_SETTINGS.joinToString("|")}> toggles one")
            }
            "on" -> set(current.copy(isEnabled = true), "Cosmetics on.")
            "off" -> set(current.copy(isEnabled = false), "Cosmetics off.")
            "skins" -> set(
                current.copy(showAppearanceOverrides = !current.showAppearanceOverrides),
                "Skin and cape overrides ${(!current.showAppearanceOverrides).onOff()}.",
            )
            "effects" -> set(
                current.copy(showEffects = !current.showEffects),
                "Effects ${(!current.showEffects).onOff()}.",
            )
            "jokes" -> set(
                current.copy(showJokeCosmetics = !current.showJokeCosmetics),
                "Joke cosmetics ${(!current.showJokeCosmetics).onOff()}.",
            )
            "animation" -> set(
                current.copy(reducedAnimation = !current.reducedAnimation),
                "Reduced animation ${(!current.reducedAnimation).onOff()}.",
            )
            else -> error("One of: ${COSMETIC_SETTINGS.joinToString(", ")}")
        }
    }

    private fun set(settings: CosmeticSettings, message: String) {
        context.cosmetics.settings = settings
        tell(message)
    }

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
        val TEST_CINEMATIC = SqId.sidequest("dev.cinematic")

        val CINE_VERBS = listOf("play", "safety", "queue", "skip", "replay", "trace")

        val MARK_VERBS = listOf("place", "list", "route", "clear", "ack")

        /** How much of a marker id is shown. Enough to be unambiguous, short enough to read. */
        const val ID_WIDTH = 8

        /** Close enough to demonstrate arrival by walking a few blocks. */
        const val TEST_ARRIVAL_RADIUS = 4.0

        /**
         * What `/sqtest` accepts, and what it completes.
         *
         * One list for both. The dispatch below also answers to a few plurals, which are deliberately *not*
         * suggested: an alias exists to forgive a typo, and completing both spellings implies a difference.
         */
        val TESTABLE = listOf(
            "notify", "sound", "queue", "presence", "chat", "item", "rule",
            // The simulations. Each stands in for something that is genuinely awkward to arrange: a rare
            // drop happens once a week, a Kuudra clear needs four other people, and dying on purpose to
            // check a death marker gets old quickly.
            "drop", "trophy", "achievement", "death", "dungeon", "kuudra", "ping", "waypoint", "cosmetic", "offline",
        )

        val ERROR_VERBS = listOf("list", "show", "clear")

        val COSMETIC_VERBS = listOf("list", "wear", "remove", "preview", "dress", "resolve", "settings")

        val COSMETIC_SETTINGS = listOf("on", "off", "skins", "effects", "jokes", "animation")

        /** How many problems `/sqstatus` prints before pointing at `/sqerr`. */
        const val PROBLEM_LINES = 5

        /** How much of a problem's message fits on a chat line next to its id. */
        const val PROBLEM_WIDTH = 60

        /** Far enough ahead to look at rather than stand inside. */
        const val PING_DISTANCE = 10.0

        /**
         * Real Hypixel drop lines, taken from the chat rules' own fixtures.
         *
         * Shared with the patterns rather than written out again here: a simulation using a line the parser
         * is not tested against would eventually simulate something the mod cannot read.
         */
        val DROP_LINES = listOf(
            "§r§6§lRARE DROP! §r§5Tarantula Talisman §r§b(+100% ✯ Magic Find)",
            "§9§lVERY RARE DROP!  §r§7(§r§f§r§5Revenant Catalyst§r§7) §r§b(+158% ✯ Magic Find)",
            "§d§lCRAZY RARE DROP!  §r§7(§r§f§r§fPocket Espresso Machine§r§7) §r§b(+158% ✯ Magic Find)",
            "§5§lPRAY TO RNGESUS DROP!  §r§7(§r§f§r§5Warden Heart§r§7) §r§b(+158% ✯ Magic Find)",
            "§6§lPET DROP! §r§5Baby Yeti §r§b(+168% ✯ Magic Find)",
        )

        const val TROPHY_LINE = "§6\uE02A §r§6§lTROPHY FISH! §r§fYou caught a §r§9Lavahorse §r§6§lGOLD§r§f!"

        /** The dungeon rule's own fixture, indentation included — the pattern requires it. */
        const val DUNGEON_LINE = "                                 Master Mode The Catacombs - Floor V"

        /**
         * A Kuudra clear.
         *
         * Unlike every other line here this is **not** a recorded fixture: the Kuudra pattern was taken from
         * SkyHanni without a `REGEX-TEST`, so there is nothing to copy. It matches the pattern, which is all
         * anybody can say about it until somebody records a real one.
         */
        const val KUUDRA_LINE = "§c§lKUUDRA DOWN!"

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
