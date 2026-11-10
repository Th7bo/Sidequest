package dev.th7bo.sidequest.feature.ui

import dev.th7bo.sidequest.platform.achievement.Achievement
import dev.th7bo.sidequest.platform.achievement.AchievementCategory
import dev.th7bo.sidequest.platform.currency.CurrencyTransaction
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Icon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The icons the cabinet draws. Defaulted to the framework's own; the host may supply better. */
public data class AchievementScreenIcons(
    public val earned: Icon = Icon(UiId.of("sidequest", "icon.check")),
    public val locked: Icon = Icon(UiId.of("sidequest", "icon.levels")),
    public val wallet: Icon = Icon(UiId.of("sidequest", "icon.plus")),
)

/**
 * One achievement, as the screen draws it.
 *
 * A row rather than the definition plus two getters, because the join between a definition and its state is
 * done once by whoever builds this list — the screen should not know that records live in one service and
 * progress in another, and a screen that does not know cannot get it wrong.
 */
public data class AchievementRow(
    public val definition: Achievement,
    /** When it was earned, or null while it is locked. */
    public val unlockedAtMillis: Long? = null,
    /** Progress on the rule it rides on, where there is one. Null means "no number to show". */
    public val progress: Int? = null,
    /** The rule's tiers, ascending, for naming the next target. Empty for an untiered rule. */
    public val tiers: List<Int> = emptyList(),
) {

    public val isUnlocked: Boolean get() = unlockedAtMillis != null

    /** The next tier above the current progress, or null when there is no higher one. */
    public val nextTier: Int?
        get() = progress?.let { value -> tiers.firstOrNull { it > value } }
}

/**
 * What the cabinet can read.
 *
 * Getters rather than values, for the reason every screen here gives: rows are built from a snapshot but
 * the wallet moves while the screen is open, and a balance that froze on open would be read as a bug.
 */
public class AchievementActions(
    /** The rows as they are *now*. */
    public val current: () -> List<AchievementRow>,
    public val balance: () -> Long,
    /** Recent transactions, newest first. */
    public val history: () -> List<CurrencyTransaction>,
)

/**
 * The trophy cabinet, with the wallet beside it.
 *
 * **Grouped by category, earned before locked within each** — because the questions asked of this screen are
 * "what have I got" and "what is left", never "everything sorted by id". A hidden achievement appears only
 * once it is earned: the caller filters them, which keeps this builder free of any opinion about secrecy.
 *
 * An empty cabinet says so and how things get into it. A screen showing nothing at all reads as broken;
 * one line saying why it is empty does not.
 */
public fun buildAchievementScreen(
    rows: List<AchievementRow>,
    actions: AchievementActions,
    icons: AchievementScreenIcons = AchievementScreenIcons(),
): ConfigScreen {
    val balance = actions.balance()

    return configScreen(id("achievements"), "Achievements", describeBalance(balance)) {
        category(id("achievements.wallet"), "Wallet", description = "${actions.balance()} coins", icon = icons.wallet) {
            section(
                "Sidequest coins",
                description = "Earned from achievements. Spent on cosmetics, titles and sounds.",
                icon = icons.wallet,
                collapsible = true,
                startsCollapsed = true,
            ) {
                val recent = actions.history()
                if (recent.isEmpty()) {
                    description(id("achievements.wallet.empty"), "Nothing yet.")
                }
                for (transaction in recent.take(RECENT_TRANSACTIONS)) {
                    description(
                        id("achievements.wallet.t." + transaction.atMillis),
                        describe(transaction),
                    )
                }
            }
        }

        // Every category that has anything in it, plan order first. An empty category is not drawn —
        // a heading over nothing is noise, and there will be empty ones until the joke content lands.
        val byCategory = AchievementCategory.entries.associateWith { category -> rows.filter { it.definition.category == category } }
        for (category in AchievementCategory.entries) {
            val inCategory = byCategory[category].orEmpty()
            if (inCategory.isEmpty()) continue

            category(
                id("achievements.c." + slug(category.name)),
                category.displayName,
                description = summary(inCategory),
                icon = if (inCategory.all { it.isUnlocked }) icons.earned else icons.locked,
            ) {
                // Earned first, then the rest in the order given — so the top of each group is what you
                // have and the bottom is what you could still get.
                for (row in inCategory.sortedByDescending { it.isUnlocked }) rowSection(row, actions, icons)
            }
        }

        if (rows.isEmpty()) {
            category(id("achievements.none"), "Nothing here yet") {
                section(
                    "No achievements",
                    description = "Achievements appear here as features declare them.",
                ) {
                    button(id("achievements.none.body"), "How it works", label = "Got it") { }
                }
            }
        }
    }
}

private fun dev.th7bo.sidequest.ui.config.CategoryBuilder.rowSection(
    row: AchievementRow,
    actions: AchievementActions,
    icons: AchievementScreenIcons,
) {
    val prefix = "achievements.a." + slug(row.definition.id.path)
    section(
        row.definition.displayName,
        id = id(prefix),
        description = describe(row),
        icon = if (row.isUnlocked) icons.earned else icons.locked,
    ) {
        if (row.definition.description.isNotBlank()) {
            description(id("$prefix.desc"), row.definition.description)
        }
        val reward = row.definition.reward
        if (reward.hasReward) {
            description(id("$prefix.reward"), describeReward(reward))
        }
    }
}

/** The line under the title: earned, or how far along. */
private fun describe(row: AchievementRow): String = when {
    row.isUnlocked -> "earned ${formatDate(row.unlockedAtMillis)}"
    row.progress == null -> "locked"
    else -> {
        val next = row.nextTier
        if (next == null) {
            "$row.progress / ${row.tiers.lastOrNull() ?: row.progress}"
        } else {
            "$row.progress / $next"
        }
    }
}

private fun describeReward(reward: dev.th7bo.sidequest.platform.achievement.AchievementReward): String =
    listOfNotNull(
        reward.currency.takeIf { it > 0 }?.let { "$it coins" },
        reward.titleId?.let { "a title" },
        reward.cosmeticId?.let { "a cosmetic" },
    ).joinToString(", ").replaceFirstChar { it.uppercaseChar() }

private fun describe(transaction: CurrencyTransaction): String = buildString {
    append(if (transaction.delta >= 0) "+" else "")
    append(transaction.delta)
    append(" · ")
    append(transaction.reason ?: transaction.source)
    append(" · ")
    append(formatDate(transaction.atMillis))
}

private fun describeBalance(balance: Long): String = "$balance coins"

/** "4 of 7" — the only summary of a category anybody wants. */
private fun summary(rows: List<AchievementRow>): String {
    val earned = rows.count { it.isUnlocked }
    return "$earned of ${rows.size}"
}

private fun formatDate(epochMillis: Long?): String =
    epochMillis?.let { DATE_FORMAT.format(Instant.ofEpochMilli(it)) } ?: ""

/** The mod's own namespace. Spelled out rather than imported, so this module needs nothing from the mod. */
private const val NAMESPACE = "sidequest"

/** How much of the wallet's recent history a collapsed section shows. */
private const val RECENT_TRANSACTIONS = 8

private fun id(path: String): UiId = UiId.of(NAMESPACE, path)

private fun slug(raw: String): String {
    val cleaned = raw.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_')
    return cleaned.ifEmpty { "x" }
}

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
