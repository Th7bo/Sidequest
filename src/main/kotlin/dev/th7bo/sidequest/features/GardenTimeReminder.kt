package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.SidequestSettings
import dev.th7bo.sidequest.platform.audio.SoundDefinition
import dev.th7bo.sidequest.platform.audio.SoundGroup
import dev.th7bo.sidequest.platform.audio.SoundRequest
import dev.th7bo.sidequest.platform.chat.ChatMessageEvent
import dev.th7bo.sidequest.platform.core.garden.FarmingStreak
import dev.th7bo.sidequest.platform.core.garden.GardenTimeChat
import dev.th7bo.sidequest.platform.core.garden.GardenTimeTodo
import dev.th7bo.sidequest.platform.core.garden.GardenTimeTodo.Advice
import dev.th7bo.sidequest.platform.core.garden.GardenTime
import dev.th7bo.sidequest.platform.core.garden.PestChat
import dev.th7bo.sidequest.platform.core.garden.PestCooldown
import dev.th7bo.sidequest.platform.core.parser.TabListParser
import dev.th7bo.sidequest.platform.core.notification.notification
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.feature.listen
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.notification.NotificationPriority
import dev.th7bo.sidequest.platform.parser.TabListChangedEvent
import dev.th7bo.sidequest.platform.parser.TabWidget
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.text.SqStyle
import dev.th7bo.sidequest.platform.text.SqText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Keeps the Garden's time on the right side of each pest wave.
 *
 * Farm at Day, Night just for the spawn, Day again before anything is killed — the routine lives in
 * [GardenTimeTodo]. What this adds is the watching: it reads Hypixel's own confirmations, reminds the player
 * while they farm through a Night that has already had its spawn, and makes a pest killed at Night
 * impossible to miss.
 *
 * **Reminders ride on the farming run.** A nag on arrival is a nag for somebody who came to talk to a
 * visitor; a nag once the crops are going is a nag for exactly the person who forgot.
 */
