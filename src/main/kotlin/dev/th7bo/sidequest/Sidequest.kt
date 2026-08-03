package dev.th7bo.sidequest

import dev.th7bo.sidequest.features.DeveloperTools
import dev.th7bo.sidequest.features.GardenViewBobbing
import dev.th7bo.sidequest.features.PlaytimeTracker
import dev.th7bo.sidequest.features.PingSystem
import dev.th7bo.sidequest.features.RareDropAnimation
import dev.th7bo.sidequest.features.SharedWaypoints
import dev.th7bo.sidequest.features.SessionDiagnostics
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.backend.BackendConfig
import dev.th7bo.sidequest.platform.backend.PairingStatus
import dev.th7bo.sidequest.platform.minecraft.ItemTextures
import dev.th7bo.sidequest.platform.minecraft.CrosshairTarget
import dev.th7bo.sidequest.platform.ping.PingStyle
import dev.th7bo.sidequest.platform.minecraft.MinecraftCinematicSink
import dev.th7bo.sidequest.platform.minecraft.MinecraftMarkerBridge
import dev.th7bo.sidequest.platform.minecraft.MinecraftNotificationSink
import dev.th7bo.sidequest.platform.minecraft.MinecraftSoundSink
import dev.th7bo.sidequest.platform.minecraft.SidequestPlatform
import dev.th7bo.sidequest.platform.minecraft.toSq
import dev.th7bo.sidequest.platform.text.SqStyle
import dev.th7bo.sidequest.platform.text.SqText
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.core.persistence.ConfigPersistenceController
import dev.th7bo.sidequest.ui.core.persistence.JsonFileConfigStore
import dev.th7bo.sidequest.ui.minecraft.lifecycle.FontReloadListener
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftItemStacks
import dev.th7bo.sidequest.ui.minecraft.lifecycle.SidequestKeybinds
import dev.th7bo.sidequest.ui.core.hud.HudLayoutPersistence
import dev.th7bo.sidequest.ui.hud.HudPlacement
import dev.th7bo.sidequest.ui.ids.ProfileId
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.minecraft.hud.SidequestHudLayer
import dev.th7bo.sidequest.ui.minecraft.screen.SidequestConfigScreen
import dev.th7bo.sidequest.ui.minecraft.screen.SidequestHudEditorScreen
import dev.th7bo.sidequest.ui.minecraft.screen.PingWheelScreen
import dev.th7bo.sidequest.ui.state.UiScheduler
import dev.th7bo.sidequest.ui.theme.DarkTheme
import dev.th7bo.sidequest.ui.theme.HighContrastDarkTheme
import dev.th7bo.sidequest.ui.theme.LightTheme
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.theme.Theme
import dev.th7bo.sidequest.ui.theme.ThemeTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Sidequest : ClientModInitializer {

    const val MOD_ID = "sidequest"

    private val loader: FabricLoader get() = FabricLoader.getInstance()

    val VERSION: String = loader.getModContainer(MOD_ID).orElseThrow()
        .metadata.version.friendlyString

    /** The Minecraft version this client is running, e.g. `26.2`. */
    val MINECRAFT: String = loader.getModContainer("minecraft").orElseThrow()
        .metadata.version.friendlyString

    val logger: Logger = LoggerFactory.getLogger("Sidequest")

    /** Background scope for persistence. Never touches the reactive graph. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Hands work back to the client thread.
     *
     * `Minecraft.execute` runs the block at the start of the next client tick, which is
     * the contract [UiScheduler] promises: never re-entrant inside layout or paint.
     */
    private val clientScheduler = UiScheduler { block -> Minecraft.getInstance().execute(block) }

    /** Built once; the definition is immutable, the values behind it are not. */
    val configScreen: ConfigScreen by lazy { buildSidequestConfigScreen() }

    val persistence: ConfigPersistenceController by lazy {
        ConfigPersistenceController(
            screen = configScreen,
            store = JsonFileConfigStore(
                root = loader.configDir.resolve(MOD_ID),
                currentVersion = CONFIG_SCHEMA_VERSION,
            ),
            coroutineScope = ioScope,
            scheduler = clientScheduler,
            schemaVersion = CONFIG_SCHEMA_VERSION,
        )
    }

    /**
     * HUD placement persistence.
     *
     * Built lazily against the layer, which does not exist until the first frame in a
     * world. Uses the same store implementation as the configuration, only a different
     * file — atomic writes and corruption quarantine matter here for the same reasons.
     */
    private var hudLayout: HudLayoutPersistence? = null

    /** Wires persistence to the HUD layer. Called once the layer has been built. */
    private fun attachHudPersistence(layer: dev.th7bo.sidequest.ui.core.hud.HudLayerNode) {
        if (hudLayout != null) return
        val controller = HudLayoutPersistence(
            layer = layer,
            store = JsonFileConfigStore(
                root = loader.configDir.resolve(MOD_ID),
                currentVersion = HUD_LAYOUT_SCHEMA_VERSION,
                fileName = HudLayoutPersistence.FILE_NAME,
            ),
            coroutineScope = ioScope,
            scheduler = clientScheduler,
            schemaVersion = HUD_LAYOUT_SCHEMA_VERSION,
        )
        controller.onLoadReport = { report ->
            when {
                report.corruptionBackupPath != null ->
                    logger.error("HUD layout was unreadable; the file was kept at {}", report.corruptionBackupPath)
                report.rejectedValues.isNotEmpty() ->
                    logger.warn("{} HUD placement(s) were rejected: {}", report.rejectedValues.size, report.rejectedValues)
                report.wasEmpty -> logger.info("No saved HUD layout; using defaults")
                else -> logger.info("HUD layout loaded")
            }
        }
        hudLayout = controller
        controller.load(ProfileId.DEFAULT)
    }

    /** The placements the last load applied, for diagnostics and in-game tests. */
    val loadedHudPlacements: Map<UiId, HudPlacement>
        get() = hudLayout?.lastLoaded ?: emptyMap()

    /** Writes the HUD layout now. Called when the editor closes. */
    fun saveHudLayout() {
        hudLayout?.saveNow(ProfileId.DEFAULT)
    }

    /** Resolves the configured theme name to a theme. */
    fun activeTheme(): Theme = styled(
        when (SidequestSettings.theme) {
            "light" -> LightTheme
            "high_contrast_dark" -> HighContrastDarkTheme
            else -> DarkTheme
        },
    )

    /**
     * The theme with the player's own cosmetic style applied.
     *
     * One hook for every personal slot that changes how the mod looks, which is why notification style and
     * cinematic style need no code of their own: both amount to an accent, and everything the mod draws —
     * toasts, cinematics, HUDs, the configuration screen — already takes its accent from the theme.
     *
     * The chosen theme is still the base. A style recolours the mod; it does not replace somebody's choice of
     * light or high-contrast, which is a legibility setting rather than a decoration.
     */
    private fun styled(base: Theme): Theme {
        // The user's own choice first, then a worn cosmetic style on top of it. Both, in that order — the
        // accent picker reached nothing at all until a screenshot showed the field set to red while every
        // button stayed purple, which is exactly the "switch that does nothing" this screen is supposed not
        // to have.
        val chosen = SidequestSettings.accentColor.takeIf { it != DEFAULT_ACCENT }?.argb
        val accent = platformOrNull?.cosmetics?.personalStyle()?.accentColour ?: chosen ?: return base
        // Opaque: a cosmetic names a colour, not a transparency, and an accent that could be made invisible
        // would let somebody wear a style that hides every button in the mod.
        val colour = Color(accent or ALPHA_OPAQUE)
        return object : Theme by base {
            // Only the tokens are replaced. Delegating the rest means a theme that grows a member does not
            // silently lose it here — which is exactly what an explicit `object : Theme` would have done,
            // and did, the first time this was written.
            override val tokens: ThemeTokens = base.tokens.copy(
                colors = base.tokens.colors.copy(
                    accent = colour,
                    // Derived rather than taken as three separate settings: a cosmetic that let somebody pick
                    // a hover colour unrelated to its base would let them make a button that looks broken.
                    accentHover = colour.lerp(Color.White, HOVER_LIFT),
                    accentPressed = colour.lerp(Color.Black, PRESS_DROP),
                ),
            )
        }
    }

    /** How far a styled accent moves towards white on hover, and towards black when pressed. */
    private const val HOVER_LIFT = 0.15f
    private const val PRESS_DROP = 0.15f

    /** Forces full opacity on a colour that names a hue rather than a transparency. */
    private const val ALPHA_OPAQUE = 0xFF000000.toInt()

    /** The theme's own accent. Choosing this means "leave the theme alone" rather than "override with this". */
    private val DEFAULT_ACCENT = Color.parse("#8B5CF6")

    /** Creates the configuration screen, ready to hand to `Minecraft.setScreen`. */
    fun createConfigScreen(): SidequestConfigScreen {
        // The screen is built once and kept, and its property bindings cache. Anything that changed a
        // setting from elsewhere — the Ignore button on a drop's notification, most obviously — would
        // otherwise show here as it was when the game started.
        configScreen.refreshBindings()
        return SidequestConfigScreen(configScreen, activeTheme(), persistence)
    }

    /**
     * The component gallery.
     *
     * No persistence: its values are throwaway state, and writing them to disk would
     * mean a demo screen could dirty a real config file.
     */
    fun createGalleryScreen(): SidequestConfigScreen =
        SidequestConfigScreen(SidequestGallery.screen, activeTheme())

    /**
     * The stress screen.
     *
     * Also unpersisted: its 1,700 settings are throwaway state, and writing them would
     * turn opening a diagnostic into a config file the size of the screen.
     */
    fun createStressScreen(): SidequestConfigScreen =
        SidequestConfigScreen(SidequestStressScreen.screen, activeTheme())

    /**
     * The HUD editor, or null when the HUD layer has not been built yet — it is created
     * lazily on the first frame in a world, so there is nothing to edit on a menu screen.
     */
    fun createHudEditorScreen(): SidequestHudEditorScreen? {
        val layer = SidequestHudLayer.hudLayer ?: return null
        return SidequestHudEditorScreen(
            layer,
            activeTheme(),
            onSave = {
                persistence.saveNow()
                saveHudLayout()
            },
        )
    }

    override fun onInitializeClient() {
        logger.info("Sidequest $VERSION loaded for Minecraft $MINECRAFT")

        // Loading is asynchronous by construction: reading happens off-thread and the
        // values are applied back on the client thread by the scheduler.
        persistence.onLoadReport = { report ->
            when {
                report.corruptionBackupPath != null ->
                    logger.error("Configuration was unreadable; the file was kept at {}", report.corruptionBackupPath)
                report.rejectedValues.isNotEmpty() ->
                    logger.warn("{} configuration value(s) were rejected: {}", report.rejectedValues.size, report.rejectedValues)
                report.migrationsApplied.isNotEmpty() ->
                    logger.info("Configuration migrated: {}", report.migrationsApplied.joinToString())
                else -> logger.info("Configuration loaded")
            }
        }
        persistence.onSaveFailure = { failure ->
            logger.error("Could not save configuration", failure)
        }

        persistence.load {
            // Auto-save only after the initial load, or loading would schedule a write
            // of what was just read.
            persistence.startAutoSave()
            // And push what was read into the services. Without this the file is correct and the running mod
            // is at its defaults until somebody opens the screen and touches something — which is the exact
            // shape of bug where a setting "does not work" and then mysteriously starts working.
            SidequestSettings.applyToPlatform()
        }

        FontReloadListener.register()
        // A resource pack can add or remove an item's texture, and both of these remember what they found the
        // last time they looked. Registered here rather than left as methods nobody calls: an `invalidate` with
        // no caller is a cache that documents a behaviour it does not have.
        FontReloadListener.onReload { ItemTextures.invalidate() }
        FontReloadListener.onReload { MinecraftItemStacks.invalidate() }
        SidequestKeybinds.register()
        SidequestHuds.register()
        SidequestWorld.register()
        SidequestHudLayer.onLayerReady = { layer -> attachHudPersistence(layer) }
        // The cinematic clock. On the render delta rather than on a tick, so a cinematic runs the same
        // wall-clock length whatever the tick rate is doing and stops while the game is paused.
        SidequestHudLayer.onFrame = { delta -> cinematicSink.advance(delta) }

        // Markers reach the world overlay layer through the bridge, redrawn from the platform's tick rather
        // than per frame: what the service reports only changes when a marker is placed, expires, or the
        // player moves an appreciable distance.
        platform.onMarkersChanged = { markerBridge.sync() }

        startPlatform()
    }

    /**
     * The mod platform: feature registry, event bus, scheduler, Minecraft adapter.
     *
     * Separate from the UI framework above and started after it. The two are independent
     * on purpose — the UI framework knows nothing about features, and the platform knows
     * nothing about rendering — so either can be worked on without the other.
     */
    /**
     * Where notifications are drawn, and where sounds come out.
     *
     * Built before the platform because it takes them as constructor arguments — the platform decides
     * *whether* to show something and these decide what it looks like, and it cannot see across the boundary
     * to the UI framework to find them itself.
     */
    private val notificationSink by lazy {
        MinecraftNotificationSink(
            // A supplier, not `platform.log`: the platform takes this sink as a constructor argument, so
            // resolving a logger here would be a cycle through `platform`'s own initialiser.
            log = { platform.log },
            // A supplier, and gated on the *layer* rather than on the queue.
            //
            // The queue object always exists, but nothing draws it until the HUD layer has been built on the
            // first frame in a world. Posting to it before then would put a toast somewhere invisible, and
            // the sink's whole reason for checking is to fall back to chat instead.
            queue = { SidequestHudLayer.hudLayer?.let { SidequestHudLayer.notifications } },
        )
    }

    private val soundSink by lazy { MinecraftSoundSink(log = { platform.log }) }

    /**
     * Where cinematics are drawn.
     *
     * Suppliers throughout, for the same reason as the notification sink: the platform takes this as a
     * constructor argument, so reaching back into `platform` eagerly would be a cycle through its own
     * initialiser — a `StackOverflowError` before the main menu, which has happened here once already.
     */
    /** Draws markers through the UI framework's world overlays. */
    private val markerBridge by lazy {
        MinecraftMarkerBridge(
            markers = platform.markers,
            overlays = SidequestHudLayer.worldOverlays,
            log = { platform.log },
        )
    }

    private val cinematicSink by lazy {
        MinecraftCinematicSink(
            log = { platform.log },
            // Gated on the *stage* rather than on the layer: nothing exists to draw on until the first frame
            // in a world, and the director treats a refusal as a cinematic that did not play and falls back
            // to a notification — which is the right outcome on a loading screen.
            stage = { SidequestHudLayer.cinematicStage },
            sounds = { platform.sounds },
            items = { platform.items },
        )
    }

    /**
     * The platform, but only if it has already been built.
     *
     * For the render loop. [platform] is a `lazy`, so touching it from a frame would *construct the whole
     * platform on the render thread* the first time — which is exactly the situation a nametag mixin is in,
     * since it runs before anything else has had a reason to ask for it.
     */
    val platformOrNull: SidequestPlatform? get() = if (lazyPlatform.isInitialized()) lazyPlatform.value else null

    private val lazyPlatform: Lazy<SidequestPlatform> = lazy {
        SidequestPlatform(
            minecraftVersion = MINECRAFT,
            notificationSink = notificationSink,
            soundSink = soundSink,
            cinematicSink = cinematicSink,
            // Alongside the configuration rather than inside it: configuration is what the player
            // edits and this is what features record, and a user clearing one should not lose the
            // other.
            storageRoot = loader.configDir.resolve(MOD_ID).resolve("data"),
        )
    }

    val platform: SidequestPlatform get() = lazyPlatform.value

    /**
     * Applies the configured server address to the platform.
     *
     * Called after login and whenever the setting changes. A blank address means no backend, which is a
     * supported state and not a misconfiguration — the local features are most of the mod, and somebody
     * who clears it should see no errors and no retries.
     */
    /** Wires the platform's first-join hook to the configured server. Called once from the initializer. */
    fun installBackendHook() {
        platform.onFirstJoin = { applyBackendConfig() }
    }

    fun applyBackendConfig() {
        val url = SidequestSettings.backendUrl.trim()
        platform.connectBackend(
            BackendConfig(
                baseUrl = url.takeIf { it.isNotEmpty() },
                // The device name shows up in the session list, so it has to say which machine this is.
                // The player's own name is the only thing available that they will recognise.
                deviceName = platform.client.localPlayerName?.let { "$it (Minecraft)" } ?: "Minecraft",
            ),
        )
    }

    /**
     * Runs the pairing flow and reports it in chat.
     *
     * In chat rather than on the config screen, because the whole point of the flow is that the user goes
     * somewhere else to approve the code — and a code on a screen they have to close to read it is a code
     * they cannot use. Chat persists, so the code is still there when they come back.
     */
    fun startPairing() {
        applyBackendConfig()
        val backend = platform.backend
        if (backend == null) {
            tell("No server is configured. Set one in the Network settings first.", isError = true)
            return
        }

        val uuid = platform.client.localPlayerId?.toString()
        val name = platform.client.localPlayerName
        if (uuid == null || name == null) {
            tell("Join a world first — pairing needs to know who you are.", isError = true)
            return
        }

        ioScope.launch {
            backend.pair(uuid, name) { progress ->
                // Back onto the client thread: chat is the game's, and this callback arrives on an IO
                // dispatcher.
                Minecraft.getInstance().execute {
                    when (progress.status) {
                        PairingStatus.WAITING -> tell(
                            "Pairing code: ${progress.code} — approve it within " +
                                "${progress.secondsRemaining(System.currentTimeMillis())}s",
                        )
                        PairingStatus.APPROVED -> tell("Paired. Sidequest is connected.")
                        PairingStatus.DENIED -> tell("The pairing was declined.", isError = true)
                        PairingStatus.EXPIRED -> tell("The pairing code expired. Try again.", isError = true)
                        PairingStatus.FAILED -> tell(
                            "Could not reach the server" + (progress.detail?.let { ": $it" } ?: "."),
                            isError = true,
                        )
                    }
                }
            }
        }
    }

    /** Forgets this device's credentials, revoking them on the server if it can be reached. */
    fun signOutOfBackend() {
        val backend = platform.backend ?: return
        ioScope.launch {
            backend.signOut()
            Minecraft.getInstance().execute { tell("Signed out. This device is no longer paired.") }
        }
    }

    /**
     * A notification in chat, for when there is no HUD to put it on.
     *
     * The main menu and the first frame of a world are both real states in which something worth saying can
     * happen — a revoked device, a failed sync — and a toast with nowhere to go would be silently dropped.
     */
    fun tellPlayer(title: String, subtitle: String? = null) {
        tell(if (subtitle == null) title else "$title — $subtitle")
    }

    /** One line in the player's chat, prefixed so it is obvious where it came from. */
    private fun tell(message: String, isError: Boolean = false) {
        platform.client.sendClientMessage(
            SqText.join(
                SqText.of("[Sidequest] ", SqStyle(color = if (isError) ERROR_COLOR else ACCENT_COLOR, bold = true)),
                SqText.of(message),
            ),
        )
    }

    /** Held for the developer inspector and for the in-game tests. */
    lateinit var sessionDiagnostics: SessionDiagnostics
        private set

    lateinit var developerTools: DeveloperTools
        private set

    /**
     * A snapshot of whatever is in the player's hand.
     *
     * Here rather than in the developer tools because it is the one place in the mod that reaches into the
     * game for an item, and a feature is not allowed to — `/sqtest item` asks the mod, which asks the game.
     */
    fun heldItemSnapshot(): dev.th7bo.sidequest.platform.item.SqItem? {
        val stack = Minecraft.getInstance().player?.mainHandItem ?: return null
        if (stack.isEmpty) return null
        return stack.toSq()
    }

    /**
     * Where the player is standing.
     *
     * Here for the same reason as [heldItemSnapshot]: it reaches into the game, and a feature is not allowed
     * to. `/sqmark place` asks the mod, which asks the game.
     */
    fun localPosition(): dev.th7bo.sidequest.platform.skyblock.SqPosition? =
        Minecraft.getInstance().player?.let {
            dev.th7bo.sidequest.platform.skyblock.SqPosition(it.x, it.y, it.z)
        }

    /** How many marker overlays are live. For the in-game test, which has no other way to see the bridge. */
    fun markerOverlayCount(): Int = SidequestHudLayer.worldOverlays.size

    private fun startPlatform() {
        sessionDiagnostics = SessionDiagnostics(platform.client)
        developerTools = DeveloperTools(platform.client, platform::setLogLevel)

        // Before `start`, so the hook is in place by the time the first join fires.
        installBackendHook()

        rareDrops = RareDropAnimation(totemAnimation = ::showTotemAnimation)
        playtime = PlaytimeTracker(
            localPlayer = { platform.client.localPlayerId?.let { PlayerId.of(it) } },
        )
        gardenBobbing = GardenViewBobbing(
            readBobbing = { net.minecraft.client.Minecraft.getInstance().options.bobView().get() },
            writeBobbing = { net.minecraft.client.Minecraft.getInstance().options.bobView().set(it) },
        )

        waypoints = SharedWaypoints(
            localPlayer = { platform.client.localPlayerId?.let { PlayerId.of(it) } },
            standingAt = { platform.localPosition },
        )
        waypoints.openManager = ::openWaypointManager

        pings = PingSystem(
            aimedAt = { platform.aimedAt },
            standingAt = { platform.localPosition },
            publish = ::publishPing,
        )

        val refusals = platform.start(
            sessionDiagnostics,
            developerTools,
            rareDrops,
            playtime,
            gardenBobbing,
            waypoints,
            pings,
        )
        for (refusal in refusals) logger.warn("Feature did not start — {}", refusal)
    }

    private lateinit var rareDrops: RareDropAnimation

    private lateinit var playtime: PlaytimeTracker

    private lateinit var gardenBobbing: GardenViewBobbing

    private lateinit var waypoints: SharedWaypoints

    /**
     * Opens the waypoint manager.
     *
     * Rebuilt from the book each time, because the screen's *shape* is a snapshot — a category per
     * collection, a section per waypoint. Edits to a row's contents write straight through; edits that add or
     * remove a row reopen, which is why `reopen` exists rather than the screen trying to mutate its own tree.
     *
     * No persistence controller is passed. This screen's bindings write into the book, which saves itself —
     * handing it the configuration's controller would have it write waypoint rows into the settings file.
     */
    fun openWaypointManager() {
        if (!::waypoints.isInitialized) return
        val actions = WaypointActions(
            edit = waypoints::editWaypoint,
            delete = waypoints::deleteWaypoint,
            editCollection = waypoints::editCollection,
            deleteCollection = waypoints::deleteCollection,
            addCollection = { waypoints.addCollection() },
            reopen = { Minecraft.getInstance().execute(::openWaypointManager) },
        )
        Minecraft.getInstance().setScreenAndShow(
            SidequestConfigScreen(buildWaypointScreen(waypoints.book(), actions), activeTheme()),
        )
    }

    private lateinit var pings: PingSystem

    /**
     * Pings what the crosshair is on. Called from the keybind on a tap.
     *
     * Guarded rather than assumed: the key exists from the moment the mod loads and the feature does not,
     * so pressing it on the title screen must do nothing rather than throw.
     *
     * The style is suggested by what was hit — a player is somebody to look at, anything else alive is
     * usually a warning — because the fast path should be right most of the time without a menu. It is only
     * a guess, and the cost of a wrong one is low: hold the key instead and choose.
     */
    fun pingWhereLooking() {
        if (!::pings.isInitialized) return
        val target = CrosshairTarget.current()
        pings.ping(target?.let(CrosshairTarget::suggest) ?: PingStyle.GO_HERE)
    }

    /**
     * Opens the ping wheel. Called from the keybind once the key has been held.
     *
     * **The target is captured here, before the screen opens.** Opening a screen frees the cursor, which
     * moves the crosshair off whatever the player was aiming at — so asking again when the wheel closes
     * would ping wherever the mouse happened to end up. Somebody aims, then asks for the menu.
     */
    fun openPingWheel() {
        if (!::pings.isInitialized) return
        val frozen = CrosshairTarget.current() ?: return

        Minecraft.getInstance().setScreenAndShow(
            PingWheelScreen(
                binding = SidequestKeybinds.ping,
                onChosen = { style ->
                    // Null is a dismissal — escape, or letting go without pushing. Deliberately not a
                    // default ping: somebody who opened the wheel and thought better of it has said so.
                    if (style != null) pings.ping(style, at = frozen.position)
                },
            ),
        )
    }

    /**
     * Sends a ping to the group.
     *
     * Returns whether anything was handed to the connection — false when there is no realtime client, which
     * is the normal state for somebody who has not paired a backend. The feature already drew the ping
     * locally by the time this is called, so a false here means "only you saw that" and not "it failed".
     *
     * The send itself is fire-and-forget on a background job. A ping is worthless a second later, so waiting
     * on the round trip would stall the client thread for something that has no useful answer.
     */
    private fun publishPing(
        location: dev.th7bo.sidequest.platform.skyblock.SqLocation,
        style: dev.th7bo.sidequest.platform.ping.PingStyle,
        label: String,
    ): Boolean {
        val platform = platformOrNull ?: return false
        val realtime = platform.realtime ?: return false
        val payload = dev.th7bo.sidequest.protocol.RealtimePayload.Ping(
            location = location,
            label = label,
            style = style.wireName,
        )

        ioScope.launch {
            runCatching {
                realtime.send(
                    dev.th7bo.sidequest.protocol.RealtimeMessage(
                        messageId = java.util.UUID.randomUUID().toString(),
                        // The server's clock, like every other timestamp that crosses the wire — two machines
                        // disagree about the time constantly, and a ping is judged stale by its age.
                        timestampMillis = platform.backend?.serverTime
                            ?.toServer(System.currentTimeMillis())
                            ?: System.currentTimeMillis(),
                        scope = payload.scope,
                        payload = payload,
                    ),
                )
            }.onFailure { logger.debug("Could not send a ping", it) }
        }
        return true
    }

    /**
     * Minecraft's own totem animation, for the drop animation's familiar option.
     *
     * Best-effort by design. The name comes from a chat line, so there may be no item registered under it —
     * "Enchanted Hay Bale" is not a Minecraft item id — and the honest answer then is false rather than a
     * guess at something that looks vaguely similar. The feature has already shown its toast by this point,
     * so failing here costs the flourish and nothing else.
     */
    private fun showTotemAnimation(itemName: String): Boolean {
        val client = net.minecraft.client.Minecraft.getInstance()
        val stack = itemStackFor(itemName) ?: return false
        client.gameRenderer.displayItemActivation(stack)
        return true
    }

    /**
     * A stack to show for a dropped item's name.
     *
     * SkyBlock item names are not Minecraft item ids, and nothing in this mod maps between them yet — so this
     * is deliberately a single fallback rather than a lookup that would be wrong most of the time. A totem is
     * what the vanilla animation is *for*, and using it says "something happened" without claiming to know
     * what the item looks like.
     */
    private fun itemStackFor(itemName: String): net.minecraft.world.item.ItemStack? =
        net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)
            .takeIf { itemName.isNotBlank() }

    /**
     * Writes the configuration after something changed it from outside the screen.
     *
     * The ignore action on a drop's toast is the case this exists for: it edits a setting the screen owns,
     * from a place the screen is not open. The controller normally saves when a *binding* changes, and a
     * plain property write is invisible to it — so this asks for the save explicitly.
     */
    fun saveConfiguration() {
        runCatching { persistence.scheduleSave() }
            .onFailure { logger.warn("Could not save the configuration", it) }
    }

    /** Schema version of the on-disk configuration. Bump alongside a new migration. */
    const val CONFIG_SCHEMA_VERSION: Int = 1

    /** Bumped when the persisted shape of a HUD placement changes. */
    const val HUD_LAYOUT_SCHEMA_VERSION: Int = 1
}

/** The accent the mod prefixes its chat with. Matches the default accent in the settings. */
private const val ACCENT_COLOR = 0xA78BFA

/** For anything the user has to act on. */
private const val ERROR_COLOR = 0xF87171
