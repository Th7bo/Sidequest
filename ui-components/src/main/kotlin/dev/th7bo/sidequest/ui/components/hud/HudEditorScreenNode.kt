package dev.th7bo.sidequest.ui.components.hud

import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.content.SurfaceNode
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.core.hud.editor.HudEditorSession
import dev.th7bo.sidequest.ui.core.layout.PaddingNode
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Insets
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.state.constantState

/**
 * The HUD editor screen: the editing chrome over the live HUDs, and the inspector
 * docked to one side.
 *
 * Deliberately does **not** re-parent the HUD layer. Minecraft already draws the layer
 * beneath the screen, so the chrome lands on top of the real HUDs for free — and the
 * layer keeps its single owner rather than being handed back and forth as the editor
 * opens and closes.
 *
 * There is no scrim, either. A HUD editor that dims the game is showing you something
 * other than what you are arranging; the point is to place elements against the scene
 * they will actually sit on.
 */
public class HudEditorScreenNode(
    id: UiId,
    public val session: HudEditorSession,
    private val componentContext: ComponentContext,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    private val overlay = HudEditorOverlayNode(id.child("overlay"), session, componentContext)

    private val inspector = HudInspectorNode(id.child("inspector"), session, componentContext)

    private val hint = SurfaceNode(id.child("hint_surface")).apply {
        colorToken = { it.colors.panelBackground }
        addChild(
            PaddingNode(id.child("hint_padding"), Insets.symmetric(tokens.spacing.medium, tokens.spacing.small))
                .apply {
                    addChild(
                        TextNode(
                            id.child("hint"),
                            constantState(HINT),
                            TextRole.SECONDARY,
                        ),
                    )
                },
        )
    }

    /** True when the viewport is too narrow to dock the inspector. */
    public var isInspectorVisible: Boolean = true
        private set

    init {
        // Order is the z-order: chrome first, then the panel above it.
        addChild(overlay)
        addChild(inspector)
        addChild(hint)
    }

    /** Keeps the inspector's readouts in step with a drag in progress. */
    public fun tick() {
        inspector.refresh()
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val width = constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: FALLBACK
        val height = constraints.maxHeight.takeIf { constraints.hasBoundedHeight } ?: FALLBACK
        val screen = Constraints(minWidth = width, maxWidth = width, minHeight = height, maxHeight = height)

        overlay.measure(screen, context)

        // The inspector is dropped rather than squeezed when there is not enough room:
        // a panel narrower than its controls is worse than no panel, and the editor is
        // still fully usable by dragging.
        isInspectorVisible = width >= MINIMUM_WIDTH_FOR_INSPECTOR
        inspector.isVisible = isInspectorVisible
        if (isInspectorVisible) {
            inspector.measure(
                Constraints(maxWidth = INSPECTOR_WIDTH, maxHeight = height - tokens.spacing.large.value * 2f),
                context,
            )
        }

        hint.measure(Constraints(maxWidth = width, maxHeight = height), context)
        return Size(width, height)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val size = measuredSize
        overlay.arrange(Rect.of(Vec2.Zero, size), context)

        if (isInspectorVisible) {
            val inspectorSize = inspector.measuredSize
            // Clamped rather than trusted: a panel measuring wider than its allowance
            // would otherwise be pushed off the right edge, taking its buttons with it.
            val left = (size.width - inspectorSize.width - tokens.spacing.large.value)
                .coerceAtLeast(0f)
            inspector.arrange(Rect.of(Vec2(left, tokens.spacing.large.value), inspectorSize), context)
        }

        val hintSize = hint.measuredSize
        hint.arrange(
            Rect.of(
                Vec2((size.width - hintSize.width) / 2f, size.height - hintSize.height - tokens.spacing.large.value),
                hintSize,
            ),
            context,
        )
    }

    public companion object {
        /** Below this the inspector is hidden and the editor is drag-only. */
        public const val MINIMUM_WIDTH_FOR_INSPECTOR: Float = 480f
        public const val INSPECTOR_WIDTH: Float = 180f

        private const val FALLBACK = 480f
        private const val HINT =
            "Drag to move  ·  Shift-click to multi-select  ·  Alt disables snapping  ·  Arrows nudge  ·  L locks"
    }
}
