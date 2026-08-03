package dev.th7bo.sidequest.platform.core.item

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeping the item names between runs.
 *
 * The point of this file is to make a daily freshness check affordable. Getting it wrong is not a crash —
 * it is nine megabytes on every launch, or an item that has no picture for a week. Both are quiet, so both
 * are worth pinning.
 */
class NeuNameCacheTest {

    @TempDir
    lateinit var directory: Path

    private var clock = 1_000_000L

    private fun cache(recheckAfter: Long = DAY) =
        NeuNameCache(directory.resolve("names.tsv"), recheckAfter, now = { clock })

    private val entries = listOf(
        NeuArchive.Entry("Hyperion", "HYPERION"),
        NeuArchive.Entry("Rod of Champions", "CHAMP_ROD"),
        NeuArchive.Entry("Baby Yeti", "BABY_YETI;3"),
    )

    @Test
    fun `what is written comes back`() {
        cache().write(entries, sha = "abc")

        val read = cache().read()

        assertEquals(entries, read?.entries)
        assertEquals("abc", read?.sha)
        assertFalse(read!!.shouldRecheck, "just written, so nothing to ask about yet")
    }

    /** After a day it is worth asking whether the database has moved on — but the copy is still good. */
    @Test
    fun `an old copy asks to be rechecked without being discarded`() {
        cache().write(entries, sha = "abc")
        clock += DAY * 2

        val read = cache().read()

        assertTrue(read!!.shouldRecheck)
        assertEquals(entries, read.entries, "still usable while the question is being asked")
    }

    /**
     * A check that finds nothing new costs one header rewrite.
     *
     * The common outcome, and the reason the commit is stored at all: rewriting seven thousand rows to
     * update one timestamp would be the expensive half of a mechanism meant to be cheap.
     */
    @Test
    fun `touching defers the next check without rewriting the rows`() {
        cache().write(entries, sha = "abc")
        clock += DAY * 2
        assertTrue(cache().read()!!.shouldRecheck)

        cache().touch()

        val read = cache().read()!!
        assertFalse(read.shouldRecheck, "the clock restarted")
        assertEquals(entries, read.entries)
        assertEquals("abc", read.sha, "and it still knows which commit it came from")
    }

    // -- refusing what it cannot trust ---------------------------------------

    @Test
    fun `nothing written reads as nothing`() {
        assertNull(cache().read())
    }

    /** A truncated or scribbled-on file is not half a database. */
    @Test
    fun `a corrupt file is refused rather than half read`() {
        val file = directory.resolve("names.tsv")
        Files.writeString(file, "this is not a cache")

        assertNull(cache().read())
    }

    /** A file from a future format is ignored, not misread as the current one. */
    @Test
    fun `an unknown format is refused`() {
        Files.writeString(directory.resolve("names.tsv"), "sqnames99\t${clock}\tabc\nHYPERION\tHyperion")

        assertNull(cache().read())
    }

    @Test
    fun `a header with no rows is refused`() {
        Files.writeString(directory.resolve("names.tsv"), "sqnames2\t$clock\tabc")

        assertNull(cache().read())
    }

    /**
     * Writing does not leave a half-file behind on failure.
     *
     * Checked by its observable consequence: after a write there is exactly one file, so nothing has been
     * left beside it for a later read to trip over.
     */
    @Test
    fun `writing leaves no temporary file`() {
        cache().write(entries, sha = "abc")

        val files = Files.list(directory).use { it.toList() }
        assertEquals(1, files.size, files.toString())
    }

    /** A name is not allowed to break the row it is on. None in the real database does. */
    @Test
    fun `a name containing a separator is skipped rather than corrupting the file`() {
        cache().write(entries + NeuArchive.Entry("Bad\tName", "BAD"), sha = "abc")

        val read = cache().read()!!
        assertEquals(entries, read.entries)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
