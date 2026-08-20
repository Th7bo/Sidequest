package dev.th7bo.sidequest.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The parts of NotEnoughUpdates' `constants/` directory this server reads.
 *
 * **Why the server and not the mod.** Every one of these is a description of SkyBlock rather than of a
 * player: which items live in which sack, what a shard is called, what a Heart of the Mountain perk does at
 * level 30. Fetching them here means one download shared by everybody connected instead of one per client,
 * and it means the logic that interprets them sits in a module that has tests.
 *
 * The data is MIT-licensed, which is what makes it usable from a closed-source mod at all.
 */
internal object NeuConstants {

    // -- sacks ---------------------------------------------------------------

    /** One sack, and what Hypixel may report inside it. */
    data class Sack(val id: String, val name: String, val itemId: String, val contents: List<String>)

    /**
     * Reads `constants/sacks.json`.
     *
     * The shape is `{"sacks": {"Agronomy": {"item": …, "contents": [...]}}}` — the sack's display name is the
     * key, so the id is derived from it rather than read.
     */
    fun parseSacks(body: String): List<Sack> {
        val root = (parseObject(body)["sacks"] as? JsonObject) ?: return emptyList()
        return root.mapNotNull { (name, raw) ->
            val sack = raw as? JsonObject ?: return@mapNotNull null
            val contents = (sack["contents"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content }
            if (contents.isEmpty()) return@mapNotNull null
            Sack(
                id = name.lowercase().replace(NON_WORD, "_").trim('_'),
                name = name,
                itemId = (sack["item"] as? JsonPrimitive)?.content.orEmpty(),
                contents = contents,
            )
        }
    }

    /**
     * An item id in the one spelling both sides can be compared in.
     *
     * The two databases disagree about a single character: NotEnoughUpdates files a dyed or damaged 1.8 item
     * as `INK_SACK-3`, because a colon cannot be a filename, while Hypixel reports the same thing as
     * `INK_SACK:3`. Comparing them raw silently drops every variant item — which is most of a Fishing sack —
     * so both are folded to the same shape before they meet.
     */
    fun normaliseItemId(id: String): String = id.trim().uppercase().replace(':', '-')

    // -- attribute shards ----------------------------------------------------

    /** One attribute shard, as the Hunting Box and the bazaar name it. */
    data class Shard(
        val id: String,
        val name: String,
        val rarity: String,
        val itemId: String,
        val ability: String,
        val alignment: String,
        val family: List<String>,
    )

    /**
     * Reads `constants/attribute_shards.json`.
     *
     * Worth stating what this fixes: shards are **not** in the item database under the name Hypixel reports
     * them by. The bazaar calls one `SHARD_GROVE`, the profile calls it something similar, and the drawable
     * item is filed as `ATTRIBUTE_SHARD_NATURE_ELEMENTAL;1` — the *ability* it grants, not the shard's own
     * name. Nothing derives one from the other, so without this table every shard falls back to a generic
     * amethyst and the whole tab looks like one repeated item.
     *
     * Each shard is indexed under every name it is known by — see [index] — because which of them Hypixel
     * puts in a profile is not something to guess at.
     */
    fun parseShards(body: String): List<Shard> {
        val shards = (parseObject(body)["attributes"] as? JsonArray) ?: return emptyList()
        return shards.mapNotNull { raw ->
            val shard = raw as? JsonObject ?: return@mapNotNull null
            val internal = (shard["internalName"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val bazaar = (shard["bazaarName"] as? JsonPrimitive)?.content.orEmpty()
            val display = (shard["displayName"] as? JsonPrimitive)?.content
                ?: bazaar.removePrefix("SHARD_").humanName()
            Shard(
                id = shardKey(bazaar.ifEmpty { display }),
                name = display,
                rarity = (shard["rarity"] as? JsonPrimitive)?.content?.uppercase() ?: "COMMON",
                itemId = internal,
                ability = (shard["abilityName"] as? JsonPrimitive)?.content.orEmpty(),
                alignment = (shard["alignment"] as? JsonPrimitive)?.content.orEmpty(),
                family = (shard["family"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            )
        }
    }

    /**
     * Every name a shard might arrive under, pointing at the shard.
     *
     * Four spellings per shard, and that is deliberate rather than defensive: the profile payload keys
     * attributes one way and owned shards another, and neither is documented. Indexing the bazaar name, the
     * display name, the ability and the item id costs nothing and removes the guess entirely.
     */
    fun index(shards: List<Shard>): Map<String, Shard> = buildMap {
        for (shard in shards) {
            listOf(
                shard.id,
                shardKey(shard.name),
                shardKey(shard.ability),
                shardKey(shard.itemId.substringBefore(';')),
            ).filter { it.isNotEmpty() }.forEach { putIfAbsent(it, shard) }
        }
    }

    /**
     * The database's rarity number for a pet tier.
     *
     * Its ladder, not Hypixel's wording: pets are filed as `TIGER;3`, and the number is the position in
     * common, uncommon, rare, epic, legendary, mythic. Null for a tier it does not name.
     */
    fun petTierFor(tier: String): Int? = when (tier.trim().uppercase()) {
        "COMMON" -> 0
        "UNCOMMON" -> 1
        "RARE" -> 2
        "EPIC" -> 3
        "LEGENDARY" -> 4
        "MYTHIC" -> 5
        else -> null
    }

    /** A shard name folded to the one form all four spellings agree on. */
    fun shardKey(value: String): String = value.trim().lowercase()
        .removePrefix("attribute_shard_")
        .removePrefix("shard_")
        .replace(NON_WORD, "_")
        .trim('_')

    // -- pets ------------------------------------------------------------------

    /** A pet that does not level like the rest. Three dragons go to 200; a Bingo pet ignores rarity. */
    data class CustomPet(
        val extraLevels: List<Int> = emptyList(),
        val maxLevel: Int? = null,
        val rarityOffsets: Map<String, Int> = emptyMap(),
    )

    /** A pet's level, and how far through it the pet is. */
    data class PetLevel(val level: Int, val maxLevel: Int, val progress: Double)

    /**
     * How pet experience becomes a pet level.
     *
     * Not a formula — a ladder of per-level costs, with the pet's rarity deciding where on that ladder it
     * starts. A legendary pet skips the first twenty rungs, which is why the same experience is a very
     * different level on two pets of different rarity.
     *
     * Read rather than copied, for the usual reason: the ladder is a hundred and nineteen numbers that
     * Hypixel changes, and three pets have their own hundred on top of it.
     */
    class PetLeveling(
        private val rarityOffsets: Map<String, Int>,
        private val baseLevels: List<Int>,
        private val custom: Map<String, CustomPet>,
        /** The handful of pets the game calls something other than their id. `TYRANNOSAURUS` is `T-Rex`. */
        val displayNames: Map<String, String>,
        /** `PET_ITEM_TIER_BOOST` to `§6Tier Boost`, colour code and all. */
        val itemNames: Map<String, String>,
    ) {
        val isEmpty: Boolean get() = baseLevels.isEmpty()

        fun maxLevel(type: String): Int = custom[type.uppercase()]?.maxLevel ?: DEFAULT_MAX_LEVEL

        /**
         * The level [experience] buys a pet of this type and rarity.
         *
         * The loop is Hypixel's own: spend experience one level at a time until the next one cannot be
         * afforded. Cross-checked against both public implementations of it, which agree.
         */
        fun levelOf(type: String, rarity: String, experience: Double): PetLevel {
            val key = type.uppercase()
            val pet = custom[key]
            val maxLevel = pet?.maxLevel ?: DEFAULT_MAX_LEVEL
            val offset = pet?.rarityOffsets?.get(rarity.uppercase())
                ?: rarityOffsets[rarity.uppercase()]
                ?: 0
            val ladder = (baseLevels + pet?.extraLevels.orEmpty()).drop(offset).take(maxLevel - 1)
            if (ladder.isEmpty()) return PetLevel(1, maxLevel, 0.0)

            var remaining = experience.coerceAtLeast(0.0)
            var level = 1
            for (cost in ladder) {
                if (remaining < cost) return PetLevel(level, maxLevel, remaining / cost)
                remaining -= cost
                level++
            }
            // Running out of rungs is normally the cap: the ladder holds exactly `maxLevel - 1` of them.
            // It says `1 + ladder.size` rather than `maxLevel` anyway, so a truncated or unfamiliar table
            // reports how far it could actually count instead of asserting a level it never reached.
            return PetLevel(level.coerceAtMost(maxLevel), maxLevel, 1.0)
        }
    }

    /**
     * Reads `constants/pets.json`.
     *
     * `pet_item_display_name_to_id` is stored the way a lore line is read — name to id — and inverted here,
     * because what a profile carries is the id and what a tooltip wants is the name.
     */
    fun parsePets(body: String): PetLeveling? {
        val root = parseObject(body)
        val base = (root["pet_levels"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        if (base.isEmpty()) return null

        fun offsets(value: JsonObject?): Map<String, Int> = value.orEmpty()
            .mapNotNull { (rarity, raw) -> (raw as? JsonPrimitive)?.intOrNull?.let { rarity.uppercase() to it } }
            .toMap()

        val custom = (root["custom_pet_leveling"] as? JsonObject).orEmpty().mapNotNull { (id, raw) ->
            val pet = raw as? JsonObject ?: return@mapNotNull null
            id.uppercase() to CustomPet(
                extraLevels = (pet["pet_levels"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.intOrNull },
                maxLevel = (pet["max_level"] as? JsonPrimitive)?.intOrNull,
                rarityOffsets = offsets(pet["rarity_offset"] as? JsonObject),
            )
        }.toMap()

        val itemNames = (root["pet_item_display_name_to_id"] as? JsonObject).orEmpty()
            .mapNotNull { (name, raw) -> (raw as? JsonPrimitive)?.content?.let { it to name } }
            .toMap()

        return PetLeveling(
            rarityOffsets = offsets(root["pet_rarity_offset"] as? JsonObject),
            baseLevels = base,
            custom = custom,
            displayNames = (root["id_to_display_name"] as? JsonObject).orEmpty()
                .mapNotNull { (id, raw) -> (raw as? JsonPrimitive)?.content?.let { id.uppercase() to it } }
                .toMap(),
            itemNames = itemNames,
        )
    }

    private const val DEFAULT_MAX_LEVEL = 100

    // -- level ladders ----------------------------------------------------------

    /**
     * Cumulative experience per Catacombs level.
     *
     * Hypixel's own skills resource does not cover the dungeon, so this is the one place the table lives.
     * It shares a file with the Heart of the Mountain ladder, which is why both are read together.
     */
    fun parseDungeonLevels(body: String): List<Double> = cumulative(parseObject(body), "catacombs")

    // -- Heart of the Mountain levels -------------------------------------------

    /**
     * Cumulative experience for each Heart of the Mountain level.
     *
     * Two of its perks — the daily powder ones — scale with the tree's own level rather than their own, so
     * the level has to be known before their descriptions can be written. The file stores the cost of each
     * level rather than the running total, which is why this sums as it goes.
     */
    fun parseTreeLevels(body: String): List<Double> = cumulative(parseObject(body), "HOTM")

    private fun cumulative(root: JsonObject, name: String): List<Double> {
        val costs = (root[name] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
        var total = 0.0
        return costs.map { cost -> total += cost; total }
    }

    private val NON_WORD = Regex("[^a-z0-9]+")
    private val PLACEHOLDER = Regex("\\{[A-Za-z0-9_]+}")

    /**
     * Reads a constants file.
     *
     * Lenient about what it does not recognise, and its own rather than the profile parser's: a repository
     * that has grown a field is not a reason to refuse the file, and an unreadable one is a caller's null
     * rather than a thrown lookup failure — none of this data is required for a profile to be shown.
     */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parseObject(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    private fun String.humanName(): String = lowercase().split('_').joinToString(" ") {
        it.replaceFirstChar(Char::uppercase)
    }
}
