package dev.th7bo.sidequest.ui.components.notification

import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.core.layout.ColumnNode
import dev.th7bo.sidequest.ui.core.layout.PaddingNode
import dev.th7bo.sidequest.ui.core.notification.NotificationQueue
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Anchor
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.HorizontalAlignment
import dev.th7bo.sidequest.ui.geometry.Insets
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.input.InputEvent
import dev.th7bo.sidequest.ui.input.PointerDownEvent
import dev.th7bo.sidequest.ui.notification.ActiveNotification
import dev.th7bo.sidequest.ui.notification.NotificationSeverity
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.Corners
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.state.derivedStateOf

/**
 * One toast.
 *
 * Reads its content from the [ActiveNotification] rather than copying it, so a coalesced
 * repeat updates the count in place instead of replacing the node — which is what lets
 * the toast stay put and simply change its number.
 */
public class ToastNode(
    id: UiId,
    public val entry: ActiveNotification,
    private val componentContext: ComponentContext,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    init {
        interactive = entry.notification.onActivate != null

        val title = derivedStateOf("${id.value}.title") {
            val base = entry.notification.title.value
            if (entry.count > 1) "$base ×${entry.count}" else base
        }

        val column = ColumnNode(id.child("column"), spacing = tokens.spacing.xs).apply {
            addChild(TextNode(id.child("title"), title, TextRole.LABEL))
            entry.notification.message?.let {
                addChild(TextNode(id.child("message"), it, TextRole.SECONDARY))
            }
        }

        addChild(
            PaddingNode(
                id.child("padding"),
                Insets(
                    // Extra on the left to clear the severity bar.
                    left = tokens.spacing.medium.value + ACCENT_BAR_WIDTH,
                    top = tokens.spacing.small.value,
                    right = tokens.spacing.medium.value,
                    bottom = tokens.spacing.small.value,
                ),
            ).apply { addChild(column) },
        )
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val child = children.first()
        val bounded = Constraints(
            maxWidth = minOf(constraints.maxWidth, MAX_WIDTH),
            maxHeight = constraints.maxHeight,
        )
        return child.measure(bounded, context)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val child = children.first()
        child.arrange(Rect.of(Vec2.Zero, child.measuredSize), context)
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val corners = Corners.all(tokens.radii.medium)

        renderer.roundedRect(bounds, corners, palette.elevatedPanelBackground)
        renderer.border(bounds, corners, tokens.metrics.borderWidth, palette.border)

        // A severity bar rather than a tinted background: the text stays on the same
        // surface as every other panel, so contrast is one problem solved once.
        val accent = severityColor(entry.notification.severity, context)
        renderer.roundedRect(
            Rect(bounds.x, bounds.y, ACCENT_BAR_WIDTH, bounds.height),
            Corners(topLeft = tokens.radii.medium, bottomLeft = tokens.radii.medium),
            accent,
        )
        context.diagnostics.drawCalls += 3

        // A countdown, so a toast that is about to leave says so.
        entry.timeoutFraction?.let { fraction ->
            val remaining = (1f - fraction).coerceIn(0f, 1f)
            if (remaining > 0f) {
                renderer.roundedRect(
                    Rect(bounds.x, bounds.bottom - COUNTDOWN_HEIGHT, bounds.width * remaining, COUNTDOWN_HEIGHT),
                    Corners.all(tokens.radii.none),
                    accent.withAlpha(COUNTDOWN_ALPHA),
                )
                context.diagnostics.drawCalls++
            }
        }
    }

    private fun severityColor(severity: NotificationSeverity, context: RenderContext): Color {
        val palette = context.theme.tokens.colors
        return when (severity) {
            NotificationSeverity.INFO -> palette.accent
            NotificationSeverity.SUCCESS -> SUCCESS
            NotificationSeverity.WARNING -> WARNING
            NotificationSeverity.ERROR -> ERROR
        }
    }

    override fun onInputEvent(event: InputEvent) {
        if (event is PointerDownEvent && event.phase == dev.th7bo.sidequest.ui.input.EventPhase.TARGET) {
            entry.notification.onActivate?.let {
                it()
                event.consume()
            }
        }
    }

    public companion object {
        public const val MAX_WIDTH: Float = 180f
        private const val ACCENT_BAR_WIDTH = 2f
        private const val COUNTDOWN_HEIGHT = 1f
        private const val COUNTDOWN_ALPHA = 0.6f

        private val SUCCESS = Color.parse("#FF4ADE80")
        private val WARNING = Color.parse("#FFFBBF24")
        private val ERROR = Color.parse("#FFF87171")
    }
}

