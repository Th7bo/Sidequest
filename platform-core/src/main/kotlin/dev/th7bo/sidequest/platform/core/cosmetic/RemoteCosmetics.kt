package dev.th7bo.sidequest.platform.core.cosmetic

import dev.th7bo.sidequest.platform.core.backend.RealtimeMessageReceivedEvent
import dev.th7bo.sidequest.platform.cosmetic.CosmeticLoadout
import dev.th7bo.sidequest.platform.cosmetic.CosmeticService
import dev.th7bo.sidequest.platform.cosmetic.CosmeticSlot
import dev.th7bo.sidequest.platform.cosmetic.EquippedCosmetic
import dev.th7bo.sidequest.platform.cosmetic.LoadoutChangedEvent
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.lifecycle.RegistrationScope
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.protocol.AccountId
import dev.th7bo.sidequest.protocol.RealtimePayload
import kotlinx.serialization.Serializable

/**
 * What is written to disk.
 *
 * **The loadout only.** The viewer's settings used to live here too and no longer do: they are a *preference*,
 * they belong in the configuration file with every other preference, and holding them in two places meant a
 * value could be changed in one and lost from the other on the next launch. A loadout is not a preference —
 * it is account data that syncs to the group — which is why it stays.
 */
@Serializable
public data class CosmeticStore(
    public val loadout: CosmeticLoadout = CosmeticLoadout.Empty,
)

/**
 * Turns a friend's loadout into something this client will draw.
 *
 * The counterpart to [dev.th7bo.sidequest.platform.core.marker.RemoteMarkerReceiver], and the same shape: the
 * only place the realtime stream becomes cosmetics, so there is one idea of what arriving means.
 *
 * What arrives is not trusted. The subject comes from the message envelope rather than the payload, unknown
 * slot names are dropped rather than guessed at, and the number of slots is capped — the definitions
 * themselves are resolved locally, so a sender naming a cosmetic this client does not have gets nothing
 * rather than something invented.
 */
public class RemoteCosmeticReceiver(
    private val cosmetics: CosmeticService,
    private val events: EventBus,
    private val log: Logger,
) {

    private val scope = RegistrationScope("cosmetics.remote")

    /** Account id to player, refreshed from the group listing. The same mapping presence and markers use. */
    private var accountToPlayer: Map<AccountId, PlayerId> = emptyMap()

    public fun onGroup(mapping: Map<AccountId, PlayerId>) {
        accountToPlayer = mapping
    }

    public fun install() {
        scope.add(
            events.on<RealtimeMessageReceivedEvent>(OwnerId.PLATFORM) { received ->
                val payload = received.message.payload
                if (payload is RealtimePayload.LoadoutChanged) onLoadout(payload, received.message.senderAccount)
            },
        )
    }

    private fun onLoadout(payload: RealtimePayload.LoadoutChanged, senderAccount: AccountId?) {
        // The envelope's sender, not the payload's subject. A client that trusted the payload would let
        // anybody dress anybody else.
        val account = senderAccount ?: return
        if (payload.subject != account) {
            log.warn { "Ignoring a loadout for ${payload.subject} that arrived from $account" }
            return
        }
        val player = accountToPlayer[account]
        if (player == null) {
            log.debug { "A loadout arrived from $account, who is not in the group listing yet" }
            return
        }

        val equipped = payload.equipped.entries
            .take(MAX_SLOTS)
            .mapNotNull { (slotName, cosmeticId) ->
                // Unknown slot names are dropped rather than guessed at, so a newer client wearing something
                // this one has never heard of loses that slot and keeps the rest.
                val slot = CosmeticSlot.entries.firstOrNull { it.name.equals(slotName, ignoreCase = true) }
                    ?: return@mapNotNull null
                // Personal slots are never accepted from anybody else. A notification style is about the
                // viewer's own client, and taking one over the wire would let a friend restyle your interface.
                if (!slot.isWorn) return@mapNotNull null
                // `parse` throws on anything that is not a well-formed id, and this string came off a
                // network — so it is caught rather than allowed to take down the receiver for every other
                // slot in the message.
                val id = runCatching { SqId.parse(cosmeticId) }.getOrNull() ?: return@mapNotNull null
                slot to EquippedCosmetic(id)
            }
            .toMap()

        cosmetics.setRemoteLoadout(player, CosmeticLoadout(equipped))
        log.debug { "$player is wearing ${equipped.size} cosmetic(s)" }
    }

    public fun close() {
        if (!scope.isClosed) scope.cancel()
    }

    private companion object {
        /**
         * How many slots are accepted from one message.
         *
         * There are fewer than twenty slots, so this only ever bites on a malformed or hostile message —
         * which is exactly when a cap is worth having.
         */
        const val MAX_SLOTS = 32
    }
}

/**
 * Tells the group what the local player is wearing.
 *
 * Sends on change rather than on a timer. A loadout changes when somebody opens a wardrobe and fiddles, which
 * is a burst of a dozen changes in a few seconds and then nothing for hours — so the publisher is handed a
 * debounced send rather than doing it itself, and the burst becomes one message.
 */
public class LoadoutPublisher(
    private val events: EventBus,
    private val log: Logger,
    /** Who we are, on the backend. Null before pairing, which makes this a no-op rather than an error. */
    private val account: () -> AccountId?,
    private val send: (RealtimePayload.LoadoutChanged) -> Unit,
) {

    private val scope = RegistrationScope("cosmetics.publish")

    public fun install() {
        scope.add(
            events.on<LoadoutChangedEvent>(OwnerId.PLATFORM) { changed -> publish(changed.loadout) },
        )
    }

    public fun publish(loadout: CosmeticLoadout) {
        val account = account() ?: return
        val equipped = loadout.equipped
            // Only what other people can see. Sending your notification style would tell the group something
            // about your client that is none of their business and that nothing would ever read.
            .filterKeys { it.isWorn }
            .map { (slot, entry) -> slot.name to entry.cosmeticId.toString() }
            .toMap()

        send(RealtimePayload.LoadoutChanged(subject = account, equipped = equipped))
        log.debug { "Published a loadout of ${equipped.size} slot(s)" }
    }

    public fun close() {
        if (!scope.isClosed) scope.cancel()
    }
}
