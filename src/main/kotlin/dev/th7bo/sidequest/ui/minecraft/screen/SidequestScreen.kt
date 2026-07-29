package dev.th7bo.sidequest.ui.minecraft.screen

import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.minecraft.input.KeyMapping
import dev.th7bo.sidequest.ui.minecraft.lifecycle.FontReloadListener
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftTextMeasurer
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftUiRenderer
import dev.th7bo.sidequest.ui.rendering.FrameInfo
import dev.th7bo.sidequest.ui.rendering.TextMeasurer
import dev.th7bo.sidequest.ui.state.UiThread
import dev.th7bo.sidequest.ui.theme.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Hosts a [UiRuntime] inside a Minecraft screen.
 *
 * Everything version-specific about running the framework in the game lives here and in
 * the renderer: lifecycle, input forwarding, resolution and GUI-scale changes. The
 * runtime itself never learns it is inside Minecraft.
 *
 * Coordinates are in *GUI-scaled* space, which is what Minecraft already hands to
 * screens and what the framework treats as its logical unit space. No conversion is
 * needed and none is done — the GUI scale changes the viewport, not the units.
 */
public abstract class SidequestScreen(
    title: Component,
    protected val theme: Theme,
) : Screen(title) {

    /** The runtime driving this screen. Created on [init], disposed on [removed]. */
    protected var runtime: UiRuntime? = null
        private set

    private var textMeasurer: MinecraftTextMeasurer? = null

    private var lastFrameNanos: Long = 0
    private var frameIndex: Long = 0

    /** Held while the screen is open, closed on [removed] so nothing outlives it. */
    private var reloadSubscription: AutoCloseable? = null

    /** Set when a frame leaves the renderer's stacks unbalanced. Surfaced in dev builds. */
    public var lastFrameWasUnbalanced: Boolean = false
        private set

    /**
     * Builds the node tree for this screen.
     *
     * Called on [init] and again on every resize, because a new viewport may change the
     * structure and not just the layout.
     */
    protected abstract fun buildTree(runtime: UiRuntime, measurer: TextMeasurer): UiNode

    /** Hook for wiring persistence, search and undo once the runtime exists. */
    protected open fun onRuntimeCreated(runtime: UiRuntime) {
        // Nothing by default.
    }

    /** Hook for flushing state before the screen goes away. */
    protected open fun onRuntimeDisposed(runtime: UiRuntime) {
        // Nothing by default.
    }

    override fun init() {
        // The client thread is the UI thread. Binding here rather than lazily means a
        // stray background access fails immediately instead of silently winning a race.
        UiThread.bind()

        val existing = runtime
        val measurer = textMeasurer ?: MinecraftTextMeasurer(minecraft.font).also { textMeasurer = it }

        if (existing == null) {
            reloadSubscription = FontReloadListener.onReload { onResourceReload() }
            val created = UiRuntime(theme)
            created.viewport = Size(width.toFloat(), height.toFloat())
            runtime = created
            onRuntimeCreated(created)
            created.root = buildTree(created, measurer)
        } else {
            // A resize: keep the runtime and its state, hand it the new viewport.
            existing.viewport = Size(width.toFloat(), height.toFloat())
        }
    }

    /**
     * Rebuilds the tree from scratch, discarding node state.
     *
     * Only for a genuine content change; a resize must not call this, or scroll position
     * and focus would be lost on every window drag.
     */
    protected fun rebuildTree() {
        val current = runtime ?: return
        val measurer = textMeasurer ?: return
        current.root = buildTree(current, measurer)
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        // `super.resize` re-runs `init`, which updates the viewport. The runtime
        // invalidates measurement itself when the viewport changes, so a resolution or
        // GUI-scale change needs nothing further here.
        runtime?.viewport = Size(width.toFloat(), height.toFloat())
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
    ) {
        renderBackdrop(graphics)

        val current = runtime ?: return
        val measurer = textMeasurer ?: return

        val now = System.nanoTime()
        val delta = if (lastFrameNanos == 0L) {
            0f
        } else {
            // Clamped: a long GC pause or a paused window must not make animations jump.
            ((now - lastFrameNanos) / NANOS_PER_SECOND).toFloat().coerceIn(0f, MAX_DELTA_SECONDS)
        }
        lastFrameNanos = now
        frameIndex++

        val renderer = MinecraftUiRenderer(
            graphics = graphics,
            font = minecraft.font,
            textMeasurer = measurer,
            frame = FrameInfo(
                viewport = dev.th7bo.sidequest.ui.geometry.Rect(0f, 0f, width.toFloat(), height.toFloat()),
                deltaSeconds = delta,
                frameIndex = frameIndex,
                guiScale = minecraft.window.guiScale.toFloat(),
            ),
        )

        try {
            current.frame(renderer, delta)
        } finally {
            // Always unwind: a component that throws mid-paint would otherwise leave a
            // scissor or pose pushed and corrupt every later frame.
            lastFrameWasUnbalanced = !renderer.isBalanced
            renderer.endFrame()
        }
    }

    /** Draws whatever sits behind the UI. Override to dim the world, or to draw nothing. */
    protected open fun renderBackdrop(graphics: GuiGraphicsExtractor) {
        graphics.fill(0, 0, width, height, theme.tokens.colors.scrim.argb)
    }

    // -- input forwarding ---------------------------------------------------

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        runtime?.input?.pointerMoved(Vec2(mouseX.toFloat(), mouseY.toFloat()))
    }

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        val handled = runtime?.input?.pointerPressed(
            position = Vec2(event.x().toFloat(), event.y().toFloat()),
            button = KeyMapping.toMouseButton(event.button()),
            modifiers = KeyMapping.toModifiers(event.modifiers()),
            clickCount = if (doubled) 2 else 1,
        ) ?: false
        return handled || super.mouseClicked(event, doubled)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val handled = runtime?.input?.pointerReleased(
            position = Vec2(event.x().toFloat(), event.y().toFloat()),
            button = KeyMapping.toMouseButton(event.button()),
            modifiers = KeyMapping.toModifiers(event.modifiers()),
        ) ?: false
        return handled || super.mouseReleased(event)
    }

    override fun mouseDragged(
        event: MouseButtonEvent,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        // The framework turns a move into a drag itself while it holds pointer capture,
        // so a drag is forwarded as a move rather than as a separate event type.
        val handled = runtime?.input?.pointerMoved(
            Vec2(event.x().toFloat(), event.y().toFloat()),
            KeyMapping.toModifiers(event.modifiers()),
        ) ?: false
        return handled || super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        val handled = runtime?.input?.scrolled(
            position = Vec2(mouseX.toFloat(), mouseY.toFloat()),
            scrollX = scrollX.toFloat(),
            scrollY = scrollY.toFloat(),
        ) ?: false
        return handled || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val handled = runtime?.input?.keyPressed(
            key = KeyMapping.toKey(event.key()),
            modifiers = KeyMapping.toModifiers(event.modifiers()),
            rawCode = event.key(),
        ) ?: false
        return handled || super.keyPressed(event)
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        val handled = runtime?.input?.keyReleased(
            key = KeyMapping.toKey(event.key()),
            modifiers = KeyMapping.toModifiers(event.modifiers()),
            rawCode = event.key(),
        ) ?: false
        return handled || super.keyReleased(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val handled = runtime?.input?.charTyped(event.codepoint()) ?: false
        return handled || super.charTyped(event)
    }

    // -- lifecycle ----------------------------------------------------------

    /** Configuration screens should not pause a singleplayer world. */
    override fun isPauseScreen(): Boolean = false

    override fun removed() {
        val current = runtime
        if (current != null) {
            onRuntimeDisposed(current)
            current.dispose()
        }
        reloadSubscription?.close()
        reloadSubscription = null
        runtime = null
        textMeasurer = null
        lastFrameNanos = 0
        super.removed()
    }

    /** Drops cached text layouts. Called when a resource reload changes the font. */
    public fun onResourceReload() {
        textMeasurer?.invalidate()
        runtime?.root?.forEachInTree { it.invalidateMeasure() }
    }

    protected companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        /** Longest delta an animation will ever see, so a stall does not teleport it. */
        private const val MAX_DELTA_SECONDS = 0.1f
    }
}
