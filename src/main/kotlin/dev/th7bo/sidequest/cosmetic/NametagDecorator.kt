package dev.th7bo.sidequest.cosmetic

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.platform.cosmetic.CosmeticResolution
import dev.th7bo.sidequest.platform.cosmetic.CosmeticSlot
import dev.th7bo.sidequest.platform.player.PlayerId
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.world.entity.player.Player

/**
 * Builds the nametag a player is drawn with.
 *
 * The bridge between the cosmetic service and Minecraft's text, and it deliberately decides nothing: it asks
 * for a resolution and lays out whatever comes back. Every question about *whether* something should appear —
 * the viewer's settings, the wearer's visibility, a missing asset, a conflict — was already answered, in one
 * place, before this was called.
 *
 * Four slots land here, in reading order:
 *
 * ```
 * [Inner Circle] ♛ chrooted ✦ ◆
 *  title         prefix name  suffix badge
 * ```
 *
 * All on one line, because Minecraft's nametag is one line: it is drawn with a single `drawInBatch` call, so a
 * newline would render as a glyph rather than as a break. A second line means submitting a second piece of
 * text at a different height, which is a different and much more version-sensitive mixin than this one — and
 * is why the title is a bracketed prefix here rather than floating above the name.
 *
 * **This runs once per player per frame.** Everything in it is a map lookup and some string building; nothing
 * allocates a texture, touches the network, or asks the asset manager to do IO. A resolution that had to wait
 * on anything would stutter the game once per player on screen.
 */
public object NametagDecorator {

    /**
     * The original name with whatever the player is wearing around it.
     *
     * Returns [original] unchanged — the same instance, which the mixin checks for — when there is nothing to
     * add, so the common case costs one lookup and no allocation.
     */
    @JvmStatic
    public fun decorate(player: Player, original: Component): Component {
        // The level's colour first, and independently: it is a property of the name Hypixel already drew,
        // not something the mod is adding around it, so it applies to everybody rather than only to the
        // people wearing something. Every path below returns this rather than `original` for that reason.
        val base = LevelNametagColour.recolour(original)

        val platform = Sidequest.platformOrNull ?: return base
        val resolution = runCatching { platform.cosmetics.resolve(PlayerId.of(player.uuid)) }
            // A failure here would be a crash in the render loop, once per frame, forever. Whatever went
            // wrong, the right answer on screen is the player's ordinary name.
            .getOrElse { return base }

        if (resolution.shown.isEmpty()) return base
        val parts = layout(resolution) ?: return base

        val built: MutableComponent = Component.empty()
        parts.title?.let {
            built.append(bracketed(it, colourOf(resolution, CosmeticSlot.TITLE))).append(SPACE)
        }
        parts.prefix?.let {
            built.append(coloured(it, colourOf(resolution, CosmeticSlot.NAMETAG_PREFIX))).append(SPACE)
        }
        built.append(base)
        parts.suffix?.let {
            built.append(SPACE).append(coloured(it, colourOf(resolution, CosmeticSlot.NAMETAG_SUFFIX)))
        }
        parts.badge?.let {
            built.append(SPACE).append(coloured(it, colourOf(resolution, CosmeticSlot.BADGE)))
        }
        return built
    }

    /**
     * The same layout, as plain text.
     *
     * For `/sqcos resolve`, and it exists because **you cannot see your own nametag** — Minecraft's
     * `shouldShowName` excludes the camera entity, so the one player whose cosmetics somebody is most likely
     * to be testing is the one player they cannot look at. Without this, checking your own nametag means
     * finding a second person.
     *
     * It shares [layout] with [decorate] rather than rebuilding the string, for the reason the cinematic
     * counter had to learn: two functions that are supposed to produce the same text will eventually not.
     */
    public fun preview(resolution: CosmeticResolution, name: String): String? {
        val parts = layout(resolution) ?: return null
        return buildString {
            parts.title?.let { append('[').append(it).append("] ") }
            parts.prefix?.let { append(it).append(' ') }
            append(name)
            parts.suffix?.let { append(' ').append(it) }
            parts.badge?.let { append(' ').append(it) }
        }
    }

    private class Parts(val title: String?, val prefix: String?, val suffix: String?, val badge: String?)

    /** What each text slot contributes, or null when none of them contribute anything. */
    private fun layout(resolution: CosmeticResolution): Parts? {
        val parts = Parts(
            title = textOf(resolution, CosmeticSlot.TITLE),
            prefix = textOf(resolution, CosmeticSlot.NAMETAG_PREFIX),
            suffix = textOf(resolution, CosmeticSlot.NAMETAG_SUFFIX),
            badge = textOf(resolution, CosmeticSlot.BADGE),
        )
        if (parts.title == null && parts.prefix == null && parts.suffix == null && parts.badge == null) {
            return null
        }
        return parts
    }

    /**
     * The text a slot contributes, or null.
     *
     * A cosmetic with no text contributes nothing here even when it resolved. That is not a failure: an
     * asset-backed badge is an image, and this bridge draws text — the image needs a different surface, which
     * does not exist yet, so the honest thing is to draw nothing rather than its internal name.
     *
     * Trimmed, because the layout supplies its own spacing. A cosmetic whose text ends in a space — which is
     * the natural way to write a prefix — would otherwise render with a gap twice as wide as its neighbours.
     */
    private fun textOf(resolution: CosmeticResolution, slot: CosmeticSlot): String? =
        resolution[slot]?.cosmetic?.text?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LENGTH)

    /** A cosmetic's rarity colour, which is what makes a legendary title read as one. */
    private fun colourOf(resolution: CosmeticResolution, slot: CosmeticSlot): Int? =
        resolution[slot]?.cosmetic?.rarity?.colour

    private fun coloured(text: String, colour: Int?): Component {
        val component = Component.literal(text)
        return if (colour == null) component else component.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(colour)))
    }

    /** Brackets stay grey whatever colour the title is, so a row of titles lines up visually. */
    private fun bracketed(text: String, colour: Int?): Component = Component.empty()
        .append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
        .append(coloured(text, colour))
        .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY))

    private val SPACE: Component = Component.literal(" ")

    /**
     * How much of one slot's text is drawn.
     *
     * The platform already truncates what arrives over the network, so this only bites on a locally defined
     * cosmetic. It is here anyway because a nametag is drawn in world space over somebody's head, and a long
     * one covers the screen for everybody near them rather than only for its owner.
     */
    private const val MAX_LENGTH = 24
}
