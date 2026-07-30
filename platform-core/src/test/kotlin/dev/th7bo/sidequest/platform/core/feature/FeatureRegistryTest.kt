package dev.th7bo.sidequest.platform.core.feature

import dev.th7bo.sidequest.platform.command.CommandCollisionException
import dev.th7bo.sidequest.platform.core.chat.DefaultChatParser
import dev.th7bo.sidequest.platform.core.context.DefaultGameContextService
import dev.th7bo.sidequest.platform.asset.AssetManager
import dev.th7bo.sidequest.platform.audio.SoundSink
import dev.th7bo.sidequest.platform.core.audio.DefaultSoundManager
import dev.th7bo.sidequest.platform.core.notification.DefaultNotificationManager
import dev.th7bo.sidequest.platform.core.party.DefaultPartyService
import dev.th7bo.sidequest.platform.notification.NotificationSink
import dev.th7bo.sidequest.platform.core.permission.DefaultPermissionService
import dev.th7bo.sidequest.platform.core.storage.JsonFileStorage
import dev.th7bo.sidequest.platform.cinematic.CinematicSink
import dev.th7bo.sidequest.platform.core.cinematic.DefaultCinematicDirector
import dev.th7bo.sidequest.platform.core.marker.DefaultMarkerService
import dev.th7bo.sidequest.platform.core.player.DefaultPlayerDirectory
import dev.th7bo.sidequest.platform.testkit.FakeGameClient
import dev.th7bo.sidequest.platform.core.rule.DefaultRuleEngine
import dev.th7bo.sidequest.platform.player.PlayerTargeting
import dev.th7bo.sidequest.platform.core.command.DefaultCommandRegistry
import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.core.log.LoggerFactory
import dev.th7bo.sidequest.platform.event.ClientTickEvent
import dev.th7bo.sidequest.platform.event.FeatureStateChangedEvent
import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.feature.DuplicateFeatureException
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureCycleException
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.FeatureRefusal
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.feature.feature
import dev.th7bo.sidequest.platform.feature.listen
import dev.th7bo.sidequest.platform.game.GameVersion
import dev.th7bo.sidequest.platform.game.VersionRange
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.log.LogCategory
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.RecordingLogSink
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

class FeatureRegistryTest {

    private lateinit var scheduler: TestScheduler
    private lateinit var bus: DefaultEventBus
    private lateinit var commands: DefaultCommandRegistry
    private lateinit var chat: DefaultChatParser
    private lateinit var gameContext: DefaultGameContextService
    private lateinit var players: DefaultPlayerDirectory

    @TempDir
    lateinit var storageRoot: java.nio.file.Path
    private lateinit var sink: RecordingLogSink
    private lateinit var loggers: LoggerFactory

    @BeforeEach
    fun setUp() {
        scheduler = TestScheduler()
        sink = RecordingLogSink()
        loggers = LoggerFactory(sink)
        bus = DefaultEventBus(scheduler, loggers.create(LogCategory.EVENT, SqId.sidequest("bus")))
        commands = DefaultCommandRegistry()
        chat = DefaultChatParser(bus, NoopLogger)
        gameContext = DefaultGameContextService(bus, NoopLogger)
        players = DefaultPlayerDirectory(bus)
    }

    private fun registry(
        version: GameVersion = GameVersion(26, 2),
        enabledByUser: (FeatureDescriptor) -> Boolean = { it.enabledByDefault },
    ) = DefaultFeatureRegistry(
        // Named, not positional: the registry gains a service every time a plan section lands, and a
        // positional call silently reorders into the wrong arguments when one is inserted.
        gameVersion = version,
        events = bus,
        scheduler = scheduler,
        commands = commands,
        chat = chat,
        gameContext = gameContext,
        players = players,
        targeting = PlayerTargeting.None,
        party = DefaultPartyService(bus, players, NoopLogger),
        notifications = DefaultNotificationManager(NotificationSink.None, gameContext, NoopLogger),
        sounds = DefaultSoundManager(SoundSink.None, NoopLogger),
        cinematics = DefaultCinematicDirector(
            sink = CinematicSink.None,
            context = gameContext,
            client = FakeGameClient(),
            notifications = DefaultNotificationManager(NotificationSink.None, gameContext, NoopLogger),
            events = bus,
            log = NoopLogger,
        ),
        markers = DefaultMarkerService(
            context = gameContext,
            events = bus,
            log = NoopLogger,
            localPosition = { null },
            localPlayer = { null },
        ),
        rules = DefaultRuleEngine(
            events = bus,
            context = gameContext,
            party = DefaultPartyService(bus, players, NoopLogger),
            players = players,
            log = NoopLogger,
            localPlayer = { null },
        ),
        assets = AssetManager.None,
        storage = JsonFileStorage(storageRoot, NoopLogger),
        permissions = DefaultPermissionService(NoopLogger, localPlayer = { null }),
        loggers = loggers,
        isEnabledByUser = enabledByUser,
    )

