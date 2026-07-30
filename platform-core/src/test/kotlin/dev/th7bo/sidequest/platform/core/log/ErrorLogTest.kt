package dev.th7bo.sidequest.platform.core.log

import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.log.ErrorId
import dev.th7bo.sidequest.platform.log.LogCategory
import dev.th7bo.sidequest.platform.log.LogLevel
import dev.th7bo.sidequest.platform.log.LogSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * Error ids, and the log they group.
 *
 * The value of an id is entirely in whether the *same* bug produces the *same* code — a random id per
 * occurrence is trivially correct and useless. So these are mostly about what counts as the same failure.
 */
class ErrorLogTest {

    private val owner = SqId.sidequest("test")

    @Test
    fun `the same failure gets the same id twice`() {
        val thrown = IllegalStateException("boom")

        assertEquals(
            ErrorId.of(owner, "could not connect", thrown),
            ErrorId.of(owner, "could not connect", thrown),
        )
    }

    /**
     * The point of generalising the message.
     *
     * "Could not load asset 4f2a…" and "could not load asset 91bc…" are one bug. Ids that differed per asset
     * would group nothing, which is the whole function of the id.
     */
    @Test
    fun `ids ignore the parts of a message that vary between occurrences`() {
        val thrown = IllegalStateException("boom")

        val first = ErrorId.of(owner, "could not load asset 4f2ab9c1d2e3f4a5 after 3 attempts", thrown)
        val second = ErrorId.of(owner, "could not load asset 91bcde0011223344 after 7 attempts", thrown)

        assertEquals(first, second)
    }

    @Test
    fun `different failures get different ids`() {
        val ids = setOf(
            ErrorId.of(owner, "could not connect", IllegalStateException("a")),
            ErrorId.of(owner, "could not parse", IllegalStateException("a")),
            ErrorId.of(owner, "could not connect", IllegalArgumentException("a")),
            ErrorId.of(SqId.sidequest("other"), "could not connect", IllegalStateException("a")),
        )

        assertEquals(4, ids.size, "owner, message and exception type should each distinguish a failure")
    }

    @Test
    fun `an id is readable aloud`() {
        val id = ErrorId.of(owner, "something", null)

        assertEquals(ErrorId.LENGTH, id.value.length)
        assertTrue(id.value.all { it in '0'..'9' || it in 'A'..'F' }, "hex, uppercase: ${id.value}")
        assertTrue(id.toString().startsWith("SQ-"))
    }

    // -- grouping ------------------------------------------------------------

    /**
     * The reason the log is keyed rather than a list.
     *
     * An unreachable backend warns on every retry. As a list, two hundred identical lines push out the one
     * interesting failure that happened once — so the buffer loses exactly what it exists to keep.
     */
    @Test
    fun `two hundred occurrences of one failure are one entry with a count`() {
        val log = RecordingErrorLog()
        val thrown = java.net.ConnectException("refused")

        repeat(200) { log.record(LogLevel.WARN, LogCategory.BACKEND, owner, "could not connect", thrown) }
        log.record(LogLevel.ERROR, LogCategory.PARSER, owner, "a different problem", null)

        assertEquals(2, log.recent().size)
        assertEquals(200, log.recent().first { it.category == LogCategory.BACKEND }.count)
        assertEquals(2, log.summary().distinct)
        assertEquals(201, log.summary().occurrences)
        assertEquals(1, log.summary().errors, "only the ERROR one counts as an error")
    }

    @Test
    fun `the most recent failure comes first`() {
        var clock = 1_000L
        val log = RecordingErrorLog(now = { clock })

        log.record(LogLevel.WARN, LogCategory.BACKEND, owner, "first", null)
        clock = 2_000
        log.record(LogLevel.WARN, LogCategory.PARSER, owner, "second", null)

        assertEquals(LogCategory.PARSER, log.recent().first().category)
    }

    @Test
    fun `the log is bounded and drops what has not been seen for longest`() {
        val log = RecordingErrorLog(capacity = 3)

        // Distinguished by letters rather than by a counter, because a counter is exactly what the id
        // generalises away — ten messages differing only in a number are one failure, correctly.
        repeat(10) { index ->
            val kind = ('a' + index).toString().repeat(3)
            log.record(LogLevel.WARN, LogCategory.FEATURE, owner, "the $kind subsystem broke", null)
        }

        assertEquals(3, log.recent(limit = 100).size)
        assertEquals(3, log.summary().distinct)
        assertEquals(10, log.summary().occurrences, "the count of everything is still right")
    }

    @Test
    fun `an entry can be looked up by the id somebody read out`() {
        val log = RecordingErrorLog()

        val id = log.record(LogLevel.ERROR, LogCategory.BACKEND, owner, "it broke", null)

        assertNotNull(log.get(id))
        assertEquals("it broke", log.get(id)?.message)
        assertNull(log.get(ErrorId("000000")))
    }

    @Test
    fun `a clean session says so`() {
        assertTrue(RecordingErrorLog().summary().isClean)
    }

    // -- the wiring ----------------------------------------------------------

