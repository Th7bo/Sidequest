package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.chat.ChatParser
import dev.th7bo.sidequest.platform.command.CommandRegistry
import dev.th7bo.sidequest.platform.backend.BackendConfig
import dev.th7bo.sidequest.platform.core.backend.DefaultBackendClient
import dev.th7bo.sidequest.platform.core.backend.DefaultRealtimeClient
import dev.th7bo.sidequest.platform.core.audio.DefaultSoundManager
import dev.th7bo.sidequest.platform.core.backend.PresencePublisher
import dev.th7bo.sidequest.platform.core.backend.StoredTokenStore
import dev.th7bo.sidequest.platform.core.notification.DefaultNotificationManager
import dev.th7bo.sidequest.platform.core.chat.DefaultChatParser
import dev.th7bo.sidequest.platform.core.chat.HypixelChatRules
import dev.th7bo.sidequest.platform.core.command.DefaultCommandRegistry
import dev.th7bo.sidequest.platform.core.parser.TabListParser
import dev.th7bo.sidequest.platform.core.party.DefaultPartyService
import dev.th7bo.sidequest.platform.core.permission.DefaultPermissionService
import dev.th7bo.sidequest.platform.core.player.DefaultPlayerDirectory
import dev.th7bo.sidequest.platform.core.storage.JsonFileStorage
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
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.lifecycle.Registration
import dev.th7bo.sidequest.platform.lifecycle.RegistrationScope
import dev.th7bo.sidequest.platform.log.LogCategory
import dev.th7bo.sidequest.platform.log.LogLevel
import dev.th7bo.sidequest.platform.log.LogSink
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.party.PartyService
import dev.th7bo.sidequest.platform.audio.SoundManager
import dev.th7bo.sidequest.platform.audio.SoundSink
import dev.th7bo.sidequest.platform.notification.NotificationManager
import dev.th7bo.sidequest.platform.notification.NotificationSink
import dev.th7bo.sidequest.platform.permission.PermissionService
import dev.th7bo.sidequest.platform.player.PlayerDirectory
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.player.PlayerTargeting
import dev.th7bo.sidequest.platform.scheduler.Scheduler
import dev.th7bo.sidequest.platform.scheduler.SchedulerThread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import dev.th7bo.sidequest.platform.storage.StorageProvider
import dev.th7bo.sidequest.platform.storage.StorageScope
import dev.th7bo.sidequest.protocol.RealtimeMessage
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
    /**
     * Where notifications are drawn.
     *
     * Supplied rather than built, because the UI framework lives on the other side of a boundary the platform
     * cannot see across. [NotificationSink.None] is the default, so a platform with no UI attached still has
     * a working manager — which is what the headless tests use.
     */
    notificationSink: NotificationSink = NotificationSink.None,
    /** Where sounds come out. Same reasoning as the notification sink. */
    soundSink: SoundSink = SoundSink.None,
    /**
     * Where feature data is written.
     *
     * Passed in rather than derived, so the in-game tests can point it at a temporary directory
     * instead of at the player's real save.
     */
    private val storageRoot: java.nio.file.Path,
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

    /**
     * The one place chat is classified.
     *
     * The built-in rules are registered in [start] under the platform's own owner, so they
     * outlive every feature: a feature being switched off must not stop the mod knowing it
     * joined a party.
     */
    private val chatParser = DefaultChatParser(
        events = events,
        log = loggers.create(LogCategory.PARSER, SqId.sidequest("chat")),
    )

    val chat: ChatParser get() = chatParser

    /**
     * The authority on where the player is.
     *
     * Fed from the scoreboard and tab list below. Features read [GameContextService.context]
     * rather than deciding for themselves what "in a dungeon" means.
     */
    private val contextService = DefaultGameContextService(
        events = events,
        log = loggers.create(LogCategory.PARSER, SqId.sidequest("context")),
        isMoving = { minecraftClient.hasMovedRecently },
    )

    val gameContext: GameContextService get() = contextService

    /**
     * Who is who.
     *
     * Learned from the tab list and from every player the client can see, and keyed on UUID
     * throughout — a Minecraft name can be released and claimed by somebody else, so nothing
     * durable may key on one.
     */
    private val playerDirectory = DefaultPlayerDirectory(events)

    val players: PlayerDirectory get() = playerDirectory

    /** Finding the player somebody means. Needs the game, so it is an adapter. */
    private val playerTargeting = MinecraftPlayerTargeting(playerDirectory)

    val targeting: PlayerTargeting get() = playerTargeting

    /**
     * The party.
     *
     * Built from the chat rules and corroborated against the tab widget — no chat parsing of its
     * own, which is the demonstration that the layering holds.
     */
    private val partyService = DefaultPartyService(
        events = events,
        players = playerDirectory,
        log = loggers.create(LogCategory.PARSER, SqId.sidequest("party")),
    )

    val party: PartyService get() = partyService

    /**
     * Durable storage for feature data.
     *
     * Separate from the UI framework's configuration store, and separate on purpose: the two
     * frameworks do not depend on each other, so sharing the implementation would mean one importing
     * the other. Two atomic-writes is the price of that split.
     */
    private val fileStorage = JsonFileStorage(
        root = storageRoot,
        log = loggers.create(LogCategory.PERSISTENCE, SqId.sidequest("storage")),
    )

    val storage: StorageProvider get() = fileStorage

    /**
     * Who may do what, and what we have agreed to reveal.
     *
     * Built before anything that could send data anywhere, which is the point: the gate has to exist
     * before there is anything to gate.
     */
    private val permissionService = DefaultPermissionService(
        log = loggers.create(LogCategory.PLATFORM, SqId.sidequest("permissions")),
        localPlayer = { minecraftClient.localPlayerId?.let { PlayerId.of(it) } },
    )

    val permissions: PermissionService get() = permissionService

    /**
     * Notifications, and the policy over them.
     *
     * Built after the context service, because deciding whether now is a bad moment is the whole of its
     * policy and the context is what knows.
     */
    private val notificationManager = DefaultNotificationManager(
        sink = notificationSink,
        context = contextService,
        log = loggers.create(LogCategory.FEATURE, SqId.sidequest("notifications")),
    )

    val notifications: NotificationManager get() = notificationManager

    private val soundManager = DefaultSoundManager(
        sink = soundSink,
        log = loggers.create(LogCategory.AUDIO, SqId.sidequest("sounds")),
    )

    val sounds: SoundManager get() = soundManager

    /**
     * The backend, when one is configured.
     *
     * Built lazily, and *not* started here. Everything it needs — the local player's account scope for its
     * token store — only exists after login, and a client built at mod-init would have nowhere to keep its
     * credentials. [connectBackend] is called once the player is known.
     */
    private var backendClient: DefaultBackendClient? = null

    private var realtimeClient: DefaultRealtimeClient? = null

    val backend: DefaultBackendClient? get() = backendClient

    val realtime: DefaultRealtimeClient? get() = realtimeClient

    /**
     * The backend configuration.
     *
     * Replaceable at runtime, because it comes from the settings screen. A change of server drops the
     * session with it — see [DefaultBackendClient.reconfigure].
     */
    var backendConfig: BackendConfig = BackendConfig.None
        private set

    /**
     * Called on the first join of a session, so the mod can apply its settings.
     *
     * A callback rather than the platform reading configuration directly: the platform has no idea what a
     * settings screen is, and giving it one would be the first crack in the boundary the whole module
     * split exists to keep.
     */
    var onFirstJoin: (() -> Unit)? = null

    val features: FeatureRegistry = DefaultFeatureRegistry(
        gameVersion = version,
        events = events,
        scheduler = scheduler,
        commands = commands,
        chat = chatParser,
        gameContext = contextService,
        players = playerDirectory,
        targeting = playerTargeting,
        party = partyService,
        notifications = notificationManager,
        sounds = soundManager,
        storage = fileStorage,
        permissions = permissionService,
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

        MinecraftChatBridge(
            parser = chatParser,
            log = loggers.create(LogCategory.PARSER, SqId.sidequest("chat")),
        ).install()

        // The local player's name is needed to tell the player's own public message from a
        // system line shaped like one, and it is not known yet at this point — hence the
        // supplier rather than a value.
        partyService.install()
        adapterScope.add(chatParser.registerAll(HypixelChatRules.all { minecraftClient.localPlayerName }, OwnerId.PLATFORM))
        val fixtureFailures = chatParser.verifyFixtures()
        if (fixtureFailures.isNotEmpty()) {
            // Loud, because it means a built-in pattern does not do what its own recorded
            // line says it does, and every feature above it is quietly broken.
            log.error { "Chat patterns failed their own fixtures:\n" + fixtureFailures.joinToString("\n") }
        }

        // Hypixel's own location packet, when the Mod API is installed. Optional by
        // design: without it the scraped sources stand on their own at lower confidence.
        HypixelModApiSource(
            context = contextService,
            log = loggers.create(LogCategory.PARSER, SqId.sidequest("hypixel_api")),
        ).install()

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
                pollPlayers()
                // Cheap: returns immediately unless something is waiting and the moment is safe.
                notificationManager.releaseQueuedIfSafe()
            }.asRegistration(),
        )
        adapterScope.add(
            minecraftLifecycle.onJoin { address ->
                // The account is known now, which is what the backend's token store is scoped to. The
                // configuration comes from the settings, so the mod applies it rather than the platform
                // guessing — the platform has no idea what the user typed.
                if (backendClient == null) onFirstJoin?.invoke()
                contextService.setOnHypixel(isHypixel(address))
                events.post(MinecraftJoinEvent(address), EventSource.GAME)
            }.asRegistration(),
        )
        adapterScope.add(
            minecraftLifecycle.onDisconnect {
                // Before the event, so a listener reacting to the disconnect does not read
                // an island from a server it has just left.
                contextService.reset()
                chatParser.reset()
                // The directory is deliberately *not* cleared: who somebody is does not stop being
                // true because we left the server, and re-learning every name on every hop would
                // throw away the rename history that makes a stale name resolvable.
                playerDirectory.forgetPresence()
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
        val tabList = MinecraftTabListReader.read()
        contextService.onTabList(tabList)
        // The party widget reaches the party service the same way the scoreboard reaches the context
        // service: through the one poll, rather than the service listening for a board event and
        // parsing the tab list a second time.
        partyService.onPartyWidget(TabListParser.parse(tabList).partyMembers)
    }

    /**
     * Feeds the online player list into the directory.
     *
     * Once a second rather than every tick. The list is up to eighty entries and changes when
     * somebody joins the lobby, so twenty passes a second would be nineteen wasted — and unlike the
     * boards this is not a source of anything time-sensitive.
     *
     * Not gated on Hypixel. Knowing who somebody is has nothing to do with which server they were
     * met on, and a friend met in singleplayer is still that person.
     */
    private fun pollPlayers() {
        if (minecraftClient.tickCount % PLAYER_POLL_TICKS != 0L) return
        for ((id, name) in MinecraftPlayerListReader.read()) {
            playerDirectory.remember(id, name)
        }
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

    /**
     * Builds and starts the backend client for the signed-in account.
     *
     * Called after login, because the token store is scoped to the account: two Minecraft accounts on one
     * machine are two devices to the backend, and sharing one credential would mean one signing the other
     * out.
     *
     * Does nothing when no server is configured, which is the default and not a problem — Sidequest's local
     * features are most of it, and a group without a server should see no errors and no retries.
     */
    fun connectBackend(config: BackendConfig) {
        val playerId = minecraftClient.localPlayerId?.let { PlayerId.of(it) } ?: return

        // Idempotent. The settings screen calls this every time somebody presses Pair, and rebuilding on an
        // unchanged configuration would leave the previous realtime loop reconnecting forever while a second
        // one started beside it.
        if (config == backendConfig && backendClient != null) return

        backendConfig = config
        // Whatever was running belongs to the old configuration. Cancelled before anything replaces it, so
        // there is never a moment with two clients talking to two servers.
        stopBackendJobs()

        if (!config.isConfigured) {
            backendClient = null
            realtimeClient = null
            return
        }

        val client = DefaultBackendClient(
            config = config,
            transport = JdkHttpTransport(),
            tokens = StoredTokenStore(fileStorage, StorageScope.Account(playerId)),
            events = events,
            log = loggers.create(LogCategory.BACKEND, SqId.sidequest("backend")),
            outbox = fileStorage.queue(
                id = SqId.sidequest("backend.outbox"),
                // Account-scoped, like the credentials: one account's unsent events are not another's.
                scope = StorageScope.Account(playerId),
                serializer = RealtimeMessage.serializer(),
            ),
        )
        backendClient = client

        val realtime = DefaultRealtimeClient(
            client = client,
            transport = JdkRealtimeTransport(),
            events = events,
            log = loggers.create(LogCategory.REALTIME, SqId.sidequest("realtime")),
        )
        realtimeClient = realtime

        // Both on the scheduler's async side. Neither may touch the game, and both are long-lived — the
        // realtime loop runs for the session — so their handles are kept rather than dropped: a
        // reconfiguration has to be able to stop them.
        // Presence closes the loop: the context knows what the player is doing and this is what carries it to
        // the group. Built here rather than at construction because it needs the realtime client.
        val presence = PresencePublisher(
            client = client,
            realtime = realtime,
            context = contextService,
            permissions = permissionService,
            players = playerDirectory,
            events = events,
            log = loggers.create(LogCategory.REALTIME, SqId.sidequest("presence")),
        ).also { it.install() }
        presencePublisher = presence

        backendJobs = listOf(
            scheduler.async(OwnerId.PLATFORM) { client.start() },
            scheduler.async(OwnerId.PLATFORM) { realtime.run() },
            // The group listing feeds the account-to-player mapping presence needs, and is re-fetched rather
            // than diffed — see RealtimePayload.GroupChanged.
            scheduler.every(OwnerId.PLATFORM, GROUP_REFRESH, thread = SchedulerThread.ASYNC) {
                scheduler.async(OwnerId.PLATFORM) {
                    client.fetchGroup().valueOrNull()?.let { presence.onGroup(it) }
                }
            },
            scheduler.every(OwnerId.PLATFORM, PRESENCE_TICK, thread = SchedulerThread.ASYNC) {
                scheduler.async(OwnerId.PLATFORM) { presence.publishIfDue() }
            },
        )
    }

    private var presencePublisher: PresencePublisher? = null

    /**
     * Handles for the backend's two long-lived jobs.
     *
     * Kept because a reconfiguration replaces them. Cancelling by owner would take everything else the
     * platform has scheduled with it.
     */
    private var backendJobs: List<Registration> = emptyList()

    private fun stopBackendJobs() {
        backendJobs.forEach { it.cancel() }
        backendJobs = emptyList()
        presencePublisher?.close()
        presencePublisher = null
    }

    /** Disables every feature and unhooks the adapter. Safe to call more than once. */
    fun stop() {
        if (!started) return
        started = false
        features.disableAll()
        partyService.close()
        stopBackendJobs()
        // The realtime loop and the backend's jobs are owned by the platform, so cancelling its scheduler
        // registrations is the whole of their shutdown. Nothing here has to be told to stop.
        scheduler.cancelAll(OwnerId.PLATFORM)
        if (!adapterScope.isClosed) adapterScope.cancel()
        log.info { "Platform stopped" }
    }

    /** Live listener count, for the developer inspector. */
    fun listenerCount(): Int = events.listenerCount()

    private fun AutoCloseable.asRegistration() =
        dev.th7bo.sidequest.platform.lifecycle.Registration { close() }

    private companion object {
        const val HYPIXEL_DOMAIN = "hypixel.net"

        /** How often the online player list is read. Once a second. */
        const val PLAYER_POLL_TICKS = 20L

        /**
         * How often presence is considered for sending.
         *
         * The publisher's own throttle decides whether anything goes out; this only has to be more frequent
         * than that, and a second is cheap.
         */
        val PRESENCE_TICK: Duration = 1.seconds

        /** How often the group listing is re-fetched, for names and roles that changed. */
        val GROUP_REFRESH: Duration = 5.minutes
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
