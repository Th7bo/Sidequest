package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.geometry.Dp
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.rendering.Corners
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.theme.Theme

/**
 * Where the pieces of the profile window go.
 *
 * Split out from the screen that draws it so that it can be tested and looked at without a running game.
 * The screen is the only thing that can hold a browser, and a browser is the one thing that cannot exist in
 * a test — so everything *except* the browser lives here, and what is left over there is input plumbing.
 *
 * Coordinates are logical units throughout; nothing here knows about GUI scale or framebuffers.
 */
public data class ProfileWindowLayout(
    /** The frame, inset from the screen unless maximised. */
    public val window: Rect,
    /** The chrome strip along the top. */
    public val bar: Rect,
    /** Where the page goes. */
    public val content: Rect,
    public val search: Rect,
    /** Steps back through the quick-switch list. Zero-width when there is nobody to step to. */
    public val previous: Rect,
    public val next: Rect,
    public val expand: Rect,
    public val close: Rect,
    public val isMaximised: Boolean,
) {
    public companion object {

        /**
         * Lays the window out inside [viewport].
         *
         * **The content is inset from the frame's rounded edge by more than a hairline, deliberately.** The
         * page is a rectangle with square corners, so a content area flush with the frame would poke its
         * corners out through the rounding. Clearing an arc of radius `r` needs an inset of at least
         * `r(1 - 1/√2)` — about three tenths of it — and [CONTENT_INSET] is comfortably past that for every
         * radius the themes use.
         */
        public fun of(
            viewport: Rect,
            isMaximised: Boolean,
            /** Whether the quick-switch arrows have anywhere to go. They are not drawn when they do not. */
            hasQuickSwitch: Boolean = false,
        ): ProfileWindowLayout {
            val margin = if (isMaximised) 0f else MARGIN
            val window = Rect(
                viewport.x + margin,
                viewport.y + margin,
                (viewport.width - margin * 2).coerceAtLeast(MIN_WINDOW),
                (viewport.height - margin * 2).coerceAtLeast(MIN_WINDOW),
            )
            val bar = Rect(window.x, window.y, window.width, BAR_HEIGHT)

            val inset = if (isMaximised) 0f else CONTENT_INSET
            val content = Rect(
                window.x + inset,
                window.y + BAR_HEIGHT,
                (window.width - inset * 2).coerceAtLeast(1f),
                (window.height - BAR_HEIGHT - inset).coerceAtLeast(1f),
            )

            val buttonTop = bar.y + (BAR_HEIGHT - BUTTON) / 2f
            val close = Rect(bar.right - PAD - BUTTON, buttonTop, BUTTON, BUTTON)
            val expand = Rect(close.x - GAP - BUTTON, buttonTop, BUTTON, BUTTON)

            val searchWidth = SEARCH_WIDTH.coerceAtMost(bar.width * SEARCH_MAX_FRACTION)
            val search = Rect(
                expand.x - GAP - searchWidth,
                bar.y + (BAR_HEIGHT - FIELD_HEIGHT) / 2f,
                searchWidth,
                FIELD_HEIGHT,
            )

            // Zero-width rather than absent when there is nobody to switch to, so the rest of the bar does
            // not shift the moment somebody adds their first friend.
            // Laid out leftwards from the search box: `‹ ›` sit together just before it.
            val arrowWidth = if (hasQuickSwitch) BUTTON else 0f
            val next = Rect(search.x - GAP - arrowWidth, buttonTop, arrowWidth, BUTTON)
            val previous = Rect(next.x - arrowWidth, buttonTop, arrowWidth, BUTTON)

            return ProfileWindowLayout(
                window,
                bar,
                content,
                search,
                previous,
                next,
                expand,
                close,
                isMaximised,
            )
        }

        /** How much screen is left around the window. */
        public const val MARGIN: Float = 18f

        /** How far the page sits inside the frame. See [of]. */
        public const val CONTENT_INSET: Float = 6f

        public const val BAR_HEIGHT: Float = 22f
        public const val PAD: Float = 8f
        public const val GAP: Float = 6f
        public const val BUTTON: Float = 14f
        public const val FIELD_HEIGHT: Float = 14f
        public const val SEARCH_WIDTH: Float = 150f

        /** The search box never takes more than this much of the bar, so the title always has room. */
        public const val SEARCH_MAX_FRACTION: Float = 0.45f

        private const val MIN_WINDOW = 1f
    }
}

