package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.SidequestSettings
import dev.th7bo.sidequest.platform.core.notification.notification
import dev.th7bo.sidequest.platform.core.settings.ContextualOverride
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.skyblock.Island
import kotlin.time.Duration.Companion.milliseconds

/**
 * A camera that looks around while the player keeps facing the crops.
 *
 * Third person, with the mouse pointed at the camera rather than at the player — so a farming run can be
 * watched from the side, from above, from wherever, without the row being cut ever changing direction. What
 * the game calls F5 gives the view; this gives the view *and* leaves the aim alone.
 *
 * **It pairs with a mouse lock rather than competing with one.** SkyHanni's lock exists so a farming run is
 * not ruined by a knocked mouse, and it works by zeroing the rotation the mouse would apply. This takes the
 * movement one step earlier, before anything has been done to it, and cancels the turn outright — so with
 * the lock on it is the only thing reading the mouse, and with the lock off it still is. Neither mod has to
 * know about the other and the order they load in does not matter.
 *
 * **The perspective is borrowed, not taken.** Turning the mode off puts back whichever view the player had,
 * through the same [ContextualOverride] the Garden's view bobbing uses, and changing perspective by hand
 * while it is on hands it back for good rather than fighting for it once a tick.
 */
class OrbitalCameraFeature(
    /** Reads the game's own perspective setting. */
    private val readPerspective: () -> String,
    /** Writes it. */
    private val writePerspective: (String) -> Unit,
    /** Turns the orbit on and off in the camera itself. */
    private val setOrbiting: (Boolean) -> Unit,
    /** Puts the camera back behind the player without leaving the mode. */
    private val recentre: () -> Unit,
    /** Hands the current settings to the camera, since a mixin may not read a config. */
    private val publishSettings: () -> Unit,
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("garden.orbital_camera"),
        displayName = "Orbital camera",
        category = FeatureCategory.VISUALS,
        description = "Looks around with the mouse without turning the player",
    )

    private lateinit var context: FeatureContext

    private val perspective = ContextualOverride<String>()

    /** Whether the player has asked for it. Separate from whether it is currently *allowed*. */
    private var requested: Boolean = false

    override fun onEnable(context: FeatureContext) {
        this.context = context

        context.command(
            name = "sqorbit",
            description = "Looks around without turning — third person, mouse on the camera",
        ) { toggle() }

        context.command(name = "sqorbitcentre", description = "Puts the orbital camera back behind you") {
            recentre()
        }

        // Polled for the same reason the Garden's bobbing is: the player can change perspective themselves
        // at any moment and the override has to notice, and leaving the island has to stand the mode down.
        context.scheduler.every(context.owner, period = CHECK, initialDelay = CHECK) { check() }
    }

    override fun onDisable() {
        stop()
    }

    private fun toggle() {
        if (requested) {
            stop()
            say("Orbital camera off")
            return
        }
        if (!isAllowed()) {
            say("The orbital camera is for the Garden.")
            return
        }
        requested = true
        publishSettings()
        check()
        say("Orbital camera on. /sqorbit again to stop, /sqorbitcentre to recentre.")
    }

    private fun stop() {
        requested = false
        setOrbiting(false)
        perspective.release(readPerspective())?.let(writePerspective)
    }

    /**
     * Keeps the mode and the perspective in step, once a tick.
     *
     * The orbit follows the *override* rather than the request: if the player takes their perspective back,
     * the override stands down, and a camera still orbiting a first-person view would be a mouse that had
     * silently stopped turning the player for no visible reason.
     */
    private fun check() {
        val wanted = requested && isAllowed()
        if (requested && !wanted) {
            stop()
            say("Orbital camera off — you left the Garden.")
            return
        }

        val desired = if (wanted) THIRD_PERSON else null
        perspective.apply(readPerspective(), desired)?.let(writePerspective)

        val orbiting = wanted && perspective.isOverriding
        publishSettings()
        setOrbiting(orbiting)
    }

    /**
     * The Garden only.
     *
     * A camera that stops the mouse turning you is a camera that gets somebody killed in a dungeon. This is
     * a farming tool and it is scoped like one.
     */
    private fun isAllowed(): Boolean = context.gameContext.island in GARDENS

    private fun say(message: String) {
        context.notifications.notify(
            notification(
                category = NotificationCategory.DEBUG,
                title = "Orbital camera",
                subtitle = message,
            ),
        )
    }

    private companion object {
        /** Both Gardens: a visit to somebody else's is the same crops from the same angle. */
        val GARDENS = setOf(Island.GARDEN, Island.GARDEN_GUEST)

        /** Minecraft's own name for the view behind the player. Compared as text, never parsed. */
        const val THIRD_PERSON = "THIRD_PERSON_BACK"

        val CHECK = 250.milliseconds
    }
}
