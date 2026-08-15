package dev.th7bo.sidequest.gametest

import dev.th7bo.sidequest.platform.item.SkyBlockItem
import dev.th7bo.sidequest.protocol.ApiResult
import dev.th7bo.sidequest.protocol.ProfileChoice
import dev.th7bo.sidequest.protocol.ProfileCollection
import dev.th7bo.sidequest.protocol.ProfileMetric
import dev.th7bo.sidequest.protocol.ProfilePet
import dev.th7bo.sidequest.protocol.ProfileProgress
import dev.th7bo.sidequest.protocol.ProfileSection
import dev.th7bo.sidequest.protocol.ProfileSkill
import dev.th7bo.sidequest.protocol.ProfileSlayer
import dev.th7bo.sidequest.protocol.SkyBlockProfile
import dev.th7bo.sidequest.ui.minecraft.screen.ProfileScreen
import dev.th7bo.sidequest.ui.minecraft.rendering.MinecraftItemStacks
import dev.th7bo.sidequest.ui.rendering.ItemRef
import dev.th7bo.sidequest.ui.theme.DarkTheme
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.world.item.Items

/** Captures the native profile viewer in the real item-rendering pipeline. */
class ProfileScreenRenderTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { runInWorld(context) }
    }

    private fun runInWorld(context: ClientGameTestContext) {
        context.waitTicks(SETTLE_TICKS)
        onClient(context) { client ->
            client.options.guiScale().set(2)
            client.resizeGui()
        }
        context.waitTicks(SETTLE_TICKS)
        onClient(context) {
            check(MinecraftItemStacks.stackFor(ItemRef("minecraft:diamond_sword")) != null) {
                "A vanilla item could not be constructed from the active client registry"
            }
            val semanticFallback = MinecraftItemStacks.stackFor(
                ItemRef(
                    "minecraft:paper",
                    model = "sidequest:missing_profile_test_model",
                    fallbackId = "minecraft:cobweb",
                ),
            )
            check(semanticFallback?.`is`(Items.COBWEB) == true) {
                "A missing SkyBlock model rendered its carrier item instead of the semantic fallback"
            }
        }

        val screen = onClientCompute(context) { client ->
            val texture = client.gameProfile.properties.get("textures").firstOrNull()
            val profile = fixture(texture?.value(), texture?.signature())
            ProfileScreen(
                username = profile.username,
                profile = null,
                theme = DarkTheme,
                parent = null,
                fetch = { _, _ -> ApiResult.Success(profile) },
                resolveItem = { key -> visualItem(key, texture?.value()) },
            )
        }

        context.setScreen { screen }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_overhaul_overview")

        onClient(context) { screen.selectTabForTest("Combat") }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_overhaul_combat")

        onClient(context) { screen.selectTabForTest("Pets") }
        context.waitTicks(SETTLE_TICKS * 2)
        onClient(context) { check(screen.loadedVisualCountForTest >= 3) { "Pet visuals did not resolve" } }
        context.takeScreenshot("profile_overhaul_pets")

        onClient(context) { client ->
            client.options.guiScale().set(3)
            client.resizeGui()
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_overhaul_compact")

        onClient(context) { client ->
            client.options.guiScale().set(1)
            client.resizeGui()
            screen.selectTabForTest("Overview")
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_overhaul_wide")

        context.setScreen { null }
        context.waitTicks(SETTLE_TICKS)
    }

    private fun fixture(skin: String?, signature: String?): SkyBlockProfile = SkyBlockProfile(
        username = "chrooted",
        uuid = "0123456789abcdef0123456789abcdef",
        profileId = "grapes-id",
        profileName = "Grapes",
        skinTexture = skin,
        skinSignature = signature,
        gameMode = "ironman",
        selected = true,
        skyBlockLevel = 312.2,
        purse = 139_477_304.0,
        bank = 4_120_000_000.0,
        firstJoinMillis = 1_603_238_400_000,
        fairySouls = 279,
        magicalPower = 810,
        selectedPower = "silky",
        cookieBuffActive = true,
        profiles = listOf(
            ProfileChoice("grapes-id", "Grapes", "ironman", true),
            ProfileChoice("mango-id", "Mango"),
            ProfileChoice("pear-id", "Pear", "stranded"),
        ),
        skills = listOf(
            ProfileSkill("FARMING", "Farming", 60, 60, 120_000_000.0, 1.0),
            ProfileSkill("MINING", "Mining", 55, 60, 74_000_000.0, .72),
            ProfileSkill("COMBAT", "Combat", 53, 60, 61_000_000.0, .38),
            ProfileSkill("FORAGING", "Foraging", 42, 60, 16_000_000.0, .41),
            ProfileSkill("FISHING", "Fishing", 30, 50, 5_200_000.0, .83),
            ProfileSkill("ENCHANTING", "Enchanting", 60, 60, 98_000_000.0, 1.0),
        ),
        slayers = listOf(
            ProfileSlayer("zombie", "Revenant Horror", 1_500_000.0, 9, 1_280),
            ProfileSlayer("spider", "Tarantula Broodfather", 800_000.0, 8, 640),
            ProfileSlayer("wolf", "Sven Packmaster", 1_100_000.0, 9, 932),
            ProfileSlayer("enderman", "Voidgloom Seraph", 420_000.0, 7, 188),
            ProfileSlayer("blaze", "Inferno Demonlord", 95_000.0, 5, 42),
        ),
        dungeons = listOf(ProfileProgress("catacombs", "Catacombs", 48, 782_000_000.0)),
        dungeonClasses = listOf(
            ProfileProgress("mage", "Mage", 46, 640_000_000.0),
            ProfileProgress("archer", "Archer", 41, 310_000_000.0),
        ),
        collections = listOf(
            ProfileCollection("WHEAT", "Wheat", 42_000_000),
            ProfileCollection("DIAMOND", "Diamond", 18_500_000),
            ProfileCollection("ENDER_STONE", "End Stone", 9_800_000),
            ProfileCollection("BLAZE_ROD", "Blaze Rod", 3_200_000),
        ),
        pets = listOf(
            ProfilePet("GOLDEN_DRAGON", "Golden Dragon", "LEGENDARY", 210_000_000.0, true, "PET_ITEM_TIER_BOOST"),
            ProfilePet("ENDER_DRAGON", "Ender Dragon", "MYTHIC", 125_000_000.0, heldItem = "PET_ITEM_LUCKY_CLOVER"),
            ProfilePet("TIGER", "Tiger", "LEGENDARY", 35_000_000.0),
        ),
        currencies = listOf(
            ProfileMetric("essence_wither_current", "Wither essence", 18_420.0),
            ProfileMetric("motes", "Motes", 942_000.0),
        ),
        mining = listOf(
            ProfileMetric("powder_mithril", "Mithril powder", 8_200_000.0),
            ProfileMetric("powder_gemstone", "Gemstone powder", 12_700_000.0),
            ProfileMetric("tokens_spent", "HOTM tokens", 26.0),
        ),
        garden = listOf(
            ProfileMetric("garden_experience", "Garden XP", 19_500_000.0),
            ProfileMetric("visitors_served", "Visitors served", 1_284.0),
        ),
        museum = listOf(ProfileMetric("value", "Museum value", 2_850_000_000.0)),
        stats = listOf(ProfileMetric("kills", "Kills", 191_200.0), ProfileMetric("deaths", "Deaths", 994.0)),
        sections = listOf(
            ProfileSection("bestiary", "Bestiary", listOf(ProfileMetric("milestone", "Milestone", 114.0))),
            ProfileSection("nether_island_player_data", "Crimson Isle", listOf(ProfileMetric("kuudra_tier", "Kuudra tier", 5.0))),
            ProfileSection("inventory", "Inventories", listOf(ProfileMetric("inv_contents", "Inventory", text = "Available"))),
            ProfileSection("sacks", "Sacks", listOf(ProfileMetric("enchanted_diamond", "Enchanted diamond", 12_400.0))),
            ProfileSection("trophy_fish", "Trophy fishing", listOf(ProfileMetric("total_caught", "Caught", 3_842.0))),
            ProfileSection("rift", "The Rift", listOf(ProfileMetric("lifetime_motes", "Lifetime motes", 4_200_000.0))),
        ),
    )

    private fun visualItem(key: String, skin: String?): SkyBlockItem {
        val vanilla = when (key) {
            "REVENANT_FLESH" -> "minecraft:rotten_flesh"
            "TARANTULA_WEB", "NULL_SPHERE" -> "minecraft:paper"
            "WOLF_TOOTH" -> "minecraft:bone"
            "DERELICT_ASHE" -> "minecraft:blaze_powder"
            else -> "minecraft:player_head"
        }
        val model = when (key) {
            "TARANTULA_WEB" -> "hypixel_skyblock:item/slayer/spider/tarantula_web"
            "NULL_SPHERE" -> "hypixel_skyblock:item/slayer/enderman/null_sphere"
            else -> null
        }
        return SkyBlockItem(
            key,
            key,
            vanilla,
            skullTexture = skin.takeIf { vanilla == "minecraft:player_head" },
            modelId = model,
        )
    }

    private fun onClient(context: ClientGameTestContext, action: (net.minecraft.client.Minecraft) -> Unit) {
        context.runOnClient<RuntimeException> { client -> action(client) }
    }

    private fun <T> onClientCompute(context: ClientGameTestContext, action: (net.minecraft.client.Minecraft) -> T): T =
        context.computeOnClient<T, RuntimeException> { client -> action(client) }

    private companion object {
        const val SETTLE_TICKS = 10
    }
}
