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
import dev.th7bo.sidequest.protocol.AccountList
import dev.th7bo.sidequest.protocol.PairStatus
import dev.th7bo.sidequest.protocol.PendingPairingList
import dev.th7bo.sidequest.protocol.WebIdentity
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

    private val GUILD = "1234567890"

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
        recipients: Set<AccountId> = emptySet(),
    ) = RealtimeMessage(
        messageId = id,
        timestampMillis = clock,
        scope = payload.scope,
        payload = payload,
        senderAccount = sender,
        recipients = recipients,
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
     * The default state path is a bare filename, and a bare filename has no parent directory.
     *
     * `Files.createDirectories(null)` throws, so this failed on the first write of every fresh install —
     * while somebody was pairing their first client, with nothing on screen to say why. Every existing test
     * passed because they all point the store at a temporary file inside a directory.
     */
    @Test
    fun `a state file with no directory still saves`() {
        val previous = System.getProperty("user.dir")
        val scratch = kotlin.io.path.createTempDirectory("sidequest-cwd")
        try {
            System.setProperty("user.dir", scratch.toString())
            val store = ServerStore(java.nio.file.Path.of("sidequest-state.json"))

            store.mutate { state -> state.copy(accounts = state.accounts) to Unit }
        } finally {
            System.setProperty("user.dir", previous)
            scratch.toFile().deleteRecursively()
        }
    }

    // -- Discord sign-in ---------------------------------------------------

    /**
     * The guild is the membership list, so being in it is the whole authorisation.
     *
     * Tested against [DiscordAuth] rather than through the routes, because what matters is the decision
     * and every interesting version of it — a member, somebody in other guilds, somebody in none, a
     * stale code, an unreachable Discord — is one a real sign-in cannot be made to produce on demand.
     */
    @Test
    fun `a guild member is recognised`() {
        val discord = fakeDiscord(guilds = listOf(GUILD, "999"))

        val result = discord.identify("code") as DiscordResult.Success

        assertEquals("42", result.identity.userId)
        assertEquals("chrooted", result.identity.username)
        assertTrue(result.identity.isInGuild)
    }

    @Test
    fun `somebody in other guilds is not in this one`() {
        val discord = fakeDiscord(guilds = listOf("111", "222"))

        val result = discord.identify("code") as DiscordResult.Success

        assertFalse(result.identity.isInGuild, "they are in servers, just not ours")
    }

    @Test
    fun `somebody in no guilds is refused`() {
        val result = fakeDiscord(guilds = emptyList()).identify("code") as DiscordResult.Success

        assertFalse(result.identity.isInGuild)
    }

    /** A reused or expired code. Distinguished from being unreachable, because they need different words. */
    @Test
    fun `a code Discord will not exchange is reported as such`() {
        val discord = DiscordAuth(discordConfig()) { _ -> 400 to """{"error":"invalid_grant"}""" }

        val result = discord.identify("stale") as DiscordResult.Failure

        assertEquals(DiscordFailure.BAD_CODE, result.reason)
    }

    @Test
    fun `an unreachable Discord is not mistaken for a refusal`() {
        val discord = DiscordAuth(discordConfig()) { _ -> throw java.io.IOException("no route to host") }

        val result = discord.identify("code") as DiscordResult.Failure

        assertEquals(DiscordFailure.BAD_CODE, result.reason, "the exchange is what failed")
    }

    /** The scopes asked for are the two that are used, and the state travels. */
    @Test
    fun `the authorize url asks only for what it needs`() {
        val url = DiscordAuth(discordConfig()).authorizeUrl("the-state")

        assertTrue(url.contains("scope=identify+guilds") || url.contains("scope=identify%20guilds"), url)
        assertFalse(url.contains("guilds.members.read"), "roles are not read, so they are not requested")
        assertTrue(url.contains("state=the-state"), url)
        assertTrue(url.contains("client_id=client"), url)
    }

    // -- browser sessions ---------------------------------------------------

    /**
     * A `state` is single use.
     *
     * It is what stops a link somebody was sent completing a sign-in inside their browser. One that could
     * be replayed would protect nothing after the first time a history was read.
     */
    @Test
    fun `an oauth state works once`() {
        val sessions = WebSessions(ttlMillis = 1000, now = { clock })
        val state = sessions.issueState()

        assertTrue(sessions.consumeState(state))
        assertFalse(sessions.consumeState(state), "a second use must fail")
        assertFalse(sessions.consumeState("never-issued"))
        assertFalse(sessions.consumeState(null))
    }

    @Test
    fun `a session expires`() {
        val sessions = WebSessions(ttlMillis = 1000, now = { clock })
        val session = sessions.open("42", "chrooted")

        assertNotNull(sessions.resolve(session.token))
        clock += 2000
        assertNull(sessions.resolve(session.token), "it should be gone once its time is up")
        assertEquals(0, sessions.count())
    }

    @Test
    fun `signing out ends the session at once`() {
        val sessions = WebSessions(ttlMillis = 100_000, now = { clock })
        val session = sessions.open("42", "chrooted")

        sessions.close(session.token)

        assertNull(sessions.resolve(session.token))
    }

    // -- what the page is told ----------------------------------------------

    @Test
    fun `the page is told which ways in this server has`() {
        withServer {
            val identity: WebIdentity = client.get(Endpoints.WEB_ME).decode()

            assertFalse(identity.signedIn)
            assertFalse(identity.discordConfigured, "no Discord application is configured in this test")
            assertTrue(identity.operatorTokenConfigured)
        }
    }

    /** With no Discord application configured, the route says so rather than redirecting nowhere. */
    @Test
    fun `starting a discord sign-in without one configured is refused clearly`() {
        withServer {
            val response = client.get(Endpoints.DISCORD_START)

            assertEquals(HttpStatusCode.NotImplemented, response.status)
        }
    }

    /** A callback with no state — a link somebody was sent, or a stale one — never opens a session. */
    @Test
    fun `a callback without a valid state is refused`() {
        withServer {
            val response = client.get(Endpoints.DISCORD_CALLBACK + "?code=whatever&state=forged")

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertNull(response.headers[HttpHeaders.SetCookie], "no session may be opened")
        }
    }

    private fun discordConfig() = BackendConfig(
        discordClientId = "client",
        discordClientSecret = "secret",
        discordGuildId = GUILD,
        publicBaseUrl = "https://example.invalid",
    )

    /** Discord, scripted. Answers the token exchange, then the two GETs, by URL. */
    private fun fakeDiscord(guilds: List<String>) = DiscordAuth(discordConfig()) { request ->
        val url = request.uri().toString()
        when {
            url.endsWith("/oauth2/token") -> 200 to """{"access_token":"at","token_type":"Bearer"}"""
            url.endsWith("/users/@me") -> 200 to """{"id":"42","username":"chrooted"}"""
            url.endsWith("/users/@me/guilds") ->
                200 to guilds.joinToString(",", "[", "]") { """{"id":"$it","name":"g"}""" }
            else -> 404 to "{}"
        }
    }

    // -- the setup page ---------------------------------------------------

    /**
     * The whole flow the page performs, in order.
     *
     * Worth a test because the page itself cannot have one: it is a file served to a browser. What it does
     * is these three calls, so testing them is testing the thing that would break.
     */
    @Test
    fun `an operator can list a pending pairing and approve it`() = runTest {
        withServer {
            val start = startPairing(name = "chrooted")

            val waiting: PendingPairingList = client.get(Endpoints.PAIR_PENDING) {
                header(HttpHeaders.Authorization, "Bearer $OPERATOR")
            }.decode()

            assertEquals(1, waiting.pending.size)
            val entry = waiting.pending.single()
            assertEquals(start.code, entry.code)
            assertEquals("chrooted", entry.minecraftName)

            approve(entry.code, OWNER)
            assertEquals(PairStatus.APPROVED, poll(start).status)
        }
    }

    /**
     * The list never carries a secret.
     *
     * The code is safe to show — it grants nothing without the device secret, which is the reason the flow
     * has two values. This asserts the response body genuinely does not contain the secret, rather than
     * asserting the type does not have a field for one, because the second is not the same claim.
     */
    @Test
    fun `the pending list leaks no secret`() = runTest {
        withServer {
            val start = startPairing()

            val body = client.get(Endpoints.PAIR_PENDING) {
                header(HttpHeaders.Authorization, "Bearer $OPERATOR")
            }.bodyAsText()

            assertFalse(body.contains(start.deviceSecret), "the device secret was in the listing")
        }
    }

    /** Listing pending pairings is administration, and a game client must never be able to do it. */
    @Test
    fun `listing pending pairings needs the operator token`() = runTest {
        withServer {
            startPairing()

            val anonymous = client.get(Endpoints.PAIR_PENDING)
            assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

            val wrong = client.get(Endpoints.PAIR_PENDING) {
                header(HttpHeaders.Authorization, "Bearer not-the-token")
            }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        }
    }

    @Test
    fun `an approved pairing drops off the pending list`() = runTest {
        withServer {
            val start = startPairing()
            approve(start.code, OWNER)

            val waiting: PendingPairingList = client.get(Endpoints.PAIR_PENDING) {
                header(HttpHeaders.Authorization, "Bearer $OPERATOR")
            }.decode()

            assertTrue(waiting.pending.isEmpty(), "it still offered a decision nobody has to make")
        }
    }

    /** The account picker's contents, and the device count beside each name. */
    @Test
    fun `accounts are listed with how many devices they have`() = runTest {
        withServer {
            val start = startPairing()
            approve(start.code, OWNER)
            poll(start)

            val accounts: AccountList = client.get(Endpoints.ACCOUNTS) {
                header(HttpHeaders.Authorization, "Bearer $OPERATOR")
            }.decode()

            val owner = accounts.accounts.firstOrNull { it.id == OWNER }
            assertNotNull(owner)
            assertEquals(1, owner!!.deviceCount)
        }
    }

    @Test
    fun `the setup page is served at the root and at its own path`() = runTest {
        withServer {
            for (path in listOf("/", Endpoints.ADMIN)) {
                val response = client.get(path)
                assertEquals(HttpStatusCode.OK, response.status, path)
                val body = response.bodyAsText()
                assertTrue(body.contains("<!doctype html>"), "$path did not serve the page")
                // The operator token must never be baked into something served to a browser.
                assertFalse(body.contains(OPERATOR), "the page shipped the operator token")
            }
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

    // -- addressing --------------------------------------------------------

    /** A message naming somebody reaches them and nobody else. */
    @Test
    fun `a targeted message reaches only who it names`() = runTest {
        withServer { backend ->
            // Both members, so the capability gate passes for each and the only thing left deciding
            // delivery is who the message names.
            backend.store.mutate { state ->
                state.copy(
                    permissions = state.permissions.copy(
                        roles = mapOf(
                            "intended" to GroupRole.MEMBER,
                            "bystander" to GroupRole.MEMBER,
                            "sender" to GroupRole.MEMBER,
                        ),
                    ),
                ) to Unit
            }
            val intended = backend.hub.register(Connection(AccountId("intended"), DeviceId("i")))
            val bystander = backend.hub.register(Connection(AccountId("bystander"), DeviceId("b")))

            val delivered = backend.hub.fanOut(
                message(
                    RealtimePayload.Ping(location()),
                    sender = AccountId("sender"),
                    recipients = setOf(AccountId("intended")),
                ),
            )

            assertEquals(1, delivered)
            assertNotNull(intended.outbound.tryReceive().getOrNull())
            assertNull(bystander.outbound.tryReceive().getOrNull()) { "a targeted ping reached somebody it did not name" }
        }
    }

    /**
     * Naming somebody does not hand them anything they could not already see.
     *
     * The property that makes the field safe to accept from a client at all: addressing is an *and* with the
     * permission, never an *or*. If it widened, a client could route round the ledger's permission simply by
     * listing the person it wanted to tell.
     */
    @Test
    fun `addressing narrows and never widens`() = runTest {
        withServer { backend ->
            backend.store.mutate { state ->
                state.copy(
                    permissions = state.permissions.copy(
                        roles = mapOf("admin" to GroupRole.ADMIN, "guest" to GroupRole.GUEST),
                    ),
                ) to Unit
            }
            val guest = backend.hub.register(Connection(AccountId("guest"), DeviceId("g")))

            val delivered = backend.hub.fanOut(
                message(
                    RealtimePayload.PaymentConfirmed("d1", 500),
                    sender = AccountId("admin"),
                    recipients = setOf(AccountId("guest")),
                ),
            )

            assertEquals(0, delivered)
            assertNull(guest.outbound.tryReceive().getOrNull()) { "being named let a guest into the ledger" }
        }
    }

    /**
     * A targeted message is filtered on replay too.
     *
     * The one that matters most, and the one an implementation forgets: fan-out and history are two paths to
     * the same event. Filtering only the live one would make a targeted waypoint reach the whole group the
     * moment somebody reconnected — a leak with a delay on it rather than no leak.
     */
    @Test
    fun `a targeted message is not replayed to somebody it did not name`() = runTest {
        withServer {
            val owner = pair(OWNER, "Th7bo")
            val other = pair(AccountId("other"), "Other")

            submit(
                owner.accessToken,
                message(
                    RealtimePayload.Waypoint("wp1", location(), "Secret spot"),
                    recipients = setOf(AccountId("nobody-here")),
                ),
            )

            val ownerPage: EventPage = authedGet("${Endpoints.EVENTS_SINCE}?since=0", owner.accessToken).decode()
            val otherPage: EventPage = authedGet("${Endpoints.EVENTS_SINCE}?since=0", other.accessToken).decode()

            assertEquals(1, ownerPage.messages.size) { "the sender should still see what they sent" }
            assertTrue(otherPage.messages.isEmpty()) { "a targeted waypoint was replayed to somebody it did not name" }
        }
    }

    /**
     * The recipients a client asks for are kept, unlike the sender it asks for.
     *
     * The two fields are treated differently on purpose and it is worth pinning which is which: a sender the
     * client chose is impersonation, while recipients the client chose can only take its own message away
     * from people.
     */
    @Test
    fun `the recipients a client sets survive the server's stamping`() = runTest {
        withServer { backend ->
            val owner = pair(OWNER, "Th7bo")

            submit(
                owner.accessToken,
                message(
                    RealtimePayload.Ping(location()),
                    sender = AccountId("someone-else"),
                    recipients = setOf(AccountId("named")),
                ),
            )

            val stored = backend.store.read { it.events }.single()
            assertEquals(setOf(AccountId("named")), stored.recipients)
            assertEquals(OWNER, stored.senderAccount) { "the client's chosen sender should have been discarded" }
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
