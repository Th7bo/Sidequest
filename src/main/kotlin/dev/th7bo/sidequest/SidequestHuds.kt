package dev.th7bo.sidequest

import dev.th7bo.sidequest.ui.components.Icons
import dev.th7bo.sidequest.ui.components.hud.ProgressHudNode
import dev.th7bo.sidequest.ui.components.registerStandardIcons
import dev.th7bo.sidequest.ui.minecraft.registerMinecraftIcons
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.hud.HudElementNode
import dev.th7bo.sidequest.ui.core.hud.HudLayerNode
import dev.th7bo.sidequest.ui.core.hud.HudContext
import dev.th7bo.sidequest.ui.geometry.Anchor
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.hud.HudDefinition
import dev.th7bo.sidequest.ui.hud.previewed
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

    /** Shown while the HUD editor is open, so the card is legible without live data. */
    private const val PREVIEW_XP = 28_450L
    private const val PREVIEW_REQUIRED = 60_000L
    private const val PREVIEW_LEVEL = 42

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
        val iconScope = dev.th7bo.sidequest.ui.extension.RegistrationScope(UiId.of(Sidequest.MOD_ID, "hud_icons"))
        SidequestHudLayer.icons.registerStandardIcons(iconScope)
        // Minecraft's own item textures alongside the mod's flat glyphs, so a screen can use whichever reads
        // better in place.
        SidequestHudLayer.icons.registerMinecraftIcons(iconScope)
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
        return HudElementNode(
            instance = instance,
            definition = miningXpDefinition,
        ) { element ->
            // Representative values while the editor is open. Outside a world there is no
            // real mining XP, and a card reading "0 / 0" is impossible to place well.
            ProgressHudNode(
                id = instance.instanceId.child("content"),
                componentContext = context,
                title = constantState("Mining XP"),
                current = previewed(miningXp, PREVIEW_XP, element.isEditing, "miningXp.preview"),
                maximum = previewed(miningXpRequired, PREVIEW_REQUIRED, element.isEditing, "miningXpRequired.preview"),
                subtitle = derivedStateOf("miningLevelLabel") {
                    if (element.isEditing.value) "Lv. $PREVIEW_LEVEL" else "Lv. ${miningLevel.value}"
                },
                icon = Icons.gear,
            )
        }
    }
}
