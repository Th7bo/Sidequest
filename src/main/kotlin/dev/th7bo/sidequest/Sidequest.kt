package dev.th7bo.sidequest

import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.core.persistence.ConfigPersistenceController
import dev.th7bo.sidequest.ui.core.persistence.JsonFileConfigStore
import dev.th7bo.sidequest.ui.minecraft.lifecycle.FontReloadListener
import dev.th7bo.sidequest.ui.minecraft.lifecycle.SidequestKeybinds
import dev.th7bo.sidequest.ui.minecraft.hud.SidequestHudLayer
import dev.th7bo.sidequest.ui.minecraft.screen.SidequestConfigScreen
import dev.th7bo.sidequest.ui.minecraft.screen.SidequestHudEditorScreen
import dev.th7bo.sidequest.ui.state.UiScheduler
import dev.th7bo.sidequest.ui.theme.DarkTheme
import dev.th7bo.sidequest.ui.theme.HighContrastDarkTheme
import dev.th7bo.sidequest.ui.theme.LightTheme
import dev.th7bo.sidequest.ui.theme.Theme
import kotlinx.coroutines.CoroutineScope
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
     * The HUD editor, or null when the HUD layer has not been built yet — it is created
     * lazily on the first frame in a world, so there is nothing to edit on a menu screen.
     */
    fun createHudEditorScreen(): SidequestHudEditorScreen? {
        val layer = SidequestHudLayer.hudLayer ?: return null
        return SidequestHudEditorScreen(layer, activeTheme(), onSave = { persistence.saveNow() })
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
    }

    /** Schema version of the on-disk configuration. Bump alongside a new migration. */
    const val CONFIG_SCHEMA_VERSION: Int = 1
}
