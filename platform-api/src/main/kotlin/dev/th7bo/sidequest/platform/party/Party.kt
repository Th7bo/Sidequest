package dev.th7bo.sidequest.platform.party

import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.skyblock.Activity
import kotlinx.serialization.Serializable

/**
 * The party, as one value.
 *
 * **Features must not parse party chat.** The plan says so and this is the reason it can be
 * obeyed: the party's membership, leader and state are here, assembled once from the chat rules
 * and the tab widget, and a feature that wants to know who is in the party reads this. Six
 * features each watching for "joined the party." is six patterns to fix when Hypixel rewords it,
 * and five of them will not get fixed.
 */
@Serializable
public data class PartyState(
    public val members: List<PartyMember> = emptyList(),
    /**
     * The leader's name, or null when nothing has said.
     *
     * A name and not a [PlayerId], because chat only ever gives a name. Resolve it through the
     * player directory when an identity is needed, and do not store the result of that.
     */
    public val leader: String? = null,
    public val confidence: PartyConfidence = PartyConfidence.NONE,
) {

    public val isInParty: Boolean get() = members.isNotEmpty()

    public val size: Int get() = members.size

    /** Whether the local player leads. Null when the leader is unknown. */
    public fun isLeader(localPlayerName: String?): Boolean? {
        val leader = leader ?: return null
        val local = localPlayerName ?: return null
        return leader.equals(local, ignoreCase = true)
    }

    public fun member(name: String): PartyMember? =
        members.firstOrNull { it.name.equals(name, ignoreCase = true) }

    public fun has(name: String): Boolean = member(name) != null

    public companion object {
        /** Not in a party. */
        public val None: PartyState = PartyState()
    }
}

/**
 * One member.
 *
 * [hasSidequest] is the field the social features hang off: a friend running the mod can be sent a
 * waypoint or a ping, and one who is not has to be told in chat or not at all. It is false until
 * something proves otherwise, because assuming otherwise means sending a plugin message into the
 * void and wondering why nothing happened.
 */
@Serializable
public data class PartyMember(
    public val name: String,
    /** Resolved through the player directory where possible. Null when the client has not seen them. */
    public val id: PlayerId? = null,
    public val role: PartyRole = PartyRole.MEMBER,
    public val hasSidequest: Boolean = false,
    /** What they are doing, when they run Sidequest and have chosen to share it. */
    public val activity: Activity = Activity.UNKNOWN,
) {
    override fun toString(): String = if (role == PartyRole.LEADER) "$name (leader)" else name
}

@Serializable
public enum class PartyRole {
    LEADER,
    MODERATOR,
    MEMBER,
}

/**
 * How much the party state is believed.
 *
 * Its own enum rather than [dev.th7bo.sidequest.platform.skyblock.ContextConfidence], because the
 * sources are different and so are the failure modes. The party is tracked by *accumulating chat
 * lines*, which means a session that began mid-party has never seen the joins and knows nothing
 * until something corroborates it.
 */
@Serializable
public enum class PartyConfidence {
    /** Nothing known. Either not in a party, or in one whose formation we did not see. */
    NONE,

    /** Built up from chat lines seen this session. Correct unless a line was missed. */
    TRACKED,

    /** Corroborated against the tab list's party widget, or freshly listed by `/party list`. */
    CONFIRMED,
}

/**
 * The one authority on the party.
 *
 * Reading is cheap. The state is recomputed when a chat line or the tab list changes, not per call.
 */
public interface PartyService {

    public val party: PartyState

    /** The current ready check, or null when none is running. */
    public val readyCheck: ReadyCheck?

    public val isInParty: Boolean get() = party.isInParty
}

/**
 * A ready check in progress.
 *
 * Modelled here rather than in the feature that starts one, because the party is where the
 * responses arrive and because two features — the party GUI and the chat command — both start them
 * and must not each keep their own idea of the state.
 *
 * Deliberately a value with a deadline rather than a live object with a timer: the timeout is then a
 * property of the data, so a screen redrawing can show the countdown without asking anybody, and
 * there is no timer to leak when a feature unloads.
 */
