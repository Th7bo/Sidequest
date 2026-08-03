package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.theme.DarkTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Choosing by direction.
 *
 * Nobody reads a radial menu after the first few uses — the hand learns "danger is down-left" and the labels
 * become confirmation rather than navigation. So the geometry is the feature, and every test here is about a
 * way of getting it subtly wrong: an option that cannot be selected by pushing straight at it, a boundary
 * that lands on the wrong side, or a wrap-around that only fails at one angle out of the whole circle.
 */
class RadialMenuTest {

    /**
     * Pushing exactly at an option selects that option.
     *
     * The one that would break if sectors started at their direction instead of being centred on it — every
     * option would then sit on the boundary between itself and its neighbour, and pushing dead at one would
     * be a coin flip.
     */
    @Test
    fun `pushing straight at an option selects it`() {
        for (count in 2..8) {
            for (index in 0 until count) {
                val angle = RadialMenuNode.sectorCentre(index, count)
                val push = Vec2(cos(angle) * PUSH, sin(angle) * PUSH)

                assertEquals(
                    index,
                    RadialMenuNode.sectorAt(RadialMenuNode.angleOf(push), count),
                    "option $index of $count, pushed at its own direction",
                )
            }
        }
    }

    /** The first option is at the top, which is where somebody looking at a menu expects to find it. */
    @Test
    fun `the first option is straight up`() {
        val up = Vec2(0f, -PUSH)

        assertEquals(0, RadialMenuNode.sectorAt(RadialMenuNode.angleOf(up), 4))
        assertEquals(0, RadialMenuNode.sectorAt(RadialMenuNode.angleOf(up), 8))
    }

    /** Screen coordinates put positive Y downwards, which is easy to get backwards. */
    @Test
    fun `the options run clockwise from the top`() {
        val count = 4
        fun at(x: Float, y: Float) = RadialMenuNode.sectorAt(RadialMenuNode.angleOf(Vec2(x, y)), count)

        assertEquals(0, at(0f, -PUSH), "up")
        assertEquals(1, at(PUSH, 0f), "right")
        assertEquals(2, at(0f, PUSH), "down")
        assertEquals(3, at(-PUSH, 0f), "left")
    }

    /**
     * Every direction lands on some option, including the seam.
     *
     * A wrap-around bug typically fails at exactly one angle out of the whole circle, which is precisely the
     * kind of thing a handful of hand-picked cases misses.
     */
    @Test
    fun `every angle selects exactly one option`() {
        for (count in 2..8) {
            for (step in 0 until 720) {
                val angle = (step / 720f) * TAU - HALF_TURN
                val push = Vec2(cos(angle) * PUSH, sin(angle) * PUSH)

                val sector = RadialMenuNode.sectorAt(RadialMenuNode.angleOf(push), count)
                assertTrue(sector in 0 until count, "angle $angle of $count gave $sector")
            }
        }
    }

    /** Each option owns an equal share of the circle, or the easy directions would be worth more. */
    @Test
    fun `the sectors are the same size`() {
        val count = 6
        val hits = IntArray(count)
        for (step in 0 until 3600) {
            val angle = (step / 3600f) * TAU - HALF_TURN
            val push = Vec2(cos(angle) * PUSH, sin(angle) * PUSH)
            hits[RadialMenuNode.sectorAt(RadialMenuNode.angleOf(push), count)]++
        }

        val expected = 3600 / count
        for ((index, count1) in hits.withIndex()) {
            assertTrue(
                kotlin.math.abs(count1 - expected) <= 2,
                "option $index got $count1 of the circle, expected about $expected: ${hits.toList()}",
            )
        }
    }

    // -- the dead zone -------------------------------------------------------

    /**
     * Opening the menu and letting go without moving chooses nothing.
     *
     * A radial menu that selected whatever happened to be under a resting pointer would fire a ping every
     * time somebody brushed the key.
     */
    @Test
    fun `nothing is selected near the centre`() {
        val menu = menuOf(4)

        menu.pointer = Vec2.Zero
        assertNull(menu.selected)

        menu.pointer = Vec2(RadialMenuNode.DEAD_ZONE - 1f, 0f)
        assertNull(menu.selected, "still inside the dead zone")

        menu.pointer = Vec2(RadialMenuNode.DEAD_ZONE + 1f, 0f)
        assertNotNull(menu.selected, "and outside it, a choice")
    }

    /**
     * Pushing further cannot change the choice.
     *
     * Distance means confidence, not direction. A menu that deselected when the pointer went too far would
     * punish the decisive flick it is meant to reward.
     */
    @Test
    fun `distance past the dead zone does not change the choice`() {
        val menu = menuOf(6)
        val angle = RadialMenuNode.sectorCentre(3, 6)

        val choices = listOf(20f, 60f, 200f, 5000f).map { distance ->
            menu.pointer = Vec2(cos(angle) * distance, sin(angle) * distance)
            menu.selected?.id
        }

        assertEquals(1, choices.distinct().size, "the choice drifted with distance: $choices")
        assertEquals("option-3", choices.first())
    }

    @Test
    fun `an empty menu selects nothing`() {
        val menu = menuOf(0)
        menu.pointer = Vec2(100f, 100f)

        assertNull(menu.selected)
    }

    private fun menuOf(count: Int): RadialMenuNode {
        resetReactiveGraphForTesting()
        val runtime = UiRuntime(DarkTheme).apply { viewport = Size(480f, 270f) }
        val menu = RadialMenuNode(UiId.of("test", "radial"), ComponentContext(DarkTheme, runtime.animations, runtime))
        menu.options = (0 until count).map { RadialOption("option-$it", "Option $it", 0xFFFFFF) }
        return menu
    }

    private companion object {
        /** Well past the dead zone, so these tests are about direction and nothing else. */
        const val PUSH = 100f

        const val TAU = (2.0 * Math.PI).toFloat()
        const val HALF_TURN = Math.PI.toFloat()
    }
}
