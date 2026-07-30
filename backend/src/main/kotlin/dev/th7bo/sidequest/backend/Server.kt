package dev.th7bo.sidequest.backend

import dev.th7bo.sidequest.platform.permission.GroupRole
import dev.th7bo.sidequest.protocol.AccountId
import dev.th7bo.sidequest.protocol.ApiError
import dev.th7bo.sidequest.protocol.ApiErrorCode
import dev.th7bo.sidequest.protocol.DeviceId
import dev.th7bo.sidequest.protocol.DeviceSummary
import dev.th7bo.sidequest.protocol.Endpoints
import dev.th7bo.sidequest.protocol.EventBatch
import dev.th7bo.sidequest.protocol.EventBatchResult
import dev.th7bo.sidequest.protocol.EventPage
import dev.th7bo.sidequest.protocol.GroupMember
import dev.th7bo.sidequest.protocol.GroupState
import dev.th7bo.sidequest.protocol.PairApproveRequest
import dev.th7bo.sidequest.protocol.PairPollRequest
import dev.th7bo.sidequest.protocol.PairStartRequest
import dev.th7bo.sidequest.protocol.Protocol
import dev.th7bo.sidequest.protocol.RealtimeAck
import dev.th7bo.sidequest.protocol.RealtimeFrame
import dev.th7bo.sidequest.protocol.RealtimeHello
import dev.th7bo.sidequest.protocol.RealtimeMessage
import dev.th7bo.sidequest.protocol.RealtimeWelcome
import dev.th7bo.sidequest.protocol.RefreshRequest
import dev.th7bo.sidequest.protocol.RevokeRequest
import dev.th7bo.sidequest.protocol.ServerInfo
import dev.th7bo.sidequest.protocol.SessionList
import dev.th7bo.sidequest.protocol.TokenScope
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Everything the server is made of, assembled.
 *
 * A class rather than loose functions so a test can build one against a temporary directory and drive it
 * through Ktor's test host. A server whose only entry point is `main` is a server that can only be
 * tested by starting it.
 */
