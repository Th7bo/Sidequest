package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.core.icon.IconRegistry
import dev.th7bo.sidequest.ui.core.icon.IconSource
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.geometry.Dp
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.rendering.UiRenderer

/**
 * Icons drawn from shapes rather than loaded from sprites.
 *
 * **A sprite is pixel art by definition.** A Minecraft item texture is sixteen pixels square, so at any size
 * an interface wants it either blurs or blocks — and next to corners that are now smooth to the display
 * pixel, it is the one thing left that still looks like a texture. These are drawn with the same primitives
 * as everything else, which means they are resolution-independent and get the same anti-aliasing for free.
 *
 * Deliberately abstract. Circles, bars, dots and rings, not little pictures of things: a recognisable bell or
 * gear cannot be built from rounded rectangles without looking like an attempt at one, and the interfaces
 * this is aiming at label with words and use the glyph as a quiet marker anyway. What each one has to do is
 * be *distinguishable at twelve pixels*, which a simple shape does and a detailed one does not.
 *
 * The ids match the framework's own `sidequest:icon.*` namespace, so registering these replaces whatever a
 * host had there without any component knowing.
 */
public object GlyphIcons {

    // -- the vocabulary -------------------------------------------------------

    /** A filled disc. */
    public val dot: IconSource.Painter = IconSource.Painter { renderer, bounds, tint ->
        disc(renderer, bounds.scaled(DOT_SIZE), tint)
    }

    /** A ring: the outline of a disc. */
    public val ring: IconSource.Painter = IconSource.Painter { renderer, bounds, tint ->
        val inner = bounds.scaled(RING_SIZE)
        renderer.border(inner, Dp(inner.width / 2f), Dp(stroke(bounds)), tint)
    }

    /** A ring with a dot in it — the closest a shape vocabulary gets to "settings". */
    public val target: IconSource.Painter = IconSource.Painter { renderer, bounds, tint ->
        val outer = bounds.scaled(RING_SIZE)
        renderer.border(outer, Dp(outer.width / 2f), Dp(stroke(bounds)), tint)
        disc(renderer, bounds.scaled(TARGET_CORE), tint)
    }

    /** Three stacked bars. A list, a menu, a set of features. */
    public val bars: IconSource.Painter = IconSource.Painter { renderer, bounds, tint ->
        stack(renderer, bounds, tint, count = BAR_COUNT) { index, row ->
            // Slightly ragged lengths, so it reads as a list of things rather than as a hamburger button.
            val width = row.width * if (index == 1) SHORT_BAR else 1f
            Rect(row.x, row.y, width, row.height)
        }
    }

    /** Two bars with a knob on each — sliders, and by extension anything adjustable. */
    public val sliders: IconSource.Painter = IconSource.Painter { renderer, bounds, tint ->
        stack(renderer, bounds, tint, count = SLIDER_COUNT) { _, row -> row }
        val inner = bounds.scaled(CONTENT_SIZE)
        val knob = inner.height / SLIDER_COUNT * KNOB_FRACTION
        for (index in 0 until SLIDER_COUNT) {
            val centreY = inner.y + inner.height * (index * 2 + 1) / (SLIDER_COUNT * 2f)
            val centreX = inner.x + inner.width * if (index == 0) FIRST_KNOB else SECOND_KNOB
            disc(renderer, Rect(centreX - knob, centreY - knob, knob * 2, knob * 2), tint)
        }
    }

    /** A rounded square outline. A panel, a screen, an area. */
    public val frame: IconSource.Painter = IconSource.Painter { renderer, bounds, tint ->
        val inner = bounds.scaled(CONTENT_SIZE)
        renderer.border(inner, Dp(inner.width * FRAME_RADIUS), Dp(stroke(bounds)), tint)
    }

    /** A filled rounded square. The solid counterpart to [frame]. */
    public val block: IconSource.Painter = IconSource.Painter { renderer, bounds, tint ->
        val inner = bounds.scaled(CONTENT_SIZE)
        renderer.roundedRect(inner, Dp(inner.width * FRAME_RADIUS), tint)
    }

    /** A plus. Adding something. */
    public val plus: IconSource.Painter = IconSource.Painter { renderer, bounds, tint ->
        val inner = bounds.scaled(CONTENT_SIZE)
        val thickness = stroke(bounds)
        val radius = Dp(thickness / 2f)
        renderer.roundedRect(
            Rect(inner.x, inner.centerY - thickness / 2f, inner.width, thickness),
            radius,
            tint,
        )
        renderer.roundedRect(
            Rect(inner.centerX - thickness / 2f, inner.y, thickness, inner.height),
            radius,
            tint,
        )
    }

    /** A single bar. A divider, a minus, "none". */
    public val dash: IconSource.Painter = IconSource.Painter { renderer, bounds, tint ->
        val inner = bounds.scaled(CONTENT_SIZE)
        val thickness = stroke(bounds)
        renderer.roundedRect(
            Rect(inner.x, inner.centerY - thickness / 2f, inner.width, thickness),
            Dp(thickness / 2f),
            tint,
        )
    }

    /** Two overlapping discs. People, a group, a friend. */
    public val people: IconSource.Painter = IconSource.Painter { renderer, bounds, tint ->
        val size = bounds.width * PERSON_SIZE
        disc(renderer, Rect(bounds.x, bounds.centerY - size / 2f, size, size), tint)
        disc(
            renderer,
            Rect(bounds.right - size, bounds.centerY - size / 2f, size, size),
            tint.scaleAlpha(PERSON_BACK_ALPHA),
        )
    }

