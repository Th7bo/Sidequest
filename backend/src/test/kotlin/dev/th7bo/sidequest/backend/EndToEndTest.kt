package dev.th7bo.sidequest.backend

import dev.th7bo.sidequest.platform.backend.BackendConfig
import dev.th7bo.sidequest.platform.backend.BackendState
import dev.th7bo.sidequest.platform.backend.HttpExchange
import dev.th7bo.sidequest.platform.backend.HttpMethod
import dev.th7bo.sidequest.platform.backend.HttpRequest
import dev.th7bo.sidequest.platform.backend.HttpTransport
import dev.th7bo.sidequest.platform.backend.PairingStatus
import dev.th7bo.sidequest.platform.permission.GroupRole
import dev.th7bo.sidequest.platform.core.backend.DefaultBackendClient
import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.core.storage.JsonFileStorage
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import dev.th7bo.sidequest.platform.storage.StorageScope
import dev.th7bo.sidequest.platform.testkit.FakeTokenStore
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import dev.th7bo.sidequest.protocol.AccountId
import dev.th7bo.sidequest.protocol.Endpoints
import dev.th7bo.sidequest.protocol.PairApproveRequest
import dev.th7bo.sidequest.protocol.RealtimeMessage
import dev.th7bo.sidequest.protocol.RealtimePayload
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

/**
 * The real client against the real server.
 *
 * This is the test that justifies `:protocol` existing. Both sides compile against one set of types, so a
 * change to the wire format breaks the build — but "it compiles" is not "it agrees", and the ways two
 * halves of a protocol disagree while compiling are numerous: a serialiser configured differently on each
 * side, a discriminator name, a header spelled one way and read another, a default that means something
 * different when it is absent.
 *
 * So the client here is `DefaultBackendClient`, unmodified, talking to `SidequestBackend`, unmodified. The
 * only thing between them is an adapter from the platform's `HttpTransport` onto Ktor's test client, which
 * is fourteen lines and no logic.
 */
class EndToEndTest {

    @TempDir
    lateinit var root: Path

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
    private var clock = 1_700_000_000_000L

    /**
     * The platform's transport, over Ktor's in-process client.
     *
     * Deliberately dumb: no retries, no mapping, no interpretation. Everything of that kind is in the client
     * being tested, and a clever adapter here would be testing itself.
     */
    private class TestClientTransport(private val client: HttpClient) : HttpTransport {
        override suspend fun send(request: HttpRequest): HttpExchange = try {
            val response = client.request(request.url) {
                method = when (request.method) {
                    HttpMethod.GET -> io.ktor.http.HttpMethod.Get
                    HttpMethod.POST -> io.ktor.http.HttpMethod.Post
                }
                request.headers.forEach { (name, value) ->
                    if (!name.equals(HttpHeaders.ContentType, ignoreCase = true)) header(name, value)
                }
                if (request.body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(request.body)
                }
            }
            HttpExchange.Response(
                status = response.status.value,
                body = response.bodyAsText(),
                headers = response.headers.entries().associate { it.key.lowercase() to it.value.first() },
            )
        } catch (thrown: Exception) {
            HttpExchange.Failure(thrown.message ?: "failed")
        }
    }

