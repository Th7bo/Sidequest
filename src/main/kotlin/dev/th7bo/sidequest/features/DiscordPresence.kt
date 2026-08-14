package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.platform.core.notification.notification
import dev.th7bo.sidequest.platform.core.presence.DiscordIpcClient
import dev.th7bo.sidequest.platform.core.presence.DiscordIpcException
import dev.th7bo.sidequest.platform.core.presence.DiscordPipe
import dev.th7bo.sidequest.platform.core.presence.DiscordSockets
import dev.th7bo.sidequest.platform.core.presence.PresenceComposer
import dev.th7bo.sidequest.platform.core.presence.PresenceSettings
import dev.th7bo.sidequest.platform.core.presence.RichPresence
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.scheduler.SchedulerThread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What the player is doing, on their Discord profile.
 *
 * Two clocks, on two threads, and the split is the design rather than an implementation detail.
 *
 * The **client thread** reads the game and composes: where the player is becomes two lines of text and a
 * pair of asset keys, in [PresenceComposer], and the result is handed over as one immutable value. Nothing
 * on this side blocks or touches a socket.
 *
 * The **background thread** owns the connection: it opens the pipe, holds it, and pushes the latest composed
 * value when it differs from the last one sent. Every call it makes can block indefinitely — a Discord that
 * has stopped reading its own socket is not a rare state — and the one rule is that none of them may ever be
 * made from the thread drawing frames.
 *
 * **Discord not running is the normal case**, not a failure. Most sessions have no Discord, and this
 * retries on a widening backoff and says nothing rather than logging every thirty seconds forever.
 */
