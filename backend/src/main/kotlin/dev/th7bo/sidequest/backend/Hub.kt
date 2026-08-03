package dev.th7bo.sidequest.backend

import dev.th7bo.sidequest.platform.permission.Permission
import dev.th7bo.sidequest.platform.permission.PermissionKind
import dev.th7bo.sidequest.platform.permission.PermissionSettings
import dev.th7bo.sidequest.platform.skyblock.Activity
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.protocol.AccountId
import dev.th7bo.sidequest.protocol.DeviceId
import dev.th7bo.sidequest.protocol.RealtimeMessage
import dev.th7bo.sidequest.protocol.RealtimePayload
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/** One connected client. */
public class Connection(
    public val accountId: AccountId,
    public val deviceId: DeviceId,
) {
    /**
     * Outbound messages, buffered and bounded.
     *
     * Bounded with drop-oldest rather than unbounded. A client that has stopped reading — a laptop that
     * slept with the socket open — would otherwise make the server hold an unbounded queue on its behalf,
     * and one such client is enough to exhaust a homelab's memory. Dropping its oldest frames costs it a
     * resume, which it is going to do anyway.
     */
    public val outbound: Channel<RealtimeMessage> =
        Channel(OUTBOUND_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    public companion object {
        public const val OUTBOUND_BUFFER: Int = 256
    }
}

/**
 * Who is connected, and who may see what.
 *
 * The fan-out is where the privacy model becomes real, and it does three things a naive broadcast does not.
 *
 * **The sender's disclosures are enforced here, on the server.** A client that shares its island but not
 * its position could be trusted to leave the position out — and trusting a client is how a privacy
 * setting becomes advisory. The server strips what the sender has not agreed to share, so a buggy or
 * modified client cannot leak more than its owner allowed.
 *
 * **The receiver's capabilities are checked here too.** Somebody with no business in the ledger does not
 * receive a copy of it. That is not tidiness: a debt event names who owes what.
 *
 * **A message addressed to named people goes only to them.** The narrowing is done here rather than left to
 * the receiving client, because a client that receives something and chooses not to draw it has still
 * received it.
 */
public class RealtimeHub(
    private val store: ServerStore,
) {

    private val connections = ConcurrentHashMap<DeviceId, Connection>()

    public val connectionCount: Int get() = connections.size

    /** Accounts with at least one live connection. What presence is derived from. */
    public fun onlineAccounts(): Set<AccountId> = connections.values.mapTo(HashSet()) { it.accountId }

    public fun register(connection: Connection): Connection {
        // A second connection from the same device replaces the first. A client that reconnected before
        // its old socket was noticed would otherwise have two, and every message would arrive twice.
        connections.put(connection.deviceId, connection)?.outbound?.close()
        return connection
    }

    public fun unregister(deviceId: DeviceId) {
        connections.remove(deviceId)?.outbound?.close()
    }

    /**
     * Sends [message] to everybody entitled to see it.
     *
     * @param from the device that sent it, so it is not echoed back. The client already knows what it
     *   sent, and an echo would make every optimistic update arrive twice.
     * @return how many connections it reached, which is what makes "nobody got my ping" distinguishable
     *   from "nobody was online".
     */
    public fun fanOut(message: RealtimeMessage, from: DeviceId? = null): Int {
        val permissions = store.read { it.permissions }
        var delivered = 0

        for (connection in connections.values) {
            if (connection.deviceId == from) continue
            val visible = redactFor(message, connection.accountId, permissions) ?: continue
            connection.outbound.trySend(visible)
            delivered++
        }
        return delivered
    }

    /**
     * What [viewer] is allowed to see of [message], or null for nothing at all.
     *
     * Two gates, in order. The receiver's capability decides whether they see the event at all; the
     * sender's disclosure decides how much of it survives.
     */
    private fun redactFor(
        message: RealtimeMessage,
        viewer: AccountId,
        permissions: PermissionSettings,
    ): RealtimeMessage? {
        val scope = message.scope

        // Addressing, first and on its own. It can only remove people — every permission gate below still
        // runs — so naming somebody as a recipient can never be a way round one.
        if (!message.isAddressedTo(viewer)) return null

        if (scope.kind == PermissionKind.CAPABILITY) {
            // A capability-scoped event goes to people who could have performed it themselves.
            if (!permissions.can(viewer.value, scope)) return null
            return message
        }

        // A disclosure. The sender decides; the server enforces rather than trusting.
        val sender = message.senderAccount ?: return message
        if (!permissions.shares(sender.value, scope, viewer.value)) return null
        return redactPresence(message, sender, viewer, permissions)
    }

    /**
     * Strips the parts of a presence the sender has not agreed to share with this viewer.
     *
     * Field by field, because the disclosures are per field: somebody may share their activity and not
     * their island, or their island and not their exact position. Suppressing the whole message on the
     * strictest setting would make the most private choice also the one that turns the feature off.
     */
    private fun redactPresence(
        message: RealtimeMessage,
        sender: AccountId,
        viewer: AccountId,
        permissions: PermissionSettings,
    ): RealtimeMessage {
        val presence = message.payload as? RealtimePayload.Presence ?: return message

        val redacted = presence.copy(
            activity = if (permissions.shares(sender.value, Permission.VIEW_ACTIVITY, viewer.value)) {
                presence.activity
            } else {
                Activity.UNKNOWN
            },
            island = if (permissions.shares(sender.value, Permission.VIEW_ISLAND, viewer.value)) {
                presence.island
            } else {
                Island.NONE
            },
            location = presence.location?.takeIf {
                permissions.shares(sender.value, Permission.VIEW_EXACT_POSITION, viewer.value)
            },
        )
        return message.copy(payload = redacted)
    }

    /** Closes every connection. For shutdown. */
    public fun closeAll() {
        connections.values.forEach { it.outbound.close() }
        connections.clear()
    }
}
