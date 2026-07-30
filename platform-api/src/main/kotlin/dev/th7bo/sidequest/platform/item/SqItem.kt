package dev.th7bo.sidequest.platform.item

import dev.th7bo.sidequest.platform.parser.HypixelText
import dev.th7bo.sidequest.platform.skyblock.GameContext
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.SkyBlockProfile
import dev.th7bo.sidequest.platform.skyblock.SubLocation
import kotlinx.serialization.Serializable

/**
 * An item, as something worth keeping.
 *
 * **Never persist an `ItemStack`.** That is the rule this type exists to make easy to follow.
 * A stack is a live game object: it belongs to a version of Minecraft, it needs a registry to
 * mean anything, and the moment Mojang changes a component the saved copy is unreadable. A
 * record of a rare drop from eighteen months ago has to still be readable, and so does a debt
 * that names the item it is for.
 *
 * So this is a *snapshot*: plain data, fully serialisable, with no game type anywhere in it.
 * Every field is optional except the two identifiers, because a snapshot of a vanilla stone
 * block and a snapshot of a five-star Hyperion are the same type.
 *
 * Everything the plan lists is here — SkyBlock id, item uuid, name, rarity, quantity, lore,
 * icon, upgrades, enchantments, estimated value, acquisition context — and the whole value is
 * the "serialized safe snapshot" it also asks for.
 */
@Serializable
public data class SqItem(
    /**
     * Minecraft's own item id, e.g. `minecraft:diamond_sword`.
     *
     * Always present, and the only field that is. Even an item Hypixel invented is *some*
     * Minecraft item underneath, which is what makes it possible to draw one from a snapshot.
     */
    public val minecraftId: String,
    /**
     * Hypixel's item id, e.g. `HYPERION`. Null for a plain vanilla item.
     *
     * The identity of the *kind* of item. Stable across renames — Hypixel changes display
     * names and has never changed one of these — so anything comparing items compares this.
     */
    public val skyblockId: String? = null,
    /**
     * Hypixel's per-instance uuid, when the item has one.
     *
     * The identity of *this* item, as opposed to its kind. Only items Hypixel considers
     * unique carry one, which is exactly the set worth tracking individually: a lent
     * Hyperion has one, a stack of cobblestone does not.
     */
    public val itemUuid: String? = null,
    /** The name as shown, formatting codes intact. */
    public val displayName: String = "",
    public val rarity: ItemRarity? = null,
    public val category: ItemCategory? = null,
    public val quantity: Int = 1,
    /** The lore as shown, formatting intact, top to bottom. */
    public val lore: List<String> = emptyList(),
    public val icon: ItemIcon? = null,
    public val upgrades: ItemUpgrades = ItemUpgrades(),
    /** Hypixel's enchantments, by their own lowercase id: `sharpness` to 7. */
    public val enchantments: Map<String, Int> = emptyMap(),
    /**
     * Estimated coin value, when a price source has been asked.
     *
     * Null means "nobody has priced this", not "worthless". There is no price source yet;
     * the field exists so the record written today can be read by the feature that adds one,
     * rather than the model changing shape underneath every stored snapshot.
     */
    public val estimatedValue: Long? = null,
    /** Where this item came from, when it was captured at the moment of acquisition. */
    public val acquisition: ItemAcquisition? = null,
    /**
     * Anything else Hypixel put in the item's data, as strings.
     *
     * The escape hatch, same as the scoreboard's `values`. A feature that needs an attribute
     * the model does not name reads it here instead of growing its own NBT access — and if it
     * turns out to matter, it graduates to a field.
     */
    public val extra: Map<String, String> = emptyMap(),
) {

    /** The name with formatting removed. What to compare against, and what to log. */
    public val plainName: String get() = HypixelText.clean(displayName)

    /** Lore with formatting removed. */
    public val plainLore: List<String> get() = lore.map(HypixelText::clean)

    /** Whether Hypixel considers this item unique enough to have given it an identity. */
    public val isUnique: Boolean get() = itemUuid != null

    /** Whether this is a SkyBlock item at all, as opposed to a plain vanilla one. */
    public val isSkyBlockItem: Boolean get() = skyblockId != null

    /** One line, for a log or a chat message. */
    public fun summary(): String = buildString {
        if (quantity > 1) append("${quantity}x ")
        append(plainName.ifEmpty { skyblockId ?: minecraftId })
        rarity?.let { append(" [${it.displayName}]") }
        val extras = upgrades.summary()
        if (extras.isNotEmpty()) append(" ($extras)")
    }

    public companion object {
        /** A snapshot of a plain vanilla item. */
        public fun vanilla(minecraftId: String, quantity: Int = 1): SqItem =
            SqItem(minecraftId = minecraftId, quantity = quantity)
    }
}

/**
 * How an item is drawn.
 *
 * Two cases, because Hypixel uses both. Most items are a real Minecraft item, possibly dyed;
 * the rest are player heads wearing a custom texture, which is how every custom-looking item
 * in SkyBlock exists.
 */
