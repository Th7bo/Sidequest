package dev.th7bo.sidequest.ui.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import kotlin.math.abs

/**
 * Anchoring is the reason HUD placement survives a resolution change, so it gets an
 * exhaustive test over every anchor rather than a couple of spot checks.
 */
class AnchorTest {

    private val screen = Size(1920f, 1080f)
    private val element = Size(200f, 100f)

    @ParameterizedTest
    @EnumSource(Anchor::class)
    fun `resolve and offsetFor are exact inverses`(anchor: Anchor) {
        val offset = Vec2(37f, -19f)

        val position = anchor.resolve(offset, element, screen)
        val recovered = anchor.offsetFor(position, element, screen)

        assertClose(offset.x, recovered.x)
        assertClose(offset.y, recovered.y)
    }

    @ParameterizedTest
    @EnumSource(Anchor::class)
    fun `re-anchoring preserves the visible position`(target: Anchor) {
        // A HUD placed against the top-left, then re-anchored: it must not jump.
        val original = Anchor.TOP_LEFT
        val originalOffset = Vec2(400f, 250f)
        val visiblePosition = original.resolve(originalOffset, element, screen)

        val newOffset = target.offsetFor(visiblePosition, element, screen)
        val afterReanchor = target.resolve(newOffset, element, screen)

        assertClose(visiblePosition.x, afterReanchor.x)
        assertClose(visiblePosition.y, afterReanchor.y)
    }

    @ParameterizedTest
    @EnumSource(Anchor::class)
    fun `distance to the anchored edge is preserved across a resolution change`(anchor: Anchor) {
        val offset = Vec2(-24f, -24f)
        val small = Size(1280f, 720f)
        val large = Size(2560f, 1440f)

        val onSmall = anchor.resolve(offset, element, small)
        val onLarge = anchor.resolve(offset, element, large)

        // The gap between the element's anchored corner and the screen's anchored
        // corner is what must stay constant, not the absolute pixel position.
        val gapSmall = anchorGap(anchor, onSmall, small)
        val gapLarge = anchorGap(anchor, onLarge, large)

        assertClose(gapSmall.x, gapLarge.x)
        assertClose(gapSmall.y, gapLarge.y)
    }

    private fun anchorGap(anchor: Anchor, position: Vec2, screenSize: Size): Vec2 {
        val elementAnchorPoint = Vec2(
            position.x + element.width * anchor.horizontalFactor,
            position.y + element.height * anchor.verticalFactor,
        )
        return Vec2(
            elementAnchorPoint.x - screenSize.width * anchor.horizontalFactor,
            elementAnchorPoint.y - screenSize.height * anchor.verticalFactor,
        )
    }

    @ParameterizedTest
    @CsvSource(
        "TOP_LEFT, 0, 0",
        "TOP_CENTER, 860, 0",
        "TOP_RIGHT, 1720, 0",
        "CENTER, 860, 490",
        "BOTTOM_RIGHT, 1720, 980",
        "BOTTOM_LEFT, 0, 980",
        "CENTER_LEFT, 0, 490",
        "CENTER_RIGHT, 1720, 490",
        "BOTTOM_CENTER, 860, 980",
    )
    fun `resolves to the expected absolute position at zero offset`(
        anchor: Anchor,
        expectedX: Float,
        expectedY: Float,
    ) {
        val position = anchor.resolve(Vec2.Zero, element, screen)
        assertClose(expectedX, position.x)
        assertClose(expectedY, position.y)
    }

    @Test
    fun `serialized ids round-trip and never depend on ordinals`() {
        for (anchor in Anchor.entries) {
            assertEquals(anchor, Anchor.fromSerializedId(anchor.serializedId))
        }
        // The persisted form must be the stable string, not the position in the enum.
        assertEquals("bottom_center", Anchor.BOTTOM_CENTER.serializedId)
        assertEquals(null, Anchor.fromSerializedId("not_an_anchor"))
    }

    @Test
    fun `nearestTo picks the anchor whose corner is closest`() {
        assertEquals(Anchor.TOP_LEFT, Anchor.nearestTo(Vec2(0f, 0f), element, screen))
        assertEquals(
            Anchor.BOTTOM_RIGHT,
            Anchor.nearestTo(Vec2(1720f, 980f), element, screen),
        )
        assertEquals(
            Anchor.CENTER,
            Anchor.nearestTo(Vec2(860f, 490f), element, screen),
        )
    }

    private fun assertClose(expected: Float, actual: Float) {
        val tolerance = 0.001f
        check(abs(expected - actual) <= tolerance) {
            "expected $expected but was $actual (tolerance $tolerance)"
        }
    }
}
