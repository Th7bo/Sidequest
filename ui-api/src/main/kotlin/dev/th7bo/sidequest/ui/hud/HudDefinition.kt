package dev.th7bo.sidequest.ui.hud

import dev.th7bo.sidequest.ui.geometry.Anchor
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.constantState
import kotlinx.serialization.Serializable

/**
 * Where a HUD element sits, and how big it is.
 *
 * Persisted as an anchor plus an offset rather than absolute pixels, so an element keeps
 * its relationship to the screen edge it was placed against when the resolution or the
 * GUI scale changes.
 */
@Serializable
public data class HudPlacement(
    public val anchor: Anchor = Anchor.TOP_LEFT,
    public val offset: Vec2 = Vec2.Zero,
    public val scale: Float = 1f,
    /** Set only for HUDs that support resizing; null means "size to content". */
    public val size: Size? = null,
    public val zIndex: Int = 0,
    public val opacity: Float = 1f,
    /** A locked HUD is drawn but cannot be moved or scaled in the editor. */
    public val locked: Boolean = false,
) {

    init {
        require(scale > 0f) { "HUD scale must be positive, was $scale" }
        require(opacity in 0f..1f) { "HUD opacity must be in 0..1, was $opacity" }
    }

    /**
     * The absolute top-left position of an element of [elementSize] on [screenSize].
     *
     * [elementSize] is the *scaled* size: scaling changes what the element occupies on
     * screen, so it has to be part of the anchor arithmetic or a scaled HUD would drift
     * away from its edge.
     */
    public fun resolve(elementSize: Size, screenSize: Size): Vec2 =
        anchor.resolve(offset, elementSize, screenSize)

    /**
     * Re-anchors without moving.
     *
     * The offset is recomputed so the element stays exactly where it is on screen — the
     * behaviour the editor needs when someone changes the anchor from a dropdown.
     */
    public fun withAnchor(newAnchor: Anchor, elementSize: Size, screenSize: Size): HudPlacement {
        if (newAnchor == anchor) return this
        val current = resolve(elementSize, screenSize)
        return copy(anchor = newAnchor, offset = newAnchor.offsetFor(current, elementSize, screenSize))
    }

    /**
     * Moves to an absolute position, keeping the current anchor.
     *
     * Used while dragging: the cursor gives a position, and the offset is whatever makes
     * the element land there.
     */
    public fun movedTo(position: Vec2, elementSize: Size, screenSize: Size): HudPlacement =
        copy(offset = anchor.offsetFor(position, elementSize, screenSize))

    /**
     * Rescales about the element's anchor-relative corner, so the point the element is
     * pinned by does not move.
     */
    public fun withScale(newScale: Float, range: ClosedFloatingPointRange<Float>): HudPlacement =
        copy(scale = newScale.coerceIn(range.start, range.endInclusive))
}

/** What a HUD lets the editor do to it. */
public enum class HudResizeMode {
    /** Neither scaled nor resized. */
    NONE,

    /** Scaled by a transform; the layout is unaffected. */
    SCALE_ONLY,

    /** Resized, changing the space the content lays out in. */
    RESIZE_ONLY,

    /** Both, as separate operations. */
    SCALE_AND_RESIZE,
    ;

    public val supportsScale: Boolean get() = this == SCALE_ONLY || this == SCALE_AND_RESIZE
    public val supportsResize: Boolean get() = this == RESIZE_ONLY || this == SCALE_AND_RESIZE
}

/** How often a HUD's content should be re-evaluated. */
public sealed interface UpdatePolicy {

    /** Recompute only when a dependency changes. The default, and the cheapest. */
    public data object OnChange : UpdatePolicy

    /** Once per client tick. */
    public data object EveryTick : UpdatePolicy

    /**
     * Every frame.
     *
     * Deliberately opt-in: a HUD that updates every frame can never be idle, and an idle
     * frame doing no layout work is the property the whole runtime is built around.
     */
    public data object EveryFrame : UpdatePolicy

    /** At a fixed wall-clock interval. */
    public data class Interval(public val millis: Long) : UpdatePolicy {
        init {
            require(millis > 0) { "Interval must be positive, was $millis" }
        }
    }

