package dev.th7bo.sidequest.feature.ui

import dev.th7bo.sidequest.platform.core.debt.Coins
import dev.th7bo.sidequest.platform.core.debt.Debt
import dev.th7bo.sidequest.platform.core.debt.DebtAmount
import dev.th7bo.sidequest.platform.core.debt.DebtLedger
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.ui.binding.bind
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Icon

/** The icons the debt screen draws. Defaulted to the framework's own; the host may supply better. */
public data class DebtScreenIcons(
    public val owed: Icon = Icon(UiId.of("sidequest", "icon.debt")),
    public val owing: Icon = Icon(UiId.of("sidequest", "icon.debt")),
    public val settled: Icon = Icon(UiId.of("sidequest", "icon.check")),
)

/**
 * What the debt screen can do, and what it may read.
 *
 * Callbacks rather than the feature, for the reason the other screens here give: the rows are described from
 * a snapshot and every value has to read through something that knows the current state.
 */
public class DebtActions(
    /** The ledger as it is *now*. Every getter reads through this, never the snapshot. */
    public val current: () -> DebtLedger,
    /** Whose screen this is. Decides which way every number points. */
    public val me: PlayerId,
    /** What to call the person on the other side of a debt. */
    public val nameOf: (PlayerId) -> String,
    public val agree: (id: String) -> Unit,
    public val forgive: (id: String) -> Unit,
    /**
     * The amount typed into a debt's payment box, and a way to change it.
     *
     * Held by the feature rather than the screen because the screen is rebuilt on every reopen, and a draft
     * that lived here would be lost the moment anything called [reopen] — which paying does.
     */
    public val draft: (id: String) -> String,
    public val setDraft: (id: String, text: String) -> Unit,
    /** Applies the draft against a debt. */
    public val pay: (id: String) -> Unit,
    /** Rebuilds and reopens. For the edits that change the screen's own shape. */
    public val reopen: () -> Unit,
)

/**
 * Who owes what.
 *
 * **Split by who is waiting on whom**, because that is the only grouping anybody opening this screen wants:
 * what needs answering, what is owed to you, what you owe. A single list sorted by date makes the one debt
 * that needs a decision indistinguishable from the eleven that do not.
 *
 * Settled debts get their own category and are never removed, because "how much has this person actually
 * paid back over a year" is a question a friend group asks and one a ledger that discarded its successes
 * could not answer.
 */
public fun buildDebtScreen(
    ledger: DebtLedger,
    actions: DebtActions,
    icons: DebtScreenIcons = DebtScreenIcons(),
): ConfigScreen {
    val mine = ledger.involving(actions.me)
    val ordinals = mine.withIndex().associate { (index, debt) -> debt.id to index }

    val waiting = mine.filter { !it.isSettled && it.debtor == actions.me && !it.debtorAgreed }
    val owedToMe = mine.filter { !it.isSettled && it.creditor == actions.me && it !in waiting }
    val owedByMe = mine.filter { !it.isSettled && it.debtor == actions.me && it !in waiting }
    val settled = mine.filter { it.isSettled }

    val balance = ledger.balanceOf(actions.me)

    return configScreen(id("debts"), "Debts", describeBalance(balance)) {
        // First, and only when there is something in it. Somebody claiming you owe them is the one thing
        // here with a decision attached, and it should not be below two screens of history.
        if (waiting.isNotEmpty()) {
            category(
                id("debts.waiting"),
                "Waiting on you",
                description = "${waiting.size} to answer · nothing counts until you do",
                icon = icons.owing,
            ) {
                for (debt in waiting) debtSection(debt, key(debt.id, ordinals), actions, icons)
            }
        }

        category(
            id("debts.owed"),
            "Owed to you",
            description = total(owedToMe),
            icon = icons.owed,
        ) {
            if (owedToMe.isEmpty()) {
                section("Nobody owes you anything", description = "/sqdebt lent <player> <amount>") {
                    button(id("debts.owed.none"), "How it works", label = "Got it") { }
                }
            }
            for (debt in owedToMe) debtSection(debt, key(debt.id, ordinals), actions, icons)
        }

        category(
            id("debts.owing"),
            "You owe",
            description = total(owedByMe),
            icon = icons.owing,
        ) {
            if (owedByMe.isEmpty()) {
                section("You owe nobody", description = "/sqdebt owe <player> <amount>") {
                    button(id("debts.owing.none"), "How it works", label = "Got it") { }
                }
            }
            for (debt in owedByMe) debtSection(debt, key(debt.id, ordinals), actions, icons)
        }

        if (settled.isNotEmpty()) {
            category(
                id("debts.settled"),
                "Settled",
                description = "${settled.size} · kept as a record",
                icon = icons.settled,
            ) {
                for (debt in settled) debtSection(debt, key(debt.id, ordinals), actions, icons)
            }
        }
    }
}

/**
 * One debt.
 *
 * Which buttons appear depends on the debt and on which side of it you are, and it is all decided here
 * rather than by disabling things: a button that is visible and refuses has already been clicked by
 * somebody who thought it would work.
 */
