package dev.th7bo.sidequest.backend

import dev.th7bo.sidequest.protocol.ProfileProgress
import dev.th7bo.sidequest.protocol.ProfileSection
import dev.th7bo.sidequest.protocol.ProfileChoice
import dev.th7bo.sidequest.protocol.ProfileCollection
import dev.th7bo.sidequest.protocol.ProfileBestiaryLocation
import dev.th7bo.sidequest.protocol.ProfileBestiaryMob
import dev.th7bo.sidequest.protocol.ProfileInventory
import dev.th7bo.sidequest.protocol.ProfileItemSlot
import dev.th7bo.sidequest.protocol.ProfileLoadout
import dev.th7bo.sidequest.protocol.ProfileMetric
import dev.th7bo.sidequest.protocol.ProfilePet
import dev.th7bo.sidequest.protocol.ProfileSkill
import dev.th7bo.sidequest.protocol.ProfileSlayer
import dev.th7bo.sidequest.protocol.ProfileSkillTree
import dev.th7bo.sidequest.protocol.ProfileSkillTreeNode
import dev.th7bo.sidequest.protocol.ProfileSkillTreeSlot
import dev.th7bo.sidequest.protocol.SkyBlockProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Failures the route can translate without exposing upstream response bodies. */
internal sealed class ProfileLookupFailure(message: String) : RuntimeException(message) {
    class NotConfigured : ProfileLookupFailure("profile lookup is not configured")
    class NotFound(message: String) : ProfileLookupFailure(message)
    class RateLimited(val retryAfterSeconds: Long?) : ProfileLookupFailure("Hypixel rate limit reached")
    class Upstream(message: String) : ProfileLookupFailure(message)
}

/**
 * The server-side profile gateway. All caches are here so every connected client shares them.
 *
 * The mutex deliberately covers a cold lookup. A profile screen is commonly opened by several friends at
 * once; allowing identical misses to fan out would spend the shared key's allowance at exactly that moment.
 */
