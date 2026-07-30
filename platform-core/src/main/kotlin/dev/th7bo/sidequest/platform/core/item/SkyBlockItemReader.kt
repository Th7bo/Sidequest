package dev.th7bo.sidequest.platform.core.item

import dev.th7bo.sidequest.platform.item.Gemstone
import dev.th7bo.sidequest.platform.item.GemstoneQuality
import dev.th7bo.sidequest.platform.item.GemstoneType
import dev.th7bo.sidequest.platform.item.ItemAcquisition
import dev.th7bo.sidequest.platform.item.ItemCategory
import dev.th7bo.sidequest.platform.item.ItemDataSource
import dev.th7bo.sidequest.platform.item.ItemIcon
import dev.th7bo.sidequest.platform.item.ItemUpgrades
import dev.th7bo.sidequest.platform.item.SqItem

/**
 * Turns an item's raw pieces into a [SqItem].
 *
 * Everything about *interpreting* a SkyBlock item lives here, and nothing about reading one
 * from the game. The adapter pulls out the four things Minecraft knows — the item id, the
 * name, the lore, the count — and an [ItemDataSource] over Hypixel's tag, and this does the
 * rest.
 *
 * The split is the point. Every attribute name below is a fact about Hypixel that will need
 * correcting one day, and correcting it means running a test, which means the logic cannot
 * live behind a Minecraft type.
 */
public object SkyBlockItemReader {

    /**
     * Attribute keys that are read into named fields.
     *
     * Held as a set so [SqItem.extra] can exclude them: an attribute that has a field would
     * otherwise appear twice, and a feature reading the escape hatch could disagree with the
     * field it duplicates.
     */
    private val CLAIMED_KEYS = setOf(
        "id",
        "uuid",
        "enchantments",
        "modifier",
        "upgrade_level",
        "dungeon_item_level",
        "rarity_upgrades",
        "hot_potato_count",
        "gems",
        "talisman_enrichment",
        "power_ability_scroll",
        "skin",
        "dye_item",
        "runes",
        "art_of_war_count",
        "artOfPeaceApplied",
        "wood_singularity_count",
        "ethermerge",
        "jalapeno_count",
        "farming_for_dummies_count",
        "mana_disintegrator_count",
        "tuned_transmission",
        "donated_museum",
    )

    /**
     * Builds a snapshot.
     *
     * @param data Hypixel's own tag. [ItemDataSource.Empty] for a vanilla item, which produces
     *   a valid snapshot with everything SkyBlock-specific absent.
     */
    public fun read(
        minecraftId: String,
        displayName: String,
        lore: List<String>,
        quantity: Int,
        data: ItemDataSource,
        icon: ItemIcon? = null,
        acquisition: ItemAcquisition? = null,
    ): SqItem {
        val loreRarity = ItemLoreParser.rarityOf(lore)
        return SqItem(
            minecraftId = minecraftId,
            skyblockId = data.string("id"),
            itemUuid = data.string("uuid"),
            displayName = displayName,
            rarity = loreRarity?.rarity,
            category = loreRarity?.category,
            quantity = quantity,
            lore = lore,
            icon = icon,
            upgrades = upgradesOf(data, lore),
            enchantments = enchantmentsOf(data),
            acquisition = acquisition,
            extra = extraOf(data, loreRarity?.categoryWording),
        )
    }

    /** Hypixel's enchantments, by their own lowercase id. */
    private fun enchantmentsOf(data: ItemDataSource): Map<String, Int> {
        val enchantments = data.compound("enchantments") ?: return emptyMap()
        return enchantments.keys().mapNotNull { key ->
            enchantments.int(key)?.let { key to it }
        }.toMap()
    }

