package dev.th7bo.sidequest.platform.core.item

import dev.th7bo.sidequest.platform.item.AcquisitionSource
import dev.th7bo.sidequest.platform.item.Gemstone
import dev.th7bo.sidequest.platform.item.GemstoneQuality
import dev.th7bo.sidequest.platform.item.GemstoneType
import dev.th7bo.sidequest.platform.item.ItemAcquisition
import dev.th7bo.sidequest.platform.item.ItemCategory
import dev.th7bo.sidequest.platform.item.ItemDataSource
import dev.th7bo.sidequest.platform.item.ItemRarity
import dev.th7bo.sidequest.platform.item.SqItem
import dev.th7bo.sidequest.platform.skyblock.GameContext
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.SkyBlockProfile
import dev.th7bo.sidequest.platform.skyblock.SubLocation
import dev.th7bo.sidequest.platform.testkit.FakeItemData
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The item model, and the reading of one.
 *
 * All of it headless, which is the point of [ItemDataSource] existing at all: every attribute
 * name in the reader is a fact about Hypixel that will need correcting, and correcting it has
 * to mean running a test rather than launching the game and finding the right sword.
 *
 * The rarity-line fixtures are SkyHanni's `REGEX-TEST` comments unchanged. The attribute names
 * come from their `SkyBlockItemModifierUtils`; the tag shapes below are constructed from those
 * names, and say so where it matters.
 */
class ItemTest {

    private fun read(
        data: ItemDataSource = ItemDataSource.Empty,
        minecraftId: String = "minecraft:diamond_sword",
        displayName: String = "§6Hyperion",
        lore: List<String> = emptyList(),
        quantity: Int = 1,
    ) = SkyBlockItemReader.read(minecraftId, displayName, lore, quantity, data)

    // ---------------------------------------------------------------
    // Rarity from lore
    // ---------------------------------------------------------------

    /** There is no rarity attribute. The last lore line is the only source there is. */
    @Test
    fun `rarity is read off the last lore line`() {
        val parsed = ItemLoreParser.rarityOf(listOf("§7Ability: Wither Impact", "§d§lMYTHIC DUNGEON SWORD"))
        assertEquals(ItemRarity.MYTHIC, parsed?.rarity)
        assertEquals(ItemCategory.SWORD, parsed?.category)
    }

    @Test
    fun `every wording Hypixel uses is recognised`() {
        assertEquals(ItemRarity.MYTHIC, ItemLoreParser.rarityOf(listOf("a MYTHIC ACCESSORY a"))?.rarity)
        assertEquals(ItemRarity.MYTHIC, ItemLoreParser.rarityOf(listOf("a SHINY MYTHIC DUNGEON CHESTPLATE a"))?.rarity)
        assertEquals(ItemRarity.VERY_SPECIAL, ItemLoreParser.rarityOf(listOf("a VERY SPECIAL HATCESSORY a"))?.rarity)
        assertEquals(ItemRarity.LEGENDARY, ItemLoreParser.rarityOf(listOf("SHINY LEGENDARY DUNGEON BOOTS"))?.rarity)
        assertEquals(ItemRarity.EPIC, ItemLoreParser.rarityOf(listOf("EPIC BOOTS"))?.rarity)
        assertEquals(ItemRarity.COMMON, ItemLoreParser.rarityOf(listOf("COMMON"))?.rarity)
        assertEquals(ItemRarity.LEGENDARY, ItemLoreParser.rarityOf(listOf("Rarity: LEGENDARY"))?.rarity)
        assertEquals(ItemRarity.DIVINE, ItemLoreParser.rarityOf(listOf("a DIVINE a"))?.rarity)
        assertEquals(ItemRarity.COMMON, ItemLoreParser.rarityOf(listOf("COMMON COMBAT SHARD (ID C9)"))?.rarity)
    }