class DiscordPresence(
    /** Read on the client thread, once per compose. */
    private val settings: () -> PresenceSettings,
    /** Injected so the connection can be pointed somewhere other than a real Discord. */
    private val openPipe: () -> DiscordPipe = DiscordSockets::open,
    private val now: () -> Long = System::currentTimeMillis,
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("discord.presence"),
        displayName = "Discord rich presence",
        category = FeatureCategory.SOCIAL,
        description = "Shows what you are doing in SkyBlock on your Discord profile",
    )

    private lateinit var context: FeatureContext

    // -- shared between the two threads --------------------------------------

    /**
     * The presence the client thread last composed, or null for "take it down".
     *
     * The only state crossing the thread boundary, and it is an immutable value written by one side and read
     * by the other. Volatile is enough precisely because it is a whole value: there is no moment at which a
     * reader can see half of it.
     */
    @Volatile
    private var desired: RichPresence? = null

    /** The settings as the client thread last read them. Same reasoning as [desired]. */
    @Volatile
    private var current: PresenceSettings = PresenceSettings()

    // -- client thread only --------------------------------------------------

    /**
     * When this session reached Hypixel, in epoch seconds, or null when it has not.
     *
     * Hypixel rather than SkyBlock, and rather than the game launching. It is what the elapsed clock counts
     * from, and "2h 15m" reads as time spent playing SkyBlock — which stops being true if it survives a
     * disconnect, so leaving clears it.
     */
    private var sessionStartSeconds: Long? = null

    // -- background thread only ----------------------------------------------

    private var client: DiscordIpcClient? = null

    /** Which application the live connection was opened for, so a changed id reconnects. */
    private var connectedTo: String? = null

    private var lastSent: RichPresence? = null
    private var hasSent: Boolean = false
    private var lastSentAt: Long = 0

    /**
     * arRPC acknowledges an activity before Vesktop has finished resolving its image assets. A clear sent
     * while that lookup is in flight can therefore arrive first, after which the older activity resurrects
     * itself. Repeat clears for a short bounded window so the final update is unambiguously the clear.
     */
    private var repeatClearUntil: Long = 0

    private var failures: Int = 0
    private var nextAttemptAt: Long = 0

    /** The last thing that went wrong, for `/sqrpc`. Volatile because the command reads it from the client. */
    @Volatile
    private var lastError: String? = null

    @Volatile
    private var isConnected: Boolean = false

    override fun onEnable(context: FeatureContext) {
        this.context = context

        context.scheduler.every(context.owner, period = COMPOSE, initialDelay = COMPOSE) { compose() }
        context.scheduler.every(
            context.owner,
            period = PUSH,
            initialDelay = PUSH,
            thread = SchedulerThread.ASYNC,
        ) { push() }

        context.command(
            name = "sqrpc",
            description = "Whether Discord rich presence is working, and what it is showing",
        ) { report() }
    }

    /**
     * Drops the connection.
     *
     * **Closing the pipe is what unblocks the background thread**, and that is why this exists rather than
     * relying on the scheduler cancelling the job. Cancelling a coroutine does not interrupt a thread parked
     * in a blocking socket read; closing the handle underneath it makes that read fail, which is the only
     * way out of a Discord that has stopped answering.
     *
     * Closing also clears the presence, which is what leaving the game should do.
     */
    override fun onDisable() {
        runCatching { client?.close() }
        client = null
        connectedTo = null
        isConnected = false
        desired = null
        sessionStartSeconds = null
        hasSent = false
        lastSent = null
    }

    // -- the client thread ---------------------------------------------------

    /** Turns where the player is into what Discord should show. Cheap: string building, nothing else. */
    private fun compose() {
        val settings = settings()
        current = settings

        if (!settings.isUsable) {
            desired = null
            sessionStartSeconds = null
            return
        }

        val game = context.gameContext.context
        if (!game.isOnHypixel) {
            sessionStartSeconds = null
        } else if (sessionStartSeconds == null) {
            sessionStartSeconds = now() / MILLIS_PER_SECOND
        }

        desired = PresenceComposer.compose(
            context = game,
            party = context.party.party,
            disclosure = settings.disclosure,
            sessionStartedAtEpochSeconds = sessionStartSeconds,
        )
    }

    // -- the background thread -----------------------------------------------

    /**
     * Keeps the connection in the state the settings ask for, and pushes what changed.
     *
     * Everything here may block. The scheduler awaits each run before starting the next, so a slow round
     * trip delays the next push rather than stacking a second one on top of it.
     */
    private fun push() {
        val settings = current
        if (!settings.isUsable) {
            if (client != null) disconnect()
            return
        }

        // A changed application id is a different Discord application, not a reconfiguration of this one.
        if (client != null && connectedTo != settings.applicationId) disconnect()

        val live = client
        if (live == null || !live.isConnected) {
            connect(settings.applicationId)
            return
        }

        val target = desired
        val now = now()
        val isRepeatingClear = target == null && hasSent && now < repeatClearUntil
        if (target == lastSent && (hasSent || target == null) && !isRepeatingClear) return
        // The floor. Discord rate-limits presence updates, and a sub-location that changes every few seconds
        // while walking across an island would otherwise be the loudest thing on this connection.
        if (hasSent && now - lastSentAt < MINIMUM_INTERVAL.inWholeMilliseconds) return

        try {
            val beganClear = target == null && hasSent && lastSent != null
            live.setActivity(target)
            lastSent = target
            hasSent = true
            lastSentAt = now
            repeatClearUntil = when {
                beganClear -> now + CLEAR_RETRY_WINDOW.inWholeMilliseconds
                target != null -> 0
                else -> repeatClearUntil
            }
            lastError = null
        } catch (e: DiscordIpcException) {
            note(e)
            disconnect()
        }
    }

    private fun connect(applicationId: String) {
        if (now() < nextAttemptAt) return

        val attempt = DiscordIpcClient(applicationId = applicationId, openPipe = openPipe)
        try {
            attempt.connect()
        } catch (e: DiscordIpcException) {
            note(e)
            runCatching { attempt.close() }
            failures++
            nextAttemptAt = now() + backoff().inWholeMilliseconds
            return
        }

        client = attempt
        connectedTo = applicationId
        isConnected = true
        failures = 0
        nextAttemptAt = 0
        lastError = null
        // Forgotten on purpose: a fresh connection has no presence on it, so whatever was last sent down the
        // previous one says nothing about what this one is showing.
        lastSent = null
        hasSent = false
        repeatClearUntil = 0
        context.log.info { "Discord rich presence connected" }
    }

    private fun disconnect() {
        runCatching { client?.close() }
        client = null
        connectedTo = null
        isConnected = false
        lastSent = null
        hasSent = false
        repeatClearUntil = 0
    }

    /**
     * Records why a connection failed, loudly or quietly.
     *
     * Discord not running is the ordinary state of most sessions, so it is a debug line. Discord running and
     * refusing the application id is a real misconfiguration that produces silence otherwise, so it is a
     * warning — and it is logged once per state change rather than once per attempt, because a message
     * repeated every fifteen seconds is one nobody reads.
     */
    private fun note(failure: DiscordIpcException) {
        val message = failure.message ?: failure::class.simpleName ?: "unknown"
        val isNew = message != lastError
        lastError = message
        when {
            failure.isExpected -> context.log.debug { "Discord rich presence: $message" }
            isNew -> context.log.warn { "Discord rich presence: $message" }
            else -> context.log.debug { "Discord rich presence: $message" }
        }
    }

    /** Widening, capped. Most machines never have Discord running, and this must cost them nothing. */
    private fun backoff(): Duration = BACKOFF.getOrElse(failures - 1) { BACKOFF.last() }

    // -- telling somebody why it is not working ------------------------------

    /**
     * The state of the connection, in the terms somebody debugging it needs.
     *
     * This exists because every failure mode of this feature looks identical from the outside: nothing
     * appears on the profile. Whether that is a missing application id, a Discord that is not running, a
     * sandbox that hides the socket, or a presence composed down to nothing by the privacy switches is not a
     * distinction anybody can make by looking.
     */
    private fun report() {
        val settings = current
        val presence = desired

        val title = when {
            !settings.isEnabled -> "Off"
            settings.applicationId.isBlank() -> "No application id"
            isConnected -> "Connected"
            else -> "Not connected"
        }

        val subtitle = when {
            !settings.isEnabled -> "Turn it on under Network · Discord in the settings."
            // Unreachable while the mod ships an application of its own — an empty field resolves to the
            // shipped id. Kept because that is a constant somebody could empty, and a diagnostic that
            // silently stops covering a state is worse than one that never covered it.
            settings.applicationId.isBlank() ->
                "No Discord application is configured, and Sidequest ships no default."
            isConnected && presence == null -> "Connected, showing nothing — you are not on Hypixel."
            isConnected -> listOfNotNull(presence?.details, presence?.state).joinToString(" / ")
            else -> lastError ?: "Looking for Discord."
        }

        context.notifications.notify(
            notification(
                category = NotificationCategory.DEBUG,
                title = "Discord presence: $title",
                subtitle = subtitle,
            ),
        )
    }

    private companion object {
        /** How often the game is read. Cheap enough to do often, and a presence should not lag by much. */
        val COMPOSE = 2.seconds

        /** How often the connection is tended. */
        val PUSH = 3.seconds

        /**
         * The floor between two updates.
         *
         * Five seconds, which is what a shipping mod uses against the same rate limit. The push tick is
         * shorter so that a change is picked up promptly; this is what stops a burst of them going out.
         */
        val MINIMUM_INTERVAL = 5.seconds

        /** Long enough to outlast Vesktop's asynchronous application and asset lookups. */
        val CLEAR_RETRY_WINDOW = 15.seconds

        /** How long to wait after each successive failed connection. */
        val BACKOFF = listOf(15.seconds, 30.seconds, 60.seconds, 120.seconds, 300.seconds)

        const val MILLIS_PER_SECOND = 1000L
    }
}
