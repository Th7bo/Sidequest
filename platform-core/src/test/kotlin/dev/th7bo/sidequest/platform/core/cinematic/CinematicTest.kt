package dev.th7bo.sidequest.platform.core.cinematic

import dev.th7bo.sidequest.platform.cinematic.Cinematic
import dev.th7bo.sidequest.platform.cinematic.CinematicComponent
import dev.th7bo.sidequest.platform.cinematic.CinematicDisposition
import dev.th7bo.sidequest.platform.cinematic.CinematicFinishedEvent
import dev.th7bo.sidequest.platform.cinematic.CinematicPolicy
import dev.th7bo.sidequest.platform.cinematic.CinematicPriority
import dev.th7bo.sidequest.platform.cinematic.CinematicQueueChangedEvent
import dev.th7bo.sidequest.platform.cinematic.CinematicSettings
import dev.th7bo.sidequest.platform.cinematic.CinematicSink
import dev.th7bo.sidequest.platform.cinematic.CinematicStartedEvent
import dev.th7bo.sidequest.platform.cinematic.UnsafeReason
import dev.th7bo.sidequest.platform.core.context.DefaultGameContextService
import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.core.notification.DefaultNotificationManager
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.game.PlayerVitals
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.notification.Notification
import dev.th7bo.sidequest.platform.notification.NotificationSink
import dev.th7bo.sidequest.platform.parser.ScoreboardSnapshot
import dev.th7bo.sidequest.platform.testkit.FakeGameClient
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The cinematic director.
 *
 * The assertions worth reading are the ones where nothing plays. Drawing an animation is the easy half; the
 * half that matters is refusing to draw one, because a cinematic covers the screen and covering the screen
 * mid-Kuudra is the mod getting somebody killed. Most of what follows is about that refusal and about the
 * reason given for it.
 */
class CinematicTest {

    /** Records what it was asked to draw, and only finishes when told to. */
    private class RecordingSink(
        private val supported: Set<String> = ALL_KINDS,
        var refuseToPlay: Boolean = false,
    ) : CinematicSink {

        val playedIds = mutableListOf<SqId>()
        val played = mutableListOf<Cinematic>()
        var skips = 0

        private var onFinished: (() -> Unit)? = null

        override var isPlaying: Boolean = false
            private set

        override fun supports(kind: String): Boolean = kind in supported

        override fun play(cinematic: Cinematic, onFinished: () -> Unit): Boolean {
            if (refuseToPlay) return false
            playedIds.add(cinematic.id)
            played.add(cinematic)
            isPlaying = true
            this.onFinished = onFinished
            return true
        }

        override fun skip() {
            skips++
            finish()
        }

        /** Ends the current playback, as a real sink would when its duration elapses. */
        fun finish() {
            isPlaying = false
            onFinished?.invoke()
            onFinished = null
        }

        companion object {
            val ALL_KINDS = setOf(
                "letterbox", "background", "title", "subtitle", "number",
                "progress", "item", "player_head", "reward", "sound",
            )
        }
    }

    private lateinit var events: DefaultEventBus
    private lateinit var context: DefaultGameContextService
    private lateinit var client: FakeGameClient
    private lateinit var sink: RecordingSink
    private lateinit var director: DefaultCinematicDirector

    private val toasts = mutableListOf<Notification>()
    private lateinit var notifications: DefaultNotificationManager
    private var clock = 100_000L

    /**
     * Everything the player was told, shown or held.
     *
     * Held counts. The notification manager applies its own busy policy on top of the director's, so a
     * compacted cinematic mid-dungeon is a notification waiting for a safe moment rather than a toast — which
     * is the intended interaction, and asserting on the toast sink alone would call it a failure.
     */
    private fun notified(): List<Notification> = notifications.history() + notifications.queued()

    @BeforeEach
    fun setUp() {
        events = DefaultEventBus(TestScheduler(), NoopLogger)
        context = DefaultGameContextService(events, NoopLogger)
        client = FakeGameClient()
        sink = RecordingSink()
        toasts.clear()
        director = build()
    }

