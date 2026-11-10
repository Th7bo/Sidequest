package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.feature.ui.AchievementActions
import dev.th7bo.sidequest.feature.ui.AchievementRow
import dev.th7bo.sidequest.platform.achievement.AchievementSource
import dev.th7bo.sidequest.platform.achievement.AchievementUnlockedEvent
import dev.th7bo.sidequest.platform.currency.CurrencyTransaction
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.feature.listen
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.notification.NotificationPriority

/**
 * The cabinet, and the announcements that fill it.
 *
 * The service knows *that* an achievement was earned; this is the part that tells anybody. One listener on
 * [AchievementUnlockedEvent] turns every unlock into a toast regardless of which rule produced it — which is
 * why the rules themselves carry no notify action for their achievements: two places announcing one unlock
 * is how a double drop happens, and the rule author would have to remember not to.
 *
 * **The wallet is deliberately quiet.** A currency change posts no toast of its own — rewards arrive in a
 * burst during a recap, and five toasts about coins is how notifications get muted wholesale. The balance
 * lives on the cabinet screen instead.
 */
class AchievementCabinet : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("progression.cabinet"),
        displayName = "Achievements",
        category = FeatureCategory.PROGRESSION,
        description = "What you have earned, and what there is left to earn",
    )

    private lateinit var context: FeatureContext

    /** Opens the cabinet screen. Set by the mod, which owns screens. */
    var openCabinet: () -> Unit = {}

    override fun onEnable(context: FeatureContext) {
        this.context = context

        context.listen<AchievementUnlockedEvent> { event -> announce(event) }

        context.command(
            name = "sqachieve",
            description = "Your achievements and Sidequest coins",
            usage = "[list|unlock <id>]",
            completions = { done ->
                when {
                    done.isEmpty() -> VERBS
                    done.first() == "unlock" ->
                        context.achievements.all(includeHidden = true).map { it.id.path }
                    else -> emptyList()
                }
            },
        ) { arguments -> handle(arguments.drop(1)) }

        // The reward side of an unlock. Here rather than inside the service because granting currency is
        // applying a reward — a decision about presentation and policy — and the service stays a record.
        context.listen<AchievementUnlockedEvent> { event ->
            val reward = context.achievements.definitionOf(event.achievementId)?.reward ?: return@listen
            if (reward.currency > 0) {
                context.currency.grant(
                    amount = reward.currency,
                    reason = event.displayName,
                    source = "achievement",
                )
            }
        }
    }

    override fun onDisable() = Unit

    // -- commands -------------------------------------------------------------

    private fun handle(arguments: List<String>) {
        when (arguments.firstOrNull()?.lowercase()) {
            // No verb opens the screen, matching /sqdebt and /sqfriend.
            null, "" -> openCabinet()
            "list" -> list()
            // Not hidden behind a developer feature: unlocking by hand is also how an achievement that
            // fired while nobody was looking gets put right after the fact.
            "unlock" -> unlockManually(arguments.getOrNull(1))
            else -> say("Not an achievement command", VERBS.joinToString(" · "))
        }
    }

    private fun list() {
        val all = context.achievements.all()
        val earned = all.count { context.achievements.isUnlocked(it.id) }
        if (all.isEmpty()) {
            say("No achievements registered", "Features add them as they land.")
            return
        }
        say(
            "$earned of ${all.size} earned",
            "Balance: ${context.currency.balance()} coins. /sqachieve for the cabinet.",
        )
    }

    private fun unlockManually(path: String?) {
        val id = path?.let { runCatching { SqId.parse(it) }.getOrNull() } ?: run {
            say("Which achievement?", "/sqachieve unlock <namespace:path>")
            return
        }
        if (context.achievements.unlock(id, AchievementSource.MANUAL)) {
            say("Unlocked", context.achievements.definitionOf(id)?.displayName ?: id.value)
        } else {
            say(
                if (context.achievements.isUnlocked(id)) "Already earned" else "No such achievement",
                id.value,
            )
        }
    }

    // -- what the screen reads ------------------------------------------------

    /**
     * The rows the cabinet draws.
     *
     * The join between definition, record and rule progress happens here rather than on the screen: the
     * screen should not know that records live in one service and progress in another.
     */
    fun rows(): List<AchievementRow> = context.achievements.all().map { definition ->
        val rule = definition.ruleId
        AchievementRow(
            definition = definition,
            unlockedAtMillis = context.achievements.recordOf(definition.id)?.unlockedAtMillis,
            progress = rule?.let { context.rules.progressOf(it).progress },
            tiers = rule?.let { id -> context.rules.rules().firstOrNull { r -> r.id == id }?.tiers }
                .orEmpty(),
        )
    }

    fun actions(): AchievementActions = AchievementActions(
        current = ::rows,
        balance = { context.currency.balance() },
        history = { context.currency.history() },
    )

    /** Recent transactions, newest first — the wallet section's lines. */
    fun recentTransactions(): List<CurrencyTransaction> = context.currency.history()

    // -- announcing -----------------------------------------------------------

    private fun announce(event: AchievementUnlockedEvent) {
        val definition = context.achievements.definitionOf(event.achievementId)
        val coins = definition?.reward?.currency ?: 0
        val description = definition?.description.orEmpty()
        val subtitle = when {
            coins > 0 -> "+$coins coins"
            description.isNotBlank() -> description
            else -> ""
        }
        say(
            "Achievement: ${event.displayName}",
            subtitle,
            priority = NotificationPriority.HIGH,
        )
    }

    private fun say(title: String, subtitle: String, priority: NotificationPriority = NotificationPriority.NORMAL) {
        context.notifications.notify(
            dev.th7bo.sidequest.platform.core.notification.notification(
                category = NotificationCategory.PROGRESSION,
                title = title,
                subtitle = subtitle,
                priority = priority,
            ),
        )
    }

    private companion object {
        val VERBS = listOf("list", "unlock")
    }
}
