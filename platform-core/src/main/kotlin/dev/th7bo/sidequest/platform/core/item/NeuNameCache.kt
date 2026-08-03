package dev.th7bo.sidequest.platform.core.item

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Keeps the item names on disk, so a restart is not another nine megabytes.
 *
 * What gets written is the *derived* mapping, not the archive: two fields per item, a few hundred kilobytes
 * against nine. Everything else about an item is fetched per item as it always was.
 *
 * It records **which commit it was built from**, and that is what makes a frequent check affordable. The
 * database gains items constantly, so a copy trusted for a week would leave new items without a picture for
 * a week — but re-downloading daily to find out would be nine megabytes to usually learn that nothing had
 * changed. Storing the commit turns the daily question into four hundred bytes.
 *
 * Deliberately not JSON. This is seven thousand rows of two strings, read once at startup, and a tab per
 * line parses in milliseconds where a serialiser would spend most of a second building objects it
 * immediately discards.
 */
public class NeuNameCache(
    private val file: Path,
    /**
     * How long before the commit is checked again.
     *
     * A day. Not how long the data is trusted — a check that finds nothing new keeps the copy — but how
     * often it is worth asking at all.
     */
    private val recheckAfterMillis: Long = DEFAULT_RECHECK,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** What was cached, and what it was built from. */
    public data class Cached(
        public val entries: List<NeuArchive.Entry>,
        /** The commit the archive came from, or empty for a copy written before this was recorded. */
        public val sha: String,
        /** True once it is worth asking whether the commit has moved on. */
        public val shouldRecheck: Boolean,
    )

    /**
     * What is stored, or null when there is nothing usable.
     *
     * Null covers every failure — absent, truncated, from a future version — and they are all the same to
     * the caller, which downloads. A cache that threw would turn a corrupt file into a broken mod.
     *
     * Age does *not* make it null. A stale copy is still the right answer while the network is being asked
     * about it, and still better than nothing if the answer never comes.
     */
    public fun read(): Cached? = runCatching {
        if (!Files.exists(file)) return null
        val lines = Files.readAllLines(file, Charsets.UTF_8)
        val header = lines.firstOrNull()?.split(FIELD) ?: return null
        if (header.size < HEADER_FIELDS || header[0] != FORMAT) return null

        val writtenAt = header[1].toLongOrNull() ?: return null
        val entries = lines.asSequence()
            .drop(1)
            .mapNotNull { line ->
                val split = line.indexOf(FIELD)
                if (split <= 0) return@mapNotNull null
                NeuArchive.Entry(
                    displayName = line.substring(split + 1),
                    internalName = line.substring(0, split),
                )
            }
            .toList()

        if (entries.isEmpty()) {
            null
        } else {
            Cached(
                entries = entries,
                sha = header.getOrElse(2) { "" },
                shouldRecheck = now() - writtenAt > recheckAfterMillis,
            )
        }
    }.getOrNull()

    /**
     * Replaces what is stored.
     *
     * Written beside the real file and moved into place, so an interrupted write leaves the previous copy
     * rather than a half-written one that reads as a much smaller database.
     */
    public fun write(entries: List<NeuArchive.Entry>, sha: String) {
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.newBufferedWriter(temporary, Charsets.UTF_8).use { writer ->
                writer.append(FORMAT).append(FIELD).append(now().toString())
                    .append(FIELD).append(sha).appendLine()
                for (entry in entries) {
                    // A name containing a tab or a newline would break the row it is on. None does, and
                    // skipping is cheaper than an escaping scheme nothing needs.
                    if (entry.displayName.any { it == FIELD || it == '\n' }) continue
                    writer.append(entry.internalName).append(FIELD).append(entry.displayName).appendLine()
                }
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Records that the copy was checked and is current, without rewriting it.
     *
     * The common outcome of a daily check: the commit has not moved, so only the header's timestamp needs
     * to change. Rewriting seven thousand rows to update one number would be the expensive half of a
     * mechanism whose whole point is being cheap.
     */
    public fun touch() {
        runCatching {
            if (!Files.exists(file)) return
            val lines = Files.readAllLines(file, Charsets.UTF_8)
            val header = lines.firstOrNull()?.split(FIELD) ?: return
            if (header.size < HEADER_FIELDS || header[0] != FORMAT) return
            lines[0] = listOf(FORMAT, now().toString(), header.getOrElse(2) { "" }).joinToString(FIELD.toString())
            Files.write(file, lines, Charsets.UTF_8)
        }
    }

    private companion object {
        /** Bumped when the row or header shape changes, so an old file is ignored rather than misread. */
        const val FORMAT = "sqnames2"

        const val FIELD = '\t'

        /** Format and timestamp. The commit is optional, so a file written without one still reads. */
        const val HEADER_FIELDS = 2

        const val DEFAULT_RECHECK = 24L * 60 * 60 * 1000
    }
}