class GardenTimeReminder(
    /** Shows a vanilla title. Supplied by the mod, since the platform has no Minecraft. */
    private val showTitle: (title: SqText, subtitle: SqText?) -> Unit,
    /** Writes a line to the local chat. */
    private val sendChat: (SqText) -> Unit,
    /** How many blocks the player has broken, ever. Differenced here into a farming run. */
    private val blocksBroken: () -> Long,
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("garden.time_reminder"),
        displayName = "Garden time reminder",
        category = FeatureCategory.UTILITY,
        description = "Night for the pest spawn, Day before you kill them",
    )

    private lateinit var context: FeatureContext

    private val todo = GardenTimeTodo()

    private val streak = FarmingStreak()
    private val since = TimeSource.Monotonic.markNow()
    private var seenBlocks: Long = 0

    /** When the current run was last reminded, or null if it has not been. */
    private var remindedAt: Duration? = null

    /**
     * When the pest cooldown runs out, on [since]'s clock, or null when no spawn is coming.
     *
     * Read off the tab list's Pests widget. Null at max pests, and until the widget has been seen at all.
     */
    private var cooldownEndsAt: Duration? = null

    /** When the player was last told to set Night for the coming spawn, or null outside that window. */
    private var nightRemindedAt: Duration? = null

    override fun onEnable(context: FeatureContext) {
        this.context = context

        context.sounds.register(
            SoundDefinition(
                id = ALARM,
                resource = "minecraft:block.note_block.pling",
                group = SoundGroup.INTERFACE,
                // Played as a burst, so the cooldown has to be shorter than the gap between notes.
                cooldown = 50.milliseconds,
            ),
        )
        context.sounds.register(
            SoundDefinition(id = NUDGE, resource = "minecraft:block.note_block.bell", group = SoundGroup.INTERFACE),
        )

        context.listen<ChatMessageEvent> { event -> onChat(event.message.clean) }
        context.listen<TabListChangedEvent> { event -> onTabList(event) }

        context.command(name = "sqgardentime", description = "What the Garden's time should be right now") {
            explain()
        }

        context.scheduler.every(context.owner, period = CHECK, initialDelay = CHECK) { followTheRun() }
    }

    private fun onChat(message: String) {
        if (!isOn()) return
        GardenTimeChat.timeSet(message)?.let { time ->
            react(todo.onTimeSet(time))
            remindedAt = null
            return
        }
        PestChat.spawn(message)?.let { spawn ->
            // The widget will show the next cooldown shortly; until then, the last one is spent.
            cooldownEndsAt = null
            nightRemindedAt = null
            react(todo.onSpawn(spawn.amount))
            return
        }
        if (GardenTimeChat.pestKilled(message) != null) react(todo.onKill())
    }

    /**
     * Keeps the pest cooldown in step with the tab list.
     *
     * Only when the Pests widget itself moved: the whole board is re-split for it, which is cheap, but there is
     * no reason to do it for a change somewhere else on the list.
     */
    private fun onTabList(event: TabListChangedEvent) {
        if (TabWidget.PESTS !in event.addedWidgets && TabWidget.PESTS !in event.changedWidgets) return
        val lines = TabListParser.parse(event.snapshot).linesOf(TabWidget.PESTS)
        val now = since.elapsedNow()
        cooldownEndsAt = when (val cooldown = PestCooldown.read(lines) ?: return) {
            is PestCooldown.Waiting -> now + cooldown.remaining
            PestCooldown.Ready -> now
            PestCooldown.MaxPests -> null
        }
    }

    private fun react(advice: Advice) {
        when (advice) {
            Advice.None -> Unit
            Advice.SetDay -> remind(
                "Pests are out — set the time to Day",
                "Before you kill them",
                priority = NotificationPriority.HIGH,
                onScreen = true,
            )
            Advice.SpawnedInDay -> remind(
                "Pests spawned during the Day",
                "Night was missed this time. Set it before the next spawn.",
            )
            Advice.ReadyToKill -> say("Day set — kill all but one.")
            Advice.KilledAtNight -> alarm()
            Advice.LastOneLeft -> say("One pest left — leave it on the plot for the next wave.")
        }
    }

    /**
     * The one mistake this is really about, and it gets every surface there is.
     *
     * A title in the middle of the screen, a burst of notes, a line in chat that stays, and an urgent toast —
     * because the player is looking at a pest, not at a corner of the screen, and one more kill at Night
     * costs the same as the first.
     */
    private fun alarm() {
        showTitle(
            SqText.of("SET GARDEN TO DAY", SqStyle(color = RED, bold = true)),
            SqText.of("You killed a pest at Night!", SqStyle(color = YELLOW)),
        )
        sendChat(
            SqText.join(
                SqText.of("[Sidequest] ", SqStyle(color = RED, bold = true)),
                SqText.of("PEST KILLED AT NIGHT! ", SqStyle(color = RED, bold = true)),
                SqText.of("Set your garden time to Day before the next one.", SqStyle(color = YELLOW)),
            ),
        )
        context.notifications.notify(
            notification(
                category = NotificationCategory.ALERT,
                title = "Pest killed at Night!",
                subtitle = "Set your garden time to Day",
                priority = NotificationPriority.URGENT,
            ),
        )
        if (SidequestSettings.Garden.timeReminderSound) {
            ALARM_PITCHES.forEachIndexed { index, pitch ->
                context.scheduler.after(context.owner, ALARM_GAP * index) {
                    context.sounds.play(SoundRequest(ALARM, pitch = pitch))
                }
            }
        }
    }

    /**
     * Reminds the player while they farm through a Night that has already had its spawn.
     *
     * Once when the run starts, and again every so often while it lasts. A Night set ahead of a spawn is
     * never nagged about, however long the player farms while waiting for it — that is the routine.
     */
    private fun followTheRun() {
        if (!isOn() || !isOnGarden()) {
            streak.reset()
            remindedAt = null
            nightRemindedAt = null
            seenBlocks = blocksBroken()
            return
        }

        val now = since.elapsedNow()
        val broken = blocksBroken()
        repeat((broken - seenBlocks).coerceIn(0, MAX_CATCH_UP).toInt()) { streak.record(now) }
        seenBlocks = broken
        val farming = streak.hasReached(RUN_BLOCKS, now)

        checkSpawnWindow(now, farming)

        if (!farming) {
            remindedAt = null
            return
        }

        val wanted = todo.todo ?: return
        val last = remindedAt
        if (last != null && now - last < REPEAT) return
        remindedAt = now

        remind(
            title = "Set the garden time to ${wanted.displayName}",
            subtitle = "The pests have spawned — Day before you kill them",
            priority = NotificationPriority.HIGH,
            onScreen = true,
        )
    }

    /**
     * Tells the player to set Night as the pest cooldown runs out.
     *
     * Once on entering the window — however the player is spending it — and again every so often while they
     * farm through it, because farming is what spawns the pest. Stops the moment the time is Night, a pest
     * spawns, or the plot is full.
     */
    private fun checkSpawnWindow(now: Duration, farming: Boolean) {
        val endsAt = cooldownEndsAt
        val due = endsAt != null &&
            endsAt - now <= SidequestSettings.Garden.nightLeadSeconds.seconds &&
            todo.time != GardenTime.NIGHT
        if (!due) {
            nightRemindedAt = null
            return
        }

        val last = nightRemindedAt
        if (last != null && (!farming || now - last < NIGHT_REPEAT)) return
        nightRemindedAt = now

        val left = (endsAt!! - now).inWholeSeconds
        remind(
            title = if (todo.time == null) "Is the garden on Night?" else "Set the garden time to Night",
            subtitle = if (left > 0) "Pests can spawn in ${left}s" else "Pests can spawn now",
            priority = NotificationPriority.HIGH,
            onScreen = true,
        )
    }

    private fun explain() {
        val wanted = todo.todo
        val time = todo.time?.displayName ?: "unknown"
        say(
            if (wanted == null) {
                "Time is $time, which is right. Pests out: ${todo.pestsOut}."
            } else {
                "Time is $time — set it to ${wanted.displayName}. Pests out: ${todo.pestsOut}."
            },
        )
    }

    /** A nudge: a toast and a bell, and a title on screen when it needs doing before the next kill. */
    private fun remind(
        title: String,
        subtitle: String,
        priority: NotificationPriority = NotificationPriority.NORMAL,
        onScreen: Boolean = false,
    ) {
        context.notifications.notify(
            notification(category = NotificationCategory.ALERT, title = title, subtitle = subtitle, priority = priority),
        )
        if (onScreen) {
            showTitle(
                SqText.of(title, SqStyle(color = YELLOW, bold = true)),
                SqText.of(subtitle, SqStyle(color = WHITE)),
            )
        }
        if (SidequestSettings.Garden.timeReminderSound) context.sounds.play(SoundRequest(NUDGE))
    }

    private fun say(message: String) {
        context.notifications.notify(
            notification(category = NotificationCategory.ALERT, title = "Garden time", subtitle = message),
        )
    }

    private fun isOn(): Boolean = SidequestSettings.Garden.timeReminder

    /** Your own Garden only: somebody else's time is not yours to set. */
    private fun isOnGarden(): Boolean = context.gameContext.island == Island.GARDEN

    private companion object {
        val ALARM = SqId.sidequest("garden.time_alarm")
        val NUDGE = SqId.sidequest("garden.time_nudge")

        /** Rising, so it reads as an alarm rather than a chime. */
        val ALARM_PITCHES = listOf(0.8f, 1.0f, 1.2f, 0.8f, 1.0f, 1.2f)
        val ALARM_GAP = 120.milliseconds

        const val RED = 0xFF5555
        const val YELLOW = 0xFFFF55
        const val WHITE = 0xFFFFFF

        val CHECK = 250.milliseconds

        /** Long enough into a run that it is a run; the orbital camera's default for the same question. */
        const val RUN_BLOCKS = 20

        /** How long a run goes between reminders about the same thing. */
        val REPEAT = 90.seconds

        /** How often farming through an open spawn window is reminded to set Night. Pests come fast. */
        val NIGHT_REPEAT = 15.seconds

        /** See [OrbitalCameraFeature]: a poll never accounts for more than this many blocks at once. */
        const val MAX_CATCH_UP = 64L
    }
}