/** What the chrome is showing, as one value so the painter takes no flags. */
public data class ProfileWindowState(
    public val searchText: String = "",
    public val isSearchFocused: Boolean = false,
    public val isCaretVisible: Boolean = false,
    /** Where the cursor is, for hover states, or null when it is elsewhere. */
    public val pointer: Vec2? = null,
    public val title: String = "SkyBlock Profiles",
    /** Somebody else's site and somebody else's work. Not optional. */
    public val credit: String = "powered by SkyCrypt",
    public val placeholder: String = "Search a player",
)

/**
 * Draws the window around the page.
 *
 * Everything except the page itself, which is a GPU texture only the game can supply — so this paints the
 * frame, leaves a hole, and the screen blits into it.
 */
public object ProfileWindowChrome {

    /** The frame: a shadow, a panel, the bar and the seam between them. */
    public fun paintFrame(renderer: UiRenderer, theme: Theme, layout: ProfileWindowLayout) {
        val radius = if (layout.isMaximised) Dp.Zero else theme.tokens.radii.large
        if (!layout.isMaximised) {
            renderer.shadow(layout.window, radius, theme.tokens.effects.panelShadow)
        }
        renderer.roundedRect(layout.window, Corners.all(radius), theme.tokens.colors.panelBackground)
        renderer.roundedRect(layout.bar, Corners.top(radius), theme.tokens.colors.elevatedPanelBackground)
        // A seam under the bar rather than a border around everything: the frame reads as one surface, and
        // only the line that separates chrome from content is drawn.
        renderer.fillRect(
            Rect(layout.window.x, layout.bar.bottom, layout.window.width, HAIRLINE),
            theme.tokens.colors.border,
        )
    }

    /** A flat fill where the page goes, for the frames before the browser has painted anything. */
    public fun paintPagePlaceholder(renderer: UiRenderer, theme: Theme, layout: ProfileWindowLayout) {
        renderer.fillRect(layout.content, theme.tokens.colors.windowBackground)
    }

    /** The title, the attribution, the search box and the two buttons. */
    public fun paintBar(
        renderer: UiRenderer,
        theme: Theme,
        layout: ProfileWindowLayout,
        state: ProfileWindowState,
    ) {
        val measurer = renderer.textMeasurer

        val title = measurer.measure(state.title, theme.textStyle(TextRole.TITLE))
        renderer.text(
            title,
            Vec2(layout.bar.x + ProfileWindowLayout.PAD, centred(layout.bar, title.size.height)),
            theme.textColor(TextRole.TITLE),
        )

        val credit = measurer.measure(state.credit, theme.textStyle(TextRole.CAPTION))
        val creditLeft = layout.bar.x + ProfileWindowLayout.PAD + title.size.width + ProfileWindowLayout.GAP
        // Dropped rather than overlapped when the bar is narrow. The attribution is the least important
        // thing here and the first that should go.
        if (creditLeft + credit.size.width < layout.search.x - ProfileWindowLayout.GAP) {
            renderer.text(
                credit,
                Vec2(creditLeft, centred(layout.bar, credit.size.height)),
                theme.textColor(TextRole.CAPTION),
            )
        }

        paintArrows(renderer, theme, layout, state.pointer)
        paintSearch(renderer, theme, layout, state)
        paintExpand(renderer, theme, layout, state.pointer)
        paintClose(renderer, theme, layout, state.pointer)
    }

    /**
     * The quick-switch arrows.
     *
     * Drawn as text, because `‹` and `›` are ordinary punctuation that any text face carries — unlike the
     * window-control glyphs, which is why the expand button next to them is drawn from primitives instead.
     */
    private fun paintArrows(renderer: UiRenderer, theme: Theme, layout: ProfileWindowLayout, pointer: Vec2?) {
        if (layout.previous.width <= 0f) return
        paintGlyph(renderer, theme, layout.previous, PREVIOUS_GLYPH, pointer)
        paintGlyph(renderer, theme, layout.next, NEXT_GLYPH, pointer)
    }

    private fun paintGlyph(
        renderer: UiRenderer,
        theme: Theme,
        bounds: Rect,
        glyph: String,
        pointer: Vec2?,
    ) {
        hover(renderer, theme, bounds, pointer)
        val layout = renderer.textMeasurer.measure(glyph, theme.textStyle(TextRole.BODY))
        renderer.text(
            layout,
            Vec2(
                bounds.x + (bounds.width - layout.size.width) / 2f,
                centred(bounds, layout.size.height),
            ),
            theme.textColor(TextRole.SECONDARY),
        )
    }

