package dev.th7bo.sidequest.backend

import dev.th7bo.sidequest.protocol.AccountId
import java.nio.file.Path

/**
 * How the server is set up.
 *
 * Read from the environment rather than a file, because the one value here that matters is a secret and
 * a secret in a file is a secret that ends up in a backup, a screenshot or a git repository. An
 * environment variable is not a vault, but it is a great deal better than `config.json` next to the
 * state file.
 */
public data class BackendConfig(
    public val port: Int = DEFAULT_PORT,
    public val host: String = DEFAULT_HOST,
    public val statePath: Path = Path.of("sidequest-state.json"),

    /**
     * The bootstrap operator credential, or null to disable administration entirely.
     *
     * Null is a supported and sensible state: a server whose group is already set up does not need it,
     * and a credential that is not present cannot be stolen. Approving a pairing is then impossible
     * until it is set again, which is the correct trade for a server nobody is currently pairing to.
     */
    public val operatorToken: String? = null,

    /** The account the bootstrap operator acts as. */
    public val ownerAccountId: AccountId = AccountId("owner"),

    /**
     * How long an access token lives.
     *
     * Short, because it travels on every request and so has the most exposure of anything here. Fifteen
     * minutes bounds the damage from one leaking to fifteen minutes.
     */
    public val accessTtlMillis: Long = 15 * 60 * 1_000,

    /**
     * How long a pairing code is valid.
     *
     * Long enough to alt-tab to a dashboard and type six characters; short enough that a code read aloud
     * in a voice call is not still valid tomorrow.
     */
    public val pairingTtlMillis: Long = 5 * 60 * 1_000,

    public val pairingPollIntervalMillis: Long = 2_000,

    /**
     * How many requests one device may make per minute.
     *
     * Not protection against an attacker — anybody with a token is already inside — but against a client
     * with a bug. A retry loop with no backoff can saturate a homelab server from one laptop, and the
     * limit turns that from an outage into a log line.
     */
    public val requestsPerMinute: Int = 240,
) {

    public companion object {
        public const val DEFAULT_PORT: Int = 8710
        public const val DEFAULT_HOST: String = "0.0.0.0"

        /**
         * Reads the environment.
         *
         * Every value has a default except the operator token, which has no safe default: inventing one
         * would mean every deployment that did not set it shipped with the same credential.
         */
        public fun fromEnvironment(getenv: (String) -> String? = System::getenv): BackendConfig =
            BackendConfig(
                port = getenv("SIDEQUEST_PORT")?.toIntOrNull() ?: DEFAULT_PORT,
                host = getenv("SIDEQUEST_HOST") ?: DEFAULT_HOST,
                statePath = getenv("SIDEQUEST_STATE")?.let(Path::of) ?: Path.of("sidequest-state.json"),
                operatorToken = getenv("SIDEQUEST_OPERATOR_TOKEN")?.takeIf { it.isNotBlank() },
                ownerAccountId = AccountId(getenv("SIDEQUEST_OWNER_ACCOUNT") ?: "owner"),
                requestsPerMinute = getenv("SIDEQUEST_RATE_LIMIT")?.toIntOrNull() ?: 240,
            )
    }
}
