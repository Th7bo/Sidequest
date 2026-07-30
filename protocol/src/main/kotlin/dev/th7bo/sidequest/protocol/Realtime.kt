package dev.th7bo.sidequest.protocol

import dev.th7bo.sidequest.platform.item.SqItem
import dev.th7bo.sidequest.platform.permission.Permission
import dev.th7bo.sidequest.platform.skyblock.Activity
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything on the realtime connection, in one envelope.
 *
 * The plan lists what every message must carry and this is that list. Two of the fields deserve saying
 * out loud, because they are the ones that make the protocol safe rather than merely typed.
 *
 * **[scope] travels with the message.** Every payload declares which permission gates it, in the
 * message itself. The server checks it before fanning out and the client checks it before displaying.
 * Putting the answer in the envelope means neither side has to hold a table mapping event types to
 * permissions — two tables that can disagree, and when they disagree the disagreement is a leak.
 *
 * **[senderAccount] is filled by the server, not the sender.** A client that could name its own sender
 * could impersonate anybody. The field exists on the way *out* of the server and is ignored on the way
 * in.
 */
@Serializable
public data class RealtimeMessage(
    public val protocolVersion: Int = Protocol.VERSION,
    /** Unique per message. What makes duplicate suppression possible after a resume. */
    public val messageId: String,
    /**
     * Server-assigned, monotonic within a group.
     *
     * Zero on a message a client is sending — the client does not get to choose its place in the
     * order. What a resuming client asks to continue from.
     */
    public val sequence: Long = 0,
    /** When the event *happened*, on the sender's clock corrected to the server's. */
    public val timestampMillis: Long,
    /** Filled by the server. Whatever a client puts here is discarded. */
    public val senderAccount: AccountId? = null,
    /** The Minecraft UUID the sender's device asserted. Display only, never a key. */
    public val senderMinecraftUuid: String? = null,
    /** Which permission gates this payload. See the class comment. */
    public val scope: Permission,
    /**
     * Whether the sender wants to know this arrived.
     *
     * Opt-in, because most of this traffic is presence and pings that are worthless a second later —
     * acknowledging all of it would double the message count to no end. A debt is worth acknowledging;
     * "I am now in the Hub" is not.
     */
    public val requiresAcknowledgement: Boolean = false,
    public val payload: RealtimePayload,
) {

    /** Whether this message is too old to be worth acting on. See [STALE_AFTER_MILLIS]. */
    public fun isStale(nowMillis: Long): Boolean =
        payload.expiresAfterMillis?.let { nowMillis - timestampMillis > it } ?: false

    public companion object {
        /**
         * How long a transient event stays meaningful.
         *
         * A ping from four minutes ago points at somewhere the player has left; showing it is worse than
         * dropping it. Applied per payload, because a debt does not expire and a ping does.
         */
        public const val STALE_AFTER_MILLIS: Long = 60_000
    }
}

/**
 * What a message carries.
 *
 * A sealed hierarchy, so adding a payload is a compile error everywhere it has to be handled rather
 * than a silently-ignored string. Each one declares the permission that gates it and how long it stays
 * relevant, next to the data — which is the only place those two facts cannot drift away from it.
 */
@Serializable
public sealed class RealtimePayload {

    /** The permission that gates this payload. */
    public abstract val scope: Permission

    /**
     * How long this stays worth acting on, or null for "indefinitely".
     *
     * Presence and pings expire; a debt or an achievement does not. Getting this wrong in the "never
     * expires" direction means showing stale state, and in the other means losing a record.
     */
    public open val expiresAfterMillis: Long? get() = null

    // -- presence ----------------------------------------------------------

    /**
     * Somebody is online and doing something.
     *
     * The most frequent message on the connection by a wide margin, and the one most tightly bound to
     * privacy: what it may contain depends on what the sender has agreed to share, which is why the
     * fields are individually nullable rather than the whole message being suppressed. Somebody who
     * shares their activity but not their island sends one with an island of
     * [Island.NONE][dev.th7bo.sidequest.platform.skyblock.Island.NONE].
     */
    @Serializable
    @SerialName("presence")
    public data class Presence(
        public val isOnline: Boolean,
        public val activity: Activity = Activity.UNKNOWN,
        public val island: Island = Island.NONE,
        /** Only when the sender shares [Permission.VIEW_EXACT_POSITION], which is off by default. */
        public val location: SqLocation? = null,
    ) : RealtimePayload() {
        override val scope: Permission get() = Permission.VIEW_ONLINE_STATUS
        override val expiresAfterMillis: Long? get() = RealtimeMessage.STALE_AFTER_MILLIS
    }

    // -- pings and waypoints -----------------------------------------------

    /** A momentary "look here". */
    @Serializable
    @SerialName("ping")
    public data class Ping(
        public val location: SqLocation,
        public val label: String = "",
        /** Which of the ping styles the sender chose. Free-form so the client owns the vocabulary. */
        public val style: String = "default",
    ) : RealtimePayload() {
        override val scope: Permission get() = Permission.SEND_PINGS

        /** Short. A ping is a gesture, and a gesture from two minutes ago is noise. */
        override val expiresAfterMillis: Long get() = 30_000
    }

