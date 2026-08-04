package dev.th7bo.sidequest.feature.ui

import dev.th7bo.sidequest.platform.core.debt.Debt
import dev.th7bo.sidequest.platform.core.debt.DebtAmount
import dev.th7bo.sidequest.platform.core.debt.DebtLedger
import dev.th7bo.sidequest.platform.item.SqItem
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.ui.config.ButtonSetting
import dev.th7bo.sidequest.ui.config.ConfigScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The debt screen.
 *
 * Two things here are worth more than the rest. Which side of a debt somebody is on decides every number and
 * every button, and getting it backwards would be the sort of bug people argue about rather than report. And
 * a button that is offered has to be one that works — an action visible on a debt it cannot apply to has
 * already been clicked by somebody who assumed otherwise.
 */
class DebtScreenTest {

    private val me = PlayerId("00000000-0000-0000-0000-00000000000a")
    private val them = PlayerId("00000000-0000-0000-0000-00000000000b")

    private var ledger = DebtLedger()
    private val drafts = mutableMapOf<String, String>()
    private val done = mutableListOf<String>()

    private val actions = DebtActions(
        current = { ledger },
        me = me,
        nameOf = { if (it == them) "Bob" else "Me" },
        agree = { id -> ledger = ledger.edit(id) { it.copy(debtorAgreed = true) }; done += "agree:$id" },
        forgive = { id -> ledger = ledger.edit(id) { it.copy(isWrittenOff = true) }; done += "forgive:$id" },
        draft = { drafts[it].orEmpty() },
        setDraft = { id, text -> drafts[id] = text },
        pay = { id -> done += "pay:$id:${drafts[id]}" },
        reopen = { },
    )

    private fun debt(
        id: String,
        theyOweMe: Boolean = true,
        coins: Long = 5_000_000,
        agreed: Boolean = true,
        settled: Boolean = false,
    ) = Debt(
        id = id,
        debtor = if (theyOweMe) them else me,
        creditor = if (theyOweMe) me else them,
        amount = DebtAmount.Coins(coins),
        debtorAgreed = agreed,
        isWrittenOff = settled,
    )

    private fun build(): ConfigScreen = buildDebtScreen(ledger, actions)

    private fun category(title: String) = build().categories.firstOrNull { it.title.peek() == title }

    private fun buttons(screen: ConfigScreen, suffix: String) =
        screen.settings.filterIsInstance<ButtonSetting>().filter { it.id.value.endsWith(suffix) }

    // -- which side you are on ------------------------------------------------

    /**
     * The grouping is the screen's whole job.
     *
     * Somebody opening this wants three answers and a single date-sorted list gives none of them: what
     * needs answering, what is owed to me, what I owe.
     */
    @Test
    fun `debts are filed by who is waiting on whom`() {
        ledger = ledger
            .with(debt("owed", theyOweMe = true))
            .with(debt("owing", theyOweMe = false))
            .with(debt("unanswered", theyOweMe = false, agreed = false))

        assertEquals(listOf("Bob · 5m"), category("Owed to you")?.sections?.map { it.title.peek() })
        assertEquals(listOf("Bob · 5m"), category("You owe")?.sections?.map { it.title.peek() })
        assertEquals(1, category("Waiting on you")?.sections?.size)
    }

    /** A debt you have not agreed to is *only* in the waiting category, never also in "you owe". */
    @Test
    fun `an unanswered debt is not also listed as owed`() {
        ledger = ledger.with(debt("d1", theyOweMe = false, agreed = false))

        assertEquals(1, category("Waiting on you")?.sections?.size)
        assertTrue(category("You owe")?.sections?.none { it.title.peek() == "Bob · 5m" } == true)
    }

    @Test
    fun `the balance says which way it goes`() {
        ledger = ledger.with(debt("d1", theyOweMe = true, coins = 5_000_000))
        assertTrue(build().description?.peek()?.contains("You are owed 5m") == true, build().description?.peek())

        ledger = DebtLedger().with(debt("d2", theyOweMe = false, coins = 2_000_000))
        assertTrue(build().description?.peek()?.contains("You owe 2m") == true, build().description?.peek())
    }

    /** Nothing waiting means no category for it, rather than a heading over nothing. */
    @Test
    fun `no unanswered debts means no waiting category`() {
        ledger = ledger.with(debt("d1"))

        assertTrue(build().categories.none { it.title.peek() == "Waiting on you" })
    }

    // -- only offering what works ---------------------------------------------

