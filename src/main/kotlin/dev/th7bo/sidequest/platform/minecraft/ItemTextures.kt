package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.TextureRef
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier

/**
 * Finds a picture for an item somebody was told about in chat.
 *
 * A drop arrives as a *display name* and nothing else — Hypixel announces `RARE DROP! Enchanted Book`, and the
 * client never sees a stack — so this is the whole of what is available to work from.
 *
 * **It resolves against the game's own registry and gives up when it cannot.** Most SkyBlock items have no
 * vanilla counterpart, and there is no honest way to draw "Revenant Catalyst": the choice is its real picture
 * or none, and a plausible-looking wrong item is worse than nothing because nobody can tell it is wrong. About
 * a third of what SkyBlock drops has a vanilla name — every enchanted book, every vanilla material, most of
 * the mining and farming drops — and those are the ones that get an icon.
 *
 * The texture rather than the model, for the same reason [MinecraftIcons] uses textures: the framework's blit
 * already resolves `minecraft:item.diamond` to `assets/minecraft/textures/item/diamond.png`, so this needs no
 * new rendering and picks up whatever resource pack the player has on. A block's flat texture lives under a
 * different directory, which is why both are tried.
 */
public object ItemTextures {

    /** Cached, because a cinematic asks once per frame and the answer for a given name never changes. */
    private val cache = HashMap<String, TextureRef?>()

    /**
     * A texture for [displayName], or null when the game has no item by that name.
     *
     * Matching is on the *registry id* built from the name — `Enchanted Book` becomes `enchanted_book` — and
     * not on the item's translated display name. Translations differ per language, and a feature that worked
     * only in English would be a feature that quietly stopped working for half the group.
     */
    public fun textureFor(displayName: String): TextureRef? = cache.getOrPut(displayName) { resolve(displayName) }

    private fun resolve(displayName: String): TextureRef? {
        val path = displayName.trim().lowercase()
            .replace(' ', '_')
            // Apostrophes and the like are not in a registry id. `Necron's Handle` has no vanilla item
            // anyway, but stripping them costs nothing and makes the near misses actually near.
            .filter { it.isLetterOrDigit() || it == '_' }
        if (path.isEmpty()) return null

        val id = runCatching { Identifier.fromNamespaceAndPath("minecraft", path) }.getOrNull() ?: return null
        if (!BuiltInRegistries.ITEM.containsKey(id)) return null

        // An item's own texture first, then the block's flat face. Both are checked because **a placeable
        // block has no `item/` texture at all** — `diamond_block` lives only under `block/`, and referencing
        // the item path would draw the missing-texture chequerboard. Being in the item registry says nothing
        // about which directory the picture is in.
        return textureIfPresent("item/$path") ?: textureIfPresent("block/$path")
    }

    /**
     * A reference to [path], if the resource actually exists.
     *
     * Asked of the resource manager rather than assumed, so a resource pack that removes a texture is a
     * missing icon rather than a chequerboard — and so the block fallback above can be a real test instead of
     * a guess about which items are placeable.
     */
    private fun textureIfPresent(path: String): TextureRef? {
        val identifier = Identifier.fromNamespaceAndPath("minecraft", "textures/$path.png")
        val manager = net.minecraft.client.Minecraft.getInstance().resourceManager
        if (manager.getResource(identifier).isEmpty) return null
        return TextureRef(UiId.of("minecraft", path.replace('/', '.')))
    }

    /** Drops the cache. For a resource reload, where a pack may have added or removed textures. */
    public fun invalidate(): Unit = cache.clear()
}
