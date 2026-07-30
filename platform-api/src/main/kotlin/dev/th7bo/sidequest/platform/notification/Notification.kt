package dev.th7bo.sidequest.platform.notification

import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.skyblock.Activity
import dev.th7bo.sidequest.platform.skyblock.Island
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Something worth telling the player.
 *
 * The plan says the notification *UI* already exists and asks for the runtime that supplies it. This is
 * what gets supplied: a value, with no widget or renderer anywhere in it, so the manager can decide what to
 * do with one without being able to draw it.
 *
 * Most of the fields are ordinary. The two that carry the design are [groupingKey] and [dedupeKey], and
 * they are different questions: grouping is "show these as one", deduplication is "this is the same one
 * again". A mod that conflates them either stacks unrelated notifications or shows forty copies of the same
 * drop.
 */
@Serializable
public data class Notification(
    /** Stable per notification instance. What an acknowledgement or a dismissal refers to. */
    public val id: String,
    public val category: NotificationCategory,
    public val priority: NotificationPriority = NotificationPriority.NORMAL,
    public val title: String,
    public val subtitle: String? = null,
    /** An icon id the UI resolves. A reference, not an image — this type never carries pixels. */
    public val iconId: SqId? = null,
    /** 0..1 for a determinate bar, or null for a notification that is not about progress. */
    public val progress: Float? = null,
    /** How long to show it. Null means the category's default. */
    public val durationMillis: Long? = null,
    /**
     * What the player can do about it.
     *
     * **Not serialised.** An action is a callback, and a callback restored from disk would point at a feature
     * that may not be loaded — or worse, at the wrong one. A notification read back from the inbox after a
     * restart is a record of something that happened, and the buttons on it are gone with the session that
     * offered them.
     */
    @Transient
    public val actions: List<NotificationAction> = emptyList(),
    /** A sound to play with it, by id. Played through the sound manager, not by the UI. */
    public val soundId: SqId? = null,

    /**
     * Notifications with the same key are shown as one.
     *
     * "Three friends came online" rather than three toasts. Grouping is about presentation and says nothing
     * about whether the notifications are the same event.
     */
    public val groupingKey: String? = null,

    /**
     * A notification with a key already seen recently replaces rather than adds.
     *
     * The same event arriving twice — a duplicated chat line, a replayed realtime message after a resume —
     * must not produce two toasts. Distinct from [groupingKey]: two different friends coming online group
     * together and are not duplicates of each other.
     */
    public val dedupeKey: String? = null,

    /** Where the player was when it happened. Kept for the inbox, which is read later and out of context. */
    public val island: Island = Island.NONE,
    public val activity: Activity = Activity.UNKNOWN,
    public val timestampMillis: Long = 0,

    /** Set by the manager rather than the caller. See [NotificationManager.notify]. */
    public val delivery: DeliveryMode = DeliveryMode.BOTH,
) {

    /** How long this should be shown, falling back to the category's own default. */
    public fun effectiveDurationMillis(): Long = durationMillis ?: category.defaultDurationMillis

    public companion object {
        /** The dedupe key most callers want: the category and a subject. */
        public fun keyOf(category: NotificationCategory, subject: String): String = "${category.name}:$subject"
    }
}

/**
 * What a notification is about.
 *
 * The category decides the default duration and the default delivery, because those correlate with the kind
 * of thing being said far more than with any individual notification. It is also what a user switches off:
 * "stop telling me about drops" is a category, not a list of notifications.
 */
@Serializable
public enum class NotificationCategory(
    public val displayName: String,
    public val defaultDurationMillis: Long,
    public val defaultDelivery: DeliveryMode,
) {
    /** A rare drop, an achievement. Worth interrupting for, briefly. */
    PROGRESSION("Progression", 5_000, DeliveryMode.BOTH),

    /** A friend came online, somebody pinged. Transient and social. */
    SOCIAL("Social", 4_000, DeliveryMode.BOTH),

    /** A debt, a payment, a ready check. Worth keeping, so it goes to the inbox regardless. */
    LEDGER("Ledger", 6_000, DeliveryMode.BOTH),

    /**
     * Something needs the player's attention now.
     *
     * The only category that is shown even while busy — see [NotificationManager]. Kept small on purpose: a
     * category that is always urgent is a category nobody reads.
     */
    ALERT("Alert", 8_000, DeliveryMode.BOTH),

    /** Backend state, a failed sync, a revoked device. Toast once, keep in the inbox. */
    SYSTEM("System", 5_000, DeliveryMode.BOTH),

    /** Developer output. Off unless somebody turned it on. */
    DEBUG("Debug", 3_000, DeliveryMode.DISABLED),
}

