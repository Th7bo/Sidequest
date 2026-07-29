package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.core.layout.ColumnNode
import dev.th7bo.sidequest.ui.core.layout.PaddingNode
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Insets
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Corners
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.rendering.TextOverflow
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.constantState

/**
 * A standalone card for the info column: an optional icon, a title, body text, and
 * whatever extra nodes the caller supplies.
 *
 * Unlike a section card this is not split across rows, because nothing here is
 * virtualized — the column is short by construction.
 */
public class InfoCardNode(
    id: UiId,
    private val componentContext: ComponentContext,
    title: UiState<String>,
    body: UiState<String>? = null,
    private val icon: Icon? = null,
    extras: List<UiNode> = emptyList(),
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    private val column = ColumnNode(id.child("column"), spacing = tokens.spacing.medium)
    private val titleNode = TextNode(id.child("title"), title, TextRole.LABEL)

    init {
        column.addChild(titleNode)
        body?.let {
            column.addChild(
                TextNode(id.child("body"), it, TextRole.SECONDARY).apply {
                    maxLines = BODY_LINES
                    overflow = TextOverflow.WRAP
                },
            )
        }
        extras.forEach(column::addChild)

        addChild(
            PaddingNode(id.child("padding"), Insets.all(tokens.spacing.large)).apply {
                addChild(column)
            },
        )
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else FALLBACK_WIDTH
        val child = children.first()
        // The icon sits on the title's line, so the text column loses that width.
        val size = child.measure(Constraints(minWidth = available, maxWidth = available), context)
        return Size(available, size.height)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val child = children.first()
        child.arrange(Rect.of(Vec2.Zero, child.measuredSize), context)
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val corners = Corners.all(tokens.radii.large)

        renderer.roundedRect(bounds, corners, palette.elevatedPanelBackground)
        renderer.border(bounds, corners, tokens.metrics.borderWidth, palette.border)
        context.diagnostics.drawCalls += 2

        icon?.let {
            val titleBounds = titleNode.absoluteBounds()
            componentContext.icons.draw(
                renderer,
                it,
                Rect(
                    bounds.right - tokens.spacing.large.value - ICON_SIZE,
                    titleBounds.y + (titleBounds.height - ICON_SIZE) / 2f,
                    ICON_SIZE,
                    ICON_SIZE,
                ),
                palette.accent,
            )
            context.diagnostics.drawCalls++
        }
    }

    private companion object {
        const val FALLBACK_WIDTH = 180f
        const val BODY_LINES = 6
        const val ICON_SIZE = 10f
    }
}

/**
 * The right-hand column: what this screen is, and the profile controls.
 *
 * Shown only when the viewport is wide enough to carry a third column — see
 * [ConfigScreenLayoutNode]. At Minecraft's GUI scales a 640-unit viewport already spends
 * most of its width on the sidebar and the settings list, and squeezing a third column
 * in would make all three unreadable.
 */
public class InfoPanelNode(
    id: UiId,
    private val componentContext: ComponentContext,
    aboutTitle: String,
    aboutBody: String,
    tipBody: String? = null,
    profileActions: List<UiNode> = emptyList(),
    profileName: UiState<String> = constantState("Default Profile"),
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    private val column = ColumnNode(id.child("column"), spacing = tokens.spacing.large)

    init {
        column.addChild(
            InfoCardNode(
                id = id.child("about"),
                componentContext = componentContext,
                title = constantState(aboutTitle),
                body = constantState(aboutBody),
                icon = Icons.eye,
                extras = buildList {
                    tipBody?.let {
                        add(
                            InfoCardNode(
                                id = id.child("tip"),
                                componentContext = componentContext,
                                title = constantState("Tip"),
                                body = constantState(it),
                            ),
                        )
                    }
                },
            ),
        )

        column.addChild(
            InfoCardNode(
                id = id.child("profile"),
                componentContext = componentContext,
                title = constantState("Profile"),
                body = profileName,
                icon = Icons.gear,
                extras = profileActions,
            ),
        )

        addChild(
            PaddingNode(id.child("padding"), Insets.all(tokens.spacing.large)).apply {
                addChild(column)
            },
        )
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val width = tokens.metrics.inspectorWidth.value
        val height = constraints.maxHeight.takeIf { constraints.hasBoundedHeight } ?: 0f
        children.first().measure(
            Constraints(minWidth = width, maxWidth = width, maxHeight = height),
            context,
        )
        return Size(width, height)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val child = children.first()
        child.arrange(Rect.of(Vec2.Zero, child.measuredSize), context)
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        // A hairline on the left separates the column without adding another surface.
        renderer.fillRect(
            Rect(bounds.x, bounds.y, 1f, bounds.height),
            context.theme.tokens.colors.border,
        )
        context.diagnostics.drawCalls++
    }
}
