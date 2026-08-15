package dev.th7bo.sidequest.ui.minecraft.rendering

import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import dev.th7bo.sidequest.ui.rendering.ItemRef
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
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
    public fun stackFor(item: ItemRef): ItemStack? {
        cache[item]?.let { return it }
        // During the earliest client-gametest frames the item registry exists before its data components
        // are bound. Constructing a stack then throws "Components not bound yet". That state is temporary,
        // so it must neither crash the screen nor poison the cache with an empty stack: a later frame retries.
        val built = runCatching { build(item) }.getOrNull() ?: return null
        if (built.isEmpty) return null
        cache[item] = built
        return built
    }

    private fun build(item: ItemRef): ItemStack? {
        val model = modelFor(item.model)
        val renderId = if (item.model != null && model == null) item.fallbackId ?: item.id else item.id
        val id = runCatching { Identifier.parse(renderId) }.getOrNull() ?: return null
        val registered = BuiltInRegistries.ITEM.getOptional(id).orElse(null) ?: return null

        // In 26.2 item components live on the synchronized, level-scoped registry holders. The built-in
        // holder still identifies the item, but deliberately has no component map bound to it; constructing
        // a stack from it therefore throws "Components not bound yet" for the entire session. Prefer the
        // runtime holder whenever a world exists. The fallback keeps item rendering available on older
        // supported clients and on screens that can legitimately appear before a world is joined.
        val runtimeHolder = net.minecraft.client.Minecraft.getInstance().level
            ?.registryAccess()
            ?.lookup(Registries.ITEM)
            ?.orElse(null)
            ?.get(id)
            ?.orElse(null)
        val stack = runtimeHolder?.let(::ItemStack) ?: ItemStack(registered)
        item.skin?.let { stack.set(DataComponents.PROFILE, profileFor(it, item.skinSignature)) }
        model?.let { stack.set(DataComponents.ITEM_MODEL, it) }
        return stack
    }

    /**
     * Hypixel's own model for an item, but only if this client can actually draw it.
     *
     * SkyBlock ships a resource pack, and most of what it adds is a vanilla item wearing one of its models
     * — which is why a Revenant Catalyst was drawing as the sheet of paper it is built on. Somebody running
     * that pack, or one that restores the original art, has the model on disk, and handing the id to the
     * game draws the real thing.
     *
     * **Asked of the resource manager rather than assumed.** A model that is not installed does not fall
     * back gracefully: the game draws its missing-model cube, which is far worse than the vanilla item. So
     * the file has to be there — and for anybody without the pack this returns null and nothing changes.
     */
    private fun modelFor(model: String?): Identifier? {
        if (model.isNullOrEmpty()) return null
        val id = runCatching { Identifier.parse(model) }.getOrNull() ?: return null
        // Item models are declared under `items/`, not `models/`: since 1.21.4 that directory holds the
        // *definition* the `minecraft:item_model` component names, and it is what has to exist.
        val definition = Identifier.fromNamespaceAndPath(id.namespace, "items/" + id.path + ".json")
        val manager = net.minecraft.client.Minecraft.getInstance().resourceManager
        return if (manager.getResource(definition).isPresent) id else null
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
    private fun profileFor(skin: String, signature: String?): ResolvableProfile {
        val property = signature?.let { Property(TEXTURES, skin, it) } ?: Property(TEXTURES, skin)
        val properties = PropertyMap(ImmutableMultimap.of(TEXTURES, property))
        val profile = GameProfile(UUID.nameUUIDFromBytes(skin.toByteArray()), SKIN_HOLDER, properties)
        return ResolvableProfile.createResolved(profile)
    }

    /** Drops the cache. For a resource reload, where the game's own item rendering has been rebuilt. */
    public fun invalidate(): Unit = cache.clear()

    private const val TEXTURES = "textures"

    /**
     * The profile needs a name and nothing reads it — the head is drawn from the texture, not the owner.
     *
     * Checked rather than assumed, after a screen of heads all drew the same skin and this looked like the
     * shared key responsible: in 26.2 both caches involved are keyed by the whole profile — `SkinManager` on
     * `(UUID, textures property)` and `PlayerSkinRenderCache` on the `ResolvableProfile` — and the UUID here
     * is already derived from the texture. The name genuinely does not participate.
     */
    private const val SKIN_HOLDER = "Sidequest"
}
