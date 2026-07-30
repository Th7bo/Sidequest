package dev.th7bo.sidequest.platform.core.notification

import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.notification.DeliveryMode
import dev.th7bo.sidequest.platform.notification.Notification
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.notification.NotificationManager
import dev.th7bo.sidequest.platform.notification.NotificationPriority
import dev.th7bo.sidequest.platform.notification.NotificationSettings
import dev.th7bo.sidequest.platform.notification.NotificationSink
import dev.th7bo.sidequest.platform.skyblock.GameContextService

/**
 * Decides what happens to a notification.
 *
 * Four rules, applied in order, and the order is the design. Each one can end the decision, and putting them
 * in a different order produces a mod that is either silent when it matters or noisy when it must not be.
 *
 * 1. **Is this category switched on at all.** A user's answer beats everything below.
 * 2. **Is this a duplicate.** The same event arriving twice — a replayed realtime message, a duplicated chat
 *    line — replaces rather than adds. Cheapest check that can end the decision, so it comes early.
 * 3. **Is the player busy.** Mid-dungeon, mid-Kuudra, mid-slayer. Held or compacted, never dropped: the
 *    thing that happened still happened.
 * 4. **Where does it go.** Toast, inbox, or both.
 *
 * [NotificationPriority.URGENT] short-circuits the third rule, and only the third. A revoked device is worth
 * interrupting a dungeon for; nothing else here is.
 */
