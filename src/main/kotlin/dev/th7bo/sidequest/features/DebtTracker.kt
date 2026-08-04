package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.platform.core.backend.RealtimeMessageReceivedEvent
import dev.th7bo.sidequest.platform.core.debt.Coins
import dev.th7bo.sidequest.platform.core.debt.Debt
import dev.th7bo.sidequest.platform.core.debt.DebtAmount
import dev.th7bo.sidequest.platform.core.debt.DebtLedger
import dev.th7bo.sidequest.platform.core.notification.notification
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.notification.NotificationPriority
import dev.th7bo.sidequest.platform.player.PlayerAction
import dev.th7bo.sidequest.platform.player.PlayerActionEntry
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.storage.Repository
import dev.th7bo.sidequest.platform.storage.StorageScope
import dev.th7bo.sidequest.protocol.AccountId
import dev.th7bo.sidequest.protocol.RealtimePayload

/**
 * Who owes what, and whether they have paid.
 *
 * The arithmetic is all in `DebtLedger`, where it can be tested — this is the part that needs a client:
 * keeping the ledger on disk, turning commands into edits, and offering the group's half through the action
 * menu.
 *
 * **A debt somebody records against you is a claim until you agree.** That is the plan's "confirmation from
 * both parties" and it is the rule this feature exists to enforce: anybody can type that you owe them five
 * million, and a balance that counted it would let one person move another's number by typing. Unagreed
 * debts are visible and listed; they are simply not arithmetic.
 *
 * Coins are entered as SkyBlock writes them — `5m`, `250k` — because nobody types eight zeroes and a ledger
 * that made them would be a ledger nobody used.
 */
