package dev.th7bo.sidequest.platform.core.party

import dev.th7bo.sidequest.platform.player.PlayerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Asking a party whether they are ready.
 *
 * Sounds like counting clicks, and the counting is the easy half. What is worth testing is everything a
 * shared connection does to it: an answer arriving twice after a reconnect, one arriving from somebody who
 * left, one arriving after the deadline — none of which is misuse, all of which is Tuesday.
 */
class ReadyCheckTest {

    private val leader = PlayerId("00000000-0000-0000-0000-00000000000a")
    private val ana = PlayerId("00000000-0000-0000-0000-00000000000b")
    private val bo = PlayerId("00000000-0000-0000-0000-00000000000c")
    private val stranger = PlayerId("00000000-0000-0000-0000-00000000000d")

    private fun check() = ReadyCheck.start(
        id = "rc1",
        leader = leader,
        members = listOf(leader, ana, bo),
        deadlineMillis = DEADLINE,
    )

    private fun name(player: PlayerId) = when (player) {
        leader -> "Leader"
        ana -> "Ana"
        bo -> "Bo"
        else -> "Somebody"
    }

    // -- starting ------------------------------------------------------------

    /** Making somebody click "yes" to their own question is how a feature gets turned off. */
    @Test
    fun `the leader is ready from the start`() {
        val check = check()

        assertEquals(ReadyState.READY, check.participant(leader)?.state)
        assertEquals(ReadyState.WAITING, check.participant(ana)?.state)
        assertEquals(2, check.waitingCount)
    }

    @Test
    fun `a member listed twice is only asked once`() {
        val check = ReadyCheck.start("rc", leader, listOf(leader, ana, ana, bo), DEADLINE)

        assertEquals(3, check.participants.size)
    }

    // -- answering -----------------------------------------------------------

    @Test
    fun `everybody saying yes finishes it`() {
        val check = check().answered(ana, true).answered(bo, true)

        assertEquals(ReadyCheckOutcome.ALL_READY, check.outcome(BEFORE))
        assertTrue(check.isFinished(BEFORE))
    }

    @Test
    fun `a decline is reported at once rather than at the deadline`() {
        val check = check().answered(ana, false)

        assertEquals(ReadyCheckOutcome.SOMEBODY_DECLINED, check.outcome(BEFORE), "without waiting for Bo")
    }

    /**
     * A resend after a hiccup must not change a decision.
     *
     * On a shared connection the same message arrives twice all the time. Letting the second through would
     * have the leader watching somebody flip between ready and not for reasons entirely about the network.
     */
    @Test
    fun `the first answer stands`() {
        val check = check().answered(ana, true).answered(ana, false)

        assertEquals(ReadyState.READY, check.participant(ana)?.state)
        assertEquals(ReadyCheckOutcome.PENDING, check.outcome(BEFORE), "no decline was really made")
    }

    /**
     * An answer from somebody who was never asked is dropped, not added.
     *
     * The participant list is the leader's statement of who is being asked. A message from somebody who left
     * the party must not quietly enlarge it — and if it did, the check could never complete, because nobody
     * would be waiting for them.
     */
    @Test
    fun `an answer from outside the check is ignored`() {
        val check = check().answered(stranger, true)

        assertEquals(3, check.participants.size)
        assertNull(check.participant(stranger))
    }

    // -- the deadline --------------------------------------------------------

    /**
     * Never answering is not the same as saying no.
     *
     * Somebody who declined has made a decision the leader can act on; somebody silent is probably away from
     * the keyboard. Reporting the second as the first would be a lie about what happened.
     */
    @Test
    fun `silence past the deadline is its own state`() {
        val check = check().answered(ana, true).settledAt(AFTER)

        assertEquals(ReadyState.NO_RESPONSE, check.participant(bo)?.state)
        assertEquals(ReadyState.READY, check.participant(ana)?.state, "an answer given still counts")
        assertEquals(ReadyCheckOutcome.TIMED_OUT, check.outcome(AFTER))
    }

    /** Nothing changes before the deadline, however many times it is asked. */
    @Test
    fun `settling early changes nothing`() {
        val check = check()

        assertEquals(check, check.settledAt(BEFORE))
    }

    /**
     * A decline outranks a timeout.
     *
     * Somebody actively saying no is the most useful thing the leader can be told, and burying it under
     * "timed out" because the clock also ran out would hide the one answer that was given.
     */
    @Test
    fun `a decline wins over silence`() {
        val check = check().answered(ana, false)

        assertEquals(ReadyCheckOutcome.SOMEBODY_DECLINED, check.outcome(AFTER))
    }

    /** Everybody ready before the deadline stays ready after it. */
    @Test
    fun `a finished check is not spoiled by the clock`() {
        val check = check().answered(ana, true).answered(bo, true)

        assertEquals(ReadyCheckOutcome.ALL_READY, check.outcome(AFTER))
    }

    /** An answer arriving late still counts, right up until something settles the check. */
    @Test
    fun `a late answer counts if the check has not been settled`() {
        val check = check().answered(ana, true).answered(bo, true)

        assertEquals(ReadyCheckOutcome.ALL_READY, check.outcome(AFTER))
    }

    /** Once settled, somebody marked as silent has answered as far as the check is concerned. */
    @Test
    fun `an answer after settling does not overwrite the record`() {
        val settled = check().settledAt(AFTER)

        val later = settled.answered(ana, true)

        assertEquals(ReadyState.NO_RESPONSE, later.participant(ana)?.state)
    }

    // -- roles and warnings --------------------------------------------------

    @Test
    fun `a role and a warning attach to a participant`() {
        val check = check().described(ana, role = "Healer").described(ana, warning = "No gear")

        assertEquals("Healer", check.participant(ana)?.role, "the second call keeps the first's role")
        assertEquals("No gear", check.participant(ana)?.warning)
    }

    @Test
    fun `describing somebody outside the check does nothing`() {
        val check = check().described(stranger, role = "Tank")

        assertEquals(3, check.participants.size)
    }

    // -- what the leader is told ---------------------------------------------

    /** "Two declined" is not actionable; a party of five is small enough to name in full. */
    @Test
    fun `the summary names the people worth naming`() {
        assertEquals(
            "Ana declined",
            check().answered(ana, false).summary(BEFORE, ::name),
        )
        assertEquals(
            "No answer from Bo",
            check().answered(ana, true).summary(AFTER, ::name),
        )
        assertEquals(
            "Everybody is ready",
            check().answered(ana, true).answered(bo, true).summary(BEFORE, ::name),
        )
    }

    @Test
    fun `a pending summary says who is holding it up`() {
        val summary = check().answered(ana, true).summary(BEFORE, ::name)

        assertTrue(summary.contains("2/3"), summary)
        assertTrue(summary.contains("Bo"), summary)
        assertFalse(summary.contains("Ana"), "Ana already answered: $summary")
    }

    private companion object {
        const val DEADLINE = 10_000L
        const val BEFORE = 5_000L
        const val AFTER = 20_000L
    }
}
