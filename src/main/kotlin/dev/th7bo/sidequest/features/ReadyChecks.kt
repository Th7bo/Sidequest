package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.platform.core.backend.RealtimeMessageReceivedEvent
import dev.th7bo.sidequest.platform.core.notification.notification
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.notification.NotificationAction
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.notification.NotificationPriority
import dev.th7bo.sidequest.platform.party.ReadyCheck
import dev.th7bo.sidequest.platform.party.ReadyCheckChangedEvent
import dev.th7bo.sidequest.platform.party.ReadyCheckOutcome
import dev.th7bo.sidequest.platform.party.ReadyResponse
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.protocol.RealtimePayload
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * "Is everybody ready?", asked once instead of five times.
 *
 * The state is the party service's — it already owns the check, because both a command and a GUI can start
 * one and two of them keeping their own copy is how they disagree. What is here is everything around it:
 * asking, answering, and telling the leader when there is something to know.
 *
 * **The leader is told once per outcome, not once per response.** A five-person party produces five events
 * and four of them are "still waiting"; a toast for each would be the feature people mute. So the outcome is
 * watched, and only a change in it is worth interrupting somebody for.
 */
class ReadyChecks(
    /** Who we are, by name — the party speaks in names, because that is what Hypixel gives it. */
    private val localName: () -> String?,
    /**
     * Starts one locally. Returns false when there is no party to ask.
     *
     * Takes who asked, because a check arriving from somebody else has to be started here too — see
     * [remoteStarted] — and recording it as ours would put the wrong name on it.
     */
    private val start: (startedBy: String, timeout: Duration, note: String?) -> Boolean,
    /** Records our own answer, and returns whether it was accepted. */
    private val answer: (name: String, response: ReadyResponse) -> Boolean,
    /** Publishes to the group. False when nothing was sent. */
    private val publishStart: (check: ReadyCheck) -> Boolean = { false },
    private val publishAnswer: (isReady: Boolean) -> Boolean = { false },
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("party.ready_check"),
        displayName = "Ready checks",
        category = FeatureCategory.SOCIAL,
        description = "Asks the party whether everybody is ready, once",
    )

    private lateinit var context: FeatureContext

    /**
     * The outcome the leader was last told about.
     *
     * Held so that repeated events reporting the same thing say nothing. Null while no check is running,
     * which is also what resets it — a new check starts the reporting over.
     */
    private var reported: ReadyCheckOutcome? = null

    override fun onEnable(context: FeatureContext) {
        this.context = context

        context.listen(ReadyCheckChangedEvent::class) { event -> onChanged(event.check) }

        // The stream, read here rather than in a receiver of its own. A ready check is one feature's whole
        // subject, unlike a marker — which has a shared receiver precisely because several features place
        // them and they must not each invent a lifetime.
        context.listen(RealtimeMessageReceivedEvent::class) { received ->
            when (val payload = received.message.payload) {
                is RealtimePayload.ReadyCheckStarted ->
                    remoteStarted(senderName(received.message.senderMinecraftUuid), payload.note)
                is RealtimePayload.ReadyCheckResponse ->
                    remoteAnswer(senderName(received.message.senderMinecraftUuid), payload.ready)
                else -> Unit
            }
        }

        context.command(
            name = "sqready",
            description = "Ask the party if everybody is ready, or answer",
            usage = "[yes|no|<note>]",
            completions = { arguments -> if (arguments.size <= 1) VERBS else emptyList() },
        ) { arguments -> handle(arguments) }
    }

    override fun onDisable() {
        reported = null
    }

    private fun handle(arguments: List<String>) {
        when (arguments.firstOrNull()?.lowercase()) {
            "yes", "y", "ready" -> respond(isReady = true)
            "no", "n", "decline" -> respond(isReady = false)
            // Anything else is what the check is *for* — `/sqready f7 carry` asks with a note. A verb list
            // somebody has to learn before they can ask a question is a verb list nobody learns.
            else -> ask(arguments.joinToString(" ").ifBlank { null })
        }
    }

    // -- asking --------------------------------------------------------------

    private fun ask(note: String?) {
        val me = localName() ?: ""
        if (!start(me, TIMEOUT, note)) {
            say("Nobody to ask", "You are not in a party.")
            return
        }
        reported = null
        val check = context.party.readyCheck
        val shared = check != null && publishStart(check)
        say(
            "Asked ${check?.responses?.size ?: 0} people",
            // Said out loud rather than logged. A ready check nobody else received looks identical to one
            // everybody ignored, and the difference is the whole point — one is a party not paying
            // attention and the other is the mod not being connected to anything.
            if (shared) note ?: "They have ${TIMEOUT.inWholeSeconds}s." else NOT_CONNECTED,
        )
    }

    // -- answering -----------------------------------------------------------

    private fun respond(isReady: Boolean) {
        if (context.party.readyCheck == null) {
            say("Nothing to answer", "No ready check is running.")
            return
        }
        val me = localName()
        if (me == null) {
            say("Cannot answer", "Join a world first.")
            return
        }

        val accepted = answer(me, if (isReady) ReadyResponse.READY else ReadyResponse.DECLINED)
        if (!accepted) {
            // The service refuses a second answer on purpose. Saying so is better than a silent no-op that
            // looks like the command is broken.
            say("Already answered", "Your first answer stands.")
            return
        }
        val shared = publishAnswer(isReady)
        say(if (isReady) "Ready" else "Not ready", if (shared) "" else NOT_CONNECTED)
    }

    // -- telling the leader --------------------------------------------------

    private fun onChanged(check: ReadyCheck?) {
        if (check == null) {
            reported = null
            return
        }
        val outcome = check.outcome()
        if (outcome == reported) return
        reported = outcome

        // Pending is the normal state and is not news. It is recorded above so that arriving *back* at
        // pending — which cannot happen today, and might if a check is ever restarted — would report again.
        if (outcome == ReadyCheckOutcome.PENDING) return

        context.notifications.notify(
            notification(
                category = NotificationCategory.SOCIAL,
                title = titleFor(outcome),
                subtitle = detailFor(check, outcome),
                priority = if (outcome == ReadyCheckOutcome.ALL_READY) {
                    NotificationPriority.NORMAL
                } else {
                    // Something is wrong with the plan, and the leader is about to pull anyway.
                    NotificationPriority.HIGH
                },
            ).copy(
                actions = listOf(
                    NotificationAction(id = "dismiss", label = "Done") { clear() },
                ),
            ),
        )
    }

    private fun titleFor(outcome: ReadyCheckOutcome): String = when (outcome) {
        ReadyCheckOutcome.ALL_READY -> "Everybody is ready"
        ReadyCheckOutcome.SOMEBODY_DECLINED -> "Somebody is not ready"
        ReadyCheckOutcome.TIMED_OUT -> "No answer from everybody"
        ReadyCheckOutcome.PENDING -> "Waiting"
    }

    /** Names people rather than counting them: a party is five, and "two declined" is not actionable. */
    private fun detailFor(check: ReadyCheck, outcome: ReadyCheckOutcome): String = when (outcome) {
        ReadyCheckOutcome.ALL_READY -> "${check.readyCount} of ${check.responses.size}"
        ReadyCheckOutcome.SOMEBODY_DECLINED -> check.declinedBy().joinToString(", ") + " declined"
        ReadyCheckOutcome.TIMED_OUT -> "Nothing from " + check.silent().joinToString(", ")
        ReadyCheckOutcome.PENDING -> "Waiting on " + check.waitingOn().joinToString(", ")
    }

    /** Ends the check. Exposed for the notification's action and for whatever ends one from a GUI. */
    var clear: () -> Unit = {}

    /** Records somebody else's answer, from the realtime stream. */
    fun remoteAnswer(name: String, isReady: Boolean) {
        answer(name, if (isReady) ReadyResponse.READY else ReadyResponse.DECLINED)
    }

    /**
     * Somebody else started one.
     *
     * Shown rather than started: this client's party service builds its own check from its own view of the
     * party, and two clients each inventing a member list is how they disagree about who was asked. What
     * arrives is the prompt, and answering is what goes back.
     */
    fun remoteStarted(from: String, note: String?) {
        // A local check, from this client's own view of the party. Without one there is nothing to answer
        // *into*: the prompt appeared, the buttons did nothing, and `/sqready yes` said "no ready check is
        // running" — which is what happens when a rationale gets written and the code behind it does not.
        //
        // Built here rather than taken from the message on purpose, and the payload carries no member list
        // for the same reason: two clients each shipping who they think is in the party is how they come to
        // disagree about it.
        if (!start(from, TIMEOUT, note)) {
            context.log.debug { "$from asked, but this client sees no party" }
            return
        }
        reported = null

        context.notifications.notify(
            notification(
                category = NotificationCategory.SOCIAL,
                title = "$from asks: ready?",
                subtitle = note ?: "Answer with /sqready yes or no",
                priority = NotificationPriority.HIGH,
            ).copy(
                actions = listOf(
                    NotificationAction(id = "ready", label = "Ready") { respond(isReady = true) },
                    NotificationAction(id = "decline", label = "Not yet") { respond(isReady = false) },
                ),
            ),
        )
    }

    /**
     * Who sent a message, by name.
     *
     * The envelope's asserted UUID resolved through the directory. Falls back to a placeholder rather than
     * dropping the message: an unnameable sender still answered, and the check has to count them.
     */
    private fun senderName(uuid: String?): String {
        val id = uuid?.let { PlayerId.parse(it) } ?: return "Somebody"
        return context.players.byId(id)?.displayName ?: "Somebody"
    }

    private fun say(title: String, subtitle: String = "") {
        context.notifications.notify(
            notification(category = NotificationCategory.SOCIAL, title = title, subtitle = subtitle),
        )
    }

    private companion object {
        val VERBS = listOf("yes", "no")

        /** What to say when the group cannot hear it. Named once so both places word it the same. */
        const val NOT_CONNECTED = "Only on your client — Sidequest is not connected to the group."

        /**
         * How long people get.
         *
         * Thirty seconds. Long enough to alt-tab back and answer, short enough that a leader waiting on one
         * silent person is not stuck behind somebody who left an hour ago.
         */
        val TIMEOUT = 30.seconds
    }
}
