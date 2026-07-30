package dev.th7bo.sidequest.protocol

import dev.th7bo.sidequest.platform.permission.GroupRole
import dev.th7bo.sidequest.platform.permission.PermissionSettings
import kotlinx.serialization.Serializable

/**
 * An account on the backend. The identity everything belongs to.
 *
 * Distinct from a [dev.th7bo.sidequest.platform.player.PlayerId], and that separation is the security
 * boundary of the whole system. A Minecraft UUID is something a client *claims*; an account id is
 * something the server *issued*. Group membership, roles and permissions hang off the account, so a
 * client lying about its Minecraft UUID gains nothing.
 */
@Serializable
@JvmInline
public value class AccountId(public val value: String) {
    override fun toString(): String = value
}

/** One paired client. An account may have several — a desktop, a laptop, the dashboard. */
@Serializable
@JvmInline
public value class DeviceId(public val value: String) {
    override fun toString(): String = value
}

// -- pairing ---------------------------------------------------------------

/**
 * Step one: the mod asks to be paired.
 *
 * Unauthenticated, because the whole point is that the mod has no credentials yet.
 *
 * The Minecraft identity here is an **assertion**, not a proof. The client says "I am this UUID" and
 * the server cannot check it — there is no way to verify a Minecraft session without Mojang's
 * join-server handshake, and the plan explicitly forbids touching session tokens. What binds the
 * device to an account is the *approval step*, performed by somebody already authenticated. The
 * asserted UUID is recorded for display and for matching players in game, and nothing is granted on
 * the strength of it.
 */
@Serializable
public data class PairStartRequest(
    /** What the client believes its Minecraft UUID is. Recorded, never trusted. */
    public val minecraftUuid: String,
    /** What the client believes its Minecraft name is. Display only. */
    public val minecraftName: String,
    /** Shown in the session list so a user can tell their devices apart. */
    public val deviceName: String,
    public val protocolVersion: Int = Protocol.VERSION,
)

/**
 * The code to type, and the secret to poll with.
 *
 * Two separate values, and both are needed. The **code** is short so a human can read it out and type
 * it, which also makes it guessable — so it grants nothing on its own. The **secret** is long, never
 * shown to anybody, and is what proves a poll comes from the device that started the pairing. Without
 * it, anybody who guessed a six-character code could poll for somebody else's tokens.
 */
@Serializable
public data class PairStartResponse(
    public val deviceId: DeviceId,
    /** Short, human-typable, single-use, and worthless without [deviceSecret]. */
    public val code: String,
    /** Long and private. Stored by the client, sent on every poll, never displayed. */
    public val deviceSecret: String,
    public val expiresAtMillis: Long,
    /** How long to wait between polls. The server sets the pace, not the client. */
    public val pollIntervalMillis: Long = 2_000,
)

/** Step two, repeated: has anybody approved us yet? */
@Serializable
public data class PairPollRequest(
    public val deviceId: DeviceId,
    public val deviceSecret: String,
)

/**
 * Where a pairing has got to.
 *
 * [DENIED] and [EXPIRED] are distinguished because they mean different things to a user: one is "the
 * operator said no", the other is "you were too slow". Collapsing them would make the mod tell
 * somebody the wrong thing about why they cannot get in.
 */
@Serializable
public enum class PairStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED,
    /** The device id or secret did not match anything. Treated as terminal. */
    UNKNOWN,
}

@Serializable
public data class PairPollResponse(
    public val status: PairStatus,
    /** Present only on [PairStatus.APPROVED]. */
    public val session: SessionTokens? = null,
)

/**
 * Step three: an authenticated operator approves the code.
 *
 * Never called by the mod. This is the half performed from the dashboard — or, until the dashboard
 * exists, with the server's bootstrap operator token — and it is where the trust comes from: the
 * approving party is authenticated, and they are the one deciding which account the device belongs to.
 */
@Serializable
public data class PairApproveRequest(
    public val code: String,
    /** The account the device is being bound to. */
    public val accountId: AccountId,
    /** False to deny rather than approve, which is what a user does with a code they did not expect. */
    public val approve: Boolean = true,
)

// -- tokens ----------------------------------------------------------------

/**
 * What a paired client holds.
 *
 * Two tokens, with sharply different lifetimes, for the usual reason: the access token travels on
 * every request and so has the most exposure, and a short life bounds the damage from one leaking.
 * The refresh token travels rarely, is revocable on its own, and is the thing worth storing carefully.
 */
@Serializable
public data class SessionTokens(
    public val accessToken: String,
    public val accessExpiresAtMillis: Long,
    public val refreshToken: String,
    public val accountId: AccountId,
    public val deviceId: DeviceId,
    /**
     * What this device may do, which may be less than the account's role allows.
     *
     * The plan asks for scoped device tokens, and this is the scope. A dashboard session and a game
     * client are the same account with different needs, and a token that can do everything the account
     * can is a token whose leak costs everything.
     */
    public val scopes: Set<TokenScope> = TokenScope.DEFAULT,
    public val role: GroupRole = GroupRole.MEMBER,
)

/**
 * What a token is allowed to be used for.
 *
 * Coarse on purpose — these gate whole categories of endpoint, where [dev.th7bo.sidequest.platform
 * .permission.Permission] gates individual actions. Both are checked: the scope says "this device may
 * write at all", the permission says "this person may create a debt".
 */
@Serializable
public enum class TokenScope {
    /** Read the group, its members and the event history. */
    READ,

    /** Submit events. */
    WRITE,

    /** Open the realtime connection. */
    REALTIME,

    /** Approve pairings, change roles, moderate. Never granted to a game client by default. */
    ADMINISTER,
    ;

    public companion object {
        /** What a game client gets. Deliberately without [ADMINISTER]. */
        public val DEFAULT: Set<TokenScope> = setOf(READ, WRITE, REALTIME)
    }
}

@Serializable
public data class RefreshRequest(public val refreshToken: String)

/** One of an account's devices, for the session list the plan asks for. */
@Serializable
public data class DeviceSummary(
    public val deviceId: DeviceId,
    public val deviceName: String,
    /** The Minecraft name this device asserted at pairing. Display only. */
    public val minecraftName: String,
    public val pairedAtMillis: Long,
    public val lastSeenAtMillis: Long,
    public val scopes: Set<TokenScope>,
    /** True for the device asking, so a session list can say "this device". */
    public val isCurrent: Boolean = false,
)

@Serializable
public data class SessionList(public val devices: List<DeviceSummary>)

@Serializable
public data class RevokeRequest(
    /** Null revokes the calling device, which is what "sign out" means. */
    public val deviceId: DeviceId? = null,
)

// -- the group -------------------------------------------------------------

/** A member of the friend group, as the server sees them. */
@Serializable
public data class GroupMember(
    public val accountId: AccountId,
    public val displayName: String,
    public val role: GroupRole,
    /** The Minecraft UUID this account's devices have asserted, when any have. Display only. */
    public val minecraftUuid: String? = null,
    public val minecraftName: String? = null,
)

/**
 * The group, as one payload.
 *
 * Members and permissions together, because they are read together and a client that fetched them
 * separately could act on a role from one moment and an override from another.
 */
@Serializable
public data class GroupState(
    public val members: List<GroupMember> = emptyList(),
    public val permissions: PermissionSettings = PermissionSettings.Default,
    /** Bumped by the server on every change, so a client can tell whether it is current. */
    public val revision: Long = 0,
)
