package dev.th7bo.sidequest.platform.core.party

import dev.th7bo.sidequest.platform.chat.PartyDisbandedEvent
import dev.th7bo.sidequest.platform.chat.PartyJoinedEvent
import dev.th7bo.sidequest.platform.chat.PartyKickedEvent
import dev.th7bo.sidequest.platform.chat.PartyLeaderChangedEvent
import dev.th7bo.sidequest.platform.chat.PartyMemberJoinedEvent
import dev.th7bo.sidequest.platform.chat.PartyMemberLeftEvent
import dev.th7bo.sidequest.platform.event.DispatchMode
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.event.MinecraftDisconnectEvent
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.lifecycle.RegistrationScope
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.party.PartyChangedEvent
import dev.th7bo.sidequest.platform.party.PartyConfidence
import dev.th7bo.sidequest.platform.party.PartyMember
import dev.th7bo.sidequest.platform.party.PartyRole
import dev.th7bo.sidequest.platform.party.PartyService
import dev.th7bo.sidequest.platform.party.PartyState
import dev.th7bo.sidequest.platform.party.ReadyCheck
import dev.th7bo.sidequest.platform.party.ReadyCheckChangedEvent
import dev.th7bo.sidequest.platform.party.ReadyResponse
import dev.th7bo.sidequest.platform.player.PlayerDirectory
import kotlin.time.Duration

/**
 * Tracks the party.
 *
 * Fed entirely from things that already exist: the chat rules for the membership lines, and the tab
 * list's party widget for corroboration. Nothing here parses a chat string — that is the whole
 * point of the chat parser existing, and this service is the demonstration that the layering works.
 *
 * Two things shape the design.
 *
 * **Chat is a stream of changes, not a state.** Joining a session mid-party means the joins were
 * never seen, so tracking alone can be silently empty. The tab widget is the corroboration that
 * fixes it, and [PartyConfidence] is how the difference is expressed rather than hidden.
 *
 * **The widget is trusted over the accumulation.** When both have something to say, the widget
 * wins: it is a statement of who is in the party right now, where the accumulation is a guess built
 * from lines that may have been missed. The accumulation still matters — the widget is not always
 * on the board — which is why both are kept.
 */
