package dev.th7bo.sidequest.ui.core.icon

import dev.th7bo.sidequest.ui.extension.OwnedRegistry
import dev.th7bo.sidequest.ui.extension.Registration
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.rendering.TextureRef
import dev.th7bo.sidequest.ui.rendering.UiRenderer

/**
 * How an icon is drawn.
 *
 * Two kinds, because both are genuinely useful: a sprite reads better for anything
 * pictorial, while a painter needs no assets at all and scales to any size — which
 * matters when the same icon appears at 10 units in a row and 20 in a card header.
 */
public sealed interface IconSource {

    /** A sprite from a texture. The host resolves the reference. */
    public class Texture(public val texture: TextureRef) : IconSource

    /**
     * Drawn from primitives.
     *
     * The painter receives the exact bounds and tint, so it can adapt rather than being
     * scaled up from a fixed size.
     */
    public fun interface Painter : IconSource {
        public fun paint(renderer: UiRenderer, bounds: Rect, tint: Color)
    }
}

/**
 * Resolves icon ids to something drawable.
 *
 * A registry rather than a direct texture reference so that a resource pack, a theme or
 * a third-party module can substitute an icon without the component that draws it
 * knowing. Registrations are scope-owned, so an unloading module takes its icons with
 * it.
 *
 * A missing icon is never an error: it draws a neutral placeholder. Config screens
 * routinely reference icons that a stripped-down resource pack has not supplied, and a
 * crash there would be far worse than a blank square.
 */
public class IconRegistry {

    private val sources = OwnedRegistry<UiId, IconSource>("icon")

    /** Icons currently registered. */
    public val size: Int get() = sources.size

    public fun register(scope: RegistrationScope, id: UiId, source: IconSource): Registration =
        sources.register(scope, id, source)

    /** Convenience for a sprite. */
    public fun registerTexture(scope: RegistrationScope, id: UiId, texture: TextureRef): Registration =
        register(scope, id, IconSource.Texture(texture))

    /** Convenience for a procedural icon. */
    public fun registerPainter(
        scope: RegistrationScope,
        id: UiId,
        painter: IconSource.Painter,
    ): Registration = register(scope, id, painter)

    public operator fun get(id: UiId): IconSource? = sources[id]

    public operator fun contains(id: UiId): Boolean = id in sources

    /**
     * Draws [icon] into [bounds].
     *
     * @return false if the icon was unknown and a placeholder was drawn instead, so a
     * diagnostics view can report what is missing.
     */
    public fun draw(
        renderer: UiRenderer,
        icon: Icon,
        bounds: Rect,
        tint: Color = Color.White,
    ): Boolean {
        when (val source = sources[icon.id]) {
            is IconSource.Texture -> renderer.image(source.texture, bounds, tint)
            is IconSource.Painter -> source.paint(renderer, bounds, tint)
            null -> {
                drawPlaceholder(renderer, bounds, tint)
                return false
            }
        }
        return true
    }

    /**
     * A hollow square, deliberately unlike any real icon.
     *
     * Neutral rather than alarming: a missing icon is a cosmetic gap, unlike a missing
     * component renderer, which is a bug worth shouting about.
     */
    private fun drawPlaceholder(renderer: UiRenderer, bounds: Rect, tint: Color) {
        renderer.border(
            bounds,
            dev.th7bo.sidequest.ui.geometry.Dp(bounds.width * PLACEHOLDER_RADIUS_FRACTION),
            dev.th7bo.sidequest.ui.geometry.Dp(1f),
            tint.scaleAlpha(PLACEHOLDER_ALPHA),
        )
    }

    private companion object {
        const val PLACEHOLDER_ALPHA = 0.45f
        const val PLACEHOLDER_RADIUS_FRACTION = 0.2f
    }
}