    /**
     * Agreeing is offered exactly once, to exactly the right person.
     *
     * Not on a debt already agreed, not on one that is settled, and never to the creditor — who wrote it
     * and therefore agreed by writing it.
     */
    @Test
    fun `agreeing is offered only on your own unanswered debts`() {
        ledger = ledger
            .with(debt("mine_unanswered", theyOweMe = false, agreed = false))
            .with(debt("mine_agreed", theyOweMe = false, agreed = true))
            .with(debt("theirs", theyOweMe = true, agreed = false))

        assertEquals(1, buttons(build(), ".agree").size)
    }

    /**
     * An item debt gets no payment box.
     *
     * Half a Hyperion is not a payment — the ledger refuses it — so a box that took an amount would be a
     * control whose only outcome is a refusal.
     */
    @Test
    fun `an item debt offers no payment box`() {
        ledger = ledger.with(
            Debt(
                id = "d1",
                debtor = them,
                creditor = me,
                amount = DebtAmount.Item(SqItem(minecraftId = "minecraft:diamond_sword", displayName = "Hyperion")),
                debtorAgreed = true,
            ),
        )

        val screen = build()
        assertTrue(buttons(screen, ".pay").isEmpty())
        assertTrue(screen.settings.none { it.id.value.endsWith(".amount") })
        // Still settleable by hand, which is the only way an item debt ever closes.
        assertEquals(1, buttons(screen, ".forgive").size)
    }

    @Test
    fun `a settled debt offers nothing to do`() {
        ledger = ledger.with(debt("d1", settled = true))

        val screen = build()
        assertTrue(buttons(screen, ".agree").isEmpty())
        assertTrue(buttons(screen, ".pay").isEmpty())
        assertTrue(buttons(screen, ".forgive").isEmpty())
    }

    /** Settled debts are kept, because the history is what makes the ledger worth having. */
    @Test
    fun `settled debts stay on the screen under their own heading`() {
        ledger = ledger.with(debt("d1", settled = true))

        assertEquals(1, category("Settled")?.sections?.size)
    }

    // -- the draft ------------------------------------------------------------

    /**
     * What is typed into the payment box survives being read back.
     *
     * The frozen-field bug in its usual shape: `MirrorBinding.set` re-reads after writing, so a getter over
     * anything but live state silently reverts every keystroke.
     */
    @Test
    fun `typing an amount sticks`() {
        ledger = ledger.with(debt("d1"))
        val screen = build()

        val field = screen.settings.first { it.id.value.endsWith(".amount") }
        @Suppress("UNCHECKED_CAST")
        (field.binding as dev.th7bo.sidequest.ui.binding.Binding<String>).set("2m")

        assertEquals("2m", drafts["d1"])
        assertEquals("2m", field.binding.state.peek())
    }

    @Test
    fun `recording a payment applies what was typed`() {
        ledger = ledger.with(debt("d1"))
        drafts["d1"] = "2m"

        buttons(build(), ".pay").single().invoke()

        assertEquals(listOf("pay:d1:2m"), done)
    }

    @Test
    fun `agreeing goes through to the ledger`() {
        ledger = ledger.with(debt("d1", theyOweMe = false, agreed = false))

        buttons(build(), ".agree").single().invoke()

        assertTrue(ledger["d1"]!!.debtorAgreed)
    }

    // -- the failure it must not have -----------------------------------------

    /** Two debts with the same person for the same amount is a Tuesday, not a mistake. */
    @Test
    fun `two identical looking debts both draw`() {
        ledger = ledger.with(debt("d1")).with(debt("d2"))

        val screen = build()
        val everyId = screen.categories.map { it.id } +
            screen.categories.flatMap { category -> category.sections.map { it.id } } +
            screen.settings.map { it.id }

        assertEquals(2, category("Owed to you")?.sections?.size)
        assertEquals(everyId.size, everyId.distinct().size, "duplicate ids would throw the screen away")
    }

    @Test
    fun `an empty ledger still builds and says so`() {
        val screen = build()

        assertEquals("Even on balance.", screen.description?.peek())
        assertTrue(screen.categories.first { it.title.peek() == "Owed to you" }.sections.isNotEmpty())
    }

    /** Somebody else's debts are not this player's business and never appear. */
    @Test
    fun `debts between other people are not shown`() {
        val third = PlayerId("00000000-0000-0000-0000-00000000000c")
        ledger = ledger.with(debt("mine")).with(
            Debt(id = "theirs", debtor = them, creditor = third, amount = DebtAmount.Coins(1), debtorAgreed = true),
        )

        val titles = build().categories.flatMap { it.sections }.map { it.title.peek() }
        assertEquals(1, titles.count { it.startsWith("Bob") })
        assertFalse(titles.any { it.contains("Me") })
    }
}
