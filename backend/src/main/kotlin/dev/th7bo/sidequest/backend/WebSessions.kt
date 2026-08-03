package dev.th7bo.sidequest.backend

import java.security.SecureRandom
import java.util.Base64

/** A signed-in browser. */
public data class WebSession(
    public val token: String,
    public val discordUserId: String,
    public val discordUsername: String,
    public val expiresAtMillis: Long,
)

/**
 * Who is signed in, in memory only.
 *
 * **Not persisted, deliberately.** A restart signing everybody out is the correct behaviour for something
 * used a handful of times during setup, and it means the one credential that can approve a device never
 * touches the disk. The state file is backed up, copied and read over shoulders; sessions are not worth
 * adding to it.
 *
 * The pending OAuth states live here too, for the same reason and with a much shorter life. They are what
 * stops a third party linking somebody to a callback they did not start — without one, a crafted link
 * could complete a sign-in in a victim's browser.
 */
public class WebSessions(
    private val ttlMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val sessions = HashMap<String, WebSession>()

    /** OAuth `state` values handed out and not yet returned, with when they stop being acceptable. */
    private val pendingStates = HashMap<String, Long>()

    private val random = SecureRandom()

    /** A fresh `state` for an authorisation redirect. */
    public fun issueState(): String = synchronized(this) {
        sweep()
        val state = randomToken()
        pendingStates[state] = now() + STATE_TTL_MILLIS
        state
    }

    /**
     * Consumes a returned `state`, or refuses it.
     *
     * Single use. A state that could be replayed would be a state that protects nothing after the first
     * time somebody's browser history is read.
     */
    public fun consumeState(state: String?): Boolean = synchronized(this) {
        sweep()
        if (state == null) return false
        val expiry = pendingStates.remove(state) ?: return false
        return expiry > now()
    }

    public fun open(discordUserId: String, discordUsername: String): WebSession = synchronized(this) {
        sweep()
        val session = WebSession(
            token = randomToken(),
            discordUserId = discordUserId,
            discordUsername = discordUsername,
            expiresAtMillis = now() + ttlMillis,
        )
        sessions[session.token] = session
        session
    }

    /** The session for a cookie value, or null when there is none or it has expired. */
    public fun resolve(token: String?): WebSession? = synchronized(this) {
        sweep()
        if (token == null) return null
        return sessions[token]?.takeIf { it.expiresAtMillis > now() }
    }

    public fun close(token: String?): Unit = synchronized(this) {
        if (token != null) sessions.remove(token)
    }

    public fun count(): Int = synchronized(this) { sweep(); sessions.size }

    /** Drops what has expired. Called on every operation, because the map is tiny and a timer is not. */
    private fun sweep() {
        val moment = now()
        sessions.entries.removeIf { it.value.expiresAtMillis <= moment }
        pendingStates.entries.removeIf { it.value <= moment }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        /** 256 bits. A session token is a bearer credential and is never typed by a human. */
        const val TOKEN_BYTES = 32

        /** How long an authorisation may take. Generous for a slow sign-in, far short of a stale link. */
        const val STATE_TTL_MILLIS = 10 * 60 * 1_000L
    }
}