@Serializable
public data class ReadyCheck(
    /** Who asked. */
    public val startedBy: String,
    public val startedAtMillis: Long,
    /** Wall clock after which unanswered counts as [ReadyResponse.TIMED_OUT]. */
    public val deadlineMillis: Long,
    /** One entry per member who was in the party when the check started. */
    public val responses: Map<String, ReadyResponse> = emptyMap(),
    /** What the check is for, shown to the party. Free text. */
    public val note: String? = null,
) {

    public fun responseOf(name: String): ReadyResponse =
        responses.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            ?: ReadyResponse.WAITING

    public val isComplete: Boolean get() = responses.values.none { it == ReadyResponse.WAITING }

    public val isEverybodyReady: Boolean
        get() = responses.isNotEmpty() && responses.values.all { it == ReadyResponse.READY }

    public fun hasExpired(nowMillis: Long): Boolean = nowMillis >= deadlineMillis

    /** The same check with everything still waiting marked as timed out. */
    public fun timedOut(): ReadyCheck = copy(
        responses = responses.mapValues { (_, response) ->
            if (response == ReadyResponse.WAITING) ReadyResponse.TIMED_OUT else response
        },
    )

    public val readyCount: Int get() = responses.values.count { it == ReadyResponse.READY }

    public val waitingCount: Int get() = responses.values.count { it == ReadyResponse.WAITING }

    /** Who has not answered yet. What a leader wants named rather than counted. */
    public fun waitingOn(): List<String> =
        responses.filterValues { it == ReadyResponse.WAITING }.keys.sorted()

    public fun declinedBy(): List<String> =
        responses.filterValues { it == ReadyResponse.DECLINED }.keys.sorted()

    public fun silent(): List<String> =
        responses.filterValues { it == ReadyResponse.TIMED_OUT }.keys.sorted()

    /**
     * What the check has come to, as one value.
     *
     * Exists so the thing telling the leader has a single question to ask. [isComplete] and
     * [isEverybodyReady] answer two of the four cases between them, and a caller combining them was going to
     * get the precedence wrong.
     *
     * **A decline outranks a timeout.** Somebody actively saying no is the most useful thing a leader can be
     * told, and reporting "timed out" because the clock also ran out would bury the one answer that was
     * actually given.
     */
    public fun outcome(): ReadyCheckOutcome = when {
        responses.isEmpty() -> ReadyCheckOutcome.PENDING
        responses.values.any { it == ReadyResponse.DECLINED } -> ReadyCheckOutcome.SOMEBODY_DECLINED
        responses.values.any { it == ReadyResponse.TIMED_OUT } -> ReadyCheckOutcome.TIMED_OUT
        responses.values.all { it == ReadyResponse.READY } -> ReadyCheckOutcome.ALL_READY
        else -> ReadyCheckOutcome.PENDING
    }
}

/** What a ready check came to. See [ReadyCheck.outcome]. */
public enum class ReadyCheckOutcome {
    /** Still waiting on somebody. */
    PENDING,
    ALL_READY,
    SOMEBODY_DECLINED,
    TIMED_OUT,
}

@Serializable
public enum class ReadyResponse {
    READY,
    DECLINED,

    /** Asked, nothing back yet. */
    WAITING,

    /** Asked, nothing back, and the deadline passed. Distinct from declining. */
    TIMED_OUT,
}

// -- events ---------------------------------------------------------------

/** Base for what the party service announces. */
public sealed class PartyEvent : SidequestEvent() {
    public abstract val party: PartyState
}

/**
 * The party changed in some way.
 *
 * One event for every change rather than one per kind. The chat layer already emits the specific
 * lines — somebody joined, somebody was kicked — and a feature that cares about *those* listens
 * there. This is for anything that just wants to redraw.
 */
public class PartyChangedEvent(
    public val previous: PartyState,
    override val party: PartyState,
) : PartyEvent() {
    override fun describe(): String = "${previous.size} -> ${party.size} member(s), leader ${party.leader}"
}

/** A ready check started, was answered, or ended. */
public class ReadyCheckChangedEvent(
    public val check: ReadyCheck?,
    override val party: PartyState,
) : PartyEvent() {
    override fun describe(): String = when {
        check == null -> "ready check ended"
        check.isEverybodyReady -> "everybody ready"
        else -> "ready check: ${check.responses.values.count { it == ReadyResponse.READY }}/${check.responses.size}"
    }
}