private fun dev.th7bo.sidequest.ui.config.CategoryBuilder.debtSection(
    debt: Debt,
    key: String,
    actions: DebtActions,
    icons: DebtScreenIcons,
) {
    val prefix = "debts.d.$key"
    val theyOweMe = debt.creditor == actions.me
    val other = if (theyOweMe) debt.debtor else debt.creditor

    section(
        title(debt, actions, other),
        // Keyed on the debt rather than its title, because two debts with the same person for the same
        // amount is an ordinary Tuesday and a duplicate id throws the whole screen away.
        id = id(prefix),
        description = describe(debt, actions),
        icon = if (debt.isSettled) icons.settled else if (theyOweMe) icons.owed else icons.owing,
        collapsible = true,
        startsCollapsed = true,
    ) {
        // Only where there is a decision to make. Agreeing to something already agreed, or to something
        // settled, is not an action anybody needs offered.
        if (!debt.isSettled && debt.debtor == actions.me && !debt.debtorAgreed) {
            button(
                id = id("$prefix.agree"),
                title = "Agree",
                label = "That is right",
                description = "It starts counting towards your balance",
            ) {
                actions.agree(debt.id)
                actions.reopen()
            }
        }

        // Payments are coins only — see `DebtAmount.Item`. An item is returned or it is not, so the box
        // that takes an amount would have nothing meaningful to put in it.
        if (!debt.isSettled && debt.amount is DebtAmount.Coins) {
            textField(
                id = id("$prefix.amount"),
                title = "Record a payment",
                description = "5m, 250k, or a plain number",
                value = bind(
                    get = { actions.draft(debt.id) },
                    set = { value -> actions.setDraft(debt.id, value) },
                    debugName = "debt.draft",
                ),
                placeholder = Coins.format(debt.outstanding),
            )
            button(
                id = id("$prefix.pay"),
                title = "Apply it",
                label = "Record",
                description = "${Coins.format(debt.outstanding)} outstanding",
            ) {
                actions.pay(debt.id)
                actions.reopen()
            }
        }

        if (!debt.isSettled) {
            button(
                id = id("$prefix.forgive"),
                title = if (theyOweMe) "Forgive it" else "Mark as settled",
                label = if (theyOweMe) "Forgive" else "Settle",
                // Said as what it is. Calling it "paid" would put something in the history that did not
                // happen, and the history is the whole reason settled debts are kept.
                description = "Closes it without recording a payment. The record stays.",
                destructive = true,
            ) {
                actions.forgive(debt.id)
                actions.reopen()
            }
        }
    }
}

/** The row's heading: who, and how much. */
private fun title(debt: Debt, actions: DebtActions, other: PlayerId): String {
    val name = actions.nameOf(other)
    return when (val owed = debt.amount) {
        is DebtAmount.Coins -> "$name · ${Coins.format(if (debt.isSettled) owed.amount else debt.outstanding)}"
        is DebtAmount.Item -> "$name · ${owed.item.displayName.ifBlank { owed.item.minecraftId }}"
    }
}

/** The line under it: what it was for, what has been paid, and whether it is agreed. */
private fun describe(debt: Debt, actions: DebtActions): String = buildString {
    append(if (debt.creditor == actions.me) "owed to you" else "you owe")
    if (debt.reason.isNotBlank()) append(" · ").append(debt.reason)

    when {
        debt.isWrittenOff -> append(" · written off")
        debt.isSettled -> append(" · settled")
        // Only when something has actually been paid. "0 paid" on every open debt is noise on the line
        // that is meant to tell you which ones have moved.
        debt.paid > 0 -> append(" · ").append(Coins.format(debt.paid)).append(" paid")
    }
    if (!debt.isAgreed && !debt.isSettled) append(" · not agreed yet")
}

private fun describeBalance(balance: Long): String = when {
    balance > 0 -> "You are owed ${Coins.format(balance)} on balance."
    balance < 0 -> "You owe ${Coins.format(-balance)} on balance."
    else -> "Even on balance."
}

/** The total of a group of debts, for a category's line. */
private fun total(debts: List<Debt>): String {
    if (debts.isEmpty()) return "Nothing"
    val coins = debts.sumOf { it.outstanding }
    val items = debts.count { it.amount is DebtAmount.Item }
    return buildString {
        append(debts.size)
        if (coins > 0) append(" · ").append(Coins.format(coins))
        // Counted separately because they have no coin value, and folding them into a total would either
        // invent a price or make the count disagree with the sum.
        if (items > 0) append(" · ").append(items).append(" item").append(if (items == 1) "" else "s")
    }
}

/**
 * A unique, legal id fragment for one debt.
 *
 * Slug for readability, ordinal for uniqueness — a debt appears in exactly one category, but two debts whose
 * ids slug alike would still collide, and a duplicate id throws the entire screen away.
 */
private fun key(id: String, ordinals: Map<String, Int>): String = slug(id) + "_" + (ordinals[id] ?: 0)

private fun slug(raw: String): String {
    val cleaned = raw.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_')
    return cleaned.ifEmpty { "debt" }
}

/** The mod's own namespace. Spelled out rather than imported, so this module needs nothing from the mod. */
private const val NAMESPACE = "sidequest"

private fun id(path: String): UiId = UiId.of(NAMESPACE, path)
