package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.SidequestSettings
import dev.th7bo.sidequest.platform.core.notification.notification
import dev.th7bo.sidequest.platform.core.playtime.PlaytimeHistory
import dev.th7bo.sidequest.platform.core.playtime.PlaytimeLedger
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.skyblock.ProfileChangedEvent
import dev.th7bo.sidequest.platform.skyblock.SkyBlockProfile
import dev.th7bo.sidequest.platform.storage.Repository
import dev.th7bo.sidequest.platform.storage.StorageScope
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How long has been spent in SkyBlock.
 *
 * The arithmetic lives in [PlaytimeLedger], where midnight, retention and the closed-game gap can be tested
 * without waiting for any of them to happen. What is here is the part that needs a running client: noticing
 * that time is passing, and knowing whose time it is.
 *
 * **Per profile, not per account.** An Ironman's hours are not the main profile's, and the storage scope is
 * what enforces that — which is also why the repository is rebound when somebody switches, rather than one
 * being opened at startup and kept.
 */
class PlaytimeTracker(
    /** Who is playing. From the game client, which is the only thing that knows. */
    private val localPlayer: () -> PlayerId?,
    /** Wall clock, injected so a test can move it. */
    private val now: () -> Long = System::currentTimeMillis,
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("playtime.tracker"),
        displayName = "Playtime tracking",
        category = FeatureCategory.UTILITY,
        description = "Records how long you spend in SkyBlock, by day",
    )

    private lateinit var context: FeatureContext

    private val ledger: PlaytimeLedger
        get() = PlaytimeLedger(retentionDays = SidequestSettings.Playtime.retentionDays)

    /**
     * The open repository, and whose it is.
     *
     * Held together because they are only meaningful together: a history written to the wrong scope is
     * somebody else's hours, and the two fields going out of step is exactly how that happens.
     */
    private var bound: Bound? = null

    private class Bound(
        val scope: StorageScope.Profile,
        val repository: Repository<PlaytimeHistory>,
        var history: PlaytimeHistory,
    ) {
        /** True once something has been credited that is not yet on disk. */
        var isDirty: Boolean = false
    }

    /**
     * When time was last counted, or null when the clock is not running.
     *
     * Null is load-bearing: it is what the main menu, a non-SkyBlock server and a profile switch all leave
     * behind, and it is what stops the next tick crediting the gap since the last one.
     */
    private var lastCredit: Long? = null

    override fun onEnable(context: FeatureContext) {
        this.context = context

        context.scheduler.every(context.owner, period = TICK, initialDelay = TICK) { tick() }
        // Flushed on its own slower schedule rather than on every tick: a write a minute for the length of a
        // session is a lot of disk for a number that changes by a minute.
        context.scheduler.every(context.owner, period = FLUSH, initialDelay = FLUSH) { flush() }

        // A profile switch ends one profile's session and starts another's. Rebinding on the event rather
        // than checking every tick means the *previous* profile's outstanding time is written to the
        // previous profile, which is only possible while its repository is still the open one.
        context.listen(ProfileChangedEvent::class) { rebind() }

        context.command(name = "sqplaytime", description = "How long you have played") { report() }
    }

    override fun onDisable() {
        // The session's tail. Without this, quitting loses up to a flush interval of everybody's time — and
        // quitting is how most sessions end.
        flush()
        bound = null
        lastCredit = null
    }

    // -- counting ------------------------------------------------------------

    private fun tick() {
        if (!SidequestSettings.Playtime.isEnabled || !context.gameContext.isInSkyBlock) {
            // The clock stops rather than pausing. A player who alt-tabs to the main menu for an hour and
            // comes back has not played for an hour, and leaving `lastCredit` set is how they would.
            lastCredit = null
            return
        }

        val target = scopeNow()
        if (target == null) {
            // In SkyBlock but nothing yet says whose profile it is. Waiting costs a tick; guessing would
            // file the time under `profiles/` with an empty name, for everyone.
            lastCredit = null
            return
        }
        if (bound?.scope != target) rebind()

        val moment = now()
        val previous = lastCredit
        lastCredit = moment
        if (previous == null) return

        val open = bound ?: return
        val updated = ledger.credit(open.history, previous, moment)
        if (updated != open.history) {
            open.history = updated
            open.isDirty = true
        }
    }

    private fun scopeNow(): StorageScope.Profile? {
        val player = localPlayer() ?: return null
        val profile = context.gameContext.context.profile
        if (!profile.isKnown) return null
        return StorageScope.Profile(player, profile)
    }

    /**
     * Writes out whatever is open, then opens whatever is current.
     *
     * In that order, always. The outstanding time belongs to the profile that was being played, and the only
     * moment its repository is still the open one is before the swap.
     */
    private fun rebind() {
        flush()
        lastCredit = null
        bound = null

        val scope = scopeNow() ?: return
        val repository = context.store(
            name = "history",
            scope = scope,
            serializer = PlaytimeHistory.serializer(),
            default = { PlaytimeHistory() },
        )
        val opened = Bound(scope, repository, PlaytimeHistory())
        bound = opened

        context.scheduler.async(context.owner) {
            val loaded = repository.load()
            context.scheduler.onMain(context.owner) {
                // Checked rather than assumed: a second profile switch may have landed while this was
                // loading, and writing the old profile's history into the new binding would merge them.
                if (bound === opened) {
                    // Merged rather than replaced, because ticks kept crediting while the load was in
                    // flight and that time is real.
                    opened.history = merge(loaded.value, opened.history)
                }
            }
        }
    }

    private fun merge(stored: PlaytimeHistory, pending: PlaytimeHistory): PlaytimeHistory {
        if (pending.isEmpty) return stored
        val days = stored.days.toMutableMap()
        for ((day, millis) in pending.days) days[day] = (days[day] ?: 0L) + millis
        return PlaytimeHistory(days)
    }

    private fun flush() {
        val open = bound ?: return
        if (!open.isDirty) return
        open.isDirty = false
        val snapshot = open.history
        context.scheduler.async(context.owner) {
            runCatching { open.repository.save(snapshot) }
                .onFailure { context.log.warn(it) { "Could not save playtime for ${open.scope.profile}" } }
        }
    }

    // -- reporting -----------------------------------------------------------

    private fun report() {
        val open = bound
        if (open == null || !SidequestSettings.Playtime.isEnabled) {
            context.notifications.notify(
                notification(
                    category = NotificationCategory.DEBUG,
                    title = "Playtime",
                    subtitle = if (!SidequestSettings.Playtime.isEnabled) {
                        "Off. Turn it on under Playtime in the settings."
                    } else {
                        "Nothing recorded yet — join a SkyBlock profile."
                    },
                ),
            )
            return
        }

        val reader = ledger
        val today = reader.dayOf(now())
        val best = reader.best(open.history)
        context.notifications.notify(
            notification(
                category = NotificationCategory.PROGRESSION,
                title = "${format(open.history.on(today))} today",
                subtitle = buildString {
                    append("${format(reader.average(open.history, today))}/day this week")
                    best?.let { append(" · best ${format(it.millis)} on ${it.day}") }
                },
            ),
        )
    }

    /** What a person reads. Hours and minutes, because seconds of playtime is a number nobody wants. */
    private fun format(millis: Long): String {
        val totalMinutes = millis / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    /** Today's total, for anything drawing it. Zero when nothing is bound. */
    fun todayMillis(): Long {
        val open = bound ?: return 0L
        return open.history.on(ledger.dayOf(now()))
    }

    /** The whole history for the open profile, for a screen that wants to draw it. */
    fun history(): PlaytimeHistory = bound?.history ?: PlaytimeHistory()

    /** Which day the tracker thinks it is, in the player's own zone. */
    fun today(): LocalDate = ledger.dayOf(now())

    /** Whose hours are being counted, or null when nothing is. */
    fun profile(): SkyBlockProfile? = bound?.scope?.profile

    private companion object {
        /**
         * How often time is counted.
         *
         * Half a minute. Fine enough that quitting loses nothing worth noticing, coarse enough that the
         * check costs nothing — and well inside the ledger's own cap on a single span, so an ordinary tick
         * can never be mistaken for a gap.
         */
        val TICK = 30.seconds

        /** How often it reaches the disk. */
        val FLUSH = 5.minutes
    }
}
