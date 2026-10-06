package dev.th7bo.sidequest.platform.core.garden

/**
 * What the Garden's time should be next, and what has gone wrong when it is not.
 *
 * The routine it keeps: **Night before pests spawn, Day before they are killed.** Then Night again for the
 * next lot. Easy to state and easy to forget halfway through a run, which is the whole reason this exists —
 * a reminder has to come from something that is watching, because the player is watching the crops.
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

    /** What the player should do next, or null when the time is already right. */
    public val todo: GardenTime?
        get() {
            val wanted = if (pestsOut > 0) GardenTime.DAY else GardenTime.NIGHT
            return wanted.takeIf { time != wanted }
        }

    /** The player set the time. */
    public fun onTimeSet(time: GardenTime): Advice {
        this.time = time
        return when {
            pestsOut > 0 && time == GardenTime.DAY -> Advice.ReadyToKill
            pestsOut > 0 && time == GardenTime.NIGHT -> Advice.NightWithPestsOut
            else -> Advice.None
        }
    }

    /** Pests arrived. */
    public fun onSpawn(amount: Int): Advice {
        pestsOut += amount.coerceAtLeast(1)
        return when (time) {
            GardenTime.NIGHT -> Advice.SetDay
            GardenTime.DAY -> Advice.SpawnedInDay
            null -> Advice.SetDay
        }
    }

    /** A pest died. */
    public fun onKill(): Advice {
        pestsOut = (pestsOut - 1).coerceAtLeast(0)
        return when {
            time == GardenTime.NIGHT -> Advice.KilledAtNight
            pestsOut == 0 && time == GardenTime.DAY -> Advice.AllClear
            else -> Advice.None
        }
    }

    /** Forgets what the mod believed. For a profile switch, or anything else that makes it stale. */
    public fun reset() {
        time = null
        pestsOut = 0
    }

    /** What an event means for the player. */
    public sealed interface Advice {
        /** Nothing to say. */
        public data object None : Advice

        /** Pests are out and it is not Day yet: switch before killing them. */
        public data object SetDay : Advice

        /** Pests spawned while it was Day, so the Night was missed this time. */
        public data object SpawnedInDay : Advice

        /** Day was set with pests out: go kill them. */
        public data object ReadyToKill : Advice

        /** Night was set while pests are still out — the next kill would be at Night. */
        public data object NightWithPestsOut : Advice

        /** A pest was killed while it was still Night. The one that has to be loud. */
        public data object KilledAtNight : Advice

        /** The last pest died at Day. Night again before the next spawn. */
        public data object AllClear : Advice
    }
}
