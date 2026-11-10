package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.platform.evidence.EvidenceEntry
import dev.th7bo.sidequest.platform.evidence.EvidenceKind
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.notification.NotificationPriority
import dev.th7bo.sidequest.platform.player.PlayerId

/**
 * The local half of the evidence locker, with a door on it.
 *
 * Most entries will arrive from rules — a rare drop or a joke death writing itself down at the moment it
 * happens. This feature is for the ones only a person can classify: the wrong lobby, the thing Andy said,
 * the trade that went exactly as badly as expected. It records what it is told and stays out of the way;
 * albums, reactions and comments are group content and belong to the backend's half of the locker.
 *
 * **Nothing here captures a screenshot.** Recording text is reversible by deleting it; capture is a
 * framebuffer act that deserves its own explicit ask, which will live on a notification action when it
 * exists. Until then `/sqev` writes moments without images rather than pretending otherwise.
 */
class EvidenceNotes(
    /** Who is playing, for stamping entries with who was there. */
    private val localPlayer: () -> PlayerId?,
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("evidence.notes"),
        displayName = "Evidence notes",
        category = FeatureCategory.SOCIAL,
        description = "Writes down the moments worth keeping",
    )

    private lateinit var context: FeatureContext

    override fun onEnable(context: FeatureContext) {
        this.context = context

        context.command(
            name = "sqev",
            description = "The evidence locker",
            usage = "[list|note <title>|hide <id>|unhide <id>|remove <id>]",
            completions = { done ->
                when {
                    done.isEmpty() -> VERBS
                    done.first() in listOf("hide", "unhide", "remove") ->
                        context.evidence.all(includeHidden = true).map { it.id }
                    else -> emptyList()
                }
            },
        ) { arguments -> handle(arguments.drop(1)) }
    }

    override fun onDisable() = Unit

    private fun handle(arguments: List<String>) {
        when (arguments.firstOrNull()?.lowercase()) {
            null, "", "list" -> list()
            "note" -> note(arguments.drop(1).joinToString(" "))
            "hide" -> setHidden(arguments.getOrNull(1), true)
            "unhide" -> setHidden(arguments.getOrNull(1), false)
            "remove", "rm" -> remove(arguments.getOrNull(1))
            else -> say("Not an evidence verb", VERBS.joinToString(" · "))
        }
    }

    /**
     * Records what just happened, stamped with where and who.
     *
     * The island comes from the game context rather than being asked for, because the answer to "where did
     * this happen" is always *here* — and the moment somebody has to type it is the moment they do not.
     */
    private fun note(title: String) {
        if (title.isBlank()) {
            say("A note needs a title", "/sqev note <what happened>")
            return
        }
        val me = localPlayer()
        val stored = context.evidence.record(
            EvidenceEntry(
                id = "",
                kind = EvidenceKind.INCIDENT,
                title = title.take(MAX_TITLE),
                atMillis = System.currentTimeMillis(),
                players = listOfNotNull(me),
                island = context.gameContext.context.island,
                activity = context.gameContext.context.activity.activity.displayName,
                tags = setOf("note"),
            ),
        ) ?: return

        say("Recorded #${stored.id}", "${stored.kind.displayName} · ${context.gameContext.context.island.displayName}")
    }

    private fun list() {
        val visible = context.evidence.all()
        if (visible.isEmpty()) {
            say("The locker is empty", "/sqev note <what happened> to start it.")
            return
        }
        say(
            "${visible.size} entr${if (visible.size == 1) "y" else "ies"}",
            visible.take(LIST_LINES).joinToString(" · ") { "#${it.id} ${it.title}" }.take(SUBTITLE_LIMIT),
        )
    }

    private fun setHidden(id: String?, hidden: Boolean) {
        val entry = id?.let { context.evidence.get(it) }
        if (entry == null) {
            say("No such entry", "/sqev list")
            return
        }
        if (hidden) context.evidence.hide(entry.id) else context.evidence.unhide(entry.id)
        say(if (hidden) "Hidden" else "Back in view", "#${entry.id} ${entry.title}")
    }

    private fun remove(id: String?) {
        val entry = id?.let { context.evidence.get(it) }
        if (entry == null) {
            say("No such entry", "/sqev list")
            return
        }
        context.evidence.remove(entry.id)
        say("Removed", "#${entry.id} ${entry.title}")
    }

    private fun say(title: String, subtitle: String, priority: NotificationPriority = NotificationPriority.NORMAL) {
        context.notifications.notify(
            dev.th7bo.sidequest.platform.core.notification.notification(
                category = NotificationCategory.SOCIAL,
                title = title,
                subtitle = subtitle,
                priority = priority,
            ),
        )
    }

    private companion object {
        val VERBS = listOf("list", "note", "hide", "unhide", "remove")

        const val MAX_TITLE = 80

        const val LIST_LINES = 6

        const val SUBTITLE_LIMIT = 160
    }
}
