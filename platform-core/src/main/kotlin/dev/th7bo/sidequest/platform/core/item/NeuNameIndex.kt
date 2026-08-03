package dev.th7bo.sidequest.platform.core.item

import dev.th7bo.sidequest.platform.parser.HypixelText

/**
 * Finds a SkyBlock item's key from its name.
 *
 * **Built from the database's own display names, and it has to be.** The first version of this searched the
 * *filenames* instead, on the theory that a key is a mangled display name whose words survive. Measured
 * against all 8,457 real entries that resolved 60% of them, and left 38% with no answer at all: `Rod of
 * Champions` is `CHAMP_ROD`, `Pendant of Divan` is `DIVAN_PENDANT`, `Canopy Shirt` is `CANOPY_CHESTPLATE`,
 * `Tasty Cat Food` is `DEAD_CAT_FOOD`. Names are abbreviated, reordered, and sometimes use a different word
 * entirely. Nothing derived from a key can be right in general, which is why every mod that does this
 * downloads the repository — the answer is only inside the files.
 *
 * With the real names, a lookup is a map read: the cleaned display name is unique for 7,260 of the 7,381
 * distinct ones.
 *
 * The word search is kept as a second pass, for a name that arrives slightly different from the stored one —
 * a suffix Hypixel adds in chat, a stray article. It is a fallback now rather than the mechanism.
 */
public class NeuNameIndex(entries: Collection<NeuArchive.Entry>) {

    /** Cleaned display name to the keys under it. A list because a few names are shared. */
    private val byName: Map<String, List<String>>

    /** Word to the keys whose *display name* contains it, for the near-miss pass. */
    private val byWord: Map<String, List<String>>

    /** How many items are known, for the inspector. */
    public val size: Int = entries.size

    init {
        val names = HashMap<String, MutableList<String>>()
        val words = HashMap<String, MutableList<String>>()
        for (entry in entries) {
            names.getOrPut(normalise(entry.displayName)) { ArrayList() }.add(entry.internalName)
            for (word in wordsOf(entry.displayName).toSet()) {
                words.getOrPut(word) { ArrayList() }.add(entry.internalName)
            }
        }
        byName = names
        byWord = words
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
        val cleaned = NeuArchive.cleanDisplayName(HypixelText.clean(displayName))
        val found = LinkedHashSet<String>()

        // The name as the database spells it. This is the answer for almost everything, and it is a map
        // read rather than a search — no ranking, no guessing, no way to be subtly wrong.
        byName[normalise(cleaned)]?.let(found::addAll)
        if (found.size >= limit) return found.take(limit)

        // Only then the words, for a name that arrived a little different from the stored one.
        for (key in matching(wordsOf(cleaned))) {
            found.add(key)
            if (found.size >= limit) break
        }
        return found.toList()
    }

    private fun matching(words: List<String>): List<String> {
        if (words.isEmpty()) return emptyList()
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

        /**
         * A name reduced to what two spellings of it have in common.
         *
         * Case, punctuation and spacing go, so `Necron's Handle` and `Necrons Handle` are one key. The
         * database's own spelling is authoritative; this only stops a stray apostrophe from missing it.
         */
        internal fun normalise(value: String): String =
            value.uppercase().replace(SEPARATORS, "")

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
