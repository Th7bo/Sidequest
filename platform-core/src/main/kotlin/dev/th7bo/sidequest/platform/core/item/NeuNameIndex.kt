package dev.th7bo.sidequest.platform.core.item

import dev.th7bo.sidequest.platform.parser.HypixelText

/**
 * Finds a SkyBlock item's key from the words in its name.
 *
 * **The general answer to a problem that was being solved one family at a time.** A display name does not
 * derive its key: `Rarefinder Chip` is `RAREFINDER_GARDEN_CHIP`, `Pray For Me Vinyl` is
 * `VINYL_PRAY_FOR_ME`, `Necron's Handle` is `NECRON_HANDLE`. Every one of those needed its own rule, and
 * there are dozens more families — runes, gemstones, shards, kits — each of which would have needed
 * another, discovered by somebody noticing an item with no picture.
 *
 * So this stops trying to *transform* a name and searches instead. Given every key the database has, an
 * item is the key whose words are the name's words. That is one rule for all of them, and the families
 * above fall out of it without being mentioned.
 *
 * **Keys only, not display names.** The mod that usually reads this data downloads the entire repository —
 * thousands of files — to build an index of display names. This needs the *filenames*, which is one listing
 * and a fraction of the bytes, and works because a key is a mangled display name: the words survive even
 * when the order and the extra category words do not.
 *
 * The trade is honest: this cannot resolve a name that shares no word with its key. Nothing observed does.
 */
public class NeuNameIndex(keys: Collection<String>) {

    /** Word to the keys containing it. Built once; the whole point is that lookups touch no network. */
    private val byWord: Map<String, List<String>>

    /** Every key, so a caller can ask how much is known. */
    public val size: Int = keys.size

    init {
        val index = HashMap<String, MutableList<String>>()
        for (key in keys) {
            for (word in wordsOf(key).toSet()) {
                index.getOrPut(word) { ArrayList() }.add(key)
            }
        }
        byWord = index
    }

    /**
     * The keys an item called [displayName] might be, best first.
     *
     * A key qualifies when it contains *every* word of the name — so `Rarefinder Chip` reaches
     * `RAREFINDER_GARDEN_CHIP`, which has a word the name does not, but not `RAREFINDER_BOOTS`, which is
     * missing one the name has.
     *
     * Ranked by how much else the key carries. A key whose words are exactly the name's is the item; one
     * with extra words is a variant of it — `Cropie` before `CROPIE_BOOTS`, `Enchanted Book` before
     * `ENCHANTED_BOOK_BUNDLE_POWER` — and that ordering is the whole of the disambiguation.
     */
    public fun resolve(displayName: String, limit: Int = DEFAULT_LIMIT): List<String> {
        // Both readings of a possessive, for the same reason the direct lookup tries both: `Giant's Sword`
        // keeps its S and `Necron's Handle` loses it, and no rule produces both.
        // Cleaned first. A name arrives from chat carrying formatting, and `§6` splits into the word "6",
        // which is in no key — so an uncleaned name matched nothing at all rather than matching loosely.
        val cleaned = HypixelText.clean(displayName)
        val readings = listOf(
            wordsOf(cleaned.replace("'", "").replace("’", "")),
            wordsOf(cleaned.replace(POSSESSIVE, "")),
        ).filter { it.isNotEmpty() }.distinct()

        val found = LinkedHashSet<String>()
        for (words in readings) {
            for (key in matching(words)) {
                found.add(key)
                if (found.size >= limit) return found.toList()
            }
        }
        return found.toList()
    }

    private fun matching(words: List<String>): List<String> {
        // Start from the rarest word, so the intersection begins as small as possible. On a name like
        // "Enchanted Book" the difference is a list of two against a list of nine hundred.
        val postings = words.map { byWord[it] ?: return emptyList() }
        val rarest = postings.minBy { it.size }

        return rarest
            .filter { key ->
                val keyWords = wordsOf(key).toSet()
                words.all { it in keyWords }
            }
            .sortedWith(
                // Fewest extra words first — an exact set is the item and anything longer is a variant of
                // it. Then the shorter key, then alphabetical, so the order never depends on file listing.
                compareBy({ wordsOf(it).size - words.size }, { it.length }, { it }),
            )
    }

    public companion object {
        public const val DEFAULT_LIMIT: Int = 4

        private val SEPARATORS = Regex("[^A-Za-z0-9]+")

        /** A trailing `'s`, which one family keeps and another drops. */
        private val POSSESSIVE = Regex("['’]s\\b", RegexOption.IGNORE_CASE)

        /**
         * The words of a key or a name.
         *
         * A pet's rarity suffix is cut before splitting: `BABY_YETI;3` is a Baby Yeti, and letting the `3`
         * become a word would mean no name ever matched a pet.
         */
        internal fun wordsOf(value: String): List<String> =
            value.substringBefore(';').uppercase().split(SEPARATORS).filter { it.isNotEmpty() }
    }
}
