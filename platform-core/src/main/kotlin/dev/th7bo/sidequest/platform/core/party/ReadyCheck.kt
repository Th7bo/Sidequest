package dev.th7bo.sidequest.platform.core.party

import dev.th7bo.sidequest.platform.player.PlayerId

/** Where one person stands in a ready check. */
public enum class ReadyState {
    /** Asked, and has not answered. */
    WAITING,
    READY,
    DECLINED,

    /**
     * Ran out of time without answering.
     *
     * Distinct from [DECLINED] on purpose. Somebody who said no has made a decision and the leader can act on
     * it; somebody who never answered is probably away from the keyboard, and "three declined" would be a
     * lie about what happened.
     */
    NO_RESPONSE,
}

/** What a ready check has come to, as a whole. */
public enum class ReadyCheckOutcome {
    /** Still waiting on somebody. */
    PENDING,

    /** Everybody said yes. */
    ALL_READY,

    /** At least one person said no. Reported the moment it happens rather than at the deadline. */
    SOMEBODY_DECLINED,

    /** The deadline passed with somebody still silent. */
    TIMED_OUT,
}

/** One participant, and anything worth warning the leader about. */
public data class ReadyParticipant(
    public val player: PlayerId,
    public val state: ReadyState = ReadyState.WAITING,
    /** What they said they would be doing, when a check asks for roles. Null when it does not. */
    public val role: String? = null,
    /**
     * A reason to look twice before pulling — no gear, wrong floor, missing a key.
     *
     * A warning rather than a refusal: the leader decides, and a ready check that blocked on equipment would
     * be wrong about somebody's plan more often than it was right.
     */
    public val warning: String? = null,
)

/**
 * A ready check, and what everybody has said.
 *
 * Immutable, like everything else that gets synced: a response arrives, a new one comes back. Answering
 * twice, answering after the deadline and answering for somebody who was never asked are all *expected*
 * traffic on a shared connection rather than misuse, so every one of them is handled here rather than being
 * left for whoever calls it.
 *
 * Timing is passed in, never read. A check is judged against the deadline it was created with, and reading
 * a clock inside would make every one of these decisions untestable.
 */
public data class ReadyCheck(
    public val id: String,
    public val leader: PlayerId,
    public val participants: List<ReadyParticipant>,
    public val deadlineMillis: Long,
    public val note: String? = null,
) {

    public fun participant(player: PlayerId): ReadyParticipant? =
        participants.firstOrNull { it.player == player }

    /**
     * Records an answer.
     *
     * Somebody not in the check is ignored rather than added. The participant list is the leader's statement
     * of who is being asked, and a stray message — from somebody who left the party, or a duplicate after a
     * reconnect — must not quietly enlarge it.
     *
     * **The first answer stands.** A second one is almost always a resend after a hiccup, and letting it
     * through would mean a leader watching somebody flip between ready and not for reasons that are entirely
     * about the network.
     */
    public fun answered(player: PlayerId, isReady: Boolean): ReadyCheck {
        val existing = participant(player) ?: return this
        if (existing.state != ReadyState.WAITING) return this
        return replace(existing.copy(state = if (isReady) ReadyState.READY else ReadyState.DECLINED))
    }

    /** Sets somebody's role or warning. Also ignored for anybody not in the check. */
    public fun described(player: PlayerId, role: String? = null, warning: String? = null): ReadyCheck {
        val existing = participant(player) ?: return this
        return replace(existing.copy(role = role ?: existing.role, warning = warning ?: existing.warning))
    }

    /**
     * The check as it stands at [nowMillis], with the silent marked as such once the deadline has passed.
     *
     * Applied rather than assumed, so [outcome] and the participant list agree — a check reporting
     * `TIMED_OUT` while everybody still reads `WAITING` would be two answers to the same question.
     */
    public fun settledAt(nowMillis: Long): ReadyCheck {
        if (nowMillis < deadlineMillis) return this
        if (participants.none { it.state == ReadyState.WAITING }) return this
        return copy(
            participants = participants.map { participant ->
                if (participant.state == ReadyState.WAITING) {
                    participant.copy(state = ReadyState.NO_RESPONSE)
                } else {
                    participant
                }
            },
        )
    }

    /**
     * What this has come to at [nowMillis].
     *
     * A decline wins over everything, including a timeout: somebody actively saying no is the most useful
     * thing the leader can be told, and burying it under "timed out" because the clock also ran out would
     * hide the one answer that was given.
     */
    public fun outcome(nowMillis: Long): ReadyCheckOutcome {
        val settled = settledAt(nowMillis)
        return when {
            settled.participants.any { it.state == ReadyState.DECLINED } -> ReadyCheckOutcome.SOMEBODY_DECLINED
            settled.participants.any { it.state == ReadyState.NO_RESPONSE } -> ReadyCheckOutcome.TIMED_OUT
            settled.participants.all { it.state == ReadyState.READY } -> ReadyCheckOutcome.ALL_READY
            else -> ReadyCheckOutcome.PENDING
        }
    }

    /** True once there is nothing left to wait for. */
    public fun isFinished(nowMillis: Long): Boolean = outcome(nowMillis) != ReadyCheckOutcome.PENDING

    /** How many are still to answer, ignoring the clock. */
    public val waitingCount: Int get() = participants.count { it.state == ReadyState.WAITING }

    public val readyCount: Int get() = participants.count { it.state == ReadyState.READY }

    /**
     * A line for the leader.
     *
     * Names the people worth naming and counts the rest. "Two declined" is not actionable; "Ana and Bo
     * declined" is, and a party of five is small enough to name in full.
     */
    public fun summary(nowMillis: Long, nameOf: (PlayerId) -> String): String {
        val settled = settledAt(nowMillis)
        val declined = settled.participants.filter { it.state == ReadyState.DECLINED }
        val silent = settled.participants.filter { it.state == ReadyState.NO_RESPONSE }
        val waiting = settled.participants.filter { it.state == ReadyState.WAITING }

        return when (settled.outcome(nowMillis)) {
            ReadyCheckOutcome.ALL_READY -> "Everybody is ready"
            ReadyCheckOutcome.SOMEBODY_DECLINED -> declined.joinToString(", ") { nameOf(it.player) } + " declined"
            ReadyCheckOutcome.TIMED_OUT -> "No answer from " + silent.joinToString(", ") { nameOf(it.player) }
            ReadyCheckOutcome.PENDING ->
                "${settled.readyCount}/${settled.participants.size} ready · waiting on " +
                    waiting.joinToString(", ") { nameOf(it.player) }
        }
    }

    private fun replace(updated: ReadyParticipant): ReadyCheck = copy(
        participants = participants.map { if (it.player == updated.player) updated else it },
    )

    public companion object {
        /**
         * Starts one.
         *
         * The leader counts as ready immediately. They are the one asking, and making somebody click "yes" to
         * their own question is the sort of thing that gets a feature turned off.
         */
        public fun start(
            id: String,
            leader: PlayerId,
            members: Collection<PlayerId>,
            deadlineMillis: Long,
            note: String? = null,
        ): ReadyCheck = ReadyCheck(
            id = id,
            leader = leader,
            participants = members.distinct().map { player ->
                ReadyParticipant(
                    player = player,
                    state = if (player == leader) ReadyState.READY else ReadyState.WAITING,
                )
            },
            deadlineMillis = deadlineMillis,
            note = note,
        )
    }
}
