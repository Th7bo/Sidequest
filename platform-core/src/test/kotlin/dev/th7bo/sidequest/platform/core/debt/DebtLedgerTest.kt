package dev.th7bo.sidequest.platform.core.debt

import dev.th7bo.sidequest.platform.item.SqItem
import dev.th7bo.sidequest.platform.player.PlayerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Who owes what.
 *
 * This is the file where being wrong costs a friendship rather than a frame, so the tests lean hard on the
 * three things that decide a number somebody will argue about: what counts as paid, what counts as agreed,
 * and which direction the sign points.
 */
class DebtLedgerTest {

    private val alice = PlayerId("00000000-0000-0000-0000-00000000000a")
    private val bob = PlayerId("00000000-0000-0000-0000-00000000000b")
    private val carol = PlayerId("00000000-0000-0000-0000-00000000000c")

    private val now = 1_000_000L

    private fun debt(
        id: String,
        debtor: PlayerId = bob,
        creditor: PlayerId = alice,
        coins: Long = 1_000_000,
        agreed: Boolean = true,
        due: Long? = null,
    ) = Debt(
        id = id,
        debtor = debtor,
        creditor = creditor,
        amount = DebtAmount.Coins(coins),
        createdAtMillis = now,
        dueAtMillis = due,
        debtorAgreed = agreed,
    )

    private fun itemDebt(id: String, debtor: PlayerId = bob, creditor: PlayerId = alice) = Debt(
        id = id,
        debtor = debtor,
        creditor = creditor,
        amount = DebtAmount.Item(SqItem(minecraftId = "minecraft:diamond_sword", skyblockId = "HYPERION")),
        createdAtMillis = now,
        debtorAgreed = true,
    )

    // -- what counts as paid --------------------------------------------------

    @Test
    fun `a new debt owes all of it`() {
        val owed = debt("d1", coins = 5_000_000)

        assertEquals(5_000_000, owed.outstanding)
        assertEquals(0, owed.paid)
        assertFalse(owed.isSettled)
    }

    @Test
    fun `a part payment reduces what is left`() {
        val owed = debt("d1", coins = 5_000_000).repay(2_000_000, now)!!

        assertEquals(2_000_000, owed.paid)
        assertEquals(3_000_000, owed.outstanding)
        assertFalse(owed.isSettled)
    }

    @Test
    fun `paying it all off settles it`() {
        val owed = debt("d1", coins = 5_000_000)
            .repay(2_000_000, now)!!
            .repay(3_000_000, now)!!

        assertEquals(0, owed.outstanding)
        assertTrue(owed.isSettled)
    }

    /**
     * Overpaying settles rather than being refused.
     *
     * Somebody rounding a 4.8 million debt up to five is being generous, and a ledger that rejected the
     * payment over it would be arguing with something that has already happened in the trade window.
     */
    @Test
    fun `overpaying settles the debt and never goes negative`() {
        val owed = debt("d1", coins = 4_800_000).repay(5_000_000, now)!!

        assertEquals(0, owed.outstanding, "outstanding must never go below zero")
        assertTrue(owed.isSettled)
    }

    /** A zero payment is a mistake and a negative one is a debt increase through the wrong door. */
    @Test
    fun `a payment that is not positive is refused`() {
        val owed = debt("d1")

        assertNull(owed.repay(0, now))
        assertNull(owed.repay(-500, now))
    }

    /**
     * An item cannot be part-paid.
     *
     * Half a Hyperion is not a thing that changes hands, and recording one would leave a debt that is
     * neither settled nor open by any rule the rest of this file can state.
     */
    @Test
    fun `an item debt refuses a coin payment`() {
        assertNull(itemDebt("d1").repay(1_000_000, now))
    }

    @Test
    fun `an item debt is settled only by being written off`() {
        val owed = itemDebt("d1")
        assertFalse(owed.isSettled)

        assertTrue(owed.copy(isWrittenOff = true).isSettled)
    }

    /** Written off says what happened. Inventing a repayment would say it was paid, which it was not. */
    @Test
    fun `writing off a coin debt settles it without claiming it was paid`() {
        val owed = debt("d1", coins = 5_000_000).copy(isWrittenOff = true)

        assertTrue(owed.isSettled)
        assertEquals(0, owed.paid, "nothing was actually paid")
    }

    // -- confirmation ---------------------------------------------------------

    /**
     * An unconfirmed payment still counts.
     *
     * Deliberate, and the direction matters: a forgotten confirmation would otherwise leave somebody
     * looking like they had not paid, which is a worse failure than briefly taking their word for it.
     */
    @Test
    fun `a payment counts before the creditor confirms it`() {
        val owed = debt("d1", coins = 5_000_000).repay(5_000_000, now)!!

        assertTrue(owed.isSettled)
        assertFalse(owed.repayments.single().isConfirmed)
    }

    @Test
    fun `confirming drains the backlog oldest first`() {
        val owed = debt("d1", coins = 5_000_000)
            .repay(1_000_000, now)!!
            .repay(2_000_000, now)!!
            .confirmNextPayment()!!

        assertEquals(listOf(true, false), owed.repayments.map { it.isConfirmed })
    }

    @Test
    fun `there is nothing to confirm when everything already is`() {
        val owed = debt("d1").repay(500, now)!!.confirmNextPayment()!!

        assertNull(owed.confirmNextPayment())
    }

    // -- agreement ------------------------------------------------------------

