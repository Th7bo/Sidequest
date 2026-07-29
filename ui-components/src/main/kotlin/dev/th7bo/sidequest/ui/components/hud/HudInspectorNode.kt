package dev.th7bo.sidequest.ui.components.hud

import dev.th7bo.sidequest.ui.binding.RefreshableBinding
import dev.th7bo.sidequest.ui.binding.asBinding
import dev.th7bo.sidequest.ui.binding.bind
import dev.th7bo.sidequest.ui.components.ButtonControlNode
import dev.th7bo.sidequest.ui.components.DropdownControlNode
import dev.th7bo.sidequest.ui.components.FloatSliderControlNode
import dev.th7bo.sidequest.ui.components.ToggleControlNode
import dev.th7bo.sidequest.ui.config.ButtonSetting
import dev.th7bo.sidequest.ui.config.DropdownSetting
import dev.th7bo.sidequest.ui.config.FloatSliderSetting
import dev.th7bo.sidequest.ui.config.Option
import dev.th7bo.sidequest.ui.config.Setting
import dev.th7bo.sidequest.ui.config.SettingMetadata
import dev.th7bo.sidequest.ui.config.ToggleSetting
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.content.SurfaceNode
import dev.th7bo.sidequest.ui.core.content.TextNode
import dev.th7bo.sidequest.ui.core.hud.editor.HudEditorSession
import dev.th7bo.sidequest.ui.core.layout.ColumnNode
import dev.th7bo.sidequest.ui.core.layout.PaddingNode
import dev.th7bo.sidequest.ui.core.layout.SpacerNode
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.geometry.Anchor
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Insets
import dev.th7bo.sidequest.ui.geometry.Rect
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.hud.HudDefinition
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.TextRole
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.mutableStateOf

/**
 * The editor's right-hand panel: what is selected, and the controls that change it.
 *
 * Built from ordinary [Setting] objects bound to the session, so the inspector reuses
 * the configuration screen's controls rather than growing a parallel set. A slider here
 * is the same `FloatSliderControlNode` a config screen uses, with a binding that reads
 * and writes placement instead of a config file.
 *
 * Rebuilt when the selection changes, because the bindings are per-selection: the
 * scale slider's range comes from the selected definition, and a shared control could
 * not express two definitions with different ranges.
 */
