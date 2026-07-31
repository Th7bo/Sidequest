package dev.th7bo.sidequest.ui.testkit

import dev.th7bo.sidequest.ui.geometry.Dp
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.FrameInfo
import dev.th7bo.sidequest.ui.rendering.Gradient
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.rendering.ItemRef
import dev.th7bo.sidequest.ui.rendering.Shadow
import dev.th7bo.sidequest.ui.rendering.TextLayout
import dev.th7bo.sidequest.ui.rendering.TextMeasurer
import dev.th7bo.sidequest.ui.rendering.TextureRef
import dev.th7bo.sidequest.ui.rendering.Transform
import dev.th7bo.sidequest.ui.rendering.UiRenderer

/**
 * A [UiRenderer] that records what it was asked to draw instead of drawing it.
 *
 * This is what lets the whole framework — layout, input, focus, animation, theming — be
 * tested without a window or a graphics context, and it is what the fake-renderer demo
 * runs against.
 *
 * It also validates the renderer contract: unbalanced clip, transform or opacity stacks
 * fail the test rather than corrupting later frames the way they would in a real one.
 */
public class RecordingRenderer(
    viewport: Size = Size(DEFAULT_WIDTH, DEFAULT_HEIGHT),
    override val textMeasurer: TextMeasurer = FakeTextMeasurer(),
) : UiRenderer {

    private val recorded = ArrayList<DrawCommand>()

    private val clipStack = ArrayList<Rect>()
    private val transformStack = ArrayList<Transform>()
    private val opacityStack = ArrayList<Float>()

    override var frame: FrameInfo = FrameInfo(
        viewport = Rect.of(Vec2.Zero, viewport),
        deltaSeconds = 0f,
        frameIndex = 0,
    )
        private set

    /** Every command recorded since the last [beginFrame]. */
    public val commands: List<DrawCommand> get() = recorded

    /** Commands of one type, for concise assertions. */
    public inline fun <reified T : DrawCommand> commandsOfType(): List<T> =
        commands.filterIsInstance<T>()

    /** The effective clip rectangle, or null when nothing is clipped. */
    public val currentClip: Rect? get() = clipStack.lastOrNull()

    /** The composed transform of the current stack. */
    public val currentTransform: Transform
        get() = transformStack.fold(Transform.Identity) { accumulated, next -> accumulated.then(next) }

    /** The composed opacity of the current stack. */
    public val currentOpacity: Float
        get() = opacityStack.fold(1f) { accumulated, next -> accumulated * next }

    /** Clears the recording and advances the frame counter. */
    public fun beginFrame(deltaSeconds: Float = DEFAULT_DELTA) {
        check(clipStack.isEmpty()) { "Previous frame left ${clipStack.size} clip(s) unpopped" }
        check(transformStack.isEmpty()) { "Previous frame left ${transformStack.size} transform(s) unpopped" }
        check(opacityStack.isEmpty()) { "Previous frame left ${opacityStack.size} opacity level(s) unpopped" }

        recorded.clear()
        frame = frame.copy(deltaSeconds = deltaSeconds, frameIndex = frame.frameIndex + 1)
    }

    /** Asserts the frame left every stack balanced. */
    public fun endFrame() {
        check(clipStack.isEmpty()) { "Frame ${frame.frameIndex} left ${clipStack.size} clip(s) unpopped" }
        check(transformStack.isEmpty()) { "Frame ${frame.frameIndex} left ${transformStack.size} transform(s) unpopped" }
        check(opacityStack.isEmpty()) { "Frame ${frame.frameIndex} left ${opacityStack.size} opacity level(s) unpopped" }
    }

    /** Resizes the viewport, as a resolution change would. */
    public fun resize(size: Size) {
        frame = frame.copy(viewport = Rect.of(Vec2.Zero, size))
    }

    /** Sets the reported GUI scale, as a Minecraft scale change would. */
    public fun setGuiScale(scale: Float) {
        frame = frame.copy(guiScale = scale)
    }

    // -- UiRenderer ---------------------------------------------------------

    override fun fillRect(bounds: Rect, color: Color) {
        record(DrawCommand.FillRect(bounds, color))
    }

    override fun roundedRect(bounds: Rect, radius: Dp, color: Color) {
        record(DrawCommand.RoundedRect(bounds, radius, color))
    }

    override fun border(bounds: Rect, radius: Dp, width: Dp, color: Color) {
        record(DrawCommand.Border(bounds, radius, width, color))
    }

    override fun gradient(bounds: Rect, gradient: Gradient, radius: Dp) {
        record(DrawCommand.GradientFill(bounds, gradient, radius))
    }

    override fun shadow(bounds: Rect, radius: Dp, shadow: Shadow) {
        record(DrawCommand.ShadowCast(bounds, radius, shadow))
    }

    override fun blur(bounds: Rect, radius: Dp, strength: Float) {
        record(DrawCommand.Blur(bounds, radius, strength))
    }

    override fun text(layout: TextLayout, position: Vec2, color: Color) {
        val content = layout.lines.joinToString("\n") { it.content }
        record(DrawCommand.Text(content, position, color, layout.size))
    }

    override fun icon(icon: Icon, bounds: Rect, tint: Color) {
        record(DrawCommand.DrawIcon(icon, bounds, tint))
    }

    override fun image(texture: TextureRef, bounds: Rect, tint: Color) {
        record(DrawCommand.Image(texture, bounds, tint))
    }

    override fun item(item: ItemRef, bounds: Rect) {
        record(DrawCommand.DrawItem(item, bounds))
    }

    override fun pushClip(bounds: Rect) {
        // Nested clips intersect, exactly as a real scissor stack does.
        val effective = clipStack.lastOrNull()?.intersect(bounds) ?: bounds
        clipStack.add(effective)
        record(DrawCommand.PushClip(bounds))
    }

    override fun popClip() {
        check(clipStack.isNotEmpty()) { "popClip without a matching pushClip" }
        clipStack.removeAt(clipStack.size - 1)
        record(DrawCommand.PopClip)
    }

    override fun pushTransform(transform: Transform) {
        transformStack.add(transform)
        record(DrawCommand.PushTransform(transform))
    }

    override fun popTransform() {
        check(transformStack.isNotEmpty()) { "popTransform without a matching pushTransform" }
        transformStack.removeAt(transformStack.size - 1)
        record(DrawCommand.PopTransform)
    }

    override fun pushOpacity(opacity: Float) {
        opacityStack.add(opacity.coerceIn(0f, 1f))
        record(DrawCommand.PushOpacity(opacity))
    }

    override fun popOpacity() {
        check(opacityStack.isNotEmpty()) { "popOpacity without a matching pushOpacity" }
        opacityStack.removeAt(opacityStack.size - 1)
        record(DrawCommand.PopOpacity)
    }

    private fun record(command: DrawCommand) {
        recorded.add(command)
    }

    /** A readable dump of the frame, used for snapshot assertions and debugging. */
    public fun describe(): String = buildString {
        var indent = 0
        for (command in recorded) {
            if (command is DrawCommand.PopClip ||
                command is DrawCommand.PopTransform ||
                command is DrawCommand.PopOpacity
            ) {
                indent--
            }
            repeat(maxOf(0, indent)) { append("  ") }
            append(describe(command)).append('\n')
            if (command is DrawCommand.PushClip ||
                command is DrawCommand.PushTransform ||
                command is DrawCommand.PushOpacity
            ) {
                indent++
            }
        }
    }

    private fun describe(command: DrawCommand): String = when (command) {
        is DrawCommand.FillRect -> "fill ${command.bounds} ${command.color}"
        is DrawCommand.RoundedRect -> "rounded ${command.bounds} r=${command.radius} ${command.color}"
        is DrawCommand.Border -> "border ${command.bounds} r=${command.radius} w=${command.width} ${command.color}"
        is DrawCommand.GradientFill -> "gradient ${command.bounds}"
        is DrawCommand.ShadowCast -> "shadow ${command.bounds}"
        is DrawCommand.Blur -> "blur ${command.bounds} strength=${command.strength}"
        is DrawCommand.Text -> "text '${command.content}' at ${command.position} ${command.color}"
        is DrawCommand.DrawIcon -> "icon ${command.icon.id} ${command.bounds}"
        is DrawCommand.Image -> "image ${command.texture.id} ${command.bounds}"
        is DrawCommand.DrawItem -> "item ${command.item.id} ${command.bounds}"
        is DrawCommand.PushClip -> "pushClip ${command.bounds}"
        DrawCommand.PopClip -> "popClip"
        is DrawCommand.PushTransform -> "pushTransform ${command.transform}"
        DrawCommand.PopTransform -> "popTransform"
        is DrawCommand.PushOpacity -> "pushOpacity ${command.opacity}"
        DrawCommand.PopOpacity -> "popOpacity"
    }

    public companion object {
        public const val DEFAULT_WIDTH: Float = 1920f
        public const val DEFAULT_HEIGHT: Float = 1080f
        public const val DEFAULT_DELTA: Float = 1f / 60f
    }
}
