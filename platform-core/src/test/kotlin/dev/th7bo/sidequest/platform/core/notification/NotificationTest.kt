package dev.th7bo.sidequest.platform.core.notification

import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.core.context.DefaultGameContextService
import dev.th7bo.sidequest.platform.notification.DeliveryMode
import dev.th7bo.sidequest.platform.notification.Notification
import dev.th7bo.sidequest.platform.notification.NotificationAction
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.notification.NotificationPriority
import dev.th7bo.sidequest.platform.notification.NotificationSettings
import dev.th7bo.sidequest.platform.notification.NotificationSink
import dev.th7bo.sidequest.platform.parser.ScoreboardSnapshot
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The notification manager's policy.
 *
 * Tested with no UI at all, which is what [NotificationSink] being an interface buys. The interesting
 * assertions are the ones about *not* showing something: a mod that shows everything is easy, and a mod that
 * knows when to wait is the whole point of this class.
 */
class NotificationTest {

    /** Records what the UI would have been asked to do. */
    private class RecordingSink : NotificationSink {
        val toasts = mutableListOf<Notification>()
        val inbox = mutableListOf<Notification>()
        val dismissed = mutableListOf<String>()

        override fun toast(notification: Notification) { toasts.add(notification) }
        override fun inbox(notification: Notification) { inbox.add(notification) }
        override fun dismiss(notificationId: String) { dismissed.add(notificationId) }
    }

    private lateinit var sink: RecordingSink
    private lateinit var context: DefaultGameContextService
    private lateinit var manager: DefaultNotificationManager
    private var clock = 1_000L

    @BeforeEach
    fun setUp() {
        sink = RecordingSink()
        context = DefaultGameContextService(DefaultEventBus(TestScheduler(), NoopLogger), NoopLogger)
        manager = DefaultNotificationManager(sink, context, NoopLogger, now = { clock })
    }

    private fun drop(id: String = "n1", dedupe: String? = null, group: String? = null) = notification(
        category = NotificationCategory.PROGRESSION,
        title = "Rare drop",
        subtitle = "Hyperion",
        dedupeKey = dedupe,
        groupingKey = group,
        id = id,
    )

    /** Puts the player mid-dungeon, which is what "busy" means. */
    private fun enterDungeon() {
        context.setOnHypixel(true)
        context.onScoreboard(ScoreboardSnapshot("§e§lSKYBLOCK", listOf(" §7⏣ §cThe Catacombs §8(F7)")))
        assertTrue(context.context.isDemanding) { "the fixture should have made the player busy" }
    }

    // -- delivery ----------------------------------------------------------

    @Test
    fun `a notification goes to both the toast and the inbox by default`() {
        assertEquals(DeliveryMode.BOTH, manager.notify(drop()))
        assertEquals(1, sink.toasts.size)
        assertEquals(1, sink.inbox.size)
    }

    @Test
    fun `a switched-off category produces nothing at all`() {
        manager.update(
            NotificationSettings(perCategory = mapOf(NotificationCategory.PROGRESSION to DeliveryMode.DISABLED)),
        )
        assertEquals(DeliveryMode.DISABLED, manager.notify(drop()))
        assertTrue(sink.toasts.isEmpty())
        assertTrue(sink.inbox.isEmpty())
    }

    @Test
    fun `inbox-only keeps it without interrupting`() {
        manager.update(
            NotificationSettings(perCategory = mapOf(NotificationCategory.PROGRESSION to DeliveryMode.INBOX_ONLY)),
        )
        manager.notify(drop())
        assertTrue(sink.toasts.isEmpty())
        assertEquals(1, sink.inbox.size)
    }

    /** Debug is off unless somebody turned it on, and the category's own default says so. */
    @Test
    fun `debug notifications are off by default`() {
        val debug = notification(NotificationCategory.DEBUG, "Layout pass took 40ms")
        assertEquals(DeliveryMode.DISABLED, manager.notify(debug))
    }

    // -- deduplication -----------------------------------------------------

