package dev.th7bo.sidequest.ui.theme

import dev.th7bo.sidequest.ui.geometry.Dp
import dev.th7bo.sidequest.ui.geometry.dp
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.Shadow
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.TextStyle

/**
 * A named set of design tokens.
 *
 * Components resolve every colour, radius, spacing value, type style and duration
 * through a theme. Hard-coding any of them inside a component is a bug: it breaks
 * theme switching, high contrast and reduced motion all at once.
 */
public interface Theme {

    public val id: UiId

    /** Shown in the theme picker. */
    public val displayName: String

    public val tokens: ThemeTokens

    /** Resolves a semantic [TextRole] into a concrete style. */
    public fun textStyle(role: TextRole): TextStyle

    /** The colour text of [role] should be drawn in. */
    public fun textColor(role: TextRole): Color
}

/**
 * Every token a component may read.
 *
 * Grouped rather than flat so that a custom theme can override one facet — say, only
 * the palette — without restating spacing and motion.
 */
public data class ThemeTokens(
    val colors: ColorTokens,
    val radii: RadiusTokens = RadiusTokens(),
    val spacing: SpacingScale = SpacingScale(),
    val typography: TypographyScale = TypographyScale(),
    val motion: MotionTokens = MotionTokens(),
    val metrics: MetricTokens = MetricTokens(),
    val effects: EffectTokens = EffectTokens(),
    val accessibility: AccessibilityTokens = AccessibilityTokens(),
)

/**
 * The palette.
 *
 * Note that state is never communicated by colour alone anywhere in the framework —
 * every coloured state also carries an icon, a border, or a text label. These tokens
 * make the visual layer of that pairing themeable, not a substitute for it.
 */
public data class ColorTokens(
    val windowBackground: Color,
    val panelBackground: Color,
    val elevatedPanelBackground: Color,
    val hoverBackground: Color,
    val pressedBackground: Color,
    val selectedBackground: Color,
    val border: Color,
    val borderStrong: Color,
    val focusRing: Color,
    val accent: Color,
    val accentHover: Color,
    val accentPressed: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val scrim: Color,
)

public data class RadiusTokens(
    val none: Dp = Dp.Zero,
    val small: Dp = 4.dp,
    val medium: Dp = 6.dp,
    val large: Dp = 10.dp,
    val pill: Dp = 999.dp,
)

/** A 4-unit spacing scale. Components use these, never raw numbers. */
public data class SpacingScale(
    val none: Dp = Dp.Zero,
    val xs: Dp = 2.dp,
    val small: Dp = 4.dp,
    val medium: Dp = 8.dp,
    val large: Dp = 12.dp,
    val xl: Dp = 16.dp,
    val xxl: Dp = 24.dp,
)

public data class TypographyScale(
    val title: TextStyle = TextStyle(scale = 1.15f, bold = true),
    val label: TextStyle = TextStyle(scale = 1f),
    val body: TextStyle = TextStyle(scale = 1f),
    val secondary: TextStyle = TextStyle(scale = 1f),
    val caption: TextStyle = TextStyle(scale = 0.85f),
    val mono: TextStyle = TextStyle(scale = 0.9f),
)

/** Animation durations in seconds. All motion is delta-time driven, never frame counted. */
public data class MotionTokens(
    val instant: Float = 0f,
    val fast: Float = 0.08f,
    val normal: Float = 0.15f,
    val slow: Float = 0.25f,
    val deliberate: Float = 0.4f,
)

/** Fixed sizes that keep controls consistent across screens. */
public data class MetricTokens(
    val controlHeight: Dp = 22.dp,
    val compactControlHeight: Dp = 18.dp,
    val borderWidth: Dp = 1.dp,
    val focusRingWidth: Dp = 1.dp,
    val sidebarWidth: Dp = 148.dp,
    val inspectorWidth: Dp = 190.dp,
    val scrollbarWidth: Dp = 4.dp,
    val iconSize: Dp = 12.dp,
)

public data class EffectTokens(
    val panelShadow: Shadow = Shadow(Color(0x40000000), blurRadius = 10f),
    val overlayShadow: Shadow = Shadow(Color(0x59000000), blurRadius = 18f),
    /** `0` disables blur entirely, which is the correct setting on weak hardware. */
    val blurStrength: Float = 0.35f,
)

/**
 * Accessibility switches. Components must honour these rather than assuming defaults.
 *
 * [reducedMotion] is checked by the animation system, which snaps to targets instead of
 * interpolating. [textScale] multiplies every type style, so layouts must not assume a
 * fixed text height.
 */
public data class AccessibilityTokens(
    val highContrast: Boolean = false,
    val reducedMotion: Boolean = false,
    val textScale: Float = 1f,
)

/**
 * Base class handling role resolution so concrete themes only supply tokens.
 */
public abstract class TokenTheme(
    override val id: UiId,
    override val displayName: String,
) : Theme {

    override fun textStyle(role: TextRole): TextStyle {
        val typography = tokens.typography
        val base = when (role) {
            TextRole.TITLE -> typography.title
            TextRole.LABEL -> typography.label
            TextRole.BODY -> typography.body
            TextRole.SECONDARY -> typography.secondary
            TextRole.CAPTION -> typography.caption
            TextRole.MONO -> typography.mono
        }
        val scale = tokens.accessibility.textScale
        return if (scale == 1f) base else base.copy(scale = base.scale * scale)
    }

    override fun textColor(role: TextRole): Color = when (role) {
        TextRole.TITLE, TextRole.LABEL, TextRole.BODY -> tokens.colors.textPrimary
        TextRole.SECONDARY, TextRole.CAPTION, TextRole.MONO -> tokens.colors.textSecondary
    }
}
