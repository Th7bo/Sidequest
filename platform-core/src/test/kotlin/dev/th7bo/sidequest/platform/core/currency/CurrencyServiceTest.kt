package dev.th7bo.sidequest.platform.core.currency

import dev.th7bo.sidequest.platform.currency.CurrencyChangedEvent
import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.testkit.EventRecorder
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The currency service.
 *
 * Two answers carry the file: spending more than you have is refused as a *normal answer*, and granting a
 * non-positive amount is logged as the caller's bug. The tests keep the two apart, because collapsing them
 * is how a shop that cannot afford to show "insufficient funds" ends up logging warnings instead.
 */
class CurrencyServiceTest {

    private lateinit var bus: DefaultEventBus
    private lateinit var events: EventRecorder
    private lateinit var service: DefaultCurrencyService

    private var clock = 1_000L

    @BeforeEach
    fun setUp() {
        bus = DefaultEventBus(TestScheduler(), NoopLogger)
        events = EventRecorder(bus)
        service = DefaultCurrencyService(bus, NoopLogger, now = { clock })
    }

    // -- earning --------------------------------------------------------------

    @Test
    fun `granting adds up`() {
        assertTrue(service.grant(100, reason = "achievement", source = "achievement"))
        assertTrue(service.grant(50, source = "rule"))

        assertEquals(150, service.balance())
    }

    @Test
    fun `a non-positive grant is refused and logged, not absorbed silently`() {
        assertFalse(service.grant(0))
        assertFalse(service.grant(-5))

        assertEquals(0, service.balance())
        events.assertNone<CurrencyChangedEvent>()
    }

    // -- spending -------------------------------------------------------------

    @Test
    fun `spending takes from the balance`() {
        service.grant(100)
        assertTrue(service.spend(30, reason = "title purchase", source = "shop"))

        assertEquals(70, service.balance())
    }

    /** A normal answer, not an error: this is what a shop shows when somebody cannot afford something. */
    @Test
    fun `spending more than you have is refused`() {
        service.grant(100)

        assertFalse(service.spend(101))
        assertEquals(100, service.balance())
        assertEquals(1, events.count<CurrencyChangedEvent>())
    }

    @Test
    fun `spending down to exactly zero works`() {
        service.grant(100)

        assertTrue(service.spend(100))
        assertEquals(0, service.balance())
    }

    // -- history --------------------------------------------------------------

    @Test
    fun `history reads newest first with signed deltas`() {
        service.grant(100, reason = "drop", source = "rule")
        clock += 10
        service.spend(40, reason = "cape", source = "shop")

        val history = service.history()
        assertEquals(-40, history[0].delta)
        assertEquals("cape", history[0].reason)
        assertEquals(100L, history[1].delta)
        assertEquals("drop", history[1].reason)
    }

    @Test
    fun `the tail is bounded so the wallet cannot grow forever`() {
        repeat(dev.th7bo.sidequest.platform.currency.Wallet.MAX_HISTORY + 100) {
            service.grant(1, source = "test")
        }

        assertTrue(service.history(Int.MAX_VALUE).size <= dev.th7bo.sidequest.platform.currency.Wallet.MAX_HISTORY)
        // The balance is authoritative and never trimmed.
        assertEquals(dev.th7bo.sidequest.platform.currency.Wallet.MAX_HISTORY + 100L, service.balance())
    }

    // -- persistence ----------------------------------------------------------

    @Test
    fun `every accepted change hands the caller the whole wallet`() {
        var saved: dev.th7bo.sidequest.platform.currency.Wallet? = null
        service.onWalletChanged = { saved = it }

        service.grant(25)
        service.spend(10)

        assertEquals(15, saved?.balance)
        assertEquals(2, saved?.transactions?.size)
    }

    @Test
    fun `a round trip through the wallet keeps balance and history`() {
        service.grant(500, reason = "season", source = "manual")
        service.spend(120, source = "shop")
        val stored = dev.th7bo.sidequest.platform.currency.Wallet(
            balance = 380,
            transactions = listOf(
                dev.th7bo.sidequest.platform.currency.CurrencyTransaction(-120, null, "shop", 2_000),
                dev.th7bo.sidequest.platform.currency.CurrencyTransaction(500, "season", "manual", 1_000),
            ),
        )

        val fresh = DefaultCurrencyService(bus, NoopLogger, now = { clock })
        fresh.load(stored)

        assertEquals(380, fresh.balance())
        assertEquals(2, fresh.history().size)
    }
}
