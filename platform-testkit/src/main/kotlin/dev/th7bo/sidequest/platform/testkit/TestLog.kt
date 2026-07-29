package dev.th7bo.sidequest.platform.testkit

import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.log.LogCategory
import dev.th7bo.sidequest.platform.log.LogLevel
import dev.th7bo.sidequest.platform.log.LogSink
import dev.th7bo.sidequest.platform.log.Logger

/** One captured line. */
public data class LoggedLine(
    public val level: LogLevel,
    public val category: LogCategory,
    public val owner: SqId,
    public val message: String,
    public val thrown: Throwable?,
)

/**
 * A sink that keeps everything.
 *
 * The platform swallows listener failures on purpose, so the log is where the evidence
 * ends up. A test that asserts a failure was *isolated* has to also assert it was
 * *reported*, or "isolated" is indistinguishable from "silently dropped".
 */
public class RecordingLogSink : LogSink {

    public val lines: MutableList<LoggedLine> = ArrayList()

    override fun write(
        level: LogLevel,
        category: LogCategory,
        owner: SqId,
        message: String,
        thrown: Throwable?,
    ) {
        lines.add(LoggedLine(level, category, owner, message, thrown))
    }

    public fun errors(): List<LoggedLine> = lines.filter { it.level == LogLevel.ERROR }

    public fun messages(): List<String> = lines.map { it.message }

    public fun clear(): Unit = lines.clear()
}

/** A logger that records nothing, for tests that do not care. */
public object NoopLogger : Logger {
    override val category: LogCategory get() = LogCategory.PLATFORM
    override fun isEnabled(level: LogLevel): Boolean = false
    override fun log(level: LogLevel, thrown: Throwable?, message: () -> String) {}
    override fun named(name: String): Logger = this
}
