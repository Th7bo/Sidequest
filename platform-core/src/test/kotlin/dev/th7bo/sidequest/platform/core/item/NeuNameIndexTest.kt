package dev.th7bo.sidequest.platform.core.item

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Finding an item's key from its name.
 *
 * Every pair below is real, read out of the database. That matters more here than usual: the claim being
 * tested is that the stored display names are the only reliable answer, and a test built from invented
 * pairs would prove it against a world where names are tidy — which is exactly the world that does not
 * exist, and exactly why searching the filenames resolved three items in five.
 */
class NeuNameIndexTest {

    private fun entry(name: String, key: String) = NeuArchive.Entry(name, key)

    /**
     * Real pairs, taken from the database.
     *
     * Every one of these is a display name that does *not* derive its key — which is the point. A test
     * built from invented pairs would prove the lookup against a world where names are tidy.
     */
    private val index = NeuNameIndex(
        listOf(
            entry("Hyperion", "HYPERION"),
            entry("Revenant Catalyst", "REVENANT_CATALYST"),
            entry("Judgement Core", "JUDGEMENT_CORE"),
            entry("Rarefinder Chip", "RAREFINDER_GARDEN_CHIP"),
            entry("Pray For Me Vinyl", "VINYL_PRAY_FOR_ME"),
            entry("Wings of Harmony Vinyl", "VINYL_WINGS_OF_HARMONY"),
            entry("Necron's Handle", "NECRON_HANDLE"),
            entry("Giant's Sword", "GIANTS_SWORD"),
            // The ones the old filename search could never have found.
            entry("Rod of Champions", "CHAMP_ROD"),
            entry("Pendant of Divan", "DIVAN_PENDANT"),
            entry("Canopy Shirt", "CANOPY_CHESTPLATE"),
            entry("Tasty Cat Food", "DEAD_CAT_FOOD"),
            entry("Blindness III Potion", "POTION_BLINDNESS;3"),
            entry("Baby Yeti", "BABY_YETI;3"),
            entry("Slug", "SLUG;4"),
            entry("Cropie", "CROPIE"),
            entry("Cropie Boots", "CROPIE_BOOTS"),
            entry("Enchanted Book", "ENCHANTED_BOOK"),
        ),
    )

    private fun best(name: String) = index.resolve(name).firstOrNull()

    // -- the families that each needed their own rule ------------------------

    /**
     * The names whose keys nothing could derive.
     *
     * `Rod of Champions` is `CHAMP_ROD` and `Canopy Shirt` is `CANOPY_CHESTPLATE` — an abbreviation and a
     * different word. Searching the filenames answered neither, which is what sent this to the archive.
     */
    @Test
    fun `a key that shares no word with its name still resolves`() {
        assertEquals("CHAMP_ROD", best("Rod of Champions"))
        assertEquals("DIVAN_PENDANT", best("Pendant of Divan"))
        assertEquals("CANOPY_CHESTPLATE", best("Canopy Shirt"))
        assertEquals("DEAD_CAT_FOOD", best("Tasty Cat Food"))
        assertEquals("POTION_BLINDNESS;3", best("Blindness III Potion"))
    }

    @Test
    fun `the families that needed rules resolve without them`() {
        assertEquals("RAREFINDER_GARDEN_CHIP", best("Rarefinder Chip"))
        assertEquals("VINYL_PRAY_FOR_ME", best("Pray For Me Vinyl"))
        assertEquals("VINYL_WINGS_OF_HARMONY", best("Wings of Harmony Vinyl"))
    }

    /** Both readings of a possessive, because one family keeps the S and another drops it. */
    @Test
    fun `an apostrophe is read both ways`() {
        assertEquals("GIANTS_SWORD", best("Giant's Sword"))
        assertEquals("NECRON_HANDLE", best("Necron's Handle"))
    }

    @Test
    fun `an ordinary item is itself`() {
        assertEquals("HYPERION", best("Hyperion"))
        assertEquals("REVENANT_CATALYST", best("Revenant Catalyst"))
        assertEquals("JUDGEMENT_CORE", best("Judgement Core"))
    }

    /** A pet's rarity suffix is not a word, or no name would ever match one. */
    @Test
    fun `a pet resolves to one of its rarities`() {
        assertTrue(best("Baby Yeti").orEmpty().startsWith("BABY_YETI;"), best("Baby Yeti").toString())
        assertTrue(best("Slug").orEmpty().startsWith("SLUG;"), best("Slug").toString())
    }

    // -- ranking -------------------------------------------------------------

    /**
     * The plain item beats its variants.
     *
     * `Cropie` must not resolve to `CROPIE_BOOTS`. This is the whole of the disambiguation: a key whose
     * words are exactly the name's is the item, and one carrying extra words is something made from it.
     */
    @Test
    fun `an exact match wins over a longer key`() {
        assertEquals("CROPIE", best("Cropie"))
        assertEquals("ENCHANTED_BOOK", best("Enchanted Book"))
    }

    /** The stored spelling wins outright, so a near-miss pass can never override an exact name. */
    @Test
    fun `an exact name is not second-guessed`() {
        assertEquals("CROPIE", index.resolve("Cropie").first())
        assertEquals("CROPIE_BOOTS", index.resolve("Cropie Boots").first())
    }

    @Test
    fun `a name sharing no word with anything resolves to nothing`() {
        assertTrue(index.resolve("Completely Unrelated Thing").isEmpty())
        assertTrue(index.resolve("").isEmpty())
    }

    @Test
    fun `a name the database does not have falls back to its words`() {
        // Not a stored name, but every word of it is in one — the near-miss pass is what catches this.
        assertEquals("CROPIE_BOOTS", best("Boots Cropie"))
    }

    @Test
    fun `formatting and punctuation do not change the answer`() {
        assertEquals("HYPERION", best("§6Hyperion"))
        assertEquals("REVENANT_CATALYST", best("  revenant   catalyst  "))
    }
}
