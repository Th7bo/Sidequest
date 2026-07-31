package dev.th7bo.sidequest.platform.core.playtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Counting how long somebody has been playing.
 *
 * Sounds like addition, and the addition is the easy part. What is worth testing is everything around it:
 * where a day ends, what happens to a session that crosses that line, and what a two-day gap in the
 * timestamps means — none of which can be produced from a running client without waiting for midnight or
 * closing the game for a week.
 */
class PlaytimeLedgerTest {

    /** A fixed zone, because "the player's own midnight" is the thing under test and must not drift. */
    private val zone = ZoneId.of("Europe/Brussels")

    private val ledger = PlaytimeLedger(zone = zone)

    private fun at(day: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(day).atStartOfDay(zone).plusHours(hour.toLong()).plusMinutes(minute.toLong())
            .toInstant().toEpochMilli()

    private fun minutes(count: Long) = count * 60_000L

    // -- crediting -----------------------------------------------------------

    @Test
    fun `a span within one day lands on that day`() {
        val history = ledger.credit(PlaytimeHistory(), at("2026-07-31", 14), at("2026-07-31", 16))

        assertEquals(minutes(120), history.on(LocalDate.parse("2026-07-31")))
        assertEquals(1, history.days.size)
    }

    /**
     * The reason crediting is not just addition.
     *
     * Somebody playing from ten to midnight and on to half past has not played two and a half hours today.
     * Crediting the whole span to whichever day was current when the tick landed would move an entire
     * evening onto the wrong side of the boundary for anybody who plays late — which is most people.
     */
    @Test
    fun `a span across midnight is split between the two days`() {
        val history = ledger.credit(PlaytimeHistory(), at("2026-07-31", 23, 50), at("2026-08-01", 0, 10))

        assertEquals(minutes(10), history.on(LocalDate.parse("2026-07-31")))
        assertEquals(minutes(10), history.on(LocalDate.parse("2026-08-01")))
    }

    /** Crediting accumulates rather than replacing — a day is many sessions. */
    @Test
    fun `spans on the same day add up`() {
        var history = ledger.credit(PlaytimeHistory(), at("2026-07-31", 9), at("2026-07-31", 10))
        history = ledger.credit(history, at("2026-07-31", 20), at("2026-07-31", 21))

        assertEquals(minutes(120), history.on(LocalDate.parse("2026-07-31")))
    }

    /**
     * A gap is not playtime.
     *
     * The difference between two timestamps is only time somebody played if the game was running throughout.
     * A closed client, a suspended laptop or a reinstall all produce an enormous difference, and crediting it
     * would hand somebody a thirty-hour day.
     */
    @Test
    fun `a span longer than the cap is refused entirely`() {
        val history = ledger.credit(PlaytimeHistory(), at("2026-07-29", 12), at("2026-07-31", 12))

        assertTrue(history.isEmpty, "nothing should have been credited, got ${history.days}")
    }

    /** The cap is a ceiling on one span, not on a day: many legitimate spans still add up past it. */
    @Test
    fun `many short spans exceed the cap between them`() {
        var history = PlaytimeHistory()
        repeat(10) { hour ->
            history = ledger.credit(history, at("2026-07-31", 8 + hour), at("2026-07-31", 9 + hour))
        }

        assertEquals(minutes(600), history.on(LocalDate.parse("2026-07-31")))
    }

    @Test
    fun `a span going backwards is ignored`() {
        val history = ledger.credit(PlaytimeHistory(), at("2026-07-31", 16), at("2026-07-31", 14))

        assertTrue(history.isEmpty)
    }

    // -- retention -----------------------------------------------------------

    @Test
    fun `days beyond the window are dropped on the next write`() {
        val short = PlaytimeLedger(retentionDays = 7, zone = zone)
        var history = short.credit(PlaytimeHistory(), at("2026-07-01", 12), at("2026-07-01", 13))

        history = short.credit(history, at("2026-07-31", 12), at("2026-07-31", 13))

        assertEquals(0L, history.on(LocalDate.parse("2026-07-01")), "a month ago is outside a week")
        assertEquals(minutes(60), history.on(LocalDate.parse("2026-07-31")))
    }

    /** The window is inclusive of today, so a seven-day retention keeps seven days and not eight. */
    @Test
    fun `the window counts back from today inclusive`() {
        val short = PlaytimeLedger(retentionDays = 7, zone = zone)
        var history = PlaytimeHistory()
        for (day in 25..31) {
            history = short.credit(history, at("2026-07-$day", 12), at("2026-07-$day", 13))
        }

        assertEquals(7, history.days.size, "the 25th through the 31st: ${history.days.keys.sorted()}")
        assertEquals(minutes(60), history.on(LocalDate.parse("2026-07-25")), "the oldest still fits")
    }

    /**
     * A key this version cannot read is left alone.
     *
     * Pruning deletes, and deleting something because it was not understood is the one move with no way
     * back. A future format, or somebody else's file, survives contact with an older client.
     */
    @Test
    fun `an unreadable key is kept rather than pruned`() {
        val short = PlaytimeLedger(retentionDays = 1, zone = zone)
        val seeded = PlaytimeHistory(mapOf("not-a-date" to minutes(5)))

        val history = short.credit(seeded, at("2026-07-31", 12), at("2026-07-31", 13))

        assertEquals(minutes(5), history.days["not-a-date"])
    }

    // -- reading -------------------------------------------------------------

    /**
     * Days off count as zero.
     *
     * An average over only the days somebody played answers "how long is a session", which is close to
     * constant and tells nobody anything. This answers "how much am I playing", which is the question.
     */
    @Test
    fun `the average includes the days with nothing on them`() {
        var history = PlaytimeHistory()
        history = ledger.credit(history, at("2026-07-31", 12), at("2026-07-31", 14))
        history = ledger.credit(history, at("2026-07-30", 12), at("2026-07-30", 14))

        val average = ledger.average(history, LocalDate.parse("2026-07-31"), window = 7)

        assertEquals(minutes(240) / 7, average, "four hours spread over a week, not two hours a day")
    }

    /** Built an hour at a time, which is how the ledger is actually fed — see the cap above. */
    private fun PlaytimeHistory.playing(day: String, hours: IntRange): PlaytimeHistory =
        hours.fold(this) { acc, hour -> ledger.credit(acc, at(day, hour), at(day, hour + 1)) }

    @Test
    fun `the best day is the longest one`() {
        val history = PlaytimeHistory()
            .playing("2026-07-29", 12..12)
            .playing("2026-07-30", 8..12)
            .playing("2026-07-31", 12..13)

        val best = ledger.best(history)

        assertEquals(LocalDate.parse("2026-07-30"), best?.day)
        assertEquals(minutes(300), best?.millis)
    }

    /** A record stands until something actually beats it, so a tie keeps the day that set it. */
    @Test
    fun `a tied record goes to the earlier day`() {
        var history = PlaytimeHistory()
        history = ledger.credit(history, at("2026-07-30", 12), at("2026-07-30", 14))
        history = ledger.credit(history, at("2026-07-31", 12), at("2026-07-31", 14))

        assertEquals(LocalDate.parse("2026-07-30"), ledger.best(history)?.day)
    }

    @Test
    fun `there is no best day before anything is played`() {
        assertNull(ledger.best(PlaytimeHistory()))
    }

    /** Weeks start on Monday, which is where the 27th of July 2026 falls. */
    @Test
    fun `days are grouped into weeks from Monday`() {
        var history = PlaytimeHistory()
        // Sunday, then the Monday and Tuesday after it: two weeks, not one.
        history = ledger.credit(history, at("2026-07-26", 12), at("2026-07-26", 13))
        history = ledger.credit(history, at("2026-07-27", 12), at("2026-07-27", 13))
        history = ledger.credit(history, at("2026-07-28", 12), at("2026-07-28", 13))

        val weeks = ledger.weeks(history)

        assertEquals(2, weeks.size, "the Sunday belongs to the week before: $weeks")
        assertEquals(LocalDate.parse("2026-07-27"), weeks.first().start, "most recent first")
        assertEquals(minutes(120), weeks.first().millis)
        assertEquals(2, weeks.first().daysPlayed)
    }

    @Test
    fun `days are grouped into months`() {
        var history = PlaytimeHistory()
        history = ledger.credit(history, at("2026-07-31", 22), at("2026-08-01", 1))

        val months = ledger.months(history)

        assertEquals(2, months.size, "the session crossed into August")
        assertEquals(LocalDate.parse("2026-08-01"), months.first().start)
        assertEquals(minutes(60), months.first().millis)
        assertEquals(minutes(120), months.last().millis)
    }
}