    /**
     * `RARE CROP` is not a rarity.
     *
     * It appears in Garden item lore, and reading it as one would report every crop as a rare
     * item. The exclusion is SkyHanni's, and it is there because it happened.
     */
    @Test
    fun `RARE CROP is not a rarity line`() {
        assertNull(ItemLoreParser.rarityOf(listOf("RARE CROP")))
        assertNull(ItemLoreParser.rarityOf(listOf("RARE CROPS")))
    }

    /**
     * Searched from the bottom up.
     *
     * Downwards, an ability description mentioning a rarity would win over the real line.
     */
    @Test
    fun `an ability description mentioning a rarity does not win`() {
        val parsed = ItemLoreParser.rarityOf(
            listOf("§7Grants a RARE item on kill", "§7", "§6§lLEGENDARY SWORD"),
        )
        assertEquals(ItemRarity.LEGENDARY, parsed?.rarity)
    }

    @Test
    fun `no rarity line means no rarity, not a default`() {
        assertNull(ItemLoreParser.rarityOf(listOf("§7Just a rock.")))
        assertNull(ItemLoreParser.rarityOf(emptyList()))
    }

    /** A category the enum does not have is kept as Hypixel worded it rather than lost. */
    @Test
    fun `an unknown category survives in the escape hatch`() {
        val item = read(lore = listOf("§6§lLEGENDARY QUANTUM WIDGET"))
        assertEquals(ItemRarity.LEGENDARY, item.rarity)
        assertNull(item.category)
        assertEquals("QUANTUM WIDGET", item.extra["category_wording"])
    }

    // ---------------------------------------------------------------
    // Identity
    // ---------------------------------------------------------------

    @Test
    fun `a SkyBlock item carries its id and its instance uuid`() {
        val item = read(FakeItemData("id" to "HYPERION", "uuid" to "e5f1a1c0-0000-4000-8000-000000000001"))
        assertEquals("HYPERION", item.skyblockId)
        assertEquals("e5f1a1c0-0000-4000-8000-000000000001", item.itemUuid)
        assertTrue(item.isSkyBlockItem)
        assertTrue(item.isUnique)
    }

    @Test
    fun `a vanilla item reads cleanly with everything SkyBlock absent`() {
        val item = read(minecraftId = "minecraft:cobblestone", displayName = "Cobblestone", quantity = 64)
        assertNull(item.skyblockId)
        assertNull(item.itemUuid)
        assertFalse(item.isSkyBlockItem)
        assertFalse(item.isUnique)
        assertEquals(64, item.quantity)
        assertTrue(item.upgrades.isStock)
    }

    // ---------------------------------------------------------------
    // Upgrades
    // ---------------------------------------------------------------

    @Test
    fun `the upgrades that change what an item is worth are all read`() {
        val item = read(
            FakeItemData(
                "id" to "HYPERION",
                "modifier" to "withered",
                "upgrade_level" to 10,
                "rarity_upgrades" to 1,
                "hot_potato_count" to 15,
                "talisman_enrichment" to "strength",
                "power_ability_scroll" to "WITHER_SHIELD_SCROLL",
                "art_of_war_count" to 1,
                "ethermerge" to 1.toByte(),
                "wood_singularity_count" to 1,
                "farming_for_dummies_count" to 5,
                "mana_disintegrator_count" to 10,
                "tuned_transmission" to 4,
                "donated_museum" to 1.toByte(),
            ),
        )

        val upgrades = item.upgrades
        assertEquals("withered", upgrades.reforge)
        assertEquals(10, upgrades.stars)
        assertTrue(upgrades.isRecombobulated)
        assertEquals(15, upgrades.hotPotatoBooks)
        assertEquals("strength", upgrades.enrichment)
        assertEquals("WITHER_SHIELD_SCROLL", upgrades.powerScroll)
        assertTrue(upgrades.artOfWar)
        assertTrue(upgrades.etherwarp)
        assertTrue(upgrades.woodSingularity)
        assertEquals(5, upgrades.farmingForDummies)
        assertEquals(10, upgrades.manaDisintegrators)
        assertEquals(4, upgrades.transmissionTuners)
        assertTrue(upgrades.isMuseumDonated)
        assertFalse(upgrades.isStock)
    }

