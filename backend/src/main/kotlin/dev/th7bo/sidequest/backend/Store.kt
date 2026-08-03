package dev.th7bo.sidequest.backend

import dev.th7bo.sidequest.platform.permission.PermissionSettings
import dev.th7bo.sidequest.protocol.AccountId
import dev.th7bo.sidequest.protocol.DeviceId
import dev.th7bo.sidequest.protocol.RealtimeMessage
import dev.th7bo.sidequest.protocol.TokenScope
import dev.th7bo.sidequest.platform.permission.GroupRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * An account, as the server stores it.
 *
 * The Minecraft identity is on the *device*, not here, and that placement is the security boundary: an
 * account is what the server issued and a Minecraft UUID is what a client claimed. Keeping the claim
 * next to the thing that made it stops it drifting into the identity everything is keyed on.
 */
@Serializable
public data class Account(
    public val id: AccountId,
    public val displayName: String,
    public val role: GroupRole,
    public val createdAtMillis: Long,
)

/**
 * A paired device.
 *
 * **[refreshTokenHash] and not the token.** A server that stores tokens as it issued them is a server
 * whose one bad day — a backup on a shared drive, a stray log line, somebody reading a file over their
 * shoulder — hands out working credentials for every device. A hash cannot be replayed.
 */
@Serializable
public data class Device(
    public val id: DeviceId,
    public val accountId: AccountId,
    public val deviceName: String,
    /** What this device asserted at pairing. Display only, never trusted, never a key. */
    public val minecraftUuid: String,
    public val minecraftName: String,
    public val scopes: Set<TokenScope>,
    public val refreshTokenHash: String,
    public val pairedAtMillis: Long,
    public var lastSeenAtMillis: Long,
    public val isRevoked: Boolean = false,
)

/**
 * A pairing waiting to be approved.
 *
 * **[secretHash] and not the secret**, for the same reason as a refresh token: it is a credential while
 * it lives, and a stored credential is a credential that can be stolen.
 */
@Serializable
public data class PendingPairing(
    public val deviceId: DeviceId,
    public val code: String,
    public val secretHash: String,
    public val minecraftUuid: String,
    public val minecraftName: String,
    public val deviceName: String,
    public val expiresAtMillis: Long,
    public val approvedBy: AccountId? = null,
    public val denied: Boolean = false,
)

/** Everything the server keeps, as one document. */
@Serializable
public data class ServerState(
    public val accounts: List<Account> = emptyList(),
    public val devices: List<Device> = emptyList(),
    public val pending: List<PendingPairing> = emptyList(),
    public val permissions: PermissionSettings = PermissionSettings.Default,
    public val groupRevision: Long = 0,
    /**
     * The event log, bounded.
     *
     * Bounded because a friend group's realtime traffic is mostly presence, and keeping every "I am in
     * the Hub" forever would grow a file nobody ever reads. The bound is what makes
     * [dev.th7bo.sidequest.protocol.RealtimeWelcome.resumeGap] a real condition rather than a
     * theoretical one, and handling it is why the client can survive being away for a week.
     */
    public val events: List<RealtimeMessage> = emptyList(),
    public val sequence: Long = 0,
)

/**
 * The server's state, on disk.
 *
 * **On not using a database.** This is a private server for a handful of people, and the whole state is
 * a few hundred kilobytes. A file behind a read-write lock is correct at that size, and it has no schema
 * to migrate, no connection pool to tune and no second process to install. The interface is narrow
 * enough that swapping in SQLite later touches this file and nothing else — which is the property worth
 * having, rather than the database.
 *
 * Writes are atomic and backed up, for the same reasons the client's storage is: this file holds the
 * group's history, and a crash mid-write must not be able to truncate it.
 */
public class ServerStore(
    private val file: Path,
    private val eventWindow: Int = DEFAULT_EVENT_WINDOW,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * A read-write lock, not a mutex.
     *
     * Reads dominate by a wide margin — every request reads the account and the device — and they do not
     * conflict with each other. A mutex would serialise a hundred concurrent reads behind one write.
     */
    private val lock = ReentrantReadWriteLock()

    private var state: ServerState = load()

    /** Reads under the shared lock. */
    public fun <T> read(block: (ServerState) -> T): T = lock.read { block(state) }

    /**
     * Transforms the state and persists it, under the exclusive lock.
     *
     * Persisted inside the lock deliberately. Writing outside it would let two writes land on disk in
     * the opposite order to the one they were applied in, and the file would then disagree with memory
     * until the next restart — at which point it would win.
     */
    public fun <T> mutate(block: (ServerState) -> Pair<ServerState, T>): T = lock.write {
        val (next, result) = block(state)
        state = next
        persist(next)
        result
    }

    /**
     * Appends an event, assigning it its sequence number.
     *
     * The only place a sequence is assigned, which is what makes it monotonic. A client that could
     * choose its own place in the order could rewrite history by claiming an earlier one.
     */
    public fun append(message: RealtimeMessage): RealtimeMessage = mutate { current ->
        val sequence = current.sequence + 1
        val stamped = message.copy(sequence = sequence)
        val events = (current.events + stamped).takeLast(eventWindow)
        current.copy(events = events, sequence = sequence) to stamped
    }

    /**
     * Events after [sequence], and whether the caller's resume point had already fallen out.
     *
     * The gap flag is the honest answer to "I have been away a while": the alternative is to hand back
     * the oldest events we still have and let the client believe it is caught up, which leaves a hole it
     * does not know about.
     */
    public fun eventsSince(sequence: Long): Pair<List<RealtimeMessage>, Boolean> = read { current ->
        val oldest = current.events.firstOrNull()?.sequence
        val hasGap = sequence > 0 && oldest != null && sequence < oldest - 1
        current.events.filter { it.sequence > sequence } to hasGap
    }

    private fun load(): ServerState {
        if (!Files.exists(file)) return ServerState()
        return runCatching { json.decodeFromString(ServerState.serializer(), Files.readString(file)) }
            .getOrElse { thrown ->
                // Kept, not deleted: this file is the group's history and the only copy of it.
                val quarantined = file.resolveSibling("${file.fileName}.corrupt-${System.currentTimeMillis()}")
                runCatching { Files.move(file, quarantined) }
                System.err.println("Sidequest: state file was unreadable and was kept at $quarantined ($thrown)")
                ServerState()
            }
    }

    private fun persist(state: ServerState) {
        // Null for a bare relative filename, which is the *default* state path — so this threw on the first
        // write of every fresh install and the server could not save anything at all. It failed at the point
        // somebody was trying to pair their first client, which is the worst possible moment for a server to
        // look broken for a reason nothing explains.
        file.parent?.let { Files.createDirectories(it) }
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(ServerState.serializer(), state))
        if (Files.exists(file)) {
            runCatching {
                Files.copy(file, file.resolveSibling("${file.fileName}.bak"), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        runCatching {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    public companion object {
        /**
         * How many events to keep.
         *
         * Enough that a client offline for an evening resumes cleanly, small enough that the file stays
         * a file. A client that has been away longer re-fetches state, which is one request.
         */
        public const val DEFAULT_EVENT_WINDOW: Int = 2_000
    }
}
