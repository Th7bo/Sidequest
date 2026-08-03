package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.platform.core.notification.notification
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.marker.Marker
import dev.th7bo.sidequest.platform.marker.MarkerKind
import dev.th7bo.sidequest.platform.marker.MarkerRender
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.notification.NotificationPriority
import dev.th7bo.sidequest.platform.ping.PingStyle
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import dev.th7bo.sidequest.platform.skyblock.SqPosition

/**
 * Pointing at something, so everybody else can see it.
 *
 * Two halves, and they are deliberately independent. The **local** half places a marker the instant somebody
 * pings and never waits on anything: a ping that appeared half a second late, or not at all because the
 * connection was down, would be useless for the thing pings are for. The **remote** half publishes the same
 * gesture to the group and is allowed to fail quietly.
 *
 * Receiving is not here — that is `RemoteMarkerReceiver`, and it is the only place an incoming ping becomes a
 * marker. A feature that also subscribed would be a second, slightly different idea of how long a ping lasts.
 */
class PingSystem(
    /** Where the player is aiming, or null when nothing is in range. Needs the game. */
    private val aimedAt: () -> SqPosition?,
    /** Where the player is standing, for the pings that mean "here". */
    private val standingAt: () -> SqPosition?,
    /**
     * Publishes to the group. Returns false when nothing was sent.
     *
     * A function rather than the realtime client, so this feature cannot accidentally do anything else with
     * the connection — and so the local half is testable without one.
     */
    private val publish: (location: SqLocation, style: PingStyle, label: String) -> Boolean = { _, _, _ -> false },
    private val now: () -> Long = System::currentTimeMillis,
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("ping.system"),
        displayName = "Pings",
        category = FeatureCategory.SOCIAL,
        description = "Point at something and everybody in the group sees it",
    )

    private lateinit var context: FeatureContext

    /** When the last ping went out, so the key can be held without flooding the group. */
    private var lastPingAt = 0L

    override fun onEnable(context: FeatureContext) {
        this.context = context

        context.command(
            name = "sqping",
            description = "Ping what you are looking at",
            usage = "[style] [label]",
            completions = { arguments ->
                if (arguments.size <= 1) PingStyle.entries.map { it.wireName } else emptyList()
            },
        ) { arguments ->
            val style = arguments.firstOrNull()?.let { first ->
                PingStyle.entries.firstOrNull { it.wireName.equals(first, ignoreCase = true) }
            }
            // An unrecognised first word is part of the label rather than a bad style. `/sqping over here`
            // should ping, not explain itself — somebody typing that is in a hurry by definition.
            val label = if (style == null) arguments.joinToString(" ") else arguments.drop(1).joinToString(" ")
            ping(style ?: PingStyle.GO_HERE, label)
        }
    }

    override fun onDisable() {
        context.markers.removeAll(MarkerKind.PING)
    }

    /**
     * Sends a ping at whatever the player is aiming at.
     *
     * Falls back to where they are standing. That is not a degraded case — [PingStyle.COME_TO_ME] and
     * [PingStyle.NEED_HELP] both mean "here", and aiming at the sky to say "help" would put the marker in the
     * sky.
     */
    fun ping(style: PingStyle, label: String = "") {
        val elapsed = now() - lastPingAt
        if (elapsed < COOLDOWN_MILLIS) {
            // Silent. A cooldown that complained would be noisier than the flood it prevents, and the key is
            // meant to be mashed.
            context.log.trace { "Ping suppressed, ${COOLDOWN_MILLIS - elapsed}ms left" }
            return
        }

        val position = if (style.meansHere) standingAt() ?: aimedAt() else aimedAt() ?: standingAt()
        if (position == null) {
            context.log.debug { "Nothing to ping at" }
            return
        }
        lastPingAt = now()

        val location = SqLocation(
            context.gameContext.island,
            position,
            context.gameContext.context.profile,
        )

        // Locally first, always. The group may not hear about it — the connection may be down, or nobody may
        // be online — and a ping that did not appear on the sender's own screen would read as broken.
        placeLocal(location, style, label)

        if (!publish(location, style, label.take(MAX_LABEL))) {
            context.log.debug { "Ping shown locally; nothing was sent" }
        }
    }

    /**
     * The sender's own copy.
     *
     * Under a fixed id, so pinging twice moves the marker rather than leaving two. That matches what an
     * incoming ping does per sender, which is what makes a ping mean "look *there*" instead of "look at both".
     */
    private fun placeLocal(location: SqLocation, style: PingStyle, label: String) {
        context.markers.place(
            Marker(
                id = LOCAL_ID,
                kind = MarkerKind.PING,
                location = location,
                label = label.take(MAX_LABEL).ifEmpty { style.displayName },
                colour = style.colour,
                lifetime = style.lifetime,
                createdAtMillis = now(),
                render = MarkerRender.Minimal.copy(edgeIndicator = true),
            ),
        )

        // The urgent ones say so on a toast as well. Somebody looking at their inventory when a friend pings
        // "danger" is exactly the person who needs to be told, and a beam behind an open screen is not
        // telling them.
        if (style.isUrgent) {
            context.notifications.notify(
                notification(
                    category = NotificationCategory.SOCIAL,
                    title = style.displayName,
                    subtitle = label.ifEmpty { "at ${location.island.displayName}" },
                    priority = NotificationPriority.HIGH,
                ),
            )
        }
    }

    private companion object {
        /** One id for the local ping. See [placeLocal]. */
        const val LOCAL_ID = "ping.self"

        const val MAX_LABEL = 48

        /**
         * How often a ping may be sent.
         *
         * Half a second. Slow enough that holding the key does not flood the group, fast enough that two
         * deliberate pings in quick succession both land — which people do, pointing at one thing and then
         * another.
         */
        const val COOLDOWN_MILLIS = 500L
    }
}

/** Whether a style means "where I am" rather than "where I am pointing". */
private val PingStyle.meansHere: Boolean
    get() = this == PingStyle.COME_TO_ME || this == PingStyle.NEED_HELP