    private fun paintSearch(
        renderer: UiRenderer,
        theme: Theme,
        layout: ProfileWindowLayout,
        state: ProfileWindowState,
    ) {
        renderer.roundedRect(layout.search, theme.tokens.radii.pill, theme.tokens.colors.windowBackground)
        if (state.isSearchFocused) {
            renderer.border(
                layout.search,
                theme.tokens.radii.pill,
                theme.tokens.metrics.focusRingWidth,
                theme.tokens.colors.accent,
            )
        }

        val isEmpty = state.searchText.isEmpty()
        val role = if (isEmpty) TextRole.CAPTION else TextRole.BODY
        val layoutText = renderer.textMeasurer.measure(
            if (isEmpty) state.placeholder else state.searchText,
            theme.textStyle(role),
            layout.search.width - ProfileWindowLayout.PAD * 2,
        )
        val textLeft = layout.search.x + ProfileWindowLayout.PAD
        val textTop = centred(layout.search, layoutText.size.height)
        renderer.text(layoutText, Vec2(textLeft, textTop), theme.textColor(role))

        if (state.isSearchFocused && state.isCaretVisible) {
            val caretLeft = textLeft + if (isEmpty) 0f else layoutText.size.width + 1f
            renderer.fillRect(
                Rect(caretLeft, textTop, HAIRLINE, layoutText.size.height),
                theme.tokens.colors.accent,
            )
        }
    }

    /**
     * The expand button, as an outlined square.
     *
     * Drawn from primitives rather than set as a glyph, because the shipped font is a text face and the
     * window-control characters are exactly the sort of thing it does not carry.
     *
     * **The radius is a literal and not `radii.small`.** The theme's small radius is five units, which on an
     * eight-unit box is most of the way to a circle — the first render of this came out as a ring and read
     * as a radio button. A corner radius that is a fixed fraction of the shape has to be written as one.
     */
    private fun paintExpand(renderer: UiRenderer, theme: Theme, layout: ProfileWindowLayout, pointer: Vec2?) {
        hover(renderer, theme, layout.expand, pointer)
        val inner = Rect(
            layout.expand.x + ICON_INSET,
            layout.expand.y + ICON_INSET,
            layout.expand.width - ICON_INSET * 2,
            layout.expand.height - ICON_INSET * 2,
        )
        renderer.border(inner, Dp(ICON_RADIUS), Dp(HAIRLINE), theme.textColor(TextRole.SECONDARY))
    }

    private fun paintClose(renderer: UiRenderer, theme: Theme, layout: ProfileWindowLayout, pointer: Vec2?) {
        hover(renderer, theme, layout.close, pointer)
        val glyph = renderer.textMeasurer.measure(CLOSE_GLYPH, theme.textStyle(TextRole.BODY))
        renderer.text(
            glyph,
            Vec2(
                layout.close.x + (layout.close.width - glyph.size.width) / 2f,
                centred(layout.close, glyph.size.height),
            ),
            theme.textColor(TextRole.SECONDARY),
        )
    }

    private fun hover(renderer: UiRenderer, theme: Theme, bounds: Rect, pointer: Vec2?) {
        if (pointer != null && bounds.contains(pointer)) {
            renderer.roundedRect(bounds, theme.tokens.radii.small, theme.tokens.colors.hoverBackground)
        }
    }

    /** A message along the bottom of the page, fading. */
    public fun paintNotice(
        renderer: UiRenderer,
        theme: Theme,
        layout: ProfileWindowLayout,
        message: String,
        opacity: Float,
    ) {
        val text = renderer.textMeasurer.measure(message, theme.textStyle(TextRole.SECONDARY))
        val pillWidth = text.size.width + ProfileWindowLayout.PAD * 2
        val pillHeight = text.size.height + theme.tokens.spacing.medium.value * 2
        val left = layout.window.x + (layout.window.width - pillWidth) / 2f
        val top = layout.content.bottom - pillHeight - theme.tokens.spacing.xl.value

        renderer.roundedRect(
            Rect(left, top, pillWidth, pillHeight),
            theme.tokens.radii.pill,
            theme.tokens.colors.elevatedPanelBackground.withAlpha(opacity),
        )
        renderer.text(
            text,
            Vec2(left + ProfileWindowLayout.PAD, top + theme.tokens.spacing.medium.value),
            theme.textColor(TextRole.SECONDARY).withAlpha(opacity),
        )
    }

    private fun centred(within: Rect, height: Float): Float = within.y + (within.height - height) / 2f

    /** Multiplication sign rather than a letter x. */
    public const val CLOSE_GLYPH: String = "×"

    /** Single angle quotes: punctuation, so any text face has them. */
    public const val PREVIOUS_GLYPH: String = "‹"
    public const val NEXT_GLYPH: String = "›"

    private const val HAIRLINE = 1f
    private const val ICON_INSET = 3f

    /** Barely rounded. See [paintExpand] — a themed radius on a shape this small is a circle. */
    private const val ICON_RADIUS = 1.5f
}
