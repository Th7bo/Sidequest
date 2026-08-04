package dev.th7bo.sidequest.platform.core.debt

import dev.th7bo.sidequest.platform.item.SqItem
import dev.th7bo.sidequest.platform.player.PlayerId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What is owed.
 *
 * **Coins or an item, never both.** The plan says "amount / item or currency" and the *or* is the whole of
 * this type. Modelling it as a nullable item beside a coin count would allow "owes five million and a
 * Hyperion", which is two debts wearing one id — and makes every question about settlement ambiguous, since
 * paying the coins would leave a debt that is both settled and not.
 */
@Serializable
public sealed interface DebtAmount {

    /** Money. Divisible, so it can be paid off in pieces. */
    @Serializable
    @SerialName("coins")
    public data class Coins(public val amount: Long) : DebtAmount

    /**
     * A thing.
     *
     * Indivisible: an item is returned or it is not, so a part-payment against one is meaningless. That is
     * why [Debt.repay] refuses on an item debt rather than quietly recording a fraction of a sword.
     */
    @Serializable
    @SerialName("item")
    public data class Item(
        public val item: SqItem,
        public val count: Int = 1,
    ) : DebtAmount
}

/** A payment made against a debt. */
@Serializable
public data class Repayment(
    public val coins: Long,
    public val atMillis: Long,
    public val note: String? = null,
    /**
     * Whether the creditor has said they received it.
     *
     * Unconfirmed payments still count against the balance. The alternative — a payment that does not
     * reduce what somebody owes until the other person clicks something — turns a forgotten confirmation
     * into a debt that looks unpaid, and that is a worse failure than briefly trusting the payer.
     */
    public val isConfirmed: Boolean = false,
)

/**
 * Somebody owes somebody something.
 *
 * Three decisions shape everything here.
 *
 * **The history is the record; the balance is derived.** Repayments are a list and what remains is computed
 * from it. Storing a running balance beside them would be a second answer to one question, and the two
 * disagree the first time a write half-lands.
 *
 * **Both parties confirm.** The plan asks for it and it is the difference between a debt and an
 * accusation: anybody can write down that you owe them five million. Until [debtorAgreed] it is a claim,
 * and [isAgreed] is what anything summarising a balance should be filtering on.
 *
 * **Nothing is ever deleted by settling.** A settled debt stays, because "how much has this person actually
 * paid back over a year" is a question the group will ask, and it cannot be answered from a ledger that
 * throws away everything that worked out.
 */
@Serializable
public data class Debt(
    public val id: String,
    /** Who owes. */
    public val debtor: PlayerId,
    /** Who is owed. */
    public val creditor: PlayerId,
    public val amount: DebtAmount,
    /** What it is for. Free text, shown to both parties. */
    public val reason: String = "",
    /** Private to whoever wrote it, like a waypoint's note. Never shared. */
    public val note: String? = null,
    public val createdAtMillis: Long = 0,
    /** When it should be settled by, or null for no deadline. */
    public val dueAtMillis: Long? = null,
    /** Oldest first. Only ever appended to. */
    public val repayments: List<Repayment> = emptyList(),
    /** Whether the person who owes has agreed that they do. */
    public val debtorAgreed: Boolean = false,
    /** Whether the person owed has agreed to the terms as written. */
    public val creditorAgreed: Boolean = true,
    /**
     * Settled by hand, whatever the numbers say.
     *
     * For the item debts, which cannot be part-paid, and for the coin debts somebody decided to forgive.
     * Kept as a flag rather than by inventing a repayment for the full amount, so the history says what
     * actually happened: written off, not paid.
     */
    public val isWrittenOff: Boolean = false,
) {

    /** How much has been paid, confirmed or not. See [Repayment.isConfirmed]. */
    public val paid: Long get() = repayments.sumOf { it.coins }

    /** What is still owed in coins. Zero for an item debt, which is not measured in coins. */
    public val outstanding: Long
        get() = when (val owed = amount) {
            is DebtAmount.Coins -> (owed.amount - paid).coerceAtLeast(0)
            is DebtAmount.Item -> 0
        }

    /**
     * Whether it is done with.
     *
     * An item debt is only ever settled by hand, because there is no number to reach — this is the one
     * place [isWrittenOff] is not an exception but the ordinary path.
     */
    public val isSettled: Boolean
        get() = isWrittenOff || (amount is DebtAmount.Coins && outstanding == 0L)

    /** Whether both parties have agreed this debt exists as written. */
    public val isAgreed: Boolean get() = debtorAgreed && creditorAgreed

    /** Whether it is past its deadline and still open. A settled debt is never overdue. */
    public fun isOverdue(nowMillis: Long): Boolean {
        val due = dueAtMillis ?: return false
        return !isSettled && nowMillis > due
    }

    /**
     * Records a payment.
     *
     * **Refused on an item debt**, because a fraction of a sword is not a thing that can be handed over —
     * see [DebtAmount.Item]. Refused on a non-positive amount too: a zero payment is a mistake and a
     * negative one is somebody trying to increase a debt through the repayment door.
     *
     * Overpayment is *allowed* and simply settles it. Somebody rounding up is being generous, and refusing
     * the payment over it would be the ledger arguing with reality.
     */
    public fun repay(coins: Long, atMillis: Long, note: String? = null): Debt? {
        if (amount !is DebtAmount.Coins || coins <= 0) return null
        return copy(repayments = repayments + Repayment(coins, atMillis, note))
    }

    /**
     * Marks the *oldest* unconfirmed payment as received. Null when there is nothing to confirm.
     *
     * Oldest first, which is what makes a backlog drain. Confirming the newest instead — which this did,
     * until a test disagreed with it — leaves an older payment unconfirmed permanently, because every
     * confirmation from then on takes the one in front of it.
     */
    public fun confirmNextPayment(): Debt? {
        val index = repayments.indexOfFirst { !it.isConfirmed }
        if (index < 0) return null
        return copy(
            repayments = repayments.toMutableList().also { it[index] = it[index].copy(isConfirmed = true) },
        )
    }

    /** Whether [player] is either side of this. */
    public fun involves(player: PlayerId): Boolean = player == debtor || player == creditor

    /**
     * What this is worth to [player], signed.
     *
     * Positive when they are owed, negative when they owe. The sign convention is stated once here so that
     * nothing summing balances has to decide it again — two places choosing differently is how a debt
     * screen ends up showing the wrong person in the red.
     */
    public fun signedFor(player: PlayerId): Long = when {
        isSettled -> 0
        amount !is DebtAmount.Coins -> 0
        player == creditor -> outstanding
        player == debtor -> -outstanding
        else -> 0
    }
}
