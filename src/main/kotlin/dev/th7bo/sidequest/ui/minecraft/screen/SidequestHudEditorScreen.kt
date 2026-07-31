package dev.th7bo.sidequest.ui.minecraft.screen

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.ui.components.hud.HudEditorScreenNode
import dev.th7bo.sidequest.ui.components.registerStandardIcons
import dev.th7bo.sidequest.ui.minecraft.registerMinecraftIcons
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.hud.HudLayerNode
import dev.th7bo.sidequest.ui.core.hud.editor.HudEditorSession
import dev.th7bo.sidequest.ui.core.icon.IconRegistry
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.TextMeasurer
import dev.th7bo.sidequest.ui.theme.Theme
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * The in-game HUD editor.
 *
 * Edits the live layer, so what is being arranged is what the game actually draws. The
 * screen contributes the chrome and the inspector; the HUDs themselves are still
 * rendered by [dev.th7bo.sidequest.ui.minecraft.hud.SidequestHudLayer], which Minecraft
 * draws beneath the screen.
 *
 * @param onSave called when the editor closes normally, so placements can be persisted.
 */
public class SidequestHudEditorScreen(
    private val layer: HudLayerNode,
    theme: Theme,
    private val onSave: (() -> Unit)? = null,
) : SidequestScreen(Component.literal("HUD Editor"), theme) {

    private val registrationScope = RegistrationScope(UiId.of(Sidequest.MOD_ID, "hud_editor"))
    private val icons = IconRegistry()

    /** The editing session. Exposed for in-game tests and for diagnostics. */
    public var session: HudEditorSession? = null
        private set

    /** The screen's node tree. */
    public var editorRoot: HudEditorScreenNode? = null
        private set

    override fun onRuntimeCreated(runtime: UiRuntime) {
        icons.registerStandardIcons(registrationScope)
        // Minecraft's own item textures alongside the mod's flat glyphs, so a screen can
        // use whichever reads better in place.
        icons.registerMinecraftIcons(registrationScope)
    }

    override fun buildTree(runtime: UiRuntime, measurer: TextMeasurer): UiNode {
        val context = ComponentContext(
            theme = theme,
            animations = runtime.animations,
            scheduler = runtime,
            icons = icons,
            isDevelopment = net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment,
        )

        // The session reads the viewport from the runtime rather than capturing it, so a
        // window resize while the editor is open is picked up on the next gesture.
        val built = HudEditorSession(layer, { runtime.viewport })
        // Elements swap to preview data while the editor is open. A HUD arranged from a
        // menu often has nothing real to show, and one rendering as "0 / 0" cannot be
        // positioned sensibly.
        built.setEditing(true)
        session = built

        val node = HudEditorScreenNode(UiId.of(Sidequest.MOD_ID, "hud_editor.root"), built, context)
        editorRoot = node
        return node
    }

    override fun tick() {
        super.tick()
        // Keeps the inspector's readouts following a drag in progress.
        editorRoot?.tick()
    }

    /**
     * No backdrop.
     *
     * A HUD editor that dims the world is showing you something other than what you are
     * arranging. The chrome alone is enough to signal that the editor is open, and it is
     * drawn over the HUDs because the screen renders after the HUD layer.
     */
    override fun renderBackdrop(graphics: GuiGraphicsExtractor) {
        // Intentionally empty.
    }

    override fun onClose() {
        // Back to live data before the screen goes away, or the HUDs would keep showing
        // samples over the actual game.
        session?.setEditing(false)
        onSave?.invoke()
        super.onClose()
    }

    override fun onRuntimeDisposed(runtime: UiRuntime) {
        registrationScope.dispose()
        session = null
        editorRoot = null
    }

    /** The viewport the session is working against, for tests. */
    public val viewport: Size get() = runtime?.viewport ?: Size.Zero
}
