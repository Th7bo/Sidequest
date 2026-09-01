package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.SidequestSettings
import dev.th7bo.sidequest.platform.core.afk.CameraPose
import dev.th7bo.sidequest.platform.core.afk.IdleWatch
import dev.th7bo.sidequest.platform.core.notification.notification
import dev.th7bo.sidequest.platform.core.settings.ContextualOverride
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The camera a big game shows when you put the controller down.
 *
 * After a while with nobody at the keyboard, the view leaves the player's eyes and cuts between shots — over
 * the shoulder, in front, overhead, a long orbit — with bars top and bottom. The instant anything happens it
 * eases straight back to exactly where it was.
 *
 * **Two questions, and only one of them is about cameras.** Whether somebody is away is
 * [IdleWatch]'s, and whether now is a moment to take the screen is already answered once for the whole mod
 * by the cinematic director's safety gate — the same gate that stops a rare-drop animation covering the view
 * at a boss. Asking it here rather than inventing a second set of conditions is the point: there is one
 * definition of "do not interrupt" and this is not allowed to have its own.
 *
 * That gate is also checked *while* the reel runs, not only before it starts. Being away is not a promise
 * that nothing will happen — something hits you on a public island and the camera has to be gone before you
 * are, which is the difference between a toy and something that can be left on.
 *
 * **The perspective is borrowed, not taken**, through the same [ContextualOverride] the Garden's view
 * bobbing uses: whatever view the player had comes back, and changing it by hand while the reel is running
 * ends the reel rather than fighting for it once a tick.
 */
