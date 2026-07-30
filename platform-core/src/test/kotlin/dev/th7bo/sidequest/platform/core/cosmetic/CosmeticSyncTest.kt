package dev.th7bo.sidequest.platform.core.cosmetic

import dev.th7bo.sidequest.platform.asset.AssetManager
import dev.th7bo.sidequest.platform.core.backend.RealtimeMessageReceivedEvent
import dev.th7bo.sidequest.platform.core.context.DefaultGameContextService
import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.cosmetic.Cosmetic
import dev.th7bo.sidequest.platform.cosmetic.CosmeticLoadout
import dev.th7bo.sidequest.platform.cosmetic.CosmeticSlot
import dev.th7bo.sidequest.platform.cosmetic.EquippedCosmetic
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.testkit.FakePlayers
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import dev.th7bo.sidequest.protocol.AccountId
import dev.th7bo.sidequest.protocol.RealtimeMessage
import dev.th7bo.sidequest.protocol.RealtimePayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Loadouts crossing the wire.
 *
 * Mostly about what is *not* trusted. A loadout is the one piece of this feature that arrives from another
 * person's client, so the interesting cases are the ones where that client is lying or is simply newer.
 */
class CosmeticSyncTest {

    private val myAccount = AccountId("account-me")
    private val friendAccount = AccountId("account-friend")
    private val friend = FakePlayers.friend.id

    private lateinit var service: DefaultCosmeticService
    private lateinit var receiver: RemoteCosmeticReceiver
    private lateinit var bus: DefaultEventBus

    @BeforeEach
    fun setUp() {
        bus = DefaultEventBus(TestScheduler(), NoopLogger)
        service = DefaultCosmeticService(
            context = DefaultGameContextService(bus, NoopLogger),
            assets = AssetManager.None,
            events = bus,
            log = NoopLogger,
            localPlayer = { FakePlayers.me.id },
        )
        receiver = RemoteCosmeticReceiver(service, bus, NoopLogger).also { it.install() }
        receiver.onGroup(mapOf(friendAccount to friend))

        service.register(cosmetic("cape", CosmeticSlot.CAPE))
        service.register(cosmetic("badge", CosmeticSlot.BADGE))
        service.register(cosmetic("style", CosmeticSlot.NOTIFICATION_STYLE))
    }

    private fun cosmetic(name: String, slot: CosmeticSlot) =
        Cosmetic(id = SqId.sidequest(name), slot = slot, displayName = name)

    private fun deliver(payload: RealtimePayload, from: AccountId?) {
        bus.post(
            RealtimeMessageReceivedEvent(
                RealtimeMessage(
                    messageId = "m",
                    timestampMillis = 0,
                    scope = payload.scope,
                    payload = payload,
                    senderAccount = from,
                ),
            ),
            EventSource.REMOTE,
        )
    }

    @Test
    fun `a friend's loadout becomes something this client can resolve`() {
        deliver(
            RealtimePayload.LoadoutChanged(friendAccount, mapOf("CAPE" to "sidequest:cape")),
            from = friendAccount,
        )

        assertEquals(SqId.sidequest("cape"), service.loadoutOf(friend)[CosmeticSlot.CAPE]?.cosmeticId)
    }

    /**
     * The sender comes from the envelope, never from the payload.
     *
     * A client that read the payload's subject would let anybody dress anybody else — including making
     * somebody appear to wear something offensive.
     */
    @Test
    fun `a loadout claiming to be somebody else is ignored`() {
        deliver(
            RealtimePayload.LoadoutChanged(subject = myAccount, equipped = mapOf("CAPE" to "sidequest:cape")),
            from = friendAccount,
        )

        assertTrue(service.loadoutOf(friend).isEmpty, "the friend should not have been dressed")
        assertTrue(service.loadout().isEmpty, "and neither should we")
    }

    @Test
    fun `a loadout from an unsigned message is ignored`() {
        deliver(RealtimePayload.LoadoutChanged(friendAccount, mapOf("CAPE" to "sidequest:cape")), from = null)

        assertTrue(service.loadoutOf(friend).isEmpty)
    }

