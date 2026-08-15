package dev.th7bo.sidequest.platform.core.backend

import dev.th7bo.sidequest.platform.backend.BackendConfig
import dev.th7bo.sidequest.platform.backend.BackendState
import dev.th7bo.sidequest.platform.backend.BackendStateChangedEvent
import dev.th7bo.sidequest.platform.backend.HttpExchange
import dev.th7bo.sidequest.platform.backend.HttpMethod
import dev.th7bo.sidequest.platform.backend.HttpRequest
import dev.th7bo.sidequest.platform.backend.HttpTransport
import dev.th7bo.sidequest.platform.backend.PairingProgress
import dev.th7bo.sidequest.platform.backend.PairingStatus
import dev.th7bo.sidequest.platform.backend.StoredSession
import dev.th7bo.sidequest.protocol.AccountId
import dev.th7bo.sidequest.platform.backend.TokenStore
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.storage.OfflineQueue
import dev.th7bo.sidequest.protocol.ApiError
import dev.th7bo.sidequest.protocol.ApiErrorCode
import dev.th7bo.sidequest.protocol.ApiResult
import dev.th7bo.sidequest.protocol.DeviceId
import dev.th7bo.sidequest.protocol.Endpoints
import dev.th7bo.sidequest.protocol.EventBatch
import dev.th7bo.sidequest.protocol.EventBatchResult
import dev.th7bo.sidequest.protocol.EventPage
import dev.th7bo.sidequest.protocol.GroupState
import dev.th7bo.sidequest.protocol.PairPollRequest
import dev.th7bo.sidequest.protocol.PairPollResponse
import dev.th7bo.sidequest.protocol.PairStartRequest
import dev.th7bo.sidequest.protocol.PairStartResponse
import dev.th7bo.sidequest.protocol.PairStatus
import dev.th7bo.sidequest.protocol.Protocol
import dev.th7bo.sidequest.protocol.RealtimeMessage
import dev.th7bo.sidequest.protocol.RefreshRequest
import dev.th7bo.sidequest.protocol.ServerInfo
import dev.th7bo.sidequest.protocol.ServerTime
import dev.th7bo.sidequest.protocol.SessionList
import dev.th7bo.sidequest.protocol.SessionTokens
import dev.th7bo.sidequest.protocol.SkyBlockProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.min
import kotlin.random.Random

/**
 * All HTTP the mod does.
 *
 * The plan asks to centralise HTTP access, and this is that — but the reason to centralise it is not
 * tidiness. It is that every one of the behaviours below is easy to get slightly wrong and impossible to
 * notice: a retry without jitter, a token refresh that races itself, a 429 that is retried immediately, a
 * queued write that is dropped instead of kept. Each of those produces a mod that mostly works.
 *
 * Everything happens here so that each of them exists exactly once:
 *
 * - **authentication**, including a refresh that is serialised so two concurrent 401s do not both refresh
 * - **retries with exponential backoff and jitter**, only for errors that say they are retryable
 * - **timeouts**, per request
 * - **error mapping** from a status to an [ApiErrorCode] a caller can act on
 * - **request ids**, generated here and echoed by the server so two logs can be correlated
 * - **server-time synchronisation**, measured from the round trip of every call
 * - **protocol negotiation**, once, before anything else
 * - **offline mode with queued writes**, which is the normal case rather than the exception
 * - **rate-limit handling**, honouring what the server asked for rather than guessing
 */
