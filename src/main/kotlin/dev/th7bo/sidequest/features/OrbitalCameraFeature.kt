package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.SidequestSettings
import dev.th7bo.sidequest.platform.chat.ChatMessageEvent
import dev.th7bo.sidequest.platform.core.garden.FarmingStreak
import dev.th7bo.sidequest.platform.core.garden.PestChat
import dev.th7bo.sidequest.platform.core.notification.notification
import dev.th7bo.sidequest.platform.core.settings.ContextualOverride
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.feature.listen
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.skyblock.Island
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

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
    /** How many blocks the player has broken, ever. Differenced here into "since the last one". */
    private val blocksBroken: () -> Long,
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

    private val streak = FarmingStreak()

    /** A fixed point to measure from, so the streak's arithmetic is on plain durations. */
    private val since = TimeSource.Monotonic.markNow()
    private var seenBlocks: Long = 0

    /**
     * True once the run started the camera by itself, so stopping it knows which of the two to undo.
     *
     * Without the distinction, turning it off by hand during a run would look identical to turning off a
     * run that had ended, and the next block broken would switch it straight back on.
     */
    private var startedByRun: Boolean = false

    /**
     * Set when the player turns it off during a run they did not start.
     *
     * The same shape as the perspective override standing down, and for the same reason: a mod that is
     * overruled has to stay overruled until the condition lapses, or the player and the mod spend the rest
     * of the run toggling it at each other.
     */
    private var stoodDownFor: Boolean = false

    override fun onEnable(context: FeatureContext) {
        this.context = context

        context.command(
            name = "sqorbit",
            description = "Looks around without turning — third person, mouse on the camera",
        ) { toggle() }

        context.command(name = "sqorbitcentre", description = "Puts the orbital camera back behind you") {
            recentre()
        }

        context.listen<ChatMessageEvent> { event ->
            if (PestChat.isSpawn(event.message.clean)) onPestSpawned()
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
            // Turning off a run the mod started means "not this run", not "never again".
            if (startedByRun) stoodDownFor = true
            stop()
            say("Orbital camera off")
            return
        }
        if (!isAllowed()) {
            say("The orbital camera is for the Garden.")
            return
        }
        requested = true
        startedByRun = false
        stoodDownFor = false
        publishSettings()
        check()
        say("Orbital camera on. /sqorbit again to stop, /sqorbitcentre to recentre.")
    }

    private fun stop() {
        requested = false
        startedByRun = false
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
        followTheRun()

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
     * Puts the camera away when a pest turns up.
     *
     * A pest is the one thing on the Garden that needs looking at rather than farming through: it has to be
     * found and killed, and doing that behind a camera pointed somewhere else is worse than doing it with no
     * camera help at all. So the run is ended outright rather than stood down — going back to farming earns
     * the camera again the same way it did the first time.
     *
     * It stops a camera the player turned on by hand too. "Stop when pests spawn" is a claim about the
     * pest, not about how the camera got there.
     */
    private fun onPestSpawned() {
        if (!SidequestSettings.Garden.orbitStopOnPests) return
        if (!isAllowed()) return

        streak.reset()
        stoodDownFor = false
        if (!requested) return

        stop()
        say("Orbital camera off — a pest spawned.")
    }

    /**
     * Starts and stops the camera with the farming run, when that is switched on.
     *
     * The threshold is on blocks rather than time because that is what tells a run from a stray swing —
     * clearing a path is four blocks, a run is hundreds. A run ending puts the camera away again, which is
     * the half that makes this bearable: a camera that turned itself on and then stayed on would be worse
     * than one that never did.
     */
    private fun followTheRun() {
        if (!SidequestSettings.Garden.orbitAutoStart || !isAllowed()) {
            if (startedByRun) {
                stop()
                say("Orbital camera off — the run ended.")
            }
            streak.reset()
            stoodDownFor = false
            seenBlocks = blocksBroken()
            return
        }

        val now = since.elapsedNow()
        val broken = blocksBroken()
        repeat((broken - seenBlocks).coerceIn(0, MAX_CATCH_UP).toInt()) { streak.record(now) }
        seenBlocks = broken

        val farming = streak.hasReached(SidequestSettings.Garden.orbitAutoStartBlocks, now)
        if (!farming) {
            // The run is over, which is also what clears a stand-down: the next one starts fresh.
            stoodDownFor = false
            if (startedByRun) {
                stop()
                say("Orbital camera off — the run ended.")
            }
            return
        }
        if (requested || stoodDownFor) return

        requested = true
        startedByRun = true
        publishSettings()
        say("Orbital camera on — ${SidequestSettings.Garden.orbitAutoStartBlocks} blocks in. /sqorbit to stop.")
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

        /**
         * A ceiling on how many blocks one poll may account for.
         *
         * The counter keeps running while this is not looking — through a warp, a disconnect, a spell in a
         * menu — and replaying all of it would make a run out of blocks broken somewhere else entirely.
         */
        const val MAX_CATCH_UP = 64L
    }
}
