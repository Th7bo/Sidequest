package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.SidequestSettings
import dev.th7bo.sidequest.platform.core.settings.ContextualOverride
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.skyblock.Island
import kotlin.time.Duration.Companion.seconds

/**
 * Turns view bobbing off while farming.
 *
 * Bobbing on the Garden is genuinely unpleasant — the camera sways through every one of the thousands of
 * blocks a farming run breaks — and turning it off by hand every time is the sort of thing nobody does twice.
 *
 * **The setting is borrowed, not taken.** All of the care is in [ContextualOverride]: it remembers what the
 * player had, gives it back on the way out, and stands down if they change it themselves rather than fighting
 * them once a second. A mod that left somebody's bobbing permanently off, weeks later, with no way to connect
 * it to the mod, is the failure this exists to avoid.
 */
class GardenViewBobbing(
    /** Reads the game's own setting. Supplied by the mod, since the platform has no Minecraft. */
    private val readBobbing: () -> Boolean,
    /** Writes it. */
    private val writeBobbing: (Boolean) -> Unit,
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("garden.view_bobbing"),
        displayName = "Garden view bobbing",
        category = FeatureCategory.VISUALS,
        description = "Turns view bobbing off while you are on the Garden",
    )

    private lateinit var context: FeatureContext

    private val override = ContextualOverride<Boolean>()

    override fun onEnable(context: FeatureContext) {
        this.context = context

        // Polled rather than driven off the island event alone, because the player can change the setting
        // themselves at any moment and the override has to notice. A boolean read once a second is nothing,
        // and it means the "they took over" path is exercised in practice rather than only in a test.
        context.scheduler.every(context.owner, period = CHECK, initialDelay = CHECK) { check() }
    }

    override fun onDisable() {
        // Handed back on the way out. A feature that disabled itself and kept one of your settings hostage
        // would be worse than one that never ran.
        override.release(readBobbing())?.let(writeBobbing)
    }

    private fun check() {
        val desired = if (shouldSuppress()) false else null
        override.apply(readBobbing(), desired)?.let { value ->
            writeBobbing(value)
            context.log.debug { "View bobbing ${if (value) "restored" else "turned off"} for the Garden" }
        }
    }

    /** Both Gardens, because a visit to somebody else's is the same camera through the same crops. */
    private fun shouldSuppress(): Boolean =
        SidequestSettings.Garden.suppressViewBobbing &&
            context.gameContext.island in GARDENS

    private companion object {
        val GARDENS = setOf(Island.GARDEN, Island.GARDEN_GUEST)

        /** Often enough that walking in feels immediate, rarely enough to be free. */
        val CHECK = 1.seconds
    }
}