    private fun build(settings: CinematicSettings = CinematicSettings.Default): DefaultCinematicDirector {
        notifications = DefaultNotificationManager(
            sink = object : NotificationSink {
                override fun toast(notification: Notification) {
                    toasts.add(notification)
                }
                override fun inbox(notification: Notification) {}
                override fun dismiss(notificationId: String) {}
            },
            context = context,
            log = NoopLogger,
            now = { clock },
        )
        return DefaultCinematicDirector(
        sink = sink,
        context = context,
        client = client,
        notifications = notifications,
        events = events,
        log = NoopLogger,
        now = { clock },
        initialSettings = settings,
        )
    }

    private fun cinematic(
        id: String = "test.drop",
        title: String = "Hyperion",
        policy: CinematicPolicy = CinematicPolicy.QUEUE,
        priority: CinematicPriority = CinematicPriority.NORMAL,
        groupingKey: String? = null,
        dedupeKey: String? = null,
    ) = Cinematic(
        id = SqId.sidequest(id),
        title = title,
        policy = policy,
        priority = priority,
        groupingKey = groupingKey,
        dedupeKey = dedupeKey,
        components = listOf(
            CinematicComponent.Title(title),
            CinematicComponent.Subtitle("from a chest"),
        ),
    )

    /** Puts the player somewhere that counts as mid-run. */
    private fun inDungeon() {
        context.setOnHypixel(true)
        context.onScoreboard(ScoreboardSnapshot("§e§lSKYBLOCK", listOf(" §7⏣ §cThe Catacombs §8(F7)")))
    }

    private fun onIsland() {
        context.setOnHypixel(true)
        context.onScoreboard(ScoreboardSnapshot("§e§lSKYBLOCK", listOf(" §7⏣ §bVillage")))
    }

    // -- the gate ------------------------------------------------------------

    @Test
    fun `a safe moment plays immediately`() {
        onIsland()

        val disposition = director.submit(cinematic())

        assertInstanceOf(CinematicDisposition.Played::class.java, disposition)
        assertEquals(listOf(SqId.sidequest("test.drop")), sink.playedIds)
    }

    @Test
    fun `a demanding activity holds it`() {
        inDungeon()

        val disposition = director.submit(cinematic())

        val queued = assertInstanceOf(CinematicDisposition.Queued::class.java, disposition)
        assertTrue(sink.playedIds.isEmpty(), "nothing may be drawn during a run")
        assertTrue(queued.reason.contains("mid-run"), "the reason was '${queued.reason}'")
    }

    /**
     * Every reason, not the first one found.
     *
     * The debugging question is "why did nothing happen", and an answer naming one of three simultaneous causes
     * invites fixing that one and asking again.
     */
    @Test
    fun `the safety reading names every reason at once`() {
        inDungeon()
        client.vitals = PlayerVitals(healthFraction = 0.1f, isTakingDamage = true)
        client.isScreenOpen = true

        val safety = director.safety()

        assertFalse(safety.isSafe)
        assertEquals(
            setOf(
                UnsafeReason.LOW_HEALTH,
                UnsafeReason.IN_COMBAT,
                UnsafeReason.DEMANDING_ACTIVITY,
                UnsafeReason.SCREEN_OPEN,
            ),
            safety.reasons,
        )
    }

    @Test
    fun `low health holds it even somewhere harmless`() {
        onIsland()
        client.vitals = PlayerVitals(healthFraction = 0.1f)

        director.submit(cinematic())

        assertTrue(sink.playedIds.isEmpty(), "a full-screen animation on two hearts is a death")
    }

    @Test
    fun `an open screen holds it`() {
        onIsland()
        client.isScreenOpen = true

        director.submit(cinematic())

        assertTrue(sink.playedIds.isEmpty())
    }

    @Test
    fun `one already playing holds the next`() {
        onIsland()
        director.submit(cinematic("first"))

        val second = director.submit(cinematic("second"))

        assertEquals(listOf(SqId.sidequest("first")), sink.playedIds)
        assertInstanceOf(CinematicDisposition.Queued::class.java, second)
    }

    /**
     * `SHOW_ANYWAY` overrides a bad moment and not the absence of anywhere to draw.
     *
     * The distinction is the reason `UnsafeReason.isAbsolute` exists. Being mid-dungeon is a judgement call a
     * feature may overrule; being dead is not a judgement at all.
     */
    @Test
    fun `show anyway beats a bad moment but not death`() {
        inDungeon()
        assertInstanceOf(
            CinematicDisposition.Played::class.java,
            director.submit(cinematic("brave", policy = CinematicPolicy.SHOW_ANYWAY)),
        )
        sink.finish()

        client.vitals = PlayerVitals(isDead = true)
        val whenDead = director.submit(cinematic("reckless", policy = CinematicPolicy.SHOW_ANYWAY))

        assertInstanceOf(CinematicDisposition.Queued::class.java, whenDead)
        assertEquals(listOf(SqId.sidequest("brave")), sink.playedIds)
    }