    /** Approves a code as the operator would, from the dashboard. */
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.approve(
        code: String,
        account: AccountId = OWNER,
    ) {
        client.post(Endpoints.PAIR_APPROVE) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $OPERATOR")
            setBody(json.encodeToString(PairApproveRequest.serializer(), PairApproveRequest(code, account)))
        }
    }

    private fun outbox(name: String) = JsonFileStorage(root.resolve(name), NoopLogger, now = { clock }).queue(
        id = SqId.sidequest("outbox"),
        scope = StorageScope.Global,
        serializer = RealtimeMessage.serializer(),
    )

    /**
     * Pairs a client end to end and returns it, online.
     *
     * The approval runs concurrently with the polling, exactly as it does in life: the mod sits polling
     * while somebody types the code somewhere else.
     */
    private fun ping(id: String = UUID.randomUUID().toString()) = RealtimeMessage(
        messageId = id,
        timestampMillis = clock,
        scope = RealtimePayload.Ping(location()).scope,
        payload = RealtimePayload.Ping(location()),
    )

    private fun location() = SqLocation(Island.HUB, SqPosition(10.0, 70.0, -5.0))

    /**
     * Driven by `testApplication` directly rather than inside `runTest`.
     *
     * `runTest` uses a virtual clock, and the Ktor client's suspensions are real — nesting the two means the
     * virtual clock cannot advance past a real network call and the test hangs rather than failing.
     */
    @Test
    fun `a client pairs, submits an event and reads it back`() = testApplication {
        run {
            val backend = SidequestBackend(
                BackendConfig(statePath = root.resolve("state.json"), operatorToken = OPERATOR, ownerAccountId = OWNER)
                    .let { it },
                now = { clock },
            )
            application { backend.install(this) }

            val transport = TestClientTransport(client)
            val tokens = FakeTokenStore()
            val events = DefaultEventBus(TestScheduler(), NoopLogger)

            val mod = DefaultBackendClient(
                // Ktor's in-process client routes any host into the application under test, so the host here
                // is a placeholder — what matters is that the client considers itself configured.
                config = dev.th7bo.sidequest.platform.backend.BackendConfig(BASE_URL, "Th7bo's laptop"),
                transport = transport,
                tokens = tokens,
                events = events,
                log = NoopLogger,
                outbox = outbox("mod"),
                now = { clock },
            )

            // 1. Negotiation. The client refuses to go further if the server speaks something else.
            assertEquals(BackendState.UNPAIRED, mod.start())

            // 2. Pairing, with the approval arriving while the client polls — as it does in life.
            val status = coroutineScope {
                var code: String? = null
                val approver = launch {
                    while (code == null) kotlinx.coroutines.delay(POLL_TICK_MILLIS)
                    approve(code!!)
                }
                val result = mod.pair(UUID.randomUUID().toString(), "Th7bo") { progress ->
                    if (progress.code.isNotEmpty()) code = progress.code
                }
                approver.join()
                result
            }

            assertEquals(PairingStatus.APPROVED, status)
            assertEquals(BackendState.ONLINE, mod.state)
            assertNotNull(tokens.load()) { "the client should have stored its refresh token" }

            // 3. The group, decoded by the client from what the server encoded.
            val group = mod.fetchGroup().valueOrNull()
            assertNotNull(group)
            assertEquals(listOf(OWNER), group!!.members.map { it.accountId })

            // 4. A write, and the server's own sequencing of it.
            assertTrue(mod.submit(ping("m1"))) { "the server should have accepted the event" }

            val page = mod.fetchEventsSince(0).valueOrNull()
            assertNotNull(page)
            assertEquals(1, page!!.messages.size)
            assertEquals(1, page.messages.single().sequence)
            // Stamped by the server, not by the client that sent it.
            assertEquals(OWNER, page.messages.single().senderAccount)

            // 5. The clock offset, measured across a real round trip.
            assertTrue(mod.serverTime.isUsable) { "the offset should have been measured" }
        }
    }

    /**
     * A write made while the server was unreachable arrives once it is not.
     *
     * The whole point of the offline queue, and the one behaviour that cannot be tested on either side
     * alone: the client has to keep it, and the server has to accept it late and sequence it correctly.
     */
    @Test
    fun `an event queued while offline is delivered later`() = testApplication {
        val queue = outbox("mod")
        val tokens = FakeTokenStore()

        // First, with no server at all.
        run {
            val events = DefaultEventBus(TestScheduler(), NoopLogger)
            val offline = DefaultBackendClient(
                config = dev.th7bo.sidequest.platform.backend.BackendConfig("http://127.0.0.1:1", "laptop"),
                transport = object : HttpTransport {
                    override suspend fun send(request: HttpRequest) = HttpExchange.Failure("unreachable")
                },
                tokens = tokens,
                events = events,
                log = NoopLogger,
                outbox = queue,
                now = { clock },
            )
            offline.start()
            offline.submit(ping("queued-1"))
            assertEquals(1, queue.size())
        }

        // Then, with one.
        run {
            val backend = SidequestBackend(
                BackendConfig(statePath = root.resolve("state.json"), operatorToken = OPERATOR, ownerAccountId = OWNER),
                now = { clock },
            )
            application { backend.install(this) }

            val transport = TestClientTransport(client)
            val events = DefaultEventBus(TestScheduler(), NoopLogger)
            val mod = DefaultBackendClient(
                config = dev.th7bo.sidequest.platform.backend.BackendConfig(BASE_URL, "laptop"),
                transport = transport,
                tokens = tokens,
                events = events,
                log = NoopLogger,
                outbox = queue,
                now = { clock },
            )

            mod.start()
            coroutineScope {
                var code: String? = null
                val approver = launch {
                    while (code == null) kotlinx.coroutines.delay(POLL_TICK_MILLIS)
                    approve(code!!)
                }
                mod.pair(UUID.randomUUID().toString(), "Th7bo") { if (it.code.isNotEmpty()) code = it.code }
                approver.join()
            }

            assertEquals(1, mod.drainOutbox()) { "the queued event should have been sent" }
            assertEquals(0, queue.size())

            val page = mod.fetchEventsSince(0).valueOrNull()
            assertEquals(listOf("queued-1"), page?.messages?.map { it.messageId })
            // The timestamp is when it happened, not when it was delivered — which is what makes a drop
            // recorded at 2am belong at 2am in the group's history.
            assertEquals(clock, page?.messages?.single()?.timestampMillis)
        }
    }

    /**
     * A waypoint shared with named people reaches them and nobody else, over the real wire.
     *
     * The unit tests either side of this prove the routing rule and the envelope's encoding separately. What
     * they cannot prove is that the two agree — that `recipients` survives the client's serialiser, the
     * server's deserialiser, storage, and the read back, all of which are configured independently. A field
     * silently dropped at any one of those points looks exactly like a working addressed message right up
     * until it turns out to have been delivered to everybody.
     *
     * Two paired clients, and the assertion is on what the *second* one can read: sending is not the claim,
     * not-receiving is.
     */
    @Test
    fun `a waypoint addressed to somebody else does not reach another client`() = testApplication {
        val backend = SidequestBackend(
            BackendConfig(statePath = root.resolve("state.json"), operatorToken = OPERATOR, ownerAccountId = OWNER),
            now = { clock },
        )
        application { backend.install(this) }

        val owner = pairClient("owner-device", OWNER, "Th7bo")
        val other = pairClient("other-device", OTHER, "Friend")

        // Both are members, so nothing but the addressing decides who sees what.
        backend.store.mutate { state ->
            state.copy(
                permissions = state.permissions.copy(
                    roles = state.permissions.roles +
                        (OWNER.value to GroupRole.OWNER) + (OTHER.value to GroupRole.MEMBER),
                ),
            ) to Unit
        }

        val secret = waypoint("secret", "Secret spot", to = setOf(AccountId("somebody-else")))
        val shared = waypoint("shared", "Shared spot", to = setOf(OTHER))
        assertTrue(owner.submit(secret))
        assertTrue(owner.submit(shared))

        val ownerSees = owner.fetchEventsSince(0).valueOrNull()?.messages.orEmpty()
        val otherSees = other.fetchEventsSince(0).valueOrNull()?.messages.orEmpty()

        assertEquals(
            listOf("secret", "shared"),
            ownerSees.map { it.messageId },
            "the sender should be able to read back everything they sent",
        )
        assertEquals(
            listOf("shared"),
            otherSees.map { it.messageId },
            "the other client should only have the one addressed to them",
        )
        // The field itself made the round trip rather than being dropped and defaulting to "everybody",
        // which would have let this pass for the wrong reason.
        assertEquals(setOf(OTHER), otherSees.single().recipients)
    }

    /**
     * A debt reaches the two people it is about and nobody else.
     *
     * The sharpest case of the addressing rule, and worth its own test rather than trusting the waypoint
     * one: a debt event names who owes what, which is the single most private thing this mod puts on a
     * wire. A third member of the group receiving it is not a cosmetic leak — it is somebody's finances
     * shown to a room.
     *
     * Both permission-relevant sides are covered at once. `CREATE_DEBTS` is a capability, so a guest would
     * be refused it anyway; here everybody is a member, which means the *only* thing keeping the debt away
     * from the third client is the recipient list.
     */
    @Test
    fun `a debt reaches only the two people it is about`() = testApplication {
        val backend = SidequestBackend(
            BackendConfig(statePath = root.resolve("state.json"), operatorToken = OPERATOR, ownerAccountId = OWNER),
            now = { clock },
        )
        application { backend.install(this) }

        val creditor = pairClient("creditor-device", OWNER, "Th7bo")
        val debtor = pairClient("debtor-device", OTHER, "Friend")
        val bystander = pairClient("bystander-device", THIRD, "Nosey")

        backend.store.mutate { state ->
            state.copy(
                permissions = state.permissions.copy(
                    roles = state.permissions.roles +
                        (OWNER.value to GroupRole.OWNER) +
                        (OTHER.value to GroupRole.MEMBER) +
                        (THIRD.value to GroupRole.MEMBER),
                ),
            ) to Unit
        }

        val payload = RealtimePayload.DebtCreated(debtId = "d1", debtor = OTHER, coins = 5_000_000)
        assertTrue(
            creditor.submit(
                RealtimeMessage(
                    messageId = "d1",
                    timestampMillis = clock,
                    scope = payload.scope,
                    recipients = setOf(OWNER, OTHER),
                    payload = payload,
                ),
            ),
        )

        assertEquals(1, debtor.fetchEventsSince(0).valueOrNull()?.messages?.size, "the debtor must be told")
        assertEquals(1, creditor.fetchEventsSince(0).valueOrNull()?.messages?.size, "so must the creditor")
        assertTrue(
            bystander.fetchEventsSince(0).valueOrNull()?.messages.orEmpty().isEmpty(),
            "somebody else's finances reached a third client",
        )
    }

    /** Pairs one client end to end and hands it back, online. */
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.pairClient(
        outboxName: String,
        account: AccountId,
        name: String,
    ): DefaultBackendClient {
        val mod = DefaultBackendClient(
            config = dev.th7bo.sidequest.platform.backend.BackendConfig(BASE_URL, "$name's laptop"),
            transport = TestClientTransport(client),
            tokens = FakeTokenStore(),
            events = DefaultEventBus(TestScheduler(), NoopLogger),
            log = NoopLogger,
            outbox = outbox(outboxName),
            now = { clock },
        )
        mod.start()
        coroutineScope {
            var code: String? = null
            val approver = launch {
                while (code == null) kotlinx.coroutines.delay(POLL_TICK_MILLIS)
                approve(code!!, account)
            }
            mod.pair(UUID.randomUUID().toString(), name) { if (it.code.isNotEmpty()) code = it.code }
            approver.join()
        }
        return mod
    }

    private fun waypoint(id: String, label: String, to: Set<AccountId>): RealtimeMessage {
        val payload = RealtimePayload.Waypoint(id = id, location = location(), label = label)
        return RealtimeMessage(
            messageId = id,
            timestampMillis = clock,
            scope = payload.scope,
            recipients = to,
            payload = payload,
        )
    }

    private companion object {
        const val OPERATOR = "operator-token-for-tests-only"
        const val BASE_URL = "http://localhost"

        /** How often the approver checks whether a code has appeared. */
        const val POLL_TICK_MILLIS = 5L
        val OWNER = AccountId("owner")

        /** A second paired account, so "who receives it" is a question with two possible answers. */
        val OTHER = AccountId("other")

        /** A third member, so "everybody entitled" and "the two involved" are different sets. */
        val THIRD = AccountId("third")
    }
}
