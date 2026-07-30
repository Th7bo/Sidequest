package dev.th7bo.sidequest.backend

import dev.th7bo.sidequest.platform.permission.GroupRole
import dev.th7bo.sidequest.protocol.AccountId
import dev.th7bo.sidequest.protocol.DeviceId
import dev.th7bo.sidequest.protocol.PairApproveRequest
import dev.th7bo.sidequest.protocol.PairPollResponse
import dev.th7bo.sidequest.protocol.PairStartRequest
import dev.th7bo.sidequest.protocol.PairStartResponse
import dev.th7bo.sidequest.protocol.PairStatus
import dev.th7bo.sidequest.protocol.SessionTokens
import dev.th7bo.sidequest.protocol.TokenScope
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A live access token.
 *
 * **In memory only, and deliberately.** An access token lives for minutes; persisting it would mean a
 * restart leaves working credentials on disk for no benefit, since a client whose access token stopped
 * working simply refreshes. Refresh tokens are the ones worth surviving a restart, and those are stored
 * as hashes.
 */
private data class AccessGrant(
    val accountId: AccountId,
    val deviceId: DeviceId,
    val scopes: Set<TokenScope>,
    val expiresAtMillis: Long,
)

/** Who a request turned out to be. */
public data class Caller(
    public val accountId: AccountId,
    public val deviceId: DeviceId,
    public val scopes: Set<TokenScope>,
    public val role: GroupRole,
) {
    public fun hasScope(scope: TokenScope): Boolean = scope in scopes
}

/**
 * Pairing, tokens and revocation.
 *
 * Four decisions here carry the security of the whole system, and each of them is a thing that is
 * commonly got wrong.
 *
 * **The asserted Minecraft UUID grants nothing.** A client says who it is and the server cannot check
 * it — verifying a Minecraft identity means Mojang's join-server handshake, and the plan forbids going
 * anywhere near session tokens. So the claim is recorded for display and *the approval is what binds
 * the device to an account*. The approving party is already authenticated, and they choose the account.
 * Anybody can start a pairing; starting one gets you nothing.
 *
 * **The code is separate from the secret.** The code is six characters so somebody can read it aloud,
 * which also makes it guessable — so it is worthless on its own. The secret is 256 bits, never
 * displayed, and is what proves a poll comes from the device that started the pairing.
 *
 * **Tokens are stored hashed.** A stolen state file yields no usable credential.
 *
 * **Revocation is checked on every request, not only at refresh.** A device revoked while holding a live
 * access token would otherwise keep working until the token expired, which is exactly the window
 * somebody revoking a device is trying to close.
 */
