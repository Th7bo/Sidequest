package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.config.Setting
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.content.SurfaceNode
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.core.layout.ColumnNode
import dev.th7bo.sidequest.ui.core.layout.PaddingNode
import dev.th7bo.sidequest.ui.core.layout.RowNode
import dev.th7bo.sidequest.ui.core.layout.SpacerNode
import dev.th7bo.sidequest.ui.core.tree.RenderContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Insets
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.VerticalAlignment
import dev.th7bo.sidequest.ui.rendering.TextOverflow
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.rendering.UiRenderer
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.derivedStateOf
import dev.th7bo.sidequest.ui.state.map
import dev.th7bo.sidequest.ui.validation.Severity

/**
 * The shared frame every setting is presented in.
 *
 * Label and description on the left, the control on the right, and beneath them
 * whatever the setting currently has to say — a validation error, a warning, a
 * restart notice.
 *
 * Controls supply only their own widget. Everything a *row* is responsible for —
 * spacing, disabled dimming, hover, the badge strip, reacting to `visibleWhen` — lives
 * here once rather than in twenty controls.
 */
public class SettingRowNode(
    public val setting: Setting<*>,
    private val componentContext: ComponentContext,
    /** The control itself, right-aligned. */
    control: UiNode,
) : UiNode(setting.id.child("row")) {

    private val tokens = componentContext.theme.tokens

    /** Combined message strip: validation first, then warnings, then flags. */
    private val statusText: UiState<String> = derivedStateOf("${setting.id.value}.status") {
        val validation = setting.validation.value
        val issue = validation.primaryIssue
        when {
            issue != null -> issue.remediation?.let { "${issue.message} — $it" } ?: issue.message
            setting.metadata.warning.value != null -> setting.metadata.warning.value.orEmpty()
            setting.metadata.requiresRestart -> "Takes effect after a restart"
            setting.metadata.isExperimental -> "Experimental"
            else -> ""
        }
    }

    private val statusNode = TextNode(setting.id.child("status"), statusText, TextRole.CAPTION).apply {
        maxLines = STATUS_LINES
        overflow = TextOverflow.WRAP
    }

    init {
        interactive = true

        val labelColumn = ColumnNode(setting.id.child("labels"), spacing = tokens.spacing.xs).apply {
            layoutWeight = 1f
            addChild(TextNode(setting.id.child("title"), setting.metadata.title, TextRole.LABEL))
            setting.metadata.description?.let { description ->
                addChild(
                    TextNode(setting.id.child("description"), description, TextRole.SECONDARY).apply {
                        maxLines = DESCRIPTION_LINES
                        overflow = TextOverflow.WRAP
                    },
                )
            }
        }

        val header = RowNode(setting.id.child("header"), spacing = tokens.spacing.large).apply {
            verticalAlignment = VerticalAlignment.CENTER
            addChildren(labelColumn, SpacerNode(setting.id.child("gap")), control)
        }

        val body = ColumnNode(setting.id.child("body"), spacing = tokens.spacing.small).apply {
            addChildren(header, statusNode)
        }

        addChild(
            PaddingNode(
                setting.id.child("padding"),
                Insets.symmetric(tokens.spacing.large, tokens.spacing.medium),
            ).apply { addChild(body) },
        )

        // The row follows the setting's own reactive rules rather than being told when
        // to hide: `visibleWhen` is a dependency, not a callback the caller must wire.
        setting.isVisible.observe(scope) { isVisible = it }
        isVisible = setting.isVisible.peek()

        setting.isEnabled.observe(scope) { invalidatePaint() }

        // An empty status must not leave a gap where a message would go.
        statusText.observe(scope) { statusNode.isVisible = it.isNotEmpty() }
        statusNode.isVisible = statusText.peek().isNotEmpty()
    }

    override fun measureSelf(
        constraints: dev.th7bo.sidequest.ui.geometry.Constraints,
        context: dev.th7bo.sidequest.ui.core.tree.LayoutContext,
    ): dev.th7bo.sidequest.ui.geometry.Size {
        val child = children.firstOrNull { it.isVisible }
            ?: return dev.th7bo.sidequest.ui.geometry.Size(constraints.minWidth, constraints.minHeight)
        val size = child.measure(constraints, context)
        // Rows fill the available width so the control column lines up down the screen.
        return if (constraints.hasBoundedWidth) {
            dev.th7bo.sidequest.ui.geometry.Size(constraints.maxWidth, size.height)
        } else {
            size
        }
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors

        if (isHovered && setting.isEnabled.peek()) {
            renderer.roundedRect(bounds, context.theme.tokens.radii.small, palette.hoverBackground)
            context.diagnostics.drawCalls++
        }

        // State is never signalled by colour alone: an error also carries its message in
        // the status strip below, which is why that strip is part of the row.
        val severity = setting.validation.peek().primaryIssue?.severity
        if (severity != null) {
            val accent = if (severity == Severity.ERROR) palette.error else palette.warning
            renderer.fillRect(
                Rect(bounds.x, bounds.y, MARKER_WIDTH, bounds.height),
                accent,
            )
            context.diagnostics.drawCalls++
        }
    }

    /** Status colour, exposed so tests can assert error and warning presentation. */
    public val statusSeverity: Severity? get() = setting.validation.peek().primaryIssue?.severity

    private companion object {
        const val DESCRIPTION_LINES = 3
        const val STATUS_LINES = 2
        const val MARKER_WIDTH = 2f
    }
}

