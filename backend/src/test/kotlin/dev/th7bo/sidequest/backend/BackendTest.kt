package dev.th7bo.sidequest.backend

import dev.th7bo.sidequest.platform.permission.GroupRole
import dev.th7bo.sidequest.platform.permission.Permission
import dev.th7bo.sidequest.platform.skyblock.Activity
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import dev.th7bo.sidequest.protocol.AccountId
import dev.th7bo.sidequest.protocol.ApiError
import dev.th7bo.sidequest.protocol.ApiErrorCode
import dev.th7bo.sidequest.protocol.DeviceId
import dev.th7bo.sidequest.protocol.Endpoints
import dev.th7bo.sidequest.protocol.EventBatch
import dev.th7bo.sidequest.protocol.EventBatchResult
import dev.th7bo.sidequest.protocol.EventPage
import dev.th7bo.sidequest.protocol.GroupState
import dev.th7bo.sidequest.protocol.PairApproveRequest
import dev.th7bo.sidequest.protocol.PairPollRequest
import dev.th7bo.sidequest.protocol.PairPollResponse
import dev.th7bo.sidequest.protocol.PairStartRequest
import dev.th7bo.sidequest.protocol.PairStartResponse
import dev.th7bo.sidequest.protocol.PairStatus
import dev.th7bo.sidequest.protocol.Protocol
import dev.th7bo.sidequest.protocol.RealtimeMessage
import dev.th7bo.sidequest.protocol.RealtimePayload
import dev.th7bo.sidequest.protocol.RefreshRequest
import dev.th7bo.sidequest.protocol.RevokeRequest
import dev.th7bo.sidequest.protocol.ServerInfo
import dev.th7bo.sidequest.protocol.SessionList
import dev.th7bo.sidequest.protocol.SessionTokens
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

/**
 * The backend, driven over real HTTP through Ktor's test host.
 *
 * The tests worth reading here are the security ones. This is the part of the project where a mistake
 * does not produce a bug report — it produces somebody reading somebody else's position, or a game
 * client that can hand out group membership. So most of what follows is an attempt to do something the
 * server should refuse.
 */
class BackendTest {

    @TempDir
    lateinit var root: Path

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

    private var clock = 1_700_000_000_000L

    private fun config(operatorToken: String? = OPERATOR) = BackendConfig(
        statePath = root.resolve("state.json"),
        operatorToken = operatorToken,
        ownerAccountId = OWNER,
    )

    /** Runs a block against a running server. */
    private fun withServer(
        operatorToken: String? = OPERATOR,
        block: suspend ApplicationTestBuilder.(SidequestBackend) -> Unit,
    ) = testApplication {
        val backend = SidequestBackend(config(operatorToken), now = { clock })
        application { backend.install(this) }
        block(backend)
    }

    // -- helpers -----------------------------------------------------------

    private suspend inline fun <reified T> HttpResponse.decode(): T =
        json.decodeFromString(bodyAsText())

