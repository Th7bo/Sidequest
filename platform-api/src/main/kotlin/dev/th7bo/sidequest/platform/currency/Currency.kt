package dev.th7bo.sidequest.platform.currency

import dev.th7bo.sidequest.platform.event.SidequestEvent
import kotlinx.serialization.Serializable

/**
 * One change to the balance, kept forever.
 *
 * **The history is the record; the balance is derived from it** — except that a wallet with
 * years of transactions is a file nobody wants to load, so [Wallet] keeps the running
 * balance *and* the history. That is one question answered twice and it is deliberate: the
 * balance is authoritative and the transactions are trimmed to a bounded tail, which is the
 * trade-off in the other direction. A ledger that grew without bound would eventually make
 * every save a rewrite of a document the size of the mod.
 *
 * What the tail is still good for is the thing anybody actually asks: where did these coins
 * come from, and when did they go.
 */
@Serializable
public data class CurrencyTransaction(
    /** Signed. Positive granted, negative spent. */
    public val delta: Long,
    /** Why, in words a screen can show. Null for the ones nobody bothered to explain. */
    public val reason: String? = null,
    /**
     * What produced it — `rule`, `achievement`, `manual`. Free text rather than an enum so
     * a source added later does not need a migration to be recordable.
     */
    public val source: String,
    public val atMillis: Long,
)

/**
 * A balance and its recent history, replaced wholesale on every change.
 *
 * Immutable like the debt ledger and the friend roster: this is the thing being persisted,
 * and a mutable one would let a caller hand a half-edited wallet to a save.
 */
@Serializable
public data class Wallet(
    public val balance: Long = 0,
    public val transactions: List<CurrencyTransaction> = emptyList(),
) {

    /**
     * Adds to the balance. Refuses nothing here — amount validation belongs to the service,
     * which is the one place that can say *why* it refused.
     */
    public fun granted(amount: Long, reason: String?, source: String, atMillis: Long): Wallet =
        copy(
            balance = balance + amount,
            transactions = (
                listOf(CurrencyTransaction(delta = amount, reason = reason, source = source, atMillis = atMillis)) +
                    transactions
                ).take(MAX_HISTORY),
        )

    /**
     * Takes from the balance, or returns null when there is not enough.
     *
     * Null rather than an unchanged wallet so "refused" and "did nothing because you asked
     * for zero" are not the same answer. An overdraft is refused rather than allowed into
     * negative because this currency buys cosmetics and titles: a negative balance would be
     * a debt in a system that already has a real one, tracked properly, elsewhere.
     */
    public fun spent(amount: Long, reason: String?, source: String, atMillis: Long): Wallet? {
        if (amount > balance) return null
        return copy(
            balance = balance - amount,
            transactions = (
                listOf(CurrencyTransaction(delta = -amount, reason = reason, source = source, atMillis = atMillis)) +
                    transactions
                ).take(MAX_HISTORY),
        )
    }

    public companion object {

        /**
         * How much of the past survives.
         *
         * Five hundred entries covers months of a friend group's earning and spending; a
         * complete audit log covers none of the questions asked often enough to keep.
         */
        public const val MAX_HISTORY: Int = 500
    }
}

/**
 * The group's joke currency.
 *
 * Earned through achievements, challenges and personal bests; spent on titles, cosmetic
 * styles and sound packs. No gameplay advantage — that constraint is why granting is
 * unrestricted but spending can fail, and why neither touches anything outside this file.
 *
 * Account-scoped by whoever owns the storage, like rule progress: the same player on either
 * profile is the same wallet, because the currency is the friend group's, not SkyBlock's.
 */
public interface CurrencyService {

    public fun balance(): Long

    /**
     * Adds to the balance. False for a non-positive amount, which is a bug in the caller
     * rather than something the player did.
     */
    public fun grant(amount: Long, reason: String? = null, source: String = "manual"): Boolean

    /**
     * Takes from the balance. False when the player cannot afford it, which is a normal
     * answer a shop shows, and not an error.
     */
    public fun spend(amount: Long, reason: String? = null, source: String = "manual"): Boolean

    /** The most recent transactions, newest first. */
    public fun history(limit: Int = 50): List<CurrencyTransaction>

    /** Restores a persisted wallet. */
    public fun load(wallet: Wallet)

    /** Called after every change, so the caller can persist. Set by whoever owns the storage. */
    public var onWalletChanged: ((Wallet) -> Unit)?
}

/** The balance moved. Posted so screens and HUDs can follow it without polling. */
public class CurrencyChangedEvent(
    public val balance: Long,
    public val delta: Long,
    public val reason: String?,
) : SidequestEvent() {
    override fun describe(): String =
        (if (delta >= 0) "+" else "") + delta + " -> " + balance
}
