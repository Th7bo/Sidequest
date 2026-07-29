package dev.th7bo.sidequest.ui.core.animation

import dev.th7bo.sidequest.ui.state.Disposable
import dev.th7bo.sidequest.ui.state.DisposableScope
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.mutableStateOf
import kotlin.math.abs

/** Maps linear progress in `0..1` onto eased progress in `0..1`. */
public fun interface Easing {

    public fun ease(t: Float): Float

    public companion object {
        public val Linear: Easing = Easing { it }

        /** Decelerating. The default for anything that appears or moves into place. */
        public val EaseOut: Easing = Easing { 1f - (1f - it) * (1f - it) }

        /** Accelerating. For things leaving. */
        public val EaseIn: Easing = Easing { it * it }

        public val EaseInOut: Easing = Easing {
            if (it < 0.5f) 2f * it * it else 1f - 2f * (1f - it) * (1f - it)
        }
    }
}

/**
 * A float that moves smoothly towards a target.
 *
 * Retargeting mid-flight continues from the **current** value rather than restarting
 * from the original one — a toggle flicked twice in quick succession must not snap
 * backwards before moving forwards again.
 *
 * The animated value is exposed as a [UiState], so a node that reads it is invalidated
 * automatically for exactly as long as the animation is running and not a frame longer.
 */
public class AnimatedFloat(
    initial: Float,
    /** Seconds to travel the full distance. */
    public var duration: Float,
    public var easing: Easing = Easing.EaseOut,
    debugName: String = "animation",
) : Disposable {

    private val backing: MutableUiState<Float> = mutableStateOf(initial, debugName)

    /** Observable current value. */
    public val state: UiState<Float> get() = backing

    public val value: Float get() = backing.value

    private var origin: Float = initial
    private var elapsed: Float = 0f

    /** Where the value is heading. Assigning re-aims without restarting. */
    public var target: Float = initial
        set(value) {
            if (field == value) return
            origin = backing.peek()
            field = value
            elapsed = 0f
        }

    /** True while the value has not yet reached [target]. */
    public val isAnimating: Boolean
        get() = abs(backing.peek() - target) > EPSILON

    /**
     * Jumps straight to [newValue], cancelling any motion. Used on first layout and
     * whenever reduced motion is enabled.
     */
    public fun snapTo(newValue: Float) {
        origin = newValue
        target = newValue
        elapsed = 0f
        backing.value = newValue
    }

    /**
     * Advances by [deltaSeconds].
     *
     * @param reducedMotion when true the value snaps to the target instead of easing.
     * @return true if the animation is still running.
     */
    public fun tick(deltaSeconds: Float, reducedMotion: Boolean = false): Boolean {
        if (!isAnimating) return false

        if (reducedMotion || duration <= 0f) {
            backing.value = target
            return false
        }

        elapsed += deltaSeconds
        val progress = (elapsed / duration).coerceIn(0f, 1f)
        backing.value = if (progress >= 1f) {
            target
        } else {
            origin + (target - origin) * easing.ease(progress)
        }
        return progress < 1f
    }

    override fun dispose() {
        snapTo(target)
    }

    private companion object {
        const val EPSILON = 0.0001f
    }
}

/**
 * Drives every live animation from one place.
 *
 * Finished animations are dropped from the active set, so an idle screen ticks nothing
 * and produces no state writes — which is what keeps an idle frame free of layout work.
 */
public class AnimationHost {

    private val active = ArrayList<AnimatedFloat>()

    /** Number of animations currently running. */
    public val activeCount: Int get() = active.size

    /** True while anything is moving. The runtime uses this to decide if a frame is idle. */
    public val hasActiveAnimations: Boolean get() = active.isNotEmpty()

    /**
     * Creates an animation owned by [scope] and driven by this host.
     */
    public fun animate(
        scope: DisposableScope,
        initial: Float,
        duration: Float,
        easing: Easing = Easing.EaseOut,
        debugName: String = "animation",
    ): AnimatedFloat {
        val animation = AnimatedFloat(initial, duration, easing, debugName)
        scope.register(animation)
        scope.register { active.remove(animation) }
        return animation
    }

    /** Registers an externally created animation for ticking. */
    public fun register(animation: AnimatedFloat) {
        if (!active.contains(animation)) active.add(animation)
    }

    public fun unregister(animation: AnimatedFloat) {
        active.remove(animation)
    }

    /**
     * Advances every animation. Anything that has settled is removed from the active
     * set in the same pass.
     */
    public fun tick(deltaSeconds: Float, reducedMotion: Boolean = false) {
        if (active.isEmpty()) return
        var writeIndex = 0
        for (readIndex in active.indices) {
            val animation = active[readIndex]
            if (animation.tick(deltaSeconds, reducedMotion)) {
                active[writeIndex++] = animation
            }
        }
        while (active.size > writeIndex) active.removeAt(active.size - 1)
    }

    /** Drops every animation without settling it. For teardown. */
    public fun clear() {
        active.clear()
    }
}

/**
 * Convenience: an animation that is registered with [host] whenever it is retargeted.
 *
 * Without this a caller has to remember to re-register after every `target =`, which is
 * exactly the kind of bookkeeping the framework exists to remove.
 */
public class HostedAnimation(
    private val host: AnimationHost,
    private val animation: AnimatedFloat,
) {

    public val state: UiState<Float> get() = animation.state

    public val value: Float get() = animation.value

    public var target: Float
        get() = animation.target
        set(value) {
            if (animation.target == value) return
            animation.target = value
            host.register(animation)
        }

    public fun snapTo(value: Float) {
        animation.snapTo(value)
        host.unregister(animation)
    }
}
