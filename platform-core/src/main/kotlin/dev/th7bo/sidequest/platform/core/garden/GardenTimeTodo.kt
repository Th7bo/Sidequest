package dev.th7bo.sidequest.platform.core.garden

/**
 * What the Garden's time should be next, and what has gone wrong when it is not.
 *
 * The routine it keeps: **farm at Day, switch to Night just for the spawn, back to Day before anything is
 * killed** — and leave one pest alive on the plot for the next wave. Night is the exception rather than the
 * rule, so the only thing worth reminding about is a Night that outstayed its spawn. A Night set ahead of a
 * spawn is the player doing it right, however long they farm while they wait.
 *
 * Nothing here reads chat or shows anything. It is told what happened and answers with what that means, so
 * the routine can be tested without a game.
 *
 * **The pest count is a best guess.** It is built from spawn announcements and kill rewards, and pests can
 * exist before the mod saw them spawn — a reconnect, a fresh launch. So a kill with nothing counted is still
 * a kill, and the count never goes below zero.
 */
public class GardenTimeTodo {

    /** What the time was last set to, or null until the mod has seen it set. */
    public var time: GardenTime? = null
        private set

    /** Pests believed to be alive. See the class comment for how far to trust it. */
    public var pestsOut: Int = 0
        private set

    /** Whether pests spawned since the time was last set to Night — so the Night has done its job. */
    private var spawnedThisNight: Boolean = false

    /** What the player should do next, or null when the time is already right. */
    public val todo: GardenTime?
        get() = GardenTime.DAY.takeIf { time == GardenTime.NIGHT && spawnedThisNight }

    /** The player set the time. */
    public fun onTimeSet(time: GardenTime): Advice {
        val spawned = spawnedThisNight
        this.time = time
        spawnedThisNight = false
        return if (time == GardenTime.DAY && spawned) Advice.ReadyToKill else Advice.None
    }

    /** Pests arrived. */
    public fun onSpawn(amount: Int): Advice {
        pestsOut += amount.coerceAtLeast(1)
        return when (time) {
            GardenTime.NIGHT -> {
                spawnedThisNight = true
                Advice.SetDay
            }
            GardenTime.DAY -> Advice.SpawnedInDay
            null -> Advice.SetDay
        }
    }

    /** A pest died. */
    public fun onKill(): Advice {
        val before = pestsOut
        pestsOut = (pestsOut - 1).coerceAtLeast(0)
        return when {
            time == GardenTime.NIGHT -> Advice.KilledAtNight
            time == GardenTime.DAY && before > KEPT && pestsOut == KEPT -> Advice.LastOneLeft
            else -> Advice.None
        }
    }

    /** Forgets what the mod believed. For a profile switch, or anything else that makes it stale. */
    public fun reset() {
        time = null
        pestsOut = 0
        spawnedThisNight = false
    }

    /** What an event means for the player. */
    public sealed interface Advice {
        /** Nothing to say. */
        public data object None : Advice

        /** Pests are out and it is not Day yet: switch before killing them. */
        public data object SetDay : Advice

        /** Pests spawned while it was Day, so the Night was missed this time. */
        public data object SpawnedInDay : Advice

        /** Day was set after the spawn: go kill them. */
        public data object ReadyToKill : Advice

        /** A pest was killed while it was still Night. The one that has to be loud. */
        public data object KilledAtNight : Advice

        /** Down to the one pest that stays on the plot. */
        public data object LastOneLeft : Advice
    }

    public companion object {
        /** The pest left alive on the plot between waves. */
        public const val KEPT: Int = 1
    }
}
