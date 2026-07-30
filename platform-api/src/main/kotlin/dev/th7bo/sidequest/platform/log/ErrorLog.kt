package dev.th7bo.sidequest.platform.log

import dev.th7bo.sidequest.platform.id.SqId

/**
 * A short code naming one *kind* of failure.
 *
 * **Derived from what went wrong, not generated per occurrence.** That is the decision worth explaining: a
 * random id per throw is easy and nearly useless, because the same bug hitting a hundred times produces a
 * hundred codes and none of them can be looked up or counted. Hashing the failure's identity instead — where
 * it happened, what type it was, the shape of its message — means the same bug always produces the same code.
 *
 * So a person can say "I keep getting SQ-4F2A9C", and that is enough to find every occurrence in their log,
 * count them, and recognise it as one already reported.
 *
 * Six hex characters. Long enough that collisions between the handful of distinct failures a mod produces are
 * not worth worrying about, short enough to read aloud.
 */
@JvmInline
public value class ErrorId(public val value: String) {

    override fun toString(): String = "SQ-$value"

    public companion object {
        public const val LENGTH: Int = 6

        /**
         * The code for a failure.
         *
         * Built from the throwable's type, the first stack frame belonging to this mod, and the message with
         * its variable parts removed — because "could not load asset 4f2a" and "could not load asset 91bc"
         * are one bug, and would otherwise be two codes.
         */
        public fun of(owner: SqId, message: String, thrown: Throwable?): ErrorId {
            val site = thrown?.let { firstOwnFrame(it) } ?: ""
            val type = thrown?.let { it::class.qualifiedName } ?: ""
            val shape = generalise(message)
            return fromHash("$owner|$type|$site|$shape")
        }

        /**
         * Strips the parts of a message that vary between occurrences of one bug.
         *
         * Numbers, hex ids and quoted strings. Without this, every id, coordinate and count in a message
         * becomes part of the code and the code stops grouping anything.
         */
        private fun generalise(message: String): String = message
            .replace(HEX, "#")
            .replace(NUMBER, "#")
            .replace(QUOTED, "'#'")
            .trim()

        /** The deepest frame inside this mod, which is where the bug is rather than where it surfaced. */
        private fun firstOwnFrame(thrown: Throwable): String {
            val frame = thrown.stackTrace.firstOrNull { it.className.startsWith(PACKAGE) }
                ?: thrown.stackTrace.firstOrNull()
                ?: return ""
            // The line number is deliberately excluded: including it would give the same bug a new code
            // every time an unrelated line above it moved.
            return "${frame.className}.${frame.methodName}"
        }

        private fun fromHash(input: String): ErrorId {
            // A plain string hash. This groups failures for a human; it is not a checksum and nothing depends
            // on it being hard to collide with.
            var hash = SEED
            for (character in input) {
                hash = hash xor character.code.toLong()
                hash *= PRIME
            }
            val hex = (hash and MASK).toString(RADIX).uppercase().padStart(LENGTH, '0')
            return ErrorId(hex.takeLast(LENGTH))
        }

        private const val PACKAGE = "dev.th7bo.sidequest"
        private val HEX = Regex("""\b[0-9a-fA-F]{8,}\b""")
        private val NUMBER = Regex("""\b\d+\b""")
        private val QUOTED = Regex("""'[^']*'""")

        // FNV-1a's parameters. Chosen because they are well-known and spread short strings well.
        private const val SEED = -3750763034362895579L
        private const val PRIME = 1099511628211L
        private const val MASK = 0xFFFFFFL
        private const val RADIX = 16
    }
}

/** One kind of failure, and how often it has happened. */
public data class ErrorRecord(
    public val id: ErrorId,
    public val category: LogCategory,
    public val owner: SqId,
    /** The most recent message. Redacted, like everything else that reaches a sink. */
    public val message: String,
    /** The exception's type and message, when there was one. */
    public val cause: String? = null,
    public val firstSeenMillis: Long,
    public val lastSeenMillis: Long,
    public val count: Int,
) {
    override fun toString(): String =
        "$id $category/$owner: $message" + (if (count > 1) " (x$count)" else "")
}

/**
 * The failures this session has produced.
 *
 * Grouped by [ErrorId] rather than kept as a list, which is the whole reason the id is derived rather than
 * random: a network blip that fires two hundred times is one entry with a count, not two hundred lines
 * pushing everything else out of a bounded buffer.
 *
 * Bounded, because it runs for as long as the game does and an unbounded one is a leak with a friendly name.
 */
public interface ErrorLog {

    /** Everything seen, most recent first. */
    public fun recent(limit: Int = DEFAULT_LIMIT): List<ErrorRecord>

    public fun get(id: ErrorId): ErrorRecord?

    /** How many distinct failures, and how many occurrences in total. */
    public fun summary(): ErrorSummary

    public fun clear()

    public companion object {
        public const val DEFAULT_LIMIT: Int = 20

        /** Records nothing. For tests of everything that merely holds one. */
        public val None: ErrorLog = object : ErrorLog {
            override fun recent(limit: Int): List<ErrorRecord> = emptyList()
            override fun get(id: ErrorId): ErrorRecord? = null
            override fun summary(): ErrorSummary = ErrorSummary(0, 0, 0)
            override fun clear() {}
        }
    }
}

public data class ErrorSummary(
    public val distinct: Int,
    public val occurrences: Int,
    /** Occurrences at [LogLevel.ERROR]. Warnings are recorded too and are usually noise by comparison. */
    public val errors: Int,
) {
    public val isClean: Boolean get() = occurrences == 0

    override fun toString(): String =
        if (isClean) "no problems" else "$distinct distinct, $occurrences occurrence(s), $errors error(s)"
}
