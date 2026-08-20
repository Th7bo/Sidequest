package dev.th7bo.sidequest.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.th7bo.sidequest.backend.NeuFixtures.PETS
import dev.th7bo.sidequest.backend.NeuFixtures.SACKS
import dev.th7bo.sidequest.backend.NeuFixtures.SHARDS

/**
 * The NotEnoughUpdates tables, read the way the profile route reads them.
 *
 * Every fixture here is copied verbatim out of the live repository rather than written to suit the test —
 * the prelude, the perk formulas, the shard entries, a sack's contents. That is the point: what is being
 * checked is that this code understands the real files, and a fixture invented to be easy would check
 * nothing at all.
 */
class NeuConstantsTest {

    // -- pet levels ------------------------------------------------------------

    private val pets = requireNotNull(NeuConstants.parsePets(PETS))

    /**
     * A pet's rarity decides where on the ladder it starts, not how fast it climbs.
     *
     * A legendary pet skips the first twenty rungs. With the fixture's ladder that leaves it nothing to
     * climb, while a common pet with the same experience is several levels up — which is the whole reason
     * this cannot be a formula over experience alone.
     */
    @Test
    fun `rarity decides where a pet starts on the ladder`() {
        // 100 + 110 + 120 = 330 buys three levels of a common pet, and part of a fourth.
        assertEquals(4, pets.levelOf("TIGER", "COMMON", 340.0).level)
        assertEquals(1, pets.levelOf("TIGER", "COMMON", 99.0).level)
        // Epic starts at rung 16, where a level costs 440 — so the same experience buys nothing.
        assertEquals(1, pets.levelOf("TIGER", "EPIC", 340.0).level)
        assertEquals(2, pets.levelOf("TIGER", "EPIC", 440.0).level)
    }

    /** Progress is measured against the *next* rung, not against the level just finished. */
    @Test
    fun `progress runs from one level to the next`() {
        // Three levels bought for 330; the fourth rung costs 130, and 65 of it is paid.
        val halfway = pets.levelOf("TIGER", "COMMON", 330.0 + 65.0)

        assertEquals(4, halfway.level)
        assertEquals(0.5, halfway.progress, 1e-9)
    }

    /** At the cap there is nothing left to climb, and the bar reads full rather than empty. */
    @Test
    fun `a capped pet reports its cap`() {
        val maxed = pets.levelOf("TIGER", "COMMON", 1_000_000.0)

        // Twenty rungs climbed from level one. The real ladder has ninety-nine of them, which is the cap;
        // this reports how far it could count rather than claiming a level the table never described.
        assertEquals(21, maxed.level)
        assertEquals(1.0, maxed.progress)
        assertEquals(100, maxed.maxLevel, "the cap it is measured against is still the pet's own")
    }

    /** Three dragons go to two hundred, on a ladder of their own appended to everybody else's. */
    @Test
    fun `a dragon levels past a hundred on its own ladder`() {
        assertEquals(200, pets.maxLevel("GOLDEN_DRAGON"))
        assertEquals(100, pets.maxLevel("TIGER"))
        // Legendary skips the twenty shared rungs and lands on the dragon's own, at 1,886,700 apiece — which
        // is why a Golden Dragon reads as level one until it has more experience than most pets ever see.
        assertEquals(1, pets.levelOf("GOLDEN_DRAGON", "LEGENDARY", 1_000_000.0).level)
        assertEquals(3, pets.levelOf("GOLDEN_DRAGON", "LEGENDARY", 1_886_700.0 * 2).level)
        // A legendary pet with no ladder of its own has nothing left to climb at all.
        assertEquals(1, pets.levelOf("TIGER", "LEGENDARY", 340.0).level)
    }

    /** A Bingo pet ignores rarity entirely, which is a per-pet override rather than a rule. */
    @Test
    fun `a pet may override the rarity offsets`() {
        assertEquals(
            pets.levelOf("BINGO", "COMMON", 340.0).level,
            pets.levelOf("BINGO", "LEGENDARY", 340.0).level,
        )
    }

    /** The two names a profile cannot supply itself: the pet's real name and its held item's. */
    @Test
    fun `names come from the table rather than the id`() {
        assertEquals("T-Rex", pets.displayNames["TYRANNOSAURUS"])
        assertEquals("§6Tier Boost", pets.itemNames["PET_ITEM_TIER_BOOST"])
        assertEquals("§9Lucky Clover", pets.itemNames["PET_ITEM_LUCKY_CLOVER"])
    }

    // -- sacks ----------------------------------------------------------------

    @Test
    fun `sacks carry their contents and their own item`() {
        val sacks = NeuConstants.parseSacks(SACKS)

        val agronomy = sacks.single { it.name == "Agronomy" }
        assertEquals("agronomy", agronomy.id)
        assertEquals("LARGE_AGRONOMY_SACK", agronomy.itemId)
        assertTrue("WHEAT" in agronomy.contents)
        assertEquals("enchanted_agronomy", sacks.single { it.name == "Enchanted Agronomy" }.id)
    }

    /**
     * One character decides whether a Fishing sack is mostly empty.
     *
     * NotEnoughUpdates files a variant item as `INK_SACK-3`, because a colon cannot be a filename; Hypixel
     * reports the same thing as `INK_SACK:3`. Comparing them raw drops every variant item silently.
     */
    @Test
    fun `variant item ids from either database compare equal`() {
        assertEquals(
            NeuConstants.normaliseItemId("INK_SACK-3"),
            NeuConstants.normaliseItemId("INK_SACK:3"),
        )
        assertEquals("LOG_2-1", NeuConstants.normaliseItemId("log_2:1"))
    }

    // -- attribute shards ------------------------------------------------------

    /**
     * The item a shard draws as is filed under the ability it grants, not under the shard.
     *
     * `SHARD_GROVE` is `ATTRIBUTE_SHARD_NATURE_ELEMENTAL;1`. Nothing derives one from the other, which is
     * exactly why the table has to be read rather than the name transformed.
     */
    @Test
    fun `a shard resolves to the item it is actually filed under`() {
        val shards = NeuConstants.parseShards(SHARDS)
        val index = NeuConstants.index(shards)

        val grove = requireNotNull(index["grove"])
        assertEquals("Grove", grove.name)
        assertEquals("COMMON", grove.rarity)
        assertEquals("ATTRIBUTE_SHARD_NATURE_ELEMENTAL;1", grove.itemId)
    }

    /** Which spelling a profile uses is undocumented, so every one it might use finds the shard. */
    @Test
    fun `a shard is reachable under all of its names`() {
        val index = NeuConstants.index(NeuConstants.parseShards(SHARDS))

        for (spelling in listOf("SHARD_GROVE", "shard_grove", "Grove", "NATURE_ELEMENTAL", "ATTRIBUTE_SHARD_NATURE_ELEMENTAL")) {
            assertNotNull(index[NeuConstants.shardKey(spelling)], "no shard found for $spelling")
        }
    }

}