public class DefaultPartyService(
    private val events: EventBus,
    private val players: PlayerDirectory,
    private val log: Logger,
    private val now: () -> Long = System::currentTimeMillis,
) : PartyService {

    override var party: PartyState = PartyState.None
        private set

    override var readyCheck: ReadyCheck? = null
        private set

    /** Names accumulated from chat, in the order they were seen. */
    private val tracked = LinkedHashSet<String>()

    private var trackedLeader: String? = null

    private val scope = RegistrationScope("party.service")

    /**
     * Subscribes to everything the party is learned from.
     *
     * `IMMEDIATE`, so the party is already up to date by the time the chat event reaches a feature
     * listening for the same line. A feature reacting to "somebody joined the party" by reading the
     * member list must not see the list from before they joined.
     */
    public fun install() {
        scope.add(events.on<PartyJoinedEvent>(OWNER, mode = DispatchMode.IMMEDIATE) { event ->
            // Joining somebody's party tells us the leader and that we are in it. It does not tell
            // us who else is there — that arrives as "You'll be partying with", which is a line we
            // do not yet have a rule for, or from the widget.
            trackedLeader = event.leader
            tracked.add(event.leader)
            recompute()
        })
        scope.add(events.on<PartyMemberJoinedEvent>(OWNER, mode = DispatchMode.IMMEDIATE) { event ->
            tracked.add(event.member)
            recompute()
        })
        scope.add(events.on<PartyMemberLeftEvent>(OWNER, mode = DispatchMode.IMMEDIATE) { event ->
            tracked.removeAll { it.equals(event.member, ignoreCase = true) }
            if (trackedLeader.equals(event.member, ignoreCase = true)) trackedLeader = null
            recompute()
        })
        scope.add(events.on<PartyLeaderChangedEvent>(OWNER, mode = DispatchMode.IMMEDIATE) { event ->
            trackedLeader = event.newLeader
            tracked.add(event.newLeader)
            recompute()
        })
        scope.add(events.on<PartyDisbandedEvent>(OWNER, mode = DispatchMode.IMMEDIATE) { leave("disbanded") })
        scope.add(events.on<PartyKickedEvent>(OWNER, mode = DispatchMode.IMMEDIATE) { leave("kicked") })

        // A party does not survive leaving the server, and a stale one would have features
        // messaging people who are not there.
        scope.add(events.on<MinecraftDisconnectEvent>(OWNER, mode = DispatchMode.IMMEDIATE) { leave("disconnected") })

        log.debug { "Party service installed" }
    }

    /**
     * Feeds the tab list's party widget.
     *
     * Called by the adapter alongside the board polling, the same way the scoreboard reaches the
     * context service. An empty list means the widget was not on the board — which is "the tab list
     * did not say", not "the party is empty".
     */
    public fun onPartyWidget(members: List<String>) {
        if (members == widgetMembers) return
        widgetMembers = members
        recompute()
    }

    private var widgetMembers: List<String> = emptyList()

    /** Starts a ready check. Returns the check, or null when there is no party to ask. */
    public fun startReadyCheck(startedBy: String, timeout: Duration, note: String? = null): ReadyCheck? {
        if (!party.isInParty) return null
        val check = ReadyCheck(
            startedBy = startedBy,
            startedAtMillis = now(),
            deadlineMillis = now() + timeout.inWholeMilliseconds,
            // Everybody who was in the party when it started, including whoever asked: a leader who
            // forgets to answer their own check is a leader nobody is waiting on.
            responses = party.members.associate { it.name to ReadyResponse.WAITING },
            note = note,
        )
        readyCheck = check
        events.post(ReadyCheckChangedEvent(check, party), EventSource.DERIVED)
        return check
    }

    /**
     * Records a response.
     *
     * A response from somebody who was not asked is ignored rather than added: a check is over a
     * fixed set of people, and letting a latecomer answer would make "everybody is ready" mean
     * something different than it did a moment ago.
     *
     * **The first answer stands.** These arrive over a shared connection where the same message turns up
     * twice after any hiccup, and letting a resend through would have the leader watching somebody flip
     * between ready and not for reasons entirely about the network. Somebody who genuinely changed their
     * mind says so out loud, which is what a party is.
     */
    public fun recordResponse(name: String, response: ReadyResponse): ReadyCheck? {
        val check = readyCheck ?: return null
        val key = check.responses.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: return check
        if (check.responses[key] != ReadyResponse.WAITING) return check
        val updated = check.copy(responses = check.responses + (key to response))
        readyCheck = updated
        events.post(ReadyCheckChangedEvent(updated, party), EventSource.DERIVED)
        return updated
    }

    /** Expires the check if its deadline has passed. Called from a tick or a scheduled job. */
    public fun expireReadyCheckIfDue() {
        val check = readyCheck ?: return
        if (!check.hasExpired(now())) return
        val timedOut = check.timedOut()
        readyCheck = timedOut
        events.post(ReadyCheckChangedEvent(timedOut, party), EventSource.DERIVED)
    }

    /** Ends the current check. */
    public fun endReadyCheck() {
        if (readyCheck == null) return
        readyCheck = null
        events.post(ReadyCheckChangedEvent(null, party), EventSource.DERIVED)
    }

    private fun leave(reason: String) {
        if (!party.isInParty && readyCheck == null) return
        log.debug { "Party ended: $reason" }
        tracked.clear()
        trackedLeader = null
        widgetMembers = emptyList()
        readyCheck?.let { endReadyCheck() }
        update(PartyState.None)
    }

    /**
     * Merges the two sources into one state.
     *
     * The widget wins where it has something to say, for the reason in the class comment. Where it
     * does not, the accumulation stands at lower confidence.
     */
    private fun recompute() {
        val names = if (widgetMembers.isNotEmpty()) widgetMembers else tracked.toList()
        if (names.isEmpty()) {
            update(PartyState.None)
            return
        }

        val leader = trackedLeader
        val members = names.map { name ->
            PartyMember(
                name = name,
                // Resolved for convenience, and not stored anywhere durable — see PartyState.leader.
                id = players.resolveUsername(name)?.id,
                role = if (name.equals(leader, ignoreCase = true)) PartyRole.LEADER else PartyRole.MEMBER,
                // Set by the realtime layer when it exists. False until something proves otherwise,
                // because assuming otherwise means messaging into the void.
                hasSidequest = party.member(name)?.hasSidequest ?: false,
            )
        }

        update(
            PartyState(
                members = members,
                leader = leader,
                confidence = if (widgetMembers.isNotEmpty()) PartyConfidence.CONFIRMED else PartyConfidence.TRACKED,
            ),
        )
    }

    private fun update(next: PartyState) {
        val previous = party
        if (previous == next) return
        party = next
        events.post(PartyChangedEvent(previous, next), EventSource.DERIVED)
    }

    /** Releases the subscriptions. */
    public fun close() {
        if (!scope.isClosed) scope.cancel()
        events.unsubscribeAll(OWNER)
    }

    private companion object {
        val OWNER = OwnerId(dev.th7bo.sidequest.platform.id.SqId.sidequest("party"))
    }
}
