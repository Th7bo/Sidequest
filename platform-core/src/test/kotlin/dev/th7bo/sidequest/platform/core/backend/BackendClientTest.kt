package dev.th7bo.sidequest.platform.core.backend

import dev.th7bo.sidequest.platform.backend.BackendConfig
import dev.th7bo.sidequest.platform.backend.BackendState
import dev.th7bo.sidequest.platform.backend.HttpExchange
import dev.th7bo.sidequest.platform.backend.PairingStatus
import dev.th7bo.sidequest.platform.backend.StoredSession
import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.core.storage.JsonFileStorage
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import dev.th7bo.sidequest.platform.storage.OfflineQueue
import dev.th7bo.sidequest.platform.storage.StorageScope
import dev.th7bo.sidequest.platform.testkit.FakeTokenStore
import dev.th7bo.sidequest.platform.testkit.FakeTransport
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import dev.th7bo.sidequest.protocol.AccountId
import dev.th7bo.sidequest.protocol.ApiError
import dev.th7bo.sidequest.protocol.ApiErrorCode
import dev.th7bo.sidequest.protocol.DeviceId
import dev.th7bo.sidequest.protocol.Endpoints
import dev.th7bo.sidequest.protocol.EventBatchResult
import dev.th7bo.sidequest.protocol.GroupState
import dev.th7bo.sidequest.protocol.PairPollResponse
import dev.th7bo.sidequest.protocol.PairStartResponse
import dev.th7bo.sidequest.protocol.PairStatus
import dev.th7bo.sidequest.protocol.Protocol
import dev.th7bo.sidequest.protocol.RealtimeMessage
import dev.th7bo.sidequest.protocol.RealtimePayload
import dev.th7bo.sidequest.protocol.ServerInfo
import dev.th7bo.sidequest.protocol.SessionTokens
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.random.Random

/**
 * The backend client, against a transport that misbehaves on demand.
 *
 * Which is the point of the transport being an interface. Everything interesting a network client does is
 * a response to the network going wrong — a timeout, a 401, a 429 with a retry-after, a server that comes
 * back after three failures — and none of those can be produced reliably against a real server.
 */
class BackendClientTest {

    @TempDir
    lateinit var root: Path

    private lateinit var transport: FakeTransport
    private lateinit var tokens: FakeTokenStore
    private lateinit var events: DefaultEventBus
    private lateinit var outbox: OfflineQueue<RealtimeMessage>

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
    private var clock = 1_700_000_000_000L

    @BeforeEach
    fun setUp() {
        transport = FakeTransport()
        tokens = FakeTokenStore()
        events = DefaultEventBus(TestScheduler(), NoopLogger)
        outbox = JsonFileStorage(root, NoopLogger, now = { clock }).queue(
            id = SqId.sidequest("sync.outbox"),
            scope = StorageScope.Global,
            serializer = RealtimeMessage.serializer(),
        )
    }

    private fun client(
        config: BackendConfig = BackendConfig("https://sq.example.net", "test"),
        store: FakeTokenStore = tokens,
    ) = DefaultBackendClient(
        config = config,
        transport = transport,
        tokens = store,
        events = events,
        log = NoopLogger,
        outbox = outbox,
        now = { clock },
        // Seeded, so the jitter is deterministic and a backoff assertion is not flaky.
        random = Random(1),
    )

    private fun serverInfo(
        protocolVersion: Int = Protocol.VERSION,
        minimum: Int = Protocol.MINIMUM_VERSION,
    ) = json.encodeToString(
        ServerInfo.serializer(),
        ServerInfo(protocolVersion, minimum, "1.0.0", clock),
    )

    private fun tokensJson(refresh: String = "refresh-1") = json.encodeToString(
        SessionTokens.serializer(),
        SessionTokens(
            accessToken = "access-1",
            accessExpiresAtMillis = clock + 900_000,
            refreshToken = refresh,
            accountId = AccountId("owner"),
            deviceId = DeviceId("device-1"),
        ),
    )

    private fun ping() = RealtimeMessage(
        messageId = "m1",
        timestampMillis = clock,
        scope = RealtimePayload.Ping(location()).scope,
        payload = RealtimePayload.Ping(location()),
    )

