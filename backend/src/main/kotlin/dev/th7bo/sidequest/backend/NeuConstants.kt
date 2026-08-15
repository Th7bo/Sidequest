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

    // -- Heart of the Mountain and Heart of the Forest ------------------------

    /** One line of a perk's description, and the condition it appears under. */
    data class LoreLine(val text: String, val onlyIf: String?)

    /** One perk, exactly as the layout file describes it. Every expression is left unevaluated. */
    data class Perk(
        val id: String,
        val name: String,
        val column: Int,
        val row: Int,
        val maxLevel: Int,
        val powder: String,
        val item: String,
        val cost: String,
        /** `stat`, `statBoost`, `statDuration` and friends: whatever this perk's lore interpolates. */
        val stats: Map<String, String>,
        val lore: List<LoreLine>,
    )

    /** A whole tree: its perks, the grid they sit on, and the prelude they are written against. */
    class TreeLayout(
        val scope: NeuLisp.Scope,
        val perks: Map<String, Perk>,
        val columns: Int,
        val rows: Int,
    ) {
        /**
         * The variables every expression in the file is written against.
         *
         * `level0` is the raw level, zero meaning never unlocked; `level` is that floored at one, which is
         * how the prelude's own helpers read it — `npi` treats `level0 = 0` as the locked, coal-coloured
         * state while every stat formula wants a level of at least one, so a locked perk still shows what
         * taking it would give. `potm` is Peak of the Mountain, which the pickaxe abilities scale against.
         */
        private fun variables(perk: Perk, level: Int, peak: Int) = mapOf(
            "level0" to level.coerceAtLeast(0).toDouble(),
            "level" to level.coerceAtLeast(1).toDouble(),
            "maxLevel" to perk.maxLevel.toDouble(),
            "potm" to peak.toDouble(),
        )

        /**
         * A perk's description at the level somebody actually has it.
         *
         * A line whose `onlyIf` does not hold is dropped, and so is one whose placeholder could not be
         * evaluated — showing "Grants +{stat} Mining Speed" would be worse than showing one line fewer.
         */
        fun describe(perk: Perk, level: Int, peakOfTheMountain: Int = 0): List<String> {
            val variables = variables(perk, level, peakOfTheMountain)
            val replacements = perk.stats.mapNotNull { (key, expression) ->
                scope.render(expression, variables)?.let { key to it }
            }.toMap()

            return perk.lore.mapNotNull { line ->
                if (line.onlyIf != null && !scope.holds(line.onlyIf, variables)) return@mapNotNull null
                var text = line.text
                for ((key, value) in replacements) text = text.replace("{$key}", value)
                if (PLACEHOLDER.containsMatchIn(text)) null else text
            }
        }

        /** The powder a perk costs, once its expression settles at [level]. Empty when it names none. */
        fun powderFor(perk: Perk, level: Int): String = when {
            perk.powder.isEmpty() -> ""
            !perk.powder.startsWith("(") -> perk.powder
            else -> scope.render(perk.powder, variables(perk, level, 0)).orEmpty()
        }

        /**
         * The Minecraft item the game draws a perk with, at the level somebody has it.
         *
         * The layout answers this itself — `(npi level0 maxLevel)` is coal when locked, emerald when partly
         * taken, diamond at the cap — so the icons match the real menu rather than approximating it. The
         * result is a 1.8-era name in the layout's own casing; lowercasing it and adding the namespace is
         * the whole translation, since none of the items involved were renamed by the flattening.
         */
        fun itemFor(perk: Perk, level: Int): String? {
            if (perk.item.isEmpty()) return null
            val name = if (perk.item.startsWith("(")) {
                scope.render(perk.item, variables(perk, level, 0))
            } else {
                perk.item.removePrefix(":")
            }
            return name?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' } }
                ?.let { "minecraft:" + it.lowercase() }
        }
    }

    /**
     * Reads `constants/hotmlayout.json` or `constants/hotflayout.json`.
     *
     * [root] is the object inside the file — `hotm` or `hotf`. The two files are the same shape, which is
     * why one reader serves both; the grids are not the same size, so the extent is measured rather than
     * assumed. Heart of the Mountain is seven columns by ten rows and Heart of the Forest seven by seven,
     * and hard-coding the taller of the two leaves the shorter tree floating three rows down its panel.
     */
    fun parseTreeLayout(body: String, root: String): TreeLayout? {
        val document = parseObject(body)
        val prelude = (document["prelude"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        val perksJson = (document[root] as? JsonObject)?.get("perks") as? JsonObject ?: return null

        val perks = perksJson.mapNotNull { (id, raw) ->
            val perk = raw as? JsonObject ?: return@mapNotNull null
            id to Perk(
                id = id,
                name = (perk["name"] as? JsonPrimitive)?.content ?: id.humanName(),
                column = (perk["x"] as? JsonPrimitive)?.intOrNull ?: 3,
                row = (perk["y"] as? JsonPrimitive)?.intOrNull ?: 0,
                maxLevel = (perk["maxLevel"] as? JsonPrimitive)?.intOrNull ?: 1,
                powder = (perk["powder"] as? JsonPrimitive)?.content.orEmpty(),
                item = (perk["item"] as? JsonPrimitive)?.content.orEmpty(),
                cost = (perk["cost"] as? JsonPrimitive)?.content.orEmpty(),
                stats = perk.filterKeys { it.startsWith("stat") }
                    .mapNotNull { (key, value) -> (value as? JsonPrimitive)?.content?.let { key to it } }
                    .toMap(),
                lore = (perk["lore"] as? JsonArray).orEmpty().mapNotNull { line ->
                    when (line) {
                        is JsonPrimitive -> LoreLine(line.content, null)
                        is JsonObject -> (line["text"] as? JsonPrimitive)?.content?.let {
                            LoreLine(it, (line["onlyIf"] as? JsonPrimitive)?.content)
                        }
                        else -> null
                    }
                },
            )
        }.toMap()
        if (perks.isEmpty()) return null

        return TreeLayout(
            scope = NeuLisp.scopeOf(prelude),
            perks = perks,
            columns = (perks.values.maxOf { it.column }) + 1,
            rows = (perks.values.maxOf { it.row }) + 1,
        )
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
