package dev.th7bo.sidequest.platform.core.skyblock

import java.awt.Color as AwtColor

/** How a SkyBlock level is coloured. */
public enum class LevelPalette {
    /** Hypixel's own: one of the game's named colours, changing every forty levels. */
    TIERED,

    /** A hue that advances with every level. No tiers, no two levels alike. */
    RAINBOW,
}

/**
 * What colour a SkyBlock level is drawn in.
 *
 * The tier boundaries are Hypixel's, not a preference: the level bracket changes colour every forty levels
 * and the sequence of named colours it runs through is a property of the game. Getting them from anywhere but
 * observation would be inventing SkyBlock, so they were read off the game and the ladder is written out here
 * as fact.
 *
 * Past the named colours the game leaves off, and everything from there is this mod's own. It is *computed*
 * rather than tabulated — see [beyondTier] — because a hand-picked table for levels most people will never
 * reach is a table nobody can check, and one that has to grow every time somebody levels past the end of it.
 */
public object SkyBlockLevelColours {

    /** How many levels share a colour. */
    public const val TIER_SIZE: Int = 40

    /**
     * Hypixel's ladder, one entry per forty levels from zero.
     *
     * The game's named colours in the order the level bracket runs through them: grey at the start, through
     * the greens and blues and purples, to red and dark red at the top of the range the game ever named.
     */
    public val NAMED_TIERS: List<Int> = listOf(
        0xAAAAAA, // grey
        0xFFFFFF, // white
        0xFFFF55, // yellow
        0x55FF55, // green
        0x00AA00, // dark green
        0x55FFFF, // aqua
        0x00AAAA, // dark aqua
        0x5555FF, // blue
        0xFF55FF, // light purple
        0xAA00AA, // dark purple
        0xFFAA00, // gold
        0xFF5555, // red
        0xAA0000, // dark red
    )

    /** The first level with no named colour of its own. */
    public val NAMED_CEILING: Int = NAMED_TIERS.size * TIER_SIZE

    /**
     * The colour for [level], as `0xRRGGBB`.
     *
     * Negative levels answer as level zero rather than throwing. A level is read off text somebody else's
     * client wrote and this is called once per player per frame, so the one thing it must never do is fail.
     */
    public fun colourFor(level: Int, palette: LevelPalette = LevelPalette.TIERED): Int {
        val clamped = level.coerceAtLeast(0)
        return when (palette) {
            LevelPalette.RAINBOW -> rainbow(clamped)
            LevelPalette.TIERED -> NAMED_TIERS.getOrNull(clamped / TIER_SIZE) ?: beyondTier(clamped)
        }
    }

    /**
     * A hue that keeps going after the named colours run out.
     *
     * Continues in tiers of forty, so a level past the ceiling still reads as belonging to a band rather than
     * shifting under you as you level. The hue picks up past red — where the named ladder ended — and walks
     * through magenta and violet, which is the part of the wheel the named colours barely use, so a very high
     * level is never mistakable for a middling one.
     *
     * Saturated and bright rather than subtle. These are the levels worth showing off.
     */
    private fun beyondTier(level: Int): Int {
        val step = (level - NAMED_CEILING) / TIER_SIZE
        val hue = (BEYOND_START_HUE + step * BEYOND_HUE_STEP) % 1f
        return AwtColor.HSBtoRGB(hue, BEYOND_SATURATION, BEYOND_BRIGHTNESS) and RGB_MASK
    }

    /**
     * One hue per level, never repeating within reach.
     *
     * Stops short of a full turn of the wheel on purpose: a sweep that wrapped would put a level-six-hundred
     * player in the same red as a level-zero one, which is the single thing a colour-by-level scheme exists to
     * prevent.
     */
    private fun rainbow(level: Int): Int {
        val hue = (level / RAINBOW_SPAN).coerceIn(0f, 1f) * RAINBOW_SWEEP
        return AwtColor.HSBtoRGB(hue, 1f, RAINBOW_BRIGHTNESS) and RGB_MASK
    }

    // -- reading a level off text -------------------------------------------

    /**
     * The level in a bracket, or null.
     *
     * Reads `[480]` out of `§8[§b480§8] §6Player`, which is the shape of a SkyBlock nametag. Commas are
     * allowed because the game writes them once a number gets long enough, and a pattern that did not accept
     * one would stop working at exactly the levels this feature is most for.
     */
    public fun levelIn(text: String): Int? =
        BRACKETED_LEVEL.find(text)?.groups?.get("level")?.value?.replace(",", "")?.toIntOrNull()

    /**
     * Where the level's digits sit in [text], or null.
     *
     * The range covers the digits alone, not the brackets or the formatting around them — a caller recolouring
     * it wants the number to change colour and the brackets to stay the grey Hypixel drew them.
     */
    public fun levelRangeIn(text: String): IntRange? =
        BRACKETED_LEVEL.find(text)?.groups?.get("level")?.range

    /**
     * A bracketed level, however it is formatted inside.
     *
     * Verified against real nametags: `§8[§b480§8] §6Player` and `§8[§6419§8] §bPlayer`. The formatting codes
     * sit between the bracket and the digits, which is why they have to be allowed for rather than stripped —
     * stripping first would lose the offsets a caller needs to put the colour back in the right place.
     */
    private val BRACKETED_LEVEL = Regex("""\[(?:§.)*(?<level>[\d,]+)(?:§.)*]""")

    private const val RGB_MASK = 0xFFFFFF

    /** Just past red, where the named ladder stopped. */
    private const val BEYOND_START_HUE = 0.88f
    private const val BEYOND_HUE_STEP = 0.045f
    private const val BEYOND_SATURATION = 0.55f
    private const val BEYOND_BRIGHTNESS = 1f

    /**
     * How many levels the rainbow spreads over.
     *
     * Wider than anybody has reached, so realistic levels never come back round to a hue already used.
     */
    private const val RAINBOW_SPAN = 600f

    /** Short of a full turn, so the sweep never wraps to the red it started on. */
    private const val RAINBOW_SWEEP = 0.85f

    private const val RAINBOW_BRIGHTNESS = 0.8f
}
