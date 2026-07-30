package dev.th7bo.sidequest

import dev.th7bo.sidequest.features.SessionDiagnostics
import dev.th7bo.sidequest.platform.backend.BackendConfig
import dev.th7bo.sidequest.platform.backend.PairingStatus
import dev.th7bo.sidequest.platform.minecraft.SidequestPlatform
import dev.th7bo.sidequest.platform.text.SqStyle
import dev.th7bo.sidequest.platform.text.SqText
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.core.persistence.ConfigPersistenceController
import dev.th7bo.sidequest.ui.core.persistence.JsonFileConfigStore
import dev.th7bo.sidequest.ui.minecraft.lifecycle.FontReloadListener
import dev.th7bo.sidequest.ui.minecraft.lifecycle.SidequestKeybinds
import dev.th7bo.sidequest.ui.core.hud.HudLayoutPersistence
import dev.th7bo.sidequest.ui.hud.HudPlacement
import dev.th7bo.sidequest.ui.ids.ProfileId
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.minecraft.hud.SidequestHudLayer
import dev.th7bo.sidequest.ui.minecraft.screen.SidequestConfigScreen
import dev.th7bo.sidequest.ui.minecraft.screen.SidequestHudEditorScreen
import dev.th7bo.sidequest.ui.state.UiScheduler
import dev.th7bo.sidequest.ui.theme.DarkTheme
import dev.th7bo.sidequest.ui.theme.HighContrastDarkTheme
import dev.th7bo.sidequest.ui.theme.LightTheme
import dev.th7bo.sidequest.ui.theme.Theme
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
    fun activeTheme(): Theme = when (SidequestSettings.theme) {
        "light" -> LightTheme
        "high_contrast_dark" -> HighContrastDarkTheme
        else -> DarkTheme
    }

    /** Creates the configuration screen, ready to hand to `Minecraft.setScreen`. */
    fun createConfigScreen(): SidequestConfigScreen =
        SidequestConfigScreen(configScreen, activeTheme(), persistence)

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
        }

        FontReloadListener.register()
        SidequestKeybinds.register()
        SidequestHuds.register()
        SidequestWorld.register()
        SidequestHudLayer.onLayerReady = { layer -> attachHudPersistence(layer) }

        startPlatform()
    }

    /**
     * The mod platform: feature registry, event bus, scheduler, Minecraft adapter.
     *
     * Separate from the UI framework above and started after it. The two are independent
     * on purpose — the UI framework knows nothing about features, and the platform knows
     * nothing about rendering — so either can be worked on without the other.
     */
    val platform: SidequestPlatform by lazy {
        SidequestPlatform(
            minecraftVersion = MINECRAFT,
            // Alongside the configuration rather than inside it: configuration is what the player
            // edits and this is what features record, and a user clearing one should not lose the
            // other.
            storageRoot = loader.configDir.resolve(MOD_ID).resolve("data"),
        )
    }

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

    private fun startPlatform() {
        sessionDiagnostics = SessionDiagnostics(platform.client)

        // Before `start`, so the hook is in place by the time the first join fires.
        installBackendHook()

        val refusals = platform.start(sessionDiagnostics)
        for (refusal in refusals) logger.warn("Feature did not start — {}", refusal)
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
