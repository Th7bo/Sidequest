package dev.th7bo.sidequest.platform.core.drops

import dev.th7bo.sidequest.platform.chat.DropRarity
import dev.th7bo.sidequest.platform.chat.TrophyTier
import dev.th7bo.sidequest.platform.skyblock.Island
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Whether a drop is worth interrupting somebody for.
 *
 * The one branchy part of the rare-drop feature, which is why it lives in this module rather than beside the
 * wiring in the mod — that module has no test source set, and leaving the decision there would have made it
 * unreachable. The projector taught that lesson at some cost.
 */
class RareDropPolicyTest {

    private fun decide(
        settings: RareDropSettings = RareDropSettings(),
        item: String = "Hyperion",
        rarity: DropRarity = DropRarity.CRAZY_RARE,
        island: Island = Island.HUB,
    ) = RareDropPolicy.decide(settings, item, rarity, island)

    private fun reasonOf(decision: DropDecision): String =
        (decision as? DropDecision.Skip)?.reason ?: "announced"

    @Test
    fun `a drop above the threshold is announced`() {
        assertTrue(decide().isAnnounced)
    }

    @Test
    fun `the threshold is the main control`() {
        val settings = RareDropSettings(minimumRarity = DropRarity.CRAZY_RARE)

        assertFalse(decide(settings, rarity = DropRarity.RARE).isAnnounced)
        assertFalse(decide(settings, rarity = DropRarity.VERY_RARE).isAnnounced)
        assertTrue(decide(settings, rarity = DropRarity.CRAZY_RARE).isAnnounced)
        assertTrue(decide(settings, rarity = DropRarity.INSANE_RARE).isAnnounced)
        assertTrue(decide(settings, rarity = DropRarity.PRAY_TO_RNGESUS).isAnnounced)
    }

    /**
     * A pet is exempt from the threshold, and this is the case most likely to be got wrong.
     *
     * `PET` sits at the end of the enum because it is a *kind* rather than a tier — Hypixel announces pets on
     * their own line and says nothing about how rare one is. Comparing it against a threshold would announce
     * every pet or none depending on where somebody set the bar, and neither is what they meant.
     */
    @Test
    fun `a pet drop ignores the rarity threshold`() {
        val strict = RareDropSettings(minimumRarity = DropRarity.PRAY_TO_RNGESUS)

        assertTrue(decide(strict, rarity = DropRarity.PET).isAnnounced)
    }

    @Test
    fun `an ignored item is skipped whatever its rarity`() {
        val settings = RareDropSettings(ignoredItems = setOf("Enchanted Hay Bale"))

        val decision = decide(settings, item = "Enchanted Hay Bale", rarity = DropRarity.PRAY_TO_RNGESUS)

        assertFalse(decision.isAnnounced)
        assertTrue("ignore list" in reasonOf(decision), reasonOf(decision))
    }

    @Test
    fun `ignoring is case-insensitive and tolerates stray spacing`() {
        val settings = RareDropSettings(ignoredItems = setOf("enchanted hay bale"))

        assertFalse(decide(settings, item = "Enchanted Hay Bale").isAnnounced)
        assertFalse(decide(settings, item = "  ENCHANTED HAY BALE  ").isAnnounced)
        assertTrue(decide(settings, item = "Enchanted Hay").isAnnounced, "a prefix is a different item")
    }

    @Test
    fun `an ignored island silences everything there and nowhere else`() {
        val settings = RareDropSettings(ignoredIslands = setOf(Island.GARDEN))

        assertFalse(decide(settings, island = Island.GARDEN).isAnnounced)
        assertTrue(decide(settings, island = Island.HUB).isAnnounced)
    }

    @Test
    fun `the master switch beats everything`() {
        val settings = RareDropSettings(isEnabled = false, minimumRarity = DropRarity.RARE)

        assertFalse(decide(settings, rarity = DropRarity.PET).isAnnounced, "including a pet")
        assertTrue("switched off" in reasonOf(decide(settings)))
    }

    /**
     * Every skip says which setting did it.
     *
     * An animation that does not play is indistinguishable from a broken feature, and the person affected is
     * the one who changed the setting — so the reason has to name it rather than say "skipped".
     */
    @Test
    fun `every skip names the setting responsible`() {
        val cases = listOf(
            decide(RareDropSettings(isEnabled = false)),
            decide(RareDropSettings(ignoredItems = setOf("Hyperion"))),
            decide(RareDropSettings(ignoredIslands = setOf(Island.HUB))),
            decide(RareDropSettings(minimumRarity = DropRarity.PRAY_TO_RNGESUS), rarity = DropRarity.RARE),
        )

        for (case in cases) {
            val reason = reasonOf(case)
            assertFalse(case.isAnnounced)
            assertTrue(reason.length > MIN_REASON_LENGTH, "'$reason' does not explain anything")
        }
    }

    // -- trophies ------------------------------------------------------------

    /**
     * Trophy tiers map onto the drop ladder rather than all announcing identically.
     *
     * Fishing produces more trophies than anything else produces drops, so a threshold that did not apply to
     * them would make the feature unusable for the activity that most needs it.
     */
    @Test
    fun `a trophy tier grades onto the rarity ladder`() {
        assertEquals(DropRarity.RARE, RareDropPolicy.rarityOf(TrophyTier.BRONZE))
        assertEquals(DropRarity.INSANE_RARE, RareDropPolicy.rarityOf(TrophyTier.DIAMOND))

        val settings = RareDropSettings(minimumRarity = DropRarity.CRAZY_RARE)
        assertFalse(decide(settings, rarity = RareDropPolicy.rarityOf(TrophyTier.BRONZE)).isAnnounced)
        assertTrue(decide(settings, rarity = RareDropPolicy.rarityOf(TrophyTier.DIAMOND)).isAnnounced)
    }

    @Test
    fun `the tiers are ordered the same way the ladder is`() {
        val mapped = TrophyTier.entries.map { RareDropPolicy.rarityOf(it).ordinal }

        assertEquals(mapped.sorted(), mapped, "a better trophy must never grade lower")
    }

    // -- presentation --------------------------------------------------------

    @ParameterizedTest
    @EnumSource(DropRarity::class)
    fun `every rarity has a headline and a colour`(rarity: DropRarity) {
        assertTrue(RareDropPolicy.headlineFor(rarity).isNotBlank())
        // Opaque and non-black: a colour of zero would draw invisible text on the cinematic's backdrop.
        assertTrue(RareDropPolicy.colourFor(rarity) > 0, "$rarity has no colour")
    }

    private companion object {
        const val MIN_REASON_LENGTH = 10
    }
}