    // ---------------------------------------------------------------
    // Declaration
    // ---------------------------------------------------------------

    @Test
    fun `the DSL produces the descriptor the plan asks for`() {
        val declared = feature("drops.rare_animation") {
            displayName = "Rare drop animation"
            category = FeatureCategory.VISUALS
            description = "Plays a cinematic when something rare drops"
            supportedVersions = VersionRange.atLeast("26.1")
            isExperimental = true
            dependsOn("sidequest:core.context")
            needsPermission("sidequest:evidence.upload")
            subscribesTo("sidequest:realtime.drops")
        }

        val descriptor = declared.descriptor
        assertEquals(SqId.sidequest("drops.rare_animation"), descriptor.id)
        assertEquals(FeatureCategory.VISUALS, descriptor.category)
        assertTrue(descriptor.isExperimental)
        assertEquals(setOf(SqId.sidequest("core.context")), descriptor.dependencies)
        assertEquals(setOf(SqId.sidequest("evidence.upload")), descriptor.backendPermissions)
        assertEquals(setOf(SqId.sidequest("realtime.drops")), descriptor.networkSubscriptions)
    }

    @Test
    fun `a display name is derived from the id when not given`() {
        assertEquals("Rare animation", feature("drops.rare_animation") {}.descriptor.displayName)
    }

    @Test
    fun `registering the same id twice is refused`() {
        val registry = registry()
        registry.register(feature("a") {})
        assertThrows<DuplicateFeatureException> { registry.register(feature("a") {}) }
    }

    @Test
    fun `a dependency cycle is rejected at registration`() {
        // At registration, not at enable: a cycle is a programming error and should
        // surface when the code that causes it loads, naming the feature that closed it.
        val registry = registry()
        registry.register(feature("a") { dependsOn("sidequest:b") })
        registry.register(feature("b") { dependsOn("sidequest:c") })

        val thrown = assertThrows<FeatureCycleException> {
            registry.register(feature("c") { dependsOn("sidequest:a") })
        }
        assertTrue(thrown.cycle.size >= 3, "the message shows the loop: ${thrown.cycle}")
    }

    // ---------------------------------------------------------------
    // Enabling
    // ---------------------------------------------------------------

    @Test
    fun `declared listeners and commands are installed on enable`() {
        val registry = registry()
        var ticks = 0
        registry.register(
            feature("counter") {
                listen<ClientTickEvent> { ticks++ }
                command("count") {}
            },
        )

        assertNull(registry.enable(SqId.sidequest("counter")))

        bus.post(ClientTickEvent(1))
        assertEquals(1, ticks)
        assertNotNull(commands["count"])
    }

    @Test
    fun `dependencies are enabled first`() {
        val registry = registry()
        val order = mutableListOf<String>()
        registry.register(feature("leaf") { onEnable { order.add("leaf") } })
        registry.register(
            feature("trunk") {
                dependsOn("sidequest:leaf")
                onEnable { order.add("trunk") }
            },
        )

        registry.enable(SqId.sidequest("trunk"))

        assertEquals(listOf("leaf", "trunk"), order)
    }

    @Test
    fun `a feature outside its version range is refused, not crashed`() {
        val registry = registry(version = GameVersion(26, 1, 2))
        registry.register(feature("newer") { supportedVersions = VersionRange.atLeast("26.2") })

        val refusal = registry.enable(SqId.sidequest("newer"))

        assertEquals(FeatureRefusal.Reason.UNSUPPORTED_VERSION, refusal?.reason)
        assertFalse(registry[SqId.sidequest("newer")]!!.isEnabled)
    }

    @Test
    fun `a feature whose dependency is missing is refused`() {
        val registry = registry()
        registry.register(feature("needy") { dependsOn("sidequest:absent") })

        assertEquals(
            FeatureRefusal.Reason.DEPENDENCY_REFUSED,
            registry.enable(SqId.sidequest("needy"))?.reason,
        )
    }

    @Test
    fun `a feature the user switched off stays off`() {
        val registry = registry(enabledByUser = { it.id != SqId.sidequest("off") })
        registry.register(feature("off") {})

        assertEquals(
            FeatureRefusal.Reason.DISABLED_BY_CONFIG,
            registry.enable(SqId.sidequest("off"))?.reason,
        )
    }

