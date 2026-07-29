package dev.th7bo.sidequest.ui.notification

import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.constantState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How loud a notification is, and what colour it takes from the theme. */
public enum class NotificationSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
    ;

    /**
     * Whether this severity should outlive a full queue.
     *
     * An error the player never saw because three info toasts were ahead of it is the
     * failure mode the priority ordering exists to prevent.
     */
    public val isImportant: Boolean get() = this == WARNING || this == ERROR
}

/** What to do when a notification arrives and the queue is already full. */
public enum class OverflowPolicy {
    /** Wait for a slot. Nothing is lost, but a burst is shown slowly. */
    QUEUE,

    /** Replace the oldest showing notification. */
    DROP_OLDEST,

    /** Refuse the new one. */
    DROP_NEWEST,
}

/**
 * One thing to tell the player.
 *
 * Holds no rendering state — no position, no animation progress, no remaining time. Those
 * live in the queue, keyed by id, for the same reason a `Setting` holds no control state:
 * the same notification may be shown twice, and a value object that remembers where it
 * was drawn cannot be.
 */
public class Notification(
    public val id: UiId,
    public val title: UiState<String>,
    public val message: UiState<String>? = null,
    public val severity: NotificationSeverity = NotificationSeverity.INFO,
    public val icon: Icon? = null,
    /** How long it shows once visible. Null means it stays until dismissed. */
    public val duration: Duration? = DEFAULT_DURATION,
    /**
     * Notifications sharing a key coalesce rather than stacking.
     *
     * Ten "picked up 1 diamond" toasts should be one toast that counts to ten. Null
     * disables coalescing for this notification.
     */
    public val coalesceKey: Any? = null,
    /** Invoked when the player clicks the notification. Null makes it non-interactive. */
    public val onActivate: (() -> Unit)? = null,
    /** Invoked however it goes away, including by timing out. */
    public val onDismiss: (() -> Unit)? = null,
) {
    override fun toString(): String = "Notification($id, $severity)"

    public companion object {
        public val DEFAULT_DURATION: Duration = 5.seconds
    }
}

/** A live notification: the [notification] plus the state the queue keeps for it. */
public class ActiveNotification(
    public val notification: Notification,
    /** Wall-clock seconds this has been showing. */
    public var elapsedSeconds: Float = 0f,
    /** How many coalesced occurrences this represents. Always at least one. */
    public var count: Int = 1,
    /** Set when it is on its way out, so the region can animate it. */
    public var isDismissing: Boolean = false,
) {
    public val id: UiId get() = notification.id

    /**
     * Progress towards timing out, in `0..1`, or null when it has no duration.
     *
     * Exposed so a toast can draw a countdown without the region needing to know how
     * timing works.
     */
    public val timeoutFraction: Float?
        get() {
            val duration = notification.duration ?: return null
            val total = duration.inWholeMilliseconds / MILLIS_PER_SECOND
            if (total <= 0f) return 1f
            return (elapsedSeconds / total).coerceIn(0f, 1f)
        }

    override fun toString(): String = "ActiveNotification(${notification.id}, count=$count)"

    private companion object {
        const val MILLIS_PER_SECOND = 1000f
    }
}

/** A notification with no message and default timing. */
public fun notification(
    id: UiId,
    title: String,
    severity: NotificationSeverity = NotificationSeverity.INFO,
): Notification = Notification(id, constantState(title), severity = severity)
