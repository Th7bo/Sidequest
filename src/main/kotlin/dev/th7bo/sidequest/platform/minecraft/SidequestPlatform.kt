package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.command.CommandRegistry
import dev.th7bo.sidequest.platform.core.command.DefaultCommandRegistry
import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.core.feature.DefaultFeatureRegistry
import dev.th7bo.sidequest.platform.core.log.LoggerFactory
import dev.th7bo.sidequest.platform.core.scheduler.DefaultScheduler
import dev.th7bo.sidequest.platform.event.ClientShutdownEvent
import dev.th7bo.sidequest.platform.event.ClientTickEvent
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.event.MinecraftDisconnectEvent
import dev.th7bo.sidequest.platform.event.MinecraftJoinEvent
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureRefusal
import dev.th7bo.sidequest.platform.feature.FeatureRegistry
import dev.th7bo.sidequest.platform.game.GameClient
import dev.th7bo.sidequest.platform.game.GameVersion
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.lifecycle.RegistrationScope
import dev.th7bo.sidequest.platform.log.LogCategory
import dev.th7bo.sidequest.platform.log.LogLevel
import dev.th7bo.sidequest.platform.log.LogSink
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.scheduler.Scheduler
import org.slf4j.LoggerFactory as Slf4jLoggerFactory

/**
 * Assembles the platform and connects it to the game.
 *
 * The only place that knows both halves. Everything above it sees interfaces;
 * everything below it is the two adapter classes. Wiring it up is the whole job:
 * translate the game's callbacks into typed events, hand the scheduler a way onto the
 * client thread, and give the feature registry somewhere to put its features.
 *
 * [start] is called once from the mod initializer, [stop] on shutdown.
 */
class SidequestPlatform(
    minecraftVersion: String,
    logSink: LogSink = Slf4jLogSink(),
) {

    val version: GameVersion = GameVersion.parse(minecraftVersion)

    private val loggers = LoggerFactory(logSink)

    val log: Logger = loggers.create(LogCategory.PLATFORM, SqId.sidequest("platform"))

    private val minecraftClient = MinecraftGameClient(version)

    val client: GameClient get() = minecraftClient

    private val minecraftLifecycle = MinecraftGameLifecycle(minecraftClient)

    val scheduler: Scheduler = DefaultScheduler(
        mainThreadExecutor = minecraftClient::submitToClientThread,
        onMainThreadCheck = { minecraftClient.isOnClientThread },
        log = loggers.create(LogCategory.PLATFORM, SqId.sidequest("scheduler")),
    )

    val events: EventBus = DefaultEventBus(
        scheduler = scheduler,
        log = loggers.create(LogCategory.EVENT, SqId.sidequest("events")),
    )

    val commands: CommandRegistry = DefaultCommandRegistry()

    val features: FeatureRegistry = DefaultFeatureRegistry(
        gameVersion = version,
        events = events,
        scheduler = scheduler,
        commands = commands,
        loggers = loggers,
    )

    /** The adapter's own hooks into the game, released on [stop]. */
    private val adapterScope = RegistrationScope("platform.adapter")

    private var started = false

    /** Turns log levels up. Wired to the developer settings once those exist. */
    fun setLogLevel(category: LogCategory, level: LogLevel) {
        loggers.setLevel(category, level)
    }

    /**
     * Registers [features] and starts everything that will start.
     *
     * Declaration is separate from enabling on purpose: every feature is known before
     * any of them runs, so dependencies resolve and a settings screen can list what the
     * user has switched off.
     */
    fun start(vararg features: Feature): List<FeatureRefusal> {
        check(!started) { "The platform has already been started" }
        started = true

        minecraftLifecycle.install()
        bridgeLifecycleToEvents()

        for (feature in features) this.features.register(feature)
        val refusals = this.features.enableAll()

        log.info { "Platform started on Minecraft $version with ${features.size} feature(s)" }
        return refusals
    }

    /**
     * Turns the game's callbacks into typed events.
     *
     * The one translation layer. A feature that wants to know it joined a server
     * subscribes to [MinecraftJoinEvent] and never learns that Fabric exists.
     */
    private fun bridgeLifecycleToEvents() {
        // Sourced as GAME: these came from the client itself, so they are the one class
        // of event the sync layer is allowed to treat as authoritative.
        adapterScope.add(
            minecraftLifecycle.onClientTick { events.post(ClientTickEvent(minecraftClient.tickCount), EventSource.GAME) }
                .asRegistration(),
        )
        adapterScope.add(
            minecraftLifecycle.onJoin { address -> events.post(MinecraftJoinEvent(address), EventSource.GAME) }
                .asRegistration(),
        )
        adapterScope.add(
            minecraftLifecycle.onDisconnect { events.post(MinecraftDisconnectEvent(), EventSource.GAME) }
                .asRegistration(),
        )
        adapterScope.add(
            minecraftLifecycle.onShutdown {
                events.post(ClientShutdownEvent(), EventSource.GAME)
                stop()
            }.asRegistration(),
        )
    }

    /** Disables every feature and unhooks the adapter. Safe to call more than once. */
    fun stop() {
        if (!started) return
        started = false
        features.disableAll()
        if (!adapterScope.isClosed) adapterScope.cancel()
        log.info { "Platform stopped" }
    }

    /** Live listener count, for the developer inspector. */
    fun listenerCount(): Int = events.listenerCount()

    private fun AutoCloseable.asRegistration() =
        dev.th7bo.sidequest.platform.lifecycle.Registration { close() }
}

/**
 * Writes platform logs through the game's logger.
 *
 * Everything ends up in the same `latest.log` the player would send anyway, tagged so
 * the Sidequest lines are greppable.
 */
class Slf4jLogSink : LogSink {

    private val logger = Slf4jLoggerFactory.getLogger("Sidequest")

    override fun write(
        level: LogLevel,
        category: LogCategory,
        owner: SqId,
        message: String,
        thrown: Throwable?,
    ) {
        val line = "[$category] $owner: $message"
        when (level) {
            LogLevel.TRACE -> logger.trace(line, thrown)
            LogLevel.DEBUG -> logger.debug(line, thrown)
            LogLevel.INFO -> logger.info(line, thrown)
            LogLevel.WARN -> logger.warn(line, thrown)
            LogLevel.ERROR -> logger.error(line, thrown)
        }
    }
}
