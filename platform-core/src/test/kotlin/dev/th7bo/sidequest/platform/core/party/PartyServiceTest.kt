package dev.th7bo.sidequest.platform.core.party

import dev.th7bo.sidequest.platform.chat.ChatMessage
import dev.th7bo.sidequest.platform.chat.PartyDisbandedEvent
import dev.th7bo.sidequest.platform.chat.PartyJoinedEvent
import dev.th7bo.sidequest.platform.chat.PartyKickedEvent
import dev.th7bo.sidequest.platform.chat.PartyLeaderChangedEvent
import dev.th7bo.sidequest.platform.chat.PartyLeaveReason
import dev.th7bo.sidequest.platform.chat.PartyMemberJoinedEvent
import dev.th7bo.sidequest.platform.chat.PartyMemberLeftEvent
import dev.th7bo.sidequest.platform.core.chat.DefaultChatParser
import dev.th7bo.sidequest.platform.core.chat.HypixelChatRules
import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.core.player.DefaultPlayerDirectory
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.event.MinecraftDisconnectEvent
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.party.PartyChangedEvent
import dev.th7bo.sidequest.platform.party.PartyConfidence
import dev.th7bo.sidequest.platform.party.PartyRole
import dev.th7bo.sidequest.platform.party.ReadyCheckChangedEvent
import dev.th7bo.sidequest.platform.party.ReadyResponse
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * The party service.
 *
 * Driven the way it is driven in the game: real Hypixel chat lines fed through the real chat parser,
 * so what is tested is the whole path from a line to a member list. Asserting against hand-built
 * `PartyMemberJoinedEvent`s would test the service and skip the layer that produces its input, which
 * is where the interesting mistakes are.
 */
class PartyServiceTest {

    private lateinit var events: DefaultEventBus
    private lateinit var chat: DefaultChatParser
    private lateinit var players: DefaultPlayerDirectory
    private lateinit var party: DefaultPartyService

    private val owner = OwnerId(SqId.sidequest("test"))
    private var clock = 1_000L

    @BeforeEach
    fun setUp() {
        events = DefaultEventBus(TestScheduler(), NoopLogger)
        chat = DefaultChatParser(events, NoopLogger, now = { clock })
        chat.registerAll(HypixelChatRules.all { LOCAL_PLAYER }, OwnerId.PLATFORM)
        players = DefaultPlayerDirectory(events, now = { clock })
        party = DefaultPartyService(events, players, NoopLogger, now = { clock })
        party.install()
    }

    /** Feeds a real Hypixel line through the real parser. */
    private fun line(raw: String) {
        // Each line gets its own moment, or the duplicate suppressor eats the second identical one.
        clock += 1_000
        chat.onMessage(ChatMessage.of(raw))
    }

    // ---------------------------------------------------------------
    // Membership from chat
    // ---------------------------------------------------------------

    @Test
    fun `joining a party records the leader`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")

