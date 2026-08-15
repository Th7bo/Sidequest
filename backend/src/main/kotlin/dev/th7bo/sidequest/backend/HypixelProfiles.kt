package dev.th7bo.sidequest.backend

import dev.th7bo.sidequest.protocol.ProfileProgress
import dev.th7bo.sidequest.protocol.ProfileSkill
import dev.th7bo.sidequest.protocol.SkyBlockProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            val parsed = HypixelProfileParser.parse(identity, requestedProfile, profileJson.body, definitions)
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
        val identity = PlayerIdentity(
            uuid = root.string("id") ?: throw ProfileLookupFailure.Upstream("Minecraft returned no UUID"),
            username = root.string("name") ?: username,
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
        const val PROFILE_TTL_MILLIS = 60_000L
        const val IDENTITY_TTL_MILLIS = 6 * 60 * 60 * 1_000L
        const val SKILL_TTL_MILLIS = 6 * 60 * 60 * 1_000L
    }
}

internal data class PlayerIdentity(val uuid: String, val username: String)
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
            ProfileProgress(id, id.humanName(), experience = xp)
        }.sortedBy { it.name }

        val dungeonTypes = member.pathObject("dungeons", "dungeon_types")
        val dungeons = dungeonTypes.orEmpty().mapNotNull { (id, raw) ->
            val value = raw as? JsonObject ?: return@mapNotNull null
            val xp = value.number("experience") ?: return@mapNotNull null
            ProfileProgress(id, id.humanName(), experience = xp)
        }.sortedBy { it.name }

        return SkyBlockProfile(
            username = identity.username,
            uuid = uuid,
            profileId = profile.string("profile_id").orEmpty(),
            profileName = profile.string("cute_name") ?: "Unknown",
            gameMode = profile.string("game_mode"),
            selected = profile.boolean("selected") == true,
            lastSaveMillis = member.long("last_save"),
            skyBlockLevel = member.pathObject("leveling")?.number("experience")?.div(100.0),
            purse = member.pathObject("currencies")?.number("coin_purse") ?: member.number("coin_purse"),
            bank = profile.pathObject("banking")?.number("balance"),
            skills = skillRows,
            slayers = slayers,
            dungeons = dungeons,
        )
    }

    private fun String.humanName(): String = lowercase().split('_').joinToString(" ") {
        it.replaceFirstChar(Char::uppercase)
    }
}

private fun parseObject(body: String): JsonObject = try {
    Json.parseToJsonElement(body).jsonObject
} catch (_: Exception) {
    throw ProfileLookupFailure.Upstream("Profile service returned invalid data")
}

private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject
private fun JsonObject.array(name: String): JsonArray? = get(name) as? JsonArray
private fun JsonObject.string(name: String): String? = get(name)?.jsonPrimitive?.content
private fun JsonObject.number(name: String): Double? = get(name)?.jsonPrimitive?.doubleOrNull
private fun JsonObject.int(name: String): Int? = get(name)?.jsonPrimitive?.intOrNull
private fun JsonObject.long(name: String): Long? = get(name)?.jsonPrimitive?.longOrNull
private fun JsonObject.boolean(name: String): Boolean? = get(name)?.jsonPrimitive?.content?.toBooleanStrictOrNull()
private fun JsonObject.pathObject(vararg names: String): JsonObject? {
    var current: JsonObject = this
    for (name in names) current = current.obj(name) ?: return null
    return current
}
