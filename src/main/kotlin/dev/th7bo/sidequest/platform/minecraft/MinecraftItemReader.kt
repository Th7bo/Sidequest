package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.core.item.SkyBlockItemReader
import dev.th7bo.sidequest.platform.item.ItemAcquisition
import dev.th7bo.sidequest.platform.item.ItemDataSource
import dev.th7bo.sidequest.platform.item.ItemIcon
import dev.th7bo.sidequest.platform.item.SqItem
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import kotlin.jvm.optionals.getOrNull

/**
 * Reads a real [ItemStack] into a [SqItem].
 *
 * Deliberately thin. Everything about *interpreting* a SkyBlock item — every attribute name,
 * every upgrade, the gemstone tag's three shapes — lives in `SkyBlockItemReader` where a test
 * can drive it from a map. This end does only what needs the game: pull out the item id, the
 * name, the lore, the count, and hand over Hypixel's tag behind [ItemDataSource].
 *
 * Nothing here returns an `ItemStack`, and nothing above here ever sees one. That is the whole
 * point of the model: a stack cannot be stored, and a record of a rare drop has to outlive
 * several Minecraft versions.
 */
fun ItemStack.toSq(
    quantity: Int = count,
    acquisition: ItemAcquisition? = null,
): SqItem = SkyBlockItemReader.read(
    minecraftId = BuiltInRegistries.ITEM.getKey(item).toString(),
    displayName = hoverName.toLegacyFormatting(),
    lore = loreLines(),
    quantity = quantity,
    data = skyBlockData(),
    icon = iconOf(),
    acquisition = acquisition,
)

/**
 * Hypixel's own item data.
 *
 * It arrives in the `CUSTOM_DATA` component, which is where the old `ExtraAttributes` compound
 * ended up: `id`, `uuid`, `modifier` and the rest sit at the top level of it. Established from
 * SkyHanni, which reads it the same way.
 *
 * [ItemDataSource.Empty] for anything without the component, which is every vanilla item and
 * produces a perfectly valid snapshot with the SkyBlock half absent.
 */
private fun ItemStack.skyBlockData(): ItemDataSource =
    get(DataComponents.CUSTOM_DATA)?.copyTag()?.let(::CompoundItemData) ?: ItemDataSource.Empty

/** The lore as shown, formatting intact. */
private fun ItemStack.loreLines(): List<String> =
    get(DataComponents.LORE)?.lines()?.map { it.toLegacyFormatting() } ?: emptyList()

/**
 * How the item is drawn.
 *
 * The skin texture matters: most custom-looking SkyBlock items are player heads wearing one,
 * and without it a snapshot of a Kuudra core renders as a bare head. It is kept as the encoded
 * value rather than resolved, because resolving means a network call to draw an inventory.
 */
private fun ItemStack.iconOf(): ItemIcon = ItemIcon(
    minecraftId = BuiltInRegistries.ITEM.getKey(item).toString(),
    skinTexture = skinTexture(),
    dyeColor = get(DataComponents.DYED_COLOR)?.rgb,
)

/**
 * The base64 skin value on a player head, if there is one.
 *
 * Guarded rather than assumed: the profile component's shape has moved between versions, and an
 * icon is decoration — failing to read one must not stop an item being recorded.
 */
private fun ItemStack.skinTexture(): String? = runCatching {
    val profile = get(DataComponents.PROFILE) ?: return@runCatching null
    profile.partialProfile().properties["textures"].firstOrNull()?.value
}.getOrNull()

/**
 * A [CompoundTag] behind the platform's read-only view of it.
 *
 * The whole of the Minecraft-specific half of item reading, and the reason the rest is
 * testable without a game.
 */
private class CompoundItemData(private val tag: CompoundTag) : ItemDataSource {

    override fun string(key: String): String? = tag.getString(key).getOrNull()

    override fun int(key: String): Int? = tag.getInt(key).getOrNull()

    override fun long(key: String): Long? = tag.getLong(key).getOrNull()

    override fun byte(key: String): Byte? = tag.getByte(key).getOrNull()

    override fun compound(key: String): ItemDataSource? =
        tag.getCompound(key).getOrNull()?.let(::CompoundItemData)

    override fun keys(): Set<String> = tag.keySet()

    /**
     * Whatever is at [key], as a string.
     *
     * `Tag.toString()` renders NBT rather than the value, so the typed reads are tried first
     * and the raw form is the last resort. A number that came out as `3b` instead of `3` would
     * be a small, silent wrongness in every escape-hatch read.
     */
    override fun asString(key: String): String? =
        string(key) ?: int(key)?.toString() ?: long(key)?.toString() ?: byte(key)?.toString()
            ?: tag.get(key)?.toString()
}