    private fun location() = SqLocation(Island.HUB, SqPosition(1.0, 70.0, 2.0))

    // -- configuration and negotiation -------------------------------------

    /** No backend is a supported state, not a misconfiguration. Nothing should be attempted. */
    @Test
    fun `an unconfigured client attempts nothing`() = runTest {
        val state = client(BackendConfig.None).start()

        assertEquals(BackendState.NOT_CONFIGURED, state)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `a configured client with no credentials is unpaired`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        assertEquals(BackendState.UNPAIRED, client().start())
    }

    /**
     * Negotiated once, before anything else.
     *
     * A client that discovered a mismatch on its first real request has already queued writes it cannot
     * send, and unpicking that is worse than refusing to connect.
     */
    @Test
    fun `an incompatible server is refused rather than retried`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo(protocolVersion = 99, minimum = 99))

        val state = client().start()

        assertEquals(BackendState.INCOMPATIBLE, state)
        assertFalse(state.canAttempt) { "an incompatible client must stop trying" }
    }

    @Test
    fun `an unreachable server is offline, not broken`() = runTest {
        transport.fallback = { HttpExchange.Failure("connection refused") }
        val state = client().start()

        assertEquals(BackendState.OFFLINE, state)
        assertTrue(state.canAttempt) { "offline is temporary and needs no user action" }
    }

    /** A token from another server is worthless, and keeping it would look like a revocation. */
    @Test
    fun `credentials for a different server are discarded`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        val store = FakeTokenStore(StoredSession("r", "owner", "d", "https://other.example.net"))

        val state = client(store = store).start()

        assertEquals(BackendState.UNPAIRED, state)
        assertTrue(store.wasCleared)
    }

    @Test
    fun `a stored session is restored by refreshing`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        transport.respond(Endpoints.TOKEN_REFRESH, tokensJson())
        val store = FakeTokenStore(StoredSession("refresh-1", "owner", "device-1", "https://sq.example.net"))

        assertEquals(BackendState.ONLINE, client(store = store).start())
    }

    // -- pairing -----------------------------------------------------------

    @Test
    fun `pairing shows a code and finishes when it is approved`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        transport.respond(
            Endpoints.PAIR_START,
            json.encodeToString(
                PairStartResponse.serializer(),
                PairStartResponse(DeviceId("device-1"), "ABC234", "secret", clock + 300_000, pollIntervalMillis = 1),
            ),
        )
        transport.sequence(
            Endpoints.PAIR_POLL,
            HttpExchange.Response(200, json.encodeToString(PairPollResponse.serializer(), PairPollResponse(PairStatus.PENDING))),
            HttpExchange.Response(
                200,
                json.encodeToString(
                    PairPollResponse.serializer(),
                    PairPollResponse(
                        PairStatus.APPROVED,
                        json.decodeFromString(SessionTokens.serializer(), tokensJson()),
                    ),
                ),
            ),
        )

        val backend = client()
        backend.start()
        val codes = mutableListOf<String>()
        val status = backend.pair("uuid", "Th7bo") { codes.add("${it.status}:${it.code}") }

        assertEquals(PairingStatus.APPROVED, status)
        assertEquals(BackendState.ONLINE, backend.state)
        assertTrue(codes.first().startsWith("WAITING:ABC234")) { codes.toString() }
        assertNotNull(tokens.load()) { "the refresh token should have been stored" }
    }

    /** The access token is deliberately not stored: it lives for minutes and a restart refreshes. */
    @Test
    fun `only the refresh token is kept`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        transport.respond(
            Endpoints.PAIR_START,
            json.encodeToString(
                PairStartResponse.serializer(),
                PairStartResponse(DeviceId("device-1"), "ABC234", "secret", clock + 300_000, pollIntervalMillis = 1),
            ),
        )
        transport.respond(
            Endpoints.PAIR_POLL,
            json.encodeToString(
                PairPollResponse.serializer(),
                PairPollResponse(PairStatus.APPROVED, json.decodeFromString(SessionTokens.serializer(), tokensJson())),
            ),
        )

        val backend = client()
        backend.start()
        backend.pair("uuid", "Th7bo") {}

        val stored = tokens.load()
        assertEquals("refresh-1", stored?.refreshToken)
        assertEquals("https://sq.example.net", stored?.baseUrl)
    }

    @Test
    fun `a denied pairing says denied, not expired`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        transport.respond(
            Endpoints.PAIR_START,
            json.encodeToString(
                PairStartResponse.serializer(),
                PairStartResponse(DeviceId("d"), "ABC234", "s", clock + 300_000, pollIntervalMillis = 1),
            ),
        )
        transport.respond(
            Endpoints.PAIR_POLL,
            json.encodeToString(PairPollResponse.serializer(), PairPollResponse(PairStatus.DENIED)),
        )

        val backend = client()
        backend.start()
        assertEquals(PairingStatus.DENIED, backend.pair("uuid", "Th7bo") {})
        assertEquals(BackendState.UNPAIRED, backend.state)
    }

    // -- retries and backoff ------------------------------------------------

    /**
     * Retried, and then stopped.
     *
     * The stopping is the half that gets forgotten. A client that retries forever is a client that hammers
     * a server which is telling it to stop.
     */
    @Test
    fun `a retryable failure is retried a bounded number of times`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        transport.on(Endpoints.GROUP) { HttpExchange.Failure("unreachable") }
        transport.respond(Endpoints.TOKEN_REFRESH, tokensJson())

        val backend = client(store = FakeTokenStore(StoredSession("r", "owner", "d", "https://sq.example.net")))
        backend.start()
        val result = backend.fetchGroup()

        assertEquals(ApiErrorCode.UNAVAILABLE, result.errorOrNull()?.code)
        assertEquals(3, transport.countFor(Endpoints.GROUP)) { "three attempts, then stop" }
    }

    @Test
    fun `a server that comes back is used rather than given up on`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        transport.respond(Endpoints.TOKEN_REFRESH, tokensJson())
        transport.sequence(
            Endpoints.GROUP,
            HttpExchange.Failure("unreachable"),
            HttpExchange.Response(200, json.encodeToString(GroupState.serializer(), GroupState(revision = 7))),
        )

        val backend = client(store = FakeTokenStore(StoredSession("r", "owner", "d", "https://sq.example.net")))
        backend.start()

        assertEquals(7, backend.fetchGroup().valueOrNull()?.revision)
    }

    /** A permanent failure is not retried; the same request would fail the same way. */
    @Test
    fun `a non-retryable failure is attempted once`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        transport.respond(Endpoints.TOKEN_REFRESH, tokensJson())
        transport.respond(
            Endpoints.GROUP,
            json.encodeToString(ApiError.serializer(), ApiError(ApiErrorCode.FORBIDDEN, "nope")),
            status = 403,
        )

        val backend = client(store = FakeTokenStore(StoredSession("r", "owner", "d", "https://sq.example.net")))
        backend.start()
        backend.fetchGroup()

        assertEquals(1, transport.countFor(Endpoints.GROUP))
    }

    // -- token refresh -----------------------------------------------------

    /** A 401 refreshes once and retries once. Not in a loop: a second refusal means something else. */
    @Test
    fun `an expired access token is refreshed and the request retried`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        transport.respond(Endpoints.TOKEN_REFRESH, tokensJson())
        transport.sequence(
            Endpoints.GROUP,
            HttpExchange.Response(401, json.encodeToString(ApiError.serializer(), ApiError(ApiErrorCode.UNAUTHENTICATED))),
            HttpExchange.Response(200, json.encodeToString(GroupState.serializer(), GroupState(revision = 3))),
        )

        val backend = client(store = FakeTokenStore(StoredSession("r", "owner", "d", "https://sq.example.net")))
        backend.start()

        assertEquals(3, backend.fetchGroup().valueOrNull()?.revision)
        assertEquals(2, transport.countFor(Endpoints.GROUP))
    }

    /**
     * A revoked device drops its credentials at once.
     *
     * Keeping them would leave the client retrying a token that will never work again, and a user who
     * revoked a device would see it apparently still trying to connect.
     */
    @Test
    fun `a revoked device forgets its credentials and stops`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        transport.respond(Endpoints.TOKEN_REFRESH, tokensJson())
        transport.respond(
            Endpoints.GROUP,
            json.encodeToString(ApiError.serializer(), ApiError(ApiErrorCode.DEVICE_REVOKED)),
            status = 401,
        )

        val store = FakeTokenStore(StoredSession("r", "owner", "d", "https://sq.example.net"))
        val backend = client(store = store)
        backend.start()
        backend.fetchGroup()

        assertEquals(BackendState.REVOKED, backend.state)
        assertTrue(store.wasCleared)
        assertFalse(backend.state.canAttempt)
    }

    @Test
    fun `a refused refresh is a revocation`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        transport.respond(Endpoints.TOKEN_REFRESH, "{}", status = 401)

        val store = FakeTokenStore(StoredSession("r", "owner", "d", "https://sq.example.net"))
        val backend = client(store = store)

        assertEquals(BackendState.REVOKED, backend.start())
        assertTrue(store.wasCleared)
    }

    // -- rate limiting -----------------------------------------------------

    /**
     * The server's own retry-after is honoured.
     *
     * Sending anyway is how a client that is being rate-limited stays rate-limited, and guessing an
     * interval when the server said one is a guess that can only be wrong.
     */
    @Test
    fun `a rate limit is waited out rather than pushed through`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        transport.respond(Endpoints.TOKEN_REFRESH, tokensJson())
        transport.respond(
            Endpoints.GROUP,
            json.encodeToString(
                ApiError.serializer(),
                ApiError(ApiErrorCode.RATE_LIMITED, retryAfterMillis = 30_000),
            ),
            status = 429,
        )

        val backend = client(store = FakeTokenStore(StoredSession("r", "owner", "d", "https://sq.example.net")))
        backend.start()
        backend.fetchGroup()
        val attemptsAfterFirst = transport.countFor(Endpoints.GROUP)

        // The second call must not even reach the transport.
        val second = backend.fetchGroup()
        assertEquals(ApiErrorCode.RATE_LIMITED, second.errorOrNull()?.code)
        assertEquals(attemptsAfterFirst, transport.countFor(Endpoints.GROUP))

        clock += 30_001
        backend.fetchGroup()
        assertTrue(transport.countFor(Endpoints.GROUP) > attemptsAfterFirst) { "the wait should have ended" }
    }

    // -- request metadata --------------------------------------------------

    @Test
    fun `every request carries a protocol version and a request id`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        client().start()

        val request = transport.requests.single()
        assertEquals(Protocol.VERSION.toString(), request.headers[Protocol.VERSION_HEADER])
        assertNotNull(request.headers[Protocol.REQUEST_ID_HEADER])
    }

    /** Two requests must not share an id, or a log cannot tell them apart. */
    @Test
    fun `request ids are unique`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        val backend = client()
        backend.start()
        backend.fetchServerInfo()

        val ids = transport.requests.mapNotNull { it.headers[Protocol.REQUEST_ID_HEADER] }
        assertEquals(ids.size, ids.toSet().size)
    }

    /**
     * The clock offset is measured from the round trip.
     *
     * Every timestamp that crosses the wire is eventually compared against something, and two machines
     * disagree about the time constantly.
     */
    @Test
    fun `the server's clock offset is measured`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo(), serverTimeMillis = clock + 5_000)

        val backend = client()
        backend.start()

        assertEquals(5_000, backend.serverTime.offsetMillis)
        assertTrue(backend.serverTime.isUsable)
        assertEquals(clock + 5_000, backend.serverTime.toServer(clock))
    }

    // -- offline mode and queued writes ------------------------------------

    /**
     * A write while offline is kept, not lost.
     *
     * The interesting moments happen while offline. A rare drop at two in the morning on a flaky
     * connection is exactly the thing worth recording, and a client that dropped it would leave a hole in
     * the group's history where somebody will look.
     */
    @Test
    fun `a write with no server is queued`() = runTest {
        transport.fallback = { HttpExchange.Failure("unreachable") }
        val backend = client()
        backend.start()

        assertFalse(backend.submit(ping()))
        assertEquals(1, outbox.size())
    }

    @Test
    fun `a queued write is sent when the server comes back`() = runTest {
        transport.fallback = { HttpExchange.Failure("unreachable") }
        val backend = client()
        backend.start()
        backend.submit(ping())
        assertEquals(1, outbox.size())

        transport.clear()
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        transport.respond(Endpoints.TOKEN_REFRESH, tokensJson())
        transport.respond(
            Endpoints.EVENTS,
            json.encodeToString(EventBatchResult.serializer(), EventBatchResult(accepted = listOf("m1"))),
        )

        val reconnected = client(store = FakeTokenStore(StoredSession("r", "owner", "d", "https://sq.example.net")))
        reconnected.start()

        assertEquals(1, reconnected.drainOutbox())
        assertEquals(0, outbox.size())
    }

    /**
     * A rejected event is acknowledged, not retried forever.
     *
     * A server that refused an event will refuse it again — a permission the group revoked while it was
     * queued does not come back — and retrying it would block everything behind it.
     */
    @Test
    fun `a rejected queued event is dropped rather than blocking the queue`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        transport.respond(Endpoints.TOKEN_REFRESH, tokensJson())
        transport.respond(
            Endpoints.EVENTS,
            json.encodeToString(
                EventBatchResult.serializer(),
                EventBatchResult(rejected = mapOf("m1" to ApiErrorCode.FORBIDDEN)),
            ),
        )

        outbox.enqueue(ping())
        val backend = client(store = FakeTokenStore(StoredSession("r", "owner", "d", "https://sq.example.net")))
        backend.start()

        backend.drainOutbox()
        assertEquals(0, outbox.size()) { "a refused event must not stay in the queue forever" }
    }

    /** Somebody who pairs tomorrow should not have lost tonight. */
    @Test
    fun `a write before pairing is still kept`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        val backend = client()
        backend.start()
        assertEquals(BackendState.UNPAIRED, backend.state)

        backend.submit(ping())
        assertEquals(1, outbox.size())
    }

    @Test
    fun `signing out forgets the credentials even if the server cannot be reached`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        transport.respond(Endpoints.TOKEN_REFRESH, tokensJson())
        transport.on(Endpoints.SESSION_REVOKE) { HttpExchange.Failure("unreachable") }

        val store = FakeTokenStore(StoredSession("r", "owner", "d", "https://sq.example.net"))
        val backend = client(store = store)
        backend.start()
        backend.signOut()

        assertTrue(store.wasCleared)
        assertEquals(BackendState.UNPAIRED, backend.state)
    }

    // -- state transitions -------------------------------------------------

    /**
     * Only real changes are announced.
     *
     * A configured client starts out unpaired, so discovering that it is unpaired is not news. An event per
     * check would make anything listening — a status indicator, a log — fire on every startup for nothing.
     */
    @Test
    fun `state changes are announced, and non-changes are not`() = runTest {
        val seen = mutableListOf<String>()
        events.subscribe(
            dev.th7bo.sidequest.platform.backend.BackendStateChangedEvent::class,
            dev.th7bo.sidequest.platform.id.OwnerId(SqId.sidequest("test")),
        ) { seen.add(it.describe()) }

        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        val backend = client()
        backend.start()
        assertEquals(emptyList<String>(), seen) { "already unpaired; nothing changed" }

        transport.clear()
        transport.fallback = { HttpExchange.Failure("unreachable") }
        backend.fetchServerInfo()

        assertEquals(listOf("UNPAIRED -> OFFLINE (unreachable)"), seen)
    }

    @Test
    fun `offline becomes online again on a successful call`() = runTest {
        transport.respond(Endpoints.SERVER_INFO, serverInfo())
        transport.respond(Endpoints.TOKEN_REFRESH, tokensJson())
        transport.sequence(
            Endpoints.GROUP,
            HttpExchange.Failure("unreachable"),
            HttpExchange.Failure("unreachable"),
            HttpExchange.Failure("unreachable"),
            HttpExchange.Response(200, json.encodeToString(GroupState.serializer(), GroupState())),
        )

        val backend = client(store = FakeTokenStore(StoredSession("r", "owner", "d", "https://sq.example.net")))
        backend.start()
        backend.fetchGroup()
        assertEquals(BackendState.OFFLINE, backend.state)

        backend.fetchGroup()
        assertEquals(BackendState.ONLINE, backend.state)
    }
}