/**
 * Where notifications appear.
 *
 * Anchored to the screen like a HUD, and for the same reason: a resolution or GUI-scale
 * change must not move it relative to the edge it was placed against. It is *not* a HUD
 * element, though — the queue owns what is in it, so it is not something the HUD editor
 * should let you drag a toast out of.
 */
public class NotificationRegionNode(
    id: UiId,
    private val queue: NotificationQueue,
    private val componentContext: ComponentContext,
    /** Which corner the stack grows from. */
    public var anchor: Anchor = Anchor.TOP_RIGHT,
    /** Distance from the anchored edges. */
    public var margin: Float = DEFAULT_MARGIN,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    private val stack = ColumnNode(id.child("stack"), spacing = tokens.spacing.small)

    /** Nodes by notification id, so a coalesced repeat keeps its node and its position. */
    private val toasts = LinkedHashMap<UiId, ToastNode>()

    init {
        addChild(stack)
        queue.showing.observe(scope) { rebuild(it) }
        rebuild(queue.showing.peek())
    }

    /** Live toast count, for assertions. */
    public val toastCount: Int get() = toasts.size

    private fun rebuild(entries: List<ActiveNotification>) {
        val wanted = entries.associateBy { it.id }

        // Remove first, so a node whose notification is gone cannot be re-measured.
        for (id in toasts.keys.toList()) {
            if (id !in wanted) {
                toasts.remove(id)?.let {
                    stack.removeChild(it)
                    it.dispose()
                }
            }
        }

        val ordered = entries.map { entry ->
            toasts[entry.id] ?: ToastNode(id.child(entry.id.path), entry, componentContext)
                .also { toasts[entry.id] = it }
        }

        // Detach and re-add in the queue's order rather than only appending new nodes.
        // The queue sorts by severity, so appending would show an error *below* the
        // routine chatter it was supposed to outrank — the ordering would exist and be
        // invisible. Node identity is preserved across the reorder, so a coalesced
        // repeat still updates in place rather than flickering.
        if (stack.children.map { it } != ordered) {
            for (node in stack.children.toList()) stack.removeChild(node)
            for (node in ordered) stack.addChild(node)
        }
        stack.invalidateMeasure()
        invalidateMeasure()
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        // The region occupies the whole viewport and positions the stack inside it, so
        // the anchor arithmetic is the same one HUD placement uses.
        val width = constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: ToastNode.MAX_WIDTH
        val height = constraints.maxHeight.takeIf { constraints.hasBoundedHeight } ?: 0f

        stack.measure(Constraints(maxWidth = minOf(width, ToastNode.MAX_WIDTH), maxHeight = height), context)
        return Size(width, height)
    }

    override fun arrangeChildren(context: LayoutContext) {
        // Toasts align to the edge the region is anchored against, so their edges line up
        // instead of going ragged where they meet the screen.
        stack.horizontalAlignment = when (anchor.horizontalFactor) {
            1f -> HorizontalAlignment.END
            0f -> HorizontalAlignment.START
            else -> HorizontalAlignment.CENTER
        }

        val stackSize = stack.measuredSize
        val position = anchor.resolve(marginOffset(), stackSize, measuredSize)
        stack.arrange(Rect.of(position, stackSize), context)
    }

    /** The margin, signed so it pushes away from whichever edges the anchor names. */
    private fun marginOffset(): Vec2 = Vec2(
        when (anchor.horizontalFactor) {
            0f -> margin
            1f -> -margin
            else -> 0f
        },
        when (anchor.verticalFactor) {
            0f -> margin
            1f -> -margin
            else -> 0f
        },
    )

    public companion object {
        public const val DEFAULT_MARGIN: Float = 8f
    }
}