/**
 * How much a notification is worth interrupting for.
 *
 * Ordered, so a policy can compare. Priority decides ordering within the queue and whether something
 * survives being shown while the player is busy; the category decides where it goes.
 */
@Serializable
public enum class NotificationPriority {
    LOW,
    NORMAL,
    HIGH,

    /** Shown immediately, whatever the player is doing. For a revoked device, not for a rare drop. */
    URGENT,
    ;

    public fun isAtLeast(other: NotificationPriority): Boolean = ordinal >= other.ordinal
}

/** Something the player can do about it. The UI renders these; the handler runs here. */
public data class NotificationAction(
    public val id: String,
    public val label: String,
    /** Whether choosing this dismisses the notification. Usually yes. */
    public val dismisses: Boolean = true,
    public val onChosen: () -> Unit,
)

/** Where a notification goes. */
@Serializable
public enum class DeliveryMode {
    /** A toast, and nothing kept. For anything worthless a minute later. */
    TOAST_ONLY,

    /** Kept in the inbox with no toast. For anything worth reading later but not worth interrupting for. */
    INBOX_ONLY,

    BOTH,

    /** Neither. What a switched-off category produces. */
    DISABLED,

    /**
     * A smaller toast, because the player is busy.
     *
     * Chosen by the manager, not by a caller: it is a *decision*, and the caller does not know whether the
     * player is mid-Kuudra.
     */
    COMPACT,

    /**
     * Held until it is safe to show.
     *
     * The most interesting mode. Something worth telling somebody about, at a moment when telling them would
     * cost them a dungeon run — so it waits. Held rather than dropped, because the thing that happened still
     * happened.
     */
    QUEUED,
}

/**
 * Where notifications end up.
 *
 * An interface, so the manager's queueing, deduplication and policy are testable with no UI at all — and so
 * the UI can be replaced without the policy moving. The manager decides *whether* and *how*; the sink
 * decides what it looks like.
 */
public interface NotificationSink {

    /** Shows a toast. Called on the client thread. */
    public fun toast(notification: Notification)

    /** Adds to the inbox. */
    public fun inbox(notification: Notification)

    /** Removes a toast early, because a replacement arrived or an action dismissed it. */
    public fun dismiss(notificationId: String)

    public companion object {
        /** Discards everything. The default, so a headless platform has a working manager. */
        public val None: NotificationSink = object : NotificationSink {
            override fun toast(notification: Notification) {}
            override fun inbox(notification: Notification) {}
            override fun dismiss(notificationId: String) {}
        }
    }
}

/**
 * What the player has asked for.
 *
 * Per category, because that is the granularity people actually want: "stop telling me about drops" rather
 * than a switch per notification type. [seriousMode] is the blanket one — see the sound manager, which has
 * the same idea for the same reason.
 */
@Serializable
public data class NotificationSettings(
    public val perCategory: Map<NotificationCategory, DeliveryMode> = emptyMap(),
    /**
     * Suppresses everything below [NotificationPriority.URGENT].
     *
     * For somebody recording, or in a serious run, who wants the mod to stop being fun for an hour. One
     * switch rather than eight, because somebody reaching for it is not in the mood to configure anything.
     */
    public val seriousMode: Boolean = false,
    /**
     * Whether to hold notifications while the player is busy rather than showing them compactly.
     *
     * Both are reasonable and people disagree, so it is a setting rather than a decision.
     */
    public val queueWhileBusy: Boolean = true,
) {

    /** The delivery for [category], falling back to the category's own default. */
    public fun deliveryOf(category: NotificationCategory): DeliveryMode =
        perCategory[category] ?: category.defaultDelivery

    public companion object {
        public val Default: NotificationSettings = NotificationSettings()
    }
}

/**
 * The one place a notification is decided on.
 *
 * Features do not draw notifications and do not decide whether to show them. They say what happened; this
 * applies the policy. Scattering the policy would mean the "serious mode" switch working for whichever
 * features remembered it.
 */
public interface NotificationManager {

    public val settings: NotificationSettings

    /**
     * Offers a notification.
     *
     * Returns what was actually done with it, which is not always what the caller asked for: it may be
     * deduplicated away, compacted, queued until the player is free, or dropped because the category is off.
     */
    public fun notify(notification: Notification): DeliveryMode

    /** Notifications shown recently, newest first. Bounded. For an inbox screen and for diagnostics. */
    public fun history(): List<Notification>

    /** Anything waiting for a safe moment. */
    public fun queued(): List<Notification>

    /** Dismisses a live notification and runs nothing. */
    public fun dismiss(notificationId: String)

    /** Runs an action on a notification, by id. */
    public fun choose(notificationId: String, actionId: String)
}
