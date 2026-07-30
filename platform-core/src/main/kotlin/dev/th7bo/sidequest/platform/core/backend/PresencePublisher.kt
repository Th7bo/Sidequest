package dev.th7bo.sidequest.platform.core.backend

import dev.th7bo.sidequest.platform.event.DispatchMode
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.MinecraftDisconnectEvent
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.lifecycle.RegistrationScope
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.permission.Permission
import dev.th7bo.sidequest.platform.permission.PermissionService
import dev.th7bo.sidequest.platform.player.PlayerDirectory
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.player.PlayerPresence
import dev.th7bo.sidequest.platform.player.PresenceState
import dev.th7bo.sidequest.platform.skyblock.ContextEvent
import dev.th7bo.sidequest.platform.skyblock.GameContextService
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import dev.th7bo.sidequest.protocol.AccountId
import dev.th7bo.sidequest.protocol.GroupState
import dev.th7bo.sidequest.protocol.RealtimeMessage
import dev.th7bo.sidequest.protocol.RealtimePayload
import java.util.UUID

/**
 * Tells the group where we are, and remembers where they are.
 *
 * The piece that closes the loop: the realtime connection exists, the game context knows what the player
 * is doing, and this is what carries one to the other.
 *
 * **What is sent is filtered here, before it leaves.** The server strips what a sender has not agreed to
 * share, and this strips it too — deliberately twice. The server's check is the one that has to be
 * trusted, because a modified client could skip this one; but a client that sends a position it has been
 * told not to share is a client that has *had* that position in flight, and the cheapest place for private
 * data to not exist is before it is serialised.
 *
 * **Presence is throttled, not sent on every change.** The sub-location changes every few seconds while
 * walking across an island, and a message per change would make presence the loudest thing on the
 * connection by an order of magnitude. What matters to somebody looking at a friend list is the island and
 * the activity, and those change rarely.
 */
