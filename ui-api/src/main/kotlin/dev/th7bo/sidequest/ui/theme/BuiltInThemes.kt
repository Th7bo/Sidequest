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
    public val ACCENT: Color = Color.parse("#8B5CF6")

    override val tokens: ThemeTokens = ThemeTokens(
        colors = ColorTokens(
            windowBackground = Color.parse("#E60B0D14"),
            panelBackground = Color.parse("#F211141F"),
            elevatedPanelBackground = Color.parse("#F5171B29"),
            hoverBackground = Color.parse("#14FFFFFF"),
            pressedBackground = Color.parse("#1FFFFFFF"),
            selectedBackground = Color.parse("#2E8B5CF6"),
            border = Color.parse("#332A2F42"),
            borderStrong = Color.parse("#663A4059"),
            focusRing = ACCENT,
            accent = ACCENT,
            accentHover = Color.parse("#9D74F8"),
            accentPressed = Color.parse("#7A47E0"),
            onAccent = Color.parse("#FFFFFF"),
            success = Color.parse("#34D399"),
            warning = Color.parse("#FBBF24"),
            error = Color.parse("#F87171"),
            textPrimary = Color.parse("#E8EAF2"),
            textSecondary = Color.parse("#9AA1B8"),
            textDisabled = Color.parse("#5A6076"),
            scrim = Color.parse("#99000000"),
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