/** Static content rows — descriptions, warnings, errors and dividers. */
public class NoticeRowNode(
    private val notice: dev.th7bo.sidequest.ui.config.NoticeSetting,
    componentContext: ComponentContext,
) : UiNode(notice.id.child("notice")) {

    private val tokens = componentContext.theme.tokens

    init {
        if (notice.kind == dev.th7bo.sidequest.ui.config.NoticeSetting.Kind.DIVIDER) {
            addChild(
                PaddingNode(
                    notice.id.child("padding"),
                    Insets.symmetric(tokens.spacing.large, tokens.spacing.small),
                ).apply {
                    addChild(
                        SurfaceNode(notice.id.child("rule")).apply {
                            preferredSize = dev.th7bo.sidequest.ui.geometry.Size(0f, 1f)
                            colorToken = { it.colors.border }
                        },
                    )
                },
            )
        } else {
            val column = ColumnNode(notice.id.child("text"), spacing = tokens.spacing.xs)
            val titleText = notice.metadata.title
            if (titleText.peek().isNotEmpty()) {
                column.addChild(TextNode(notice.id.child("title"), titleText, TextRole.LABEL))
            }
            column.addChild(
                TextNode(notice.id.child("body"), notice.body, TextRole.SECONDARY).apply {
                    maxLines = BODY_LINES
                    overflow = TextOverflow.WRAP
                },
            )
            addChild(
                PaddingNode(
                    notice.id.child("padding"),
                    Insets.symmetric(tokens.spacing.large, tokens.spacing.medium),
                ).apply { addChild(column) },
            )
        }
    }

    override fun measureSelf(
        constraints: dev.th7bo.sidequest.ui.geometry.Constraints,
        context: dev.th7bo.sidequest.ui.core.tree.LayoutContext,
    ): dev.th7bo.sidequest.ui.geometry.Size {
        val child = children.firstOrNull()
            ?: return dev.th7bo.sidequest.ui.geometry.Size(constraints.minWidth, constraints.minHeight)
        val size = child.measure(constraints, context)
        return if (constraints.hasBoundedWidth) {
            dev.th7bo.sidequest.ui.geometry.Size(constraints.maxWidth, size.height)
        } else {
            size
        }
    }

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        val palette = context.theme.tokens.colors
        val backdrop = when (notice.kind) {
            dev.th7bo.sidequest.ui.config.NoticeSetting.Kind.WARNING ->
                palette.warning.withAlpha(NOTICE_ALPHA)
            dev.th7bo.sidequest.ui.config.NoticeSetting.Kind.ERROR ->
                palette.error.withAlpha(NOTICE_ALPHA)
            else -> return
        }
        renderer.roundedRect(bounds, context.theme.tokens.radii.small, backdrop)
        context.diagnostics.drawCalls++
    }

    private companion object {
        const val BODY_LINES = 4
        const val NOTICE_ALPHA = 0.14f
    }
}

/** A section title with an optional description, rendered between groups of rows. */
public class SectionHeaderNode(
    private val section: dev.th7bo.sidequest.ui.config.Section,
    componentContext: ComponentContext,
) : UiNode(section.id.child("header")) {

    private val tokens = componentContext.theme.tokens

    init {
        val column = ColumnNode(section.id.child("header_text"), spacing = tokens.spacing.xs)
        column.addChild(TextNode(section.id.child("title"), section.title, TextRole.TITLE))
        section.description?.let {
            column.addChild(TextNode(section.id.child("subtitle"), it, TextRole.SECONDARY))
        }
        addChild(
            PaddingNode(
                section.id.child("padding"),
                Insets.of(
                    left = tokens.spacing.large,
                    top = tokens.spacing.xl,
                    right = tokens.spacing.large,
                    bottom = tokens.spacing.small,
                ),
            ).apply { addChild(column) },
        )
    }

    override fun measureSelf(
        constraints: dev.th7bo.sidequest.ui.geometry.Constraints,
        context: dev.th7bo.sidequest.ui.core.tree.LayoutContext,
    ): dev.th7bo.sidequest.ui.geometry.Size {
        val child = children.first()
        val size = child.measure(constraints, context)
        return if (constraints.hasBoundedWidth) {
            dev.th7bo.sidequest.ui.geometry.Size(constraints.maxWidth, size.height)
        } else {
            size
        }
    }
}

/** Convenience for a label whose text never changes. */
internal fun staticLabel(
    id: dev.th7bo.sidequest.ui.ids.UiId,
    text: String,
    role: TextRole = TextRole.LABEL,
): TextNode = TextNode(id, constantState(text), role)

/** Formats a setting's value for a readout, reacting to changes. */
internal fun <T> Setting<T>.formatted(format: (T) -> String): UiState<String> =
    state.map("${id.value}.formatted") { format(it) }
