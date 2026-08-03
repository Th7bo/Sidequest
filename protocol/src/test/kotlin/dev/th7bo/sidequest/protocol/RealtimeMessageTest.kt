package dev.th7bo.sidequest.protocol

import dev.th7bo.sidequest.platform.permission.Permission
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The envelope's own rules.
 *
 * Both ends depend on these, which is why they are defined on the message rather than on the server: the
 * server routes by them and a client decides what to draw by them, and two implementations of one rule is
 * how a message aimed at one person ends up on somebody else's screen.
 */
class RealtimeMessageTest {

    private val alice = AccountId("alice")
    private val bob = AccountId("bob")
    private val carol = AccountId("carol")

    private fun message(
        recipients: Set<AccountId> = emptySet(),
        sender: AccountId? = alice,
        payload: RealtimePayload = RealtimePayload.Ping(location()),
    ) = RealtimeMessage(
        messageId = "m1",
        timestampMillis = 1_000,
        senderAccount = sender,
        scope = payload.scope,
        recipients = recipients,
        payload = payload,
    )

    private fun location() = SqLocation(Island.HUB, SqPosition(1.0, 2.0, 3.0))

    // -- addressing ----------------------------------------------------------

    /** The common case, and the default: no recipients means everybody the permissions already allow. */
    @Test
    fun `an unaddressed message is for everybody`() {
        val message = message()

        assertTrue(message.isAddressedTo(bob))
        assertTrue(message.isAddressedTo(carol))
    }

    @Test
    fun `an addressed message is only for who it names`() {
        val message = message(recipients = setOf(bob))

        assertTrue(message.isAddressedTo(bob))
        assertFalse(message.isAddressedTo(carol))
    }

    /**
     * The sender can always read back what they sent.
     *
     * Not politeness: without it a resume would drop the sender's own copy of everything they addressed to
     * other people, and their waypoint would vanish from their own screen on the next reconnect.
     */
    @Test
    fun `the sender sees their own message even when they are not a recipient`() {
        assertTrue(message(recipients = setOf(bob)).isAddressedTo(alice))
    }

    /** Addressing is not authorisation. It only ever removes people; the scope still has to be checked. */
    @Test
    fun `addressing carries no permission of its own`() {
        val ledger = message(recipients = setOf(carol), payload = RealtimePayload.PaymentConfirmed("d1", 500))

        // All this says is that the field does not silently change the scope somebody has to satisfy. The
        // check itself lives on the server, and its own tests prove naming somebody does not admit them.
        assertEquals(Permission.CONFIRM_PAYMENTS, ledger.scope)
        assertTrue(ledger.isAddressedTo(carol))
    }

    // -- the wire ------------------------------------------------------------

    /**
     * A message from a build that predates recipients still reads.
     *
     * The field has a default for exactly this: an older client's message arrives without it and must mean
     * "everybody", which is what it meant before the field existed.
     */
    @Test
    fun `a message with no recipients field decodes as unaddressed`() {
        val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
        val encoded = """
            {"messageId":"m1","timestampMillis":1000,"scope":"VIEW_ONLINE_STATUS",
             "payload":{"type":"ping","location":{"island":"HUB","position":{"x":1.0,"y":2.0,"z":3.0}}}}
        """.trimIndent()

        val decoded = json.decodeFromString(RealtimeMessage.serializer(), encoded)

        assertTrue(decoded.recipients.isEmpty())
        assertTrue(decoded.isAddressedTo(bob))
    }

    @Test
    fun `recipients survive a round trip`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
        val original = message(recipients = setOf(bob, carol))

        val decoded = json.decodeFromString(
            RealtimeMessage.serializer(),
            json.encodeToString(RealtimeMessage.serializer(), original),
        )

        assertEquals(setOf(bob, carol), decoded.recipients)
    }

    // -- staleness -----------------------------------------------------------

    /** A ping expires; a debt does not. The two live side by side on one connection. */
    @Test
    fun `only the payloads that expire go stale`() {
        val ping = message(payload = RealtimePayload.Ping(location()))
        val debt = message(payload = RealtimePayload.DebtCreated("d1", bob, 500))

        assertFalse(ping.isStale(1_000))
        assertTrue(ping.isStale(1_000 + 31_000))
        assertFalse(debt.isStale(1_000 + 31_000))
    }
}
