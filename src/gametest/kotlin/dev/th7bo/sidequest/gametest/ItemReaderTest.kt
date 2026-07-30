package dev.th7bo.sidequest.gametest

import dev.th7bo.sidequest.platform.item.GemstoneQuality
import dev.th7bo.sidequest.platform.item.GemstoneType
import dev.th7bo.sidequest.platform.item.ItemCategory
import dev.th7bo.sidequest.platform.item.ItemRarity
import dev.th7bo.sidequest.platform.minecraft.toSq
import kotlinx.serialization.json.Json
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import dev.th7bo.sidequest.platform.item.SqItem

/**
 * Reads a real item stack.
 *
 * The item model is covered headlessly against a fake data source, which is where the
 * interpretation belongs — every attribute name is a fact about Hypixel and correcting one
 * should mean running a test. What no fake can cover is the step before it: whether the adapter
 * pulls the right things out of a real `ItemStack`.
 *
 * That step is where a mistake would hide. Hypixel's item data lives in the `CUSTOM_DATA`
 * component, the lore in `LORE`, and the name in `hoverName`; every one of those has moved
 * between Minecraft versions, and every headless test in the suite would still pass if this
 * end read the wrong component or none at all.
 *
 * So this builds a stack shaped the way Hypixel builds one and checks it comes back out.
 */
class ItemReaderTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        context.waitTicks(SETTLE_TICKS)

        // 1. A vanilla stack reads cleanly, with the SkyBlock half simply absent.
        context.runOnClient<RuntimeException> {
            val item = ItemStack(Items.COBBLESTONE, 64).toSq()

            check(item.minecraftId == "minecraft:cobblestone") { "Read the id as '${item.minecraftId}'" }
            check(item.quantity == 64) { "Read the count as ${item.quantity}" }
            check(item.skyblockId == null) { "A cobblestone claimed to be '${item.skyblockId}'" }
            check(item.upgrades.isStock) { "A cobblestone claimed upgrades: ${item.upgrades}" }
        }

        // 2. A stack built the way Hypixel builds one.
        context.runOnClient<RuntimeException> {
            val item = hyperion().toSq()

            // The component the whole model hangs off. If this is wrong, nothing else is read.
            check(item.skyblockId == "HYPERION") { "Read the SkyBlock id as '${item.skyblockId}'" }
            check(item.itemUuid == ITEM_UUID) { "Read the uuid as '${item.itemUuid}'" }

            // The name and lore come from two different components, both version-sensitive.
            check(item.plainName == "Withered Hyperion") { "Read the name as '${item.plainName}'" }
            check(item.lore.size == 2) { "Read ${item.lore.size} lore line(s): ${item.lore}" }

            // Rarity has no attribute at all — it is read off the last lore line, so this
            // failing means the lore did not survive the trip.
            check(item.rarity == ItemRarity.MYTHIC) { "Read the rarity as ${item.rarity}" }
            check(item.category == ItemCategory.SWORD) { "Read the category as ${item.category}" }

            // Nested tags, which are the part most likely to be read as empty.
            check(item.upgrades.reforge == "withered") { "Read the reforge as '${item.upgrades.reforge}'" }
            check(item.upgrades.stars == 10) { "Read ${item.upgrades.stars} star(s)" }
            check(item.upgrades.isRecombobulated) { "Did not read the recombobulator" }
            check(item.enchantments["sharpness"] == 7) { "Read the enchantments as ${item.enchantments}" }
            check(
                item.upgrades.gemstones.any {
                    it.type == GemstoneType.JADE && it.quality == GemstoneQuality.PERFECT
                },
            ) { "Read the gemstones as ${item.upgrades.gemstones}" }

            // And the escape hatch, which must not duplicate anything with a field.
            check(item.extra["edition"] == "42") { "Read extra as ${item.extra}" }
            check("modifier" !in item.extra) { "The reforge was duplicated into extra: ${item.extra}" }

            // The point of the whole type: it survives being stored.
            val json = Json.encodeToString(SqItem.serializer(), item)
            val restored = Json.decodeFromString(SqItem.serializer(), json)
            check(restored == item) { "A snapshot did not survive a round trip" }
        }

        context.waitTicks(SETTLE_TICKS)
    }

    /**
     * A stack shaped the way Hypixel sends one.
     *
     * The attribute names are Hypixel's, established from SkyHanni. What is being tested is
     * that the adapter finds them in the components they actually live in.
     */
    private fun hyperion(): ItemStack {
        val gems = CompoundTag().apply {
            putString("JADE_0", "PERFECT")
            putString("unlocked_slots", "JADE_0")
        }
        val enchantments = CompoundTag().apply { putInt("sharpness", 7) }
        val data = CompoundTag().apply {
            putString("id", "HYPERION")
            putString("uuid", ITEM_UUID)
            putString("modifier", "withered")
            putInt("upgrade_level", 10)
            putInt("rarity_upgrades", 1)
            putInt("edition", 42)
            put("gems", gems)
            put("enchantments", enchantments)
        }

        return ItemStack(Items.DIAMOND_SWORD).apply {
            set(DataComponents.CUSTOM_DATA, CustomData.of(data))
            set(
                DataComponents.CUSTOM_NAME,
                Component.literal("Withered Hyperion").withStyle(ChatFormatting.LIGHT_PURPLE),
            )
            set(
                DataComponents.LORE,
                ItemLore(
                    listOf(
                        Component.literal("Ability: Wither Impact").withStyle(ChatFormatting.GRAY),
                        Component.literal("MYTHIC DUNGEON SWORD")
                            .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
                    ),
                ),
            )
        }
    }

    private companion object {
        const val SETTLE_TICKS = 10
        const val ITEM_UUID = "e5f1a1c0-0000-4000-8000-000000000001"
    }
}
