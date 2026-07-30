package dev.th7bo.sidequest.platform.core.cinematic

import dev.th7bo.sidequest.platform.cinematic.Cinematic
import dev.th7bo.sidequest.platform.cinematic.CinematicDirector
import dev.th7bo.sidequest.platform.cinematic.CinematicDisposition
import dev.th7bo.sidequest.platform.cinematic.CinematicFinishedEvent
import dev.th7bo.sidequest.platform.cinematic.CinematicPolicy
import dev.th7bo.sidequest.platform.cinematic.CinematicPriority
import dev.th7bo.sidequest.platform.cinematic.CinematicQueueChangedEvent
import dev.th7bo.sidequest.platform.cinematic.CinematicSettings
import dev.th7bo.sidequest.platform.cinematic.CinematicSink
import dev.th7bo.sidequest.platform.cinematic.CinematicStartedEvent
import dev.th7bo.sidequest.platform.cinematic.QueuedCinematic
import dev.th7bo.sidequest.platform.cinematic.SafetyReading
import dev.th7bo.sidequest.platform.cinematic.UnsafeReason
import dev.th7bo.sidequest.platform.cinematic.CinematicComponent
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.game.GameClient
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.notification.Notification
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.notification.NotificationManager
import dev.th7bo.sidequest.platform.notification.NotificationPriority
import dev.th7bo.sidequest.platform.skyblock.GameContextService

/**
 * Decides whether a cinematic plays, and what happens to it when it does not.
 *
 * The drawing is the easy half. This is the half that matters, and the shape of it is one idea: **a cinematic
 * covers the screen, so refusing to play one is a safety feature.** A full-screen animation during a Kuudra run
 * is the mod getting somebody killed, and a feature that rolled its own would have to get that right by itself.
 *
 * Four things follow from that.
 *
 * **The gate comes before the policy.** Whether now is safe is asked first and answered from the game, not from
 * the cinematic. A `SHOW_ANYWAY` policy can override a bad moment; it cannot override the player being dead,
 * because that is not a judgement call — there is nothing to draw on.
 *
 * **Nothing is dropped silently.** Every submission produces a [CinematicDisposition] carrying a reason, and
 * the last few hundred are kept. Nine causes look identical from the player's chair.
 *
 * **A backlog becomes a recap, not a sequence.** Eleven cinematics back to back on leaving a dungeon is a
 * punishment for having had a good run.
 *
 * **Expiry is checked on release, not on submission.** A cinematic for a drop from two hours ago is noise, and
 * playing it because the queue happened to reach it is worse than never having queued it.
 */
