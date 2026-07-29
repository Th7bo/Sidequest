package dev.th7bo.sidequest.ui.core.animation

import dev.th7bo.sidequest.ui.state.DisposableScope
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AnimationTest {

    private lateinit var scope: DisposableScope

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        scope = DisposableScope()
    }

    @AfterEach
    fun tearDown() {
        scope.dispose()
        resetReactiveGraphForTesting()
    }

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.001f) {
        assertTrue(abs(expected - actual) <= tolerance) { "expected $expected but was $actual" }
    }

    @Test
    fun `a linear animation reaches its target in exactly its duration`() {
        val animation = AnimatedFloat(0f, duration = 1f, easing = Easing.Linear)
        animation.target = 100f

        repeat(10) { animation.tick(0.1f) }

        assertClose(100f, animation.value)
        assertFalse(animation.isAnimating)
    }

    @Test
    fun `progress is delta-time based, not frame based`() {
        val fewBigSteps = AnimatedFloat(0f, duration = 1f, easing = Easing.Linear)
        val manySmallSteps = AnimatedFloat(0f, duration = 1f, easing = Easing.Linear)
        fewBigSteps.target = 100f
        manySmallSteps.target = 100f

        repeat(2) { fewBigSteps.tick(0.25f) }
        repeat(50) { manySmallSteps.tick(0.01f) }

        assertClose(fewBigSteps.value, manySmallSteps.value)
    }

    @Test
    fun `retargeting mid-flight continues from the current value`() {
        val animation = AnimatedFloat(0f, duration = 1f, easing = Easing.Linear)
        animation.target = 100f
        animation.tick(0.5f)
        val midpoint = animation.value
        assertClose(50f, midpoint)

        // Aim somewhere new. The value must not jump back to 0.
        animation.target = 0f
        animation.tick(0.0f)
        assertClose(midpoint, animation.value, tolerance = 0.01f)

        animation.tick(0.5f)
        assertClose(25f, animation.value, tolerance = 0.01f)
    }

    @Test
    fun `retargeting to the value it is already heading to does not restart`() {
        val animation = AnimatedFloat(0f, duration = 1f, easing = Easing.Linear)
        animation.target = 100f
        animation.tick(0.5f)

        animation.target = 100f
        animation.tick(0.5f)

        assertClose(100f, animation.value, tolerance = 0.01f)
    }

    @Test
    fun `reduced motion snaps straight to the target`() {
        val animation = AnimatedFloat(0f, duration = 1f, easing = Easing.EaseOut)
        animation.target = 100f

        val stillRunning = animation.tick(1f / 60f, reducedMotion = true)

        assertClose(100f, animation.value)
        assertFalse(stillRunning)
    }

    @Test
    fun `a zero duration animation is effectively instant`() {
        val animation = AnimatedFloat(0f, duration = 0f)
        animation.target = 42f
        animation.tick(1f / 60f)
        assertClose(42f, animation.value)
    }

    @Test
    fun `snapTo cancels motion in progress`() {
        val animation = AnimatedFloat(0f, duration = 1f, easing = Easing.Linear)
        animation.target = 100f
        animation.tick(0.3f)

        animation.snapTo(7f)

        assertClose(7f, animation.value)
        assertFalse(animation.isAnimating)
        animation.tick(0.5f)
        assertClose(7f, animation.value)
    }

    @Test
    fun `the animated value is observable and only notifies while moving`() {
        val animation = AnimatedFloat(0f, duration = 1f, easing = Easing.Linear)
        var notifications = 0
        animation.state.observe(scope) { notifications++ }

        animation.tick(0.1f)
        assertEquals(0, notifications, "an idle animation must not write state")

        animation.target = 10f
        animation.tick(0.1f)
        assertEquals(1, notifications)

        // Run to completion, then keep ticking: the writes must stop.
        repeat(20) { animation.tick(0.1f) }
        val afterCompletion = notifications
        repeat(10) { animation.tick(0.1f) }
        assertEquals(afterCompletion, notifications, "a settled animation must stop writing")
    }

    @Test
    fun `easing curves stay within bounds and hit both ends`() {
        for (easing in listOf(Easing.Linear, Easing.EaseIn, Easing.EaseOut, Easing.EaseInOut)) {
            assertClose(0f, easing.ease(0f))
            assertClose(1f, easing.ease(1f))
            for (step in 0..10) {
                val eased = easing.ease(step / 10f)
                assertTrue(eased in -0.001f..1.001f) { "easing produced $eased" }
            }
        }
    }

    @Test
    fun `easeOut decelerates and easeIn accelerates`() {
        assertTrue(Easing.EaseOut.ease(0.5f) > 0.5f, "ease-out should be ahead at the midpoint")
        assertTrue(Easing.EaseIn.ease(0.5f) < 0.5f, "ease-in should be behind at the midpoint")
    }

    // -- host ---------------------------------------------------------------

    @Test
    fun `the host drops animations once they settle`() {
        val host = AnimationHost()
        val animation = AnimatedFloat(0f, duration = 0.5f, easing = Easing.Linear)
        animation.target = 1f
        host.register(animation)

        assertEquals(1, host.activeCount)
        assertTrue(host.hasActiveAnimations)

        repeat(60) { host.tick(1f / 60f) }

        assertEquals(0, host.activeCount, "a finished animation must leave the active set")
        assertFalse(host.hasActiveAnimations)
    }

    @Test
    fun `an idle host ticks nothing`() {
        val host = AnimationHost()
        assertFalse(host.hasActiveAnimations)
        host.tick(1f / 60f)
        assertEquals(0, host.activeCount)
    }

    @Test
    fun `several animations advance independently`() {
        val host = AnimationHost()
        val fast = AnimatedFloat(0f, duration = 0.1f, easing = Easing.Linear).also { it.target = 1f }
        val slow = AnimatedFloat(0f, duration = 1f, easing = Easing.Linear).also { it.target = 1f }
        host.register(fast)
        host.register(slow)

        repeat(12) { host.tick(1f / 60f) }

        assertClose(1f, fast.value)
        assertTrue(slow.value < 0.3f)
        assertEquals(1, host.activeCount, "only the unfinished animation should remain")
    }

    @Test
    fun `a hosted animation re-registers itself when retargeted`() {
        val host = AnimationHost()
        val hosted = HostedAnimation(host, AnimatedFloat(0f, duration = 0.2f, easing = Easing.Linear))

        assertFalse(host.hasActiveAnimations)
        hosted.target = 5f
        assertTrue(host.hasActiveAnimations, "retargeting must put it back in the active set")

        repeat(20) { host.tick(1f / 60f) }
        assertClose(5f, hosted.value)
        assertFalse(host.hasActiveAnimations)
    }

    @Test
    fun `disposing the owning scope removes the animation from the host`() {
        val host = AnimationHost()
        val localScope = DisposableScope()
        val animation = host.animate(localScope, initial = 0f, duration = 1f)
        animation.target = 1f
        host.register(animation)

        assertEquals(1, host.activeCount)
        localScope.dispose()
        assertEquals(0, host.activeCount)
    }
}
