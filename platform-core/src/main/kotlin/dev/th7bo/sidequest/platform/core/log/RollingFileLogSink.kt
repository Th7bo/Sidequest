package dev.th7bo.sidequest.platform.core.log

import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.log.LogCategory
import dev.th7bo.sidequest.platform.log.LogLevel
import dev.th7bo.sidequest.platform.log.LogSink
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The mod's own log file, rotated.
 *
 * Its own file rather than only the game's, because the game's log is where every mod writes and asking
 * somebody to find Sidequest's lines in it — then upload the whole thing, with everybody else's chat in it —
 * is asking for a bad bug report. This one contains only this mod, is already redacted by the logger, and is
 * small enough to attach.
 *
 * **Rotation is by size, and the cap is a total rather than a per-file one.** A log that grows without bound
 * is a slow disk leak that nobody notices until it is gigabytes, and a per-file cap with unlimited files is
 * the same leak with extra steps. Old files are deleted, oldest first.
 *
 * Failures here are swallowed. A read-only directory, a full disk or a locked file must not take the mod down
 * — a diagnostic that can crash the thing it is diagnosing is worse than no diagnostic, and there is nowhere
 * useful to report a logging failure to anyway.
 */
public class RollingFileLogSink(
    private val directory: Path,
    /** When the live file passes this, it is rotated. */
    private val maxBytesPerFile: Long = DEFAULT_MAX_BYTES,
    /** How many rotated files to keep, besides the live one. */
    private val keep: Int = DEFAULT_KEEP,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : LogSink {

    private val live: Path get() = directory.resolve(LIVE_NAME)

    private var written = 0L
    private var ready = false

    /** Bytes written to the live file, so rotation does not stat the file on every line. */
    private val lock = Any()

    override fun write(
        level: LogLevel,
        category: LogCategory,
        owner: SqId,
        message: String,
        thrown: Throwable?,
    ) {
        val line = buildString {
            append(TIME.format(clock().atZone(zone)))
            append(" [").append(level).append("] ")
            append(category).append('/').append(owner).append(": ")
            append(message)
            append('\n')
            thrown?.let { append(stackTraceOf(it)) }
        }

        synchronized(lock) {
            runCatching {
                prepare()
                if (written > maxBytesPerFile) rotate()
                Files.writeString(
                    live,
                    line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
                written += line.length
            }
        }
    }

    private fun prepare() {
        if (ready) return
        Files.createDirectories(directory)
        // The size is read once, at startup, rather than tracked from zero: a session appending to the
        // previous session's file would otherwise never rotate until it had written a whole cap of its own.
        written = if (Files.exists(live)) Files.size(live) else 0
        ready = true
    }

    /**
     * Moves the live file aside and prunes.
     *
     * Named by timestamp rather than shuffled through `.1`, `.2`, `.3`. Shuffling means renaming every file
     * on every rotation and produces names that say nothing; a timestamp sorts correctly, survives a crash
     * mid-rotation, and tells somebody which file covers when the bug happened.
     */
    private fun rotate() {
        val stamp = STAMP.format(clock().atZone(zone))
        runCatching { Files.move(live, directory.resolve("sidequest-$stamp.log")) }
        written = 0

        runCatching {
            Files.list(directory).use { entries ->
                entries.filter { it.fileName.toString().startsWith(PREFIX) && it.fileName.toString() != LIVE_NAME }
                    .sorted()
                    .toList()
                    .dropLast(keep)
                    .forEach { old -> runCatching { Files.deleteIfExists(old) } }
            }
        }
    }

    private fun stackTraceOf(thrown: Throwable): String {
        val writer = StringWriter()
        thrown.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    public companion object {
        /** Two mebibytes a file. Large enough to hold a session's tracing, small enough to attach to a message. */
        public const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024

        /** Five rotated files, so a bug from a couple of sessions ago is still there. */
        public const val DEFAULT_KEEP: Int = 5

        private const val PREFIX = "sidequest"
        private const val LIVE_NAME = "sidequest.log"

        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