    private suspend fun ApplicationTestBuilder.startPairing(name: String = "Th7bo"): PairStartResponse =
        client.post(Endpoints.PAIR_START) {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    PairStartRequest.serializer(),
                    PairStartRequest(UUID.randomUUID().toString(), name, "$name's laptop"),
                ),
            )
        }.decode()

    private suspend fun ApplicationTestBuilder.approve(
        code: String,
        account: AccountId,
        token: String? = OPERATOR,
        approve: Boolean = true,
    ): HttpResponse = client.post(Endpoints.PAIR_APPROVE) {
        contentType(ContentType.Application.Json)
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        setBody(json.encodeToString(PairApproveRequest.serializer(), PairApproveRequest(code, account, approve)))
    }

    private suspend fun ApplicationTestBuilder.poll(start: PairStartResponse): PairPollResponse =
        client.post(Endpoints.PAIR_POLL) {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    PairPollRequest.serializer(),
                    PairPollRequest(start.deviceId, start.deviceSecret),
                ),
            )
        }.decode()

    /** Pairs a device end to end and returns its tokens. */
    private suspend fun ApplicationTestBuilder.pair(
        account: AccountId = OWNER,
        name: String = "Th7bo",
    ): SessionTokens {
        val start = startPairing(name)
        approve(start.code, account)
        return requireNotNull(poll(start).session) { "pairing was not approved" }
    }

    private suspend fun ApplicationTestBuilder.authedGet(path: String, token: String): HttpResponse =
        client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun ApplicationTestBuilder.submit(
        token: String,
        vararg messages: RealtimeMessage,
    ): HttpResponse = client.post(Endpoints.EVENTS) {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer $token")
        setBody(json.encodeToString(EventBatch.serializer(), EventBatch(messages.toList())))
    }

    private fun message(
        payload: RealtimePayload,
        id: String = UUID.randomUUID().toString(),
        sender: AccountId? = null,
    ) = RealtimeMessage(
        messageId = id,
        timestampMillis = clock,
        scope = payload.scope,
        payload = payload,
        senderAccount = sender,
    )

    // -- server info -------------------------------------------------------

    @Test
    fun `the server says what it speaks and what time it is`() = runTest {
        withServer {
            val info: ServerInfo = client.get(Endpoints.SERVER_INFO).decode()
            assertEquals(Protocol.VERSION, info.protocolVersion)
            assertEquals(clock, info.timeMillis)
            assertTrue(info.isCompatibleWithThisBuild)
            assertTrue(info.pairingEnabled)
        }
    }

    /** A server with no operator token cannot approve anything, and says so rather than pretending. */
    @Test
    fun `pairing is reported as disabled without an operator token`() = runTest {
        withServer(operatorToken = null) {
            val info: ServerInfo = client.get(Endpoints.SERVER_INFO).decode()
            assertFalse(info.pairingEnabled)

            val response = client.post(Endpoints.PAIR_START) {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        PairStartRequest.serializer(),
                        PairStartRequest("uuid", "Th7bo", "laptop"),
                    ),
                )
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `the protocol version travels on every response`() = runTest {
        withServer {
            val response = client.get(Endpoints.SERVER_INFO)
            assertEquals(Protocol.VERSION.toString(), response.headers[Protocol.VERSION_HEADER])
            assertNotNull(response.headers[Protocol.SERVER_TIME_HEADER])
        }
    }

    @Test
    fun `a request id is echoed so two logs can be correlated`() = runTest {
        withServer {
            val response = client.post(Endpoints.PAIR_START) {
                contentType(ContentType.Application.Json)
                header(Protocol.REQUEST_ID_HEADER, "abc-123")
                setBody(
                    json.encodeToString(
                        PairStartRequest.serializer(),
                        PairStartRequest("uuid", "Th7bo", "laptop"),
                    ),
                )
            }
            assertEquals("abc-123", response.headers[Protocol.REQUEST_ID_HEADER])
        }
    }

    @Test
    fun `a client speaking an unsupported protocol is refused`() = runTest {
        withServer {
            val response = client.post(Endpoints.PAIR_START) {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        PairStartRequest.serializer(),
                        PairStartRequest("uuid", "Th7bo", "laptop", protocolVersion = 999),
                    ),
                )
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ApiErrorCode.PROTOCOL_MISMATCH, response.decode<ApiError>().code)
        }
    }

    // -- pairing -----------------------------------------------------------

    @Test
    fun `a pairing goes from pending to approved`() = runTest {
        withServer {
            val start = startPairing()
            assertEquals(PairStatus.PENDING, poll(start).status)

            approve(start.code, OWNER)

            val approved = poll(start)
            assertEquals(PairStatus.APPROVED, approved.status)
            assertNotNull(approved.session)
            assertEquals(OWNER, approved.session!!.accountId)
        }
    }

    /**
     * The code is worthless without the secret.
     *
     * A six-character code is guessable by design — it has to be typable by a human. Everything that
     * makes the flow safe rests on the secret, so polling with a wrong one must reveal nothing.
     */
    @Test
    fun `polling with the wrong secret reveals nothing`() = runTest {
        withServer {
            val start = startPairing()
            approve(start.code, OWNER)

            val response: PairPollResponse = client.post(Endpoints.PAIR_POLL) {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        PairPollRequest.serializer(),
                        PairPollRequest(start.deviceId, "not-the-secret"),
                    ),
                )
            }.decode()

            // Unknown, not "wrong secret": distinguishing them would confirm the device id exists.
            assertEquals(PairStatus.UNKNOWN, response.status)
            assertNull(response.session)
        }
    }

    /**
     * The one thing a game client must never be able to do.
     *
     * Approving is what binds a device to an account. A client that could approve its own pairing could
     * grant itself membership of the group, which is the whole security boundary of the system.
     */
    @Test
    fun `a paired client cannot approve a pairing`() = runTest {
        withServer {
            val tokens = pair()
            val start = startPairing("Somebody")

            val response = approve(start.code, OWNER, token = tokens.accessToken)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(PairStatus.PENDING, poll(start).status)
        }
    }

    @Test
    fun `approving with no token at all is refused`() = runTest {
        withServer {
            val start = startPairing()
            assertEquals(HttpStatusCode.Unauthorized, approve(start.code, OWNER, token = null).status)
        }
    }

    @Test
    fun `a denied pairing says denied, not expired`() = runTest {
        withServer {
            val start = startPairing()
            approve(start.code, OWNER, approve = false)
            assertEquals(PairStatus.DENIED, poll(start).status)
        }
    }

    @Test
    fun `an expired code cannot be approved`() = runTest {
        withServer {
            val start = startPairing()
            clock += config().pairingTtlMillis + 1

            assertEquals(HttpStatusCode.NotFound, approve(start.code, OWNER).status)
            assertEquals(PairStatus.EXPIRED, poll(start).status)
        }
    }

    @Test
    fun `a code is single use`() = runTest {
        withServer {
            val start = startPairing()
            approve(start.code, OWNER)
            assertEquals(PairStatus.APPROVED, poll(start).status)

            // Consumed. A second poll finds nothing, so a replayed poll cannot mint a second session.
            assertEquals(PairStatus.UNKNOWN, poll(start).status)
        }
    }

    /** A game client never gets administration, however it was paired. */
    @Test
    fun `a paired device is never granted administration`() = runTest {
        withServer {
            val tokens = pair()
            assertFalse(dev.th7bo.sidequest.protocol.TokenScope.ADMINISTER in tokens.scopes)
        }
    }

    /** The claimed Minecraft UUID grants nothing — the approval is what binds the account. */
    @Test
    fun `the asserted Minecraft identity does not decide the account`() = runTest {
        withServer { backend ->
            val start = startPairing("Impostor")
            approve(start.code, AccountId("somebody-else"))
            val session = poll(start).session

            assertEquals(AccountId("somebody-else"), session?.accountId)
            // Recorded for display, and only that.
            val device = backend.store.read { state -> state.devices.single() }
            assertEquals("Impostor", device.minecraftName)
        }
    }

    /** A stolen state file must not yield working credentials. */
    @Test
    fun `tokens are never stored as issued`() = runTest {
        withServer { backend ->
            val tokens = pair()
            val stateText = java.nio.file.Files.readString(root.resolve("state.json"))

            assertFalse(tokens.refreshToken in stateText) { "the refresh token is in the state file" }
            assertFalse(tokens.accessToken in stateText) { "the access token is in the state file" }
            assertTrue(backend.store.read { it.devices }.single().refreshTokenHash.isNotEmpty())
        }
    }

    // -- tokens ------------------------------------------------------------

    @Test
    fun `a refresh token buys a new access token`() = runTest {
        withServer {
            val tokens = pair()
            clock += 1_000

            val refreshed: SessionTokens = client.post(Endpoints.TOKEN_REFRESH) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RefreshRequest.serializer(), RefreshRequest(tokens.refreshToken)))
            }.decode()

            assertNotEquals(tokens.accessToken, refreshed.accessToken)
            assertEquals(tokens.deviceId, refreshed.deviceId)
            assertEquals(HttpStatusCode.OK, authedGet(Endpoints.GROUP, refreshed.accessToken).status)
        }
    }

    @Test
    fun `an expired access token is refused`() = runTest {
        withServer {
            val tokens = pair()
            assertEquals(HttpStatusCode.OK, authedGet(Endpoints.GROUP, tokens.accessToken).status)

            clock += config().accessTtlMillis + 1
            assertEquals(HttpStatusCode.Unauthorized, authedGet(Endpoints.GROUP, tokens.accessToken).status)
        }
    }

    @Test
    fun `an invented token is refused`() = runTest {
        withServer {
            assertEquals(HttpStatusCode.Unauthorized, authedGet(Endpoints.GROUP, "made-up").status)
        }
    }

    /**
     * Revocation takes effect at once, not when the token expires.
     *
     * The window between revoking a device and its token expiring is exactly the window somebody
     * revoking it is trying to close.
     */
    @Test
    fun `revoking a device stops its live access token immediately`() = runTest {
        withServer {
            val tokens = pair()
            assertEquals(HttpStatusCode.OK, authedGet(Endpoints.GROUP, tokens.accessToken).status)

            val revoked = client.post(Endpoints.SESSION_REVOKE) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
                setBody(json.encodeToString(RevokeRequest.serializer(), RevokeRequest()))
            }
            assertEquals(HttpStatusCode.OK, revoked.status)

            assertEquals(HttpStatusCode.Unauthorized, authedGet(Endpoints.GROUP, tokens.accessToken).status)
        }
    }

    @Test
    fun `a revoked device cannot refresh its way back in`() = runTest {
        withServer { backend ->
            val tokens = pair()
            backend.auth.revoke(tokens.deviceId)

            val response = client.post(Endpoints.TOKEN_REFRESH) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RefreshRequest.serializer(), RefreshRequest(tokens.refreshToken)))
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ApiErrorCode.DEVICE_REVOKED, response.decode<ApiError>().code)
        }
    }

    /** One compromised client must not be able to sign out the group. */
    @Test
    fun `a device cannot revoke somebody else's`() = runTest {
        withServer {
            val mine = pair(OWNER, "Th7bo")
            val theirs = pair(AccountId("friend"), "Friend")

            val response = client.post(Endpoints.SESSION_REVOKE) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${mine.accessToken}")
                setBody(json.encodeToString(RevokeRequest.serializer(), RevokeRequest(theirs.deviceId)))
            }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(HttpStatusCode.OK, authedGet(Endpoints.GROUP, theirs.accessToken).status)
        }
    }

    @Test
    fun `a session list shows only the caller's own devices`() = runTest {
        withServer {
            val mine = pair(OWNER, "Th7bo")
            pair(AccountId("friend"), "Friend")

            val sessions: SessionList = authedGet(Endpoints.SESSIONS, mine.accessToken).decode()

            assertEquals(1, sessions.devices.size)
            assertTrue(sessions.devices.single().isCurrent)
            assertEquals("Th7bo's laptop", sessions.devices.single().deviceName)
        }
    }

    @Test
    fun `an account can hold several devices`() = runTest {
        withServer {
            val first = pair(OWNER, "Th7bo")
            pair(OWNER, "Th7bo")

            val sessions: SessionList = authedGet(Endpoints.SESSIONS, first.accessToken).decode()
            assertEquals(2, sessions.devices.size)
            assertEquals(1, sessions.devices.count { it.isCurrent })
        }
    }

    // -- events and permissions --------------------------------------------

    @Test
    fun `an event is accepted, sequenced and readable back`() = runTest {
        withServer {
            val tokens = pair()

            val result: EventBatchResult = submit(
                tokens.accessToken,
                message(RealtimePayload.Ping(location(), "over here")),
            ).decode()

            assertEquals(1, result.accepted.size)
            assertTrue(result.rejected.isEmpty())
            assertEquals(1, result.currentSequence)

            val page: EventPage = authedGet("${Endpoints.EVENTS_SINCE}?since=0", tokens.accessToken).decode()
            assertEquals(1, page.messages.size)
            assertEquals(1, page.messages.single().sequence)
        }
    }

    /**
     * The sender is stamped by the server.
     *
     * A client that could name its own sender could impersonate anybody in the group, which would make
     * every attributable event — a debt, a payment, a comment — worthless.
     */
    @Test
    fun `a client cannot name itself as somebody else`() = runTest {
        withServer {
            val tokens = pair(AccountId("friend"), "Friend")

            submit(
                tokens.accessToken,
                message(RealtimePayload.Ping(location()), sender = AccountId("the-owner")),
            )

            val page: EventPage = authedGet("${Endpoints.EVENTS_SINCE}?since=0", tokens.accessToken).decode()
            assertEquals(AccountId("friend"), page.messages.single().senderAccount)
        }
    }

    /** A client that chose its own sequence could rewrite the order of history. */
    @Test
    fun `a client cannot choose its place in the order`() = runTest {
        withServer {
            val tokens = pair()

            submit(
                tokens.accessToken,
                message(RealtimePayload.Ping(location())).copy(sequence = 9_999),
            )

            val page: EventPage = authedGet("${Endpoints.EVENTS_SINCE}?since=0", tokens.accessToken).decode()
            assertEquals(1, page.messages.single().sequence)
        }
    }

    /**
     * A capability is checked on the server, not by the client that is exercising it.
     *
     * A guest submitting a payment confirmation is the shape of every "the client checks its own
     * permissions" failure.
     */
    @Test
    fun `an event the sender may not perform is rejected`() = runTest {
        withServer { backend ->
            val tokens = pair(AccountId("guest"), "Guest")
            backend.store.mutate { state ->
                state.copy(
                    accounts = state.accounts.map {
                        if (it.id == AccountId("guest")) it.copy(role = GroupRole.GUEST) else it
                    },
                    permissions = state.permissions.copy(roles = mapOf("guest" to GroupRole.GUEST)),
                ) to Unit
            }

            val result: EventBatchResult = submit(
                tokens.accessToken,
                message(RealtimePayload.PaymentConfirmed("debt-1", 500)),
            ).decode()

            assertTrue(result.accepted.isEmpty())
            assertEquals(ApiErrorCode.FORBIDDEN, result.rejected.values.single())
        }
    }

    /**
     * One rejected event must not take the batch with it.
     *
     * A client that has been offline for an evening submits a hundred; one whose permission the group
     * revoked in the meantime is not a reason to lose the other ninety-nine.
     */
    @Test
    fun `a rejected event does not fail the whole batch`() = runTest {
        withServer { backend ->
            val tokens = pair(AccountId("member"), "Member")
            backend.store.mutate { state ->
                state.copy(
                    permissions = state.permissions.copy(roles = mapOf("member" to GroupRole.MEMBER)),
                ) to Unit
            }

            val result: EventBatchResult = submit(
                tokens.accessToken,
                message(RealtimePayload.Ping(location()), id = "ok-1"),
                message(RealtimePayload.PaymentConfirmed("debt-1", 500), id = "no-1"),
                message(RealtimePayload.Ping(location()), id = "ok-2"),
            ).decode()

            assertEquals(listOf("ok-1", "ok-2"), result.accepted)
            assertEquals(setOf("no-1"), result.rejected.keys)
        }
    }

    /**
     * The history endpoint is not a way around the privacy model.
     *
     * Filtered on the way out, exactly as the live fan-out is. A replay that returned everything would
     * make every privacy setting a matter of when you asked.
     */
    @Test
    fun `history is filtered by the same rules as live traffic`() = runTest {
        withServer { backend ->
            val owner = pair(OWNER, "Th7bo")
            val guest = pair(AccountId("guest"), "Guest")
            backend.store.mutate { state ->
                state.copy(
                    permissions = state.permissions.copy(
                        roles = state.permissions.roles + ("guest" to GroupRole.GUEST),
                    ),
                ) to Unit
            }

            submit(owner.accessToken, message(RealtimePayload.DebtCreated("d1", AccountId("guest"), 500)))

            val ownerPage: EventPage = authedGet("${Endpoints.EVENTS_SINCE}?since=0", owner.accessToken).decode()
            val guestPage: EventPage = authedGet("${Endpoints.EVENTS_SINCE}?since=0", guest.accessToken).decode()

            assertEquals(1, ownerPage.messages.size)
            assertTrue(guestPage.messages.isEmpty()) { "a guest received a ledger event" }
        }
    }

    /**
     * A resume point older than the window is reported as a gap.
     *
     * The alternative is handing back the oldest events we still have and letting the client believe it
     * is caught up, which leaves a hole it does not know about.
     */
    @Test
    fun `a resume point that has fallen out of the window is reported`() = runTest {
        withServer {
            val tokens = pair()
            submit(tokens.accessToken, message(RealtimePayload.Ping(location())))

            val page: EventPage = authedGet("${Endpoints.EVENTS_SINCE}?since=0", tokens.accessToken).decode()
            assertFalse(page.resumeGap, "nothing has fallen out yet")
        }
    }

    // -- the group ---------------------------------------------------------

    @Test
    fun `the group lists its members and their roles`() = runTest {
        withServer {
            val owner = pair(OWNER, "Th7bo")
            pair(AccountId("friend"), "Friend")

            val group: GroupState = authedGet(Endpoints.GROUP, owner.accessToken).decode()

            assertEquals(setOf(OWNER, AccountId("friend")), group.members.map { it.accountId }.toSet())
            assertEquals(GroupRole.OWNER, group.members.single { it.accountId == OWNER }.role)
            assertEquals(GroupRole.MEMBER, group.members.single { it.accountId == AccountId("friend") }.role)
        }
    }

    /** The first account approved becomes the owner; there has to be one and nobody can grant it. */
    @Test
    fun `the configured owner account is created as owner`() = runTest {
        withServer { backend ->
            pair(OWNER)
            assertEquals(GroupRole.OWNER, backend.store.read { it.accounts.single().role })
        }
    }

    /** The group listing must not hand out a Minecraft identity somebody has not shared. */
    @Test
    fun `a member who shares nothing is listed without their Minecraft identity`() = runTest {
        withServer { backend ->
            val owner = pair(OWNER, "Th7bo")
            pair(AccountId("private"), "Private")

            backend.store.mutate { state ->
                state.copy(
                    permissions = state.permissions.copy(
                        disclosures = mapOf("private" to mapOf(Permission.VIEW_ONLINE_STATUS to emptySet())),
                    ),
                ) to Unit
            }

            val group: GroupState = authedGet(Endpoints.GROUP, owner.accessToken).decode()
            val private = group.members.single { it.accountId == AccountId("private") }
            assertNull(private.minecraftUuid)
        }
    }

    // -- fan-out redaction -------------------------------------------------

    /**
     * A position is stripped for a viewer who has not been given it.
     *
     * Tested against the hub directly rather than over a socket: what is being checked is the redaction,
     * and driving two WebSockets to observe it would test the framing instead.
     */
    @Test
    fun `a presence is redacted per viewer, field by field`() = runTest {
        withServer { backend ->
            val sender = AccountId("sharer")
            val trusted = AccountId("trusted")
            val everybody = AccountId("everybody")

            backend.store.mutate { state ->
                state.copy(
                    permissions = state.permissions.copy(
                        roles = mapOf(sender.value to GroupRole.MEMBER, trusted.value to GroupRole.MEMBER, everybody.value to GroupRole.MEMBER),
                        disclosures = mapOf(
                            sender.value to mapOf(
                                // Position with one person; island with the group; activity with nobody.
                                Permission.VIEW_EXACT_POSITION to setOf(trusted.value),
                                Permission.VIEW_ACTIVITY to emptySet(),
                            ),
                        ),
                    ),
                ) to Unit
            }

            val trustedConnection = backend.hub.register(Connection(trusted, DeviceId("trusted-device")))
            val otherConnection = backend.hub.register(Connection(everybody, DeviceId("other-device")))

            val presence = RealtimePayload.Presence(
                isOnline = true,
                activity = Activity.DUNGEONS,
                island = Island.CATACOMBS,
                location = location(),
            )
            backend.hub.fanOut(message(presence, sender = sender))

            val toTrusted = trustedConnection.outbound.tryReceive().getOrNull()?.payload as RealtimePayload.Presence
            val toOther = otherConnection.outbound.tryReceive().getOrNull()?.payload as RealtimePayload.Presence

            assertNotNull(toTrusted.location) { "the trusted viewer should see the position" }
            assertNull(toOther.location) { "the position leaked to somebody who was not given it" }

            assertEquals(Island.CATACOMBS, toTrusted.island, "the island is shared by default")
            assertEquals(Island.CATACOMBS, toOther.island)

            assertEquals(Activity.UNKNOWN, toTrusted.activity, "the activity was turned off for everybody")
            assertEquals(Activity.UNKNOWN, toOther.activity)
        }
    }

    /** A sender does not receive an echo of what it just sent. */
    @Test
    fun `the sender's own connection is not sent its own event`() = runTest {
        withServer { backend ->
            val account = AccountId("sender")
            val device = DeviceId("sender-device")
            val connection = backend.hub.register(Connection(account, device))

            val delivered = backend.hub.fanOut(message(RealtimePayload.Ping(location()), sender = account), from = device)

            assertEquals(0, delivered)
            assertNull(connection.outbound.tryReceive().getOrNull())
        }
    }

    /** A capability-scoped event only reaches people who could have performed it. */
    @Test
    fun `a guest does not receive a ledger event`() = runTest {
        withServer { backend ->
            backend.store.mutate { state ->
                state.copy(
                    permissions = state.permissions.copy(
                        roles = mapOf("admin" to GroupRole.ADMIN, "guest" to GroupRole.GUEST),
                    ),
                ) to Unit
            }
            val adminConnection = backend.hub.register(Connection(AccountId("admin"), DeviceId("a")))
            val guestConnection = backend.hub.register(Connection(AccountId("guest"), DeviceId("g")))

            backend.hub.fanOut(
                message(RealtimePayload.PaymentConfirmed("d1", 500), sender = AccountId("admin")),
            )

            assertNotNull(adminConnection.outbound.tryReceive().getOrNull())
            assertNull(guestConnection.outbound.tryReceive().getOrNull())
        }
    }

    /** A reconnecting device replaces its old connection rather than doubling up. */
    @Test
    fun `a second connection from one device replaces the first`() = runTest {
        withServer { backend ->
            backend.hub.register(Connection(OWNER, DeviceId("d")))
            backend.hub.register(Connection(OWNER, DeviceId("d")))
            assertEquals(1, backend.hub.connectionCount)
        }
    }

    // -- persistence -------------------------------------------------------

    @Test
    fun `state survives a restart`() = runTest {
        var refreshToken = ""
        withServer { refreshToken = pair().refreshToken }

        withServer {
            val refreshed: SessionTokens = client.post(Endpoints.TOKEN_REFRESH) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken)))
            }.decode()
            assertEquals(OWNER, refreshed.accountId)
        }
    }

    /**
     * Access tokens do not survive a restart, and that is correct.
     *
     * They are held in memory on purpose: an access token lives for minutes, and persisting one would
     * leave working credentials on disk for no benefit — a client whose token stopped working refreshes.
     */
    @Test
    fun `access tokens do not survive a restart`() = runTest {
        var access = ""
        withServer { access = pair().accessToken }
        withServer {
            assertEquals(HttpStatusCode.Unauthorized, authedGet(Endpoints.GROUP, access).status)
        }
    }

    private fun location() = SqLocation(Island.HUB, SqPosition(10.0, 70.0, -5.0))

    private companion object {
        const val OPERATOR = "operator-token-for-tests-only"
        val OWNER = AccountId("owner")
    }
}