    /**
     * Stars live in two attributes.
     *
     * `dungeon_item_level` is the older one, still on items nobody has touched since, and only
     * meaningful on a dungeon item. Reading only `upgrade_level` undercounts an old sword to
     * zero stars.
     */
    @Test
    fun `stars are read from the old attribute on an old dungeon item`() {
        val item = read(
            FakeItemData("id" to "SPIRIT_SCEPTRE", "dungeon_item_level" to 5),
            lore = listOf("§d§lMYTHIC DUNGEON SWORD"),
        )
        assertEquals(5, item.upgrades.stars)
    }

    /** The old attribute means nothing outside a dungeon item, so it is not read there. */
    @Test
    fun `the old star attribute is ignored on a non-dungeon item`() {
        val item = read(
            FakeItemData("id" to "HYPERION", "dungeon_item_level" to 5),
            lore = listOf("§6§lLEGENDARY SWORD"),
        )
        assertEquals(0, item.upgrades.stars)
    }

    @Test
    fun `the modern attribute wins where both are present`() {
        val item = read(
            FakeItemData("upgrade_level" to 10, "dungeon_item_level" to 5),
            lore = listOf("§d§lMYTHIC DUNGEON SWORD"),
        )
        assertEquals(10, item.upgrades.stars)
    }

    /**
     * Gemstone slots arrive in three shapes, and all three are live.
     *
     * A typed slot names its gemstone in the key. A slot whose value is a tag holds the quality
     * inside it. A universal slot's key does not name a type at all — that is in a sibling key
     * with `_gem` appended, which is why the companion keys have to be skipped as slots.
     */
    @Test
    fun `all three gemstone slot shapes are read`() {
        val item = read(
            FakeItemData(
                "gems" to FakeItemData(
                    "JADE_0" to "PERFECT",
                    "AMBER_0" to FakeItemData("quality" to "FLAWLESS"),
                    "UNIVERSAL_0" to "FINE",
                    "UNIVERSAL_0_gem" to "RUBY",
                    "unlocked_slots" to "JADE_0",
                ),
            ),
        )

        assertEquals(
            setOf(
                Gemstone(GemstoneType.JADE, GemstoneQuality.PERFECT),
                Gemstone(GemstoneType.AMBER, GemstoneQuality.FLAWLESS),
                Gemstone(GemstoneType.RUBY, GemstoneQuality.FINE),
            ),
            item.upgrades.gemstones.toSet(),
        )
    }

    @Test
    fun `an empty gemstone slot contributes nothing`() {
        val item = read(FakeItemData("gems" to FakeItemData("JADE_0" to "", "unlocked_slots" to "JADE_0")))
        assertEquals(emptyList<Gemstone>(), item.upgrades.gemstones)
    }

    @Test
    fun `enchantments come out by their Hypixel ids`() {
        val item = read(FakeItemData("enchantments" to FakeItemData("sharpness" to 7, "looting" to 5)))
        assertEquals(mapOf("sharpness" to 7, "looting" to 5), item.enchantments)
    }

    @Test
    fun `a rune is read as id and tier`() {
        assertEquals("MUSIC_RUNE" + ":3", read(FakeItemData("runes" to FakeItemData("MUSIC_RUNE" to 3))).upgrades.rune)
    }

    // ---------------------------------------------------------------
    // The escape hatch
    // ---------------------------------------------------------------

    /** An attribute with a field must not also appear in the map, or the two could disagree. */
    @Test
    fun `an attribute with a field of its own is not duplicated into extra`() {
        val item = read(FakeItemData("id" to "HYPERION", "modifier" to "withered", "unknown_thing" to 3))
        assertEquals(mapOf("unknown_thing" to "3"), item.extra)
    }