    /** A shared waypoint, which unlike a ping is meant to persist. */
    @Serializable
    @SerialName("waypoint")
    public data class Waypoint(
        public val id: String,
        public val location: SqLocation,
        public val label: String,
        public val note: String? = null,
        /** True to remove a waypoint previously shared under the same id. */
        public val removed: Boolean = false,
    ) : RealtimePayload() {
        override val scope: Permission get() = Permission.SEND_WAYPOINTS
    }

    // -- party coordination -------------------------------------------------

    @Serializable
    @SerialName("ready_check_started")
    public data class ReadyCheckStarted(
        public val checkId: String,
        public val deadlineMillis: Long,
        public val note: String? = null,
    ) : RealtimePayload() {
        override val scope: Permission get() = Permission.CREATE_READY_CHECKS
    }

    @Serializable
    @SerialName("ready_check_response")
    public data class ReadyCheckResponse(
        public val checkId: String,
        public val ready: Boolean,
    ) : RealtimePayload() {
        override val scope: Permission get() = Permission.CREATE_READY_CHECKS
    }

    // -- the ledger ---------------------------------------------------------

    /**
     * A debt was recorded.
     *
     * Carries the item it is for as an [SqItem] rather than a name, because that is the type whose
     * entire purpose is being readable in eighteen months. Never expires.
     */
    @Serializable
    @SerialName("debt_created")
    public data class DebtCreated(
        public val debtId: String,
        /** Who owes. An account id, because a debt outlives a username. */
        public val debtor: AccountId,
        public val coins: Long = 0,
        public val item: SqItem? = null,
        public val note: String? = null,
    ) : RealtimePayload() {
        override val scope: Permission get() = Permission.CREATE_DEBTS
    }

    /** A debt was settled. Separate from creation because the permission differs. */
    @Serializable
    @SerialName("payment_confirmed")
    public data class PaymentConfirmed(
        public val debtId: String,
        public val coins: Long = 0,
        public val note: String? = null,
    ) : RealtimePayload() {
        override val scope: Permission get() = Permission.CONFIRM_PAYMENTS
    }

    // -- progression --------------------------------------------------------

    @Serializable
    @SerialName("achievement_earned")
    public data class AchievementEarned(
        public val achievementId: String,
        public val displayName: String,
        /** What earned it, when an item did. */
        public val item: SqItem? = null,
    ) : RealtimePayload() {
        override val scope: Permission get() = Permission.CREATE_ACHIEVEMENTS
    }

    @Serializable
    @SerialName("title_assigned")
    public data class TitleAssigned(
        public val subject: AccountId,
        public val titleId: String,
        public val removed: Boolean = false,
    ) : RealtimePayload() {
        override val scope: Permission get() = Permission.ASSIGN_TITLES
    }

    @Serializable
    @SerialName("currency_changed")
    public data class CurrencyChanged(
        public val subject: AccountId,
        public val delta: Long,
        public val balance: Long,
        public val reason: String? = null,
    ) : RealtimePayload() {
        override val scope: Permission get() = Permission.MANAGE_SHOP_REWARDS
    }

    @Serializable
    @SerialName("cosmetic_changed")
    public data class CosmeticChanged(
        public val subject: AccountId,
        public val cosmeticId: String,
        public val equipped: Boolean,
    ) : RealtimePayload() {
        override val scope: Permission get() = Permission.ASSIGN_JOKE_COSMETICS
    }

    // -- shared memories ----------------------------------------------------

    /**
     * A screenshot or clip was uploaded.
     *
     * Metadata only. The asset itself goes through the asset manager, which is a later plan item, and
     * putting a megabyte of PNG on the realtime connection would block every ping behind it.
     */
    @Serializable
    @SerialName("evidence_added")
    public data class EvidenceAdded(
        public val evidenceId: String,
        public val caption: String = "",
        /** What it is of, when it is of something the mod knows about. */
        public val item: SqItem? = null,
        public val location: SqLocation? = null,
    ) : RealtimePayload() {
        override val scope: Permission get() = Permission.UPLOAD_EVIDENCE
    }

    @Serializable
    @SerialName("comment_added")
    public data class CommentAdded(
        public val commentId: String,
        /** What is being commented on: an evidence id, a debt id, an achievement id. */
        public val subjectId: String,
        public val text: String,
    ) : RealtimePayload() {
        override val scope: Permission get() = Permission.UPLOAD_EVIDENCE
    }

    @Serializable
    @SerialName("reaction_added")
    public data class ReactionAdded(
        public val subjectId: String,
        public val reaction: String,
        public val removed: Boolean = false,
    ) : RealtimePayload() {
        override val scope: Permission get() = Permission.UPLOAD_EVIDENCE
    }