    @Test
    fun `not being in a world refuses it`() {
        client.isInGame = false

        director.submit(cinematic(policy = CinematicPolicy.SHOW_ANYWAY))

        assertTrue(sink.playedIds.isEmpty())
    }

    // -- policies ------------------------------------------------------------

    @Test
    fun `compact falls back to a notification carrying the subtitle`() {
        inDungeon()

        val disposition = director.submit(cinematic(policy = CinematicPolicy.COMPACT))

        assertInstanceOf(CinematicDisposition.Compacted::class.java, disposition)
        assertEquals(1, notified().size)
        assertEquals("Hyperion", notified().first().title)
        assertEquals("from a chest", notified().first().subtitle, "the cinematic's own line, not a second copy")
    }

    /**
     * The two busy policies compose rather than fighting.
     *
     * Low health is unsafe for a cinematic and not for a toast — the notification manager asks the *context*
     * whether interrupting costs anything, and a harmless island on low health costs nothing. So a compacted
     * cinematic here is shown at once, while the same one mid-dungeon is held. Both are right, and this is the
     * test that says the seam between them was thought about.
     */
    @Test
    fun `compact on low health shows at once, unlike compact mid-run`() {
        onIsland()
        client.vitals = PlayerVitals(healthFraction = 0.1f)

        director.submit(cinematic(policy = CinematicPolicy.COMPACT))

        assertEquals(1, toasts.size, "nothing about a toast is unsafe on low health")
        assertTrue(notifications.queued().isEmpty())
    }

    @Test
    fun `log only writes nothing to the screen`() {
        inDungeon()

        val disposition = director.submit(cinematic(policy = CinematicPolicy.LOG_ONLY))

        assertInstanceOf(CinematicDisposition.Logged::class.java, disposition)
        assertTrue(sink.playedIds.isEmpty())
        assertTrue(toasts.isEmpty())
        assertTrue(director.queued().isEmpty())
    }

    @Test
    fun `discard says so rather than queueing quietly`() {
        inDungeon()

        val disposition = director.submit(cinematic(policy = CinematicPolicy.DISCARD))

        val dropped = assertInstanceOf(CinematicDisposition.Dropped::class.java, disposition)
        assertTrue(dropped.reason.contains("discarded"), "the reason was '${dropped.reason}'")
        assertTrue(director.queued().isEmpty())
    }

    /** The user's answer beats the feature's intent. */
    @Test
    fun `compact only downgrades even a safe moment`() {
        onIsland()
        director = build(CinematicSettings.Default.copy(compactOnly = true))

        val disposition = director.submit(cinematic(policy = CinematicPolicy.SHOW_ANYWAY))

        assertInstanceOf(CinematicDisposition.Compacted::class.java, disposition)
        assertTrue(sink.playedIds.isEmpty())
    }

    /** "Do not make me wait" is not "do not tell me". */
    @Test
    fun `queueing off becomes a notification, not a drop`() {
        inDungeon()
        director = build(CinematicSettings.Default.copy(queueWhileUnsafe = false))

        val disposition = director.submit(cinematic(policy = CinematicPolicy.QUEUE))

        assertInstanceOf(CinematicDisposition.Compacted::class.java, disposition)
        assertEquals(1, notified().size)
    }

    // -- merging and deduplication -------------------------------------------

    @Test
    fun `merging collapses a group and counts it`() {
        inDungeon()

        director.submit(cinematic("drop.a", policy = CinematicPolicy.MERGE, groupingKey = "run"))
        val second = director.submit(cinematic("drop.b", policy = CinematicPolicy.MERGE, groupingKey = "run"))
        val third = director.submit(cinematic("drop.c", policy = CinematicPolicy.MERGE, groupingKey = "run"))

        assertInstanceOf(CinematicDisposition.Merged::class.java, second)
        assertEquals(3, (third as CinematicDisposition.Merged).count)
        assertEquals(1, director.queued().size, "three events, one queued cinematic")
        assertEquals(3, director.queued().first().count)
    }