    /** Nested tags are skipped: a flattened blob in a string map is not usable by anything. */
    @Test
    fun `nested tags are left out of extra rather than flattened`() {
        val item = read(FakeItemData("petInfo" to FakeItemData("type" to "ENDER_DRAGON"), "edition" to 1))
        assertEquals(mapOf("edition" to "1"), item.extra)
    }

    // ---------------------------------------------------------------
    // Acquisition and persistence
    // ---------------------------------------------------------------

    @Test
    fun `acquisition captures the context at the moment of the drop`() {
        val context = GameContext(
            isOnHypixel = true,
            isInSkyBlock = true,
            island = Island.CRIMSON_ISLE,
            subLocation = SubLocation("Kuudra's Hollow (T5)"),
            profile = SkyBlockProfile("Mango"),
        )
        val acquisition = ItemAcquisition.from(context, 1_700_000_000_000, AcquisitionSource.DROP, note = "Kuudra")

        assertEquals(Island.CRIMSON_ISLE, acquisition.island)
        assertEquals(SubLocation("Kuudra's Hollow (T5)"), acquisition.subLocation)
        assertEquals(SkyBlockProfile("Mango"), acquisition.profile)
        assertEquals(AcquisitionSource.DROP, acquisition.source)
        assertEquals("Kuudra", acquisition.note)
    }

    /**
     * The whole snapshot survives a round trip.
     *
     * This is the assertion that matters most in this file. A record of a rare drop has to
     * still be readable in eighteen months, which it can only be if nothing in the model
     * needs the game to interpret it.
     */
    @Test
    fun `a snapshot round-trips through JSON unchanged`() {
        val original = read(
            FakeItemData(
                "id" to "HYPERION",
                "uuid" to "e5f1a1c0-0000-4000-8000-000000000001",
                "modifier" to "withered",
                "upgrade_level" to 10,
                "enchantments" to FakeItemData("sharpness" to 7),
                "gems" to FakeItemData("JADE_0" to "PERFECT"),
                "edition" to 42,
            ),
            lore = listOf("§7Ability: Wither Impact", "§d§lMYTHIC DUNGEON SWORD"),
        ).copy(
            acquisition = ItemAcquisition(1_700_000_000_000, AcquisitionSource.DROP, Island.CATACOMBS),
            estimatedValue = 1_200_000_000,
        )

        val json = Json.encodeToString(SqItem.serializer(), original)
        assertEquals(original, Json.decodeFromString(SqItem.serializer(), json))
    }

    @Test
    fun `the summary reads like something worth putting in chat`() {
        val item = read(
            FakeItemData("id" to "HYPERION", "modifier" to "withered", "upgrade_level" to 10, "rarity_upgrades" to 1),
            displayName = "§dWithered Hyperion",
            lore = listOf("§d§lMYTHIC DUNGEON SWORD"),
        )
        assertEquals("Withered Hyperion [Mythic] (withered, 10★, recombobulated)", item.summary())
    }

    @Test
    fun `rarities compare in Hypixel's order`() {
        assertTrue(ItemRarity.MYTHIC.isAtLeast(ItemRarity.LEGENDARY))
        assertFalse(ItemRarity.RARE.isAtLeast(ItemRarity.EPIC))
        assertTrue(ItemRarity.ULTIMATE.isAtLeast(ItemRarity.COMMON))
    }

    /** The ids are Hypixel's and have a gap at 7, which is why they are written down. */
    @Test
    fun `the Hypixel rarity ids are not the ordinals`() {
        assertEquals(6, ItemRarity.DIVINE.hypixelId)
        assertEquals(8, ItemRarity.SPECIAL.hypixelId)
        assertEquals(7, ItemRarity.SPECIAL.ordinal)
    }
}
