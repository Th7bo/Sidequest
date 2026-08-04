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

/**
 * Corner radii.
 *
 * Generous, and they were not always allowed to be: the corners used to be cut on whole pixels, so a large
 * radius meant a large staircase and the values were held down to hide it. With the corners anti-aliased
 * that constraint is gone, and a bigger radius is most of what separates a soft modern panel from a box.
 */
public data class RadiusTokens(
    val none: Dp = Dp.Zero,
    val small: Dp = 5.dp,
    val medium: Dp = 8.dp,
    val large: Dp = 12.dp,
    val pill: Dp = 999.dp,
)

/**
 * The spacing rhythm.
 *
 * Tighter than it was. The interfaces this is aiming at are *dense* — rows close together inside a panel,
 * with the breathing room spent on the gap between panels instead. Padding everything evenly is what made
 * one settings page fill a screen and read as sprawling rather than as composed.
 */
public data class SpacingScale(
    val none: Dp = Dp.Zero,
    val xs: Dp = 2.dp,
    val small: Dp = 4.dp,
    val medium: Dp = 6.dp,
    val large: Dp = 10.dp,
    val xl: Dp = 14.dp,
    val xxl: Dp = 22.dp,
)

/**
 * A compact type scale for the bundled Inter face.
 *
 * Titles get just enough size and weight to anchor a region; secondary copy steps down slightly so dense
 * setting lists remain easy to scan. The shipped font is oversampled, so these restrained fractional sizes
 * remain smooth in Minecraft rather than degrading into rescaled bitmap glyphs.
 */
public data class TypographyScale(
    val title: TextStyle = TextStyle(scale = 1.15f, bold = true),
    val label: TextStyle = TextStyle(scale = 1f),
    val body: TextStyle = TextStyle(scale = 1f),
    val secondary: TextStyle = TextStyle(scale = 0.92f),
    val caption: TextStyle = TextStyle(scale = 0.86f),
    val mono: TextStyle = TextStyle(scale = 0.92f),
)

/** Animation durations in seconds. All motion is delta-time driven, never frame counted. */
public data class MotionTokens(
    val instant: Float = 0f,
    val fast: Float = 0.08f,
    val normal: Float = 0.15f,
    val slow: Float = 0.25f,
    val deliberate: Float = 0.4f,
)

/**
 * Fixed sizes that keep controls consistent across screens.
 *
 * The control heights are tight against the font's own line height on purpose. A row that is twice as tall
 * as the text in it reads as a form; one that hugs its label reads as a list, which is what the references
 * are.
 */
public data class MetricTokens(
    val controlHeight: Dp = 22.dp,
    val compactControlHeight: Dp = 17.dp,
    val borderWidth: Dp = 1.dp,
    val focusRingWidth: Dp = 1.dp,
    val sidebarWidth: Dp = 142.dp,
    val inspectorWidth: Dp = 182.dp,
    val scrollbarWidth: Dp = 4.dp,
    val iconSize: Dp = 12.dp,
)

/**
 * Depth.
 *
 * Separation comes from *layering* — a shadow under a panel and blur behind it — rather than from drawing a
 * line around everything. A visible border on every surface is what makes an interface read as a stack of
 * boxes; a panel that simply floats above what is behind it reads as one thing.
 */
public data class EffectTokens(
    val panelShadow: Shadow = Shadow(Color(0x66000000), blurRadius = 14f),
    val overlayShadow: Shadow = Shadow(Color(0x80000000.toInt()), blurRadius = 22f),
    /** `0` disables blur entirely, which is the correct setting on weak hardware. */
    val blurStrength: Float = 0.6f,
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
