package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.command.CommandRegistry
import dev.th7bo.sidequest.platform.core.command.DefaultCommandRegistry
import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.core.feature.DefaultFeatureRegistry
import dev.th7bo.sidequest.platform.core.log.LoggerFactory
import dev.th7bo.sidequest.platform.core.context.DefaultGameContextService
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
import dev.th7bo.sidequest.platform.skyblock.GameContextService
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

    // The registry and the bridge refer to each other: the registry notifies the bridge
    // of a new command, and the bridge resolves through the registry when one runs.
    // Broken with a late assignment rather than an interface, because the alternative —
    // a supplier threaded through the bridge — hides a one-line ordering problem behind
    // a layer of indirection.
    private var commandBridge: MinecraftCommandBridge? = null

    val commands: CommandRegistry = DefaultCommandRegistry(
        onRegistered = { command -> commandBridge?.onRegistered(command.spec) },
    )

    val features: FeatureRegistry = DefaultFeatureRegistry(
        gameVersion = version,
        events = events,
        scheduler = scheduler,
        commands = commands,
        loggers = loggers,
    )

    /**
     * The authority on where the player is.
     *
     * Fed from the scoreboard and tab list below. Features read [GameContextService.context]
     * rather than deciding for themselves what "in a dungeon" means.
     */
    private val contextService = DefaultGameContextService(
        events = events,
        log = loggers.create(LogCategory.PARSER, SqId.sidequest("context")),
    )

    val gameContext: GameContextService get() = contextService

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

        // Before the features are enabled, so the commands they register on the way up
        // are announced to a bridge that exists.
        commandBridge = MinecraftCommandBridge(
            registry = commands,
            log = loggers.create(LogCategory.PLATFORM, SqId.sidequest("commands")),
        ).also { it.install() }

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
            minecraftLifecycle.onClientTick {
                events.post(ClientTickEvent(minecraftClient.tickCount), EventSource.GAME)
                pollBoards()
            }.asRegistration(),
        )
        adapterScope.add(
            minecraftLifecycle.onJoin { address ->
                contextService.setOnHypixel(isHypixel(address))
                events.post(MinecraftJoinEvent(address), EventSource.GAME)
            }.asRegistration(),
        )
        adapterScope.add(
            minecraftLifecycle.onDisconnect {
                // Before the event, so a listener reacting to the disconnect does not read
                // an island from a server it has just left.
                contextService.reset()
                contextService.setOnHypixel(false)
                events.post(MinecraftDisconnectEvent(), EventSource.GAME)
            }.asRegistration(),
        )
        adapterScope.add(
            minecraftLifecycle.onShutdown {
                events.post(ClientShutdownEvent(), EventSource.GAME)
                stop()
            }.asRegistration(),
        )
    }

    /**
     * Feeds the scoreboard and tab list to the context service.
     *
     * Every tick, but not every tick's worth of work: the service compares the snapshot
     * to the previous one and returns immediately when nothing changed, which is nearly
     * always. Reading the boards is a handful of string builds — cheap enough to do at
     * 20 Hz, and polling is the only option because neither has a change callback.
     *
     * Skipped entirely off Hypixel: parsing a vanilla server's scoreboard would find
     * nothing, and there is no reason to look.
     */
    private fun pollBoards() {
        if (!contextService.context.isOnHypixel) return
        contextService.onScoreboard(MinecraftScoreboardReader.read())
        contextService.onTabList(MinecraftTabListReader.read())
    }

    /**
     * Whether an address is Hypixel.
     *
     * Deliberately generous about subdomains — `mc.hypixel.net`, `alpha.hypixel.net` and
     * the regional addresses are all Hypixel — and deliberately not generous about
     * anything else, because the parsers would happily read a lookalike scoreboard and
     * report an island the player is not on.
     */
    private fun isHypixel(address: String?): Boolean {
        val host = address?.substringBefore(':')?.lowercase() ?: return false
        return host == HYPIXEL_DOMAIN || host.endsWith(".$HYPIXEL_DOMAIN")
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

    private companion object {
        const val HYPIXEL_DOMAIN = "hypixel.net"
    }
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
