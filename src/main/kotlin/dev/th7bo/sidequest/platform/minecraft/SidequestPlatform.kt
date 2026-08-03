package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.chat.ChatParser
import dev.th7bo.sidequest.platform.command.CommandRegistry
import dev.th7bo.sidequest.platform.command.CommandSpec
import dev.th7bo.sidequest.platform.asset.AssetManager
import dev.th7bo.sidequest.platform.cosmetic.CosmeticService
import dev.th7bo.sidequest.platform.log.ErrorLog
import dev.th7bo.sidequest.platform.backend.BackendConfig
import dev.th7bo.sidequest.platform.core.backend.DefaultBackendClient
import dev.th7bo.sidequest.platform.core.backend.DefaultRealtimeClient
import dev.th7bo.sidequest.platform.core.audio.DefaultSoundManager
import dev.th7bo.sidequest.platform.core.backend.PresencePublisher
import dev.th7bo.sidequest.platform.core.backend.StoredTokenStore
import dev.th7bo.sidequest.platform.core.notification.DefaultNotificationManager
import dev.th7bo.sidequest.platform.core.cinematic.DefaultCinematicDirector
import dev.th7bo.sidequest.platform.core.chat.DefaultChatParser
import dev.th7bo.sidequest.platform.core.chat.HypixelChatRules
import dev.th7bo.sidequest.platform.core.command.DefaultCommandRegistry
import dev.th7bo.sidequest.platform.core.parser.TabListParser
import dev.th7bo.sidequest.platform.core.party.DefaultPartyService
import dev.th7bo.sidequest.platform.core.permission.DefaultPermissionService
import dev.th7bo.sidequest.platform.core.asset.DefaultAssetManager
import dev.th7bo.sidequest.platform.core.item.NeuItemRepository
import dev.th7bo.sidequest.platform.core.item.NeuNameCache
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import dev.th7bo.sidequest.platform.item.SkyBlockItemRepository
import dev.th7bo.sidequest.platform.core.cosmetic.CosmeticStore
import dev.th7bo.sidequest.platform.core.cosmetic.DefaultCosmeticService
import dev.th7bo.sidequest.platform.core.cosmetic.LoadoutPublisher
import dev.th7bo.sidequest.platform.core.cosmetic.RemoteCosmeticReceiver
import dev.th7bo.sidequest.platform.core.marker.DefaultMarkerService
import dev.th7bo.sidequest.platform.core.marker.RemoteMarkerReceiver
import dev.th7bo.sidequest.platform.core.player.DefaultPlayerDirectory
import dev.th7bo.sidequest.platform.core.rule.DefaultRuleEngine
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
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.skyblock.IslandChangedEvent
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
import dev.th7bo.sidequest.platform.notification.Notification
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.notification.NotificationPriority
import dev.th7bo.sidequest.platform.audio.SoundRequest
import dev.th7bo.sidequest.platform.audio.SoundResult
import dev.th7bo.sidequest.platform.rule.ActionHandler
import dev.th7bo.sidequest.platform.rule.RuleAction
import dev.th7bo.sidequest.platform.rule.RuleEngine
import dev.th7bo.sidequest.platform.rule.RuleStore
import dev.th7bo.sidequest.platform.cinematic.Cinematic
import dev.th7bo.sidequest.platform.cinematic.CinematicComponent
import dev.th7bo.sidequest.platform.cinematic.CinematicDirector
import dev.th7bo.sidequest.platform.cinematic.CinematicSink
import dev.th7bo.sidequest.platform.marker.MarkerKind
import dev.th7bo.sidequest.platform.marker.MarkerService
import dev.th7bo.sidequest.platform.marker.MarkerStore
import dev.th7bo.sidequest.platform.scheduler.Scheduler
import dev.th7bo.sidequest.platform.scheduler.SchedulerThread
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    /** Where cinematics are drawn. Same reasoning again. */
    private val cinematicSink: CinematicSink = CinematicSink.None,
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

    /**
     * Where the local player is standing, or null outside a world.
     *
     * Kept off [GameClient] on purpose — a live position on the interface invites features to read one and
     * forget which island they are on. Exposed here for the ones that genuinely need it, which so far means
     * saving a waypoint where somebody is standing.
     */
    internal val localPosition: SqPosition? get() = minecraftClient.localPosition

    /**
     * Where the player is aiming.
     *
     * See [CrosshairTarget] for why this is not `Minecraft.hitResult`: that field stops at arm's length,
     * which made every distant ping land on the sender's own feet.
     */
    internal val aimedAt: SqPosition? get() = CrosshairTarget.current()?.position

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
    private val partyServiceImpl = DefaultPartyService(
        events = events,
        players = playerDirectory,
        log = loggers.create(LogCategory.PARSER, SqId.sidequest("party")),
    )

    val party: PartyService get() = partyServiceImpl

    /**
     * The party service as its concrete type.
     *
     * [party] is the read-only view every feature gets. Starting a ready check, recording a response and
     * ending one are writes, and they live on the implementation so that a feature has to be handed them
     * deliberately rather than finding them on the interface it already has.
     */
    internal val partyService: DefaultPartyService get() = partyServiceImpl

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
     * Waypoints, pings, death markers and everything else at a place.
     *
     * Built after the context service, because whether a marker is *here* is a question about the island and
     * the context is what knows.
     */
    private val markerService = DefaultMarkerService(
        context = contextService,
        events = events,
        log = loggers.create(LogCategory.FEATURE, SqId.sidequest("markers")),
        localPosition = { minecraftClient.localPosition },
        localPlayer = { minecraftClient.localPlayerId?.let { PlayerId.of(it) } },
    )

    val markers: MarkerService get() = markerService

    /**
     * Where speculative asset work runs.
     *
     * Its own scope rather than the scheduler's, because a preload must outlive the caller that asked for it:
     * a feature that gives up waiting should not cancel a download the next frame wants. Cancelled in [stop].
     */
    private val assetScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("sidequest.assets"),
    )

    /**
     * Images, sounds and everything else the group shares.
     *
     * The cache lives under the storage root's throwaway directory, which is the point of
     * [StorageScope.Cache] existing — deleting it wholesale must cost nothing but a re-download.
     */
    private val assetManager = DefaultAssetManager(
        transport = JdkAssetTransport(),
        store = FileAssetStore(storageRoot.resolve(StorageScope.Cache.path).resolve("assets")),
        log = loggers.create(LogCategory.PERSISTENCE, SqId.sidequest("assets")),
        scope = assetScope,
        baseUrl = { backendConfig.baseUrl },
    )

    val assets: AssetManager get() = assetManager

    /**
     * What SkyBlock's own items look like.
     *
     * Its own transport rather than the backend's, because it talks to a public repository and not to us —
     * nothing about it should carry a session token. Nothing is fetched until something asks about an item.
     */
    private val itemRepository = NeuItemRepository(
        transport = JdkHttpTransport(),
        log = loggers.create(LogCategory.PLATFORM, SqId.sidequest("items")),
        // The database's archive is bytes, and the ordinary transport answers with text — a gzip stream
        // read as a String is a corrupted gzip stream. This is the same transport the asset manager uses.
        archives = JdkAssetTransport(),
        // Under the throwaway directory, which is exactly what this is: deleting it costs one download.
        cache = NeuNameCache(
            storageRoot.resolve(StorageScope.Cache.path).resolve("skyblock-item-names.tsv"),
        ),
    )

    val items: SkyBlockItemRepository get() = itemRepository

    /**
     * Cosmetics.
     *
     * Built after the asset manager, because a cosmetic that needs an image is hidden until the image is
     * resident and the thing that answers that has to exist first.
     */
    private val cosmeticService = DefaultCosmeticService(
        context = contextService,
        assets = assetManager,
        events = events,
        log = loggers.create(LogCategory.FEATURE, SqId.sidequest("cosmetics")),
        localPlayer = { minecraftClient.localPlayerId?.let { PlayerId.of(it) } },
        isFriend = { playerDirectory.byId(it)?.isCustomFriend == true },
        isInParty = { partyServiceImpl.party.isInParty },
    )

    init {
        // The sound manager asks the cosmetic service which pack is on. Set here rather than passed to the
        // constructor because the two are built in the other order — the cosmetic service needs the asset
        // manager, and the sound manager is older than both.
        soundManager.soundPack = { cosmeticService.personalStyle().soundPack }
    }

    val cosmetics: CosmeticService get() = cosmeticService

    /**
     * Feeds a line to the chat parser as though the server had sent it.
     *
     * For the developer commands, and deliberately *not* on the `ChatParser` interface: a feature able to
     * inject chat could make every other feature believe anything, and none of them would have a way to tell.
     * It lives here, on the concrete platform, where only the mod's own tools reach it.
     *
     * Everything downstream is real — the pattern, the derived event, whatever listens for it — so a
     * simulation exercises the parsing as well as the feature, which is the half that actually breaks when
     * Hypixel rewords something.
     */
    fun simulateChatLine(raw: String, kind: dev.th7bo.sidequest.platform.chat.ChatKind = dev.th7bo.sidequest.platform.chat.ChatKind.SYSTEM) {
        chatParser.onMessage(
            dev.th7bo.sidequest.platform.chat.ChatMessage(
                raw = raw,
                text = dev.th7bo.sidequest.platform.text.SqText.of(raw),
                kind = kind,
            ),
        )
    }

    /**
     * Applies the user's preferences to every service that acts on them.
     *
     * One method, because the services expose `val settings` on their interfaces and only the
     * implementations are mutable — so the alternative is leaking the concrete types to whoever holds the
     * config. Each service takes its settings through an `update` method rather than a plain assignment, and
     * that is not ceremony: leaving serious mode has to *release what was held*, and an assignment would
     * silently discard an hour of somebody's notifications. The setters are private precisely so that cannot
     * be got wrong from outside.
     */
    fun applyPreferences(
        notifications: dev.th7bo.sidequest.platform.notification.NotificationSettings,
        sounds: dev.th7bo.sidequest.platform.audio.SoundSettings,
        cinematics: dev.th7bo.sidequest.platform.cinematic.CinematicSettings,
        cosmetics: dev.th7bo.sidequest.platform.cosmetic.CosmeticSettings,
    ) {
        notificationManager.update(notifications)
        soundManager.update(sounds)
        cinematicDirector.update(cinematics)
        cosmeticService.settings = cosmetics
    }

    /** What has gone wrong this session, grouped by kind. Filled by every logger this factory makes. */
    val errors: ErrorLog get() = loggers.errors

    /**
     * The things worth stopping the game for.
     *
     * Built after the notification manager, because a cinematic that cannot be shown falls back to a
     * notification and the fallback has to exist before the thing that falls back to it.
     */
    private val cinematicDirector = DefaultCinematicDirector(
        sink = cinematicSink,
        context = contextService,
        client = minecraftClient,
        notifications = notificationManager,
        events = events,
        log = loggers.create(LogCategory.FEATURE, SqId.sidequest("cinematics")),
    )

    val cinematics: CinematicDirector get() = cinematicDirector

    /**
     * When this, then that.
     *
     * Built after the services its conditions read — the context, the party and the directory — because a
     * condition that asks "am I in a party" needs somebody to ask. Its action handlers are registered in
     * [start], once the managers they dispatch into exist.
     */
    private val ruleEngine = DefaultRuleEngine(
        events = events,
        context = contextService,
        party = partyServiceImpl,
        players = playerDirectory,
        log = loggers.create(LogCategory.FEATURE, SqId.sidequest("rules")),
        localPlayer = { minecraftClient.localPlayerId?.let { PlayerId.of(it) } },
    )

    val rules: RuleEngine get() = ruleEngine

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

    /**
     * Called each tick so the mod can redraw markers.
     *
     * A callback rather than the platform reaching into the overlay layer: the platform has no idea what a
     * world overlay is, and giving it one would be the crack in the boundary the module split exists to keep.
     */
    var onMarkersChanged: (() -> Unit)? = null

    val features: FeatureRegistry = DefaultFeatureRegistry(
        gameVersion = version,
        events = events,
        scheduler = scheduler,
        commands = commands,
        chat = chatParser,
        gameContext = contextService,
        players = playerDirectory,
        targeting = playerTargeting,
        party = partyServiceImpl,
        notifications = notificationManager,
        sounds = soundManager,
        cinematics = cinematicDirector,
        markers = markerService,
        rules = ruleEngine,
        assets = assetManager,
        cosmetics = cosmeticService,
        errors = loggers.errors,
        storage = fileStorage,
        permissions = permissionService,
        loggers = loggers,
        items = itemRepository,
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
        // Registered by the platform rather than by a feature: a notification's actions have to work whatever
        // is switched on, and the notification manager is the platform's.
        adapterScope.add(
            commands.register(
                OwnerId.PLATFORM,
                CommandSpec(
                    name = ACTION_COMMAND,
                    description = "Runs a notification action",
                    // Not for typing. It exists so a chat component has something to click.
                    isHidden = true,
                    usage = "<notification> <action>",
                    takesArguments = true,
                    handler = { arguments ->
                        if (arguments.size < 2) {
                            log.debug { "$ACTION_COMMAND needs a notification id and an action id" }
                        } else {
                            notificationManager.choose(arguments[0], arguments[1])
                        }
                    },
                ),
            ),
        )

        partyService.install()
        // Pings and rally targets belong to where they were placed. Subscribed by the platform rather than by
        // the service, so the service stays testable without an event bus subscription of its own.
        adapterScope.add(
            events.on<IslandChangedEvent>(OwnerId.PLATFORM) { markerService.onIslandChanged() },
        )
        registerRuleActions()
        ruleEngine.install()
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
        // Written once, at INFO, because it is the first thing worth knowing from somebody else's log: which
        // features are live, what the parsers loaded, and whether a backend is even configured. A log that
        // omits this makes every later line ambiguous.
        log.info {
            "Loaded ${chatParser.patterns().size} chat pattern(s); " +
                "commands: ${commands.all().joinToString(", ") { "/" + it.spec.name }}"
        }
        log.info {
            "Backend: " + (backendConfig.baseUrl?.let { "configured at $it" } ?: "not configured (local only)")
        }
        for (refusal in refusals) log.warn { "Feature refused: $refusal" }
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
                // Cheap: both return immediately unless something is waiting and the moment is safe.
                notificationManager.releaseQueuedIfSafe()
                cinematicDirector.releaseIfSafe()
                // Expiry and arrival. Also cheap: returns immediately when nothing is held.
                markerService.tick()
                onMarkersChanged?.invoke()
            }.asRegistration(),
        )
        adapterScope.add(
            minecraftLifecycle.onJoin { address ->
                // The account is known now, which is what the backend's token store is scoped to. The
                // configuration comes from the settings, so the mod applies it rather than the platform
                // guessing — the platform has no idea what the user typed.
                if (backendClient == null) onFirstJoin?.invoke()
                attachRuleStorage()
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
        boardPolls++
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
    /** Counted rather than logged per poll: at 20 Hz a line per poll is a log nobody can read. */
    private var boardPolls = 0L

    private fun pollPlayers() {
        // A heartbeat at TRACE, so a session that *looks* frozen can be told from one that is. Off by
        // default, and one line a minute when it is not.
        if (minecraftClient.tickCount % POLL_REPORT_TICKS == 0L && boardPolls > 0) {
            log.trace { "Polled the boards $boardPolls time(s) in the last minute" }
            boardPolls = 0
        }
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
     * Teaches the rule engine how to do things.
     *
     * The engine imports no subsystem — this is the one place the two meet, and it is here rather than in the
     * engine because *which* subsystems exist is the mod's business and not the engine's. Four kinds are
     * handled today; the rest of [RuleAction] is deliberately unhandled, which the engine logs and skips.
     * A rule that grants currency and plays a sound plays the sound.
     */
    private fun registerRuleActions() {
        ruleEngine.handle("notify") { action, outcome ->
            val notify = action as? RuleAction.Notify ?: return@handle false
            // Unknown names fall back rather than dropping the notification. A rule from the backend naming a
            // category this version has never heard of should still tell the player something.
            val category = NotificationCategory.entries
                .firstOrNull { it.name.equals(notify.category, ignoreCase = true) }
                ?: NotificationCategory.PROGRESSION
            val priority = NotificationPriority.entries
                .firstOrNull { it.name.equals(notify.priority, ignoreCase = true) }
                ?: NotificationPriority.NORMAL
            notificationManager.notify(
                Notification(
                    id = java.util.UUID.randomUUID().toString(),
                    category = category,
                    priority = priority,
                    // Substituted here, so one action serves every tier of a rule.
                    title = outcome.format(notify.title),
                    subtitle = notify.subtitle?.let { outcome.format(it) },
                    // Keyed on the rule and tier, so a rule that fires twice in a moment replaces rather than
                    // stacks — and a *different* tier is a different thing worth saying.
                    dedupeKey = "rule.${outcome.rule.id.value}.${outcome.tier ?: ""}",
                    groupingKey = "rule.${outcome.rule.id.value}",
                ),
            )
            // True regardless of what the policy decided. The action *ran*; a switched-off category is the
            // user's answer, not a failure, and reporting it as one would put a warning in the log for
            // working as configured.
            true
        }
        ruleEngine.handle("sound") { action, outcome ->
            val sound = action as? RuleAction.PlaySound ?: return@handle false
            val result = soundManager.play(
                SoundRequest(
                    soundId = sound.soundId,
                    volume = sound.volume,
                    // Positioned when the firing knew where it happened, so a rule about a place is heard
                    // from that direction.
                    position = outcome.location?.position,
                ),
            )
            result != SoundResult.MISSING
        }
        ruleEngine.handle("sound_pool") { action, _ ->
            val pool = action as? RuleAction.PlaySoundPool ?: return@handle false
            soundManager.playPool(pool.poolId) != SoundResult.MISSING
        }
        ruleEngine.handle("cinematic") { action, outcome ->
            val queue = action as? RuleAction.QueueCinematic ?: return@handle false
            // The rule names a cinematic and the director decides what happens to it, which is the point of
            // both: a rule that fires mid-dungeon has no business knowing that, and the director has no
            // business knowing which rule asked.
            val disposition = cinematicDirector.submit(
                Cinematic(
                    id = queue.cinematicId,
                    title = outcome.format(outcome.rule.displayName.ifEmpty { outcome.rule.id.value }),
                    components = listOf(
                        CinematicComponent.Letterbox(),
                        CinematicComponent.Background(),
                        CinematicComponent.Title(outcome.format("{rule}")),
                        outcome.tier?.let { CinematicComponent.Subtitle("tier $it") }
                            ?: CinematicComponent.Subtitle("×${outcome.progress}"),
                    ),
                    // Keyed on the rule, so a rule firing repeatedly in a dungeon merges into one showing
                    // rather than filling the queue with copies of itself.
                    groupingKey = "rule." + outcome.rule.id.value,
                    subject = outcome.subject,
                ),
            )
            log.debug { "Cinematic for ${outcome.rule.id}: ${disposition::class.simpleName}" }
            true
        }
        ruleEngine.handle("waypoint") { action, outcome ->
            val waypoint = action as? RuleAction.CreateWaypoint ?: return@handle false
            placeRuleMarker(MarkerKind.WAYPOINT, outcome.format(waypoint.label), outcome)
        }
        ruleEngine.handle("ping") { action, outcome ->
            val ping = action as? RuleAction.CreatePing ?: return@handle false
            placeRuleMarker(MarkerKind.PING, outcome.format(ping.label).ifEmpty { outcome.rule.displayName }, outcome)
        }
        ruleEngine.handle("stat") { action, outcome ->
            val stat = action as? RuleAction.WriteStat ?: return@handle false
            // No statistics service yet, and this is the honest interim: the number is logged rather than
            // silently dropped, so a rule wired to a stat can be *seen* working before there is a screen to
            // show it on. Replaced, not extended, when §27 lands.
            log.debug { "Stat ${stat.statId} += ${stat.amount} from ${outcome.rule.id}" }
            true
        }
    }

    /**
     * Places a marker where a rule fired.
     *
     * The location comes from the firing where it has one and from the player otherwise — a rule about a
     * friend's drop knows where that happened, and a rule about the local player is about here. Returns false
     * when neither is available, which the engine logs as an action that did nothing rather than a failure.
     */
    private fun placeRuleMarker(
        kind: MarkerKind,
        label: String,
        outcome: dev.th7bo.sidequest.platform.rule.RuleOutcome,
    ): Boolean {
        val location = outcome.location ?: minecraftClient.localPosition?.let { position ->
            dev.th7bo.sidequest.platform.skyblock.SqLocation(
                island = contextService.context.island,
                position = position,
                profile = contextService.context.profile,
            )
        } ?: return false

        markerService.place(
            dev.th7bo.sidequest.platform.marker.Marker(
                id = "",
                kind = kind,
                location = location,
                label = label,
                creator = outcome.subject,
            ),
        )
        return true
    }

    /**
     * Loads and persists rule progress for the signed-in account.
     *
     * Account-scoped, because progress follows the account: it is the same player whichever profile they are
     * on, and a rule that resets per profile says so with [RuleReset.ON_PROFILE_CHANGE] rather than by being
     * filed under one.
     *
     * Called on join, not at construction, for the same reason as the backend client: the account is not known
     * before login, and a repository scoped to nobody would write one player's progress into another's file.
     */
    private fun attachRuleStorage() {
        val playerId = minecraftClient.localPlayerId?.let { PlayerId.of(it) } ?: return
        if (ruleStorageAccount == playerId) return
        ruleStorageAccount = playerId

        val repository = fileStorage.repository(
            id = SqId.sidequest("rules"),
            scope = StorageScope.Account(playerId),
            serializer = RuleStore.serializer(),
            default = { RuleStore() },
        )

        scheduler.async(OwnerId.PLATFORM) {
            val stored = repository.load()
            if (stored.report.isWorthReporting) log.warn { "Rule progress: ${stored.report}" }
            // Applied on the client thread, so a rule evaluating on the same tick sees either the old state or
            // the new one and never a half-loaded map.
            scheduler.onMain(OwnerId.PLATFORM) { ruleEngine.load(stored.value) }
        }

        // Debounced through a flag rather than saved per change: a rule that adds progress on every kill would
        // otherwise write the file twenty times a second. The engine's callback is synchronous and can run on
        // any thread, so it may do nothing but set a bit.
        ruleEngine.onStoreChanged = { pendingRuleStore = it }

        // Markers, on the same account scope and the same timer. Profile-scoped would be wrong: a waypoint at
        // the Bazaar is at the Bazaar on every profile, and the two islands that *are* per profile already say
        // so through `SqLocation.profile`.
        val markerRepository = fileStorage.repository(
            id = SqId.sidequest("markers"),
            scope = StorageScope.Account(playerId),
            serializer = MarkerStore.serializer(),
            default = { MarkerStore() },
        )
        scheduler.async(OwnerId.PLATFORM) {
            val stored = markerRepository.load()
            if (stored.report.isWorthReporting) log.warn { "Markers: ${stored.report}" }
            scheduler.onMain(OwnerId.PLATFORM) { markerService.load(stored.value) }
        }
        markerService.onStoreChanged = { pendingMarkerStore = it }

        // The cosmetic loadout, same scope again: it is the account's and follows you between profiles.
        val cosmeticRepository = fileStorage.repository(
            id = SqId.sidequest("cosmetics"),
            scope = StorageScope.Account(playerId),
            serializer = CosmeticStore.serializer(),
            default = { CosmeticStore() },
        )
        scheduler.async(OwnerId.PLATFORM) {
            val stored = cosmeticRepository.load()
            if (stored.report.isWorthReporting) log.warn { "Cosmetics: ${stored.report}" }
            // `wear` rather than assigning, so a loadout naming a cosmetic that has since been removed
            // drops that slot and keeps the rest instead of failing whole. The viewer's settings are not
            // here — they come from the configuration file, like every other preference.
            scheduler.onMain(OwnerId.PLATFORM) { cosmeticService.wear(stored.value.loadout) }
        }
        cosmeticService.onLoadoutChanged = { pendingCosmeticStore = CosmeticStore(it) }

        adapterScope.add(
            scheduler.every(OwnerId.PLATFORM, RULE_SAVE_INTERVAL, thread = SchedulerThread.ASYNC) {
                pendingRuleStore?.let { store ->
                    pendingRuleStore = null
                    scheduler.async(OwnerId.PLATFORM) { repository.save(store) }
                }
                pendingMarkerStore?.let { store ->
                    pendingMarkerStore = null
                    scheduler.async(OwnerId.PLATFORM) { markerRepository.save(store) }
                }
                pendingCosmeticStore?.let { store ->
                    pendingCosmeticStore = null
                    scheduler.async(OwnerId.PLATFORM) { cosmeticRepository.save(store) }
                }
            },
        )
    }

    /** Which account's rule progress is loaded, so a rejoin does not re-attach. */
    private var ruleStorageAccount: PlayerId? = null

    /** The most recent rule state not yet written. See [attachRuleStorage]. */
    @Volatile
    private var pendingRuleStore: RuleStore? = null

    /** The most recent marker state not yet written. Same debounce, same reason. */
    @Volatile
    private var pendingMarkerStore: MarkerStore? = null

    /** The most recent cosmetic state not yet written. Same debounce again. */
    @Volatile
    private var pendingCosmeticStore: CosmeticStore? = null

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

        // A friend's ping becomes a marker here and nowhere else. Built alongside presence because both need
        // the same account-to-player mapping, and deriving it twice is one derivation too many.
        val remoteMarkers = RemoteMarkerReceiver(
            markers = markerService,
            players = playerDirectory,
            events = events,
            log = loggers.create(LogCategory.REALTIME, SqId.sidequest("markers.remote")),
        ).also { it.install() }
        remoteMarkerReceiver = remoteMarkers

        // What friends are wearing, and what we are. Same shape as markers and for the same reason: one
        // place turns the stream into cosmetics, so there is one idea of what arriving means.
        val remoteCosmetics = RemoteCosmeticReceiver(
            cosmetics = cosmeticService,
            events = events,
            log = loggers.create(LogCategory.REALTIME, SqId.sidequest("cosmetics.remote")),
        ).also { it.install() }
        remoteCosmeticReceiver = remoteCosmetics

        val publisher = LoadoutPublisher(
            events = events,
            log = loggers.create(LogCategory.REALTIME, SqId.sidequest("cosmetics.publish")),
            account = { client.accountId },
            send = { payload ->
                scheduler.async(OwnerId.PLATFORM) {
                    realtime.send(
                        RealtimeMessage(
                            messageId = java.util.UUID.randomUUID().toString(),
                            // The server's clock, like every other timestamp that crosses the wire.
                            timestampMillis = client.serverTime.toServer(System.currentTimeMillis()),
                            scope = payload.scope,
                            payload = payload,
                        ),
                    )
                }
            },
        ).also { it.install() }
        loadoutPublisher = publisher

        backendJobs = listOf(
            scheduler.async(OwnerId.PLATFORM) { client.start() },
            scheduler.async(OwnerId.PLATFORM) { realtime.run() },
            // The group listing feeds the account-to-player mapping presence needs, and is re-fetched rather
            // than diffed — see RealtimePayload.GroupChanged.
            scheduler.every(OwnerId.PLATFORM, GROUP_REFRESH, thread = SchedulerThread.ASYNC) {
                scheduler.async(OwnerId.PLATFORM) {
                    client.fetchGroup().valueOrNull()?.let {
                        presence.onGroup(it)
                        remoteMarkers.onGroup(presence.accountToPlayer)
                        remoteCosmetics.onGroup(presence.accountToPlayer)
                        // Republished on every group refresh, not only on change: somebody who came online
                        // after our last change would otherwise never learn what we are wearing, and there is
                        // no "tell me your loadout" request for them to make.
                        publisher.publish(cosmeticService.loadout())
                    }
                }
            },
            scheduler.every(OwnerId.PLATFORM, PRESENCE_TICK, thread = SchedulerThread.ASYNC) {
                scheduler.async(OwnerId.PLATFORM) { presence.publishIfDue() }
            },
        )
    }

    private var presencePublisher: PresencePublisher? = null

    private var remoteMarkerReceiver: RemoteMarkerReceiver? = null

    private var remoteCosmeticReceiver: RemoteCosmeticReceiver? = null

    private var loadoutPublisher: LoadoutPublisher? = null

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
        remoteMarkerReceiver?.close()
        remoteMarkerReceiver = null
        remoteCosmeticReceiver?.close()
        remoteCosmeticReceiver = null
        loadoutPublisher?.close()
        loadoutPublisher = null
    }

    /** Disables every feature and unhooks the adapter. Safe to call more than once. */
    fun stop() {
        if (!started) return
        started = false
        features.disableAll()
        partyService.close()
        ruleEngine.close()
        // The last state, written on the way out rather than lost to the save interval. Fire-and-forget: the
        // storage layer's flush is what actually waits for it.
        pendingRuleStore?.let { store ->
            pendingRuleStore = null
            ruleStorageAccount?.let { account ->
                scheduler.async(OwnerId.PLATFORM) {
                    fileStorage.repository(
                        id = SqId.sidequest("rules"),
                        scope = StorageScope.Account(account),
                        serializer = RuleStore.serializer(),
                        default = { RuleStore() },
                    ).save(store)
                }
            }
        }
        stopBackendJobs()
        // The realtime loop and the backend's jobs are owned by the platform, so cancelling its scheduler
        // registrations is the whole of their shutdown. Nothing here has to be told to stop.
        scheduler.cancelAll(OwnerId.PLATFORM)
        if (!adapterScope.isClosed) adapterScope.cancel()
        // Preloads are speculative and nobody is waiting on them, so they are simply dropped. The disk cache
        // keeps whatever finished, which is why abandoning them costs nothing.
        assetScope.cancel()
        assetManager.releaseMemory()
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

        /** How often the board-poll counter is reported at TRACE. Once a minute. */
        const val POLL_REPORT_TICKS = 1_200L

        /**
         * How often presence is considered for sending.
         *
         * The publisher's own throttle decides whether anything goes out; this only has to be more frequent
         * than that, and a second is cheap.
         */
        val PRESENCE_TICK: Duration = 1.seconds

        /** How often the group listing is re-fetched, for names and roles that changed. */
        val GROUP_REFRESH: Duration = 5.minutes

        /**
         * How often rule progress is written, when it changed.
         *
         * Ten seconds. Long enough that a burst of firings is one write, short enough that a crash costs at
         * most a few seconds of progress — and [stop] writes what is pending anyway.
         */
        val RULE_SAVE_INTERVAL: Duration = 10.seconds
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