    /**
     * The same event twice must not be two toasts.
     *
     * A duplicated chat line and a replayed realtime message both produce this, and both are normal.
     */
    @Test
    fun `the same key within the window replaces rather than adds`() {
        manager.notify(drop(id = "first", dedupe = "drop:hyperion"))
        clock += 1_000
        manager.notify(drop(id = "second", dedupe = "drop:hyperion"))

        assertEquals(listOf("first"), sink.dismissed) { "the earlier toast should have been taken down" }
        assertEquals(listOf("first", "second"), sink.toasts.map { it.id })
    }

    /** A genuine second drop later is not a duplicate. */
    @Test
    fun `the same key outside the window is a new notification`() {
        manager.notify(drop(id = "first", dedupe = "drop:hyperion"))
        clock += 60_000
        manager.notify(drop(id = "second", dedupe = "drop:hyperion"))

        assertTrue(sink.dismissed.isEmpty())
        assertEquals(2, sink.toasts.size)
    }

    /** Two different friends coming online group together and are not duplicates of each other. */
    @Test
    fun `grouping is not deduplication`() {
        manager.notify(notification(NotificationCategory.SOCIAL, "Alice is online", groupingKey = "online"))
        manager.notify(notification(NotificationCategory.SOCIAL, "Bob is online", groupingKey = "online"))

        assertEquals(2, sink.toasts.size) { "both are real events" }
        assertTrue(sink.dismissed.isEmpty())
    }

    // -- being busy --------------------------------------------------------

    /**
     * Held, not dropped.
     *
     * Something worth telling somebody about, at a moment when telling them would cost them a dungeon run.
     * The thing that happened still happened.
     */
    @Test
    fun `a notification while busy is held until it is safe`() {
        enterDungeon()

        assertEquals(DeliveryMode.QUEUED, manager.notify(drop()))
        assertTrue(sink.toasts.isEmpty())
        assertEquals(1, manager.queued().size)
    }

    @Test
    fun `leaving the dungeon releases what was held`() {
        enterDungeon()
        manager.notify(drop())

        context.onScoreboard(ScoreboardSnapshot("§e§lSKYBLOCK", listOf(" §7⏣ §bDungeon Hub")))
        manager.releaseQueuedIfSafe()

        assertEquals(1, sink.toasts.size)
        assertTrue(manager.queued().isEmpty())
    }

    @Test
    fun `nothing is released while still busy`() {
        enterDungeon()
        manager.notify(drop())
        manager.releaseQueuedIfSafe()

        assertTrue(sink.toasts.isEmpty())
        assertEquals(1, manager.queued().size)
    }

    /**
     * Released as one, not as eleven.
     *
     * Eleven toasts at once is eleven toasts nobody reads, so a group becomes one carrying the count.
     */
    @Test
    fun `a burst released at once is grouped`() {
        enterDungeon()
        repeat(4) { index -> manager.notify(drop(id = "n$index", group = "drops")) }

        context.onScoreboard(ScoreboardSnapshot("§e§lSKYBLOCK", listOf(" §7⏣ §bHub")))
        manager.releaseQueuedIfSafe()

        assertEquals(1, sink.toasts.size)
        assertTrue(sink.toasts.single().subtitle!!.contains("and 3 more")) { sink.toasts.single().subtitle!! }
    }

    /** Somebody who would rather see a small toast than wait can have one. */
    @Test
    fun `compact delivery is used when queueing is turned off`() {
        manager.update(NotificationSettings(queueWhileBusy = false))
        enterDungeon()

        assertEquals(DeliveryMode.COMPACT, manager.notify(drop()))
        assertEquals(1, sink.toasts.size)
        assertTrue(manager.queued().isEmpty())
    }

    /**
     * Urgent gets through regardless.
     *
     * A revoked device is worth interrupting a dungeon for. Nothing else in the mod is, which is why the
     * priority exists and why it is used sparingly.
     */
    @Test
    fun `an urgent notification interrupts even a dungeon`() {
        enterDungeon()
        val urgent = notification(
            NotificationCategory.ALERT,
            "This device was revoked",
            priority = NotificationPriority.URGENT,
        )

        assertEquals(DeliveryMode.BOTH, manager.notify(urgent))
        assertEquals(1, sink.toasts.size)
    }

    // -- serious mode ------------------------------------------------------

