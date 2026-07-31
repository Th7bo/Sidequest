package dev.th7bo.sidequest.ui.testkit

import dev.th7bo.sidequest.ui.geometry.Dp
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.Gradient
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.rendering.ItemRef
import dev.th7bo.sidequest.ui.rendering.Shadow
import dev.th7bo.sidequest.ui.rendering.TextureRef
import dev.th7bo.sidequest.ui.rendering.Transform

/**
 * One recorded drawing operation.
 *
 * Tests assert on these rather than on pixels, so a rendering test states what the
 * framework *asked* for — which is the thing the framework is actually responsible for.
 */
public sealed class DrawCommand {

    public data class FillRect(val bounds: Rect, val color: Color) : DrawCommand()

    public data class RoundedRect(val bounds: Rect, val radius: Dp, val color: Color) : DrawCommand()

    public data class Border(
        val bounds: Rect,
        val radius: Dp,
        val width: Dp,
        val color: Color,
    ) : DrawCommand()

    public data class GradientFill(
        val bounds: Rect,
        val gradient: Gradient,
        val radius: Dp,
    ) : DrawCommand()

    public data class ShadowCast(val bounds: Rect, val radius: Dp, val shadow: Shadow) : DrawCommand()

    public data class Blur(val bounds: Rect, val radius: Dp, val strength: Float) : DrawCommand()

    public data class Text(
        val content: String,
        val position: Vec2,
        val color: Color,
        val size: dev.th7bo.sidequest.ui.geometry.Size,
    ) : DrawCommand()

    public data class DrawIcon(val icon: Icon, val bounds: Rect, val tint: Color) : DrawCommand()

    public data class Image(val texture: TextureRef, val bounds: Rect, val tint: Color) : DrawCommand()

    public data class DrawItem(val item: ItemRef, val bounds: Rect) : DrawCommand()

    public data class PushClip(val bounds: Rect) : DrawCommand()

    public data object PopClip : DrawCommand()

    public data class PushTransform(val transform: Transform) : DrawCommand()

    public data object PopTransform : DrawCommand()

    public data class PushOpacity(val opacity: Float) : DrawCommand()

    public data object PopOpacity : DrawCommand()
}