@Serializable
public data class ItemIcon(
    public val minecraftId: String,
    /**
     * The base64 skin value, for a player-head icon.
     *
     * Kept as the encoded value rather than a resolved URL: it is what the game needs to draw
     * the head, and resolving it would mean a network call to render an inventory.
     */
    public val skinTexture: String? = null,
    /** Leather-armour dye, as RGB. */
    public val dyeColor: Int? = null,
) {
    public val isPlayerHead: Boolean get() = skinTexture != null
}

/**
 * Rarity, in Hypixel's order.
 *
 * Ordered, so `>=` works: a feature that acts on "legendary or better" compares rather than
 * enumerating. The ids are Hypixel's own and are not the ordinal — there is a gap at 7 —
 * which is why they are written down instead of derived.
 */
@Serializable
public enum class ItemRarity(public val hypixelId: Int, public val displayName: String) {
    COMMON(0, "Common"),
    UNCOMMON(1, "Uncommon"),
    RARE(2, "Rare"),
    EPIC(3, "Epic"),
    LEGENDARY(4, "Legendary"),
    MYTHIC(5, "Mythic"),
    DIVINE(6, "Divine"),
    SPECIAL(8, "Special"),
    VERY_SPECIAL(9, "Very Special"),
    ULTIMATE(10, "Ultimate"),
    ;

    /** Hypixel's own wording, as it appears in lore: `VERY SPECIAL`. */
    public val loreWording: String get() = name.replace('_', ' ')

    public fun isAtLeast(other: ItemRarity): Boolean = ordinal >= other.ordinal

    public companion object {
        /** Reads Hypixel's lore wording. Null when it is not one of them. */
        public fun ofWording(wording: String): ItemRarity? {
            val normalised = wording.trim().replace(' ', '_').uppercase()
            return entries.firstOrNull { it.name == normalised }
        }

        /** Rarities in lore order, longest first, for building an alternation that matches greedily. */
        public val LORE_WORDINGS: List<String> = entries.map { it.loreWording }.sortedByDescending { it.length }
    }
}

/**
 * What kind of item it is, from the rarity line's trailing word.
 *
 * Hypixel prints the category next to the rarity — `MYTHIC DUNGEON SWORD` — so it comes free
 * with the rarity. Deliberately not exhaustive: an unrecognised category reads as null rather
 * than failing, because Hypixel adds them and no feature enumerates.
 */
@Serializable
public enum class ItemCategory {
    SWORD,
    BOW,
    LONGSWORD,
    WAND,
    HELMET,
    CHESTPLATE,
    LEGGINGS,
    BOOTS,
    NECKLACE,
    CLOAK,
    BELT,
    GLOVES,
    BRACELET,
    ACCESSORY,
    HATCESSORY,
    PET,
    PET_ITEM,
    REFORGE_STONE,
    TRAVEL_SCROLL,
    ARROW,
    ARROW_POISON,
    AXE,
    HOE,
    PICKAXE,
    DRILL,
    SHOVEL,
    FISHING_ROD,
    FISHING_WEAPON,
    ENCHANTED_BOOK,
    POTION,
    SACK,
    BAIT,
    COSMETIC,
    GAUNTLET,
    DEPLOYABLE,
    SHEARS,
    VACUUM,
    SPADE,
    NONE,
    ;

    public companion object {
        /** Reads Hypixel's lore wording, `PET ITEM` and all. Null when unrecognised. */
        public fun ofWording(wording: String): ItemCategory? {
            val normalised = wording.trim().replace(' ', '_').uppercase()
            return entries.firstOrNull { it.name == normalised }
        }
    }
}

/**
 * What has been done to an item.
 *
 * Every field is a thing that changes what the item is worth or what it can do, and every one
 * of them is read from a single attribute Hypixel stores on the stack. The set is not
 * complete — there are dozens — and it does not need to be: what is missing is in
 * [SqItem.extra], and a field is added when a feature needs it.
 */
@Serializable
public data class ItemUpgrades(
    /** The reforge's own id, e.g. `withered`. */
    public val reforge: String? = null,
    /** Stars, from master stars and dungeon upgrades. */
    public val stars: Int = 0,
    public val isRecombobulated: Boolean = false,
    public val hotPotatoBooks: Int = 0,
    public val gemstones: List<Gemstone> = emptyList(),
    /** Talisman enrichment, e.g. `strength`. */
    public val enrichment: String? = null,
    /** The applied power scroll's item id. */
    public val powerScroll: String? = null,
    /** A helmet skin's item id. */
    public val skin: String? = null,
    /** An applied armour dye's item id. */
    public val dye: String? = null,
    /** An applied rune, as `id:tier`. */
    public val rune: String? = null,
    public val artOfWar: Boolean = false,
    public val artOfPeace: Boolean = false,
    public val woodSingularity: Boolean = false,
    public val etherwarp: Boolean = false,
    public val jalapenoBook: Boolean = false,
    public val farmingForDummies: Int = 0,
    public val manaDisintegrators: Int = 0,
    public val transmissionTuners: Int = 0,
    public val isMuseumDonated: Boolean = false,
) {

    /** Whether anything at all has been done to the item. */
    public val isStock: Boolean get() = this == Stock

    /** The upgrades worth naming, comma separated. Empty when the item is stock. */
    public fun summary(): String = buildList {
        reforge?.let { add(it) }
        if (stars > 0) add("$stars★")
        if (isRecombobulated) add("recombobulated")
        if (hotPotatoBooks > 0) add("$hotPotatoBooks hpb")
        if (gemstones.isNotEmpty()) add(gemstones.joinToString(" ") { it.summary() })
        enrichment?.let { add("$it enrichment") }
        powerScroll?.let { add(it) }
        if (artOfWar) add("art of war")
        if (etherwarp) add("etherwarp")
    }.joinToString(", ")

    public companion object {
        public val Stock: ItemUpgrades = ItemUpgrades()
    }
}