    @Test
    fun `enableAll starts everything it can and reports the rest`() {
        val registry = registry(version = GameVersion(26, 1, 2))
        registry.register(feature("fine") {})
        registry.register(feature("newer") { supportedVersions = VersionRange.atLeast("26.2") })
        registry.register(feature("broken") { onEnable { error("boom") } })

        val refusals = registry.enableAll()

        assertTrue(registry[SqId.sidequest("fine")]!!.isEnabled, "one bad feature must not stop the rest")
        assertEquals(2, refusals.size)
    }

    // ---------------------------------------------------------------
    // Teardown — the guarantee the whole design exists for
    // ---------------------------------------------------------------

    @Test
    fun `disabling undoes every registration the feature made`() {
        val registry = registry()
        registry.register(
            feature("noisy") {
                listen<ClientTickEvent> {}
                command("noisy") {}
                onEnable { context ->
                    context.every(1.seconds) {}
                    context.listen<ClientTickEvent> {}
                }
            },
        )

        registry.enable(SqId.sidequest("noisy"))
        assertEquals(2, bus.listenerCount())
        assertEquals(1, commands.all().size)
        assertEquals(1, scheduler.jobCount())

        registry.disable(SqId.sidequest("noisy"))

        assertEquals(0, bus.listenerCount(), "a listener outliving its feature fires against dead state")
        assertEquals(0, commands.all().size)
        assertEquals(0, scheduler.jobCount())
    }

    @Test
    fun `a feature that throws while enabling leaves nothing behind`() {
        // Half-enabled is worse than disabled: the listeners it did register fire against
        // state its `onEnable` never finished setting up.
        val registry = registry()
        registry.register(
            feature("halfway") {
                onEnable { context ->
                    context.listen<ClientTickEvent> {}
                    context.command(dev.th7bo.sidequest.platform.command.CommandSpec("halfway") {})
                    error("failed after registering")
                }
            },
        )

        val refusal = registry.enable(SqId.sidequest("halfway"))

        assertEquals(FeatureRefusal.Reason.FAILED_TO_ENABLE, refusal?.reason)
        assertEquals(0, bus.listenerCount())
        assertEquals(0, commands.all().size)
        assertFalse(registry[SqId.sidequest("halfway")]!!.isEnabled)
    }

    @Test
    fun `dependents are disabled before what they depend on`() {
        val registry = registry()
        val order = mutableListOf<String>()
        registry.register(feature("leaf") { onDisable { order.add("leaf") } })
        registry.register(
            feature("trunk") {
                dependsOn("sidequest:leaf")
                onDisable { order.add("trunk") }
            },
        )
        registry.enableAll()

        registry.disable(SqId.sidequest("leaf"))

        assertEquals(
            listOf("trunk", "leaf"),
            order,
            "a feature must never run for even one dispatch against a dependency that is gone",
        )
    }

    @Test
    fun `using the context after being disabled throws instead of leaking`() {
        val registry = registry()
        lateinit var captured: FeatureContext
        registry.register(feature("escapee") { onEnable { captured = it } })
        registry.enable(SqId.sidequest("escapee"))
        registry.disable(SqId.sidequest("escapee"))

        val thrown = assertThrows<IllegalStateException> { captured.listen<ClientTickEvent> {} }
        assertTrue(thrown.message!!.contains("escapee"), "the message names the offender")
    }

    @Test
    fun `a feature that throws while disabling still ends up disabled`() {
        val registry = registry()
        registry.register(
            feature("stubborn") {
                listen<ClientTickEvent> {}
                onDisable { error("will not go quietly") }
            },
        )
        registry.enable(SqId.sidequest("stubborn"))

        registry.disable(SqId.sidequest("stubborn"))

        assertFalse(registry[SqId.sidequest("stubborn")]!!.isEnabled)
        assertEquals(0, bus.listenerCount(), "its registrations were already gone when onDisable ran")
        assertEquals(1, sink.errors().size, "and the failure is reported rather than swallowed")
    }

    @Test
    fun `re-enabling a feature installs its registrations again`() {
        val registry = registry()
        var ticks = 0
        registry.register(feature("toggle") { listen<ClientTickEvent> { ticks++ } })

        registry.enable(SqId.sidequest("toggle"))
        bus.post(ClientTickEvent(1))
        registry.disable(SqId.sidequest("toggle"))
        bus.post(ClientTickEvent(2))
        registry.enable(SqId.sidequest("toggle"))
        bus.post(ClientTickEvent(3))

        assertEquals(2, ticks, "off means off, and on again means on again")
    }

    // ---------------------------------------------------------------
    // Observability
    // ---------------------------------------------------------------