    @Test
    fun `serious mode holds everything below urgent`() {
        manager.update(NotificationSettings(seriousMode = true))

        assertEquals(DeliveryMode.QUEUED, manager.notify(drop()))
        assertEquals(
            DeliveryMode.BOTH,
            manager.notify(
                notification(NotificationCategory.ALERT, "Revoked", priority = NotificationPriority.URGENT),
            ),
        )
        assertEquals(1, sink.toasts.size)
    }

    /** Somebody turning the mod back on wants to know what they missed. */
    @Test
    fun `leaving serious mode releases what it held`() {
        manager.update(NotificationSettings(seriousMode = true))
        manager.notify(drop())
        assertTrue(sink.toasts.isEmpty())

        manager.update(NotificationSettings(seriousMode = false))

        assertEquals(1, sink.toasts.size)
        assertTrue(manager.queued().isEmpty())
    }

    // -- actions and history ------------------------------------------------

    @Test
    fun `choosing an action runs it and dismisses the notification`() {
        var chosen = false
        manager.notify(
            drop().copy(
                actions = listOf(NotificationAction("accept", "Accept") { chosen = true }),
            ),
        )

        manager.choose("n1", "accept")

        assertTrue(chosen)
        assertEquals(listOf("n1"), sink.dismissed)
    }

    /** Same isolation as everywhere else: one badly-behaved action must not strand a toast on screen. */
    @Test
    fun `an action that throws still dismisses the notification`() {
        manager.notify(
            drop().copy(actions = listOf(NotificationAction("boom", "Boom") { error("badly behaved") })),
        )

        manager.choose("n1", "boom")

        assertEquals(listOf("n1"), sink.dismissed)
    }

    @Test
    fun `an unknown action is ignored`() {
        manager.notify(drop())
        manager.choose("n1", "nope")
        assertTrue(sink.dismissed.isEmpty())
    }

    @Test
    fun `history is newest first and bounded`() {
        repeat(250) { index -> manager.notify(drop(id = "n$index")) }

        val history = manager.history()
        assertEquals(200, history.size)
        assertEquals("n249", history.first().id)
    }

    @Test
    fun `a notification is stamped with the time it happened`() {
        clock = 5_000
        manager.notify(drop())
        assertEquals(5_000, sink.toasts.single().timestampMillis)
    }

    @Test
    fun `a category's own default duration is used when none is given`() {
        manager.notify(drop())
        assertEquals(
            NotificationCategory.PROGRESSION.defaultDurationMillis,
            sink.toasts.single().effectiveDurationMillis(),
        )
    }

    /** A held queue that grows for an hour is not a queue. */
    @Test
    fun `the held queue is bounded, oldest dropped`() {
        manager.update(NotificationSettings(seriousMode = true))
        repeat(60) { index -> manager.notify(drop(id = "n$index")) }

        val queued = manager.queued()
        assertEquals(50, queued.size)
        assertFalse(queued.any { it.id == "n0" }) { "the oldest should have gone" }
    }

    /**
     * An action stays runnable after the toast has gone.
     *
     * The whole reason actions are offered in chat: a toast lasts five seconds, and somebody who was looking
     * at their inventory when it appeared should still be able to act on it. If `choose` only worked while the
     * toast was on screen, the chat line would be a button that does nothing most of the time.
     */
    @Test
    fun `an action still runs after the notification has timed out`() {
        var chosen = false
        manager.notify(
            drop().copy(actions = listOf(NotificationAction("accept", "Accept") { chosen = true })),
        )

        // The UI takes the toast down on its own; nothing tells the manager.
        clock += 60_000

        manager.choose("n1", "accept")
        assertTrue(chosen)
    }

    /**
     * The runnable set is bounded.
     *
     * A toast that times out is dismissed by the UI and never tells the manager, so nothing removed it. This
     * grew for every notification ever shown over a session before it was bounded.
     */
    @Test
    fun `only recent notifications keep their actions runnable`() {
        var oldRan = false
        manager.notify(
            drop(id = "old").copy(actions = listOf(NotificationAction("go", "Go") { oldRan = true })),
        )
        repeat(80) { index -> manager.notify(drop(id = "filler$index")) }

        manager.choose("old", "go")

        assertFalse(oldRan) { "an action from eighty notifications ago should have been let go" }
        assertTrue(manager.history().size <= 200)
    }
}