public class DefaultCinematicDirector(
    private val sink: CinematicSink,
    private val context: GameContextService,
    private val client: GameClient,
    /**
     * Where a compacted cinematic goes.
     *
     * The fallback is a notification, which is the reason this is here and not a second copy of the notify
     * policy: `COMPACT` means "the player still learns it happened", and the notification manager is what
     * already knows how to say something without interrupting.
     */
    private val notifications: NotificationManager,
    private val events: EventBus,
    private val log: Logger,
    private val now: () -> Long = System::currentTimeMillis,
    initialSettings: CinematicSettings = CinematicSettings.Default,
) : CinematicDirector {

    override var settings: CinematicSettings = initialSettings
        private set

    /** Waiting, highest priority first and oldest first within a priority. */
    private val waiting = ArrayList<QueuedCinematic>()

    /** What has played, newest first. Bounded. What [replay] draws from. */
    private val played = ArrayDeque<Cinematic>()

    /** Newest first, bounded. */
    private val recent = ArrayDeque<CinematicDisposition>()

    /** Dedupe key to when it was last seen. */
    private val lastSeenByKey = HashMap<String, Long>()

    /** The one playing, so [skip] and the finish callback know what they are about. */
    private var current: Cinematic? = null

    /**
     * Set while a skip is in flight.
     *
     * A flag rather than a second callback, because the sink's finish callback is the same one either way — it
     * has no idea why it ended. Without this, skipping reported a normal finish: the sink called back from
     * inside [skip], `current` was already cleared, and the skip-flavoured event never fired.
     */
    private var skipping = false

    public fun update(settings: CinematicSettings) {
        val wasHolding = this.settings.seriousMode || !this.settings.isEnabled
        this.settings = settings
        // Coming out of serious mode releases what was held, like notifications. Somebody switching the mod
        // back on wants what they missed, and the alternative is discarding an evening of it.
        if (wasHolding && settings.isEnabled && !settings.seriousMode) releaseIfSafe()
    }

    // -- the gate ------------------------------------------------------------

    /**
     * Every reason now is a bad moment, not just the first.
     *
     * All of them, because the debug question is "why did nothing happen" and an answer that names one of three
     * simultaneous reasons invites fixing that one and asking again.
     */
    override fun safety(): SafetyReading {
        val reasons = LinkedHashSet<UnsafeReason>()
        val game = context.context

        if (!settings.isEnabled) reasons.add(UnsafeReason.DISABLED)
        if (!client.isInGame) reasons.add(UnsafeReason.NOT_IN_GAME)

        val vitals = client.vitals
        if (vitals.isDead) reasons.add(UnsafeReason.DEAD)
        if (vitals.isLowHealth) reasons.add(UnsafeReason.LOW_HEALTH)
        if (vitals.isTakingDamage) reasons.add(UnsafeReason.IN_COMBAT)

        // Both halves of the context, because they are different questions: the island is where the player is
        // and the activity is what they are doing, and either alone misses cases the other catches.
        if (game.island.isHazardous) reasons.add(UnsafeReason.HAZARDOUS_ISLAND)
        if (game.activity.activity.isDemanding) reasons.add(UnsafeReason.DEMANDING_ACTIVITY)

        if (client.isScreenOpen) reasons.add(UnsafeReason.SCREEN_OPEN)
        if (sink.isPlaying) reasons.add(UnsafeReason.ALREADY_PLAYING)
        if (settings.seriousMode) reasons.add(UnsafeReason.SERIOUS_MODE)

        return SafetyReading(reasons)
    }

    // -- submission ----------------------------------------------------------

    override fun submit(cinematic: Cinematic): CinematicDisposition {
        val stamped = if (cinematic.timestampMillis == 0L) {
            cinematic.copy(timestampMillis = now())
        } else {
            cinematic
        }

        // Deduplication first, because it is the cheapest check that can end the decision and because a
        // duplicate should not be able to displace something from a full queue.
        stamped.dedupeKey?.let { key ->
            val previous = lastSeenByKey[key]
            lastSeenByKey[key] = now()
            if (lastSeenByKey.size > DEDUPE_KEYS_LIMIT) pruneDedupeKeys()
            if (previous != null && now() - previous < DEDUPE_WINDOW_MILLIS) {
                return dispose(CinematicDisposition.Dropped(stamped, "a duplicate of one seen just now"))
            }
        }

        // The user's answer beats the feature's intent. Somebody who asked for notifications only gets one,
        // whatever the cinematic wanted.
        if (settings.compactOnly) return compact(stamped, "cinematics are set to compact only")

        val safety = safety()
        if (safety.isSafe) return play(stamped)

        // A refusal is not a bad moment — there is nowhere to draw. The only policies that mean anything are
        // the ones that do not involve drawing.
        if (safety.isRefused && stamped.policy == CinematicPolicy.SHOW_ANYWAY) {
            return queueOrCompact(stamped, safety.explain())
        }

        return when (stamped.policy) {
            CinematicPolicy.SHOW_ANYWAY -> play(stamped)
            CinematicPolicy.COMPACT -> compact(stamped, safety.explain())
            CinematicPolicy.QUEUE -> queueOrCompact(stamped, safety.explain())
            CinematicPolicy.MERGE -> merge(stamped, safety.explain())
            CinematicPolicy.LOG_ONLY -> {
                log.info { "Cinematic ${stamped.id}: ${stamped.title}" }
                dispose(CinematicDisposition.Logged(stamped, safety.explain()))
            }
            CinematicPolicy.DISCARD -> dispose(
                CinematicDisposition.Dropped(stamped, "discarded: ${safety.explain()}"),
            )
        }
    }

    private fun play(cinematic: Cinematic): CinematicDisposition {
        // Warned about before playing, so a component that will not appear is knowable from the log rather than
        // from somebody noticing a cinematic looks thin.
        val unsupported = cinematic.components.filterNot { sink.supports(it.kind) }
        if (unsupported.isNotEmpty()) {
            log.debug {
                "${cinematic.id}: the sink cannot draw " + unsupported.joinToString(", ") { it.kind }
            }
        }

        current = cinematic
        val started = sink.play(cinematic) { onFinished(cinematic) }
        if (!started) {
            // A sink that could not start is not a cinematic that played. Falling back rather than reporting
            // success, because reporting success would consume a queue entry for nothing.
            current = null
            log.warn { "The sink refused to play ${cinematic.id}; falling back to a notification" }
            return compact(cinematic, "the sink could not play it")
        }

        played.addFirst(cinematic)
        while (played.size > HISTORY_LIMIT) played.removeLast()
        events.post(CinematicStartedEvent(cinematic), EventSource.DERIVED)
        log.debug { "Playing ${cinematic.id}" }
        return dispose(CinematicDisposition.Played(cinematic))
    }

    /**
     * Holds it, unless the user asked not to hold anything.
     *
     * `QUEUE` with `queueWhileUnsafe` off becomes `COMPACT` rather than a drop: the setting means "do not make
     * me wait", not "do not tell me".
     */
    private fun queueOrCompact(cinematic: Cinematic, reason: String): CinematicDisposition {
        if (!settings.queueWhileUnsafe) return compact(cinematic, reason)
        return enqueue(cinematic, reason, count = 1)
    }

    private fun merge(cinematic: Cinematic, reason: String): CinematicDisposition {
        val key = cinematic.groupingKey
        if (key == null) {
            // A merge policy with nothing to merge on is a queue. Said in the log rather than silently
            // treated as one, because it is almost certainly a mistake in the rule that produced it.
            log.debug { "${cinematic.id} asked to merge but has no grouping key; queueing it instead" }
            return queueOrCompact(cinematic, reason)
        }

        val index = waiting.indexOfFirst { it.cinematic.groupingKey == key }
        if (index < 0) return enqueue(cinematic, reason, count = 1)

        val existing = waiting[index]
        // The count goes up and the *first* one is kept, because the first is the one whose timestamp decides
        // expiry — a group that keeps refreshing its own age would never expire.
        val merged = existing.copy(count = existing.count + 1)
        waiting[index] = merged
        log.debug { "Merged ${cinematic.id} into ${existing.cinematic.id} (${merged.count} events)" }
        return dispose(
            CinematicDisposition.Merged(cinematic, into = existing.cinematic.id, count = merged.count),
        )
    }

    private fun enqueue(cinematic: Cinematic, reason: String, count: Int): CinematicDisposition {
        if (waiting.size >= settings.maxQueue) {
            // The lowest-priority oldest entry goes, and only if this one outranks it. A full queue that drops
            // the *new* arrival would mean a CRITICAL cinematic lost to twenty LOW ones.
            val weakest = waiting.withIndex().minByOrNull { (_, queued) ->
                queued.cinematic.priority.ordinal
            }
            // Strictly better, not "at least as good". A queue full of equals is first-come-first-served: the
            // entry that has been waiting has more claim than the one that just arrived, and letting equals
            // displace each other would mean a steady trickle kept the queue permanently churning.
            if (weakest == null || cinematic.priority.ordinal <= weakest.value.cinematic.priority.ordinal) {
                return dispose(
                    CinematicDisposition.Dropped(cinematic, "the queue is full of equal or better cinematics"),
                )
            }
            waiting.removeAt(weakest.index)
            log.debug { "Dropped ${weakest.value.cinematic.id} from a full queue for ${cinematic.id}" }
            dispose(CinematicDisposition.Dropped(weakest.value.cinematic, "displaced by ${cinematic.id}"))
        }

        val queued = QueuedCinematic(cinematic, count = count, queuedAtMillis = now())
        waiting.add(queued)
        sortQueue()
        events.post(CinematicQueueChangedEvent(waiting.size), EventSource.DERIVED)
        val position = waiting.indexOf(queued)
        log.debug { "Queued ${cinematic.id} at $position: $reason" }
        return dispose(CinematicDisposition.Queued(cinematic, position = position, reason = reason))
    }

    /**
     * Falls back to a notification.
     *
     * The notification manager then applies *its own* busy policy on top of this one, and that is deliberate
     * rather than an oversight: it means a compacted cinematic mid-dungeon is held and released like every other
     * notification, instead of two layers each having their own idea of when interrupting is acceptable. What
     * changes is only the form — a toast later rather than a full cinematic later.
     */
    private fun compact(cinematic: Cinematic, reason: String): CinematicDisposition {
        notifications.notify(
            Notification(
                id = "cinematic." + cinematic.id.value,
                category = NotificationCategory.PROGRESSION,
                priority = cinematic.priority.toNotificationPriority(),
                title = cinematic.title.ifEmpty { cinematic.id.value },
                // The best line the cinematic already has, rather than a second copy of the same text written
                // for a toast. A cinematic without a subtitle simply has none.
                subtitle = cinematic.components
                    .filterIsInstance<CinematicComponent.Subtitle>()
                    .firstOrNull()?.text,
                dedupeKey = cinematic.dedupeKey ?: cinematic.groupingKey,
                groupingKey = cinematic.groupingKey,
            ),
        )
        log.debug { "Compacted ${cinematic.id}: $reason" }
        return dispose(CinematicDisposition.Compacted(cinematic))
    }

    // -- release -------------------------------------------------------------

    /**
     * Plays what is waiting, if it is now safe.
     *
     * Called from a tick rather than from a context event, so a player who leaves one dungeon and immediately
     * enters another does not get a burst between the two. Returns immediately unless something is waiting.
     */
    public fun releaseIfSafe() {
        if (waiting.isEmpty()) return
        // Expiry is checked here and not on submission: a cinematic is worth showing when it is *shown*, and
        // one that sat through a two-hour run is not.
        dropExpired()
        if (waiting.isEmpty()) return
        if (!safety().isSafe) return

        if (settings.recap && waiting.size >= settings.recapThreshold) {
            recap()
            return
        }

        val next = waiting.removeAt(0)
        events.post(CinematicQueueChangedEvent(waiting.size), EventSource.DERIVED)
        play(next.cinematic.withCount(next.count))
    }

    /**
     * Collapses a backlog into one showing.
     *
     * Not a sequence, which is the whole point. Eleven cinematics back to back is not eleven rewards, it is a
     * five-minute cutscene the player cannot leave, and the natural reaction to it is to switch the feature off.
     *
     * The highest-priority one keeps its own title and leads; the rest become lines under it.
     */
    private fun recap() {
        val batch = waiting.toList()
        waiting.clear()
        events.post(CinematicQueueChangedEvent(0), EventSource.DERIVED)

        val lead = batch.first()
        val others = batch.drop(1)
        val total = batch.sumOf { it.count }
        log.info { "Recapping ${batch.size} held cinematic(s), $total event(s)" }

        play(
            lead.cinematic.copy(
                id = SqId.sidequest("cinematic.recap"),
                title = "While you were busy",
                priority = lead.cinematic.priority,
                components = buildList {
                    add(CinematicComponent.Title("While you were busy"))
                    add(CinematicComponent.Subtitle("$total thing(s) happened"))
                    // Each held one gets a line, capped: a recap of forty is a wall of text, and the queue's
                    // own limit is higher than anything readable.
                    for (queued in batch.take(RECAP_LINES)) {
                        val suffix = if (queued.count > 1) " ×${queued.count}" else ""
                        add(
                            CinematicComponent.RewardReveal(
                                label = queued.cinematic.title.ifEmpty { queued.cinematic.id.value } + suffix,
                            ),
                        )
                    }
                    if (batch.size > RECAP_LINES) {
                        add(CinematicComponent.Subtitle("and ${batch.size - RECAP_LINES} more"))
                    }
                    // Whatever sound the lead wanted, so a recap of something momentous still sounds like it.
                    lead.cinematic.components.filterIsInstance<CinematicComponent.Sound>()
                        .firstOrNull()?.let { add(it) }
                },
                // Longer than one, because it has more to say — but bounded rather than the sum, which for a
                // long backlog would be a minute of held attention.
                duration = lead.cinematic.duration * RECAP_DURATION_FACTOR,
                groupingKey = null,
                dedupeKey = null,
            ),
        )
        // Not silently forgotten: the ones that did not get a line still happened, and this is the record.
        for (queued in others.drop(RECAP_LINES)) {
            dispose(CinematicDisposition.Logged(queued.cinematic, "recapped without a line"))
        }
    }

    private fun dropExpired() {
        val expired = waiting.filter { it.hasExpired(now()) }
        if (expired.isEmpty()) return
        waiting.removeAll(expired)
        for (queued in expired) {
            dispose(CinematicDisposition.Dropped(queued.cinematic, "expired while waiting"))
        }
        log.debug { "Dropped ${expired.size} expired cinematic(s)" }
        events.post(CinematicQueueChangedEvent(waiting.size), EventSource.DERIVED)
    }

    private fun onFinished(cinematic: Cinematic) {
        val wasSkipped = skipping
        skipping = false
        current = null
        events.post(CinematicFinishedEvent(cinematic, wasSkipped), EventSource.DERIVED)
        // Not chained straight into the next one: the release runs from the tick, so a queue drains at one per
        // safe moment and a player who is about to be attacked is not committed to four more.
        log.debug { "${cinematic.id} " + if (wasSkipped) "skipped" else "finished" }
    }

    override fun skip() {
        val playing = current ?: return
        if (!playing.isSkippable) {
            log.debug { "${playing.id} is not skippable" }
            return
        }
        skipping = true
        sink.skip()
        // Finished here too, if the sink did not call back. A sink that forgets would leave the director
        // believing something is still playing, and the gate would never open again.
        if (current != null) onFinished(playing)
        skipping = false
    }

    override fun queued(): List<QueuedCinematic> = waiting.toList()

    override fun history(): List<Cinematic> = played.toList()

    override fun trace(): List<CinematicDisposition> = recent.toList()

    override fun replay(cinematicId: SqId): CinematicDisposition? {
        val previous = played.firstOrNull { it.id == cinematicId } ?: return null
        // A deliberate request, so it bypasses deduplication and expiry — both of which exist to filter what
        // arrived by itself. It does not bypass the safety gate: asking to be shown something is not asking to
        // have the screen covered mid-boss.
        return submit(
            previous.copy(
                timestampMillis = now(),
                dedupeKey = null,
                policy = CinematicPolicy.QUEUE,
            ),
        )
    }

    private fun sortQueue() {
        // Priority first, then age. Stable, so equal entries keep the order they arrived in — a queue that
        // reshuffles equals is a queue whose behaviour cannot be tested.
        waiting.sortWith(
            compareByDescending<QueuedCinematic> { it.cinematic.priority.ordinal }
                .thenBy { it.queuedAtMillis },
        )
    }

    private fun dispose(disposition: CinematicDisposition): CinematicDisposition {
        recent.addFirst(disposition)
        while (recent.size > TRACE_LIMIT) recent.removeLast()
        return disposition
    }

    private fun pruneDedupeKeys() {
        val cutoff = now() - DEDUPE_WINDOW_MILLIS
        lastSeenByKey.entries.removeIf { it.value < cutoff }
        if (lastSeenByKey.size > DEDUPE_KEYS_LIMIT) {
            log.warn { "Too many cinematic dedupe keys; something is generating them per event" }
            lastSeenByKey.clear()
        }
    }

    private fun CinematicPriority.toNotificationPriority(): NotificationPriority = when (this) {
        CinematicPriority.LOW -> NotificationPriority.LOW
        CinematicPriority.NORMAL -> NotificationPriority.NORMAL
        CinematicPriority.HIGH -> NotificationPriority.HIGH
        // Not URGENT. Urgent is reserved for something that needs acting on, and a compacted cinematic is by
        // definition something the player was too busy for.
        CinematicPriority.CRITICAL -> NotificationPriority.HIGH
    }

    /**
     * Says how many events a merged cinematic stands for.
     *
     * On the title, because that is the one line a player reads, and the count is the difference between "you
     * got a drop" and "you got eleven".
     */
    private fun Cinematic.withCount(count: Int): Cinematic =
        if (count <= 1) this else copy(title = "$title ×$count")

    private companion object {
        const val HISTORY_LIMIT = 50
        const val TRACE_LIMIT = 200
        const val DEDUPE_KEYS_LIMIT = 200

        /** How close together two cinematics with one key count as the same. */
        const val DEDUPE_WINDOW_MILLIS = 10_000L

        /** How many held cinematics get their own line in a recap. */
        const val RECAP_LINES = 5

        /** How much longer a recap runs than the cinematic it was built from. */
        const val RECAP_DURATION_FACTOR = 2
    }
}
