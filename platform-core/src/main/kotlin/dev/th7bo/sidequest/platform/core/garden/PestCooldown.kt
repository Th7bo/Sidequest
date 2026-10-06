package dev.th7bo.sidequest.platform.core.garden

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the tab list's Pests widget says about the next spawn.
 *
 * Hypixel writes it as ` Cooldown: 1m 58s`, ` Cooldown: READY` or ` Cooldown: MAX PESTS` under the widget's
 * header. The patterns are SkyHanni's (`garden.pests.cooldowntime-no-color` and `max-pests-alive`), which
 * carry the real shapes as regex tests.
 *
 * **The widget can arrive partial**, so ` Alive: 8` is checked first: eight pests is the cap, and no
 * cooldown line means anything once it is reached.
 */
public sealed interface PestCooldown {

    /** Counting down. [remaining] is as of the moment the widget was read. */
    public data class Waiting(public val remaining: Duration) : PestCooldown

    /** The cooldown is over: the next crop broken can spawn a pest. */
    public data object Ready : PestCooldown

    /** The plot is full. Nothing spawns until something dies. */
    public data object MaxPests : PestCooldown

    public companion object {
        private val COOLDOWN =
            Regex("""^\s*Cooldown: (?<time>\d{1,2}[ms](?: \d{1,2}s?)?)?(?<ready>READY)?(?<max>MAX PESTS)?.*""")

        private val MAX_ALIVE = Regex("""^\s*Alive: 8\b.*""")

        private val PART = Regex("""(?<amount>\d{1,2})(?<unit>[ms])""")

        /** What the Pests widget's value [lines] say, or null when there is no cooldown line among them. */
        public fun read(lines: List<String>): PestCooldown? {
            if (lines.any { MAX_ALIVE.matches(it) }) return MaxPests
            for (line in lines) {
                val match = COOLDOWN.matchEntire(line) ?: continue
                if (match.groups["max"] != null) return MaxPests
                if (match.groups["ready"] != null) return Ready
                val time = match.groups["time"]?.value ?: return null
                return Waiting(durationOf(time))
            }
            return null
        }

        /** `1m 58s`, `1m`, `58s`. */
        private fun durationOf(text: String): Duration =
            PART.findAll(text).fold(Duration.ZERO) { total, part ->
                val amount = part.groups["amount"]!!.value.toInt()
                total + if (part.groups["unit"]!!.value == "m") amount.minutes else amount.seconds
            }
    }
}