class DebtTracker(
    /** Who is playing. Needs the game, and decides which file the ledger lives in. */
    private val localPlayer: () -> PlayerId?,
    /** Publishes to the group. False when nothing was sent. */
    private val publish: (debt: Debt, isPayment: Boolean) -> Boolean = { _, _ -> false },
    /**
     * Turns a backend account into the player it belongs to.
     *
     * The backend speaks in accounts and this feature speaks in Minecraft UUIDs. Passed in rather than
     * looked up, so the feature needs no backend client of its own — and null for somebody this client has
     * never seen, which is treated as "not about anybody here" rather than guessed at.
     */
    private val resolveAccount: (AccountId) -> PlayerId? = { null },
    private val now: () -> Long = System::currentTimeMillis,
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("debt.tracker"),
        displayName = "Debts",
        category = FeatureCategory.SOCIAL,
        description = "Remembers who owes what, so nobody has to",
    )

    private lateinit var context: FeatureContext

    private var repository: Repository<DebtLedger>? = null

    private var ledger: DebtLedger = DebtLedger()

    override fun onEnable(context: FeatureContext) {
        this.context = context

        // Account scope, like the friend list: a debt is owed to a person, not to a profile, and filing it
        // per profile would split one balance across three of them.
        localPlayer()?.let { player ->
            val store = context.store(
                name = "debts",
                scope = StorageScope.Account(player),
                serializer = DebtLedger.serializer(),
                default = { DebtLedger() },
            )
            repository = store
            context.scheduler.async(context.owner) {
                val loaded = store.load()
                context.scheduler.onMain(context.owner) {
                    ledger = loaded.value
                    announceWhatIsWaiting()
                }
            }
        }

        // The group's half. Read here rather than in a shared receiver because a debt is this feature's
        // whole subject — unlike a marker, which several features place and which therefore needs one
        // receiver so they cannot each invent a lifetime for it.
        context.listen(RealtimeMessageReceivedEvent::class) { received ->
            when (val payload = received.message.payload) {
                is RealtimePayload.DebtCreated -> onRemoteCreated(payload, received.message.senderAccount)
                is RealtimePayload.PaymentConfirmed -> onRemotePayment(payload)
                else -> Unit
            }
        }

        registerCommands()
        registerActions()
    }

    override fun onDisable() = Unit

    /**
     * Says what needs answering, once, at login.
     *
     * Only what is waiting on *this* player — a debt somebody recorded while they were offline is exactly
     * the thing they would otherwise never notice, since nothing else in the mod would mention it again.
     */
    private fun announceWhatIsWaiting() {
        val me = localPlayer() ?: return
        val waiting = ledger.awaitingAgreement(me)
        if (waiting.isEmpty()) return

        say(
            "${waiting.size} debt${plural(waiting.size)} waiting on you",
            "/sqdebt list to see them. Nothing counts until you agree.",
            priority = NotificationPriority.HIGH,
        )
    }

    // -- commands -------------------------------------------------------------

    private fun registerCommands() {
        context.command(
            name = "sqdebt",
            description = "Track who owes what",
            usage = "[list|owe <player> <amount> [reason]|lent <player> <amount> [reason]|paid <id> <amount>|agree <id>|forgive <id>]",
            completions = { arguments ->
                when (arguments.size) {
                    0, 1 -> VERBS
                    2 -> when (arguments.first().lowercase()) {
                        // Friends, because a debt against somebody you have met once is not the common case
                        // and the resolver still accepts any name that has been seen.
                        "owe", "lent" -> context.players.customFriends().map { it.username }
                        "paid", "agree", "forgive" ->
                            localPlayer()?.let { me -> ledger.open(me).map { it.id } }.orEmpty()
                        else -> emptyList()
                    }
                    else -> emptyList()
                }
            },
        ) { arguments -> handle(arguments) }
    }

    private fun handle(arguments: List<String>) {
        val rest = arguments.drop(1)
        when (arguments.firstOrNull()?.lowercase()) {
            // No verb opens the screen, matching /sqwp and /sqfriend. `list` stays for the times
            // somebody wants the answer without a screen covering the game.
            null, "" -> openLedger()
            "list" -> list()
            // Two verbs rather than one with a direction flag, because "who owes whom" is the single thing
            // this feature must never get backwards and a flag is exactly how that gets typed wrong.
            "owe" -> record(rest.firstOrNull(), rest.getOrNull(1), rest.drop(2).joinToString(" "), iOwe = true)
            "lent" -> record(rest.firstOrNull(), rest.getOrNull(1), rest.drop(2).joinToString(" "), iOwe = false)
            "paid" -> paid(rest.firstOrNull(), rest.getOrNull(1))
            "agree" -> agree(rest.firstOrNull())
            "forgive", "writeoff" -> forgive(rest.firstOrNull())
            else -> say("Not a debt command", VERBS.joinToString(" · "))
        }
    }

    /**
     * Records a debt in one direction or the other.
     *
     * @param iOwe true for `/sqdebt owe`, which records the local player as the debtor.
     */
    private fun record(who: String?, amount: String?, reason: String, iOwe: Boolean) {
        val me = localPlayer() ?: return
        val other = who?.let { resolve(it) }
        if (other == null) {
            say("Who?", "Sidequest can only record debts against players it has seen.")
            return
        }
        if (other == me) {
            say("That is you")
            return
        }
        val coins = amount?.let(Coins::parse)
        if (coins == null || coins <= 0) {
            say("How much?", "Try 5m, 250k, or a plain number.")
            return
        }

        val debt = Debt(
            id = "debt" + nextId(),
            debtor = if (iOwe) me else other,
            creditor = if (iOwe) other else me,
            amount = DebtAmount.Coins(coins),
            reason = reason.take(MAX_REASON),
            createdAtMillis = now(),
            // Whoever writes it has agreed to it by writing it, and the asymmetry after that is
            // deliberate. Declaring a debt *against yourself* counts immediately — nobody lies to their own
            // disadvantage, and making somebody confirm a gift is friction protecting against nothing.
            // Declaring one against somebody else waits for them, which is the whole safety property.
            debtorAgreed = iOwe,
            creditorAgreed = true,
        )

        ledger = ledger.with(debt)
        persist()
        publish(debt, false)

        val name = context.players.byId(other)?.displayName ?: "them"
        say(
            if (iOwe) "You owe $name ${Coins.format(coins)}" else "$name owes you ${Coins.format(coins)}",
            if (iOwe) "Recorded, and already agreed by you." else "Waiting for them to agree. /sqdebt list",
        )
    }

    private fun paid(id: String?, amount: String?) {
        val debt = id?.let { ledger[it] }
        if (debt == null) {
            say("No such debt", "/sqdebt list")
            return
        }
        val coins = amount?.let(Coins::parse)
        if (coins == null || coins <= 0) {
            say("How much?", "Try 5m, 250k, or a plain number.")
            return
        }

        val updated = debt.repay(coins, now())
        if (updated == null) {
            // The only way `repay` refuses a positive amount is an item debt, so say the useful thing
            // rather than a generic refusal.
            say("That debt is not in coins", "Use /sqdebt forgive ${debt.id} when it is returned.")
            return
        }

        ledger = ledger.with(updated)
        persist()
        publish(updated, true)
        say(
            if (updated.isSettled) "Settled" else "Paid ${Coins.format(coins)}",
            if (updated.isSettled) "Nothing left on it." else "${Coins.format(updated.outstanding)} left.",
        )
    }

    private fun agree(id: String?) {
        val me = localPlayer() ?: return
        val debt = id?.let { ledger[it] }
        if (debt == null || !debt.involves(me)) {
            say("No such debt", "/sqdebt list")
            return
        }

        // Whichever side you are. Agreeing to a debt you are owed is a real action too — it is how a debt
        // somebody recorded as "I owe you" becomes arithmetic.
        val updated = if (debt.debtor == me) debt.copy(debtorAgreed = true) else debt.copy(creditorAgreed = true)
        ledger = ledger.with(updated)
        persist()
        publish(updated, false)
        say("Agreed", describe(updated, me))
    }

    private fun forgive(id: String?) {
        val debt = id?.let { ledger[it] }
        if (debt == null) {
            say("No such debt", "/sqdebt list")
            return
        }
        val updated = debt.copy(isWrittenOff = true)
        ledger = ledger.with(updated)
        persist()
        publish(updated, true)
        // Said as what it is. "Settled" would suggest it was paid, and the history says otherwise.
        say("Written off", "It no longer counts. The record stays.")
    }

    private fun list() {
        val me = localPlayer() ?: return
        val open = ledger.open(me)
        if (open.isEmpty()) {
            say("Nothing outstanding", "Nobody owes you and you owe nobody.")
            return
        }

        val balance = ledger.balanceOf(me)
        say(
            when {
                balance > 0 -> "You are owed ${Coins.format(balance)} on balance"
                balance < 0 -> "You owe ${Coins.format(-balance)} on balance"
                else -> "${open.size} open debt${plural(open.size)}, even on balance"
            },
            open.joinToString(" · ") { describe(it, me) }.take(SUBTITLE_LIMIT),
        )
    }

    /** One debt, from this player's side. */
    private fun describe(debt: Debt, me: PlayerId): String {
        val other = if (debt.debtor == me) debt.creditor else debt.debtor
        val name = context.players.byId(other)?.displayName ?: "somebody"
        val direction = if (debt.debtor == me) "you owe $name" else "$name owes you"
        val waiting = if (!debt.isAgreed) " (unagreed)" else ""
        return "${debt.id}: $direction ${Coins.format(debt.outstanding)}$waiting"
    }

    // -- the action menu ------------------------------------------------------

    /**
     * What this feature offers on a player.
     *
     * Only "view", because recording a debt needs an amount and a menu button has nowhere to type one. The
     * button opens the list rather than pretending otherwise — an action that silently invented a number
     * would be the worst possible thing for this particular feature to do.
     */
    private fun registerActions() {
        context.scope.add(
            context.playerActions.register(context.owner) { target ->
                val me = localPlayer()
                if (target.isSelf || me == null) return@register emptyList()

                val between = ledger.balanceBetween(me, target.player.id)
                val open = ledger.involving(target.player.id).count { !it.isSettled }
                if (open == 0) return@register emptyList()

                listOf(
                    PlayerActionEntry(
                        PlayerAction(
                            id = SqId.sidequest("action.debt.view"),
                            label = "Debts",
                            description = when {
                                between > 0 -> "They owe you ${Coins.format(between)}"
                                between < 0 -> "You owe them ${Coins.format(-between)}"
                                else -> "$open open, even on balance"
                            },
                            order = DEBT_ORDER,
                        ),
                    ) {
                        // The screen, not a notification. A debt has an id you would otherwise have to
                        // read off a toast and type back — which is the whole reason the screen exists.
                        openLedger()
                    },
                )
            },
        )
    }

    // -- what the rest of the mod reads ---------------------------------------

    /** The ledger, for a screen that draws it. */
    fun ledger(): DebtLedger = ledger

    /** Opens the ledger screen. Set by the mod, which owns screens. */
    var openLedger: () -> Unit = {}

    /**
     * What has been typed into each debt's payment box.
     *
     * Held here rather than on the screen because the screen is rebuilt on every reopen — and recording a
     * payment reopens it — so a draft living there would be lost by the act of using it.
     */
    private val drafts = mutableMapOf<String, String>()

    fun draft(id: String): String = drafts[id].orEmpty()

    fun setDraft(id: String, text: String) {
        drafts[id] = text
    }

    /** Applies whatever is in a debt's payment box. */
    fun applyDraft(id: String) {
        val typed = drafts[id].orEmpty()
        paid(id, typed)
        // Cleared only on success, so a rejected amount stays in the box for somebody to correct rather
        // than vanishing and leaving them to guess what they typed wrong.
        if (Coins.parse(typed) != null) drafts.remove(id)
    }

    /** Agrees to a debt, for the screen. */
    fun agreeTo(id: String) = agree(id)

    /** Writes a debt off, for the screen. */
    fun forgiveDebt(id: String) = forgive(id)

    /**
     * Records a debt that arrived from the group.
     *
     * **Never agreed on arrival.** Somebody else's client asserting that you owe them is exactly the claim
     * this feature exists to hold at arm's length — it lands as a claim and waits, whoever sent it.
     *
     * The creditor is the *sender*, taken from the envelope rather than from the payload. A client that
     * could name its own creditor could record a debt owed to somebody else, and the server stamps the
     * sender precisely so that this does not have to be trusted.
     */
    private fun onRemoteCreated(payload: RealtimePayload.DebtCreated, sender: AccountId?) {
        val debtor = resolveAccount(payload.debtor) ?: return
        val creditor = sender?.let(resolveAccount) ?: return
        val me = localPlayer() ?: return
        // Only what concerns this player. A debt between two other people is their business, and the
        // server's own addressing should have kept it away — this is the belt to that pair of braces.
        if (debtor != me && creditor != me) return

        // Never overwrite what is already here. A resend or a resume must not undo an agreement, or erase
        // repayments recorded since, by replacing the debt with the version that first went out.
        if (ledger[payload.debtId] != null) return

        val amount = payload.item?.let { DebtAmount.Item(it) } ?: DebtAmount.Coins(payload.coins)
        val debt = Debt(
            id = payload.debtId,
            debtor = debtor,
            creditor = creditor,
            amount = amount,
            reason = payload.note.orEmpty().take(MAX_REASON),
            createdAtMillis = now(),
            // The sender wrote it, so they have agreed. The debtor has not, unless the debtor is the sender
            // — somebody declaring their own debt, which counts immediately for the reason `record` gives.
            debtorAgreed = debtor == creditor || sender == payload.debtor,
            creditorAgreed = true,
        )

        ledger = ledger.with(debt)
        persist()

        if (debt.debtor == me && !debt.debtorAgreed) {
            say(
                "Somebody says you owe them",
                "${Coins.format(debt.outstanding)} — /sqdebt agree ${debt.id} if that is right.",
                priority = NotificationPriority.HIGH,
            )
        }
    }

    /**
     * Records a payment that arrived from the group.
     *
     * Applied only to a debt already held. A payment naming a debt this client has never heard of is not
     * something to invent a debt from — the amount would be the payment rather than what was owed, and the
     * result would be a settled debt that never existed.
     */
    private fun onRemotePayment(payload: RealtimePayload.PaymentConfirmed) {
        val existing = ledger[payload.debtId] ?: return
        val updated = existing.repay(payload.coins, now(), payload.note?.take(MAX_REASON)) ?: return

        ledger = ledger.with(updated)
        persist()

        val me = localPlayer()
        if (me != null && updated.creditor == me) {
            say(
                if (updated.isSettled) "Settled" else "Paid ${Coins.format(payload.coins)}",
                if (updated.isSettled) "Nothing left on it." else "${Coins.format(updated.outstanding)} left.",
            )
        }
    }

    // -- odds and ends --------------------------------------------------------

    private fun resolve(name: String): PlayerId? {
        PlayerId.parse(name)?.let { return it }
        return context.players.resolveUsername(name.trim().trim('"'))?.id
    }

    private var idCounter = 0

    /** Short, unique and alphanumeric, for the same reasons a waypoint's id is. */
    private fun nextId(): String = now().toString(RADIX) + (idCounter++).toString(RADIX)

    private fun persist() {
        val store = repository ?: return
        val snapshot = ledger
        context.scheduler.async(context.owner) {
            runCatching { store.save(snapshot) }
                .onFailure { context.log.warn(it) { "Could not save the debt ledger" } }
        }
    }

    private fun say(
        title: String,
        subtitle: String = "",
        priority: NotificationPriority = NotificationPriority.NORMAL,
    ) {
        context.notifications.notify(
            notification(
                category = NotificationCategory.SOCIAL,
                title = title,
                subtitle = subtitle,
                priority = priority,
            ),
        )
    }

    private fun plural(count: Int): String = if (count == 1) "" else "s"

    private companion object {
        val VERBS = listOf("list", "owe", "lent", "paid", "agree", "forgive")

        const val MAX_REASON = 100

        const val SUBTITLE_LIMIT = 160

        /** Below the friend and party actions: a debt is a thing to look at, not a thing to do. */
        const val DEBT_ORDER = 300

        /** Base 36, like every other short id the mod generates. */
        const val RADIX = 36
    }
}