    private fun upgradesOf(data: ItemDataSource, lore: List<String>): ItemUpgrades = ItemUpgrades(
        reforge = data.string("modifier"),
        stars = starsOf(data, lore),
        isRecombobulated = (data.int("rarity_upgrades") ?: 0) > 0,
        hotPotatoBooks = data.int("hot_potato_count") ?: 0,
        gemstones = gemstonesOf(data),
        enrichment = data.string("talisman_enrichment"),
        powerScroll = data.string("power_ability_scroll"),
        skin = data.string("skin"),
        dye = data.string("dye_item"),
        rune = runeOf(data),
        artOfWar = (data.int("art_of_war_count") ?: 0) > 0,
        artOfPeace = data.byte("artOfPeaceApplied") == ONE,
        woodSingularity = (data.int("wood_singularity_count") ?: 0) > 0,
        etherwarp = data.byte("ethermerge") == ONE,
        jalapenoBook = (data.int("jalapeno_count") ?: 0) > 0,
        farmingForDummies = data.int("farming_for_dummies_count") ?: 0,
        manaDisintegrators = data.int("mana_disintegrator_count") ?: 0,
        transmissionTuners = data.int("tuned_transmission") ?: 0,
        isMuseumDonated = data.byte("donated_museum") == ONE,
    )

    /**
     * Stars, which Hypixel counts in two places.
     *
     * `upgrade_level` is the modern one and covers everything. `dungeon_item_level` is the
     * older attribute, still present on items that have not been touched since, and only
     * meaningful on a dungeon item — hence the lore check. Reading only one of them
     * undercounts a stack of stars on an old sword.
     */
    private fun starsOf(data: ItemDataSource, lore: List<String>): Int {
        data.int("upgrade_level")?.let { return it }
        val isDungeonItem = lore.any { it.contains("DUNGEON ") }
        return if (isDungeonItem) data.int("dungeon_item_level") ?: 0 else 0
    }

    /**
     * Gemstones, from the `gems` tag.
     *
     * Awkward, and unavoidably so. A slot is keyed by its type — `JADE_0` — and the value is
     * either the quality directly or a nested tag holding it. Worse, a *universal* slot's key
     * does not name the type at all; the type is in a sibling key with `_gem` appended. All
     * three shapes are live on real items, which is why this is not two lines.
     */
    private fun gemstonesOf(data: ItemDataSource): List<Gemstone> {
        val gems = data.compound("gems") ?: return emptyList()
        val slots = ArrayList<Gemstone>()

        for (key in gems.keys()) {
            // `X_gem` is the companion of another key, not a slot of its own.
            if (key.endsWith("_gem") || key == "unlocked_slots") continue

            val qualityName = gems.string(key)?.takeIf { it.isNotEmpty() }
                ?: gems.compound(key)?.string("quality")?.takeIf { it.isNotEmpty() }
                ?: continue
            val quality = GemstoneQuality.ofName(qualityName) ?: continue

            // The type is in the key for a typed slot, and in the companion key for a
            // universal one.
            val type = GemstoneType.ofName(key.substringBefore('_'))
                ?: gems.string("${key}_gem")?.let(GemstoneType::ofName)
                ?: continue

            slots.add(Gemstone(type, quality))
        }
        return slots
    }

    /** The applied rune, as `id:tier`. The tag holds one entry at most. */
    private fun runeOf(data: ItemDataSource): String? {
        val runes = data.compound("runes") ?: return null
        val key = runes.keys().firstOrNull() ?: return null
        val tier = runes.int(key) ?: return key
        return "$key:$tier"
    }

    /**
     * Everything Hypixel stored that has no field, as strings.
     *
     * Nested tags are skipped rather than flattened: a flattened `pet_info` blob in a map of
     * strings is not usable by anything, and pretending otherwise invites a feature to parse
     * it. What is here is the flat attributes, which is what an escape hatch is for.
     */
    private fun extraOf(data: ItemDataSource, categoryWording: String?): Map<String, String> {
        val extra = LinkedHashMap<String, String>()
        for (key in data.keys()) {
            if (key in CLAIMED_KEYS) continue
            if (data.compound(key) != null) continue
            data.asString(key)?.takeIf { it.isNotEmpty() }?.let { extra[key] = it }
        }
        // Kept so a category the enum does not know is still recoverable rather than lost.
        if (categoryWording != null && ItemCategory.ofWording(categoryWording) == null) {
            extra["category_wording"] = categoryWording
        }
        return extra
    }

    private const val ONE: Byte = 1
}
