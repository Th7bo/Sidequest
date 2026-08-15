package dev.th7bo.sidequest.backend

import dev.th7bo.sidequest.protocol.ProfileProgress
import dev.th7bo.sidequest.protocol.ProfileSection
import dev.th7bo.sidequest.protocol.ProfileChoice
import dev.th7bo.sidequest.protocol.ProfileCollection
import dev.th7bo.sidequest.protocol.ProfileMetric
import dev.th7bo.sidequest.protocol.ProfilePet
import dev.th7bo.sidequest.protocol.ProfileSkill
import dev.th7bo.sidequest.protocol.ProfileSlayer
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
            val basic = HypixelProfileParser.parse(
                identity,
                requestedProfile,
                profileJson.body,
                definitions,
                dungeonLevelDefinitions(),
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
        val skin = upstream.optionalGet("$SESSION_API/session/minecraft/profile/$uuid?unsigned=false", emptyMap())
            ?.let { body -> runCatching { parseObject(body) }.getOrNull() }
            ?.array("properties")
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.string("name") == "textures" }
            ?.string("value")
        val identity = PlayerIdentity(uuid = uuid, username = root.string("name") ?: username, skinTexture = skin)
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

internal data class PlayerIdentity(val uuid: String, val username: String, val skinTexture: String? = null)
internal data class SkillDefinition(val name: String, val maxLevel: Int, val thresholds: List<Double>)
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

    fun parse(
        identity: PlayerIdentity,
        requestedProfile: String?,
        body: String,
        definitions: Map<String, SkillDefinition>,
        dungeonThresholds: List<Double> = emptyList(),
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
            (raw as? JsonPrimitive)?.longOrNull?.let { ProfileCollection(id, id.humanName(), it) }
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

        val mining = member.obj("mining_core").toMetrics(limit = 48)
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

            addSection("bestiary", "Bestiary")
            addSection("nether_island_player_data", "Crimson Isle")
            addSection("experimentation", "Experimentation")
            addSection("foraging", "Foraging")
            addSection("foraging_core", "Foraging core")
            addSection("attributes", "Attributes")
            addSection("forge", "Forge")
            addSection("garden_player_data", "Garden progress")
            addSection("jacobs_contest", "Jacob's contests")
            addSection("glacite_player_data", "Glacite Tunnels")
            addSection("leveling", "SkyBlock leveling")
            addSection("loadout", "Loadout")
            addSection("rift", "The Rift")
            addSection("safari", "Hunting & safari")
            addSection("shards", "Shards")
            addSection("skill_tree", "Skill trees")
            addSection("temples", "Temples")
            addSection("trophy_fish", "Trophy fishing")
            addSection("events", "Events")
            addSection("item_data", "Item progression")

            member.obj("inventory")?.let { inventory ->
                inventorySummary(inventory).takeIf { it.isNotEmpty() }
                    ?.let { add(ProfileSection("inventory", "Inventories", it)) }
                inventory.obj("sacks_counts").toMetrics(limit = 96).takeIf { it.isNotEmpty() }
                    ?.let { add(ProfileSection("sacks", "Sacks", it.sortedByDescending { metric -> metric.value })) }
            }
            member.obj("shared_inventory")?.let { inventory ->
                inventorySummary(inventory).takeIf { it.isNotEmpty() }
                    ?.let { add(ProfileSection("shared_inventory", "Shared inventories", it)) }
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
            currencies = currencies,
            mining = mining,
            stats = stats,
            sections = sections,
        )
    }

    fun parseGarden(body: String): List<ProfileMetric> =
        parseObject(body).obj("garden").toMetrics(limit = 64)

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
