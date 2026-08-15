package dev.th7bo.sidequest.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.th7bo.sidequest.backend.NeuFixtures.HOTF
import dev.th7bo.sidequest.backend.NeuFixtures.HOTM
import dev.th7bo.sidequest.backend.NeuFixtures.PRELUDE
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

    // -- the expression language ---------------------------------------------

    private val scope = NeuLisp.scopeOf(PRELUDE)

    @Test
    fun `arithmetic follows the formula`() {
        // Mining Fortune: `(* level 2)`.
        assertEquals("60", scope.render("(* level 2)", mapOf("level" to 30.0)))
        // Mining Speed's cost: `(pow (+ level 2) 3)`.
        assertEquals("125", scope.render("(pow (+ level 2) 3)", mapOf("level" to 3.0)))
    }

    /** A whole number reads as one. "Grants +1000.0 Mining Speed" is not what the game says. */
    @Test
    fun `whole numbers lose their decimal point`() {
        assertEquals("1000", scope.render("(* level 20)", mapOf("level" to 50.0)))
        assertEquals("2.5", scope.render("(round-decimals (/ level 2) 2)", mapOf("level" to 5.0)))
    }

    /**
     * `if` picks a branch rather than evaluating both.
     *
     * Not a style point. Several perks guard a division with it, and evaluating eagerly would divide by zero
     * on the branch that was never meant to run.
     */
    @Test
    fun `a conditional takes only the branch it chose`() {
        assertEquals("200", scope.render("""(if (lt potm 2) "200" "250")""", mapOf("potm" to 0.0)))
        assertEquals("250", scope.render("""(if (lt potm 2) "250" (/ 1 0))""", mapOf("potm" to 0.0)))
    }

    /** The prelude is where `npi`, `api` and the helpers live; every perk is written assuming them. */
    @Test
    fun `prelude functions are callable`() {
        val locked = mapOf("level0" to 0.0, "maxLevel" to 50.0)
        val partial = mapOf("level0" to 20.0, "maxLevel" to 50.0)
        val capped = mapOf("level0" to 50.0, "maxLevel" to 50.0)

        assertEquals("COAL", scope.render("(npi level0 maxLevel)", locked))
        assertEquals("EMERALD", scope.render("(npi level0 maxLevel)", partial))
        assertEquals("DIAMOND", scope.render("(npi level0 maxLevel)", capped))
        assertEquals("COAL_BLOCK", scope.render("(api level0)", locked))
    }

    /** Core of the Mountain's cost is a literal ladder, indexed by level. */
    @Test
    fun `a list is indexed by level`() {
        val expression = "(list.at (list.new 0 50000 100000 200000) level)"

        assertEquals("0", scope.render(expression, mapOf("level" to 0.0)))
        assertEquals("100000", scope.render(expression, mapOf("level" to 2.0)))
        assertNull(scope.render(expression, mapOf("level" to 9.0)), "off the end is nothing, not a crash")
    }

    /**
     * An expression this dialect does not know is null, not an exception.
     *
     * One perk gaining a form nobody anticipated must cost that perk's tooltip and nothing else. It must not
     * take the profile lookup down with it.
     */
    @Test
    fun `an unknown form is a miss rather than a failure`() {
        assertNull(scope.render("(sqrt level)", mapOf("level" to 9.0)))
        assertNull(scope.render("(* level", mapOf("level" to 9.0)), "unbalanced parentheses")
        assertNull(scope.render("(/ level 0)", mapOf("level" to 9.0)), "division by zero")
    }

    // -- perk descriptions ----------------------------------------------------

    private val hotm = requireNotNull(NeuConstants.parseTreeLayout(HOTM, "hotm"))

    @Test
    fun `a perk's stat is interpolated at the level somebody has it`() {
        val perk = requireNotNull(hotm.perks["mining_fortune"])

        assertEquals(listOf("§7Grants §a+§a60 §6☘ Mining Fortune§7."), hotm.describe(perk, level = 30))
        assertEquals("MITHRIL", hotm.powderFor(perk, 30))
        assertEquals(50, perk.maxLevel)
    }

    /**
     * A locked perk still describes itself, at the level taking it would give.
     *
     * `level0` is zero and `level` is one — the split the prelude's own helpers rely on, and why the icon
     * goes to coal while the text still reads as a level-one perk rather than as nothing.
     */
    @Test
    fun `a locked perk shows what taking it would give`() {
        val perk = requireNotNull(hotm.perks["mining_fortune"])

        assertEquals(listOf("§7Grants §a+§a2 §6☘ Mining Fortune§7."), hotm.describe(perk, level = 0))
        assertEquals("minecraft:coal", hotm.itemFor(perk, 0))
        assertEquals("minecraft:emerald", hotm.itemFor(perk, 30))
        assertEquals("minecraft:diamond", hotm.itemFor(perk, 50))
    }

    /**
     * Core of the Mountain's lore grows a line per level, each guarded by its own condition.
     *
     * A level of zero still shows the first line, for the same reason a locked perk shows its level-one
     * stat: the conditions are written against `level`, which is floored at one, so an untaken perk reads
     * as what taking it would give rather than as a blank tooltip.
     */
    @Test
    fun `conditional lore appears only once its level is reached`() {
        val perk = requireNotNull(hotm.perks["core_of_the_mountain"])

        assertEquals(1, hotm.describe(perk, level = 0).size, "untaken shows the first reward, nothing more")
        assertEquals(3, hotm.describe(perk, level = 3).size)
        assertTrue(hotm.describe(perk, level = 3).first().contains("Pickaxe Ability Level"))
        assertEquals(10, hotm.describe(perk, level = 10).size)
    }

    /** The powder a Core costs changes with its level, so its expression has to be evaluated too. */
    @Test
    fun `a perk whose powder is an expression resolves it`() {
        val perk = requireNotNull(hotm.perks["core_of_the_mountain"])

        assertEquals("MITHRIL", hotm.powderFor(perk, 2))
        assertEquals("GEMSTONE", hotm.powderFor(perk, 5))
        assertEquals("GLACITE", hotm.powderFor(perk, 9))
    }

    /**
     * The grid is measured, not assumed.
     *
     * Heart of the Mountain is ten rows and Heart of the Forest seven. Drawing the shorter one on the
     * taller one's grid leaves it floating three rows down its own panel.
     */
    @Test
    fun `the grid extent comes from the tree`() {
        assertEquals(7, hotm.columns)
        assertEquals(10, hotm.rows)

        val hotf = requireNotNull(NeuConstants.parseTreeLayout(HOTF, "hotf"))
        assertEquals(7, hotf.columns)
        assertEquals(7, hotf.rows)
        assertEquals(0, requireNotNull(hotf.perks["half_empty"]).row, "the top row is row zero")
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
