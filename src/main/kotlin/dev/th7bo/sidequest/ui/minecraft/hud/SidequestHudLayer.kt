package dev.th7bo.sidequest.ui.minecraft.hud

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.hud.HudElementNode
import dev.th7bo.sidequest.ui.core.hud.HudLayerNode
import dev.th7bo.sidequest.ui.components.cinematic.CinematicStageNode
import dev.th7bo.sidequest.ui.components.notification.NotificationRegionNode
import dev.th7bo.sidequest.ui.components.world.WaypointLayerNode
import dev.th7bo.sidequest.ui.core.icon.IconRegistry
import dev.th7bo.sidequest.ui.core.layout.BoxNode
import dev.th7bo.sidequest.ui.core.notification.NotificationQueue
import dev.th7bo.sidequest.ui.core.world.WorldOverlayLayer
import dev.th7bo.sidequest.ui.minecraft.world.MinecraftWorldProjector
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

    /** Notifications shown over the game. */
    public val notifications: NotificationQueue = NotificationQueue()

    /** Waypoints and other world-anchored overlays. */
    public val worldOverlays: WorldOverlayLayer = WorldOverlayLayer()

    /**
     * The cinematic stage, once there is a frame to build it on.
     *
     * Read by the cinematic sink, which is why it is exposed rather than private: the sink is on the platform
     * side and cannot see this object's internals, and a cinematic submitted before the first frame has to be
     * refused rather than queued into a stage that does not exist.
     */
    public val cinematicStage: CinematicStageNode? get() = cinematic

    private var cinematic: CinematicStageNode? = null

    /** Advances the cinematic clock each frame. Set by the mod, which owns the sink. */
    public var onFrame: ((deltaSeconds: Float) -> Unit)? = null

    private var notificationRegion: NotificationRegionNode? = null
    private var waypoints: WaypointLayerNode? = null
    private var root: BoxNode? = null

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

    /**
     * Called once the layer exists and has been populated.
     *
     * Separate from [onPopulate] because loading saved placements is a different concern
     * from deciding what is on the layer, and it has to happen after — there is nothing
     * to position until the elements are there.
     */
    public var onLayerReady: ((HudLayerNode) -> Unit)? = null

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
            root?.preferredSize = screenSize
        }

        val current = runtime ?: build(client)
        val delta = deltaTracker.getRealtimeDeltaTicks() / TICKS_PER_SECOND
        frameIndex++

        // Rebuilt every frame: the camera moves, and a cached projector is a stale basis.
        waypoints?.projector = MinecraftWorldProjector.forCurrentFrame(client, screenSize)
        if (worldOverlays.size > 0) waypoints?.invalidatePaint()

        // Timeouts run off the render clock rather than ticks, so a notification lasts the
        // same wall-clock time whether or not the server is keeping up.
        if (notifications.tick(delta)) notificationRegion?.invalidateMeasure()

        // The cinematic clock, on the same real-time delta as the notifications: a cinematic must run the same
        // wall-clock length whatever the tick rate is doing, and must stop while the game is paused.
        onFrame?.invoke(delta)

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
        layer = built
        runtime = created

        // World overlays under the HUD, notifications above it, the cinematic above everything: a waypoint is
        // part of the scene, a notification is an interruption, and a cinematic is the only thing that is
        // allowed to be the whole screen. The stacking follows what each one is for rather than registration
        // order.
        val waypointLayer = WaypointLayerNode(UiId.of(Sidequest.MOD_ID, "waypoints"), worldOverlays, context)
        waypoints = waypointLayer

        val region = NotificationRegionNode(
            UiId.of(Sidequest.MOD_ID, "notifications"),
            notifications,
            context,
        )
        notificationRegion = region

        val stage = CinematicStageNode(UiId.of(Sidequest.MOD_ID, "cinematic"), context)
        cinematic = stage

        // A box fixed to the screen, so all three layers are measured against the full
        // viewport rather than against each other's sizes.
        val stack = BoxNode(UiId.of(Sidequest.MOD_ID, "hud.root")).apply {
            preferredSize = screenSize
            addChildren(waypointLayer, built, region, stage)
        }
        root = stack
        created.root = stack

        onPopulate?.invoke(built, context)
        onLayerReady?.invoke(built)
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
        notifications.dispose()
        notificationRegion = null
        cinematic = null
        onFrame = null
        waypoints = null
        root = null
        runtime?.dispose()
        runtime = null
        layer = null
        measurer = null
        components = null
    }

    private const val TICKS_PER_SECOND = 20f
}
