package dev.th7bo.sidequest.platform.core.item

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Reads the item database's archive.
 *
 * **Why an archive rather than a listing.** The first attempt at this searched the *filenames* for the
 * words in an item's name, on the theory that a key is a mangled display name. Measured against all 8,457
 * real entries, that resolved 60% of them: `Rod of Champions` is `CHAMP_ROD`, `Pendant of Divan` is
 * `DIVAN_PENDANT`, `Canopy Shirt` is `CANOPY_CHESTPLATE`, `Tasty Cat Food` is `DEAD_CAT_FOOD`. Names are
 * abbreviated, reordered, and sometimes use a different word entirely, so nothing derived from the key can
 * be right in general. The display names are only inside the files, which is why every mod that does this
 * downloads the lot.
 *
 * Only two fields are kept per entry. The archive is nine megabytes and seventy-five extracted; the
 * mapping it yields is a few hundred kilobytes, and everything else about an item is fetched per item as
 * it always was.
 *
 * Gzip and tar are read here rather than pulled in. The JDK decompresses, and a tar header is a name, a
 * size and a type at fixed offsets — sixty lines against a library shipped into somebody's game.
 */
public object NeuArchive {

    /** An item's display name, cleaned, and the key it is filed under. */
    public data class Entry(public val displayName: String, public val internalName: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Every named item in a `.tar.gz` of the repository.
     *
     * Entries that cannot be read are skipped rather than failing the lot: one malformed file upstream
     * must not cost every other item its picture.
     */
    public fun read(archive: ByteArray): List<Entry> {
        val entries = ArrayList<Entry>(EXPECTED_ITEMS)
        GZIPInputStream(ByteArrayInputStream(archive)).use { stream ->
            forEachFile(stream.readBytes()) { path, bytes ->
                // `items/` only. The repository also carries recipes, constants and mob data, and reading
                // those would be parsing several megabytes to discard them.
                if (!path.contains("/items/") || !path.endsWith(".json")) return@forEachFile
                parse(bytes)?.let(entries::add)
            }
        }
        return entries
    }

    private fun parse(bytes: ByteArray): Entry? = runCatching {
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val key = root["internalname"]?.jsonPrimitive?.content ?: return@runCatching null
        val display = root["displayname"]?.jsonPrimitive?.content ?: return@runCatching null
        val cleaned = cleanDisplayName(display)
        if (cleaned.isEmpty()) null else Entry(cleaned, key)
    }.getOrNull()

    /**
     * A display name as a person reads it.
     *
     * Formatting codes go, and so does a pet's `[Lvl {LVL}]` — that placeholder is in the stored name and
     * never in the one Hypixel announces, so leaving it in would mean no pet ever matched.
     */
    public fun cleanDisplayName(raw: String): String =
        raw.replace(FORMATTING, "").replace(LEVEL_PLACEHOLDER, "").trim()

    /**
     * Walks a tar, calling [onFile] with each regular file's path and contents.
     *
     * The format is blocks of 512 bytes: a header, then the file's bytes padded up to the next block. The
     * name is the first hundred bytes, the size is octal at 124, and the type is one byte at 156 — `0` or
     * a NUL for a regular file. A header of all zeroes ends the archive.
     */
    private fun forEachFile(tar: ByteArray, onFile: (path: String, bytes: ByteArray) -> Unit) {
        var offset = 0
        while (offset + BLOCK <= tar.size) {
            if (tar.copyOfRange(offset, offset + BLOCK).all { it == ZERO }) break

            val name = readString(tar, offset, NAME_LENGTH)
            if (name.isEmpty()) break
            val size = readOctal(tar, offset + SIZE_OFFSET, SIZE_LENGTH)
            val type = tar[offset + TYPE_OFFSET]
            offset += BLOCK

            if (size > 0 && offset + size <= tar.size && (type == ZERO || type == REGULAR)) {
                onFile(name, tar.copyOfRange(offset, offset + size.toInt()))
            }
            // Rounded up to the next block boundary, which is how a tar is laid out.
            offset += ((size + BLOCK - 1) / BLOCK * BLOCK).toInt()
        }
    }

    private fun readString(tar: ByteArray, at: Int, length: Int): String {
        val end = (at until at + length).firstOrNull { tar[it] == ZERO } ?: (at + length)
        return String(tar, at, end - at, Charsets.UTF_8).trim()
    }

    /** Tar stores its numbers as octal text, padded with spaces or NULs. */
    private fun readOctal(tar: ByteArray, at: Int, length: Int): Long =
        readString(tar, at, length).trim().takeIf { it.isNotEmpty() }?.toLongOrNull(RADIX_OCTAL) ?: 0L

    private const val BLOCK = 512
    private const val NAME_LENGTH = 100
    private const val SIZE_OFFSET = 124
    private const val SIZE_LENGTH = 12
    private const val TYPE_OFFSET = 156
    private const val RADIX_OCTAL = 8

    private const val ZERO: Byte = 0
    private const val REGULAR: Byte = '0'.code.toByte()

    /** Roughly how many items there are, so the list is allocated once. */
    private const val EXPECTED_ITEMS = 9_000

    private val FORMATTING = Regex("§.")

    /** Pets store their level as a placeholder the announced name never carries. */
    private val LEVEL_PLACEHOLDER = Regex("""\[Lvl \{LVL}]\s*""")
}
