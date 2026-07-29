package dev.th7bo.sidequest.ui.core.demo

import dev.th7bo.sidequest.ui.core.UiTestHarness
import dev.th7bo.sidequest.ui.core.UiTestHarness.Companion.id
import dev.th7bo.sidequest.ui.core.animation.AnimationHost
import dev.th7bo.sidequest.ui.core.animation.Easing
import dev.th7bo.sidequest.ui.core.animation.HostedAnimation
import dev.th7bo.sidequest.ui.core.content.SurfaceNode
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.core.layout.BoxNode
import dev.th7bo.sidequest.ui.core.layout.ColumnNode
import dev.th7bo.sidequest.ui.core.layout.FixedSizeNode
import dev.th7bo.sidequest.ui.core.layout.PaddingNode
import dev.th7bo.sidequest.ui.core.layout.RowNode
import dev.th7bo.sidequest.ui.core.layout.SpacerNode
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Alignment
import dev.th7bo.sidequest.ui.geometry.Insets
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.VerticalAlignment
import dev.th7bo.sidequest.ui.geometry.dp
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.map
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.theme.DarkTheme
import dev.th7bo.sidequest.ui.theme.Theme
import kotlin.math.roundToInt

/**
 * A compact progress card, assembled from the Phase 1 primitives only.
 *
 * This is a scaled-down stand-in for the Phase 4 mining-XP HUD: dark translucent
 * surface, thin border, small icon block on the left, title and value on one line, and
 * a thin accent progress bar underneath. Every colour, radius, gap and duration comes
 * from theme tokens — nothing here hard-codes a visual value.
 */
class ProgressCard(
    private val theme: Theme,
    private val animations: AnimationHost,
    title: String,
    private val current: UiState<Int>,
    private val maximum: UiState<Int>,
) {

    private val tokens = theme.tokens

    /** Width of the filled portion, animated so a jump in XP glides rather than snaps. */
    private val fillWidth = HostedAnimation(
        animations,
        dev.th7bo.sidequest.ui.core.animation.AnimatedFloat(
            initial = 0f,
            duration = tokens.motion.slow,
            easing = Easing.EaseOut,
            debugName = "progress_fill",
        ),
    )

    private val valueLabel: UiState<String> =
        dev.th7bo.sidequest.ui.state.combine(current, maximum) { value, total -> "$value / $total" }

    private val progressFraction: UiState<Float> =
        dev.th7bo.sidequest.ui.state.combine(current, maximum) { value, total ->
            if (total <= 0) 0f else (value.toFloat() / total).coerceIn(0f, 1f)
        }

    private val track = SurfaceNode(id("card.track")).apply {
        preferredSize = Size(TRACK_WIDTH, TRACK_HEIGHT)
        colorToken = { it.colors.border }
        cornerRadius = tokens.radii.pill
    }

    private val fill = SurfaceNode(id("card.fill")).apply {
        preferredSize = Size(0f, TRACK_HEIGHT)
        colorToken = { it.colors.accent }
        cornerRadius = tokens.radii.pill
    }

    /** The card's root node, ready to be handed to a runtime. */
    val node: UiNode

    init {
        val icon = FixedSizeNode(
            id("card.icon_slot"),
            width = tokens.metrics.iconSize,
            height = tokens.metrics.iconSize,
        ).apply {
            addChild(
                SurfaceNode(id("card.icon")).apply {
                    colorToken = { it.colors.accent.withAlpha(ICON_TINT_ALPHA) }
                    cornerRadius = tokens.radii.small
                },
            )
        }

        // The header is pinned to the card's content width so the weighted spacer has a
        // finite amount of space to hand out. Without that, a weighted child would
        // stretch the card to whatever the parent offered — a HUD card must stay
        // compact regardless of the screen it sits on.
        val header = FixedSizeNode(id("card.header_width"), width = ProgressCard.TRACK_WIDTH.dp).apply {
            addChild(
                RowNode(id("card.header"), spacing = tokens.spacing.small).apply {
                    addChildren(
                        TextNode.of(id("card.title"), title, TextRole.LABEL),
                        SpacerNode(id("card.header_spacer")).apply { layoutWeight = 1f },
                        TextNode(id("card.value"), valueLabel, TextRole.SECONDARY),
                    )
                },
            )
        }

        val progressBar = BoxNode(id("card.progress"), Alignment.CenterStart).apply {
            addChildren(track, fill)
        }

        val body = ColumnNode(id("card.body"), spacing = tokens.spacing.small).apply {
            addChildren(header, progressBar)
        }

        val content = RowNode(id("card.content"), spacing = tokens.spacing.medium).apply {
            verticalAlignment = VerticalAlignment.CENTER
            addChildren(icon, body)
        }

        node = SurfaceNode(id("card")).apply {
            colorToken = { it.colors.elevatedPanelBackground }
            cornerRadius = tokens.radii.medium
            borderColorToken = { it.colors.border }
            borderWidth = tokens.metrics.borderWidth
            addChild(
                PaddingNode(id("card.padding"), Insets.symmetric(tokens.spacing.large, tokens.spacing.medium))
                    .apply { addChild(content) },
            )
        }

        // The bar reacts to the data, not the other way round: the fill node's width is
        // driven by an animation whose target follows the progress state.
        progressFraction.observe(node.scope) { fraction ->
            fillWidth.target = TRACK_WIDTH * fraction
        }
        fillWidth.state.observe(node.scope) { width ->
            fill.preferredSize = Size(width, TRACK_HEIGHT)
        }
        fillWidth.snapTo(TRACK_WIDTH * progressFraction.peek())
        fill.preferredSize = Size(fillWidth.value, TRACK_HEIGHT)
    }

    /** Rendered fill width, for assertions. */
    val fillWidthValue: Float get() = fill.preferredSize?.width ?: 0f

    companion object {
        const val TRACK_WIDTH: Float = 120f
        const val TRACK_HEIGHT: Float = 3f
        const val ICON_TINT_ALPHA: Float = 0.35f
    }
}

/**
 * Builds the demo scene: a screen-like backdrop with the progress card centred.
 */
class DemoScene(viewport: Size = Size(320f, 120f), theme: Theme = DarkTheme) {

    val harness: UiTestHarness = UiTestHarness(viewport, theme)

    val currentXp: MutableUiState<Int> = mutableStateOf(0, "current_xp")
    val requiredXp: MutableUiState<Int> = mutableStateOf(1000, "required_xp")

    val card: ProgressCard = ProgressCard(
        theme = theme,
        animations = harness.runtime.animations,
        title = "Mining XP",
        current = currentXp,
        maximum = requiredXp,
    )

    /** Percentage label derived from the same state the bar uses. */
    val percentLabel: UiState<String> = currentXp.map { "${(it / 10f).roundToInt()}%" }

    init {
        val backdrop = BoxNode(id("scene"), Alignment.Center).apply {
            preferredSize = viewport
            addChild(card.node)
        }
        harness.root = backdrop
    }

    fun frame(deltaSeconds: Float = UiTestHarness.FRAME_DELTA) = harness.frame(deltaSeconds)

    fun dispose() {
        harness.dispose()
        resetReactiveGraphForTesting()
    }
}
