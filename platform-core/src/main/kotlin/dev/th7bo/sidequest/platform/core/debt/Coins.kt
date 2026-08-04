package dev.th7bo.sidequest.platform.core.debt

import kotlin.math.floor
import kotlin.math.round

/**
 * Reading and writing amounts the way SkyBlock does.
 *
 * `5m`, `250k`, `1.5b`. Nobody types eight zeroes, and a ledger that made them would be one nobody used —
 * which for a debt tracker means the debts get remembered somewhere worse, like memory.
 *
 * **This lives here rather than in the feature so that it can be tested at all.** The mod module has no test
 * source set, and a suffix parser is exactly the sort of code that is obviously right and off by a factor of
 * a thousand: `5m` read as five thousand turns a five-million debt into pocket change, and nothing about the
 * resulting number looks wrong enough for anybody to question it.
 */
public object Coins {

    /**
     * Reads an amount, or null when it is not one.
     *
     * Null rather than zero for anything unparseable, because zero is a real amount somebody could mean and
     * a parser that returned it for nonsense would record a debt of nothing instead of refusing.
     */
    public fun parse(raw: String): Long? {
        val text = raw.trim().lowercase().replace(",", "").replace("_", "").replace(" ", "")
        if (text.isEmpty()) return null

        val multiplier = when (text.last()) {
            'k' -> THOUSAND
            'm' -> MILLION
            'b' -> BILLION
            else -> 1L
        }
        val number = if (multiplier == 1L) text else text.dropLast(1)
        if (number.isEmpty()) return null

        val value = number.toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite() || value < 0) return null

        val coins = value * multiplier
        // Above this it is a typo rather than an amount, and it is where the arithmetic stops being
        // trustworthy. Refused rather than clamped: a silently capped debt is a wrong number presented as a
        // right one.
        if (coins > MAX) return null
        return coins.toLong()
    }

    /**
     * Writes an amount the way it would have been typed.
     *
     * One decimal place at most, and a trailing `.0` is dropped — `5m` rather than `5.0m`, because the
     * second one reads like a rounding somebody should check.
     */
    public fun format(coins: Long): String {
        val negative = coins < 0
        val magnitude = if (negative) -coins else coins
        val text = when {
            magnitude >= BILLION -> trim(magnitude / BILLION.toDouble()) + "b"
            magnitude >= MILLION -> trim(magnitude / MILLION.toDouble()) + "m"
            magnitude >= THOUSAND -> trim(magnitude / THOUSAND.toDouble()) + "k"
            else -> magnitude.toString()
        }
        return if (negative) "-$text" else text
    }

    private fun trim(value: Double): String {
        val rounded = round(value * 10) / 10
        return if (rounded == floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }

    private const val THOUSAND = 1_000L
    private const val MILLION = 1_000_000L
    private const val BILLION = 1_000_000_000L

    /**
     * The largest amount worth taking seriously.
     *
     * A trillion. SkyBlock's economy does reach the billions, so this is above anything real while still
     * catching a hand that stayed on the zero key.
     */
    private const val MAX = 1_000_000_000_000.0
}
