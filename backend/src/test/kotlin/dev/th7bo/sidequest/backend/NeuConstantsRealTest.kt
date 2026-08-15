package dev.th7bo.sidequest.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The layout readers, against the real files.
 *
 * **Skipped unless they are present.** Point the environment at a checkout of the constants directory and
 * this runs; otherwise the ordinary suite stays offline.
 *
 * It exists because the hand-written fixtures next door prove the evaluator understands *four* perks, and
 * the claim being made is that it understands all seventy-five. Every one of them is a separate formula,
 * and one using a form this dialect lacks would show up in-game as a perk with no description and nowhere
 * else. So the check is blunt and total: every perk in both trees, at three levels, must produce a
 * description with no placeholder left unfilled.
 *
 * ```
 * git clone --depth 1 https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO /tmp/neu
 * SIDEQUEST_NEU_CONSTANTS=/tmp/neu/constants ./gradlew :backend:test --tests '*NeuConstantsRealTest*'
 * ```
 */
class NeuConstantsRealTest {

    private fun constant(name: String): String? {
        val directory = System.getenv("SIDEQUEST_NEU_CONSTANTS")?.let(Path::of) ?: return null
        val file = directory.resolve("$name.json")
        return if (Files.exists(file)) Files.readString(file) else null
    }

    @Test
    fun `every Heart of the Mountain perk describes itself`() = checkTree("hotmlayout", "hotm", atLeast = 40)

    @Test
    fun `every Heart of the Forest perk describes itself`() = checkTree("hotflayout", "hotf", atLeast = 25)

    /**
     * [atLeast] is a floor, not a count, and deliberately.
     *
     * Hypixel revamps these trees — the Heart of the Forest gained a tier — and pinning the exact number
     * would turn every upstream update into a failing build for a change that is entirely expected. What
     * is worth catching is the opposite: a parse that silently returned two perks out of forty.
     */
    private fun checkTree(file: String, root: String, atLeast: Int) {
        val body = constant(file)
        assumeTrue(body != null, "set SIDEQUEST_NEU_CONSTANTS to run this")

        val layout = requireNotNull(NeuConstants.parseTreeLayout(body!!, root)) { "$file did not parse" }
        assertTrue(layout.perks.size >= atLeast, "only ${layout.perks.size} perks parsed from $file")

        val silent = mutableListOf<String>()
        for (perk in layout.perks.values) {
            for (level in listOf(0, 1, perk.maxLevel)) {
                if (perk.lore.isEmpty()) continue
                // A perk whose lore is entirely conditional is legitimately quiet until its level is up,
                // so it is only required to have something to say once it is capped.
                val required = perk.lore.any { it.onlyIf == null } || level == perk.maxLevel
                if (required && layout.describe(perk, level, peakOfTheMountain = 1).isEmpty()) {
                    silent += "${perk.id} lore at $level"
                }
            }
            if (perk.powder.isNotEmpty() && layout.powderFor(perk, 1).isEmpty()) silent += "${perk.id} powder"
            if (perk.item.isNotEmpty() && layout.itemFor(perk, 1) == null) silent += "${perk.id} item"
            // The cost is not shown anywhere — the off-by-one between "cost of this level" and "cost of the
            // next" is not settled — but it is still evaluated here, because it is where the list forms and
            // the fractional exponents live and nothing else in the file would exercise them.
            if (perk.cost.isNotEmpty() && layout.scope.render(perk.cost, costVariables(perk)) == null) {
                silent += "${perk.id} cost"
            }
        }
        assertTrue(silent.isEmpty(), "expressions this dialect could not evaluate: $silent")
    }

    private fun costVariables(perk: NeuConstants.Perk) = mapOf(
        "level" to 1.0,
        "level0" to 1.0,
        "maxLevel" to perk.maxLevel.toDouble(),
        "potm" to 1.0,
    )

    /**
     * The grid is measured from the tree, and the two trees are not the same shape.
     *
     * The exact numbers are not asserted — a tier added upstream changes them and should — but the forest
     * being *shorter* than the mountain is the property the drawing code depends on, and the one that was
     * wrong when the mountain's height was hard-coded for both.
     */
    @Test
    fun `each tree reports its own shape`() {
        val mountain = constant("hotmlayout")
        val forest = constant("hotflayout")
        assumeTrue(mountain != null && forest != null, "set SIDEQUEST_NEU_CONSTANTS to run this")

        val hotm = requireNotNull(NeuConstants.parseTreeLayout(mountain!!, "hotm"))
        val hotf = requireNotNull(NeuConstants.parseTreeLayout(forest!!, "hotf"))

        assertEquals(7, hotm.columns, "both trees are seven wide in the game's menu")
        assertEquals(7, hotf.columns)
        assertTrue(hotm.rows in 8..16, "implausible mountain height: ${hotm.rows}")
        assertTrue(hotf.rows in 5..16, "implausible forest height: ${hotf.rows}")
        assertTrue(hotf.rows < hotm.rows, "the forest is the shorter tree: ${hotf.rows} against ${hotm.rows}")
    }

    /**
     * The alias table covers exactly the perks whose ids differ, and nothing else.
     *
     * A stale entry is worse than a missing one — it would read a level out of a key Hypixel does not send
     * and report the perk as untaken — so every alias must still name a perk that exists.
     */
    @Test
    fun `the Hypixel id aliases all name real perks`() {
        val body = constant("hotmlayout")
        assumeTrue(body != null, "set SIDEQUEST_NEU_CONSTANTS to run this")

        val layout = requireNotNull(NeuConstants.parseTreeLayout(body!!, "hotm"))
        val unknown = hotmNodeIdsForTest().keys - layout.perks.keys
        assertTrue(unknown.isEmpty(), "aliases for perks that no longer exist: $unknown")
    }

    /** Every shard the table names resolves to an item id, and to itself under each of its spellings. */
    @Test
    fun `every attribute shard resolves to a drawable item`() {
        val body = constant("attribute_shards")
        assumeTrue(body != null, "set SIDEQUEST_NEU_CONSTANTS to run this")

        val shards = NeuConstants.parseShards(body!!)
        assertTrue(shards.size > 100, "only ${shards.size} shards parsed")
        assertTrue(shards.all { it.itemId.startsWith("ATTRIBUTE_SHARD_") }, "a shard is filed somewhere else now")

        val index = NeuConstants.index(shards)
        val missing = shards.filter { index[it.id] == null }
        assertTrue(missing.isEmpty(), "shards unreachable under their own name: ${missing.map { it.name }}")
    }

    /** Sacks exist, name their own item, and between them account for a real player's item spread. */
    @Test
    fun `the sack table covers the game's sacks`() {
        val body = constant("sacks")
        assumeTrue(body != null, "set SIDEQUEST_NEU_CONSTANTS to run this")

        val sacks = NeuConstants.parseSacks(body!!)
        assertTrue(sacks.size >= 20, "only ${sacks.size} sacks parsed")
        assertTrue(sacks.all { it.id.isNotEmpty() && it.contents.isNotEmpty() })
        assertTrue(sacks.any { it.contents.any { id -> '-' in id } }, "no variant ids: the normaliser is untested here")
    }
}