    /**
     * A merged group keeps the first one's age.
     *
     * Otherwise a group that keeps being added to never expires, which is the opposite of what expiry is for:
     * the whole point is that the *first* event is what the player has been waiting to see.
     */
    @Test
    fun `a merged group expires from its first event`() {
        inDungeon()
        director.submit(cinematic("drop.a", policy = CinematicPolicy.MERGE, groupingKey = "run"))

        clock += 9.minutes.inWholeMilliseconds
        director.submit(cinematic("drop.b", policy = CinematicPolicy.MERGE, groupingKey = "run"))
        clock += 2.minutes.inWholeMilliseconds

        onIsland()
        director.releaseIfSafe()

        assertTrue(sink.playedIds.isEmpty(), "the group aged from its first event, so it expired")
    }

    @Test
    fun `merging without a grouping key queues instead`() {
        inDungeon()

        val disposition = director.submit(cinematic(policy = CinematicPolicy.MERGE, groupingKey = null))

        assertInstanceOf(CinematicDisposition.Queued::class.java, disposition)
    }

    @Test
    fun `the same event twice is dropped once`() {
        onIsland()
        director.submit(cinematic("drop", dedupeKey = "hyperion-1"))
        sink.finish()

        val again = director.submit(cinematic("drop", dedupeKey = "hyperion-1"))

        val dropped = assertInstanceOf(CinematicDisposition.Dropped::class.java, again)
        assertTrue(dropped.reason.contains("duplicate"))
        assertEquals(1, sink.playedIds.size)
    }

    @Test
    fun `the same event later is not a duplicate`() {
        onIsland()
        director.submit(cinematic("drop", dedupeKey = "hyperion-1"))
        sink.finish()
        clock += 30.seconds.inWholeMilliseconds

        director.submit(cinematic("drop", dedupeKey = "hyperion-1"))

        assertEquals(2, sink.playedIds.size, "a genuine second drop still counts")
    }

    // -- the queue -----------------------------------------------------------

    @Test
    fun `the queue is ordered by priority then age`() {
        inDungeon()
        director.submit(cinematic("low", priority = CinematicPriority.LOW))
        clock += 1000
        director.submit(cinematic("critical", priority = CinematicPriority.CRITICAL))
        clock += 1000
        director.submit(cinematic("normal.first", priority = CinematicPriority.NORMAL))
        clock += 1000
        director.submit(cinematic("normal.second", priority = CinematicPriority.NORMAL))

        assertEquals(
            listOf("sidequest:critical", "sidequest:normal.first", "sidequest:normal.second", "sidequest:low"),
            director.queued().map { it.cinematic.id.value },
        )
    }

    /**
     * A full queue drops its weakest entry, not the arrival.
     *
     * Dropping the arrival would mean a once-in-a-lifetime drop lost to twenty routine ones that happened to
     * come first, which is exactly backwards.
     */
    @Test
    fun `a better cinematic displaces the weakest in a full queue`() {
        inDungeon()
        director = build(CinematicSettings.Default.copy(maxQueue = 2, recap = false))
        director.submit(cinematic("low.a", priority = CinematicPriority.LOW))
        clock += 1000
        director.submit(cinematic("low.b", priority = CinematicPriority.LOW))

        val disposition = director.submit(cinematic("critical", priority = CinematicPriority.CRITICAL))

        assertInstanceOf(CinematicDisposition.Queued::class.java, disposition)
        assertEquals(
            listOf("sidequest:critical", "sidequest:low.b"),
            director.queued().map { it.cinematic.id.value },
            "the oldest of the weakest went",
        )
    }

    @Test
    fun `an equal cinematic does not displace one already waiting`() {
        inDungeon()
        director = build(CinematicSettings.Default.copy(maxQueue = 1, recap = false))
        director.submit(cinematic("first"))

        val disposition = director.submit(cinematic("second"))

        val dropped = assertInstanceOf(CinematicDisposition.Dropped::class.java, disposition)
        assertTrue(dropped.reason.contains("full"))
        assertEquals(listOf("sidequest:first"), director.queued().map { it.cinematic.id.value })
    }