    /** A sound to play in everybody's ears. */
    @Serializable
    @SerialName("sound")
    public data class Sound(
        public val soundId: String,
        public val volume: Float = 1f,
    ) : RealtimePayload() {
        override val scope: Permission get() = Permission.TRIGGER_SOUNDS

        /** Short. A sound that arrives late is a sound nobody understands. */
        override val expiresAfterMillis: Long get() = 10_000
    }

    // -- the group itself ---------------------------------------------------

    /**
     * The group's members or permissions changed.
     *
     * Deliberately carries no detail: it is a signal to re-fetch [GroupState], not a diff. A diff of
     * permissions applied out of order would leave a client enforcing a rule the group has changed, and
     * re-fetching is one request.
     */
    @Serializable
    @SerialName("group_changed")
    public data class GroupChanged(public val revision: Long) : RealtimePayload() {
        override val scope: Permission get() = Permission.VIEW_ONLINE_STATUS
    }

    /**
     * Somebody changed what they are wearing.
     *
     * **The whole loadout, not a diff**, and for the same reason [GroupChanged] carries no detail: a client
     * that missed one message would otherwise be permanently wrong about what somebody is wearing, with
     * nothing to tell it so. A loadout is a handful of short strings, so sending all of it costs nothing next
     * to the cost of being wrong.
     *
     * This is why it exists alongside [CosmeticChanged], which is a different event: that one is an *grant*
     * — an admin gave somebody a cosmetic — and this one is a person putting one on.
     *
     * Keyed by slot name rather than by an ordinal, so a client on an older version drops the slots it does
     * not recognise instead of misreading them as the wrong ones.
     */
    @Serializable
    @SerialName("loadout_changed")
    public data class LoadoutChanged(
        public val subject: AccountId,
        /** Slot name to cosmetic id. An absent slot is empty; there is no separate "unequipped" message. */
        public val equipped: Map<String, String> = emptyMap(),
    ) : RealtimePayload() {
        /**
         * Seeing what a friend wears is part of seeing that they are there.
         *
         * Not its own permission: a cosmetic is public by construction — the wearer already decided who may
         * see it through `CosmeticVisibility`, and a second gate here would mean two places deciding one
         * question.
         */
        override val scope: Permission get() = Permission.VIEW_ONLINE_STATUS
    }
}

// -- connection control ----------------------------------------------------

/**
 * What a client sends when it opens the connection.
 *
 * The resume point is the whole reason this exists. A client that reconnects and starts listening has
 * a hole in its history exactly as long as it was away, and the events it missed are the ones that
 * happened while somebody was wondering where it went.
 */
@Serializable
public data class RealtimeHello(
    public val protocolVersion: Int = Protocol.VERSION,
    /**
     * The last sequence this client processed, or 0 for "everything you still have".
     *
     * Not "everything ever": the server keeps a bounded window, and a client that has been away for a
     * week is better off fetching current state than replaying a week of pings.
     */
    public val resumeFromSequence: Long = 0,
)

/** The server's answer. */
@Serializable
public data class RealtimeWelcome(
    public val protocolVersion: Int,
    public val accountId: AccountId,
    /** The newest sequence the server has. A client behind this will be sent the difference. */
    public val currentSequence: Long,
    /**
     * True when the requested resume point is older than the window the server kept.
     *
     * The client's cue that it has a hole it cannot fill from the stream and should re-fetch state
     * instead of pretending it is caught up.
     */
    public val resumeGap: Boolean = false,
)

/** Acknowledges messages that asked for it. */
@Serializable
public data class RealtimeAck(public val messageIds: List<String>)

/**
 * Anything the connection can carry, in either direction.
 *
 * One sealed type rather than two, because the framing is the same and a single type means the
 * serialiser configuration cannot differ between the two directions — which it did, once, in every
 * project that used two.
 */
@Serializable
public sealed class RealtimeFrame {

    @Serializable
    @SerialName("hello")
    public data class Hello(public val hello: RealtimeHello) : RealtimeFrame()

    @Serializable
    @SerialName("welcome")
    public data class Welcome(public val welcome: RealtimeWelcome) : RealtimeFrame()

    @Serializable
    @SerialName("message")
    public data class Message(public val message: RealtimeMessage) : RealtimeFrame()

    @Serializable
    @SerialName("ack")
    public data class Ack(public val ack: RealtimeAck) : RealtimeFrame()

    /**
     * Keeps the connection from being closed by something in the middle.
     *
     * Sent by the server, echoed by the client. A silent WebSocket through a home router is a
     * WebSocket that stops working after a few minutes with no error on either side.
     */
    @Serializable
    @SerialName("ping")
    public data class KeepAlive(public val timestampMillis: Long) : RealtimeFrame()

    /** The server is closing the connection, and why. */
    @Serializable
    @SerialName("error")
    public data class Error(
        public val code: ApiErrorCode,
        public val message: String,
    ) : RealtimeFrame()
}