public class HudInspectorNode(
    id: UiId,
    private val session: HudEditorSession,
    private val componentContext: ComponentContext,
) : UiNode(id) {

    private val tokens = componentContext.theme.tokens

    private val body = ColumnNode(id.child("body"), spacing = tokens.spacing.small)

    /** Refreshed each frame so the readouts follow a drag in progress. */
    private val bindings = ArrayList<RefreshableBinding<*>>(BINDING_CAPACITY)

    private val offsetReadout = mutableStateOf("", "${id.value}.offset")

    init {
        addChild(
            SurfaceNode(id.child("surface")).apply {
                colorToken = { it.colors.panelBackground }
                addChild(
                    PaddingNode(id.child("padding"), Insets.all(tokens.spacing.medium))
                        .apply { addChild(body) },
                )
            },
        )

        session.selection.observe(scope) { rebuild() }
        rebuild()
    }

    /** Re-reads every binding. Cheap, and the only thing that keeps a drag live. */
    public fun refresh() {
        for (binding in bindings) binding.refresh()
        offsetReadout.value = describeOffset()
    }

    private fun describeOffset(): String {
        val elements = session.selectedElements
        if (elements.size != 1) return ""
        val placement = elements.first().placement.peek()
        return "${placement.offset.x.toInt()}, ${placement.offset.y.toInt()}"
    }

    private fun rebuild() {
        body.clearChildren()
        bindings.clear()

        val selected = session.selectedElements
        if (selected.isEmpty()) {
            body.addChild(
                TextNode(
                    id.child("empty"),
                    constantState("Select a HUD element to edit it."),
                    TextRole.SECONDARY,
                ),
            )
            return
        }

        val title = if (selected.size == 1) {
            selected.first().definition.title.peek()
        } else {
            "${selected.size} elements"
        }
        body.addChild(TextNode(id.child("title"), constantState(title), TextRole.TITLE))

        if (selected.size == 1) {
            body.addChild(TextNode(id.child("offset_label"), offsetReadout, TextRole.SECONDARY))
        }

        addRow("Anchor", anchorSetting()) { DropdownControlNode(it, componentContext) }

        // Only offered when every selected definition actually supports it — a control
        // that silently does nothing is worse than one that is not there.
        if (selected.all { it.definition.resizeMode.supportsScale }) {
            addRow("Scale", scaleSetting()) { FloatSliderControlNode(it, componentContext) }
        }
        addRow("Opacity", opacitySetting()) { FloatSliderControlNode(it, componentContext) }
        addRow("Locked", lockedSetting()) { ToggleControlNode(it, componentContext) }

        body.addChild(SpacerNode(id.child("gap"), Size(0f, tokens.spacing.small.value)))
        body.addChild(actionButton("front", "Bring to front") { session.bringToFront() })
        body.addChild(actionButton("back", "Send to back") { session.sendToBack() })
        body.addChild(actionButton("reset", "Reset placement") { session.resetSelection() })
    }

    /**
     * A stacked row: caption above control.
     *
     * The configuration screen's side-by-side [SettingRowNode] needs room for a title,
     * a description *and* a control; in a panel this narrow it truncates the title to
     * nothing useful. Stacking gives the label the full width and the control the line
     * below it.
     *
     * Generic in the setting type so each control gets the type it needs without a cast.
     */
    private fun <S : Setting<*>> addRow(caption: String, setting: S, control: (S) -> UiNode) {
        body.addChild(
            ColumnNode(setting.id.child("stack"), spacing = tokens.spacing.xs).apply {
                addChild(TextNode(setting.id.child("caption"), constantState(caption), TextRole.SECONDARY))
                addChild(control(setting).apply { layoutWeight = 0f })
            },
        )
    }

    private fun anchorSetting(): DropdownSetting<Anchor> {
        val binding = bind(
            get = { session.selectedElements.firstOrNull()?.placement?.peek()?.anchor ?: Anchor.TOP_LEFT },
            set = { session.setAnchor(it) },
            debugName = "hud.inspector.anchor",
        )
        bindings.add(binding)
        return DropdownSetting(
            id = id.child("anchor"),
            metadata = SettingMetadata(constantState("Anchor")),
            binding = binding,
            defaultValue = Anchor.TOP_LEFT,
            options = constantState(
                Anchor.entries.map {
                    Option(it.serializedId, constantState(it.readableName()), it)
                },
            ),
        )
    }

    private fun scaleSetting(): FloatSliderSetting {
        // The narrowest range across the selection, so a slider can never ask for a
        // scale one of the selected definitions would refuse.
        val range = session.selectedElements
            .map { it.definition.scaleRange }
            .reduceOrNull { a, b ->
                maxOf(a.start, b.start)..minOf(a.endInclusive, b.endInclusive)
            } ?: HudDefinition.DEFAULT_SCALE_RANGE

        val binding = bind(
            get = { session.selectedElements.firstOrNull()?.placement?.peek()?.scale ?: 1f },
            set = { session.setScale(it) },
            debugName = "hud.inspector.scale",
        )
        bindings.add(binding)
        return FloatSliderSetting(
            id = id.child("scale"),
            metadata = SettingMetadata(constantState("Scale")),
            binding = binding,
            defaultValue = 1f,
            range = range,
            step = SCALE_STEP,
            format = { "${String.format("%.2f", it)}x" },
        )
    }

    private fun opacitySetting(): FloatSliderSetting {
        val binding = bind(
            get = { session.selectedElements.firstOrNull()?.placement?.peek()?.opacity ?: 1f },
            set = { session.setOpacity(it) },
            debugName = "hud.inspector.opacity",
        )
        bindings.add(binding)
        return FloatSliderSetting(
            id = id.child("opacity"),
            metadata = SettingMetadata(constantState("Opacity")),
            binding = binding,
            defaultValue = 1f,
            range = 0f..1f,
            step = OPACITY_STEP,
            format = { "${(it * PERCENT).toInt()}%" },
        )
    }

    private fun lockedSetting(): ToggleSetting {
        val binding = bind(
            get = { session.selectedElements.all { it.placement.peek().locked } },
            set = { session.setLocked(it) },
            debugName = "hud.inspector.locked",
        )
        bindings.add(binding)
        return ToggleSetting(
            id = id.child("locked"),
            metadata = SettingMetadata(constantState("Locked")),
            binding = binding,
            defaultValue = false,
        )
    }

    private fun actionButton(name: String, label: String, action: () -> Unit): UiNode {
        val setting = ButtonSetting(
            id = id.child(name),
            metadata = SettingMetadata(constantState(label)),
            binding = mutableStateOf(0, "${id.value}.$name").asBinding(),
            label = constantState(label),
            onInvoke = action,
        )
        return ButtonControlNode(setting, componentContext)
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
        val child = children.first()
        return child.measure(constraints, context)
    }

    override fun arrangeChildren(context: LayoutContext) {
        val child = children.first()
        child.arrange(Rect.of(Vec2.Zero, child.measuredSize), context)
    }

    private companion object {
        const val BINDING_CAPACITY = 6
        const val SCALE_STEP = 0.05f
        const val OPACITY_STEP = 0.05f
        const val PERCENT = 100f
    }
}

/** `BOTTOM_RIGHT` reads as "Bottom right" in a dropdown. */
private fun Anchor.readableName(): String =
    name.split('_').joinToString(" ") { part ->
        part.lowercase().replaceFirstChar { it.uppercase() }
    }
