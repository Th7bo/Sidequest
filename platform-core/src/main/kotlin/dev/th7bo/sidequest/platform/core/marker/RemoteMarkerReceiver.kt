package dev.th7bo.sidequest.platform.core.marker

import dev.th7bo.sidequest.platform.core.backend.RealtimeMessageReceivedEvent
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.lifecycle.RegistrationScope
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.marker.Marker
import dev.th7bo.sidequest.platform.marker.MarkerKind
import dev.th7bo.sidequest.platform.marker.MarkerRender
import dev.th7bo.sidequest.platform.marker.MarkerService
import dev.th7bo.sidequest.platform.ping.PingStyle
import dev.th7bo.sidequest.platform.player.PlayerDirectory
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.protocol.AccountId
import dev.th7bo.sidequest.protocol.RealtimePayload

/**
 * Turns a friend's ping or shared waypoint into a marker.
 *
 * The half of §24 that makes it worth having: a marker system nobody else can see is a bookmark. This is where
 * the realtime stream becomes something on screen, and it is deliberately the *only* place — a feature that
 * subscribed to `RealtimePayload.Ping` itself would be a second, slightly different idea of how long a ping
 * lasts.
 *
 * What arrives is not trusted with anything it should not be. The label is truncated, the sender is taken from
 * the message envelope rather than from the payload, and the lifetime comes from this client's own
 * [PingStyle] table — the sender names a style, never a duration. A friend cannot place a ping that never
 * expires by sending an unusual number, and a style this build has never heard of draws as a generic ping
 * rather than as nothing.
 */
public class RemoteMarkerReceiver(
    private val markers: MarkerService,
    private val players: PlayerDirectory,
    private val events: EventBus,
    private val log: Logger,
) {

    private val scope = RegistrationScope("markers.remote")

    /**
     * Account id to player, so a message can be attributed.
     *
     * The backend speaks in accounts and the mod speaks in Minecraft UUIDs. Refreshed from the group listing
     * by whoever owns it — the same mapping presence needs, and deliberately not a second copy of it.
     */
    private var accountToPlayer: Map<AccountId, PlayerId> = emptyMap()

    public fun onGroup(mapping: Map<AccountId, PlayerId>) {
        accountToPlayer = mapping
    }

    public fun install() {
        scope.add(
            events.on<RealtimeMessageReceivedEvent>(OwnerId.PLATFORM) { received ->
                when (val payload = received.message.payload) {
                    is RealtimePayload.Ping -> onPing(payload, received.message.senderAccount)
                    is RealtimePayload.Waypoint -> onWaypoint(payload, received.message.senderAccount)
                    else -> Unit
                }
            },
        )
    }

    private fun onPing(payload: RealtimePayload.Ping, senderAccount: AccountId?) {
        val sender = senderAccount?.let { accountToPlayer[it] }
        // Resolved rather than trusted. The style is free-form on the wire and may name something a newer
        // client knows and this one does not; `ofWire` answers with a generic ping instead of nothing,
        // because somebody was pointing at something either way.
        val style = PingStyle.ofWire(payload.style)

        markers.place(
            Marker(
                // Per sender rather than random, so a second ping from the same person replaces the first
                // instead of littering. Somebody pinging twice means "look *there*", not "look at both".
                id = "ping." + (senderAccount?.value ?: "unknown"),
                kind = MarkerKind.PING,
                location = payload.location,
                // The sender's words if they wrote any, then what the style means, then who sent it. Never
                // blank: a beam in the distance with no label is a question rather than a message.
                label = payload.label.take(MAX_LABEL).ifEmpty { style.displayName },
                creator = sender,
                colour = style.colour,
                lifetime = style.lifetime,
                // Edge indicator on, because the whole point of a ping is that it is somewhere you are not
                // yet looking. Minimal otherwise: it is a gesture, not a destination.
                render = MarkerRender.Minimal.copy(edgeIndicator = true),
                iconId = STYLE_ICONS[style.wireName],
            ),
        )
        log.debug { "${style.displayName} from ${senderName(sender)} at ${payload.location.island}" }
    }

    private fun onWaypoint(payload: RealtimePayload.Waypoint, senderAccount: AccountId?) {
        // Namespaced by sender, so two people sharing a waypoint they both call "boss" do not overwrite each
        // other. The protocol's id is only unique within its sender.
        val id = "shared." + (senderAccount?.value ?: "unknown") + "." + payload.id

        if (payload.removed) {
            markers.remove(id)
            return
        }

        val sender = senderAccount?.let { accountToPlayer[it] }
        markers.place(
            Marker(
                id = id,
                kind = MarkerKind.WAYPOINT,
                location = payload.location,
                label = payload.label.take(MAX_LABEL),
                note = payload.note?.take(MAX_NOTE),
                creator = sender,
            ),
        )
        log.debug { "Shared waypoint '${payload.label}' from ${senderName(sender)}" }
    }

    private fun senderName(sender: PlayerId?): String =
        sender?.let { players.byId(it)?.displayName } ?: "somebody"

    public fun close() {
        if (!scope.isClosed) scope.cancel()
    }

    private companion object {
        /**
         * How much of a label is kept.
         *
         * Truncated rather than rejected: a long label is somebody being enthusiastic, not somebody attacking,
         * and dropping the whole marker over it would be the wrong trade. What it protects against is a label
         * long enough to cover the screen.
         */
        const val MAX_LABEL = 48
        const val MAX_NOTE = 200

        /**
         * Art for the styles that have any.
         *
         * Keyed on the wire name, so it lines up with [PingStyle] without needing an entry per style — one
         * with no icon takes the kind's default, which is a beam and a label and perfectly legible.
         */
        val STYLE_ICONS: Map<String, SqId> = mapOf(
            PingStyle.DANGER.wireName to SqId.sidequest("marker.danger"),
            PingStyle.ITEM_HERE.wireName to SqId.sidequest("marker.loot"),
            PingStyle.GO_HERE.wireName to SqId.sidequest("marker.here"),
        )
    }
}
