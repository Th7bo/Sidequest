package dev.th7bo.sidequest.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The tree reader, against the real files.
 *
 * **Skipped unless they are present.** Point the environment at a directory holding `hotm.json` and
 * `hotf.json` and this runs; otherwise the ordinary suite stays offline.
 *
 * It exists because the fixture next door proves the reader understands seven nodes, and the claim is that
 * it understands all hundred. Every one carries its own formula, and one written in a form this arithmetic
 * cannot read would show up in-game as a perk with no description and nowhere else.
 *
 * ```
 * mkdir -p /tmp/trees && cd /tmp/trees
 * curl -sO https://repo.owdding.me/mining/hotm.json
 * curl -sO https://repo.owdding.me/foraging/hotf.json
 * SIDEQUEST_TREE_REPO=/tmp/trees ./gradlew :backend:test --tests '*SkillTreeRepoRealTest*'
 * ```
 */
class SkillTreeRepoRealTest {

    private fun tree(name: String): String? {
        val directory = System.getenv("SIDEQUEST_TREE_REPO")?.let(Path::of) ?: return null
        val file = directory.resolve("$name.json")
        return if (Files.exists(file)) Files.readString(file) else null
    }

    @Test
    fun `every Heart of the Mountain node describes itself`() = check("hotm", atLeast = 40, rows = 10)

    /**
     * The Heart of the Forest, since its revamp.
     *
     * Eight tiers rather than seven, and seven perks that did not exist before. This is the whole reason
     * the trees are read from this repository instead of the one the rest of the tables come from.
     */
    @Test
    fun `every Heart of the Forest node describes itself`() = check("hotf", atLeast = 30, rows = 8)

    private fun check(name: String, atLeast: Int, rows: Int) {
        val body = tree(name)
        assumeTrue(body != null, "set SIDEQUEST_TREE_REPO to run this")

        val layout = requireNotNull(SkillTreeRepo.parse(body!!)) { "$name did not parse" }
        assertTrue(layout.nodes.size >= atLeast, "only ${layout.nodes.size} nodes parsed from $name")
        assertEquals(7, layout.columns, "both trees are seven wide once the furniture is dropped")
        assertEquals(rows, layout.rows)
        assertTrue(layout.nodes.none { it.column < 0 }, "a furniture column survived the read")

        val silent = mutableListOf<String>()
        for (node in layout.nodes) {
            for (level in listOf(1, node.maxLevel)) {
                val variables = mapOf(
                    "level" to level.toDouble(),
                    "effectiveLevel" to 2.0,
                    "hotmLevel" to 10.0,
                )
                if (node.tooltip.isNotEmpty() && SkillTreeRepo.describe(node, variables).isEmpty()) {
                    silent += "${node.id} lore at $level"
                }
            }
            if (node.cost != null && node.level(0) == null) silent += "${node.id} cost"
            // A colour tag that was not understood would be printed rather than converted.
            if (node.tooltip.any { '<' in it || '>' in it }) silent += "${node.id} tags"
        }
        assertTrue(silent.isEmpty(), "nodes this reader could not describe: $silent")
    }

    private fun SkillTreeRepo.Node.level(at: Int): String? = SkillTreeRepo.costOf(this, at)

    /**
     * Every colour the files use is one this converter knows.
     *
     * Checked against the raw text rather than the parsed nodes, because an unfamiliar tag is *dropped* on
     * conversion — the tooltip still reads correctly, minus a colour, and nothing downstream can tell. The
     * only place that mistake is visible is here.
     */
    @Test
    fun `every colour tag in the files is understood`() {
        val bodies = listOfNotNull(tree("hotm"), tree("hotf"))
        assumeTrue(bodies.size == 2, "set SIDEQUEST_TREE_REPO to run this")

        val tags = bodies.flatMap { Regex("<([a-zA-Z_/]+)>").findAll(it).map { match -> match.groupValues[1] } }
            .map { it.lowercase() }
            .toSortedSet()
        assertTrue(tags.size > 8, "only ${tags.size} tags found; the scan is not finding them")

        val unknown = tags.filterNot(SkillTreeRepo::knowsTag)
        assertTrue(unknown.isEmpty(), "colour tags this converter would silently drop: $unknown")
    }

    /** The core is the one node whose description is a list of what it has unlocked so far. */
    @Test
    fun `a core grows a line per level taken`() {
        val body = tree("hotm")
        assumeTrue(body != null, "set SIDEQUEST_TREE_REPO to run this")

        val core = requireNotNull(SkillTreeRepo.parse(body!!))
            .byId.getValue("core_of_the_mountain")

        assertEquals(10, core.maxLevel)
        assertEquals(0, SkillTreeRepo.describe(core, mapOf("level" to 0.0)).size)
        assertTrue(SkillTreeRepo.describe(core, mapOf("level" to 10.0)).size >= 10)
    }
}
