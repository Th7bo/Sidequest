package dev.th7bo.sidequest.ui.theme

import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Color

/**
 * The default dark theme: near-black navy surfaces, slightly lighter elevated cards,
 * thin soft-gray borders and a restrained purple accent.
 *
 * These values are the framework's design language expressed as tokens. Nothing else
 * in the codebase hard-codes a colour.
 */
public object DarkTheme : TokenTheme(UiId.of("sidequest", "theme.dark"), "Dark") {

    /** The accent the design language is built around. */
    public val ACCENT: Color = Color.parse("#A855F7")

    override val tokens: ThemeTokens = ThemeTokens(
        colors = ColorTokens(
            // Near-black with a violet cast rather than a neutral grey, so the accent looks like it belongs
            // to the surface instead of sitting on top of it.
            windowBackground = Color.parse("#F00A0912"),
            // The two panel levels are close together on purpose. A card that is obviously lighter than its
            // container turns a page into a stack of boxes; a card that is *barely* lighter reads as one
            // surface with depth, which is what the references do.
            panelBackground = Color.parse("#D9121020"),
            elevatedPanelBackground = Color.parse("#B31A1730"),
            hoverBackground = Color.parse("#12FFFFFF"),
            pressedBackground = Color.parse("#1AFFFFFF"),
            selectedBackground = Color.parse("#33A855F7"),
            // Barely there. Depth is the shadow's job — see `EffectTokens` — and a line around every
            // surface is exactly what a modern interface leaves out.
            border = Color.parse("#1AFFFFFF"),
            borderStrong = Color.parse("#38FFFFFF"),
            focusRing = ACCENT,
            accent = ACCENT,
            accentHover = Color.parse("#C084FC"),
            accentPressed = Color.parse("#9333EA"),
            onAccent = Color.parse("#FFFFFF"),
            success = Color.parse("#4ADE80"),
            warning = Color.parse("#FBBF24"),
            error = Color.parse("#FB7185"),
            textPrimary = Color.parse("#F2EEFA"),
            textSecondary = Color.parse("#8B85A8"),
            textDisabled = Color.parse("#544E6C"),
            scrim = Color.parse("#A6000000"),
        ),
    )
}

/** Light counterpart, same geometry and motion, inverted surfaces. */
public object LightTheme : TokenTheme(UiId.of("sidequest", "theme.light"), "Light") {

    override val tokens: ThemeTokens = ThemeTokens(
        colors = ColorTokens(
            windowBackground = Color.parse("#F2F5F6FA"),
            panelBackground = Color.parse("#FFFFFFFF"),
            elevatedPanelBackground = Color.parse("#FFFAFBFF"),
            hoverBackground = Color.parse("#0F000000"),
            pressedBackground = Color.parse("#1A000000"),
            selectedBackground = Color.parse("#248B5CF6"),
            border = Color.parse("#33202538"),
            borderStrong = Color.parse("#59202538"),
            focusRing = DarkTheme.ACCENT,
            accent = Color.parse("#7C3AED"),
            accentHover = Color.parse("#8B5CF6"),
            accentPressed = Color.parse("#6D28D9"),
            onAccent = Color.parse("#FFFFFF"),
            success = Color.parse("#059669"),
            warning = Color.parse("#B45309"),
            error = Color.parse("#DC2626"),
            textPrimary = Color.parse("#12141C"),
            textSecondary = Color.parse("#4A5068"),
            textDisabled = Color.parse("#9AA1B8"),
            scrim = Color.parse("#66000000"),
        ),
    )
}

/**
 * A high-contrast variant of [DarkTheme]: opaque surfaces, full-strength borders, no
 * blur, and text pushed to pure white.
 */
public object HighContrastDarkTheme :
    TokenTheme(UiId.of("sidequest", "theme.high_contrast_dark"), "High Contrast Dark") {

    override val tokens: ThemeTokens = DarkTheme.tokens.copy(
        colors = DarkTheme.tokens.colors.copy(
            windowBackground = Color.parse("#FF000000"),
            panelBackground = Color.parse("#FF07090F"),
            elevatedPanelBackground = Color.parse("#FF10131E"),
            hoverBackground = Color.parse("#38FFFFFF"),
            pressedBackground = Color.parse("#4DFFFFFF"),
            border = Color.parse("#CCB9C0D8"),
            borderStrong = Color.parse("#FFFFFFFF"),
            textPrimary = Color.parse("#FFFFFF"),
            textSecondary = Color.parse("#D6DAE8"),
            textDisabled = Color.parse("#8C93A8"),
        ),
        effects = DarkTheme.tokens.effects.copy(blurStrength = 0f),
        accessibility = AccessibilityTokens(highContrast = true),
    )
}
