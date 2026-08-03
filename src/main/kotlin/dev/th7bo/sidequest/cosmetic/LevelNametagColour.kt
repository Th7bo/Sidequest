package dev.th7bo.sidequest.cosmetic

import dev.th7bo.sidequest.SidequestSettings
import dev.th7bo.sidequest.platform.core.skyblock.SkyBlockLevelColours
import dev.th7bo.sidequest.platform.minecraft.plainContent
import dev.th7bo.sidequest.platform.minecraft.runs
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor

/**
 * Recolours the SkyBlock level in a nametag.
 *
 * `§8[§b480§8] §6Player` — Hypixel colours the bracketed level itself, in one of the game's sixteen, and this
 * replaces that colour with one from [SkyBlockLevelColours]. Which is the point of doing it at all: a
 * component can carry true RGB, so the bands past the ones the game names are expressible here and are not in
 * chat's legacy formatting.
 *
 * **Rebuilt from the original's own runs rather than from a legacy string.** Converting a component to
 * `§`-codes and back is lossy in both directions — an RGB colour has no legacy code, and a legacy string has
 * no click or hover events — and a nametag that lost a hover on the way through would be a bug caused
 * entirely by the round trip. Walking the runs keeps everything except the one style being replaced.
 */
public object LevelNametagColour {

    /**
     * The nametag with its level recoloured, or [original] unchanged.
     *
     * The same instance comes back when there is nothing to do, which is the common case — most entities are
     * not players with a level — and the caller checks identity to skip its own work.
     */
    @JvmStatic
    public fun recolour(original: Component): Component {
        if (!SidequestSettings.Levels.isEnabled) return original

        // Read off the plain text, so an index is an index into something a run can be measured against.
        // Doing it on formatted text would mean the offsets included colour codes that no run contains.
        val plain = original.string
        val level = SkyBlockLevelColours.levelIn(plain) ?: return original
        val digits = SkyBlockLevelColours.levelRangeIn(plain) ?: return original

        val colour = TextColor.fromRgb(
            SkyBlockLevelColours.colourFor(level, SidequestSettings.Levels.palette),
        )

        val rebuilt: MutableComponent = Component.empty()
        var offset = 0
        var replaced = false

        for (run in original.runs()) {
            val text = run.plainContent()
            if (text.isEmpty()) continue
            val start = offset
            offset += text.length

            // Runs entirely outside the digits keep their own styling, untouched.
            if (digits.last < start || digits.first >= offset) {
                rebuilt.append(Component.literal(text).withStyle(run.style))
                continue
            }

            // The run holding the digits is split into three, so the brackets around them keep the grey
            // Hypixel drew them in. Colouring the whole run would repaint whatever else it carried.
            val from = (digits.first - start).coerceAtLeast(0)
            val until = (digits.last + 1 - start).coerceAtMost(text.length)

            if (from > 0) rebuilt.append(Component.literal(text.substring(0, from)).withStyle(run.style))
            rebuilt.append(
                Component.literal(text.substring(from, until)).withStyle(run.style.withColor(colour)),
            )
            if (until < text.length) {
                rebuilt.append(Component.literal(text.substring(until)).withStyle(run.style))
            }
            replaced = true
        }

        // Nothing was actually recoloured — the digits fell outside every run, which should not happen but
        // would otherwise return a rebuilt component identical to the original for no benefit.
        return if (replaced) rebuilt else original
    }
}