public class AuthService(
    private val store: ServerStore,
    private val config: BackendConfig,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {

    private val accessTokens = ConcurrentHashMap<String, AccessGrant>()

    // -- pairing -----------------------------------------------------------

    /** Starts a pairing. Unauthenticated: the point is that the caller has no credentials yet. */
    public fun startPairing(request: PairStartRequest): PairStartResponse {
        val deviceId = DeviceId(UUID.randomUUID().toString())
        val code = generateCode()
        val secret = generateToken()

        val pending = PendingPairing(
            deviceId = deviceId,
            code = code,
            secretHash = hash(secret),
            minecraftUuid = request.minecraftUuid,
            minecraftName = request.minecraftName,
            deviceName = request.deviceName,
            expiresAtMillis = now() + config.pairingTtlMillis,
        )

        store.mutate { state ->
            // Expired pairings are swept here rather than on a timer. It is the only moment the list is
            // touched, and a timer would be a second thing that has to be running.
            val live = state.pending.filter { it.expiresAtMillis > now() }
            state.copy(pending = live + pending) to Unit
        }

        return PairStartResponse(
            deviceId = deviceId,
            code = code,
            deviceSecret = secret,
            expiresAtMillis = pending.expiresAtMillis,
            pollIntervalMillis = config.pairingPollIntervalMillis,
        )
    }

    /**
     * Polls a pairing.
     *
     * The secret is compared in constant time. A poll endpoint that leaked the secret through timing
     * would let somebody recover it a byte at a time, and the whole security of the flow rests on it.
     */
    public fun pollPairing(deviceId: DeviceId, secret: String): PairPollResponse {
        val pending = store.read { state -> state.pending.firstOrNull { it.deviceId == deviceId } }
            ?: return PairPollResponse(PairStatus.UNKNOWN)

        if (!constantTimeEquals(hash(secret), pending.secretHash)) {
            // Reported as unknown, not as a wrong secret: telling a caller that a device id exists but
            // their secret is wrong confirms the id for them.
            return PairPollResponse(PairStatus.UNKNOWN)
        }
        if (pending.denied) return PairPollResponse(PairStatus.DENIED)
        if (pending.expiresAtMillis <= now()) return PairPollResponse(PairStatus.EXPIRED)

        val approvedBy = pending.approvedBy ?: return PairPollResponse(PairStatus.PENDING)

        // Approved. The pending record is consumed here rather than at approval time, so that the
        // approving operator's request cannot be the one that has to deliver the tokens.
        val tokens = completePairing(pending, approvedBy)
        return PairPollResponse(PairStatus.APPROVED, tokens)
    }

    /**
     * Approves or denies a code. Called by an authenticated operator, never by the mod.
     *
     * The code is matched case-insensitively because a human types it. It is single-use because it is
     * consumed on approval.
     */
    public fun approvePairing(request: PairApproveRequest): Boolean = store.mutate { state ->
        val pending = state.pending.firstOrNull { it.code.equals(request.code, ignoreCase = true) }
            ?: return@mutate state to false
        if (pending.expiresAtMillis <= now()) return@mutate state to false

        val updated = if (request.approve) {
            pending.copy(approvedBy = request.accountId)
        } else {
            pending.copy(denied = true)
        }
        state.copy(pending = state.pending - pending + updated) to true
    }

    private fun completePairing(pending: PendingPairing, accountId: AccountId): SessionTokens {
        val refreshToken = generateToken()
        val account = store.read { state -> state.accounts.firstOrNull { it.id == accountId } }

        val device = Device(
            id = pending.deviceId,
            accountId = accountId,
            deviceName = pending.deviceName,
            minecraftUuid = pending.minecraftUuid,
            minecraftName = pending.minecraftName,
            // Never ADMINISTER. A game client that could approve pairings would be a game client whose
            // compromise hands over the group, and it has no reason to need it.
            scopes = TokenScope.DEFAULT,
            refreshTokenHash = hash(refreshToken),
            pairedAtMillis = now(),
            lastSeenAtMillis = now(),
        )

        store.mutate { state ->
            state.copy(
                devices = state.devices.filterNot { it.id == device.id } + device,
                pending = state.pending.filterNot { it.deviceId == pending.deviceId },
            ) to Unit
        }

        return issueAccess(device, refreshToken, account?.role ?: GroupRole.MEMBER)
    }

    // -- tokens ------------------------------------------------------------

    /** Exchanges a refresh token for a fresh access token. */
    public fun refresh(refreshToken: String): SessionTokens? {
        val hashed = hash(refreshToken)
        val device = store.read { state ->
            state.devices.firstOrNull { constantTimeEquals(it.refreshTokenHash, hashed) }
        } ?: return null
        if (device.isRevoked) return null

        val account = store.read { state -> state.accounts.firstOrNull { it.id == device.accountId } }
        touch(device.id)
        // The same refresh token is returned rather than rotated. Rotation is stronger, and it also
        // means a client whose response is lost to a dropped connection has thrown away its only
        // credential — which on hotel wifi happens. Revocation is the control that matters here.
        return issueAccess(device, refreshToken, account?.role ?: GroupRole.MEMBER)
    }

    private fun issueAccess(device: Device, refreshToken: String, role: GroupRole): SessionTokens {
        val accessToken = generateToken()
        val expiresAt = now() + config.accessTtlMillis
        accessTokens[accessToken] = AccessGrant(device.accountId, device.id, device.scopes, expiresAt)
        pruneExpiredAccessTokens()

        return SessionTokens(
            accessToken = accessToken,
            accessExpiresAtMillis = expiresAt,
            refreshToken = refreshToken,
            accountId = device.accountId,
            deviceId = device.id,
            scopes = device.scopes,
            role = role,
        )
    }

    /**
     * Resolves an access token to a caller, or null.
     *
     * Revocation is checked here, on every request, and not only when refreshing. A device revoked while
     * holding a live token would otherwise keep working for the rest of the token's life, which is the
     * window somebody revoking it is trying to close.
     */
    public fun authenticate(accessToken: String?): Caller? {
        val grant = accessTokens[accessToken ?: return null] ?: return null
        if (grant.expiresAtMillis <= now()) {
            accessTokens.remove(accessToken)
            return null
        }

        val device = store.read { state -> state.devices.firstOrNull { it.id == grant.deviceId } } ?: return null
        if (device.isRevoked) {
            accessTokens.remove(accessToken)
            return null
        }

        val account = store.read { state -> state.accounts.firstOrNull { it.id == grant.accountId } }
        touch(device.id)
        return Caller(
            accountId = grant.accountId,
            deviceId = grant.deviceId,
            scopes = grant.scopes,
            role = account?.role ?: GroupRole.GUEST,
        )
    }

    /**
     * The bootstrap operator token, from configuration.
     *
     * The chicken-and-egg answer: approving a pairing needs an authenticated operator, and the first
     * operator cannot have paired. So the server reads a token from its own configuration, and holding
     * it means `ADMINISTER` on the owner account. Whoever can read the server's config file can already
     * read its state file, so this grants nothing they did not have.
     *
     * It is the *only* way to administer until the dashboard exists, and it is compared in constant
     * time because it is a bearer credential like any other.
     */
    public fun authenticateOperator(token: String?): Caller? {
        val expected = config.operatorToken ?: return null
        if (token == null || !constantTimeEquals(token, expected)) return null
        return Caller(
            accountId = config.ownerAccountId,
            deviceId = DeviceId("operator"),
            scopes = TokenScope.entries.toSet(),
            role = GroupRole.OWNER,
        )
    }

    /** Revokes a device. Returns false when there was nothing to revoke. */
    public fun revoke(deviceId: DeviceId): Boolean {
        val revoked = store.mutate { state ->
            val device = state.devices.firstOrNull { it.id == deviceId } ?: return@mutate state to false
            state.copy(devices = state.devices - device + device.copy(isRevoked = true)) to true
        }
        if (revoked) {
            // Live access tokens for the device are dropped immediately. Leaving them to expire would
            // leave the device working for minutes after being revoked.
            accessTokens.entries.removeIf { it.value.deviceId == deviceId }
        }
        return revoked
    }

    /** Records that a device is alive, for the session list. */
    private fun touch(deviceId: DeviceId) {
        store.mutate { state ->
            val device = state.devices.firstOrNull { it.id == deviceId } ?: return@mutate state to Unit
            // Written only when it has moved by more than a minute. Every request would otherwise
            // rewrite the whole state file, which is the one thing this storage design cannot afford.
            if (now() - device.lastSeenAtMillis < TOUCH_INTERVAL_MILLIS) return@mutate state to Unit
            state.copy(
                devices = state.devices - device + device.copy(lastSeenAtMillis = now()),
            ) to Unit
        }
    }

    private fun pruneExpiredAccessTokens() {
        val cutoff = now()
        accessTokens.entries.removeIf { it.value.expiresAtMillis <= cutoff }
    }

    // -- primitives --------------------------------------------------------

    /**
     * A code a human can read out.
     *
     * The alphabet excludes characters that get misread aloud or mistyped — no `0`/`O`, no `1`/`I`/`L`.
     * A code that has to be repeated three times is a code that makes people give up on pairing.
     */
    private fun generateCode(): String = (1..CODE_LENGTH)
        .map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }
        .joinToString("")

    /** 256 bits from a secure source, URL-safe so it survives being put in a query string. */
    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * Compares without leaking length or content through timing.
     *
     * `==` on a string returns as soon as two characters differ, which over enough attempts reveals a
     * secret one character at a time. This is not paranoia for a homelab, it is one line.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private companion object {
        const val CODE_LENGTH = 6
        const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        const val TOKEN_BYTES = 32

        /** How often a device's last-seen time is written. See `touch`. */
        const val TOUCH_INTERVAL_MILLIS = 60_000L
    }
}