    /**
     * A raised level must not hide the record.
     *
     * Turning a category up is about how much reaches the log file. If it also decided what the error list
     * held, then quietening the backend's chatter would lose the record of the backend failing — which is
     * the one thing somebody quietening it still wants to know.
     */
    @Test
    fun `problems are recorded even when the level filter would drop the line`() {
        val written = mutableListOf<String>()
        val factory = LoggerFactory(LogSink { _, _, _, message, _ -> written.add(message) })
        factory.setLevel(LogCategory.BACKEND, LogLevel.ERROR)
        val log = factory.create(LogCategory.BACKEND, owner)

        log.warn { "a warning nobody wanted in the file" }

        assertTrue(written.isEmpty(), "the file should not have it")
        assertEquals(1, factory.errors.summary().occurrences, "and the error log should")
    }

    @Test
    fun `routine lines are not recorded as problems`() {
        val factory = LoggerFactory(LogSink { _, _, _, _, _ -> })
        val log = factory.create(LogCategory.FEATURE, owner)

        log.info { "everything is fine" }
        log.debug { "in detail" }

        assertTrue(factory.errors.summary().isClean)
    }

    /** Redaction is on the way in, so a token cannot reach the error list either. */
    @Test
    fun `a recorded problem is redacted`() {
        val factory = LoggerFactory(LogSink { _, _, _, _, _ -> })
        val log = factory.create(LogCategory.BACKEND, owner)

        log.error { "refresh failed for token=abcdef0123456789" }

        val recorded = factory.errors.recent().single().message
        assertFalse("abcdef0123456789" in recorded, "the token reached the error log: $recorded")
        assertTrue("***" in recorded)
    }
}

/**
 * The log file.
 *
 * Small tests, because the class is deliberately dull — but rotation and the total cap are worth pinning,
 * since the failure mode of getting them wrong is somebody's disk filling up quietly.
 */
class RollingFileLogSinkTest {

    @Test
    fun `lines are written with their level, category and owner`(@TempDirectory directory: Path) {
        val sink = sink(directory)

        sink.write(LogLevel.WARN, LogCategory.BACKEND, SqId.sidequest("client"), "unreachable", null)

        val text = directory.resolve("sidequest.log").readText()
        assertTrue("WARN" in text, text)
        assertTrue("BACKEND" in text, text)
        assertTrue("unreachable" in text, text)
    }

    @Test
    fun `a stack trace is written when there is one`(@TempDirectory directory: Path) {
        val sink = sink(directory)

        sink.write(LogLevel.ERROR, LogCategory.PLATFORM, SqId.sidequest("x"), "broke", IllegalStateException("why"))

        val text = directory.resolve("sidequest.log").readText()
        assertTrue("IllegalStateException" in text, text)
        assertTrue("why" in text, text)
    }

    @Test
    fun `the file rotates once it passes its size`(@TempDirectory directory: Path) {
        var moment = Instant.parse("2026-01-01T00:00:00Z")
        val sink = RollingFileLogSink(directory, maxBytesPerFile = 200, keep = 5, clock = { moment })

        repeat(20) { index ->
            moment = moment.plusSeconds(1)
            sink.write(LogLevel.INFO, LogCategory.FEATURE, SqId.sidequest("x"), "line $index padded out a bit", null)
        }

        val files = directory.listDirectoryEntries()
        assertTrue(files.size > 1, "it should have rotated, found ${files.map { it.fileName }}")
        assertTrue(files.any { it.fileName.toString() == "sidequest.log" }, "and there is still a live file")
    }

    /**
     * The cap is a total, not a per-file one.
     *
     * A per-file cap with unlimited files is the same slow disk leak with extra steps.
     */
    @Test
    fun `only the newest rotated files are kept`(@TempDirectory directory: Path) {
        var moment = Instant.parse("2026-01-01T00:00:00Z")
        val sink = RollingFileLogSink(directory, maxBytesPerFile = 100, keep = 2, clock = { moment })

        repeat(40) { index ->
            moment = moment.plusSeconds(1)
            sink.write(LogLevel.INFO, LogCategory.FEATURE, SqId.sidequest("x"), "line $index padded out a bit", null)
        }

        // Two rotated plus the live one.
        assertEquals(3, directory.listDirectoryEntries().size, directory.listDirectoryEntries().toString())
    }

    /**
     * A logging failure must not take the mod down.
     *
     * There is nowhere useful to report one to, and a diagnostic that can crash the thing it is diagnosing is
     * worse than no diagnostic.
     */
    @Test
    fun `an unwritable directory does not throw`(@TempDirectory directory: Path) {
        // A file where the directory should be, so every write fails.
        val blocked = directory.resolve("blocked")
        Files.writeString(blocked, "not a directory")
        val sink = RollingFileLogSink(blocked.resolve("logs"))

        sink.write(LogLevel.ERROR, LogCategory.PLATFORM, SqId.sidequest("x"), "anything", null)
    }

    private fun sink(directory: Path) = RollingFileLogSink(
        directory,
        clock = { Instant.parse("2026-01-01T12:00:00Z") },
        zone = ZoneId.of("UTC"),
    )
}

/** JUnit's own temporary directory, aliased so the intent reads at the call site. */
private typealias TempDirectory = org.junit.jupiter.api.io.TempDir