internal class HypixelProfileService(
    private val apiKey: String?,
    private val now: () -> Long,
    private val upstream: ProfileUpstream = JdkProfileUpstream(),
) {
    private data class Cached<T>(val value: T, val expiresAt: Long)

    private val profiles = ConcurrentHashMap<String, Cached<SkyBlockProfile>>()
    private val identities = ConcurrentHashMap<String, Cached<PlayerIdentity>>()
    @Volatile private var skills: Cached<Map<String, SkillDefinition>>? = null
    @Volatile private var collections: Cached<Map<String, CollectionDefinition>>? = null
    @Volatile private var dungeonLevels: Cached<List<Double>>? = null
    private val misses = Mutex()

    suspend fun lookup(username: String, requestedProfile: String?): SkyBlockProfile {
        val key = username.lowercase() + ":" + requestedProfile.orEmpty().lowercase()
        profiles[key]?.takeIf { it.expiresAt > now() }?.let { return it.value }

        return misses.withLock {
            profiles[key]?.takeIf { it.expiresAt > now() }?.let { return@withLock it.value }
            val credential = apiKey ?: throw ProfileLookupFailure.NotConfigured()
            val identity = identity(username)
            val profileJson = upstream.get(
                "$HYPIXEL_API/v2/skyblock/profiles?uuid=${identity.uuid}",
                mapOf("API-Key" to credential),
            )
            val definitions = skillDefinitions()
            val collectionDefinitions = collectionDefinitions()
            val basic = HypixelProfileParser.parse(
                identity,
                requestedProfile,
                profileJson.body,
                definitions,
                dungeonLevelDefinitions(),
                collectionDefinitions,
            )
            val parsed = coroutineScope {
                val garden = async {
                    upstream.optionalGet(
                        "$HYPIXEL_API/v2/skyblock/garden?profile=${basic.profileId}",
                        mapOf("API-Key" to credential),
                    )
                }
                val museum = async {
                    upstream.optionalGet(
                        "$HYPIXEL_API/v2/skyblock/museum?profile=${basic.profileId}",
                        mapOf("API-Key" to credential),
                    )
                }
                basic.copy(
                    garden = garden.await()?.let(HypixelProfileParser::parseGarden).orEmpty(),
                    museum = museum.await()?.let(HypixelProfileParser::parseMuseum).orEmpty(),
                )
            }
            profiles[key] = Cached(parsed, now() + PROFILE_TTL_MILLIS)
            parsed
        }
    }

    private suspend fun identity(username: String): PlayerIdentity {
        val key = username.lowercase()
        identities[key]?.takeIf { it.expiresAt > now() }?.let { return it.value }
        val encoded = URLEncoder.encode(username, StandardCharsets.UTF_8)
        val response = upstream.get("$MINECRAFT_API/minecraft/profile/lookup/name/$encoded")
        if (response.status == 404) throw ProfileLookupFailure.NotFound("No Minecraft player named $username")
        response.requireSuccess()
        val root = parseObject(response.body)
        val uuid = root.string("id") ?: throw ProfileLookupFailure.Upstream("Minecraft returned no UUID")
        val textureProperty = upstream.optionalGet("$SESSION_API/session/minecraft/profile/$uuid?unsigned=false", emptyMap())
            ?.let { body -> runCatching { parseObject(body) }.getOrNull() }
            ?.array("properties")
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.string("name") == "textures" }
        val identity = PlayerIdentity(
            uuid = uuid,
            username = root.string("name") ?: username,
            skinTexture = textureProperty?.string("value"),
            skinSignature = textureProperty?.string("signature"),
        )
        identities[key] = Cached(identity, now() + IDENTITY_TTL_MILLIS)
        return identity
    }

    private suspend fun skillDefinitions(): Map<String, SkillDefinition> {
        skills?.takeIf { it.expiresAt > now() }?.let { return it.value }
        val response = upstream.get("$HYPIXEL_API/v2/resources/skyblock/skills")
        response.requireSuccess()
        val parsed = HypixelProfileParser.parseSkillDefinitions(response.body)
        if (parsed.isEmpty()) throw ProfileLookupFailure.Upstream("Hypixel returned no skill definitions")
        skills = Cached(parsed, now() + SKILL_TTL_MILLIS)
        return parsed
    }

    private suspend fun collectionDefinitions(): Map<String, CollectionDefinition> {
        collections?.takeIf { it.expiresAt > now() }?.let { return it.value }
        val response = upstream.get("$HYPIXEL_API/v2/resources/skyblock/collections")
        response.requireSuccess()
        val parsed = HypixelProfileParser.parseCollectionDefinitions(response.body)
        collections = Cached(parsed, now() + SKILL_TTL_MILLIS)
        return parsed
    }

    /** Catacombs and class levels are not part of Hypixel's skills resource; NEU maintains their live table. */
    private suspend fun dungeonLevelDefinitions(): List<Double> {
        dungeonLevels?.takeIf { it.expiresAt > now() }?.let { return it.value }
        val body = upstream.optionalGet(NEU_LEVELING, emptyMap())
        val costs = body?.let { runCatching { parseObject(it) }.getOrNull() }
            ?.array("catacombs").orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.doubleOrNull
        }
        var total = 0.0
        val cumulative = costs.map { cost -> total += cost; total }
        dungeonLevels = Cached(cumulative, now() + SKILL_TTL_MILLIS)
        return cumulative
    }

    private fun UpstreamResponse.requireSuccess() {
        when (status) {
            in 200..299 -> Unit
            404, 422 -> throw ProfileLookupFailure.NotFound("No SkyBlock profile found")
            429 -> throw ProfileLookupFailure.RateLimited(headers["retry-after"]?.toLongOrNull())
            401, 403 -> throw ProfileLookupFailure.Upstream("Hypixel rejected the server API key")
            else -> throw ProfileLookupFailure.Upstream("Profile service returned HTTP $status")
        }
    }

    private companion object {
        const val HYPIXEL_API = "https://api.hypixel.net"
        const val MINECRAFT_API = "https://api.minecraftservices.com"
        const val SESSION_API = "https://sessionserver.mojang.com"
        const val NEU_LEVELING =
            "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/leveling.json"
        const val PROFILE_TTL_MILLIS = 60_000L
        const val IDENTITY_TTL_MILLIS = 6 * 60 * 60 * 1_000L
        const val SKILL_TTL_MILLIS = 6 * 60 * 60 * 1_000L
    }
}

private suspend fun ProfileUpstream.optionalGet(url: String, headers: Map<String, String>): String? {
    return try {
        val response = get(url, headers)
        response.body.takeIf { response.status in 200..299 }
    } catch (_: ProfileLookupFailure) {
        null
    }
}

internal data class PlayerIdentity(
    val uuid: String,
    val username: String,
    val skinTexture: String? = null,
    val skinSignature: String? = null,
)
internal data class SkillDefinition(val name: String, val maxLevel: Int, val thresholds: List<Double>)
internal data class CollectionDefinition(val name: String, val category: String)
internal data class UpstreamResponse(val status: Int, val body: String, val headers: Map<String, String>)