class AfkCinematics(
    /** Where the player is and which way they are facing, or null outside a world. */
    private val pose: () -> CameraPose?,
    /** Reads the game's own perspective setting. */
    private val readPerspective: () -> String,
    /** Writes it. */
    private val writePerspective: (String) -> Unit,
    /** Starts the reel in the camera itself. */
    private val startShots: (shotLength: Duration, letterbox: Boolean) -> Unit,
    /** Asks the reel to hand the view back over its own transition. */
    private val releaseShots: () -> Unit,
    /** Ends the reel now, wherever it had got to. */
    private val stopShots: () -> Unit,
    /** Whether the camera is back where the player left it. */
    private val isSettled: () -> Boolean,
    /** The name of the shot on screen, for the debug line. */
    private val shotName: () -> String?,
    /** Whether another Sidequest feature already has the camera. */
    private val isCameraBusy: () -> Boolean,
    /** How many blocks the player has broken, ever. A sign of life the pose alone would miss. */
    private val blocksBroken: () -> Long,
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("cinematics.afk"),
        displayName = "AFK cinematics",
        category = FeatureCategory.VISUALS,
        description = "Cuts between camera shots once you have been away for a while",
    )

    private lateinit var context: FeatureContext

    private val perspective = ContextualOverride<String>()

    private val idle = IdleWatch()

    /** A fixed point to measure from, so the idle arithmetic is on plain durations. */
    private val since = TimeSource.Monotonic.markNow()

    private var seenBlocks: Long = 0

    private var running: Boolean = false

    /** True between asking for the view back and the camera having actually arrived. */
    private var releasing: Boolean = false

    /** When that ask happened, so an exit that never finishes still ends. */
    private var releasedAt: Duration = Duration.ZERO

    /**
     * Set when the player turns it off by hand while still standing still.
     *
     * Without it, `/sqafk` while away would stop the reel and the next poll — a quarter of a second later,
     * with the idle clock still long past the threshold — would start it again. Cleared by the next sign of
     * life, so "off" means "not this time" rather than "never again".
     */
    private var stoodDown: Boolean = false

    /** Set by `/sqafk`, consumed by the next poll. See [startOnDemand] for why it is not acted on at once. */
    private var requested: Boolean = false

    override fun onEnable(context: FeatureContext) {
        this.context = context
        seenBlocks = blocksBroken()

        context.command(
            name = "sqafk",
            description = "Starts the AFK camera now, or puts it away",
        ) { toggle() }

        // Polled rather than driven by an event, because the thing being watched is an *absence*: there is no
        // callback for somebody having stopped doing anything. Four times a second is far more often than the
        // decision needs and cheap enough not to matter — the reel itself runs off the render clock.
        context.scheduler.every(context.owner, period = CHECK, initialDelay = CHECK) { check() }
    }

    override fun onDisable() {
        stop()
    }

    private fun check() {
        val now = since.elapsedNow()
        val stirred = observe(now)
        if (stirred) stoodDown = false

        if (releasing) {
            // The deadline is not belt and braces. The reel advances from the render clock, so a world that
            // went away mid-exit — a disconnect, a kick — means it never finishes and the perspective is
            // never handed back. Waiting for a frame that is not coming is how a mod leaves somebody in
            // third person with no idea why.
            if (isSettled() || now - releasedAt >= RELEASE_DEADLINE) {
                releasing = false
                stopShots()
                giveBackPerspective()
            }
            return
        }

        if (requested) {
            requested = false
            startOnDemand()
            return
        }

        if (running) {
            if (stirred || shouldStop()) release()
            return
        }

        if (!SidequestSettings.Afk.isEnabled || stoodDown) return
        if (!idle.hasBeenIdleFor(SidequestSettings.Afk.after, now)) return
        if (refusal() != null) return
        begin()
    }

    /** Records this poll's signs of life, and says whether there were any. */
    private fun observe(now: Duration): Boolean {
        var active = idle.observe(pose(), now)

        // Broken blocks as well as the pose, because an autoclicker on a crop is somebody's session running
        // and the pose barely moves. The counter keeps running while this is not looking, so any change at
        // all is the signal rather than how much it changed by.
        val broken = blocksBroken()
        if (broken != seenBlocks) {
            seenBlocks = broken
            idle.stir(now)
            active = true
        }
        return active
    }

    /**
     * Whether the reel has to end for a reason other than somebody having moved.
     *
     * Movement is handled by the caller and is deliberately silent: somebody who came back does not need to
     * be told they came back. What is left is the case that matters — the same safety gate the whole mod
     * uses, which covers a screen opening, a mob landing a hit, a dungeon starting and the world going away.
     *
     * It renews the perspective hold on the way past, which is the cheapest place to notice the player
     * having taken their own view back: pressing F5 moves nothing, so no amount of watching the pose sees it.
     */
    private fun shouldStop(): Boolean =
        !SidequestSettings.Afk.isEnabled ||
            !context.cinematics.safety().isSafe ||
            !holdPerspective()

    /** Why the reel cannot start, or null when it can. The wording is what `/sqafk` reports back. */
    private fun refusal(): String? {
        if (isCameraBusy()) return "another Sidequest camera has the view"
        val safety = context.cinematics.safety()
        return if (safety.isSafe) null else safety.explain()
    }

    /**
     * Starts it because somebody asked, a poll after they asked.
     *
     * The delay is the point. `/sqafk` runs while the chat screen is still open — the game closes it after
     * the line has been handled — so deciding on the spot would consult the safety gate at the one moment it
     * is guaranteed to say no.
     */
    private fun startOnDemand() {
        if (running || releasing) return
        stoodDown = false

        val reason = refusal()
        if (reason != null) {
            say("Not now — $reason.")
            return
        }
        begin()
        if (running) {
            say("AFK camera on${shotName()?.let { " — $it" } ?: ""}. Move to end it, /sqafk to stop.")
        } else {
            say("The game would not give up the camera.")
        }
    }

    private fun begin() {
        if (!holdPerspective()) return
        running = true
        startShots(SidequestSettings.Afk.shotLength, SidequestSettings.Afk.letterbox)
    }

    /** Asks for the view back and waits for it. The perspective goes back once the camera has arrived. */
    private fun release() {
        if (!running) return
        running = false
        releasing = true
        releasedAt = since.elapsedNow()
        releaseShots()
    }

    /** Ends everything now. For the feature being disabled, where there is no next poll to finish in. */
    private fun stop() {
        running = false
        releasing = false
        requested = false
        stopShots()
        giveBackPerspective()
    }

    private fun giveBackPerspective() {
        perspective.release(readPerspective())?.let(writePerspective)
    }

    /**
     * Holds the game in third person, and says whether it is there.
     *
     * The return value is what the perspective *reads* rather than whether this is overriding, and the
     * difference is the case that would otherwise be silently broken: somebody already in third person needs
     * nothing written and nothing remembered, and asking whether the override is in force would say no and
     * refuse to start the reel for the one player who was already set up for it.
     */
    private fun holdPerspective(): Boolean {
        val current = readPerspective()
        val write = perspective.apply(current, THIRD_PERSON)
        write?.let(writePerspective)
        return (write ?: current) == THIRD_PERSON
    }

    private fun toggle() {
        if (running || releasing || requested) {
            // Turning it off while still standing still means "not this time", not "never again": without
            // the stand-down the next poll would find the idle clock still well past the threshold and start
            // the whole thing again a quarter of a second later.
            stoodDown = true
            stop()
            say("AFK camera off. It comes back once you have been still a while.")
            return
        }
        if (!SidequestSettings.Afk.isEnabled) {
            say("AFK cinematics are switched off in the settings.")
            return
        }
        requested = true
    }

    private fun say(message: String) {
        context.notifications.notify(
            notification(
                category = NotificationCategory.DEBUG,
                title = "AFK camera",
                subtitle = message,
            ),
        )
    }

    private companion object {
        /** Minecraft's own name for the view behind the player. Compared as text, never parsed. */
        const val THIRD_PERSON = "THIRD_PERSON_BACK"

        val CHECK = 250.milliseconds

        /** Comfortably longer than the reel's own transition, and short enough not to be noticed. */
        val RELEASE_DEADLINE = 2.seconds
    }
}
