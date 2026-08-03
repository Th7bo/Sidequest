package dev.th7bo.sidequest.platform.core.item

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The archive reader, against a real archive.
 *
 * **Skipped unless one is present.** Point `SIDEQUEST_NEU_ARCHIVE` at a `.tar.gz` of the item repository
 * and this runs; otherwise it is quietly ignored, so the ordinary suite needs no network and no nine
 * megabyte file in the repository.
 *
 * It exists because the two things it checks cannot be checked any other way. A tar parser tested against a
 * tar I wrote proves my writer and my reader agree, which is not the claim — the claim is that it reads
 * what GitHub actually serves. And the accuracy figure is the entire justification for downloading an
 * archive at all: searching the *filenames* instead resolved 60% of items, and the only way to know that
 * was to measure it against every real name.
 *
 * ```
 * curl -sL -o /tmp/neu.tar.gz \
 *   https://codeload.github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/tar.gz/refs/heads/master
 * SIDEQUEST_NEU_ARCHIVE=/tmp/neu.tar.gz ./gradlew :platform-core:test --tests '*NeuArchiveRealTest*'
 * ```
 */
class NeuArchiveRealTest {

    private fun archive(): ByteArray? {
        val path = System.getenv("SIDEQUEST_NEU_ARCHIVE")?.let(Path::of) ?: return null
        return if (Files.exists(path)) Files.readAllBytes(path) else null
    }

    @Test
    fun `the reader finds the items in a real archive`() {
        val bytes = archive()
        assumeTrue(bytes != null, "set SIDEQUEST_NEU_ARCHIVE to run this")

        val entries = NeuArchive.read(bytes!!)

        assertTrue(entries.size > MINIMUM_ITEMS, "only found ${entries.size} items")
        // A name the reader must have got right, and one whose key it could never have guessed.
        val byName = entries.associateBy { it.displayName }
        assertEquals("HYPERION", byName["Hyperion"]?.internalName)
        assertEquals("CHAMP_ROD", byName["Rod of Champions"]?.internalName)
    }

    /** A pet's stored name carries a level placeholder the announced one never does. */
    @Test
    fun `a pet's level placeholder is stripped`() {
        val bytes = archive()
        assumeTrue(bytes != null, "set SIDEQUEST_NEU_ARCHIVE to run this")

        val entries = NeuArchive.read(bytes!!)

        val yeti = entries.filter { it.internalName.startsWith("BABY_YETI;") }
        assertTrue(yeti.isNotEmpty(), "no Baby Yeti in the archive")
        assertTrue(
            yeti.all { it.displayName == "Baby Yeti" },
            "a placeholder survived: ${yeti.map { it.displayName }.distinct()}",
        )
    }

    /**
     * How much of the database the index can actually answer for.
     *
     * The number that decided the design. Searching filenames scored 60%; anything built from the real
     * names should be near-perfect, and a regression here means a lookup silently stopped working for a
     * slice of items rather than failing loudly.
     */
    @Test
    fun `the index resolves virtually every real name`() {
        val bytes = archive()
        assumeTrue(bytes != null, "set SIDEQUEST_NEU_ARCHIVE to run this")

        val entries = NeuArchive.read(bytes!!)
        val index = NeuNameIndex(entries)
        val valid = entries.groupBy({ it.displayName }, { it.internalName })

        var correct = 0
        val failures = ArrayList<String>()
        for ((name, keys) in valid) {
            if (index.resolve(name).firstOrNull() in keys) correct++ else failures += name
        }

        val percent = correct * 100.0 / valid.size
        assertTrue(
            percent > REQUIRED_ACCURACY,
            "only $percent% of ${valid.size} names resolved; first failures: ${failures.take(10)}",
        )
    }

    private companion object {
        const val MINIMUM_ITEMS = 5_000

        /** Filename search managed sixty. Anything built from the real names should be far past this. */
        const val REQUIRED_ACCURACY = 99.0
    }
}