internal fun interface ProfileUpstream {
    suspend fun get(url: String, headers: Map<String, String>): UpstreamResponse

    suspend fun get(url: String): UpstreamResponse = get(url, emptyMap())
}

private class JdkProfileUpstream : ProfileUpstream {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override suspend fun get(url: String, headers: Map<String, String>): UpstreamResponse =
        withContext(Dispatchers.IO) {
            val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET()
            headers.forEach(builder::header)
            try {
                val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                UpstreamResponse(
                    response.statusCode(),
                    response.body().orEmpty(),
                    response.headers().map().mapKeys { it.key.lowercase() }
                        .mapValues { it.value.firstOrNull().orEmpty() },
                )
            } catch (failure: Exception) {
                throw ProfileLookupFailure.Upstream(failure.message ?: "profile service unavailable")
            }
        }
}

/** Dynamic parser: Hypixel's profile payload is intentionally not mirrored as hundreds of brittle DTOs. */
internal object HypixelProfileParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseSkillDefinitions(body: String): Map<String, SkillDefinition> {
        val skills = parseObject(body).obj("skills") ?: return emptyMap()
        return skills.mapNotNull { (id, value) ->
            val skill = value as? JsonObject ?: return@mapNotNull null
            val levels = skill.array("levels")?.mapNotNull { level ->
                (level as? JsonObject)?.number("totalExpRequired")
            }.orEmpty()
            val max = skill.int("maxLevel") ?: levels.size
            id.uppercase() to SkillDefinition(skill.string("name") ?: id.humanName(), max, levels)
        }.toMap()
    }

    fun parseCollectionDefinitions(body: String): Map<String, CollectionDefinition> {
        val categories = parseObject(body).obj("collections") ?: return emptyMap()
        return buildMap {
            for ((categoryId, rawCategory) in categories) {
                val category = rawCategory as? JsonObject ?: continue
                val categoryName = category.string("name") ?: categoryId.humanName()
                for ((itemId, rawItem) in category.obj("items").orEmpty()) {
                    val item = rawItem as? JsonObject ?: continue
                    put(itemId, CollectionDefinition(item.string("name") ?: itemId.humanName(), categoryName))
                }
            }
        }
    }

    fun parse(
        identity: PlayerIdentity,
        requestedProfile: String?,
        body: String,
        definitions: Map<String, SkillDefinition>,
        dungeonThresholds: List<Double> = emptyList(),
        collectionDefinitions: Map<String, CollectionDefinition> = emptyMap(),
    ): SkyBlockProfile {
        val root = parseObject(body)
        if (root.boolean("success") == false) throw ProfileLookupFailure.Upstream("Hypixel refused the lookup")
        val profiles = root.array("profiles")?.mapNotNull { it as? JsonObject }.orEmpty()
        if (profiles.isEmpty()) throw ProfileLookupFailure.NotFound("${identity.username} has no SkyBlock profiles")

        val profile = if (requestedProfile == null) {
            profiles.firstOrNull { it.boolean("selected") == true } ?: profiles.first()
        } else {
            profiles.firstOrNull { it.string("cute_name")?.equals(requestedProfile, true) == true }
                ?: throw ProfileLookupFailure.NotFound("${identity.username} has no profile named $requestedProfile")
        }
        val uuid = identity.uuid.replace("-", "")
        val members = profile.obj("members").orEmpty()
        val member = (members[uuid] ?: members.entries.firstOrNull {
            it.key.replace("-", "").equals(uuid, true)
        }?.value) as? JsonObject ?: throw ProfileLookupFailure.NotFound("Player is not a member of that profile")

        val experience = member.pathObject("player_data", "experience")
        val skillRows = definitions.mapNotNull { (id, definition) ->
            val xp = experience?.number("SKILL_$id")
                ?: member.number("experience_skill_${id.lowercase()}")
                ?: return@mapNotNull null
            val level = definition.thresholds.count { xp >= it }.coerceAtMost(definition.maxLevel)
            val previous = definition.thresholds.getOrNull(level - 1) ?: 0.0
            val next = definition.thresholds.getOrNull(level)
            val progress = if (next == null || next <= previous) 1.0 else ((xp - previous) / (next - previous)).coerceIn(0.0, 1.0)
            ProfileSkill(id, definition.name, level, definition.maxLevel, xp, progress)
        }.sortedBy { it.name }

        val slayerRoot = member.pathObject("slayer", "slayer_bosses") ?: member.obj("slayer_bosses")
        val slayers = slayerRoot.orEmpty().mapNotNull { (id, raw) ->
            val value = raw as? JsonObject ?: return@mapNotNull null
            val xp = value.number("xp") ?: return@mapNotNull null
            val claimed = value.obj("claimed_levels")?.values?.count { level ->
                (level as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
            }
            val kills = value.entries.filter { it.key.startsWith("boss_kills_tier_") }
                .sumOf { (it.value as? JsonPrimitive)?.intOrNull ?: 0 }
            ProfileSlayer(id, id.humanName(), xp, claimed, kills)
        }.sortedBy { it.name }

        val dungeonTypes = member.pathObject("dungeons", "dungeon_types")
        val dungeons = dungeonTypes.orEmpty().mapNotNull { (id, raw) ->
            val value = raw as? JsonObject ?: return@mapNotNull null
            val xp = value.number("experience") ?: return@mapNotNull null
            ProfileProgress(
                id,
                id.humanName(),
                level = dungeonThresholds.count { xp >= it }.coerceAtMost(50),
                experience = xp,
                details = value.obj("tier_completions").toMetrics(limit = 16),
            )
        }.sortedBy { it.name }

        val dungeonClasses = member.pathObject("dungeons", "player_classes").orEmpty().mapNotNull { (id, raw) ->
            val value = raw as? JsonObject ?: return@mapNotNull null
            val xp = value.number("experience") ?: return@mapNotNull null
            ProfileProgress(
                id,
                id.humanName(),
                level = dungeonThresholds.count { xp >= it }.coerceAtMost(50),
                experience = xp,
            )
        }.sortedBy { it.name }

        val collections = member.obj("collection").orEmpty().mapNotNull { (id, raw) ->
            val definition = collectionDefinitions[id]
            (raw as? JsonPrimitive)?.longOrNull?.let {
                ProfileCollection(id, definition?.name ?: id.humanName(), it, definition?.category ?: collectionCategory(id))
            }
        }.sortedByDescending { it.amount }

        val pets = (member.pathObject("pets_data")?.array("pets") ?: member.array("pets")).orEmpty()
            .mapNotNull { raw ->
                val pet = raw as? JsonObject ?: return@mapNotNull null
                val type = pet.string("type") ?: return@mapNotNull null
                ProfilePet(
                    type = type,
                    name = type.humanName(),
                    rarity = pet.string("tier") ?: "UNKNOWN",
                    experience = pet.number("exp") ?: 0.0,
                    active = pet.boolean("active") == true,
                    heldItem = pet.string("heldItem"),
                    skin = pet.string("skin"),
                    candyUsed = pet.int("candyUsed") ?: 0,
                )
            }.sortedWith(compareByDescending<ProfilePet> { it.active }.thenByDescending { it.experience })

        val currencyRoot = member.obj("currencies")
        val currencies = currencyRoot
            ?.let { JsonObject(it.filterKeys { id -> id != "coin_purse" }) }
            .toMetrics(limit = 64)
            .map { metric ->
                metric.copy(name = metric.id.removePrefix("essence_").humanName())
            }
            .sortedBy { it.name }

        val mining = parseMiningSummary(member)
        val inventories = parseInventories(member)
        val bestiary = parseBestiary(member.obj("bestiary"))
        val skillTrees = parseSkillTrees(member.obj("skill_tree"))
        val loadouts = parseLoadouts(member.obj("loadout"))
        val playerStats = member.obj("player_stats")
        val kills = playerStats?.entries?.filter { it.key.startsWith("kills_") }
            ?.sumOf { (it.value as? JsonPrimitive)?.longOrNull ?: 0L }
        val deaths = playerStats?.entries?.filter { it.key.startsWith("deaths_") }
            ?.sumOf { (it.value as? JsonPrimitive)?.longOrNull ?: 0L }
        val stats = buildList {
            kills?.let { add(ProfileMetric("kills", "Kills", value = it.toDouble())) }
            deaths?.let { add(ProfileMetric("deaths", "Deaths", value = it.toDouble())) }
            member.pathObject("player_data")?.number("fishing_treasure_caught")?.let {
                add(ProfileMetric("fishing_treasure", "Fishing treasure", value = it))
            }
            member.pathObject("player_data")?.number("fastest_target_practice")?.let {
                add(ProfileMetric("target_practice", "Target practice", value = it))
            }
            member.pathObject("jacobs_contest", "medals_inv")?.number("bronze")?.let {
                add(ProfileMetric("bronze_medals", "Bronze medals", value = it))
            }
        }

        val accessory = member.obj("accessory_bag_storage")
        val profileData = member.obj("profile")
        val fairy = member.obj("fairy_soul")
        val sections = buildList {
            fun addSection(id: String, name: String, limit: Int = 48) {
                val metrics = member.obj(id).toMetrics(limit = limit)
                if (metrics.isNotEmpty()) add(ProfileSection(id, name, metrics))
            }

            addSection("nether_island_player_data", "Crimson Isle")
            addSection("experimentation", "Experimentation")
            addSection("attributes", "Attributes")
            addSection("forge", "Forge")
            member.obj("jacobs_contest")?.let(::parseFarmingSummary)?.takeIf { it.isNotEmpty() }
                ?.let { add(ProfileSection("farming_summary", "Farming progress", it)) }
            parseForagingSummary(member).takeIf { it.isNotEmpty() }
                ?.let { add(ProfileSection("foraging_summary", "Foraging progress", it)) }
            addSection("glacite_player_data", "Glacite Tunnels")
            addSection("leveling", "SkyBlock leveling")
            addSection("rift", "The Rift")
            addSection("safari", "Hunting & safari")
            addSection("shards", "Shards")
            addSection("temples", "Temples")
            addSection("trophy_fish", "Trophy fishing")
            addSection("events", "Events")
            addSection("item_data", "Item progression")

            member.obj("inventory")?.let { inventory ->
                inventory.obj("sacks_counts").toMetrics(limit = 96).takeIf { it.isNotEmpty() }
                    ?.let { add(ProfileSection("sacks", "Sacks", it.sortedByDescending { metric -> metric.value })) }
            }
            member.obj("objectives")?.let { objectives ->
                val completed = objectives.values.count {
                    (it as? JsonObject)?.string("status")?.equals("COMPLETE", ignoreCase = true) == true
                }
                add(
                    ProfileSection(
                        "objectives",
                        "Objectives",
                        listOf(
                            ProfileMetric("objectives_total", "Recorded", value = objectives.size.toDouble()),
                            ProfileMetric("objectives_complete", "Completed", value = completed.toDouble()),
                        ),
                    ),
                )
            }
            member.obj("quests")?.size?.takeIf { it > 0 }?.let {
                add(ProfileSection("quests", "Quests", listOf(ProfileMetric("quests", "Quest lines", value = it.toDouble()))))
            }
        }

        return SkyBlockProfile(
            username = identity.username,
            uuid = uuid,
            skinTexture = identity.skinTexture,
            skinSignature = identity.skinSignature,
            profileId = profile.string("profile_id").orEmpty(),
            profileName = profile.string("cute_name") ?: "Unknown",
            gameMode = profile.string("game_mode"),
            selected = profile.boolean("selected") == true,
            lastSaveMillis = member.long("last_save"),
            skyBlockLevel = member.pathObject("leveling")?.number("experience")?.div(100.0),
            purse = member.pathObject("currencies")?.number("coin_purse") ?: member.number("coin_purse"),
            bank = profile.pathObject("banking")?.number("balance"),
            firstJoinMillis = profileData?.long("first_join") ?: member.long("first_join"),
            fairySouls = fairy?.int("total_collected") ?: member.int("fairy_souls_collected"),
            fairyExchanges = fairy?.int("fairy_exchanges") ?: member.int("fairy_exchanges"),
            cookieBuffActive = profileData?.boolean("cookie_buff_active"),
            magicalPower = accessory?.int("highest_magical_power"),
            selectedPower = accessory?.string("selected_power"),
            profiles = profiles.map {
                ProfileChoice(
                    id = it.string("profile_id").orEmpty(),
                    name = it.string("cute_name") ?: "Unknown",
                    gameMode = it.string("game_mode"),
                    selected = it.boolean("selected") == true,
                )
            },
            skills = skillRows,
            slayers = slayers,
            dungeons = dungeons,
            dungeonClasses = dungeonClasses,
            collections = collections,
            pets = pets,
            inventories = inventories,
            bestiary = bestiary,
            skillTrees = skillTrees,
            loadouts = loadouts,
            currencies = currencies,
            mining = mining,
            stats = stats,
            sections = sections,
        )
    }

    fun parseGarden(body: String): List<ProfileMetric> =
        parseObject(body).obj("garden")?.let { garden ->
            buildList {
                garden.number("garden_experience")?.let { add(ProfileMetric("garden_experience", "Garden XP", value = it)) }
                garden.number("visitors_served")?.let { add(ProfileMetric("visitors_served", "Visitors served", value = it)) }
                garden.array("unlocked_plots_ids")?.size?.let { add(ProfileMetric("plots", "Unlocked plots", value = it.toDouble())) }
                garden.obj("commission_data")?.number("total_visitors")?.let { add(ProfileMetric("visitors", "Visitor requests", value = it)) }
            }
        }.orEmpty()

    fun parseMuseum(body: String): List<ProfileMetric> {
        val profile = parseObject(body).obj("profile") ?: return emptyList()
        return buildList {
            profile.number("value")?.let { add(ProfileMetric("value", "Museum value", value = it)) }
            profile.boolean("appraisal")?.let { add(ProfileMetric("appraisal", "Appraisal", text = if (it) "Unlocked" else "Locked")) }
            profile.obj("items")?.size?.let { add(ProfileMetric("items", "Donated items", value = it.toDouble())) }
            profile.array("special")?.size?.let { add(ProfileMetric("special", "Special items", value = it.toDouble())) }
        }
    }

}

