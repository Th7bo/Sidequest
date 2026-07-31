package dev.th7bo.sidequest.ui.minecraft.rendering

import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import dev.th7bo.sidequest.ui.rendering.ItemRef
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ResolvableProfile
import java.util.UUID

/**
 * Turns a named item into one the game can draw.
 *
 * The bridge between a [ItemRef] — two strings, no Minecraft — and a real `ItemStack`. It exists on this side
 * of the boundary because building a stack needs the registry, the component system and authlib, none of
 * which the UI framework has or should have.
 *
 * **Cached, and it has to be.** A cinematic asks for its item once a frame, and constructing a head means
 * building a game profile and a property map every time. The answer for a given reference never changes, so
 * the second frame onwards is a map lookup.
 */
public object MinecraftItemStacks {

    /** Keyed by the whole reference, since two items can share an id and differ only by skin. */
    private val cache = HashMap<ItemRef, ItemStack>()

    /**
     * A stack for [item], or null when this client has no such item.
     *
     * Null happens, and legitimately: the item database is shared across Minecraft versions and names items
     * this one may not have. A missing icon is the honest outcome — the caller draws nothing rather than a
     * chequerboard or a stand-in that nobody could tell was wrong.
     */
    public fun stackFor(item: ItemRef): ItemStack? =
        cache.getOrPut(item) { build(item) ?: ItemStack.EMPTY }.takeUnless { it.isEmpty }

    private fun build(item: ItemRef): ItemStack? {
        val id = runCatching { Identifier.parse(item.id) }.getOrNull() ?: return null
        val registered = BuiltInRegistries.ITEM.getOptional(id).orElse(null) ?: return null

        val stack = ItemStack(registered)
        item.skin?.let { stack.set(DataComponents.PROFILE, profileFor(it)) }
        return stack
    }

    /**
     * The profile that carries a custom skin.
     *
     * A player head's texture is not a property of the item — it is a property of the *profile* attached to
     * it, which is why a head cannot be drawn as a flat texture and needs this at all. Minecraft fetches and
     * caches the skin itself from here.
     *
     * The UUID is derived from the texture rather than random, so the same skin is the same profile every
     * time. A fresh UUID per stack would defeat the game's own skin cache and re-fetch the same image for
     * every item that shares a texture.
     */
    private fun profileFor(skin: String): ResolvableProfile {
        val properties = PropertyMap(ImmutableMultimap.of(TEXTURES, Property(TEXTURES, skin)))
        val profile = GameProfile(UUID.nameUUIDFromBytes(skin.toByteArray()), SKIN_HOLDER, properties)
        return ResolvableProfile.createResolved(profile)
    }

    /** Drops the cache. For a resource reload, where the game's own item rendering has been rebuilt. */
    public fun invalidate(): Unit = cache.clear()

    private const val TEXTURES = "textures"

    /** The profile needs a name and nothing reads it — the head is drawn from the texture, not the owner. */
    private const val SKIN_HOLDER = "Sidequest"
}
