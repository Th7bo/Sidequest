package dev.th7bo.sidequest.platform.core.debt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Amounts, as people type them.
 *
 * A suffix parser is the sort of code that looks obviously right and is off by a thousand. `5m` read as five
 * thousand turns a five-million debt into pocket change, and nothing about the number that comes out looks
 * wrong enough for anybody to question it — so every multiplier is checked against its literal value rather
 * than against another expression involving the same constant.
 */
class CoinsTest {

    // -- reading --------------------------------------------------------------

    @Test
    fun `a plain number is itself`() {
        assertEquals(500, Coins.parse("500"))
        assertEquals(0, Coins.parse("0"))
    }

    /** Each multiplier against its literal value. The whole point of the file. */
    @Test
    fun `the suffixes are worth what they say`() {
        assertEquals(1_000, Coins.parse("1k"))
        assertEquals(1_000_000, Coins.parse("1m"))
        assertEquals(1_000_000_000, Coins.parse("1b"))
        assertEquals(5_000_000, Coins.parse("5m"))
        assertEquals(250_000, Coins.parse("250k"))
    }

    @Test
    fun `a fraction of a suffix works`() {
        assertEquals(1_500_000, Coins.parse("1.5m"))
        assertEquals(2_500, Coins.parse("2.5k"))
        assertEquals(1_500_000_000, Coins.parse("1.5b"))
    }

    @Test
    fun `case does not matter`() {
        assertEquals(5_000_000, Coins.parse("5M"))
        assertEquals(1_000_000_000, Coins.parse("1B"))
    }

    @Test
    fun `separators people type are ignored`() {
        assertEquals(1_000_000, Coins.parse("1,000,000"))
        assertEquals(1_000_000, Coins.parse("1_000_000"))
        assertEquals(5_000_000, Coins.parse("  5m  "))
    }

    // -- refusing -------------------------------------------------------------

    /** Null, never zero. Zero is an amount somebody could mean, so it cannot double as "unparseable". */
    @Test
    fun `nonsense is refused rather than read as nothing`() {
        assertNull(Coins.parse(""))
        assertNull(Coins.parse("   "))
        assertNull(Coins.parse("lots"))
        assertNull(Coins.parse("m"))
        assertNull(Coins.parse("5x"))
        assertNull(Coins.parse("--5"))
    }

    @Test
    fun `a negative amount is refused`() {
        assertNull(Coins.parse("-5m"))
        assertNull(Coins.parse("-1"))
    }

    /**
     * A hand that stayed on the zero key is refused, not clamped.
     *
     * A silently capped debt is a wrong number presented as a right one, which for this feature is the
     * worst available outcome.
     */
    @Test
    fun `an absurd amount is refused rather than capped`() {
        assertNull(Coins.parse("999999b"))
        assertNull(Coins.parse("100000000000000"))
    }

    // -- writing --------------------------------------------------------------

    @Test
    fun `an amount is written the way it would be typed`() {
        assertEquals("500", Coins.format(500))
        assertEquals("1k", Coins.format(1_000))
        assertEquals("5m", Coins.format(5_000_000))
        assertEquals("1b", Coins.format(1_000_000_000))
    }

    /** `5m`, not `5.0m` — the second reads like a rounding somebody ought to check. */
    @Test
    fun `a whole amount has no decimal`() {
        assertEquals("2m", Coins.format(2_000_000))
        assertEquals("1.5m", Coins.format(1_500_000))
    }

    @Test
    fun `a negative balance keeps its sign`() {
        assertEquals("-5m", Coins.format(-5_000_000))
        assertEquals("-500", Coins.format(-500))
    }

    /**
     * What is written reads back as what it was.
     *
     * Only for the amounts that survive one decimal place — `1.25m` writes as `1.3m` and is not expected to
     * come back, which is fine for a label and is why nothing parses its own output.
     */
    @Test
    fun `a written amount reads back the same`() {
        for (amount in listOf(0L, 500L, 1_000L, 250_000L, 1_500_000L, 5_000_000L, 1_000_000_000L)) {
            assertEquals(amount, Coins.parse(Coins.format(amount)), "round trip of $amount")
        }
    }
}