public class DefaultNotificationManager(
    private val sink: NotificationSink,
    private val context: GameContextService,
    private val log: Logger,
    private val now: () -> Long = System::currentTimeMillis,
    initialSettings: NotificationSettings = NotificationSettings.Default,
) : NotificationManager {

    override var settings: NotificationSettings = initialSettings
        private set

    /** Newest first, bounded. An unbounded history in a long session is a leak that looks like a feature. */
    private val shown = ArrayDeque<Notification>()

    /** Waiting for a safe moment, oldest first — they are released in the order they happened. */
    private val waiting = ArrayDeque<Notification>()

    /** Dedupe key to when it was last shown. Pruned as it is read; nothing sweeps it. */
    private val lastSeenByKey = HashMap<String, Long>()

    /**
     * Notifications whose actions can still be run, oldest first.
     *
     * Bounded, and that is a fix rather than a precaution: a toast that times out is dismissed by the UI and
     * never tells the manager, so nothing removed it. Over a long session this grew for every notification
     * ever shown. The bound is generous enough that a chat action offered a few minutes ago still works.
     */
    private val live = LinkedHashMap<String, Notification>()

    public fun update(settings: NotificationSettings) {
        val wasSerious = this.settings.seriousMode
        this.settings = settings
        // Leaving serious mode releases what was held. Somebody who turns the mod back on wants to know what
        // they missed, and the alternative is silently discarding an hour of it.
        if (wasSerious && !settings.seriousMode) releaseQueued()
    }

    override fun notify(notification: Notification): DeliveryMode {
        val stamped = if (notification.timestampMillis == 0L) {
            notification.copy(timestampMillis = now())
        } else {
            notification
        }

        // 1. What the user asked for.
        val configured = settings.deliveryOf(stamped.category)
        if (configured == DeliveryMode.DISABLED) {
            // Logged, because "the notification did not appear" has five possible causes that look identical
            // from outside, and a switched-off category is the most common and the least suspected.
            log.debug { "Dropped '${stamped.title}': ${stamped.category} is switched off" }
            return DeliveryMode.DISABLED
        }

        if (settings.seriousMode && !stamped.priority.isAtLeast(NotificationPriority.URGENT)) {
            // Held rather than dropped: serious mode is temporary, and what happened during it still
            // happened. Released when it is switched off.
            log.debug { "Held '${stamped.title}': serious mode" }
            waiting.addLast(stamped.copy(delivery = DeliveryMode.QUEUED))
            trimWaiting()
            return DeliveryMode.QUEUED
        }

        // 2. The same thing again.
        stamped.dedupeKey?.let { key ->
            val previous = lastSeenByKey[key]
            if (previous != null && now() - previous < DEDUPE_WINDOW_MILLIS) {
                // Replaced, not added. The newer one is the more accurate — a progress notification's whole
                // purpose is to supersede the last one.
                val existing = live.values.firstOrNull { it.dedupeKey == key }
                if (existing != null) sink.dismiss(existing.id)
                log.trace { "Replacing a notification with the same key: $key" }
            }
            lastSeenByKey[key] = now()
            if (lastSeenByKey.size > DEDUPE_KEYS_LIMIT) pruneDedupeKeys()
        }

        // 3. Is now a bad moment.
        if (isBusy() && !stamped.priority.isAtLeast(NotificationPriority.URGENT)) {
            return if (settings.queueWhileBusy) {
                log.debug { "Held '${stamped.title}': busy (${context.context.activity.activity.displayName})" }
                waiting.addLast(stamped.copy(delivery = DeliveryMode.QUEUED))
                trimWaiting()
                DeliveryMode.QUEUED
            } else {
                deliver(stamped.copy(delivery = DeliveryMode.COMPACT))
            }
        }

        // 4. Where it goes.
        return deliver(stamped.copy(delivery = configured))
    }

    /**
     * Hands a notification to the sink, and records it.
     *
     * The one place the sink is touched, so [DeliveryMode] means the same thing everywhere. Two call sites
     * interpreting `BOTH` slightly differently is the kind of divergence nobody notices until an inbox is
     * missing half its entries.
     */
    private fun deliver(notification: Notification): DeliveryMode {
        when (notification.delivery) {
            DeliveryMode.TOAST_ONLY, DeliveryMode.COMPACT -> sink.toast(notification)
            DeliveryMode.INBOX_ONLY -> sink.inbox(notification)
            DeliveryMode.BOTH -> {
                sink.toast(notification)
                sink.inbox(notification)
            }
            DeliveryMode.DISABLED, DeliveryMode.QUEUED -> return notification.delivery
        }

        live[notification.id] = notification
        while (live.size > LIVE_LIMIT) live.remove(live.keys.first())
        shown.addFirst(notification)
        while (shown.size > HISTORY_LIMIT) shown.removeLast()
        return notification.delivery
    }

    /**
     * Releases what was held, if it is now safe.
     *
     * Called from a tick or a scheduled job rather than from the context events, so a player who leaves a
     * dungeon and immediately enters another does not get a burst between the two.
     */
    public fun releaseQueuedIfSafe() {
        if (settings.seriousMode || isBusy()) return
        releaseQueued()
    }

    private fun releaseQueued() {
        if (waiting.isEmpty()) return
        log.debug { "Releasing ${waiting.size} held notification(s)" }

        // Grouped on the way out, because releasing eleven at once is eleven toasts nobody reads. One per
        // grouping key, carrying the count.
        val batch = waiting.toList()
        waiting.clear()

        for ((key, group) in batch.groupBy { it.groupingKey ?: it.id }) {
            val first = group.first()
            val delivery = settings.deliveryOf(first.category)
            if (group.size == 1) {
                deliver(first.copy(delivery = delivery))
            } else {
                deliver(
                    first.copy(
                        // The count goes in the subtitle rather than the title: the title is what the group
                        // is about and stays readable, and "and 4 more" is detail.
                        subtitle = first.subtitle?.let { "$it (and ${group.size - 1} more)" }
                            ?: "and ${group.size - 1} more",
                        delivery = delivery,
                        groupingKey = key,
                    ),
                )
            }
        }
    }

    /**
     * Whether interrupting would cost the player something.
     *
     * Asked of the game context, which is the one place that knows — and it combines the island with the
     * activity, because standing in the Crimson Isle is not demanding and being mid-Kuudra is.
     */
    private fun isBusy(): Boolean = context.context.isDemanding

    override fun history(): List<Notification> = shown.toList()

    override fun queued(): List<Notification> = waiting.toList()

    override fun dismiss(notificationId: String) {
        live.remove(notificationId)
        sink.dismiss(notificationId)
    }

    override fun choose(notificationId: String, actionId: String) {
        val notification = live[notificationId] ?: return
        val action = notification.actions.firstOrNull { it.id == actionId } ?: return
        // Isolated, like every other callback the platform runs. One badly-behaved action must not stop the
        // notification being dismissed, or the player is left with a toast they cannot get rid of.
        runCatching { action.onChosen() }
            .onFailure { log.error(it) { "Notification action '$actionId' threw" } }
        if (action.dismisses) dismiss(notificationId)
    }

    /** Drops the oldest held notifications. A queue that grows for an hour is not a queue. */
    private fun trimWaiting() {
        while (waiting.size > QUEUE_LIMIT) waiting.removeFirst()
    }

    private fun pruneDedupeKeys() {
        val cutoff = now() - DEDUPE_WINDOW_MILLIS
        lastSeenByKey.entries.removeIf { it.value < cutoff }
        // Still too many means a caller is generating unique keys, which defeats the point. Cleared rather
        // than grown, because the alternative is an unbounded map.
        if (lastSeenByKey.size > DEDUPE_KEYS_LIMIT) {
            log.warn { "Too many notification dedupe keys; something is generating them per event" }
            lastSeenByKey.clear()
        }
    }

    private companion object {
        const val HISTORY_LIMIT = 200

        /** How many notifications keep their actions runnable. See [live]. */
        const val LIVE_LIMIT = 64
        const val QUEUE_LIMIT = 50
        const val DEDUPE_KEYS_LIMIT = 500

        /**
         * How close together two notifications with one key count as the same.
         *
         * Ten seconds. Long enough to catch a replayed realtime message or a duplicated chat line, short
         * enough that a genuine second drop of the same item is still reported.
         */
        const val DEDUPE_WINDOW_MILLIS = 10_000L
    }
}

/** Builds a notification with an id and a timestamp filled in. The form feature code uses. */
public fun notification(
    category: NotificationCategory,
    title: String,
    subtitle: String? = null,
    priority: NotificationPriority = NotificationPriority.NORMAL,
    dedupeKey: String? = null,
    groupingKey: String? = null,
    id: String = java.util.UUID.randomUUID().toString(),
    block: Notification.() -> Notification = { this },
): Notification = Notification(
    id = id,
    category = category,
    priority = priority,
    title = title,
    subtitle = subtitle,
    dedupeKey = dedupeKey,
    groupingKey = groupingKey,
).block()