    /** Only when the owner asks. */
    public data object Manual : UpdatePolicy
}

/**
 * The durable description of a HUD element.
 *
 * Like [dev.th7bo.sidequest.ui.config.Setting], this holds no rendering state: position,
 * drag state and animation live in the runtime, keyed by instance id.
 *
 * A definition can back several instances — timers, waypoints and stat displays are all
 * "one definition, many instances" — so nothing here assumes there is only one.
 */
public class HudDefinition(
    public val id: UiId,
    public val title: UiState<String>,
    /** Groups HUDs in the editor's list. */
    public val category: UiState<String> = constantState("General"),
    public val icon: Icon? = null,

    public val defaultAnchor: Anchor = Anchor.TOP_LEFT,
    public val defaultOffset: Vec2 = Vec2.Zero,
    public val defaultScale: Float = 1f,
    public val scaleRange: ClosedFloatingPointRange<Float> = DEFAULT_SCALE_RANGE,
    public val resizeMode: HudResizeMode = HudResizeMode.SCALE_ONLY,
    /** Bounds for resizable HUDs. Ignored otherwise. */
    public val minSize: Size = Size(MIN_DIMENSION, MIN_DIMENSION),
    public val maxSize: Size? = null,

    /** Whether the element draws at all. Composable, and testable without rendering. */
    public val visibleWhen: UiState<Boolean> = constantState(true),
    public val defaultZIndex: Int = 0,
    public val updatePolicy: UpdatePolicy = UpdatePolicy.OnChange,

    /** Extra terms the editor's search should find this by. */
    public val keywords: List<String> = emptyList(),

    /** Whether more than one instance may exist. */
    public val allowsMultipleInstances: Boolean = false,
) {

    init {
        require(defaultScale in scaleRange) {
            "Default scale $defaultScale is outside $scaleRange for $id"
        }
    }

    /** The placement a fresh instance starts with. */
    public fun defaultPlacement(): HudPlacement = HudPlacement(
        anchor = defaultAnchor,
        offset = defaultOffset,
        scale = defaultScale,
        zIndex = defaultZIndex,
    )

    public fun searchTerms(): List<String> = buildList {
        add(title.peek())
        add(category.peek())
        addAll(keywords)
        add(id.path)
    }

    override fun toString(): String = "HudDefinition($id)"

    public companion object {
        public val DEFAULT_SCALE_RANGE: ClosedFloatingPointRange<Float> = 0.5f..2.5f
        public const val MIN_DIMENSION: Float = 8f
    }
}

/**
 * One placed copy of a [HudDefinition].
 *
 * The instance id is what placement is persisted against, so two copies of the same
 * definition keep separate positions, scales and settings.
 */
public class HudInstance(
    public val instanceId: UiId,
    public val definitionId: UiId,
    /** Shown in the editor when several instances of one definition exist. */
    public val label: UiState<String>? = null,
) {
    override fun toString(): String = "HudInstance($instanceId of $definitionId)"
}

/**
 * Live data, or a sample while the editor is open.
 *
 * A HUD arranged in a menu often has nothing real to show — no skill XP outside a world,
 * no timer that has started — and an element that renders as `0 / 0` is impossible to
 * position sensibly. This swaps in representative values exactly while editing, and is a
 * derived state so it costs nothing when [isEditing] never changes.
 */
public fun <T> previewed(
    live: UiState<T>,
    sample: T,
    isEditing: UiState<Boolean>,
    debugName: String = "preview",
): UiState<T> = dev.th7bo.sidequest.ui.state.derivedStateOf(debugName) {
    if (isEditing.value) sample else live.value
}

/** Everything persisted about where HUDs sit, for one profile. */
@Serializable
public class HudLayoutSnapshot(
    public val schemaVersion: Int,
    /** Placement by instance id, keyed by the stringified [UiId]. */
    public val placements: Map<String, HudPlacement> = emptyMap(),
    /** Instances that exist, so multi-instance HUDs survive a restart. */
    public val instances: Map<String, String> = emptyMap(),
) {
    public val size: Int get() = placements.size
}