    // -- drawing helpers ------------------------------------------------------

    /** A circle, as a rounded rectangle whose radius is half its side. */
    private fun disc(renderer: UiRenderer, bounds: Rect, tint: Color) {
        renderer.roundedRect(bounds, Dp(bounds.width / 2f), tint)
    }

    private inline fun stack(
        renderer: UiRenderer,
        bounds: Rect,
        tint: Color,
        count: Int,
        shape: (Int, Rect) -> Rect,
    ) {
        val inner = bounds.scaled(CONTENT_SIZE)
        val thickness = stroke(bounds)
        val step = inner.height / count
        for (index in 0 until count) {
            val row = Rect(inner.x, inner.y + step * index + (step - thickness) / 2f, inner.width, thickness)
            renderer.roundedRect(shape(index, row), Dp(thickness / 2f), tint)
        }
    }

    /**
     * How thick a line in a glyph should be.
     *
     * A fraction of the icon rather than a fixed number, so a glyph drawn large is not a hairline drawing of
     * itself. Floored at one *logical* unit — below that a stroke can vanish entirely at low GUI scales,
     * where the anti-aliasing has nothing to work with.
     */
    private fun stroke(bounds: Rect): Float = (bounds.width * STROKE_FRACTION).coerceAtLeast(1f)

    /** A concentric box at [fraction] of the size, which is how every glyph gets its padding. */
    private fun Rect.scaled(fraction: Float): Rect {
        val width = this.width * fraction
        val height = this.height * fraction
        return Rect(centerX - width / 2f, centerY - height / 2f, width, height)
    }

    private val Rect.centerX: Float get() = x + width / 2f
    private val Rect.centerY: Float get() = y + height / 2f

    // -- registration ---------------------------------------------------------

    /** Every glyph, against the id a component would ask for. */
    public val all: Map<String, IconSource.Painter> = mapOf(
        "icon.settings" to target,
        "icon.features" to bars,
        "icon.network" to ring,
        "icon.tools" to sliders,
        "icon.appearance" to block,
        "icon.quiet" to dash,
        "icon.notifications" to dot,
        "icon.sound" to ring,
        "icon.cinematics" to frame,
        "icon.rare_drop" to dot,
        "icon.cosmetics" to block,
        "icon.playtime" to ring,
        "icon.title_screen" to frame,
        "icon.levels" to bars,
        "icon.garden" to dot,
        "icon.waypoint" to target,
        "icon.friend" to people,
        "icon.online" to dot,
        "icon.debt" to ring,
        "icon.check" to dot,
        "icon.plus" to plus,
    )

    private const val DOT_SIZE = 0.42f
    private const val RING_SIZE = 0.68f
    private const val TARGET_CORE = 0.22f
    private const val CONTENT_SIZE = 0.72f
    private const val FRAME_RADIUS = 0.26f
    private const val STROKE_FRACTION = 0.11f
    private const val BAR_COUNT = 3
    private const val SHORT_BAR = 0.62f
    private const val SLIDER_COUNT = 2
    private const val KNOB_FRACTION = 0.36f
    private const val FIRST_KNOB = 0.32f
    private const val SECOND_KNOB = 0.68f
    private const val PERSON_SIZE = 0.56f
    private const val PERSON_BACK_ALPHA = 0.55f
}

/**
 * Registers the glyph set.
 *
 * Owned by [scope], so a host that wants different art disposes this and registers its own against the same
 * ids — which is the whole reason icons resolve through a registry.
 */
public fun IconRegistry.registerGlyphIcons(scope: RegistrationScope, namespace: String = "sidequest") {
    for ((path, painter) in GlyphIcons.all) {
        registerPainter(scope, UiId.of(namespace, path), painter)
    }
}

/** The ids this set covers, for a host wiring a screen to them. */
public object GlyphIconIds {
    public fun of(path: String): Icon = Icon(UiId.of("sidequest", path))

    public val settings: Icon = of("icon.settings")
    public val features: Icon = of("icon.features")
    public val network: Icon = of("icon.network")
    public val tools: Icon = of("icon.tools")
    public val appearance: Icon = of("icon.appearance")
    public val quiet: Icon = of("icon.quiet")
    public val notifications: Icon = of("icon.notifications")
    public val sound: Icon = of("icon.sound")
    public val cinematics: Icon = of("icon.cinematics")
    public val rareDrop: Icon = of("icon.rare_drop")
    public val cosmetics: Icon = of("icon.cosmetics")
    public val playtime: Icon = of("icon.playtime")
    public val titleScreen: Icon = of("icon.title_screen")
    public val levels: Icon = of("icon.levels")
    public val garden: Icon = of("icon.garden")
    public val waypoint: Icon = of("icon.waypoint")
    public val friend: Icon = of("icon.friend")
    public val online: Icon = of("icon.online")
    public val debt: Icon = of("icon.debt")
    public val plus: Icon = of("icon.plus")

    // Plural spellings, because the screens that ask for these were written against a texture set that used
    // them. An alias costs nothing and saves touching forty call sites to rename a field.
    public val waypoints: Icon = waypoint
    public val friends: Icon = friend
    public val debts: Icon = debt
    public val settled: Icon = of("icon.check")
}
