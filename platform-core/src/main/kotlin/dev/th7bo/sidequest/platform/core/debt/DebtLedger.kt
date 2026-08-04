package dev.th7bo.sidequest.platform.core.debt

import dev.th7bo.sidequest.platform.player.PlayerId
import kotlinx.serialization.Serializable

/**
 * Every debt, and what they add up to.
 *
 * Immutable and replaced wholesale, like the waypoint book and the friend roster — this is the thing being
 * persisted, and a mutable one would let a caller hand a half-edited ledger to a save. It is a friend group,
 * so it is tens of entries at the outside.
 *
 * **Nothing is removed by being settled.** A settled debt stays in the list, because "how much has this
 * person actually paid back" is a question that cannot be answered by a ledger that discards everything
 * which worked out. Only [without] removes, and that is for a debt recorded by mistake.
 */
@Serializable
public data class DebtLedger(
    public val debts: List<Debt> = emptyList(),
) {

    public val size: Int get() = debts.size

    public operator fun get(id: String): Debt? = debts.firstOrNull { it.id == id }

    /** Adds or replaces, keyed by id. */
    public fun with(debt: Debt): DebtLedger = copy(debts = debts.filterNot { it.id == debt.id } + debt)

    public fun without(id: String): DebtLedger = copy(debts = debts.filterNot { it.id == id })

    /**
     * Applies a change to one debt, if it is here.
     *
     * A function rather than a replacement, so a caller never holds a whole debt: it edits a field of
     * whatever is current, which is what stops a screen putting back the debt it was drawing before two
     * repayments landed.
     */
    public fun edit(id: String, change: (Debt) -> Debt): DebtLedger {
        val existing = get(id) ?: return this
        return with(change(existing))
    }

    /** Records a payment. Unchanged when the debt is unknown, or when [Debt.repay] refuses it. */
    public fun repay(id: String, coins: Long, atMillis: Long, note: String? = null): DebtLedger {
        val updated = get(id)?.repay(coins, atMillis, note) ?: return this
        return with(updated)
    }

    // -- asking about somebody ------------------------------------------------

    /** Everything [player] is on either side of, newest first. */
    public fun involving(player: PlayerId): List<Debt> =
        debts.filter { it.involves(player) }.sortedByDescending { it.createdAtMillis }

    /** The open ones, which is what a summary is about. */
    public fun open(player: PlayerId): List<Debt> = involving(player).filterNot { it.isSettled }

    /** Open debts past their deadline. */
    public fun overdue(player: PlayerId, nowMillis: Long): List<Debt> =
        involving(player).filter { it.isOverdue(nowMillis) }

    /**
     * What [player] is worth across every open debt, in coins.
     *
     * Positive when they are owed more than they owe. **Only agreed debts count**: anybody can write down
     * that somebody owes them five million, and a balance that included unconfirmed claims would let one
     * person move another's number by typing. The unagreed ones are still in the ledger and still visible —
     * they are just not arithmetic yet.
     */
    public fun balanceOf(player: PlayerId): Long =
        debts.filter { it.isAgreed }.sumOf { it.signedFor(player) }

    /**
     * What stands between two people, from [player]'s side.
     *
     * Positive when [other] owes them. This is the number worth showing, because two people who owe each
     * other are usually one settlement rather than two — netting is the whole point of tracking both
     * directions rather than a list per person.
     */
    public fun balanceBetween(player: PlayerId, other: PlayerId): Long = debts
        .filter { it.isAgreed && it.involves(player) && it.involves(other) }
        .sumOf { it.signedFor(player) }

    /**
     * Debts waiting on [player] to agree.
     *
     * What a "somebody says you owe them" prompt is built from. Only the ones where they are the *debtor*:
     * a creditor who has not agreed wrote it themselves, which is a state the ledger allows but nothing
     * creates.
     */
    public fun awaitingAgreement(player: PlayerId): List<Debt> =
        debts.filter { it.debtor == player && !it.debtorAgreed && !it.isSettled }

    /** Payments the creditor has not yet acknowledged, for debts [player] is owed. */
    public fun awaitingConfirmation(player: PlayerId): List<Debt> =
        debts.filter { it.creditor == player && it.repayments.any { payment -> !payment.isConfirmed } }
}
