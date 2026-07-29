package dev.th7bo.sidequest

import dev.th7bo.sidequest.ui.components.Icons
import dev.th7bo.sidequest.ui.components.hud.ProgressHudNode
import dev.th7bo.sidequest.ui.components.registerStandardIcons
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.hud.HudElementNode
import dev.th7bo.sidequest.ui.core.hud.HudLayerNode
import dev.th7bo.sidequest.ui.core.hud.HudContext
import dev.th7bo.sidequest.ui.geometry.Anchor
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.hud.HudDefinition
import dev.th7bo.sidequest.ui.hud.HudInstance
import dev.th7bo.sidequest.ui.hud.HudResizeMode
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.minecraft.hud.SidequestHudLayer
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.derivedStateOf
import dev.th7bo.sidequest.ui.state.mutableStateOf

/**
 * The mod's HUD elements.
 *
 * The data behind them is ordinary observable state; nothing here knows how a HUD is
 * drawn, positioned or persisted.
 */
public object SidequestHuds {

    /** Stand-in data until there is a real source. Drives the HUD reactively. */
    public val miningXp: MutableUiState<Long> = mutableStateOf(28_450L, "miningXp")
    public val miningXpRequired: MutableUiState<Long> = mutableStateOf(60_000L, "miningXpRequired")
    public val miningLevel: MutableUiState<Int> = mutableStateOf(42, "miningLevel")

    public val miningXpDefinition: HudDefinition = HudDefinition(
        id = UiId.of(Sidequest.MOD_ID, "hud.mining_xp"),
        title = constantState("Mining XP"),
        category = constantState("Skills"),
        icon = Icons.gear,
        defaultAnchor = Anchor.BOTTOM_CENTER,
        defaultOffset = Vec2(0f, -90f),
        defaultScale = 1f,
        scaleRange = 0.5f..2.5f,
        resizeMode = HudResizeMode.SCALE_ONLY,
        keywords = listOf("mining", "skill", "experience", "progress"),
    )

    /** Registers everything on the live HUD layer. */
    public fun register() {
        SidequestHudLayer.icons.registerStandardIcons(
            dev.th7bo.sidequest.ui.extension.RegistrationScope(UiId.of(Sidequest.MOD_ID, "hud_icons")),
        )
        SidequestHudLayer.onPopulate = { layer, context -> populate(layer, context) }
        SidequestHudLayer.register { Sidequest.activeTheme() }
    }

    private fun populate(layer: HudLayerNode, context: ComponentContext) {
        layer.add(createMiningXpHud(context))
    }

    /** Builds the mining XP element. Shared by the game and by the in-game tests. */
    public fun createMiningXpHud(context: ComponentContext): HudElementNode {
        val instance = HudInstance(
            UiId.of(Sidequest.MOD_ID, "hud.mining_xp.default"),
            miningXpDefinition.id,
        )
        val content = ProgressHudNode(
            id = instance.instanceId.child("content"),
            componentContext = context,
            title = constantState("Mining XP"),
            current = miningXp,
            maximum = miningXpRequired,
            subtitle = derivedStateOf("miningLevelLabel") { "Lv. ${miningLevel.value}" },
            icon = Icons.gear,
        )
        return HudElementNode(
            instance = instance,
            definition = miningXpDefinition,
            hudContext = {
                HudContext(
                    screenSize = Size(1f, 1f),
                    guiScale = 1f,
                    partialTick = 0f,
                    components = context,
                )
            },
            content = content,
        )
    }
}