    @Test
    fun `a stale cinematic expires rather than playing late`() {
        inDungeon()
        director.submit(cinematic("drop"))

        clock += 11.minutes.inWholeMilliseconds
        onIsland()
        director.releaseIfSafe()

        assertTrue(sink.playedIds.isEmpty())
        assertTrue(director.queued().isEmpty())
        assertTrue(
            director.trace().any {
                it is CinematicDisposition.Dropped && it.reason.contains("expired")
            },
            "the expiry has to be in the trace, or nobody can tell it from a bug",
        )
    }

    @Test
    fun `releasing does nothing while it is still unsafe`() {
        inDungeon()
        director.submit(cinematic())

        director.releaseIfSafe()

        assertTrue(sink.playedIds.isEmpty())
        assertEquals(1, director.queued().size)
    }

    @Test
    fun `releasing plays one at a time`() {
        inDungeon()
        director = build(CinematicSettings.Default.copy(recap = false))
        director.submit(cinematic("a"))
        director.submit(cinematic("b"))
        onIsland()

        director.releaseIfSafe()

        assertEquals(listOf(SqId.sidequest("a")), sink.playedIds)
        assertEquals(1, director.queued().size, "the second waits for the next safe tick, not for this one")
    }

    @Test
    fun `a merged cinematic says how many it stands for`() {
        inDungeon()
        director = build(CinematicSettings.Default.copy(recap = false))
        repeat(3) { director.submit(cinematic("drop", policy = CinematicPolicy.MERGE, groupingKey = "run")) }
        onIsland()

        director.releaseIfSafe()

        assertEquals("Hyperion ×3", sink.played.single().title)
    }

    // -- recap ---------------------------------------------------------------

    /**
     * A backlog becomes one showing, not a sequence.
     *
     * Eleven cinematics back to back is not eleven rewards — it is a cutscene the player cannot leave, and the
     * natural reaction to it is to switch the whole feature off.
     */
    @Test
    fun `a backlog is recapped instead of replayed in sequence`() {
        inDungeon()
        repeat(4) { director.submit(cinematic("drop.$it", title = "Drop $it")) }
        onIsland()

        director.releaseIfSafe()

        assertEquals(1, sink.playedIds.size, "one showing, not four")
        val recap = sink.played.single()
        assertEquals(SqId.sidequest("cinematic.recap"), recap.id)
        assertTrue(director.queued().isEmpty())
        // Each held one gets a line, so the player learns what they were.
        val labels = recap.components.filterIsInstance<CinematicComponent.RewardReveal>().map { it.label }
        assertEquals(listOf("Drop 0", "Drop 1", "Drop 2", "Drop 3"), labels)
    }

    @Test
    fun `a recap counts merged events rather than cinematics`() {
        inDungeon()
        repeat(3) { director.submit(cinematic("drop", policy = CinematicPolicy.MERGE, groupingKey = "run")) }
        director.submit(cinematic("other.a", title = "Other A"))
        director.submit(cinematic("other.b", title = "Other B"))
        onIsland()

        director.releaseIfSafe()

        val subtitles = sink.played.single().components
            .filterIsInstance<CinematicComponent.Subtitle>().map { it.text }
        assertTrue(subtitles.any { it.contains("5 thing") }, "subtitles were $subtitles")
    }

    @Test
    fun `below the threshold there is no recap`() {
        inDungeon()
        director = build(CinematicSettings.Default.copy(recapThreshold = 3))
        director.submit(cinematic("a", title = "A"))
        director.submit(cinematic("b", title = "B"))
        onIsland()

        director.releaseIfSafe()

        assertEquals(listOf(SqId.sidequest("a")), sink.playedIds, "two is a queue, not a backlog")
    }

    // -- skipping and replay -------------------------------------------------

    @Test
    fun `skipping ends it and reports that it was skipped`() {
        onIsland()
        val finished = mutableListOf<Boolean>()
        events.on<CinematicFinishedEvent>(OwnerId.PLATFORM) { finished.add(it.wasSkipped) }
        director.submit(cinematic())

        director.skip()

        assertEquals(1, sink.skips)
        assertEquals(listOf(true), finished)
        assertFalse(sink.isPlaying)
    }

    @Test
    fun `an unskippable cinematic is not skipped`() {
        onIsland()
        director.submit(cinematic().copy(isSkippable = false))

        director.skip()

        assertEquals(0, sink.skips)
        assertTrue(sink.isPlaying)
    }

