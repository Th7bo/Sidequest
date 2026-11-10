package dev.th7bo.sidequest.platform.core.currency

import dev.th7bo.sidequest.platform.currency.CurrencyChangedEvent
import dev.th7bo.sidequest.platform.currency.CurrencyService
import dev.th7bo.sidequest.platform.currency.CurrencyTransaction
import dev.th7bo.sidequest.platform.currency.Wallet
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.log.Logger

/**
 * A balance and a bounded tail of how it got there.
 *
 * Small on purpose. The whole state is one immutable [Wallet] replaced per change, so there
 * is no lock to get wrong: the platform persists through the change callback and the callback
 * always sees a complete wallet.
 *
 * **Refusals are answers, not errors.** Spending more than you have returns false because a
 * shop shows "you cannot afford that". Granting zero or less is different — it is a bug in
 * whatever called, and it is logged rather than silently absorbed, because a rule whose reward
 * never lands is exactly the kind of silent failure this platform exists to prevent.
 */
public class DefaultCurrencyService(
    private val events: EventBus,
    private val log: Logger,
    private val now: () -> Long = System::currentTimeMillis,
) : CurrencyService {

    private var wallet = Wallet()

    override var onWalletChanged: ((Wallet) -> Unit)? = null

    override fun balance(): Long = wallet.balance

    override fun grant(amount: Long, reason: String?, source: String): Boolean {
        if (amount <= 0) {
            log.debug { "Refused granting $amount coin(s): not positive" }
            return false
        }
        apply(wallet.granted(amount, reason, source, now()), amount, reason)
        return true
    }

    override fun spend(amount: Long, reason: String?, source: String): Boolean {
        if (amount <= 0) {
            log.debug { "Refused spending $amount coin(s): not positive" }
            return false
        }
        val updated = wallet.spent(amount, reason, source, now()) ?: run {
            log.debug { "Refused spending $amount coin(s): balance is ${wallet.balance}" }
            return false
        }
        apply(updated, -amount, reason)
        return true
    }

    override fun history(limit: Int): List<CurrencyTransaction> =
        wallet.transactions.take(limit)

    override fun load(wallet: Wallet) {
        this.wallet = wallet
        log.debug { "Loaded ${wallet.transactions.size} transaction(s), balance ${wallet.balance}" }
    }

    /** One path for every accepted change, so nothing can update the balance without announcing it. */
    private fun apply(updated: Wallet, delta: Long, reason: String?) {
        wallet = updated
        onWalletChanged?.invoke(updated)
        events.post(
            CurrencyChangedEvent(
                balance = updated.balance,
                delta = delta,
                reason = reason,
            ),
            EventSource.DERIVED,
        )
    }
}
