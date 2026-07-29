package dev.th7bo.sidequest.ui.core.notification

import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.notification.ActiveNotification
import dev.th7bo.sidequest.ui.notification.Notification
import dev.th7bo.sidequest.ui.notification.NotificationSeverity
import dev.th7bo.sidequest.ui.notification.OverflowPolicy
import dev.th7bo.sidequest.ui.state.Disposable
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.UiThread
import dev.th7bo.sidequest.ui.state.mutableStateOf

/**
 * Decides what is on screen, for how long, and in what order.
 *
 * Kept entirely separate from the region that draws it. Timing, coalescing, priority and
 * overflow are the hard parts and none of them need a renderer, so all of them are tested
 * against a clock the test controls rather than against a frame loop.
 */
public class NotificationQueue(
    /** How many show at once. Beyond this, [OverflowPolicy] decides. */
    public val maxVisible: Int = DEFAULT_MAX_VISIBLE,
    public val overflowPolicy: OverflowPolicy = OverflowPolicy.QUEUE,
    /** Cap on the backlog, so a runaway emitter cannot grow the queue without bound. */
    public val maxPending: Int = DEFAULT_MAX_PENDING,
) : Disposable {

    private val showingState: MutableUiState<List<ActiveNotification>> =
        mutableStateOf(emptyList(), "notifications.showing")

    private val pending = ArrayDeque<Notification>()

    /** Currently on screen, in display order. */
    public val showing: UiState<List<ActiveNotification>> get() = showingState

    /** Waiting for a slot. */
    public val pendingCount: Int get() = pending.size

    /** Notifications dropped because the backlog was full. Surfaced rather than hidden. */
    public var droppedCount: Int = 0
        private set

    /** True while nothing is showing and nothing is waiting. */
    public val isIdle: Boolean get() = showingState.peek().isEmpty() && pending.isEmpty()

    /**
     * Pauses timeouts.
     *
     * Set while a screen is open: a notification that expired behind the configuration
     * screen was never actually seen, and silently discarding it is the one behaviour a
     * notification system must not have.
     */
    public var isPaused: Boolean = false

    /**
     * Shows [notification], or queues it.
     *
     * @return the live entry if it went on screen immediately or coalesced into one that
     *   is, and null if it was queued or dropped.
     */
    public fun post(notification: Notification): ActiveNotification? {
        UiThread.check()

        // Coalescing first: a repeat of something already showing must not consume a slot
        // or restart the whole queue's ordering.
        notification.coalesceKey?.let { key ->
            val existing = showingState.peek().firstOrNull {
                !it.isDismissing && it.notification.coalesceKey == key
            }
            if (existing != null) {
                existing.count++
                // Restarted, not extended: the newest occurrence is what the player is
                // reacting to, so it gets a full duration rather than a leftover sliver.
                existing.elapsedSeconds = 0f
                bump()
                return existing
            }
        }

        val current = showingState.peek()
        if (current.size < maxVisible) {
            showingState.value = insertByPriority(current, ActiveNotification(notification))
            return showingState.peek().first { it.notification === notification }
        }

        return when (overflowPolicy) {
            OverflowPolicy.QUEUE -> {
                if (pending.size >= maxPending) {
                    droppedCount++
                    null
                } else {
                    pending.addLast(notification)
                    null
                }
            }

            OverflowPolicy.DROP_OLDEST -> {
                // An important notification may evict a trivial one; the reverse must not
                // happen, or an error can be pushed off by routine chatter.
                val victim = current
                    .filter { !it.notification.severity.isImportant || notification.severity.isImportant }
                    .minByOrNull { it.notification.severity.ordinal }
                if (victim == null) {
                    droppedCount++
                    null
                } else {
                    remove(victim.id)
                    showingState.value = insertByPriority(showingState.peek(), ActiveNotification(notification))
                    showingState.peek().first { it.notification === notification }
                }
            }

            OverflowPolicy.DROP_NEWEST -> {
                droppedCount++
                null
            }
        }
    }

    /** Higher severity sorts first; equal severity keeps arrival order. */
    private fun insertByPriority(
        current: List<ActiveNotification>,
        entry: ActiveNotification,
    ): List<ActiveNotification> {
        val index = current.indexOfFirst {
            it.notification.severity.ordinal < entry.notification.severity.ordinal
        }
        val next = ArrayList(current)
        if (index < 0) next.add(entry) else next.add(index, entry)
        return next
    }

    /**
     * Advances timers by [deltaSeconds] and promotes anything waiting.
     *
     * @return true if what is showing changed.
     */
    public fun tick(deltaSeconds: Float): Boolean {
        UiThread.check()
        var changed = false

        if (!isPaused) {
            val expired = showingState.peek().filter { entry ->
                entry.elapsedSeconds += deltaSeconds
                val fraction = entry.timeoutFraction
                fraction != null && fraction >= 1f
            }
            for (entry in expired) {
                remove(entry.id)
                changed = true
            }
        }

        // Promote after expiry, so a slot freed this tick is filled this tick.
        while (showingState.peek().size < maxVisible && pending.isNotEmpty()) {
            val next = pending.removeFirst()
            showingState.value = insertByPriority(showingState.peek(), ActiveNotification(next))
            changed = true
        }

        return changed
    }

    /** Dismisses [id]. @return true if it was showing. */
    public fun dismiss(id: UiId): Boolean {
        UiThread.check()
        return remove(id)
    }

    private fun remove(id: UiId): Boolean {
        val current = showingState.peek()
        val entry = current.firstOrNull { it.id == id } ?: return false
        showingState.value = current - entry
        entry.notification.onDismiss?.invoke()
        return true
    }

    /** Dismisses everything showing. Pending notifications are kept. */
    public fun dismissAll() {
        UiThread.check()
        for (entry in showingState.peek()) entry.notification.onDismiss?.invoke()
        showingState.value = emptyList()
    }

    /** Clears everything, showing and pending, without invoking dismissal callbacks. */
    public fun clear() {
        UiThread.check()
        showingState.value = emptyList()
        pending.clear()
        droppedCount = 0
    }

    /** Runs the activation handler for [id] and dismisses it. */
    public fun activate(id: UiId): Boolean {
        UiThread.check()
        val entry = showingState.peek().firstOrNull { it.id == id } ?: return false
        entry.notification.onActivate?.invoke()
        return remove(id)
    }

    /** Forces observers to re-read after a mutation made in place. */
    private fun bump() {
        showingState.value = ArrayList(showingState.peek())
    }

    override fun dispose() {
        showingState.value = emptyList()
        pending.clear()
    }

    public companion object {
        public const val DEFAULT_MAX_VISIBLE: Int = 3
        public const val DEFAULT_MAX_PENDING: Int = 64

        /** Severity ordering, highest first. Exposed so the region can label a group. */
        public val SEVERITY_ORDER: List<NotificationSeverity> =
            NotificationSeverity.entries.sortedByDescending { it.ordinal }
    }
}