    /**
     * An unagreed debt is a claim, and claims are not arithmetic.
     *
     * The property that stops one person moving another's balance by typing. The debt is still in the
     * ledger and still visible — it just does not count until the person who supposedly owes it says so.
     */
    @Test
    fun `a debt nobody agreed to does not move a balance`() {
        val ledger = DebtLedger().with(debt("d1", coins = 5_000_000, agreed = false))

        assertEquals(0, ledger.balanceOf(alice))
        assertEquals(0, ledger.balanceOf(bob))
        assertEquals(1, ledger.involving(alice).size, "it is still there to look at")
    }

    @Test
    fun `agreeing makes it count`() {
        val ledger = DebtLedger()
            .with(debt("d1", coins = 5_000_000, agreed = false))
            .edit("d1") { it.copy(debtorAgreed = true) }

        assertEquals(5_000_000, ledger.balanceOf(alice))
    }

    @Test
    fun `what is waiting on somebody is what they have not agreed to`() {
        val ledger = DebtLedger()
            .with(debt("d1", debtor = bob, agreed = false))
            .with(debt("d2", debtor = bob, agreed = true))
            .with(debt("d3", debtor = carol, agreed = false))

        assertEquals(listOf("d1"), ledger.awaitingAgreement(bob).map { it.id })
    }

    // -- the sign -------------------------------------------------------------

    /**
     * Which way round the number goes.
     *
     * Stated in one place on purpose. Two parts of the mod choosing differently is exactly how a screen
     * ends up showing the wrong person in the red, and it is the sort of bug nobody reports because they
     * assume they misread it.
     */
    @Test
    fun `the creditor is owed and the debtor owes`() {
        val ledger = DebtLedger().with(debt("d1", debtor = bob, creditor = alice, coins = 5_000_000))

        assertEquals(5_000_000, ledger.balanceOf(alice), "alice is owed, so positive")
        assertEquals(-5_000_000, ledger.balanceOf(bob), "bob owes, so negative")
    }

    @Test
    fun `a settled debt is worth nothing to either side`() {
        val ledger = DebtLedger().with(debt("d1", coins = 1_000).repay(1_000, now)!!)

        assertEquals(0, ledger.balanceOf(alice))
        assertEquals(0, ledger.balanceOf(bob))
    }

    /** Debts in both directions net off — two people who owe each other settle once, not twice. */
    @Test
    fun `debts in opposite directions cancel`() {
        val ledger = DebtLedger()
            .with(debt("d1", debtor = bob, creditor = alice, coins = 5_000_000))
            .with(debt("d2", debtor = alice, creditor = bob, coins = 2_000_000))

        assertEquals(3_000_000, ledger.balanceBetween(alice, bob))
        assertEquals(-3_000_000, ledger.balanceBetween(bob, alice))
    }

    @Test
    fun `a balance between two people ignores everybody else`() {
        val ledger = DebtLedger()
            .with(debt("d1", debtor = bob, creditor = alice, coins = 1_000))
            .with(debt("d2", debtor = carol, creditor = alice, coins = 9_000))

        assertEquals(1_000, ledger.balanceBetween(alice, bob))
        assertEquals(10_000, ledger.balanceOf(alice), "both count towards the total")
    }

    /** An item debt has no coin value, so it never silently contributes a number to a balance. */
    @Test
    fun `an item debt is not worth coins`() {
        val ledger = DebtLedger().with(itemDebt("d1"))

        assertEquals(0, ledger.balanceOf(alice))
        assertEquals(1, ledger.open(alice).size, "it is still open, just not arithmetic")
    }

    // -- deadlines ------------------------------------------------------------

    @Test
    fun `a debt past its date is overdue`() {
        val ledger = DebtLedger().with(debt("d1", due = now + 1_000))

        assertTrue(ledger.overdue(bob, now + 2_000).map { it.id } == listOf("d1"))
        assertTrue(ledger.overdue(bob, now).isEmpty(), "not yet")
    }

    /** Settling stops the reminders. A paid debt is not late. */
    @Test
    fun `a settled debt is never overdue`() {
        val settled = debt("d1", coins = 1_000, due = now + 1_000).repay(1_000, now)!!

        assertFalse(settled.isOverdue(now + 999_999))
    }

    @Test
    fun `a debt with no deadline is never overdue`() {
        assertFalse(debt("d1", due = null).isOverdue(now + 999_999_999))
    }

    // -- the ledger itself ----------------------------------------------------

    @Test
    fun `settling does not remove it from the record`() {
        val ledger = DebtLedger().with(debt("d1", coins = 1_000)).repay("d1", 1_000, now)

        assertEquals(1, ledger.size, "history is the point")
        assertTrue(ledger.open(alice).isEmpty())
        assertEquals(1, ledger.involving(alice).size)
    }

    @Test
    fun `paying against a debt that is not there changes nothing`() {
        val ledger = DebtLedger().with(debt("d1"))

        assertEquals(ledger, ledger.repay("nope", 500, now))
    }

    @Test
    fun `adding the same id twice replaces rather than duplicating`() {
        val ledger = DebtLedger().with(debt("d1", coins = 1_000)).with(debt("d1", coins = 2_000))

        assertEquals(1, ledger.size)
        assertEquals(DebtAmount.Coins(2_000), ledger["d1"]?.amount)
    }

    @Test
    fun `somebody with nothing owes nothing`() {
        val ledger = DebtLedger().with(debt("d1", debtor = bob, creditor = alice))

        assertEquals(0, ledger.balanceOf(carol))
        assertTrue(ledger.involving(carol).isEmpty())
    }
}
