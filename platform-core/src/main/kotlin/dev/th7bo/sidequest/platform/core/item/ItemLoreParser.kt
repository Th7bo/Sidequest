package dev.th7bo.sidequest.platform.core.item

import dev.th7bo.sidequest.platform.item.ItemCategory
import dev.th7bo.sidequest.platform.item.ItemRarity
import dev.th7bo.sidequest.platform.parser.HypixelText

/** Rarity and category, as the lore states them. */
public data class LoreRarity(
    public val rarity: ItemRarity,
    public val category: ItemCategory?,
    /** Hypixel's own wording for the category, kept when the enum does not have it. */
    public val categoryWording: String?,
)

/**
 * Reads an item's rarity off its lore.
 *
 * There is no attribute for rarity. Hypixel writes it as the last line of the lore, in caps,
 * with the category beside it — `MYTHIC DUNGEON SWORD` — and reading that line is the only way
 * to know. The pattern and its fixtures come from SkyHanni's `utils` group, unchanged.
 *
 * **Searched from the bottom up.** The rarity line is the last one, and searching downwards
 * would find the word "RARE" in an ability description first. `RARE CROP` is the counter-case
 * that proves it needs excluding: it appears in Garden item lore and is not a rarity.
 */
public object ItemLoreParser {

    /**
     * The rarity line.
     *
     * The `a … a` wrapping is Hypixel's obfuscated-glyph decoration on the fanciest items,
     * which renders as animated characters and arrives as plain letters. `SHINY` is a prefix
     * on museum-shiny items. Both are stripped rather than captured.
     */
    private val RARITY_LINE = Regex(
        """(?:Rarity: )?(?:a )?(?:SHINY )?(?<rarity>${ItemRarity.LORE_WORDINGS.joinToString("|")})""" +
            """(?: DUNGEON)? ?(?<category>[A-Z].*?|)(?: a)?(?: \(ID \w\d+\))?""",
    )

    /** `RARE CROP` in Garden lore is not a rarity line. */
    private val NOT_RARITY_LINE = Regex("""RARE CROPS?""")

    /**
     * Reads [lore], which may still be formatted.
     *
     * Null when no line states a rarity, which is the normal answer for a vanilla item and for
     * plenty of SkyBlock ones.
     */
    public fun rarityOf(lore: List<String>): LoreRarity? {
        for (line in lore.asReversed()) {
            val clean = HypixelText.clean(line)
            if (NOT_RARITY_LINE.matches(clean)) continue
            val match = RARITY_LINE.matchEntire(clean) ?: continue
            val rarity = ItemRarity.ofWording(match.groups["rarity"]!!.value) ?: continue
            val wording = match.groups["category"]!!.value.takeIf { it.isNotBlank() }
            return LoreRarity(
                rarity = rarity,
                category = wording?.let(ItemCategory::ofWording),
                categoryWording = wording,
            )
        }
        return null
    }
}