/** One gemstone in one slot. */
@Serializable
public data class Gemstone(
    public val type: GemstoneType,
    public val quality: GemstoneQuality,
) {
    public fun summary(): String = "${quality.displayName} ${type.displayName}"
}

@Serializable
public enum class GemstoneQuality(public val displayName: String) {
    ROUGH("Rough"),
    FLAWED("Flawed"),
    FINE("Fine"),
    FLAWLESS("Flawless"),
    PERFECT("Perfect"),
    ;

    public companion object {
        public fun ofName(name: String): GemstoneQuality? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

@Serializable
public enum class GemstoneType(public val displayName: String) {
    JADE("Jade"),
    AMBER("Amber"),
    TOPAZ("Topaz"),
    SAPPHIRE("Sapphire"),
    AMETHYST("Amethyst"),
    JASPER("Jasper"),
    RUBY("Ruby"),
    OPAL("Opal"),
    ONYX("Onyx"),
    AQUAMARINE("Aquamarine"),
    CITRINE("Citrine"),
    PERIDOT("Peridot"),
    ;

    public companion object {
        public fun ofName(name: String): GemstoneType? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Where an item came from.
 *
 * Captured at the moment of acquisition, because it cannot be recovered afterwards: the
 * player will have moved, hopped server and switched profile long before anybody asks where
 * the drop happened. Every social feature that shows an item — evidence, a debt, a rare-drop
 * feed — wants this, and none of them can reconstruct it.
 */
@Serializable
public data class ItemAcquisition(
    /** Wall clock, epoch milliseconds. */
    public val timestampMillis: Long,
    public val source: AcquisitionSource = AcquisitionSource.UNKNOWN,
    public val island: Island = Island.NONE,
    public val subLocation: SubLocation = SubLocation.Unknown,
    public val profile: SkyBlockProfile = SkyBlockProfile.Unknown,
    /** Free text for the source's own detail: the mob that dropped it, who gifted it. */
    public val note: String? = null,
) {
    public companion object {
        /** Captures the current context. The form a feature uses at the moment of a drop. */
        public fun from(
            context: GameContext,
            timestampMillis: Long,
            source: AcquisitionSource = AcquisitionSource.UNKNOWN,
            note: String? = null,
        ): ItemAcquisition = ItemAcquisition(
            timestampMillis = timestampMillis,
            source = source,
            island = context.island,
            subLocation = context.subLocation,
            profile = context.profile,
            note = note,
        )
    }
}

/** How an item was come by. */
@Serializable
public enum class AcquisitionSource {
    DROP,
    CHEST,
    CRAFT,
    PURCHASE,
    AUCTION,
    BAZAAR,
    TRADE,
    GIFT,
    FORGE,
    FISHING,
    FARMING,
    MINING,
    UNKNOWN,
}

/**
 * Read-only access to an item's Hypixel data, without a Minecraft type in sight.
 *
 * Hypixel stores everything about an item in one nested tag on the stack. Reading it needs
 * the game; *interpreting* it does not, and this interface is the seam between the two. The
 * adapter implements it over the real tag in eight lines, and the interpretation — every
 * upgrade, every attribute name — lives in `platform-core` where a test can drive it from a
 * map.
 *
 * Without the seam, the whole of the item model's logic would be in the Minecraft adapter,
 * untestable except in a running game.
 */
public interface ItemDataSource {

    public fun string(key: String): String?

    public fun int(key: String): Int?

    public fun long(key: String): Long?

    public fun byte(key: String): Byte?

    /** A nested tag, or null when the key is absent or is not one. */
    public fun compound(key: String): ItemDataSource?

    public fun keys(): Set<String>

    /** Whatever is at [key], rendered as a string. For the escape-hatch map. */
    public fun asString(key: String): String?

    public companion object {
        /** An item with no Hypixel data at all — a vanilla stack. */
        public val Empty: ItemDataSource = object : ItemDataSource {
            override fun string(key: String): String? = null
            override fun int(key: String): Int? = null
            override fun long(key: String): Long? = null
            override fun byte(key: String): Byte? = null
            override fun compound(key: String): ItemDataSource? = null
            override fun keys(): Set<String> = emptySet()
            override fun asString(key: String): String? = null
        }
    }
}