    /**
     * Personal slots are never accepted from anybody else.
     *
     * A notification style is about the viewer's own client. Taking one over the wire would let a friend
     * restyle your interface, which is the same class of problem as the viewer-wins rule.
     */
    @Test
    fun `a personal slot cannot be pushed by a friend`() {
        deliver(
            RealtimePayload.LoadoutChanged(
                friendAccount,
                mapOf("NOTIFICATION_STYLE" to "sidequest:style", "CAPE" to "sidequest:cape"),
            ),
            from = friendAccount,
        )

        val loadout = service.loadoutOf(friend)
        assertNull(loadout[CosmeticSlot.NOTIFICATION_STYLE], "your interface is not theirs to style")
        assertEquals(SqId.sidequest("cape"), loadout[CosmeticSlot.CAPE]?.cosmeticId, "the rest still arrives")
    }

    /** A newer client wearing something this one has never heard of loses that slot and keeps the others. */
    @Test
    fun `an unknown slot is dropped rather than guessed at`() {
        deliver(
            RealtimePayload.LoadoutChanged(
                friendAccount,
                mapOf("HAT_FROM_THE_FUTURE" to "sidequest:hat", "BADGE" to "sidequest:badge"),
            ),
            from = friendAccount,
        )

        assertEquals(1, service.loadoutOf(friend).equipped.size)
        assertEquals(SqId.sidequest("badge"), service.loadoutOf(friend)[CosmeticSlot.BADGE]?.cosmeticId)
    }

    /** `SqId.parse` throws on a malformed id, and this input came off a network. */
    @Test
    fun `a malformed cosmetic id does not take the rest of the message with it`() {
        deliver(
            RealtimePayload.LoadoutChanged(
                friendAccount,
                mapOf("CAPE" to "not a valid id at all!!", "BADGE" to "sidequest:badge"),
            ),
            from = friendAccount,
        )

        assertNull(service.loadoutOf(friend)[CosmeticSlot.CAPE])
        assertEquals(SqId.sidequest("badge"), service.loadoutOf(friend)[CosmeticSlot.BADGE]?.cosmeticId)
    }

    /** Whole state, not a diff: an empty message means "wearing nothing", not "no change". */
    @Test
    fun `an empty loadout undresses`() {
        deliver(RealtimePayload.LoadoutChanged(friendAccount, mapOf("CAPE" to "sidequest:cape")), friendAccount)
        assertTrue(service.loadoutOf(friend).equipped.isNotEmpty())

        deliver(RealtimePayload.LoadoutChanged(friendAccount, emptyMap()), friendAccount)

        assertTrue(service.loadoutOf(friend).isEmpty)
    }

    @Test
    fun `a loadout from somebody not in the group listing is held back`() {
        deliver(
            RealtimePayload.LoadoutChanged(AccountId("stranger"), mapOf("CAPE" to "sidequest:cape")),
            from = AccountId("stranger"),
        )

        assertTrue(service.loadoutOf(FakePlayers.stranger.id).isEmpty)
    }

    // -- publishing ----------------------------------------------------------

    @Test
    fun `publishing sends only the slots other people can see`() {
        val sent = mutableListOf<RealtimePayload.LoadoutChanged>()
        val publisher = LoadoutPublisher(bus, NoopLogger, account = { myAccount }, send = { sent.add(it) })
        publisher.install()

        service.wear(
            CosmeticLoadout(
                mapOf(
                    CosmeticSlot.CAPE to EquippedCosmetic(SqId.sidequest("cape")),
                    CosmeticSlot.NOTIFICATION_STYLE to EquippedCosmetic(SqId.sidequest("style")),
                ),
            ),
        )

        val payload = sent.last()
        assertEquals(setOf("CAPE"), payload.equipped.keys, "your toast style is nobody else's business")
        assertEquals(myAccount, payload.subject)
    }

    @Test
    fun `publishing before pairing does nothing rather than failing`() {
        val sent = mutableListOf<RealtimePayload.LoadoutChanged>()
        LoadoutPublisher(bus, NoopLogger, account = { null }, send = { sent.add(it) }).install()

        service.equip(CosmeticSlot.CAPE, SqId.sidequest("cape"))

        assertTrue(sent.isEmpty())
    }
}
