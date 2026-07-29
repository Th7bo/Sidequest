package dev.th7bo.sidequest.ui.minecraft.hud

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.hud.HudElementNode
import dev.th7bo.sidequest.ui.core.hud.HudLayerNode
import dev.th7bo.sidequest.ui.core.icon.IconRegistry
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftTextMeasurer
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftUiRenderer
import dev.th7bo.sidequest.ui.rendering.FrameInfo
import dev.th7bo.sidequest.ui.state.UiThread
import dev.th7bo.sidequest.ui.theme.Theme
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

/**
 * Draws the HUD layer during normal gameplay.
 *
 * The whole layer is one Fabric HUD element rather than one per definition: the
 * framework already owns ordering, placement and visibility, and registering each HUD
 * separately would hand that to Minecraft and lose the z-order the editor controls.
 *
 * Everything here is lifecycle. The layer itself is framework-independent and the same
 * code the headless tests drive.
 */
public object SidequestHudLayer : HudElement {

    private val registrationScope = RegistrationScope(UiId.of(Sidequest.MOD_ID, "hud"))

    /** Icons available to HUD content. */
    public val icons: IconRegistry = IconRegistry()

    private var runtime: UiRuntime? = null
    private var measurer: MinecraftTextMeasurer? = null
    private var layer: HudLayerNode? = null

    private var screenSize: Size = Size(1f, 1f)
    private var frameIndex: Long = 0

    /** Built lazily on the first frame, when a font and a window actually exist. */
    private var components: ComponentContext? = null

    /**
     * Populates the layer. Called once the runtime exists, so HUD content can use the
     * component context.
     */
    public var onPopulate: ((HudLayerNode, ComponentContext) -> Unit)? = null

    /** The live layer, for the editor and for diagnostics. */
    public val hudLayer: HudLayerNode? get() = layer

    public fun register(theme: () -> Theme) {
        themeProvider = theme
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(Sidequest.MOD_ID, "hud_layer"),
            this,
        )
    }

    private var themeProvider: () -> Theme = { dev.th7bo.sidequest.ui.theme.DarkTheme }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        val client = Minecraft.getInstance()

        // Hide while a screen is open: the configuration screen dims the world, and
        // leaving HUDs on top of it would defeat that.
        if (client.level == null) return

        UiThread.bind()

        val width = graphics.guiWidth().toFloat()
        val height = graphics.guiHeight().toFloat()
        if (screenSize.width != width || screenSize.height != height) {
            screenSize = Size(width, height)
            runtime?.viewport = screenSize
        }

        val current = runtime ?: build(client)
        val delta = deltaTracker.getRealtimeDeltaTicks() / TICKS_PER_SECOND
        frameIndex++

        val renderer = MinecraftUiRenderer(
            graphics = graphics,
            font = client.font,
            textMeasurer = measurer!!,
            frame = FrameInfo(
                viewport = Rect.of(Vec2.Zero, screenSize),
                deltaSeconds = delta,
                frameIndex = frameIndex,
                guiScale = client.window.guiScale.toFloat(),
            ),
        )

        try {
            current.frame(renderer, delta)
        } finally {
            renderer.endFrame()
        }
    }

    private fun build(client: Minecraft): UiRuntime {
        val font = client.font

        val created = UiRuntime(themeProvider())
        created.viewport = screenSize

        val textMeasurer = MinecraftTextMeasurer(font)
        measurer = textMeasurer

        val context = ComponentContext(
            theme = themeProvider(),
            animations = created.animations,
            scheduler = { block -> client.execute(block) },
            icons = icons,
            isDevelopment = net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment,
        )
        components = context

        val built = HudLayerNode(UiId.of(Sidequest.MOD_ID, "hud.layer")) { screenSize }
        created.root = built
        layer = built
        runtime = created

        onPopulate?.invoke(built, context)
        return created
    }

    /** Adds an element to the live layer, building the runtime first if needed. */
    public fun add(element: HudElementNode) {
        layer?.add(element)
    }

    /** Drops caches when resources reload; a new font changes every measurement. */
    public fun onResourceReload() {
        measurer?.invalidate()
        runtime?.root?.forEachInTree { it.invalidateMeasure() }
    }

    /** Releases everything. For a full teardown. */
    public fun dispose() {
        registrationScope.dispose()
        runtime?.dispose()
        runtime = null
        layer = null
        measurer = null
        components = null
    }

    private const val TICKS_PER_SECOND = 20f
}
