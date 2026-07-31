package dev.th7bo.sidequest.platform.core.playtime

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * How long has been spent in SkyBlock, by day.
 *
 * Keyed by ISO date rather than by an index or an epoch day, because the file outlives the code that wrote it
 * and `2026-07-31` still means something to a human reading it in a year. The values are milliseconds.
 *
 * One of these per profile — the scope of the repository it is stored in decides that, not this class. An
 * Ironman profile's hours are not the main profile's, and merging them would make every number here a lie.
 */
@Serializable
public data class PlaytimeHistory(
    public val days: Map<String, Long> = emptyMap(),
) {
    /** What a given day came to. Zero for a day with nothing recorded, which is the same as not playing. */
    public fun on(day: LocalDate): Long = days[day.toString()] ?: 0L

    public val isEmpty: Boolean get() = days.isEmpty()
}

/** One day's total, for the days worth naming. */
public data class DayTotal(public val day: LocalDate, public val millis: Long)

/** A run of days summed — a week, a month. [start] is the first day it covers. */
public data class PeriodTotal(
    public val start: LocalDate,
    public val millis: Long,
    /** How many of the days in the period had any time at all. Separates "quiet week" from "one long day". */
    public val daysPlayed: Int,
)

/**
 * Reads and updates a [PlaytimeHistory].
 *
 * Every method is pure: it takes a history and returns one. That is what makes the interesting parts testable
 * at all — the midnight split, the retention window, the gap after a closed game — none of which can be
 * exercised through a running client without waiting for midnight.
 */
public class PlaytimeLedger(
    /**
     * How many days to keep.
     *
     * Days beyond it are dropped on the next write rather than at a scheduled time, so the file cannot grow
     * without bound even if the pruning job never runs.
     */
    public val retentionDays: Int = DEFAULT_RETENTION,
    /** Which midnight counts. The player's own, because "today" is a thing they experience locally. */
    public val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * Credits the span from [fromMillis] to [toMillis], splitting it across midnight.
     *
     * The split is the point. A session from 23:50 to 00:10 is ten minutes yesterday and ten minutes today,
     * and crediting all twenty to whichever day happened to be current when the tick landed would put a
     * full evening's play on the wrong side of the boundary for anybody who plays late.
     *
     * A span longer than [MAX_CREDITED_SPAN] is refused outright and the history comes back untouched. That
     * is not a safety valve for a slow frame — it is the case where the game was closed, or the computer was
     * asleep, for a day and a half, and the difference between two timestamps is not time anybody played.
     */
    public fun credit(history: PlaytimeHistory, fromMillis: Long, toMillis: Long): PlaytimeHistory {
        val span = toMillis - fromMillis
        if (span <= 0L || span > MAX_CREDITED_SPAN) return history

        val updated = history.days.toMutableMap()
        var cursor = fromMillis
        while (cursor < toMillis) {
            val day = dayOf(cursor)
            // The first instant of tomorrow, which is where this day's share of the span stops.
            val boundary = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val until = minOf(boundary, toMillis)
            updated[day.toString()] = (updated[day.toString()] ?: 0L) + (until - cursor)
            cursor = until
        }

        return PlaytimeHistory(prune(updated, dayOf(toMillis)))
    }

    /** The day a moment falls on, in the player's own zone. */
    public fun dayOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /**
     * Drops anything older than the retention window.
     *
     * Measured back from [today] rather than from the newest key present, so a history carried forward from
     * an old install does not keep itself alive by being old.
     */
    private fun prune(days: Map<String, Long>, today: LocalDate): Map<String, Long> {
        if (retentionDays <= 0) return days
        val oldest = today.minusDays(retentionDays.toLong() - 1)
        return days.filterKeys { key ->
            val day = runCatching { LocalDate.parse(key) }.getOrNull()
            // A key that will not parse is kept rather than silently deleted: it is somebody else's data or a
            // format this version does not know, and destroying it on read is the one unrecoverable move.
            day == null || !day.isBefore(oldest)
        }
    }

    // -- reading -------------------------------------------------------------

    /**
     * The mean over the last [window] days, including days with nothing on them.
     *
     * Days off are counted as zero on purpose. An average that skipped them would answer "how long is a
     * session" and be near-constant; this answers "how much am I playing", which is the question somebody
     * asks a playtime tracker.
     */
    public fun average(history: PlaytimeHistory, today: LocalDate, window: Int = DEFAULT_WINDOW): Long {
        if (window <= 0) return 0L
        val total = (0 until window).sumOf { back -> history.on(today.minusDays(back.toLong())) }
        return total / window
    }

    /** The longest day on record, or null when nothing is. */
    public fun best(history: PlaytimeHistory): DayTotal? = history.days
        .mapNotNull { (key, millis) -> runCatching { LocalDate.parse(key) }.getOrNull()?.let { DayTotal(it, millis) } }
        .filter { it.millis > 0 }
        // Ties go to the earlier day: a record stands until something actually beats it.
        .minWithOrNull(compareByDescending<DayTotal> { it.millis }.thenBy { it.day })

    public fun total(history: PlaytimeHistory): Long = history.days.values.sum()

    /** Every week the history covers, most recent first. Weeks start on Monday. */
    public fun weeks(history: PlaytimeHistory): List<PeriodTotal> =
        group(history) { it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }

    /** Every month the history covers, most recent first. */
    public fun months(history: PlaytimeHistory): List<PeriodTotal> =
        group(history) { it.withDayOfMonth(1) }

    private fun group(history: PlaytimeHistory, startOf: (LocalDate) -> LocalDate): List<PeriodTotal> =
        history.days
            .mapNotNull { (key, millis) ->
                runCatching { LocalDate.parse(key) }.getOrNull()?.let { it to millis }
            }
            .filter { (_, millis) -> millis > 0 }
            .groupBy { (day, _) -> startOf(day) }
            .map { (start, entries) ->
                PeriodTotal(
                    start = start,
                    millis = entries.sumOf { it.second },
                    daysPlayed = entries.size,
                )
            }
            .sortedByDescending { it.start }

    public companion object {
        /** Three months. Long enough for a monthly view to have something in it, short enough to stay small. */
        public const val DEFAULT_RETENTION: Int = 90

        /** A week, for the rolling average. */
        public const val DEFAULT_WINDOW: Int = 7

        /**
         * The longest span that can be credited in one go.
         *
         * Four hours. Longer than any plausible gap between ticks and shorter than any plausible sleep,
         * suspend or reinstall, which is the pair of things it has to sit between.
         */
        public const val MAX_CREDITED_SPAN: Long = 4L * 60L * 60L * 1000L
    }
}
