package dev.th7bo.sidequest.platform.backend

import dev.th7bo.sidequest.platform.event.SidequestEvent
import kotlinx.serialization.Serializable

/**
 * Where the backend is and whether to use it.
 *
 * All of it optional. Sidequest works with no backend at all — the local features are most of it — and a
 * group that has not set a server up should see no errors, no retries and no warnings. That is why
 * [baseUrl] being null is a supported state rather than a misconfiguration.
 */
@Serializable
public data class BackendConfig(
    /** e.g. `https://sidequest.example.net`. Null means "no backend", and everything degrades quietly. */
    public val baseUrl: String? = null,
    /** Shown in the session list on the server, so a user can tell their devices apart. */
    public val deviceName: String = "Minecraft",
) {
    public val isConfigured: Boolean get() = !baseUrl.isNullOrBlank()

    public companion object {
        public val None: BackendConfig = BackendConfig()
    }
}

/**
 * Where the connection stands.
 *
 * A state machine rather than a pair of booleans, because the states are not independent and the wrong
 * combinations are the ones that cause trouble: "paired but offline" and "not paired" want different
 * words in front of a user, and "refusing to talk" must not look like "trying".
 */
public enum class BackendState {
    /** No server configured. The default, and not a problem. */
    NOT_CONFIGURED,

    /** Configured, no credentials. Pairing is the way forward. */
    UNPAIRED,

    /** Pairing in progress: a code is on screen and the client is polling. */
    PAIRING,

    /** Paired, and the server is answering. */
    ONLINE,

    /**
     * Paired, and the server is not answering.
     *
     * Distinct from [UNPAIRED] because it is temporary and needs no user action. Writes queue.
     */
    OFFLINE,

    /**
     * The server will not talk to this build.
     *
     * Terminal until one side updates, and reported as its own state so the client stops retrying. A
     * client that treated this as [OFFLINE] would retry forever.
     */
    INCOMPATIBLE,

    /** This device was revoked. Terminal: the tokens are gone and pairing again is the only way back. */
    REVOKED,
    ;

    /** Whether it is worth attempting a request. */
    public val canAttempt: Boolean get() = this == ONLINE || this == OFFLINE

    /** Whether the user has to do something. */
    public val needsUser: Boolean get() = this == UNPAIRED || this == REVOKED || this == INCOMPATIBLE
}

/** The backend's state changed. */
public class BackendStateChangedEvent(
    public val previous: BackendState,
    public val state: BackendState,
    public val detail: String? = null,
) : SidequestEvent() {
    override fun describe(): String = "$previous -> $state" + (detail?.let { " ($it)" } ?: "")
}

/**
 * A pairing in progress, as a feature or a screen sees it.
 *
 * Carries the code because somebody has to type it somewhere else, and the deadline because a code that
 * has silently expired while sitting on screen is the most confusing possible failure.
 */
public data class PairingProgress(
    public val code: String,
    public val expiresAtMillis: Long,
    public val status: PairingStatus,
    public val detail: String? = null,
) {
    public fun secondsRemaining(nowMillis: Long): Long =
        ((expiresAtMillis - nowMillis) / 1_000).coerceAtLeast(0)
}

/** How a pairing attempt ended, in the client's own words. */
public enum class PairingStatus {
    WAITING,
    APPROVED,
    DENIED,
    EXPIRED,
    /** Could not reach the server, or it refused to start one. */
    FAILED,
}

/**
 * Where the device's credentials live.
 *
 * An interface, so the tokens can be kept somewhere better than a JSON file later without anything above
 * it changing. What it must do is in the contract rather than left to the implementation, because "store
 * a credential" is exactly the kind of thing that gets done casually.
 */
public interface TokenStore {

    /** The stored session, or null when this device has never been paired. */
    public suspend fun load(): StoredSession?

    public suspend fun save(session: StoredSession)

    /** Forgets the credentials. Called on revocation, and when a user signs out. */
    public suspend fun clear()
}

/**
 * What a paired device keeps.
 *
 * The **refresh token only**. The access token is deliberately absent: it lives for minutes, so storing
 * one buys nothing and leaves a credential on disk that a restart cannot invalidate. A client that has
 * just started refreshes, which is one request.
 */
@Serializable
public data class StoredSession(
    public val refreshToken: String,
    public val accountId: String,
    public val deviceId: String,
    /** The server this belongs to. A token from one server is meaningless at another. */
    public val baseUrl: String,
)