private fun parseInventories(member: JsonObject): List<ProfileInventory> = buildList {
    fun addInventory(container: JsonObject?, id: String, name: String, columns: Int = 9) {
        val data = container?.obj(id)?.string("data") ?: return
        val slots = SkyBlockInventoryNbt.decode(data)
        if (slots.isNotEmpty()) add(ProfileInventory(id, name, columns, slots))
    }
    val inventory = member.obj("inventory")
    addInventory(inventory, "inv_contents", "Inventory")
    addInventory(inventory, "ender_chest_contents", "Ender Chest")
    addInventory(inventory, "inv_armor", "Armor", 4)
    addInventory(inventory, "equipment_contents", "Equipment", 4)
    addInventory(inventory, "personal_vault_contents", "Personal Vault")
    val bags = inventory?.obj("bag_contents")
    listOf(
        "talisman_bag" to "Accessory Bag", "potion_bag" to "Potion Bag",
        "fishing_bag" to "Fishing Bag", "quiver" to "Quiver",
    ).forEach { (id, name) -> addInventory(bags, id, name) }
    val backpacks = inventory?.obj("backpack_contents")
    backpacks.orEmpty().entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.forEach { (id, raw) ->
        val data = (raw as? JsonObject)?.string("data") ?: return@forEach
        SkyBlockInventoryNbt.decode(data).takeIf { it.isNotEmpty() }
            ?.let { add(ProfileInventory("backpack_$id", "Backpack ${id.toIntOrNull()?.plus(1) ?: id}", 9, it)) }
    }
}