    @Test
    fun `state changes are announced so optional dependencies can react`() {
        val registry = registry()
        val seen = mutableListOf<String>()
        bus.on<FeatureStateChangedEvent>(OwnerId.PLATFORM) {
            seen.add("${it.featureId}=${it.isEnabled}")
        }
        registry.register(feature("watched") {})

        registry.enable(SqId.sidequest("watched"))
        registry.disable(SqId.sidequest("watched"))

        assertEquals(listOf("sidequest:watched=true", "sidequest:watched=false"), seen)
    }

    @Test
    fun `two features cannot claim the same command`() {
        val registry = registry()
        registry.register(feature("first") { command("sq") {} })
        registry.register(feature("second") { command("sq") {} })

        registry.enable(SqId.sidequest("first"))
        val refusal = registry.enable(SqId.sidequest("second"))

        assertEquals(FeatureRefusal.Reason.FAILED_TO_ENABLE, refusal?.reason)
        assertEquals(
            SqId.sidequest("first"),
            commands["sq"]?.owner?.value,
            "the one that got there first keeps it",
        )
    }

    /**
     * A command declares whether it takes anything, and the declaration follows from what was asked for.
     *
     * The game builds its grammar from this: a bare command gets no argument node, so the client stops
     * offering an `<arguments>` hint for something that takes none and a typo is rejected rather than
     * silently ignored. Getting it from the helper rather than from a fourth boolean nobody remembers to
     * pass is the point.
     */
    @Test
    fun `a command takes arguments only when it says what they are`() {
        val registry = registry()
        registry.register(
            feature("declaring") {
                command("bare") {}
                command("withUsage", usage = "<thing>") {}
                command("withCompletions", completions = { listOf("a", "b") }) {}
            },
        )
        registry.enable(SqId.sidequest("declaring"))

        assertFalse(commands["bare"]!!.spec.takesArguments, "a bare command must not advertise arguments")
        assertTrue(commands["withUsage"]!!.spec.takesArguments)
        assertTrue(commands["withCompletions"]!!.spec.takesArguments)
        assertEquals(listOf("a", "b"), commands["withCompletions"]!!.spec.completions(emptyList()))
        // The default has to be an empty list rather than null, so the bridge never branches on it.
        assertEquals(emptyList<String>(), commands["bare"]!!.spec.completions(emptyList()))
    }

    /** A usage string on a command the game would refuse arguments for is a contradiction, caught at declaration. */
    @Test
    fun `a usage without arguments is rejected`() {
        assertThrows<IllegalArgumentException> {
            dev.th7bo.sidequest.platform.command.CommandSpec("x", usage = "<thing>", takesArguments = false) {}
        }
    }

    @Test
    fun `a command collision names both owners`() {
        val thrown = assertThrows<CommandCollisionException> {
            commands.register(OwnerId(SqId.sidequest("a")), dev.th7bo.sidequest.platform.command.CommandSpec("x") {})
            commands.register(OwnerId(SqId.sidequest("b")), dev.th7bo.sidequest.platform.command.CommandSpec("x") {})
        }
        assertTrue(thrown.message!!.contains("sidequest:a") && thrown.message!!.contains("sidequest:b"))
    }

    // ---------------------------------------------------------------
    // A feature written the way features are meant to be written
    // ---------------------------------------------------------------

    private class TickCounter : Feature {
        var ticks = 0

        override val descriptor = FeatureDescriptor(
            id = SqId.sidequest("example.tick_counter"),
            displayName = "Tick counter",
            category = FeatureCategory.DEVELOPER,
            description = "Counts client ticks",
        )

        override fun onEnable(context: FeatureContext) {
            context.listen<ClientTickEvent> { ticks++ }
            context.command("ticks") { context.log.info { "$ticks" } }
        }
    }

    @Test
    fun `a hand-written feature needs nothing but the context`() {
        val registry = registry()
        val counter = TickCounter()
        registry.register(counter)
        registry.enableAll()

        repeat(3) { bus.post(ClientTickEvent(it.toLong())) }
        assertEquals(3, counter.ticks)

        registry.disableAll()
        bus.post(ClientTickEvent(99))
        assertEquals(3, counter.ticks)
        assertEquals(0, bus.listenerCount())
    }

    @Test
    fun `events posted by a feature are marked as derived`() {
        val registry = registry()
        var source: dev.th7bo.sidequest.platform.event.EventSource? = null
        class Custom : SidequestEvent()

        bus.on<Custom>(OwnerId.PLATFORM) { source = it.metadata.source }
        registry.register(feature("poster") { onEnable { it.post(Custom()) } })
        registry.enable(SqId.sidequest("poster"))

        assertEquals(dev.th7bo.sidequest.platform.event.EventSource.DERIVED, source)
    }
}
