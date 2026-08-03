package dev.th7bo.sidequest.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Who signed in, once Discord has said so. */
public data class DiscordIdentity(
    public val userId: String,
    public val username: String,
    /** True when they are in the guild this server belongs to. The whole authorisation decision. */
    public val isInGuild: Boolean,
)

/** Why a sign-in did not produce an identity. Distinguished because they need different words. */
public enum class DiscordFailure {
    /** Discord refused the code — usually a stale or reused one. */
    BAD_CODE,

    /** Discord answered, but not with what was asked for. A misconfigured application, generally. */
    BAD_RESPONSE,

    /** Discord could not be reached at all. */
    UNREACHABLE,
}

public sealed interface DiscordResult {
    public data class Success(public val identity: DiscordIdentity) : DiscordResult
    public data class Failure(public val reason: DiscordFailure, public val detail: String) : DiscordResult
}

/**
 * Signing in with Discord, and answering the one question that matters: are they in the guild?
 *
 * **Guild membership is the whole membership list.** For a group whose people are already a Discord
 * server, that server is the only roster anybody maintains — so an allowlist beside it would be a second
 * one to forget to update, and approving each device by hand was a step that existed only because there
 * was nothing better to ask.
 *
 * The two HTTP calls are behind [http] so this is testable without Discord. Every interesting case here —
 * a member, somebody in no guilds, a stale code, an unreachable Discord — is one a real sign-in cannot be
 * made to produce on demand.
 *
 * The JDK's client rather than a library, for the reason the mod uses it too: a dependency is a thing to
 * keep patched, and this makes two requests.
 */
public class DiscordAuth(
    private val config: BackendConfig,
    /** Performs a request and returns status and body. Replaced wholesale in tests. */
    private val http: (HttpRequest) -> Pair<Int, String> = ::send,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Where to send the browser to begin.
     *
     * `identify` for who they are and `guilds` for what they are in. Deliberately not
     * `guilds.members.read`, which would also reveal their roles: this server does not care about roles,
     * and asking for a permission that is not used is asking somebody to grant something for nothing.
     */
    public fun authorizeUrl(state: String): String {
        val parameters = listOf(
            "client_id" to config.discordClientId.orEmpty(),
            "redirect_uri" to config.discordRedirectUri,
            "response_type" to "code",
            "scope" to "identify guilds",
            "state" to state,
            // Discord remembers a previous authorisation and skips the screen. For a page whose whole job
            // is "prove who you are", being sent straight through without seeing it is confusing.
            "prompt" to "consent",
        )
        return AUTHORIZE + "?" + parameters.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
    }

    /** Exchanges the code Discord sent back, and asks who it belongs to. */
    public fun identify(code: String): DiscordResult {
        val token = exchange(code) ?: return DiscordResult.Failure(
            DiscordFailure.BAD_CODE,
            "Discord would not exchange that code. It may have been used already.",
        )

        val user = get(USER_ME, token) ?: return DiscordResult.Failure(
            DiscordFailure.UNREACHABLE,
            "Discord did not answer when asked who you are.",
        )
        val userId = user["id"]?.jsonPrimitive?.contentOrNullSafe()
        val username = user["username"]?.jsonPrimitive?.contentOrNullSafe()
        if (userId == null) {
            return DiscordResult.Failure(DiscordFailure.BAD_RESPONSE, "Discord did not say who you are.")
        }

        val guilds = getArray(USER_GUILDS, token) ?: return DiscordResult.Failure(
            DiscordFailure.UNREACHABLE,
            "Discord did not answer when asked what servers you are in.",
        )
        val isInGuild = guilds.any { it.jsonObject["id"]?.jsonPrimitive?.contentOrNullSafe() == config.discordGuildId }

        return DiscordResult.Success(DiscordIdentity(userId, username ?: userId, isInGuild))
    }

    private fun exchange(code: String): String? {
        val body = listOf(
            "client_id" to config.discordClientId.orEmpty(),
            "client_secret" to config.discordClientSecret.orEmpty(),
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to config.discordRedirectUri,
        ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }

        val request = HttpRequest.newBuilder(URI.create(TOKEN))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val (status, response) = runCatching { http(request) }.getOrElse { return null }
        if (status !in 200..299) return null
        return runCatching {
            json.parseToJsonElement(response).jsonObject["access_token"]?.jsonPrimitive?.contentOrNullSafe()
        }.getOrNull()
    }

    private fun get(url: String, token: String) = runCatching {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        val (status, body) = http(request)
        if (status !in 200..299) null else json.parseToJsonElement(body).jsonObject
    }.getOrNull()

    private fun getArray(url: String, token: String) = runCatching {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        val (status, body) = http(request)
        if (status !in 200..299) null else json.parseToJsonElement(body).jsonArray
    }.getOrNull()

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()?.takeIf { it.isNotEmpty() }

    public companion object {
        private const val API = "https://discord.com/api/v10"
        private const val AUTHORIZE = "https://discord.com/oauth2/authorize"
        private const val TOKEN = "$API/oauth2/token"
        private const val USER_ME = "$API/users/@me"
        private const val USER_GUILDS = "$API/users/@me/guilds"

        private const val TIMEOUT_SECONDS = 10L

        private val client: HttpClient by lazy {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build()
        }

        /** The real thing. Split out so the class's default is a function rather than a client. */
        private fun send(request: HttpRequest): Pair<Int, String> {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            return response.statusCode() to response.body().orEmpty()
        }
    }
}
