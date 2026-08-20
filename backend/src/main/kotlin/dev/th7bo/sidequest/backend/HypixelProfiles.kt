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
import dev.th7bo.sidequest.protocol.ProfileAttribute
import dev.th7bo.sidequest.protocol.ProfileChocolateFactory
import dev.th7bo.sidequest.protocol.ProfileCrimsonIsle
import dev.th7bo.sidequest.protocol.ProfileDojoChallenge
import dev.th7bo.sidequest.protocol.ProfileExperiment
import dev.th7bo.sidequest.protocol.ProfileExperimentation
import dev.th7bo.sidequest.protocol.ProfileRabbitEmployee
import dev.th7bo.sidequest.protocol.ProfileKuudraTier
import dev.th7bo.sidequest.protocol.ProfileRift
import dev.th7bo.sidequest.protocol.ProfileSack
import dev.th7bo.sidequest.protocol.ProfileSackItem
import dev.th7bo.sidequest.protocol.ProfileTimecharm
import dev.th7bo.sidequest.protocol.ProfileTrophyFish
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
import kotlinx.serialization.json.JsonNull
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
    @Volatile private var items: Cached<Map<String, ItemDefinition>>? = null
    @Volatile private var neu: Cached<NeuTables>? = null
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
            val itemDefinitions = itemDefinitions()
            val neu = neuTables()
            val basic = HypixelProfileParser.parse(
                identity,
                requestedProfile,
                profileJson.body,
                definitions,
                neu.dungeonLevels,
                collectionDefinitions,
                itemDefinitions,
                neu,
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

    private suspend fun itemDefinitions(): Map<String, ItemDefinition> {
        items?.takeIf { it.expiresAt > now() }?.let { return it.value }
        val response = upstream.get("$HYPIXEL_API/v2/resources/skyblock/items")
        response.requireSuccess()
        val parsed = HypixelProfileParser.parseItemDefinitions(response.body)
        items = Cached(parsed, now() + SKILL_TTL_MILLIS)
        return parsed
    }

    /**
     * The NotEnoughUpdates tables that describe SkyBlock rather than a player.
     *
     * Fetched together and cached together, because they change on the same cadence — whenever somebody
     * updates that repository — and because a partial set is still worth having. **Every one is optional.**
     * GitHub being unreachable must degrade the sack grouping and the perk tooltips, not fail the lookup:
     * the profile itself came from Hypixel and is perfectly displayable without any of this.
     */
    private suspend fun neuTables(): NeuTables {
        neu?.takeIf { it.expiresAt > now() }?.let { return it.value }
        val tables = coroutineScope {
            val sacks = async { upstream.optionalGet(neuConstant("sacks"), emptyMap()) }
            val shards = async { upstream.optionalGet(neuConstant("attribute_shards"), emptyMap()) }
            val hotm = async { upstream.optionalGet(treeConstant(SkillTreeRepo.Tree.MINING), emptyMap()) }
            val hotf = async { upstream.optionalGet(treeConstant(SkillTreeRepo.Tree.FORAGING), emptyMap()) }
            val levels = async { upstream.optionalGet(neuConstant("leveling"), emptyMap()) }
            val pets = async { upstream.optionalGet(neuConstant("pets"), emptyMap()) }
            NeuTables(
                sacks = sacks.await()?.let { runCatching { NeuConstants.parseSacks(it) }.getOrNull() }.orEmpty(),
                shards = shards.await()?.let { runCatching { NeuConstants.parseShards(it) }.getOrNull() }.orEmpty(),
                mining = hotm.await()?.let { runCatching { SkillTreeRepo.parse(it) }.getOrNull() },
                foraging = hotf.await()?.let { runCatching { SkillTreeRepo.parse(it) }.getOrNull() },
                treeLevels = levels.await()?.let { runCatching { NeuConstants.parseTreeLevels(it) }.getOrNull() }.orEmpty(),
                dungeonLevels = levels.await()?.let { runCatching { NeuConstants.parseDungeonLevels(it) }.getOrNull() }.orEmpty(),
                pets = pets.await()?.let { runCatching { NeuConstants.parsePets(it) }.getOrNull() },
            )
        }
        neu = Cached(tables, now() + SKILL_TTL_MILLIS)
        return tables
    }

    private fun neuConstant(name: String): String = "$NEU_CONSTANTS/$name.json"

    private fun treeConstant(tree: SkillTreeRepo.Tree): String = "${SkillTreeRepo.BASE_URL}/${tree.path}"

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
        const val NEU_CONSTANTS =
            "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants"
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
/**
 * One collection, and the ladder of tiers it unlocks things at.
 *
 * The thresholds are Hypixel's own and come down with the same resource as the name, so keeping them costs
 * one field rather than another request. [tiers] is ascending and cumulative — a collection is at tier N
 * when its amount has reached the Nth threshold.
 */
internal data class CollectionDefinition(
    val name: String,
    val category: String,
    val maxTiers: Int = 0,
    val tiers: List<Long> = emptyList(),
    /** What each tier unlocked, in the same order. Only the next one is ever shown. */
    val unlocks: List<List<String>> = emptyList(),
) {
    /** The tier an amount has reached, how far into the next it is, and what that next one gives. */
    fun progressAt(amount: Long): Triple<Int, Double, List<String>> {
        if (tiers.isEmpty()) return Triple(0, 0.0, emptyList())
        val tier = tiers.count { amount >= it }
        val reached = tiers.getOrNull(tier - 1) ?: 0L
        val next = tiers.getOrNull(tier)
        val progress = when {
            next == null -> 1.0
            next <= reached -> 1.0
            else -> ((amount - reached).toDouble() / (next - reached)).coerceIn(0.0, 1.0)
        }
        return Triple(tier, progress, unlocks.getOrNull(tier).orEmpty())
    }
}
internal data class ItemDefinition(val name: String, val rarity: String)

/**
 * The NotEnoughUpdates tables a parse may use, all of them optional.
 *
 * Absent means "describe this the plain way", never "fail": a missing sack table puts every count in one
 * group, and a missing layout leaves a perk with its raw name and no description.
 */
internal data class NeuTables(
    val sacks: List<NeuConstants.Sack> = emptyList(),
    val shards: List<NeuConstants.Shard> = emptyList(),
    val mining: SkillTreeRepo.Layout? = null,
    val foraging: SkillTreeRepo.Layout? = null,
    val pets: NeuConstants.PetLeveling? = null,
    /** Cumulative experience for each Heart of the Mountain level, which two of its perks scale against. */
    val treeLevels: List<Double> = emptyList(),
    /** Cumulative experience per Catacombs level. From the same file, so it costs the same one request. */
    val dungeonLevels: List<Double> = emptyList(),
) {
    val shardsByName: Map<String, NeuConstants.Shard> by lazy { NeuConstants.index(shards) }

    fun tree(tree: SkillTreeRepo.Tree): SkillTreeRepo.Layout? =
        if (tree == SkillTreeRepo.Tree.MINING) mining else foraging
}
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
                    // Ordered by the tier number Hypixel states rather than by array position: the two agree
                    // today, and sorting costs nothing against being subtly wrong if they ever stop.
                    val tiers = item.array("tiers").orEmpty()
                        .mapNotNull { it as? JsonObject }
                        .sortedBy { it.int("tier") ?: 0 }
                    put(
                        itemId,
                        CollectionDefinition(
                            name = item.string("name") ?: itemId.humanName(),
                            category = categoryName,
                            maxTiers = item.int("maxTiers") ?: tiers.size,
                            tiers = tiers.mapNotNull { it.long("amountRequired") },
                            unlocks = tiers.map { tier ->
                                tier.array("unlocks").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                            },
                        ),
                    )
                }
            }
        }
    }

    fun parseItemDefinitions(body: String): Map<String, ItemDefinition> = parseObject(body).array("items").orEmpty()
        .mapNotNull { it as? JsonObject }
        .mapNotNull { item ->
            val id = item.string("id") ?: return@mapNotNull null
            id to ItemDefinition(item.string("name") ?: id.humanName(), item.string("tier") ?: "COMMON")
        }.toMap()

    fun parse(
        identity: PlayerIdentity,
        requestedProfile: String?,
        body: String,
        definitions: Map<String, SkillDefinition>,
        dungeonThresholds: List<Double> = emptyList(),
        collectionDefinitions: Map<String, CollectionDefinition> = emptyMap(),
        itemDefinitions: Map<String, ItemDefinition> = emptyMap(),
        neu: NeuTables = NeuTables(),
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
            val amount = (raw as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val (tier, progress, unlocks) = definition?.progressAt(amount) ?: Triple(0, 0.0, emptyList())
            ProfileCollection(
                id = id,
                name = definition?.name ?: id.humanName(),
                amount = amount,
                category = definition?.category ?: collectionCategory(id),
                tier = tier,
                maxTiers = definition?.maxTiers ?: 0,
                progress = progress,
                nextTierAt = definition?.tiers?.getOrNull(tier),
                nextUnlocks = unlocks.take(6),
            )
        }.sortedByDescending { it.amount }

        val pets = (member.pathObject("pets_data")?.array("pets") ?: member.array("pets")).orEmpty()
            .mapNotNull { raw ->
                val pet = raw as? JsonObject ?: return@mapNotNull null
                val type = pet.string("type") ?: return@mapNotNull null
                val rarity = pet.string("tier") ?: "UNKNOWN"
                val heldItem = pet.string("heldItem")
                val experience = pet.number("exp") ?: 0.0
                // A Tier Boost makes the pet behave as one rarity above what Hypixel reports, which moves
                // where it starts on the level ladder. Both public implementations of this agree on it.
                val boosted = heldItem == TIER_BOOST
                val leveling = neu.pets
                val level = leveling?.levelOf(type, if (boosted) boostedRarity(rarity) else rarity, experience)
                ProfilePet(
                    type = type,
                    name = leveling?.displayNames?.get(type.uppercase()) ?: type.humanName(),
                    rarity = rarity,
                    experience = experience,
                    active = pet.boolean("active") == true,
                    heldItem = heldItem,
                    skin = pet.string("skin"),
                    candyUsed = pet.int("candyUsed") ?: 0,
                    level = level?.level ?: 1,
                    maxLevel = level?.maxLevel ?: leveling?.maxLevel(type) ?: 100,
                    progress = level?.progress ?: 0.0,
                    heldItemName = heldItem?.let { leveling?.itemNames?.get(it) },
                    tierBoosted = boosted,
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
        val skillTrees = parseSkillTrees(member, neu)
        val loadouts = parseLoadouts(member.obj("loadout"))
        val trophyFish = parseTrophyFish(member.obj("trophy_fish"))
        val attributes = parseAttributes(member, itemDefinitions, neu)
        val sacks = parseSacks(member.pathObject("inventory", "sacks_counts"), neu)
        val rift = parseRift(member)
        val experimentation = parseExperimentation(member.obj("experimentation"))
        val chocolateFactory = parseChocolateFactory(member.obj("events"))
        val crimsonIsle = parseCrimsonIsle(member.obj("nether_island_player_data"))
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

            addSection("forge", "Forge")
            member.obj("jacobs_contest")?.let(::parseFarmingSummary)?.takeIf { it.isNotEmpty() }
                ?.let { add(ProfileSection("farming_summary", "Farming progress", it)) }
            parseForagingSummary(member).takeIf { it.isNotEmpty() }
                ?.let { add(ProfileSection("foraging_summary", "Foraging progress", it)) }
            addSection("glacite_player_data", "Glacite Tunnels")
            addSection("leveling", "SkyBlock leveling")
            addSection("safari", "Hunting & safari")
            addSection("shards", "Shards")
            addSection("temples", "Temples")
            addSection("item_data", "Item progression")

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
            sacks = sacks,
            trophyFish = trophyFish,
            attributes = attributes,
            rift = rift,
            experimentation = experimentation,
            chocolateFactory = chocolateFactory,
            crimsonIsle = crimsonIsle,
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

/**
 * Heart of the Mountain and Heart of the Forest.
 *
 * **Where the levels live is not one place.** Hypixel keeps the mining tree under `mining_core.nodes` —
 * verified against SkyCrypt, which reads `mining_core.nodes.special_0` for Peak of the Mountain — while a
 * `skill_tree` object holds the saved presets. Both are read and merged, newest-looking first, so a profile
 * that only populates one of them still produces a tree.
 *
 * **The ids do not match the layout's keys either.** NotEnoughUpdates names a perk after what the menu calls
 * it and Hypixel does not: Quick Forge arrives as `forge_time`, Core of the Mountain as `special_0`, Gem
 * Lover as `fortunate`. Eleven of the forty-six differ. [HOTM_NODE_IDS] is the bridge, and it was derived by
 * matching NotEnoughUpdates' names against SkyCrypt's published Hypixel-id table rather than written from
 * memory — the two agreed on all forty-six, with nothing left over on either side.
 *
 * The Heart of the Forest has no such published table. Its keys are assumed to match the layout's, and any
 * node Hypixel sends that no layout entry claims is still shown, with its id humanised, rather than dropped:
 * a rename upstream then looks like an oddly-named perk instead of a silently missing one.
 */
private fun parseSkillTrees(member: JsonObject, neu: NeuTables): List<ProfileSkillTree> {
    val presets = member.obj("skill_tree")

    return SkillTreeRepo.Tree.entries.mapNotNull { tree ->
        val layout = neu.tree(tree)
        val core = member.pathObject("${tree.id}_core", "nodes")
        val experience = member.pathObject("${tree.id}_core")?.number("experience") ?: 0.0
        val treeLevel = neu.treeLevels.count { experience >= it }

        val slots = (1..5).mapNotNull { slot ->
            val key = if (slot == 1) tree.id else "${tree.id}_$slot"
            val saved = presets?.obj("nodes")?.obj(key)
            val values = when {
                slot == 1 && core != null -> JsonObject(core + saved.orEmpty())
                else -> saved ?: return@mapNotNull null
            }
            ProfileSkillTreeSlot(
                slot,
                presets?.pathObject("selected_ability")?.string(key)?.humanName(),
                treeNodes(tree, layout, values, treeLevel),
            )
        }
        if (slots.isEmpty()) {
            null
        } else {
            ProfileSkillTree(
                id = tree.id,
                name = tree.displayName,
                selectedSlot = presets?.pathObject("selected_skill_tree_slot")?.int(tree.id) ?: 1,
                slots = slots,
                columns = layout?.columns ?: 7,
                rows = maxOf(
                    layout?.rows ?: 10,
                    slots.flatMap { it.nodes }.maxOfOrNull { it.row + 1 } ?: 0,
                ),
            )
        }
    }
}

private fun treeNodes(
    tree: SkillTreeRepo.Tree,
    layout: SkillTreeRepo.Layout?,
    values: JsonObject,
    treeLevel: Int,
): List<ProfileSkillTreeNode> {
    fun level(vararg keys: String): Int? = keys.firstNotNullOfOrNull { (values[it] as? JsonPrimitive)?.intOrNull }
    fun toggle(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull {
        (values["toggle_$it"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
    }

    // An ability's strength depends on the tree's core rather than on itself: taking the core at all buys
    // every ability its second level. Read from whichever spelling of the core node this payload carries.
    val coreLevel = layout?.let { level(tree.core, HOTM_NODE_IDS[tree.core].orEmpty()) } ?: 0
    val abilityLevel = if (coreLevel >= 1) 2 else 1

    val claimed = HashSet<String>()
    val fromLayout = layout?.nodes.orEmpty().map { node ->
        val hypixelId = HOTM_NODE_IDS[node.id]?.takeIf { tree == SkillTreeRepo.Tree.MINING } ?: node.id
        claimed += setOf(node.id, hypixelId, "toggle_$hypixelId", "toggle_${node.id}")
        val nodeLevel = level(node.id, hypixelId) ?: 0
        val variables = mapOf(
            "level" to nodeLevel.coerceAtLeast(1).toDouble(),
            "effectiveLevel" to abilityLevel.toDouble(),
            "hotmLevel" to treeLevel.toDouble(),
        )
        ProfileSkillTreeNode(
            id = node.id,
            name = node.name,
            level = nodeLevel,
            enabled = toggle(node.id, hypixelId),
            column = node.column,
            row = node.row,
            maxLevel = node.maxLevel,
            kind = node.kind,
            itemId = null,
            costLabel = if (nodeLevel < node.maxLevel) SkillTreeRepo.costOf(node, nodeLevel) else null,
            lore = SkillTreeRepo.describe(node, variables),
        )
    }

    // Whatever Hypixel sent that no node claimed.
    //
    // **Shown rather than dropped, and this is not a corner case.** Hypixel revamps these trees, and a
    // description of the new one takes days to appear. Until it does, an unrecognised perk is laid out in
    // rows of its own beneath the tree, where it reads as an extra strip rather than a pile on one square.
    val extraRow = layout?.rows ?: 0
    val width = (layout?.columns ?: 7).coerceAtLeast(1)
    val leftovers = values.keys
        .filter { it !in claimed && !it.startsWith("toggle_") && "selected" !in it }
        .sorted()
        .mapIndexedNotNull { index, id ->
            val nodeLevel = (values[id] as? JsonPrimitive)?.intOrNull ?: return@mapIndexedNotNull null
            ProfileSkillTreeNode(
                id = id,
                name = id.humanName(),
                level = nodeLevel,
                column = index % width,
                row = extraRow + index / width,
                maxLevel = nodeLevel.coerceAtLeast(1),
            )
        }

    return (fromLayout + leftovers).sortedWith(compareBy({ it.row }, { it.column }))
}

/**
 * NotEnoughUpdates' perk key against the id Hypixel puts in a profile, for the eleven that differ.
 *
 * **Both spellings are real and both are Hypixel's.** The live tree under `mining_core.nodes` uses the older
 * ids — Quick Forge as `forge_time`, Core of the Mountain as `special_0` — while the saved presets under
 * `skill_tree.nodes.mining` use the same display-derived names the description repositories do. Two public
 * profile viewers read one object each and neither needs a translation; this reads both and merges them, so
 * the table is what lets a perk be found whichever object carried it.
 *
 * Derived rather than remembered: the names were matched against the `hypixel id -> friendly name` table
 * SkyCrypt publishes, forty-six against forty-six with nothing left over on either side.
 */
private val HOTM_NODE_IDS = mapOf(
    "pickobulus" to "pickaxe_toss",
    "luck_of_the_cave" to "random_event",
    "quick_forge" to "forge_time",
    "sky_mall" to "daily_effect",
    "gem_lover" to "fortunate",
    "seasoned_mineman" to "mining_experience",
    "core_of_the_mountain" to "special_0",
    "speedy_mineman" to "mining_speed_2",
    "fortunate_mineman" to "mining_fortune_2",
    "warm_heart" to "warm_hearted",
    "dead_mans_chest" to "hungry_for_more",
)

/** For the test that checks the aliases still name perks the layout has. */
internal fun hotmNodeIdsForTest(): Map<String, String> = HOTM_NODE_IDS

/**
 * Sack contents, grouped by sack.
 *
 * Hypixel sends one flat map — several hundred item counts with nothing saying which sack any of them came
 * from — so the grouping comes from NotEnoughUpdates' table. Anything that table does not place still
 * appears, under "Other": a sack added to the game before that repository catches up must not make a
 * player's items vanish from their own profile.
 */
private fun parseSacks(counts: JsonObject?, neu: NeuTables): List<ProfileSack> {
    if (counts == null) return emptyList()
    val owned = counts.mapNotNull { (id, raw) ->
        val amount = (raw as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        if (amount <= 0L) null else id to amount
    }.toMap()
    if (owned.isEmpty()) return emptyList()

    val placed = HashSet<String>()
    val groups = neu.sacks.mapNotNull { sack ->
        val wanted = sack.contents.map(NeuConstants::normaliseItemId).toSet()
        val items = owned.filterKeys { NeuConstants.normaliseItemId(it) in wanted }
        if (items.isEmpty()) return@mapNotNull null
        placed += items.keys
        ProfileSack(
            id = sack.id,
            name = sack.name,
            itemId = sack.itemId,
            items = items.map { (id, amount) -> ProfileSackItem(id, id.humanName(), amount) }
                .sortedByDescending { it.amount },
        )
    }

    val other = owned.filterKeys { it !in placed }
    return if (other.isEmpty()) {
        groups
    } else {
        groups + ProfileSack(
            id = "other",
            name = "Other",
            items = other.map { (id, amount) -> ProfileSackItem(id, id.humanName(), amount) }
                .sortedByDescending { it.amount },
        )
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

private fun parseTrophyFish(root: JsonObject?): List<ProfileTrophyFish> {
    if (root == null) return emptyList()
    val tiers = setOf("bronze", "silver", "gold", "diamond")
    val counts = linkedMapOf<String, MutableMap<String, Int>>()
    for ((key, raw) in root) {
        val tier = tiers.firstOrNull { key.endsWith("_$it") } ?: continue
        val id = key.removeSuffix("_$tier")
        val amount = (raw as? JsonPrimitive)?.intOrNull ?: continue
        counts.getOrPut(id) { linkedMapOf() }[tier] = amount
    }
    return counts.map { (id, values) ->
        ProfileTrophyFish(id, id.humanName(), values["bronze"] ?: 0, values["silver"] ?: 0, values["gold"] ?: 0, values["diamond"] ?: 0)
    }.sortedByDescending { it.total }
}

/**
 * Attribute shards.
 *
 * **A shard is not in the item database under the name Hypixel reports.** The profile names one by its
 * bazaar id, and the item that can actually be drawn is filed under the *ability* the shard grants —
 * `SHARD_GROVE` against `ATTRIBUTE_SHARD_NATURE_ELEMENTAL;1`. Nothing derives one from the other. Deriving
 * `SHARD_<id>` and hoping, which is what this did before, produces a 404 for every shard in the game and
 * leaves the tab drawing the same fallback amethyst several dozen times.
 *
 * So the shard table is the source for the id, the display name and the rarity, and Hypixel's item
 * definitions are only a fallback for a shard added since that table was last updated.
 */
private fun parseAttributes(
    member: JsonObject,
    definitions: Map<String, ItemDefinition>,
    neu: NeuTables,
): List<ProfileAttribute> {
    val stacks = member.pathObject("attributes", "stacks").orEmpty().map { (id, raw) ->
        NeuConstants.shardKey(id) to ((raw as? JsonPrimitive)?.intOrNull ?: 0)
    }.toMap()
    val owned = member.pathObject("shards")?.array("owned").orEmpty().mapNotNull { raw ->
        val shard = raw as? JsonObject ?: return@mapNotNull null
        val type = shard.string("type") ?: return@mapNotNull null
        NeuConstants.shardKey(type) to (shard.int("amount_owned") ?: 0)
    }.toMap()

    return (stacks.keys + owned.keys).map { id ->
        val shard = neu.shardsByName[id]
        val fallbackId = "SHARD_${id.uppercase()}"
        val definition = definitions[fallbackId]
        ProfileAttribute(
            id = id,
            name = shard?.name ?: definition?.name?.removeSuffix(" Shard") ?: id.humanName(),
            rarity = shard?.rarity ?: definition?.rarity ?: "COMMON",
            syphoned = stacks[id] ?: 0,
            owned = owned[id] ?: 0,
            itemId = shard?.itemId ?: fallbackId,
        )
    }.sortedWith(compareBy<ProfileAttribute> { rarityOrder(it.rarity) }.thenByDescending { it.syphoned }.thenBy { it.name })
}

private fun parseRift(member: JsonObject): ProfileRift? {
    val root = member.obj("rift") ?: return null
    val stats = member.pathObject("player_stats", "rift")
    val trophies = root.pathObject("gallery")?.array("secured_trophies").orEmpty().mapNotNull { raw ->
        val trophy = raw as? JsonObject ?: return@mapNotNull null
        val id = trophy.string("type") ?: return@mapNotNull null
        ProfileTimecharm(id, RIFT_TIMECHARMS[id] ?: id.humanName(), trophy.int("visits") ?: 0, trophy.long("timestamp"))
    }
    return ProfileRift(
        lifetimeMotes = stats?.long("lifetime_motes_earned") ?: 0,
        visits = stats?.int("visits") ?: 0,
        enigmaSouls = root.pathObject("enigma")?.array("found_souls")?.size ?: 0,
        foundCats = root.pathObject("dead_cats")?.array("found_cats")?.size ?: 0,
        unlockedEyes = root.pathObject("wither_cage")?.array("killed_eyes")?.size ?: 0,
        grubberStacks = root.pathObject("castle")?.int("grubber_stacks") ?: 0,
        secondsSitting = root.pathObject("village_plaza", "lonely")?.long("seconds_sitting") ?: 0,
        timecharms = trophies,
    )
}

private fun parseExperimentation(root: JsonObject?): ProfileExperimentation? {
    if (root == null) return null
    val experiments = root.obj("experiments").orEmpty().mapNotNull { (id, raw) ->
        val value = raw as? JsonObject ?: return@mapNotNull null
        val attempts = value.int("attempts") ?: value.int("times_played") ?: 0
        val best = value.int("best_score") ?: value.int("best_score_1") ?: value.entries
            .filter { "best" in it.key }.maxOfOrNull { (it.value as? JsonPrimitive)?.intOrNull ?: 0 } ?: 0
        ProfileExperiment(id, id.humanName(), attempts, best)
    }
    return ProfileExperimentation(
        serumsDrank = root.int("serums_drank") ?: 0,
        resetsUsed = root.int("claims_resets") ?: 0,
        lastAttemptMillis = root.long("last_attempt"),
        experiments = experiments.sortedByDescending { it.bestScore },
    )
}

private fun parseChocolateFactory(events: JsonObject?): ProfileChocolateFactory? {
    val root = events?.obj("easter") ?: return null
    val employees = root.obj("employees").orEmpty().mapNotNull { (id, raw) ->
        val level = (raw as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        ProfileRabbitEmployee(id, id.humanName(), level)
    }.sortedByDescending { it.level }
    val rabbits = root.obj("rabbits").orEmpty().values.count { it is JsonPrimitive }
    val capacityLevel = root.int("rabbit_barn_capacity_level") ?: 0
    return ProfileChocolateFactory(
        chocolate = root.long("chocolate") ?: 0,
        totalChocolate = root.long("total_chocolate") ?: 0,
        prestige = root.int("chocolate_level") ?: 0,
        barnCapacity = capacityLevel * 2 + 18,
        rabbitsCollected = rabbits,
        employees = employees,
    )
}

private fun parseCrimsonIsle(root: JsonObject?): ProfileCrimsonIsle? {
    if (root == null) return null
    val kuudra = root.obj("kuudra_completed_tiers")
    val dojo = root.obj("dojo")
    return ProfileCrimsonIsle(
        selectedFaction = root.string("selected_faction")?.let(CRIMSON_FACTIONS::get),
        // Reported as-is, including a negative. Siding with one faction drives the other's standing below
        // zero, and clamping that to zero told a player they were neutral with a faction that hates them.
        mageReputation = root.int("mages_reputation") ?: 0,
        barbarianReputation = root.int("barbarians_reputation") ?: 0,
        kuudra = KUUDRA_TIERS.map { (id, name) ->
            ProfileKuudraTier(id, name, kuudra?.int(id) ?: 0, kuudra?.int("highest_wave_$id") ?: 0)
        },
        dojo = DOJO_CHALLENGES.map { (id, name) ->
            ProfileDojoChallenge(id, name, dojo?.int("dojo_points_$id") ?: -1, dojo?.int("dojo_time_$id") ?: -1)
        },
    )
}

private const val TIER_BOOST = "PET_ITEM_TIER_BOOST"

/**
 * One step up the rarity ladder, which is what a Tier Boost buys.
 *
 * Stops at mythic because nothing is above it. Whether a *particular* pet can reach mythic is a per-pet fact
 * this server has no table for — but legendary and mythic share a starting point on the level ladder, so the
 * distinction cannot change a level either way. The reported rarity is left as Hypixel sent it and the boost
 * is flagged separately, rather than claiming a rarity the pet may not have.
 */
private fun boostedRarity(rarity: String): String = when (rarity.uppercase()) {
    "COMMON" -> "UNCOMMON"
    "UNCOMMON" -> "RARE"
    "RARE" -> "EPIC"
    "EPIC" -> "LEGENDARY"
    else -> "MYTHIC"
}

private val CRIMSON_FACTIONS = mapOf("mages" to "Mage", "barbarians" to "Barbarian")
private val KUUDRA_TIERS = linkedMapOf(
    "none" to "Basic", "hot" to "Hot", "burning" to "Burning", "fiery" to "Fiery", "infernal" to "Infernal",
)
private val DOJO_CHALLENGES = linkedMapOf(
    "mob_kb" to "Force", "wall_jump" to "Stamina", "archer" to "Mastery", "snake" to "Swiftness",
    "sword_swap" to "Discipline", "fireball" to "Tenacity", "lock_head" to "Control",
)

private fun rarityOrder(rarity: String): Int = when (rarity.uppercase()) {
    "DIVINE" -> 0; "MYTHIC" -> 1; "LEGENDARY" -> 2; "EPIC" -> 3; "RARE" -> 4; "UNCOMMON" -> 5; else -> 6
}

private val RIFT_TIMECHARMS = mapOf(
    "wyldly_supreme" to "Supreme Timecharm", "chicken_n_egg" to "Chicken N Egg Timecharm",
    "mirrored" to "Mirrorverse Timecharm", "citizen" to "SkyBlock Citizen Timecharm",
    "lazy_living" to "Living Timecharm", "slime" to "Globulate Timecharm",
    "vampiric" to "Vampiric Timecharm", "mountain" to "Celestial Timecharm",
)

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
/**
 * A string field, or null when there isn't one.
 *
 * **`JsonNull` is a `JsonPrimitive`, and its content is the four-character string `"null"`.** So a field
 * Hypixel explicitly sends as null came back as text reading "null" and every caller treated it as a real
 * value. That is what made every pet in a stable draw the same animal: a pet with no skin reported its skin
 * as `"null"`, all forty of them agreed on it, and the icon cache — keyed by skin where there is one —
 * collapsed to a single entry holding whichever pet was looked up first. The active one, as it happens.
 */
private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
private fun JsonObject.number(name: String): Double? = (get(name) as? JsonPrimitive)?.doubleOrNull

/**
 * A whole number, however Hypixel chose to write it.
 *
 * **`intOrNull` alone is not enough, and the failure is silent.** SkyBlock stores most of a profile as
 * doubles, so a value that is conceptually an integer arrives as `2850.0` about as often as `2850` — and
 * `"2850.0".toIntOrNull()` is null, which every call site here turns into a default of zero. That is what
 * made a player with real Crimson Isle reputation read as having none, and it would have done the same to
 * any other count Hypixel happened to serialise with a decimal point.
 */
private fun JsonObject.int(name: String): Int? =
    (get(name) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

private fun JsonObject.long(name: String): Long? =
    (get(name) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
private fun JsonObject.boolean(name: String): Boolean? =
    (get(name) as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
private fun JsonObject.pathObject(vararg names: String): JsonObject? {
    var current: JsonObject = this
    for (name in names) current = current.obj(name) ?: return null
    return current
}