private fun parseBestiary(root: JsonObject?): List<ProfileBestiaryLocation> {
    val kills = root?.obj("kills") ?: return emptyList()
    return kills.mapNotNull { (id, raw) ->
        val amount = (raw as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        ProfileBestiaryMob(id, id.humanName(), amount)
    }.groupBy { bestiaryLocation(it.id) }
        .map { (location, mobs) ->
            ProfileBestiaryLocation(location.lowercase().replace(' ', '_'), location, mobs.sumOf { it.kills }, mobs.sortedByDescending { it.kills })
        }.sortedByDescending { it.kills }
}

private fun bestiaryLocation(id: String): String {
    val key = id.lowercase()
    return when {
        listOf("enderman", "endermite", "dragon", "obsidian_defender", "watcher", "zealot").any(key::contains) -> "The End"
        listOf("blaze", "magma", "ghast", "pigman", "kuudra", "barbarian", "ashfang").any(key::contains) -> "Crimson Isle"
        listOf("dungeon", "crypt", "skeletor", "lost_adventurer", "shadow_assassin", "watchful_eye").any(key::contains) -> "Dungeons"
        listOf("sea_", "guardian", "squid", "shark", "jawbus", "water_hydra", "seaweed").any(key::contains) -> "Sea Creatures"
        listOf("glacite", "goblin", "automaton", "yog", "thyst", "worm", "treasure_hoarder").any(key::contains) -> "Mining Islands"
        listOf("spider", "arachne", "brood").any(key::contains) -> "Spider's Den"
        listOf("wolf", "sven", "old_wolf").any(key::contains) -> "The Park"
        listOf("rift", "vampire", "bacte", "shy", "volt").any(key::contains) -> "The Rift"
        else -> "Private Island & Hub"
    }
}

private fun parseSkillTrees(root: JsonObject?): List<ProfileSkillTree> {
    if (root == null) return emptyList()
    val nodes = root.obj("nodes") ?: return emptyList()
    return listOf("mining" to "Heart of the Mountain", "foraging" to "Heart of the Forest").mapNotNull { (id, name) ->
        val slots = (1..5).mapNotNull { slot ->
            val key = if (slot == 1) id else "${id}_$slot"
            val values = nodes.obj(key) ?: return@mapNotNull null
            val toggles = values.filterKeys { it.startsWith("toggle_") }
            val parsedNodes = values.mapNotNull node@{ (nodeId, raw) ->
                if (nodeId.startsWith("toggle_") || "selected" in nodeId) return@node null
                val level = (raw as? JsonPrimitive)?.intOrNull ?: return@node null
                ProfileSkillTreeNode(nodeId, nodeId.humanName(), level, toggles["toggle_$nodeId"]?.let { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() })
            }.sortedByDescending { it.level }
            ProfileSkillTreeSlot(slot, values.string("selected_ability")?.humanName(), parsedNodes)
        }
        if (slots.isEmpty()) null else ProfileSkillTree(id, name, root.pathObject("selected_skill_tree_slot")?.int(id) ?: 1, slots)
    }
}

private fun parseLoadouts(root: JsonObject?): List<ProfileLoadout> {
    val saved = root?.obj("loadouts") ?: return emptyList()
    fun sets(group: String, names: List<String>): Pair<Int?, Map<Int, List<ProfileItemSlot>>> {
        val container = root.obj(group) ?: return null to emptyMap()
        val equipped = container.int("equipped_set")
        val values = container.entries.mapNotNull { (_, raw) ->
            val set = raw as? JsonObject ?: return@mapNotNull null
            val id = set.int("id") ?: return@mapNotNull null
            val items = names.mapIndexedNotNull { slot, name ->
                val data = set.obj(name)?.string("data") ?: return@mapIndexedNotNull null
                SkyBlockInventoryNbt.decode(data).firstOrNull()?.copy(slot = slot)
            }
            id to items
        }.toMap()
        return equipped to values
    }
    val (equippedArmor, armor) = sets("armor", listOf("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"))
    val (equippedEquipment, equipment) = sets("equipment", listOf("EQUIPMENT_SLOT_1", "EQUIPMENT_SLOT_2", "EQUIPMENT_SLOT_3", "EQUIPMENT_SLOT_4"))
    return saved.mapNotNull { (id, raw) ->
        val value = raw as? JsonObject ?: return@mapNotNull null
        val armorId = value.int("armor_set_id")
        val equipmentId = value.int("equipment_set_id")
        ProfileLoadout(
            id = id,
            name = value.string("name") ?: "Loadout ${id.humanName()}",
            equipped = value.boolean("equipped") == true || value.boolean("selected") == true ||
                (armorId != null && armorId == equippedArmor && equipmentId == equippedEquipment),
            pet = value.string("pet") ?: value.string("pet_uuid"),
            powerStone = value.string("power_stone")?.humanName(),
            armor = armor[armorId].orEmpty(),
            equipment = equipment[equipmentId].orEmpty(),
        )
    }
}

private fun parseMiningSummary(member: JsonObject): List<ProfileMetric> {
    val core = member.obj("mining_core") ?: return emptyList()
    return buildList {
        core.number("experience")?.let { add(ProfileMetric("hotm_experience", "HOTM XP", value = it)) }
        core.number("tokens_spent")?.let { add(ProfileMetric("tokens_spent", "Tokens spent", value = it)) }
        listOf("powder_mithril" to "Mithril powder", "powder_gemstone" to "Gemstone powder", "powder_glacite" to "Glacite powder").forEach { (id, name) ->
            core.number(id)?.let { add(ProfileMetric(id, name, value = it)) }
        }
        core.obj("crystals")?.values?.mapNotNull { it as? JsonObject }?.count { it.string("state") == "FOUND" }
            ?.let { add(ProfileMetric("crystals_found", "Crystals found", value = it.toDouble())) }
    }
}

private fun parseFarmingSummary(root: JsonObject): List<ProfileMetric> = buildList {
    root.obj("medals_inv")?.let { medals ->
        listOf("bronze", "silver", "gold").forEach { id -> medals.number(id)?.let { add(ProfileMetric("${id}_medals", "${id.humanName()} medals", value = it)) } }
    }
    root.obj("perks")?.number("double_drops")?.let { add(ProfileMetric("double_drops", "Farming fortune perk", value = it)) }
    root.obj("personal_bests")?.size?.let { add(ProfileMetric("personal_bests", "Crop personal bests", value = it.toDouble())) }
    root.obj("contests")?.size?.let { add(ProfileMetric("contests", "Recorded contests", value = it.toDouble())) }
}

private fun parseForagingSummary(member: JsonObject): List<ProfileMetric> = buildList {
    val core = member.obj("foraging_core")
    val data = member.obj("foraging")
    core?.number("daily_trees_cut")?.let { add(ProfileMetric("daily_trees", "Trees cut today", value = it)) }
    core?.number("daily_logs_cut")?.let { add(ProfileMetric("daily_logs", "Logs cut today", value = it)) }
    core?.number("daily_tree_gifts")?.let { add(ProfileMetric("tree_gifts", "Tree gifts today", value = it)) }
    data?.number("level_cap")?.let { add(ProfileMetric("level_cap", "Foraging level cap", value = it)) }
    data?.obj("tree_gifts")?.size?.let { add(ProfileMetric("tree_gift_types", "Tree gift types", value = it.toDouble())) }
}

private fun collectionCategory(id: String): String = when {
    id in setOf("WHEAT", "CARROT_ITEM", "POTATO_ITEM", "PUMPKIN", "MELON", "SEEDS", "MUSHROOM_COLLECTION", "INK_SACK:3", "CACTUS", "SUGAR_CANE", "NETHER_STALK", "MUTTON", "PORK", "RAW_CHICKEN", "FEATHER", "LEATHER", "RABBIT") -> "Farming"
    id in setOf("COBBLESTONE", "COAL", "IRON_INGOT", "GOLD_INGOT", "DIAMOND", "EMERALD", "REDSTONE", "QUARTZ", "OBSIDIAN", "GLOWSTONE_DUST", "GRAVEL", "ICE", "NETHERRACK", "SAND", "ENDER_STONE", "MITHRIL_ORE", "HARD_STONE") -> "Mining"
    id in setOf("LOG", "LOG:1", "LOG:2", "LOG:3", "LOG_2", "LOG_2:1") -> "Foraging"
    id.contains("FISH") || id in setOf("INK_SACK", "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS", "CLAY_BALL", "SPONGE", "LILY_PAD") -> "Fishing"
    else -> "Combat"
}

private fun String.humanName(): String = lowercase().split('_').joinToString(" ") {
    it.replaceFirstChar(Char::uppercase)
}

private fun inventorySummary(inventory: JsonObject): List<ProfileMetric> = inventory.mapNotNull { (id, raw) ->
    if (id == "sacks_counts") return@mapNotNull null
    val value = when (raw) {
        is JsonObject -> when {
            raw.string("data")?.isNotBlank() == true -> "Available"
            else -> "${raw.size} sections"
        }
        is JsonArray -> "${raw.size} entries"
        is JsonPrimitive -> raw.doubleOrNull?.let { formatMetricNumber(it) } ?: raw.content.take(64)
    }
    ProfileMetric(id, id.humanName(), text = value)
}

private fun formatMetricNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

private fun JsonObject?.toMetrics(prefix: String = "", limit: Int): List<ProfileMetric> {
    if (this == null) return emptyList()
    val result = ArrayList<ProfileMetric>()
    fun visit(value: JsonObject, path: String, depth: Int) {
        for ((id, raw) in value) {
            if (result.size >= limit) return
            val full = if (path.isEmpty()) id else "${path}_$id"
            when {
                raw is JsonObject && depth < 4 -> visit(raw, full, depth + 1)
                raw is JsonObject -> Unit
                raw is JsonArray -> result.add(ProfileMetric(full, full.humanName(), value = raw.size.toDouble()))
                else -> {
                    val primitive = raw as? JsonPrimitive ?: continue
                    primitive.doubleOrNull?.let { result.add(ProfileMetric(full, full.humanName(), value = it)) }
                        ?: primitive.content.toBooleanStrictOrNull()?.let {
                            result.add(ProfileMetric(full, full.humanName(), text = if (it) "Yes" else "No"))
                        }
                        ?: primitive.content.takeIf { it.isNotBlank() && it.length <= 64 }?.let {
                            result.add(ProfileMetric(full, full.humanName(), text = it.humanName()))
                        }
                }
            }
        }
    }
    visit(this, prefix, 0)
    return result
}

private fun parseObject(body: String): JsonObject = try {
    Json.parseToJsonElement(body).jsonObject
} catch (_: Exception) {
    throw ProfileLookupFailure.Upstream("Profile service returned invalid data")
}

private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject
private fun JsonObject.array(name: String): JsonArray? = get(name) as? JsonArray
private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.content
private fun JsonObject.number(name: String): Double? = (get(name) as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull
private fun JsonObject.long(name: String): Long? = (get(name) as? JsonPrimitive)?.longOrNull
private fun JsonObject.boolean(name: String): Boolean? =
    (get(name) as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
private fun JsonObject.pathObject(vararg names: String): JsonObject? {
    var current: JsonObject = this
    for (name in names) current = current.obj(name) ?: return null
    return current
}