public class DefaultBackendClient(
    private var config: BackendConfig,
    private val transport: HttpTransport,
    private val tokens: TokenStore,
    private val events: EventBus,
    private val log: Logger,
    /** Where writes go when the server is unreachable. Drained on the next successful call. */
    private val outbox: OfflineQueue<RealtimeMessage>,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    public var state: BackendState = if (config.isConfigured) BackendState.UNPAIRED else BackendState.NOT_CONFIGURED
        private set

    /** The clock offset, remeasured on every successful call. */
    public var serverTime: ServerTime = ServerTime.None
        private set

    private var session: SessionTokens? = null

    private var stored: StoredSession? = null

    /**
     * Who this client is, once it has paired.
     *
     * Null before pairing, which callers treat as "not yet" rather than as an error — a mod that has not been
     * paired is a supported state, and anything that needs to name itself on the wire simply does nothing
     * until it can.
     */
    public val accountId: AccountId? get() = stored?.let { AccountId(it.accountId) }

    /**
     * Serialises token refresh.
     *
     * Without it, ten concurrent requests that all see a 401 all refresh, and nine of the ten results are
     * thrown away — on a server that rotated refresh tokens, eight of them would also be invalid.
     */
    private val refreshLock = Mutex()

    /** Honours a `RATE_LIMITED` response. Nothing is attempted before this. */
    private var rateLimitedUntilMillis: Long = 0

    // -- lifecycle ---------------------------------------------------------

    /**
     * Negotiates the protocol and restores a session.
     *
     * Done once, before anything else. A client that discovered a version mismatch on its first real
     * request has already queued writes it cannot send, and unpicking that is worse than refusing to
     * connect.
     */
    public suspend fun start(): BackendState {
        if (!config.isConfigured) return transition(BackendState.NOT_CONFIGURED)

        when (val info = fetchServerInfo()) {
            is ApiResult.Failure -> return transition(BackendState.OFFLINE, info.error.message)
            is ApiResult.Success -> {
                if (!info.value.isCompatibleWithThisBuild) {
                    return transition(
                        BackendState.INCOMPATIBLE,
                        "server speaks ${info.value.minimumProtocolVersion}..${info.value.protocolVersion}, " +
                            "this build speaks ${Protocol.VERSION}",
                    )
                }
            }
        }

        val saved = tokens.load()
        if (saved == null) return transition(BackendState.UNPAIRED)
        if (saved.baseUrl != config.baseUrl) {
            // A token from another server is worthless, and keeping it would make a server change look
            // like a revocation.
            log.info { "Stored credentials belong to ${saved.baseUrl}; discarding them" }
            tokens.clear()
            return transition(BackendState.UNPAIRED)
        }

        stored = saved
        return when (refreshSession()) {
            true -> transition(BackendState.ONLINE)
            false -> state
        }
    }

    /** Replaces the configuration. A change of server drops the session with it. */
    public suspend fun reconfigure(config: BackendConfig) {
        if (this.config == config) return
        this.config = config
        session = null
        stored = null
        state = if (config.isConfigured) BackendState.UNPAIRED else BackendState.NOT_CONFIGURED
        start()
    }

    // -- pairing -----------------------------------------------------------

    /**
     * Runs the pairing flow to its end.
     *
     * Polls on the interval the *server* asked for, not one of our choosing: the server knows what it can
     * take, and a client that polled faster would be rate-limited into looking broken.
     *
     * @param onProgress called with a code to show the user, then on every change.
     */
    public suspend fun pair(
        minecraftUuid: String,
        minecraftName: String,
        onProgress: (PairingProgress) -> Unit,
    ): PairingStatus {
        if (!config.isConfigured) return PairingStatus.FAILED

        val start = post(
            Endpoints.PAIR_START,
            PairStartRequest.serializer(),
            PairStartRequest(minecraftUuid, minecraftName, config.deviceName),
            PairStartResponse.serializer(),
            authenticated = false,
        )
        val begun = when (start) {
            is ApiResult.Failure -> {
                log.warn { "Could not start pairing: ${start.error}" }
                onProgress(PairingProgress("", 0, PairingStatus.FAILED, start.error.message))
                return PairingStatus.FAILED
            }
            is ApiResult.Success -> start.value
        }

        transition(BackendState.PAIRING)
        onProgress(PairingProgress(begun.code, begun.expiresAtMillis, PairingStatus.WAITING))

        while (now() < begun.expiresAtMillis) {
            delay(begun.pollIntervalMillis)

            val poll = post(
                Endpoints.PAIR_POLL,
                PairPollRequest.serializer(),
                PairPollRequest(begun.deviceId, begun.deviceSecret),
                PairPollResponse.serializer(),
                authenticated = false,
            )
            val response = poll.valueOrNull() ?: continue

            when (response.status) {
                PairStatus.PENDING -> continue
                PairStatus.APPROVED -> {
                    val tokensGranted = response.session ?: continue
                    adopt(tokensGranted)
                    onProgress(PairingProgress(begun.code, begun.expiresAtMillis, PairingStatus.APPROVED))
                    transition(BackendState.ONLINE)
                    return PairingStatus.APPROVED
                }
                PairStatus.DENIED -> {
                    onProgress(PairingProgress(begun.code, begun.expiresAtMillis, PairingStatus.DENIED))
                    transition(BackendState.UNPAIRED)
                    return PairingStatus.DENIED
                }
                PairStatus.EXPIRED, PairStatus.UNKNOWN -> {
                    onProgress(PairingProgress(begun.code, begun.expiresAtMillis, PairingStatus.EXPIRED))
                    transition(BackendState.UNPAIRED)
                    return PairingStatus.EXPIRED
                }
            }
        }

        onProgress(PairingProgress(begun.code, begun.expiresAtMillis, PairingStatus.EXPIRED))
        transition(BackendState.UNPAIRED)
        return PairingStatus.EXPIRED
    }

    private suspend fun adopt(granted: SessionTokens) {
        session = granted
        val toStore = StoredSession(
            refreshToken = granted.refreshToken,
            accountId = granted.accountId.value,
            deviceId = granted.deviceId.value,
            baseUrl = config.baseUrl.orEmpty(),
        )
        stored = toStore
        tokens.save(toStore)
        log.info { "Paired as ${granted.accountId} (device ${granted.deviceId})" }
    }

    /** Signs out: revokes on the server if it can, and forgets the credentials either way. */
    public suspend fun signOut() {
        if (session != null) {
            post(
                Endpoints.SESSION_REVOKE,
                dev.th7bo.sidequest.protocol.RevokeRequest.serializer(),
                dev.th7bo.sidequest.protocol.RevokeRequest(),
                kotlinx.serialization.json.JsonObject.serializer(),
            )
        }
        // Forgotten even if the revoke failed. A client that kept credentials because it could not reach
        // the server to give them up is a client that stays signed in when a user asked it not to.
        session = null
        stored = null
        tokens.clear()
        transition(BackendState.UNPAIRED)
    }

    // -- the API -----------------------------------------------------------

    public suspend fun fetchServerInfo(): ApiResult<ServerInfo> =
        get(Endpoints.SERVER_INFO, ServerInfo.serializer(), authenticated = false)

    public suspend fun fetchGroup(): ApiResult<GroupState> = get(Endpoints.GROUP, GroupState.serializer())

    public suspend fun fetchSessions(): ApiResult<SessionList> = get(Endpoints.SESSIONS, SessionList.serializer())

    public suspend fun revokeDevice(deviceId: DeviceId): ApiResult<Unit> = post(
        Endpoints.SESSION_REVOKE,
        dev.th7bo.sidequest.protocol.RevokeRequest.serializer(),
        dev.th7bo.sidequest.protocol.RevokeRequest(deviceId),
        kotlinx.serialization.json.JsonObject.serializer(),
    ).let { result ->
        when (result) {
            is ApiResult.Success -> ApiResult.Success(Unit, result.requestId)
            is ApiResult.Failure -> result
        }
    }

    public suspend fun fetchEventsSince(sequence: Long): ApiResult<EventPage> =
        get("${Endpoints.EVENTS_SINCE}?since=$sequence", EventPage.serializer())

    /**
     * Sends an event, or keeps it for later.
     *
     * The whole point of the offline queue, and the reason writes go through one method: a feature calls
     * this and does not care whether the server is reachable. Recording a rare drop must not fail because
     * a homelab is rebooting.
     */
    public suspend fun submit(message: RealtimeMessage): Boolean {
        if (!state.canAttempt) {
            // Queued even when not paired. Somebody who pairs tomorrow should not have lost tonight.
            outbox.enqueue(message)
            return false
        }

        val result = postBatch(listOf(message))
        if (result is ApiResult.Success) {
            drainOutbox()
            return message.messageId in result.value.accepted
        }

        outbox.enqueue(message)
        return false
    }

    /**
     * Sends whatever has been waiting.
     *
     * Called after any successful write, so a reconnection drains the backlog without anything having to
     * notice the reconnection happened.
     *
     * Rejected entries are acknowledged rather than retried. A server that refused an event will refuse it
     * again — a permission the group revoked while it was queued does not come back — and retrying it
     * forever would block everything behind it.
     */
    public suspend fun drainOutbox(): Int {
        if (!state.canAttempt) return 0
        var sent = 0

        while (true) {
            val batch = outbox.peek()
            if (batch.isEmpty()) return sent

            val result = postBatch(batch.map { it.entry })
            if (result !is ApiResult.Success) return sent

            val handled = result.value.accepted + result.value.rejected.keys
            if (result.value.rejected.isNotEmpty()) {
                log.warn { "The server refused ${result.value.rejected.size} queued event(s): ${result.value.rejected}" }
            }
            // Matched by the queue's own ids, not the message ids, because the queue owns its ids.
            outbox.acknowledge(batch.filter { it.entry.messageId in handled }.map { it.id })
            sent += result.value.accepted.size

            // Nothing was handled, so retrying the same batch would loop. Stop and try again later.
            if (handled.isEmpty()) return sent
        }
    }

    private suspend fun postBatch(messages: List<RealtimeMessage>): ApiResult<EventBatchResult> = post(
        Endpoints.EVENTS,
        EventBatch.serializer(),
        EventBatch(messages),
        EventBatchResult.serializer(),
    )

    /** Fetches one native profile summary through the authenticated, key-holding backend. */
    public suspend fun fetchSkyBlockProfile(username: String, profile: String? = null): ApiResult<SkyBlockProfile> {
        val player = URLEncoder.encode(username, StandardCharsets.UTF_8)
        val selected = profile?.let { "&profile=" + URLEncoder.encode(it, StandardCharsets.UTF_8) }.orEmpty()
        return get(
            "${Endpoints.SKYBLOCK_PROFILE}?player=$player$selected",
            SkyBlockProfile.serializer(),
        )
    }

    // -- the request machinery ---------------------------------------------

    private suspend fun <R : Any> get(
        path: String,
        responseSerializer: KSerializer<R>,
        authenticated: Boolean = true,
    ): ApiResult<R> = exchange(HttpMethod.GET, path, null, responseSerializer, authenticated)

    private suspend fun <B : Any, R : Any> post(
        path: String,
        bodySerializer: KSerializer<B>,
        body: B,
        responseSerializer: KSerializer<R>,
        authenticated: Boolean = true,
    ): ApiResult<R> = exchange(
        HttpMethod.POST,
        path,
        json.encodeToString(bodySerializer, body),
        responseSerializer,
        authenticated,
    )

    /**
     * One logical call: retries, refresh, mapping and clock measurement.
     *
     * The retry loop is the part worth reading. It retries only what the error says is retryable, backs
     * off exponentially, and jitters — the jitter is not decoration, because without it every client that
     * dropped at the same moment retries at the same moment, and a server that fell over gets to fall
     * over again the instant it comes back.
     */
    private suspend fun <R : Any> exchange(
        method: HttpMethod,
        path: String,
        body: String?,
        responseSerializer: KSerializer<R>,
        authenticated: Boolean,
    ): ApiResult<R> {
        val base = config.baseUrl ?: return ApiResult.failure(ApiErrorCode.UNAVAILABLE, "no backend configured")

        if (now() < rateLimitedUntilMillis) {
            // The server asked us to wait. Sending anyway is how a client that is being rate-limited stays
            // rate-limited.
            return ApiResult.failure(ApiErrorCode.RATE_LIMITED, "waiting out a rate limit")
        }

        var attempt = 0
        var refreshed = false

        while (true) {
            val requestId = UUID.randomUUID().toString()
            val request = HttpRequest(
                method = method,
                url = base.trimEnd('/') + path,
                headers = buildMap {
                    put("Content-Type", "application/json")
                    put("Accept", "application/json")
                    put(Protocol.VERSION_HEADER, Protocol.VERSION.toString())
                    put(Protocol.REQUEST_ID_HEADER, requestId)
                    if (authenticated) session?.accessToken?.let { put("Authorization", "Bearer $it") }
                },
                body = body,
            )

            val sentAt = now()
            val exchange = transport.send(request)
            val receivedAt = now()

            when (exchange) {
                is HttpExchange.Failure -> {
                    val code = if (exchange.isTimeout) ApiErrorCode.TIMEOUT else ApiErrorCode.UNAVAILABLE
                    if (attempt < MAX_ATTEMPTS - 1) {
                        attempt++
                        delay(backoffMillis(attempt))
                        continue
                    }
                    transition(BackendState.OFFLINE, exchange.reason)
                    return ApiResult.failure(code, exchange.reason)
                }

                is HttpExchange.Response -> {
                    measureClock(exchange, sentAt, receivedAt)

                    if (exchange.isSuccess) {
                        if (state == BackendState.OFFLINE) transition(BackendState.ONLINE)
                        return decode(exchange.body, responseSerializer, requestId)
                    }

                    val error = parseError(exchange, requestId)

                    // A single refresh-and-retry on a 401. Once, not in a loop: a token that is refused
                    // again after a successful refresh means something else is wrong.
                    if (error.code == ApiErrorCode.UNAUTHENTICATED && authenticated && !refreshed) {
                        refreshed = true
                        if (refreshSession()) continue
                        return ApiResult.Failure(error)
                    }

                    if (error.code == ApiErrorCode.DEVICE_REVOKED) {
                        // Terminal. The credentials are dropped so nothing keeps trying them.
                        log.warn { "This device has been revoked; forgetting its credentials" }
                        session = null
                        stored = null
                        tokens.clear()
                        transition(BackendState.REVOKED)
                        return ApiResult.Failure(error)
                    }

                    if (error.code == ApiErrorCode.PROTOCOL_MISMATCH) {
                        transition(BackendState.INCOMPATIBLE, error.message)
                        return ApiResult.Failure(error)
                    }

                    if (error.code == ApiErrorCode.RATE_LIMITED) {
                        // Honour what the server asked for rather than guessing. It knows what it can take.
                        val wait = error.retryAfterMillis ?: DEFAULT_RATE_LIMIT_WAIT_MILLIS
                        rateLimitedUntilMillis = now() + wait
                        return ApiResult.Failure(error)
                    }

                    if (error.code.isRetryable && attempt < MAX_ATTEMPTS - 1) {
                        attempt++
                        delay(backoffMillis(attempt))
                        continue
                    }
                    return ApiResult.Failure(error)
                }
            }
        }
    }

    /** Exchanges the refresh token for a new access token. Serialised; see [refreshLock]. */
    private suspend fun refreshSession(): Boolean = refreshLock.withLock {
        val refreshToken = stored?.refreshToken ?: session?.refreshToken ?: return false

        // Deliberately not through `exchange`: that would refresh on a 401 and recurse.
        val request = HttpRequest(
            method = HttpMethod.POST,
            url = config.baseUrl!!.trimEnd('/') + Endpoints.TOKEN_REFRESH,
            headers = mapOf(
                "Content-Type" to "application/json",
                Protocol.VERSION_HEADER to Protocol.VERSION.toString(),
            ),
            body = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken)),
        )

        when (val exchange = transport.send(request)) {
            is HttpExchange.Failure -> {
                transition(BackendState.OFFLINE, exchange.reason)
                return false
            }
            is HttpExchange.Response -> {
                if (!exchange.isSuccess) {
                    log.warn { "Refresh was refused; this device is no longer paired" }
                    session = null
                    stored = null
                    tokens.clear()
                    transition(BackendState.REVOKED)
                    return false
                }
                val granted = runCatching {
                    json.decodeFromString(SessionTokens.serializer(), exchange.body)
                }.getOrNull() ?: return false
                session = granted
                stored = stored?.copy(refreshToken = granted.refreshToken)
                stored?.let { tokens.save(it) }
                if (state != BackendState.ONLINE) transition(BackendState.ONLINE)
                return true
            }
        }
    }

    /**
     * Measures the clock offset from the round trip.
     *
     * Every response carries the server's time, so this costs nothing and improves with every call. Not
     * applied when the round trip was slow — a correction derived mostly from network noise is worse than
     * no correction.
     */
    private fun measureClock(response: HttpExchange.Response, sentAt: Long, receivedAt: Long) {
        val serverMillis = response.headers[Protocol.SERVER_TIME_HEADER]?.toLongOrNull() ?: return
        val measured = ServerTime.estimate(sentAt, serverMillis, receivedAt)
        if (measured.isUsable) serverTime = measured
    }

    private fun <R : Any> decode(body: String, serializer: KSerializer<R>, requestId: String): ApiResult<R> =
        runCatching { ApiResult.Success(json.decodeFromString(serializer, body), requestId) }
            .getOrElse { thrown ->
                // A response we cannot read is the server's problem, not the network's, and retrying will
                // produce the same unreadable answer.
                log.error(thrown) { "Could not read a response from the backend" }
                ApiResult.failure(ApiErrorCode.BAD_REQUEST, "unreadable response")
            }

    /**
     * Maps a failed response to something a caller can act on.
     *
     * The body is tried first, because the server sends a code that is more specific than the status —
     * a 403 could be a revoked device or a permission, and those want different behaviour. The status is
     * the fallback for a proxy's error page, which will not be one of ours.
     */
    private fun parseError(response: HttpExchange.Response, requestId: String): ApiError {
        runCatching { json.decodeFromString(ApiError.serializer(), response.body) }
            .getOrNull()
            ?.let { return it.copy(requestId = it.requestId ?: requestId) }

        val code = when (response.status) {
            400 -> ApiErrorCode.BAD_REQUEST
            401 -> ApiErrorCode.UNAUTHENTICATED
            403 -> ApiErrorCode.FORBIDDEN
            404 -> ApiErrorCode.NOT_FOUND
            429 -> ApiErrorCode.RATE_LIMITED
            in 500..599 -> ApiErrorCode.INTERNAL
            else -> ApiErrorCode.INTERNAL
        }
        return ApiError(code, "HTTP ${response.status}", requestId = requestId)
    }

    /**
     * Exponential backoff with jitter.
     *
     * The jitter matters more than the exponent. Without it every client that dropped at the same moment
     * retries at the same moment, and a server that fell over under load gets the same load again the
     * instant it recovers.
     */
    private fun backoffMillis(attempt: Int): Long {
        val exponential = min(BASE_BACKOFF_MILLIS shl (attempt - 1), MAX_BACKOFF_MILLIS)
        return exponential / 2 + random.nextLong(exponential / 2 + 1)
    }

    private fun transition(next: BackendState, detail: String? = null): BackendState {
        if (state == next) return state
        val previous = state
        state = next
        log.info { "Backend: $previous -> $next" + (detail?.let { " ($it)" } ?: "") }
        events.post(BackendStateChangedEvent(previous, next, detail), EventSource.DERIVED)
        return next
    }

    /** The access token, for the realtime connection. Null when not authenticated. */
    internal fun accessTokenOrNull(): String? = session?.accessToken

    internal fun baseUrlOrNull(): String? = config.baseUrl

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BASE_BACKOFF_MILLIS = 500L
        const val MAX_BACKOFF_MILLIS = 8_000L

        /** Used when a rate-limited response did not say how long to wait. */
        const val DEFAULT_RATE_LIMIT_WAIT_MILLIS = 10_000L
    }
}
