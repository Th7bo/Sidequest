package dev.th7bo.sidequest.platform.log

import dev.th7bo.sidequest.platform.id.SqId

/**
 * Structured logging.
 *
 * Every line carries a category and an owner, so a log from a user is filterable rather
 * than a wall of text with no indication of which feature produced what.
 *
 * Messages are lambdas: a debug line that is switched off costs nothing, which is what
 * makes it acceptable to leave parser tracing in the shipped build.
 */
public interface Logger {

    public val category: LogCategory

    public fun isEnabled(level: LogLevel): Boolean

    public fun log(level: LogLevel, thrown: Throwable? = null, message: () -> String)

    public fun trace(message: () -> String): Unit = log(LogLevel.TRACE, null, message)

    public fun debug(message: () -> String): Unit = log(LogLevel.DEBUG, null, message)

    public fun info(message: () -> String): Unit = log(LogLevel.INFO, null, message)

    public fun warn(thrown: Throwable? = null, message: () -> String): Unit =
        log(LogLevel.WARN, thrown, message)

    public fun error(thrown: Throwable? = null, message: () -> String): Unit =
        log(LogLevel.ERROR, thrown, message)

    /** A child logger tagged with [name], for a subsystem within one owner. */
    public fun named(name: String): Logger
}

public enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }

/**
 * What a line is about.
 *
 * Filterable independently of level, because the useful question when debugging is
 * almost never "show me everything at DEBUG" but "show me the parsers".
 */
public enum class LogCategory {
    PLATFORM,
    FEATURE,
    EVENT,
    PARSER,
    PERSISTENCE,
    BACKEND,
    REALTIME,
    ASSET,
    RENDER,
    AUDIO,
}

/**
 * Removes anything that must not reach a log file.
 *
 * Applied by the logger implementation to every message, not left to call sites. A rule
 * that has to be remembered at each call site is a rule that leaks a token the one time
 * somebody forgets — and these logs get pasted into chat when something breaks.
 */
public interface LogRedactor {

    public fun redact(message: String): String

    public companion object {
        /** Redacts bearer tokens, pairing codes and anything that looks like a secret. */
        public val Default: LogRedactor = object : LogRedactor {

            private val patterns = listOf(
                // `token=…`, `"accessToken": "…"`, `Authorization: Bearer …`
                Regex("""(?i)(bearer\s+)[A-Za-z0-9._\-]{8,}"""),
                Regex("""(?i)("?(?:access_?token|refresh_?token|token|secret|password|api_?key)"?\s*[:=]\s*"?)[^"\s,}]+"""),
            )

            override fun redact(message: String): String =
                patterns.fold(message) { text, pattern -> pattern.replace(text) { it.groupValues[1] + REDACTED } }
        }

        public const val REDACTED: String = "***"
    }
}

/** Where log lines go. Implemented by the Minecraft adapter over the game's logger. */
public fun interface LogSink {
    public fun write(
        level: LogLevel,
        category: LogCategory,
        owner: SqId,
        message: String,
        thrown: Throwable?,
    )
}