public class PresencePublisher(
    private val client: DefaultBackendClient,
    private val realtime: DefaultRealtimeClient,
    private val context: GameContextService,
    private val permissions: PermissionService,
    private val players: PlayerDirectory,
    private val events: EventBus,
    private val log: Logger,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val scope = RegistrationScope("presence")

    /** The last payload sent, so an unchanged one is not sent again. */
    private var lastSent: RealtimePayload.Presence? = null

    private var lastSentAtMillis: Long = 0

    /**
     * Account to Minecraft identity, from the group listing.
     *
     * Needed to put an incoming presence against a player the client can recognise. The mapping only
     * exists for accounts that have chosen to share their online status — the server omits it otherwise —
     * so a presence from somebody more private is recorded against nothing and simply not shown.
     */
    private var minecraftByAccount: Map<AccountId, PlayerId> = emptyMap()

    /** Subscribes to what changes presence, and to what arrives. */
    public fun install() {
        scope.add(
            events.on<ContextEvent>(OWNER, mode = DispatchMode.IMMEDIATE) {
                // Marked rather than sent. `publishIfDue` decides, so a burst of context events during a
                // server hop produces one message rather than five.
                isDirty = true
            },
        )
        scope.add(
            events.on<RealtimeMessageReceivedEvent>(OWNER, mode = DispatchMode.IMMEDIATE) { event ->
                onIncoming(event.message)
            },
        )
        scope.add(
            events.on<MinecraftDisconnectEvent>(OWNER, mode = DispatchMode.IMMEDIATE) {
                // Announced rather than left to time out. A friend list that says somebody is in a dungeon
                // twenty minutes after they quit is worse than one that says nothing.
                lastSent = null
                isDirty = true
            },
        )
        log.debug { "Presence publisher installed" }
    }

    private var isDirty = false

    /**
     * Sends our presence if anything has changed and enough time has passed.
     *
     * Called from a scheduled job rather than from the context events themselves, which is what makes the
     * throttle a throttle: the events set a flag and this decides.
     */
    public suspend fun publishIfDue() {
        val payload = current()

        val changed = payload != lastSent
        val stale = now() - lastSentAtMillis >= HEARTBEAT_MILLIS

        // The heartbeat matters as much as the change. A client that only sent on change would appear
        // frozen to anybody who connected after its last one — and "resuming from 0" is exactly the state a
        // newly connected client is in.
        if (!changed && !stale) return
        if (changed && now() - lastSentAtMillis < MINIMUM_INTERVAL_MILLIS && lastSent != null) {
            isDirty = true
            return
        }

        lastSent = payload
        lastSentAtMillis = now()
        isDirty = false

        realtime.send(
            RealtimeMessage(
                messageId = UUID.randomUUID().toString(),
                // The server's clock, not ours. Every timestamp that crosses the wire is compared against
                // something, and two machines disagree about the time constantly.
                timestampMillis = client.serverTime.toServer(now()),
                scope = payload.scope,
                payload = payload,
            ),
        )
    }

    /**
     * Our presence, with everything we have not agreed to share left out.
     *
     * Field by field, because the disclosures are per field. Somebody who shares their island but not their
     * activity sends an island and [dev.th7bo.sidequest.platform.skyblock.Activity.UNKNOWN], not nothing —
     * suppressing the whole message on the strictest setting would make the most private choice also the
     * one that turns the feature off for everybody.
     */
    private fun current(): RealtimePayload.Presence {
        val snapshot = context.context

        return RealtimePayload.Presence(
            // Being online is itself a disclosure. Somebody who shares nothing sends `false`, which reads as
            // offline — the honest answer, since as far as the group is concerned they are not there.
            isOnline = shares(Permission.VIEW_ONLINE_STATUS) && snapshot.isInSkyBlock,
            activity = if (shares(Permission.VIEW_ACTIVITY)) {
                snapshot.activity.activity
            } else {
                dev.th7bo.sidequest.platform.skyblock.Activity.UNKNOWN
            },
            island = if (shares(Permission.VIEW_ISLAND)) snapshot.island else Island.NONE,
            location = null,
        )
    }

    /**
     * Whether we share [permission] with anybody at all.
     *
     * The right question for an outbound broadcast: the message goes to the whole group and the server
     * decides who actually receives each field. Asking "do I share this with X" would need one message per
     * recipient, which is what the server-side redaction exists to avoid.
     */
    private fun shares(permission: Permission): Boolean = permissions.sharesWithAnybody(permission)

    /**
     * Records an incoming presence against the player it belongs to.
     *
     * Everything else the mod knows about a player lives in the directory, so this goes there rather than
     * into a second store. A friend list, a HUD element and a name-tag decoration then all read one place.
     */
    private fun onIncoming(message: RealtimeMessage) {
        val payload = message.payload as? RealtimePayload.Presence ?: return
        val account = message.senderAccount ?: return
        val playerId = minecraftByAccount[account] ?: return

        players.updatePresence(
            playerId,
            PlayerPresence(
                state = if (payload.isOnline) PresenceState.ONLINE else PresenceState.OFFLINE,
                activity = payload.activity,
                island = payload.island,
                location = payload.location,
                // True only because a location arrived. The server strips one that was not shared, so its
                // presence *is* the permission.
                isLocationShared = payload.location != null,
            ),
        )
    }

    /**
     * Takes the account-to-player mapping from a group listing.
     *
     * Called whenever the group is fetched. Accounts that share nothing have no Minecraft identity in the
     * listing, so they map to nothing and their presence is quietly ignored — which is what a privacy
     * setting should feel like from the other side.
     */
    public fun onGroup(group: GroupState) {
        minecraftByAccount = group.members
            .mapNotNull { member ->
                member.minecraftUuid
                    ?.let { runCatching { PlayerId.of(UUID.fromString(it)) }.getOrNull() }
                    ?.let { member.accountId to it }
            }
            .toMap()

        // Names too. The group listing is often the only place a client learns the name behind an account
        // it has never shared a lobby with.
        for (member in group.members) {
            val playerId = minecraftByAccount[member.accountId] ?: continue
            member.minecraftName?.let { players.remember(playerId, it) }
        }
        log.debug { "Group: ${group.members.size} member(s), ${minecraftByAccount.size} with a known identity" }
    }

    public fun close() {
        if (!scope.isClosed) scope.cancel()
        events.unsubscribeAll(OWNER)
    }

    private companion object {
        val OWNER = OwnerId(SqId.sidequest("presence"))

        /**
         * How often to say we are still here, even unchanged.
         *
         * Half a minute, which is inside the sixty seconds after which a presence goes stale — so a client
         * that missed one is not shown as gone.
         */
        const val HEARTBEAT_MILLIS = 30_000L

        /**
         * The floor between two presence messages.
         *
         * The sub-location changes every few seconds while walking, and without this presence would be the
         * loudest thing on the connection by an order of magnitude. What a friend list shows — the island
         * and the activity — changes rarely.
         */
        const val MINIMUM_INTERVAL_MILLIS = 5_000L
    }
}
