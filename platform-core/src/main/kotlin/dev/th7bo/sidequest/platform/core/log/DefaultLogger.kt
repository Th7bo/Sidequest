package dev.th7bo.sidequest.platform.core.log

import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.log.LogCategory
import dev.th7bo.sidequest.platform.log.LogLevel
import dev.th7bo.sidequest.platform.log.LogRedactor
import dev.th7bo.sidequest.platform.log.LogSink
import dev.th7bo.sidequest.platform.log.Logger

/**
 * The logger.
 *
 * Redaction happens here, once, on the way to the sink — not at call sites. These logs
 * get pasted into a chat channel when something breaks, and a rule that each call site
 * has to remember is a rule that leaks a token the one time somebody forgets.
 *
 * Levels are per category, so parser tracing can be turned up without drowning in
 * everything else.
 */
public class LoggerFactory(
    private val sink: LogSink,
    private val redactor: LogRedactor = LogRedactor.Default,
) {

    private val levels = HashMap<LogCategory, LogLevel>()

    /** Below this, nothing is written. */
    public var defaultLevel: LogLevel = LogLevel.INFO

    public fun setLevel(category: LogCategory, level: LogLevel) {
        levels[category] = level
    }

    public fun levelOf(category: LogCategory): LogLevel = levels[category] ?: defaultLevel

    public fun create(category: LogCategory, owner: SqId): Logger =
        DefaultLogger(category, owner, this, sink, redactor)
}

private class DefaultLogger(
    override val category: LogCategory,
    private val owner: SqId,
    private val factory: LoggerFactory,
    private val sink: LogSink,
    private val redactor: LogRedactor,
) : Logger {

    override fun isEnabled(level: LogLevel): Boolean = level >= factory.levelOf(category)

    override fun log(level: LogLevel, thrown: Throwable?, message: () -> String) {
        // The message is a lambda so a disabled line costs nothing to leave in place,
        // which is what makes it acceptable to ship the parser tracing.
        if (!isEnabled(level)) return
        sink.write(level, category, owner, redactor.redact(message()), thrown)
    }

    override fun named(name: String): Logger =
        DefaultLogger(category, owner.child(name), factory, sink, redactor)
}