        assertTrue(party.isInParty)
        assertEquals("Throwpo", party.party.leader)
        assertEquals(PartyRole.LEADER, party.party.member("Throwpo")?.role)
        assertEquals(PartyConfidence.TRACKED, party.party.confidence)
    }

    @Test
    fun `members joining and leaving move the list`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        line("§b[MVP§d+§b] CalMWolfs §ejoined the party.")
        assertEquals(listOf("Throwpo", "CalMWolfs"), party.party.members.map { it.name })

        line("§7CalMWolfs §ehas left the party.")
        assertEquals(listOf("Throwpo"), party.party.members.map { it.name })
    }

    @Test
    fun `a member removed for any reason leaves the list`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        line("§7riblets §ejoined the party.")
        line("§7riblets §ehas been removed from the party.")

        assertFalse(party.party.has("riblets"))
    }

    @Test
    fun `leadership moving updates the roles`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        line("§b[MVP§d+§b] CalMWolfs §ejoined the party.")
        line("The party was transferred to [MVP+] CalMWolfs because [MVP+] Throwpo left")

        assertEquals("CalMWolfs", party.party.leader)
        assertEquals(PartyRole.LEADER, party.party.member("CalMWolfs")?.role)
        assertEquals(PartyRole.MEMBER, party.party.member("Throwpo")?.role)
    }

    @Test
    fun `a disband ends the party`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        line("§b[MVP§d+§b] Throwpo §ehas disbanded the party!")

        assertFalse(party.isInParty)
        assertNull(party.party.leader)
    }

    @Test
    fun `being kicked ends the party`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        line("§eYou have been kicked from the party by §b[MVP§d+§b] Throwpo §e")

        assertFalse(party.isInParty)
    }

    /** A party does not survive leaving the server, and a stale one messages people who are not there. */
    @Test
    fun `disconnecting ends the party`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        events.post(MinecraftDisconnectEvent(), EventSource.GAME)

        assertFalse(party.isInParty)
    }

    @Test
    fun `a change is announced with what it was before`() {
        val changes = mutableListOf<String>()
        events.on<PartyChangedEvent>(owner) { changes.add("${it.previous.size} -> ${it.party.size}") }

        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        line("§b[MVP§d+§b] CalMWolfs §ejoined the party.")

        assertEquals(listOf("0 -> 1", "1 -> 2"), changes)
    }

    // ---------------------------------------------------------------
    // The tab widget as corroboration
    // ---------------------------------------------------------------

    /**
     * The widget wins, because it states the membership rather than accumulating it.
     *
     * A session that started mid-party never saw the joins. Tracking alone is silently empty there,
     * and the widget is the only thing that can fix it.
     */
    @Test
    fun `the widget fills in a party whose formation was never seen`() {
        party.onPartyWidget(listOf("Throwpo", "CalMWolfs", "lrg89"))

        assertEquals(3, party.party.size)
        assertEquals(PartyConfidence.CONFIRMED, party.party.confidence)
    }

    @Test
    fun `the widget overrides an accumulation that has drifted`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        assertEquals(1, party.party.size)

        party.onPartyWidget(listOf("Throwpo", "CalMWolfs"))
        assertEquals(listOf("Throwpo", "CalMWolfs"), party.party.members.map { it.name })
        assertEquals("Throwpo", party.party.leader, "the leader still comes from chat")
    }

    /** An absent widget means the tab list did not say — not that the party is empty. */
    @Test
    fun `an empty widget falls back to the accumulation`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        party.onPartyWidget(emptyList())

        assertEquals(1, party.party.size)
        assertEquals(PartyConfidence.TRACKED, party.party.confidence)
    }

    @Test
    fun `members are resolved to identities where the client has seen them`() {
        players.remember(PlayerId.of(UUID.fromString("11111111-1111-4111-8111-111111111111")), "Throwpo")
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")

        assertEquals("11111111-1111-4111-8111-111111111111", party.party.member("Throwpo")?.id?.value)
        line("§7Stranger §ejoined the party.")
        assertNull(party.party.member("Stranger")?.id, "an unseen player has no identity yet")
    }

    /** Nothing is known about a member's mod until the realtime layer says so. */
    @Test
    fun `a member is assumed not to have Sidequest`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        assertFalse(party.party.member("Throwpo")!!.hasSidequest)
    }

    // ---------------------------------------------------------------
    // Ready checks
    // ---------------------------------------------------------------

    @Test
    fun `a ready check asks everybody who was in the party`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        line("§b[MVP§d+§b] CalMWolfs §ejoined the party.")

        val check = party.startReadyCheck(LOCAL_PLAYER, 30.seconds)

        assertEquals(setOf("Throwpo", "CalMWolfs"), check?.responses?.keys)
        assertTrue(check!!.responses.values.all { it == ReadyResponse.WAITING })
        assertFalse(check.isComplete)
    }

    @Test
    fun `there is nothing to ask with no party`() {
        assertNull(party.startReadyCheck(LOCAL_PLAYER, 30.seconds))
        assertNull(party.readyCheck)
    }

    @Test
    fun `responses complete the check`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        party.startReadyCheck(LOCAL_PLAYER, 30.seconds)

        val updated = party.recordResponse("throwpo", ReadyResponse.READY)

        assertEquals(ReadyResponse.READY, updated?.responseOf("Throwpo"))
        assertTrue(updated!!.isComplete)
        assertTrue(updated.isEverybodyReady)
    }

    @Test
    fun `a decline is not a timeout and not a readiness`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        party.startReadyCheck(LOCAL_PLAYER, 30.seconds)
        val updated = party.recordResponse("Throwpo", ReadyResponse.DECLINED)

        assertTrue(updated!!.isComplete)
        assertFalse(updated.isEverybodyReady)
    }

    /**
     * Somebody who was not asked cannot answer.
     *
     * A check is over a fixed set of people; letting a latecomer answer would make "everybody is
     * ready" mean something different than it did a moment before.
     */
    @Test
    fun `a response from somebody who was not asked is ignored`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        party.startReadyCheck(LOCAL_PLAYER, 30.seconds)

        val updated = party.recordResponse("Latecomer", ReadyResponse.READY)
        assertEquals(setOf("Throwpo"), updated?.responses?.keys)
    }

    @Test
    fun `the deadline turns waiting into timed out`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        party.startReadyCheck(LOCAL_PLAYER, 30.seconds)

        party.expireReadyCheckIfDue()
        assertEquals(ReadyResponse.WAITING, party.readyCheck?.responseOf("Throwpo"), "not due yet")

        clock += 31_000
        party.expireReadyCheckIfDue()
        assertEquals(ReadyResponse.TIMED_OUT, party.readyCheck?.responseOf("Throwpo"))
    }

    @Test
    fun `every stage of a check is announced`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        val announced = mutableListOf<String>()
        events.on<ReadyCheckChangedEvent>(owner) { announced.add(it.describe()) }

        party.startReadyCheck(LOCAL_PLAYER, 30.seconds)
        party.recordResponse("Throwpo", ReadyResponse.READY)
        party.endReadyCheck()

        assertEquals(listOf("ready check: 0/1", "everybody ready", "ready check ended"), announced)
    }

    @Test
    fun `leaving the party ends any check with it`() {
        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        party.startReadyCheck(LOCAL_PLAYER, 30.seconds)
        line("§b[MVP§d+§b] Throwpo §ehas disbanded the party!")

        assertNull(party.readyCheck)
    }

    // ---------------------------------------------------------------
    // Ordering
    // ---------------------------------------------------------------

    /**
     * The party is up to date before a feature sees the chat event.
     *
     * A feature reacting to "somebody joined the party" by reading the member list must not see the
     * list from before they joined, which is why the service subscribes at `IMMEDIATE`.
     */
    @Test
    fun `a feature listening to the same line sees the updated party`() {
        var sizeWhenNotified = -1
        events.on<PartyMemberJoinedEvent>(owner, mode = dev.th7bo.sidequest.platform.event.DispatchMode.IMMEDIATE) {
            sizeWhenNotified = party.party.size
        }

        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        line("§b[MVP§d+§b] CalMWolfs §ejoined the party.")

        assertEquals(2, sizeWhenNotified)
    }

    @Test
    fun `the chat layer still emits the specific lines for features that want them`() {
        val seen = mutableListOf<String>()
        events.on<PartyJoinedEvent>(owner) { seen.add("joined ${it.leader}") }
        events.on<PartyMemberJoinedEvent>(owner) { seen.add("+${it.member}") }
        events.on<PartyMemberLeftEvent>(owner) { seen.add("-${it.member} (${it.reason})") }
        events.on<PartyLeaderChangedEvent>(owner) { seen.add("leader ${it.newLeader}") }
        events.on<PartyDisbandedEvent>(owner) { seen.add("disbanded") }
        events.on<PartyKickedEvent>(owner) { seen.add("kicked") }

        line("§eYou have joined §b[MVP§d+§b] Throwpo's §eparty!")
        line("§7riblets §ejoined the party.")
        line("§7riblets §ehas left the party.")
        line("§b[MVP§d+§b] Throwpo §ehas disbanded the party!")

        assertEquals(
            listOf("joined Throwpo", "+riblets", "-riblets (${PartyLeaveReason.LEFT})", "disbanded"),
            seen,
        )
    }

    private companion object {
        const val LOCAL_PLAYER = "Th7bo"
    }
}
