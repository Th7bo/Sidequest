package dev.th7bo.sidequest.ui.theme

import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Color

/**
 * The default dark theme: an opaque ink-blue shell, quiet elevated cards and a warm
 * rose accent. The palette is deliberately low-noise so content, not chrome, carries
 * the hierarchy.
 *
 * These values are the framework's design language expressed as tokens. Nothing else
 * in the codebase hard-codes a colour.
 */
public object DarkTheme : TokenTheme(UiId.of("sidequest", "theme.dark"), "Dark") {

    /** The accent the design language is built around. */
    public val ACCENT: Color = Color.parse("#F07FB2")

    override val tokens: ThemeTokens = ThemeTokens(
        colors = ColorTokens(
            // Opaque surfaces keep the interface stable over bright skies, inventories and particle-heavy
            // scenes. A faint blue cast prevents the black from feeling flat or muddy.
            windowBackground = Color.parse("#FC080B12"),
            panelBackground = Color.parse("#FF0C111C"),
            elevatedPanelBackground = Color.parse("#FF141C2B"),
            hoverBackground = Color.parse("#0FFFFFFF"),
            pressedBackground = Color.parse("#18FFFFFF"),
            selectedBackground = Color.parse("#26F07FB2"),
            border = Color.parse("#14C8D2E8"),
            borderStrong = Color.parse("#30C8D2E8"),
            focusRing = ACCENT,
            accent = ACCENT,
            accentHover = Color.parse("#F59AC2"),
            accentPressed = Color.parse("#D9689B"),
            onAccent = Color.parse("#FFFBFD"),
            success = Color.parse("#45D483"),
            warning = Color.parse("#F4C15D"),
            error = Color.parse("#F06C75"),
            textPrimary = Color.parse("#F4F6FB"),
            textSecondary = Color.parse("#98A2B8"),
            textDisabled = Color.parse("#586176"),
            scrim = Color.parse("#B3000000"),
        ),
        effects = EffectTokens(
            panelShadow = dev.th7bo.sidequest.ui.rendering.Shadow(Color.parse("#8A000000"), blurRadius = 18f),
            overlayShadow = dev.th7bo.sidequest.ui.rendering.Shadow(Color.parse("#B3000000"), blurRadius = 26f),
            blurStrength = 0.35f,
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
            selectedBackground = Color.parse("#24F07FB2"),
            border = Color.parse("#33202538"),
            borderStrong = Color.parse("#59202538"),
            focusRing = DarkTheme.ACCENT,
            accent = Color.parse("#DE679D"),
            accentHover = Color.parse("#EA7FB0"),
            accentPressed = Color.parse("#C65389"),
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
