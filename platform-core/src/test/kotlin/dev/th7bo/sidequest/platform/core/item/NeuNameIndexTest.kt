package dev.th7bo.sidequest.platform.core.item

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Finding an item's key from the words in its name.
 *
 * Every key below is real, taken from the live repository. That matters more here than usual: the whole
 * claim of this class is that one rule replaces a growing list of per-family ones, and a test built from
 * invented keys would prove that against a world I made up rather than the one the mod runs in.
 */
class NeuNameIndexTest {

    private val index = NeuNameIndex(
        listOf(
            // Ordinary items, where the key is the name.
            "HYPERION", "REVENANT_CATALYST", "JUDGEMENT_CORE", "WARDEN_HEART", "TARANTULA_TALISMAN",
            "POCKET_ESPRESSO_MACHINE", "FOUL_FLESH", "ENCHANTED_BOOK",
            // The families that each needed a rule of their own.
            "RAREFINDER_GARDEN_CHIP", "CROPSHOT_GARDEN_CHIP", "VINYL_PRAY_FOR_ME", "VINYL_WINGS_OF_HARMONY",
            "NECRON_HANDLE", "GIANTS_SWORD",
            // Pets, which exist once per rarity.
            "BABY_YETI;0", "BABY_YETI;3", "BABY_YETI;4", "SLUG;3", "SLUG;4",
            // Variants that must not win over the plain item.
            "CROPIE", "CROPIE_BOOTS", "CROPIE_HELMET", "SLUG_BOOTS",
            "ENCHANTED_BOOK_BUNDLE_POWER", "RAREFINDER_BOOTS",
        ),
    )

    private fun best(name: String) = index.resolve(name).firstOrNull()

    // -- the families that each needed their own rule ------------------------

    /**
     * The three shapes that prompted this, resolved by one rule.
     *
     * A chip's key carries a category word the name does not; a vinyl's carries the same word moved to the
     * front. Searching for the name's words inside the key handles both without either being mentioned.
     */
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
        assertEquals("POCKET_ESPRESSO_MACHINE", best("Pocket Espresso Machine"))
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

    /** Boots that share a word are not the chip, because they are missing one the name has. */
    @Test
    fun `a key missing one of the words does not qualify`() {
        val candidates = index.resolve("Rarefinder Chip")

        assertTrue("RAREFINDER_BOOTS" !in candidates, candidates.toString())
    }

    @Test
    fun `a name sharing no word with anything resolves to nothing`() {
        assertTrue(index.resolve("Completely Unrelated Thing").isEmpty())
        assertTrue(index.resolve("").isEmpty())
    }

    /** More than one plausible answer is offered, best first, so a caller can try them in turn. */
    @Test
    fun `several candidates come back in order`() {
        val candidates = index.resolve("Cropie")

        assertEquals("CROPIE", candidates.first())
        assertTrue(candidates.size > 1, "the variants are still offered: $candidates")
    }

    @Test
    fun `formatting and punctuation do not change the answer`() {
        assertEquals("HYPERION", best("§6Hyperion"))
        assertEquals("REVENANT_CATALYST", best("  revenant   catalyst  "))
    }
}