    @Test
    fun `skipping with nothing playing does nothing`() {
        director.skip()

        assertEquals(0, sink.skips)
    }

    @Test
    fun `replay plays a past one again, ignoring its dedupe key`() {
        onIsland()
        director.submit(cinematic("drop", dedupeKey = "once"))
        sink.finish()

        val disposition = director.replay(SqId.sidequest("drop"))

        assertInstanceOf(CinematicDisposition.Played::class.java, disposition)
        assertEquals(2, sink.playedIds.size)
    }

    /** Asking to be shown something is not asking for the screen to be covered mid-boss. */
    @Test
    fun `replay still respects the gate`() {
        onIsland()
        director.submit(cinematic("drop"))
        sink.finish()
        inDungeon()

        val disposition = director.replay(SqId.sidequest("drop"))

        assertInstanceOf(CinematicDisposition.Queued::class.java, disposition)
    }

    @Test
    fun `replaying something that never played returns nothing`() {
        assertNull(director.replay(SqId.sidequest("never")))
    }

    // -- the sink ------------------------------------------------------------

    /**
     * A sink that could not start is not a cinematic that played.
     *
     * Reporting success would consume a queue entry for nothing, and the player would be told about a drop they
     * never saw. Falling back to a notification is the honest outcome.
     */
    @Test
    fun `a sink that refuses falls back to a notification`() {
        onIsland()
        sink.refuseToPlay = true

        val disposition = director.submit(cinematic())

        assertInstanceOf(CinematicDisposition.Compacted::class.java, disposition)
        assertEquals(1, notified().size)
        // And the gate is not left believing something is playing, which would close it forever.
        assertTrue(director.safety().isSafe)
    }

    /** A component nothing can draw is skipped, and the rest still plays. */
    @Test
    fun `an unsupported component does not stop the cinematic`() {
        onIsland()
        sink = RecordingSink(supported = setOf("title"))
        director = build()

        val disposition = director.submit(
            cinematic().copy(
                components = listOf(
                    CinematicComponent.Title("Hyperion"),
                    CinematicComponent.Particles(SqId.sidequest("sparkle")),
                    CinematicComponent.Shader(SqId.sidequest("bloom")),
                ),
            ),
        )

        assertInstanceOf(CinematicDisposition.Played::class.java, disposition)
    }

    // -- events --------------------------------------------------------------

    @Test
    fun `starting and finishing are posted`() {
        onIsland()
        val seen = mutableListOf<String>()
        events.on<CinematicStartedEvent>(OwnerId.PLATFORM) { seen.add("started") }
        events.on<CinematicFinishedEvent>(OwnerId.PLATFORM) { seen.add("finished") }

        director.submit(cinematic())
        sink.finish()

        assertEquals(listOf("started", "finished"), seen)
    }

    /** The queue indicator is driven by an event, so a HUD showing one does not have to poll. */
    @Test
    fun `the queue length is announced as it changes`() {
        inDungeon()
        val counts = mutableListOf<Int>()
        events.on<CinematicQueueChangedEvent>(OwnerId.PLATFORM) { counts.add(it.waiting) }

        director.submit(cinematic("a"))
        director.submit(cinematic("b"))
        onIsland()
        director.releaseIfSafe()

        // Two on the way in, and one on the way out — two is below the recap threshold, so the release plays
        // a single cinematic and leaves the other waiting.
        assertEquals(listOf(1, 2, 1), counts)
    }

    @Test
    fun `leaving serious mode releases what was held`() {
        onIsland()
        director = build(CinematicSettings.Default.copy(seriousMode = true, recap = false))
        director.submit(cinematic("held"))
        assertTrue(sink.playedIds.isEmpty())

        director.update(CinematicSettings.Default.copy(seriousMode = false, recap = false))

        assertEquals(listOf(SqId.sidequest("held")), sink.playedIds)
    }

    @Test
    fun `switched off, nothing is drawn and the reason says so`() {
        onIsland()
        director = build(CinematicSettings.Default.copy(isEnabled = false))

        director.submit(cinematic(policy = CinematicPolicy.SHOW_ANYWAY))

        assertTrue(sink.playedIds.isEmpty())
        assertTrue(director.safety().reasons.contains(UnsafeReason.DISABLED))
    }
}
