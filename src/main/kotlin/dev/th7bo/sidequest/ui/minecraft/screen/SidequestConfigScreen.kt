package dev.th7bo.sidequest.ui.minecraft.screen

import dev.th7bo.sidequest.ui.components.ConfigScreenController
import dev.th7bo.sidequest.ui.components.registerStandardIcons
import dev.th7bo.sidequest.ui.minecraft.registerMinecraftIcons
import dev.th7bo.sidequest.ui.core.icon.IconRegistry
import dev.th7bo.sidequest.ui.components.ConfigScreenLayoutNode
import dev.th7bo.sidequest.ui.components.registerStandardControls
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.component.ComponentRegistry
import dev.th7bo.sidequest.ui.core.overlay.OverlayHost
import dev.th7bo.sidequest.ui.core.overlay.OverlayPlacement
import dev.th7bo.sidequest.ui.core.overlay.OverlayRootNode
import dev.th7bo.sidequest.ui.core.persistence.ConfigPersistenceController
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.rendering.TextMeasurer
import dev.th7bo.sidequest.ui.theme.Theme
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * The in-game configuration screen.
 *
 * Assembles the pieces the earlier phases built — the component registry, the standard
 * controls, the virtualized list and search — and hosts them in a Minecraft screen.
 * Everything above [SidequestScreen] is framework-independent; this class is the only
 * part that knows both.
 */
public open class SidequestConfigScreen(
    private val definition: ConfigScreen,
    theme: Theme,
    /** Optional: when supplied, the screen saves on close. */
    private val persistence: ConfigPersistenceController? = null,
) : SidequestScreen(Component.literal(definition.title.peek()), theme) {

    private val registrationScope = RegistrationScope(definition.id.child("screen"))
    private val registry = ComponentRegistry()

    /** Exposes category selection, search and navigation to callers and to tests. */
    public var controller: ConfigScreenController? = null
        private set

    /** The overlay layer hosting any open popup. */
    public var overlayRoot: OverlayRootNode? = null
        private set

    /** The screen's node tree, for diagnostics and in-game tests. */
    public val uiRoot: UiNode? get() = overlayRoot

    private val icons = IconRegistry()

    override fun onRuntimeCreated(runtime: UiRuntime) {
        registry.registerStandardControls(registrationScope)
        icons.registerStandardIcons(registrationScope)
        // Minecraft's own item textures alongside the mod's flat glyphs, so a screen can
        // use whichever reads better in place.
        icons.registerMinecraftIcons(registrationScope)
    }

    override fun buildTree(runtime: UiRuntime, measurer: TextMeasurer): UiNode {
        // The overlay root is the tree root, so popups paint above everything and are
        // hit tested before it. Controls reach it through the component context, which
        // means a control never needs to know where in the tree it sits.
        lateinit var root: OverlayRootNode

        val context = ComponentContext(
            theme = theme,
            animations = runtime.animations,
            scheduler = runtime,
            overlays = object : OverlayHost {
                override val isShowingOverlay: Boolean get() = root.isShowingOverlay
                override fun show(
                    key: Any,
                    anchor: UiNode,
                    content: UiNode,
                    placement: OverlayPlacement,
                    dismissOnOutsideClick: Boolean,
                    onDismiss: (() -> Unit)?,
                ) = root.show(key, anchor, content, placement, dismissOnOutsideClick, onDismiss)

                override fun dismiss(key: Any): Boolean = root.dismiss(key)
                override fun dismissAll(): Boolean = root.dismissAll()
            },
            icons = icons,
            isDevelopment = FabricLoader.getInstance().isDevelopmentEnvironment,
        )

        val built = ConfigScreenController(definition, registry, context, runtime.focus)
        controller = built

        val layout = ConfigScreenLayoutNode(
            id = definition.id.child("layout"),
            controller = built,
            componentContext = context,
            onSaveAndClose = {
                persistence?.saveNow()
                onClose()
            },
            onClose = { onClose() },
        )

        root = OverlayRootNode(definition.id.child("root"), layout)
        overlayRoot = root
        return root
    }

    /**
     * Escape closes an open popup before it closes the screen.
     *
     * Without this the first Escape would take the whole screen away, which is never
     * what someone with a dropdown open means by it.
     */
    override fun onClose() {
        val dismissed = (runtime?.root as? OverlayRootNode)?.dismissAll() ?: false
        if (!dismissed) super.onClose()
    }

    /**
     * Dims the world behind the screen rather than hiding it.
     *
     * The design language is translucent surfaces over the game, so the backdrop is a
     * scrim and not an opaque panel.
     */
    override fun renderBackdrop(graphics: GuiGraphicsExtractor) {
        graphics.fill(0, 0, width, height, theme.tokens.colors.windowBackground.argb)
    }

    override fun onRuntimeDisposed(runtime: UiRuntime) {
        // Flush before the screen goes away: a debounced save still pending when the
        // player closes the screen must not be lost.
        persistence?.saveNow()
        registrationScope.dispose()
        // Releases the controller's visibility subscriptions. Without it a closed screen keeps rebuilding
        // its row list every time a setting it was watching changes, for the life of the game.
        controller?.dispose()
        controller = null
        overlayRoot = null
    }
}