public class SidequestBackend(
    public val config: BackendConfig,
    private val now: () -> Long = System::currentTimeMillis,
) {

    public val store: ServerStore = ServerStore(config.statePath)

    public val auth: AuthService = AuthService(store, config, now)

    public val hub: RealtimeHub = RealtimeHub(store)

    private val rateLimiter = RateLimiter(config.requestsPerMinute, now)

    private val logger = org.slf4j.LoggerFactory.getLogger(SidequestBackend::class.java)

    /**
     * The JSON configuration, shared by HTTP and the WebSocket.
     *
     * One instance, deliberately. Two configurations that differ — one lenient about unknown keys and one
     * not — is a protocol that works over one transport and not the other, and the difference shows up
     * only when somebody adds a field.
     */
    public val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    /** Installs everything into a Ktor application. */
    public fun install(application: Application) {
        application.install(ContentNegotiation) { json(json) }
        application.install(WebSockets) {
            // The server pings, so a connection through a home router that drops idle TCP is noticed
            // rather than silently dead.
            pingPeriodMillis = 30.seconds.inWholeMilliseconds
            timeoutMillis = 60.seconds.inWholeMilliseconds
        }
        application.install(StatusPages) {
            exception<Throwable> { call, cause ->
                // Never the exception's message. A stack trace or an internal path in a client's chat is
                // how implementation detail leaks out of a system.
                logger.error("Unhandled failure on ${call.request.local.uri}", cause)
                call.fail(HttpStatusCode.InternalServerError, ApiErrorCode.INTERNAL, "internal error")
            }
        }
        application.routing { routes() }
    }

    private fun io.ktor.server.routing.Route.routes() {
        // -- unauthenticated ------------------------------------------------

        get(Endpoints.SERVER_INFO) {
            call.respondWithTime(
                ServerInfo(
                    protocolVersion = Protocol.VERSION,
                    minimumProtocolVersion = Protocol.MINIMUM_VERSION,
                    serverVersion = SERVER_VERSION,
                    timeMillis = now(),
                    pairingEnabled = config.operatorToken != null,
                ),
            )
        }

        post(Endpoints.PAIR_START) {
            val request = call.receive<PairStartRequest>()
            if (!call.checkProtocol(request.protocolVersion)) return@post
            if (config.operatorToken == null) {
                // Nobody can approve, so starting is pointless. Said plainly rather than leaving a client
                // polling a code that can never be approved.
                call.fail(HttpStatusCode.Forbidden, ApiErrorCode.FORBIDDEN, "pairing is disabled")
                return@post
            }
            if (!rateLimiter.allow("pair:${call.clientKey()}")) {
                call.fail(HttpStatusCode.TooManyRequests, ApiErrorCode.RATE_LIMITED, "slow down")
                return@post
            }
            call.respondWithTime(auth.startPairing(request))
        }

        post(Endpoints.PAIR_POLL) {
            val request = call.receive<PairPollRequest>()
            // Rate limited per device, because polling is a loop by design and a client with a bug in its
            // interval handling would otherwise poll as fast as the network allows.
            if (!rateLimiter.allow("poll:${request.deviceId.value}")) {
                call.fail(HttpStatusCode.TooManyRequests, ApiErrorCode.RATE_LIMITED, "slow down")
                return@post
            }
            call.respondWithTime(auth.pollPairing(request.deviceId, request.deviceSecret))
        }

        // -- operator -------------------------------------------------------

        post(Endpoints.PAIR_APPROVE) {
            // Deliberately *not* the ordinary token path. Approving is what binds a device to an account,
            // so it is the one thing a game client must never be able to do.
            val operator = auth.authenticateOperator(call.bearerToken())
            if (operator == null) {
                call.fail(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHENTICATED, "operator token required")
                return@post
            }
            val request = call.receive<PairApproveRequest>()
            ensureAccount(request.accountId)
            if (auth.approvePairing(request)) {
                call.respondWithTime(mapOf("ok" to true))
            } else {
                call.fail(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND, "no such pairing code")
            }
        }

        // -- session --------------------------------------------------------

        post(Endpoints.TOKEN_REFRESH) {
            val request = call.receive<RefreshRequest>()
            val tokens = auth.refresh(request.refreshToken)
            if (tokens == null) {
                // Revoked and unknown are the same answer here, on purpose: distinguishing them tells a
                // caller whether a token they hold was ever real.
                call.fail(HttpStatusCode.Unauthorized, ApiErrorCode.DEVICE_REVOKED, "refresh rejected")
                return@post
            }
            call.respondWithTime(tokens)
        }

        post(Endpoints.SESSION_REVOKE) {
            val caller = call.authenticated() ?: return@post
            val request = call.receive<RevokeRequest>()
            val target = request.deviceId ?: caller.deviceId

            // A device may revoke itself; revoking somebody else's needs administration. Without that
            // check, one compromised client could sign out the whole group.
            val owns = store.read { state -> state.devices.any { it.id == target && it.accountId == caller.accountId } }
            if (!owns && !caller.hasScope(TokenScope.ADMINISTER)) {
                call.fail(HttpStatusCode.Forbidden, ApiErrorCode.FORBIDDEN, "not your device")
                return@post
            }
            if (auth.revoke(target)) {
                hub.unregister(target)
                call.respondWithTime(mapOf("ok" to true))
            } else {
                call.fail(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND, "no such device")
            }
        }

        get(Endpoints.SESSIONS) {
            val caller = call.authenticated() ?: return@get
            val devices = store.read { state ->
                state.devices
                    .filter { it.accountId == caller.accountId && !it.isRevoked }
                    .map { device ->
                        DeviceSummary(
                            deviceId = device.id,
                            deviceName = device.deviceName,
                            minecraftName = device.minecraftName,
                            pairedAtMillis = device.pairedAtMillis,
                            lastSeenAtMillis = device.lastSeenAtMillis,
                            scopes = device.scopes,
                            isCurrent = device.id == caller.deviceId,
                        )
                    }
            }
            call.respondWithTime(SessionList(devices))
        }

        // -- the group ------------------------------------------------------

        get(Endpoints.GROUP) {
            val caller = call.authenticated(TokenScope.READ) ?: return@get
            call.respondWithTime(groupState(caller.accountId))
        }

        post(Endpoints.EVENTS) {
            val caller = call.authenticated(TokenScope.WRITE) ?: return@post
            val batch = call.receive<EventBatch>()
            if (!call.checkProtocol(batch.protocolVersion)) return@post
            call.respondWithTime(acceptBatch(caller, batch))
        }

        get(Endpoints.EVENTS_SINCE) {
            val caller = call.authenticated(TokenScope.READ) ?: return@get
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
            val (events, gap) = store.eventsSince(since)
            val permissions = store.read { it.permissions }
            call.respondWithTime(
                EventPage(
                    // Filtered on the way out, exactly as the fan-out does. A history endpoint that
                    // returned everything would be a way around the privacy model.
                    messages = events.filter { visibleTo(it, caller.accountId, permissions) },
                    currentSequence = store.read { it.sequence },
                    resumeGap = gap,
                ),
            )
        }

        // -- realtime -------------------------------------------------------

        webSocket(Endpoints.REALTIME) {
            /*
             * The token arrives in a query parameter rather than a header.
             *
             * Not a preference: browser WebSocket clients cannot set headers on the handshake, and the
             * dashboard is a browser client. A token in a query string is logged by proxies, which is why
             * access tokens are short-lived and why this one cannot refresh itself — a leaked one expires.
             */
            val caller = auth.authenticate(call.request.queryParameters["token"])
            if (caller == null || !caller.hasScope(TokenScope.REALTIME)) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "unauthenticated"))
                return@webSocket
            }

            val hello = runCatching {
                val frame = incoming.receive() as? Frame.Text ?: return@runCatching null
                json.decodeFromString(RealtimeFrame.serializer(), frame.readText()) as? RealtimeFrame.Hello
            }.getOrNull()?.hello ?: RealtimeHello()

            if (!Protocol.isCompatible(hello.protocolVersion)) {
                send(errorFrame(ApiErrorCode.PROTOCOL_MISMATCH, "protocol ${hello.protocolVersion} unsupported"))
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "protocol mismatch"))
                return@webSocket
            }

            val connection = hub.register(Connection(caller.accountId, caller.deviceId))
            val (missed, gap) = store.eventsSince(hello.resumeFromSequence)

            send(
                textFrame(
                    RealtimeFrame.Welcome(
                        RealtimeWelcome(
                            protocolVersion = Protocol.VERSION,
                            accountId = caller.accountId,
                            currentSequence = store.read { it.sequence },
                            resumeGap = gap,
                        ),
                    ),
                ),
            )

            // The replay goes out before anything new, so the client sees history in order. Filtered the
            // same way live traffic is — a resume must not be a way around the privacy model.
            val permissions = store.read { it.permissions }
            for (message in missed.filter { visibleTo(it, caller.accountId, permissions) }) {
                send(textFrame(RealtimeFrame.Message(message)))
            }

            // Outbound and inbound run concurrently. A single loop would mean a client that sends nothing
            // receives nothing, because it would be blocked on `incoming.receive()`.
            val writer = launch {
                connection.outbound.consumeEach { message ->
                    send(textFrame(RealtimeFrame.Message(message)))
                }
            }

            try {
                while (isActive) {
                    val frame = incoming.receive() as? Frame.Text ?: continue
                    handleFrame(caller, frame.readText())
                }
            } catch (_: Exception) {
                // A closed connection arrives as an exception from `receive`. Normal, not an error.
            } finally {
                writer.cancel()
                hub.unregister(caller.deviceId)
            }
        }
    }

    // -- request handling --------------------------------------------------

    private fun handleFrame(caller: Caller, text: String) {
        val frame = runCatching { json.decodeFromString(RealtimeFrame.serializer(), text) }.getOrNull() ?: return
        when (frame) {
            is RealtimeFrame.Message -> {
                if (!caller.hasScope(TokenScope.WRITE)) return
                submit(caller, frame.message)
            }
            // Acknowledgements are accepted and recorded nowhere yet: the delivery guarantee is
            // at-least-once, so nothing depends on them. The frame exists so a client can start sending
            // them before the server starts caring.
            is RealtimeFrame.Ack -> Unit
            is RealtimeFrame.KeepAlive -> Unit
            else -> Unit
        }
    }

    /**
     * Accepts one event, or drops it.
     *
     * Three things happen here and each of them is a rule the client cannot be trusted with. The sender is
     * *stamped* by the server; the permission is *checked* by the server; the sequence is *assigned* by
     * the server. A client that could set any of those three could impersonate somebody, act beyond its
     * permissions, or rewrite the order of history.
     */
    private fun submit(caller: Caller, message: RealtimeMessage): ApiErrorCode? {
        val permissions = store.read { it.permissions }

        // A capability-scoped event has to be one the sender may perform. A disclosure-scoped one is about
        // themselves, so there is nothing to authorise — what it may contain is decided at fan-out.
        if (message.scope.kind == dev.th7bo.sidequest.platform.permission.PermissionKind.CAPABILITY &&
            !permissions.can(caller.accountId.value, message.scope)
        ) {
            logger.info("Rejected {} from {}: no permission", message.payload::class.simpleName, caller.accountId)
            return ApiErrorCode.FORBIDDEN
        }

        val device = store.read { state -> state.devices.firstOrNull { it.id == caller.deviceId } }
        val stamped = message.copy(
            // Overwritten, not defaulted. Whatever the client put here is discarded.
            senderAccount = caller.accountId,
            senderMinecraftUuid = device?.minecraftUuid,
            protocolVersion = Protocol.VERSION,
        )

        val appended = store.append(stamped)
        hub.fanOut(appended, from = caller.deviceId)
        return null
    }

    private fun acceptBatch(caller: Caller, batch: EventBatch): EventBatchResult {
        val accepted = ArrayList<String>()
        val rejected = HashMap<String, ApiErrorCode>()

        for (message in batch.messages) {
            // A batch is not all-or-nothing. One event whose permission the group revoked since it was
            // queued must not block the other ninety-nine.
            when (val failure = submit(caller, message)) {
                null -> accepted.add(message.messageId)
                else -> rejected[message.messageId] = failure
            }
        }

        return EventBatchResult(accepted, rejected, store.read { it.sequence })
    }

    private fun visibleTo(
        message: RealtimeMessage,
        viewer: AccountId,
        permissions: dev.th7bo.sidequest.platform.permission.PermissionSettings,
    ): Boolean {
        val scope = message.scope
        return if (scope.kind == dev.th7bo.sidequest.platform.permission.PermissionKind.CAPABILITY) {
            permissions.can(viewer.value, scope)
        } else {
            val sender = message.senderAccount ?: return true
            permissions.shares(sender.value, scope, viewer.value)
        }
    }

    private fun groupState(viewer: AccountId): GroupState = store.read { state ->
        val online = hub.onlineAccounts()
        GroupState(
            members = state.accounts.map { account ->
                val device = state.devices
                    .filter { it.accountId == account.id && !it.isRevoked }
                    .maxByOrNull { it.lastSeenAtMillis }
                GroupMember(
                    accountId = account.id,
                    displayName = account.displayName,
                    role = account.role,
                    // Only when the account shares their online status with this viewer, and only ever as
                    // display detail. The group listing is not a way around a privacy setting.
                    minecraftUuid = device?.minecraftUuid?.takeIf {
                        state.permissions.shares(
                            account.id.value,
                            dev.th7bo.sidequest.platform.permission.Permission.VIEW_ONLINE_STATUS,
                            viewer.value,
                        )
                    },
                    minecraftName = device?.minecraftName?.takeIf { account.id in online },
                )
            },
            permissions = state.permissions,
            revision = state.groupRevision,
        )
    }

    /**
     * Creates an account on first approval, if it does not exist.
     *
     * The bootstrap path. The first pairing the operator approves has nothing to bind to, and requiring an
     * account to be created first would mean a second endpoint that exists only for the first five minutes
     * of a server's life.
     */
    private fun ensureAccount(accountId: AccountId) {
        store.mutate { state ->
            if (state.accounts.any { it.id == accountId }) return@mutate state to Unit
            val role = if (accountId == config.ownerAccountId) GroupRole.OWNER else GroupRole.MEMBER
            val account = Account(accountId, accountId.value, role, now())
            state.copy(
                accounts = state.accounts + account,
                permissions = state.permissions.copy(
                    roles = state.permissions.roles + (accountId.value to role),
                ),
                groupRevision = state.groupRevision + 1,
            ) to Unit
        }
    }

    // -- helpers -----------------------------------------------------------

    private fun textFrame(frame: RealtimeFrame): Frame.Text =
        Frame.Text(json.encodeToString(RealtimeFrame.serializer(), frame))

    private fun errorFrame(code: ApiErrorCode, message: String): Frame.Text =
        textFrame(RealtimeFrame.Error(code, message))

    private suspend fun ApplicationCall.authenticated(scope: TokenScope? = null): Caller? {
        val caller = auth.authenticate(bearerToken())
        if (caller == null) {
            fail(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHENTICATED, "missing or expired token")
            return null
        }
        if (!rateLimiter.allow("device:${caller.deviceId.value}")) {
            fail(HttpStatusCode.TooManyRequests, ApiErrorCode.RATE_LIMITED, "slow down")
            return null
        }
        if (scope != null && !caller.hasScope(scope)) {
            fail(HttpStatusCode.Forbidden, ApiErrorCode.FORBIDDEN, "token lacks $scope")
            return null
        }
        return caller
    }

    private fun ApplicationCall.bearerToken(): String? =
        request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A key to rate-limit an unauthenticated caller by.
     *
     * The remote address, which is the only thing available before a token exists. Behind a reverse proxy
     * every caller looks the same, which is why the authenticated limit is per device — this one is only a
     * guard on the pairing endpoints.
     */
    private fun ApplicationCall.clientKey(): String = request.local.remoteHost

    private suspend inline fun <reified T : Any> ApplicationCall.respondWithTime(body: T) {
        withProtocolHeaders()
        respond(body)
    }

    private suspend fun ApplicationCall.fail(status: HttpStatusCode, code: ApiErrorCode, message: String) {
        withProtocolHeaders()
        respond(status, ApiError(code, message, requestId = request.headers[Protocol.REQUEST_ID_HEADER]))
    }

    private fun ApplicationCall.withProtocolHeaders() {
        response.header(Protocol.VERSION_HEADER, Protocol.VERSION.toString())
        response.header(Protocol.SERVER_TIME_HEADER, now().toString())
        request.headers[Protocol.REQUEST_ID_HEADER]?.let { response.header(Protocol.REQUEST_ID_HEADER, it) }
    }

    /** Refuses a caller speaking a protocol this build will not talk to. */
    private suspend fun ApplicationCall.checkProtocol(version: Int): Boolean {
        if (Protocol.isCompatible(version)) return true
        fail(
            HttpStatusCode.BadRequest,
            ApiErrorCode.PROTOCOL_MISMATCH,
            "server speaks ${Protocol.MINIMUM_VERSION}..${Protocol.VERSION}",
        )
        return false
    }

    public companion object {
        public const val SERVER_VERSION: String = "1.0.0"
    }
}

/**
 * A fixed-window request limiter.
 *
 * Not protection against an attacker — anybody with a token is already inside — but against a client with
 * a bug. A retry loop with no backoff can saturate a homelab from one laptop, and this turns that from an
 * outage into a log line.
 *
 * A fixed window rather than a sliding one, because the failure it guards against is a runaway loop and a
 * fixed window catches that with one integer per caller.
 */
public class RateLimiter(
    private val perMinute: Int,
    private val now: () -> Long,
) {
    private class Window(var startMillis: Long, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    public fun allow(key: String): Boolean {
        val window = windows.computeIfAbsent(key) { Window(now(), 0) }
        synchronized(window) {
            if (now() - window.startMillis >= WINDOW_MILLIS) {
                window.startMillis = now()
                window.count = 0
            }
            window.count++
            return window.count <= perMinute
        }
    }

    private companion object {
        const val WINDOW_MILLIS = 60_000L
    }
}

/** Generates a request id. Used by the client; here so both sides make them the same shape. */
public fun newRequestId(): String = UUID.randomUUID().toString()
