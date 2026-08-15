package dev.th7bo.sidequest.gametest

import dev.th7bo.sidequest.platform.item.SkyBlockItem
import dev.th7bo.sidequest.protocol.ApiResult
import dev.th7bo.sidequest.protocol.ProfileChoice
import dev.th7bo.sidequest.protocol.ProfileCollection
import dev.th7bo.sidequest.protocol.ProfileBestiaryLocation
import dev.th7bo.sidequest.protocol.ProfileBestiaryMob
import dev.th7bo.sidequest.protocol.ProfileAttribute
import dev.th7bo.sidequest.protocol.ProfileChocolateFactory
import dev.th7bo.sidequest.protocol.ProfileCrimsonIsle
import dev.th7bo.sidequest.protocol.ProfileDojoChallenge
import dev.th7bo.sidequest.protocol.ProfileExperiment
import dev.th7bo.sidequest.protocol.ProfileExperimentation
import dev.th7bo.sidequest.protocol.ProfileInventory
import dev.th7bo.sidequest.protocol.ProfileItemSlot
import dev.th7bo.sidequest.protocol.ProfileLoadout
import dev.th7bo.sidequest.protocol.ProfileKuudraTier
import dev.th7bo.sidequest.protocol.ProfileRabbitEmployee
import dev.th7bo.sidequest.protocol.ProfileRift
import dev.th7bo.sidequest.protocol.ProfileSack
import dev.th7bo.sidequest.protocol.ProfileSackItem
import dev.th7bo.sidequest.protocol.ProfileTimecharm
import dev.th7bo.sidequest.protocol.ProfileTrophyFish
import dev.th7bo.sidequest.protocol.ProfileMetric
import dev.th7bo.sidequest.protocol.ProfilePet
import dev.th7bo.sidequest.protocol.ProfileProgress
import dev.th7bo.sidequest.protocol.ProfileSection
import dev.th7bo.sidequest.protocol.ProfileSkill
import dev.th7bo.sidequest.protocol.ProfileSlayer
import dev.th7bo.sidequest.protocol.ProfileSkillTree
import dev.th7bo.sidequest.protocol.ProfileSkillTreeNode
import dev.th7bo.sidequest.protocol.ProfileSkillTreeSlot
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
        onClient(context) { screen.selectTabForTest("Combat") }
        context.waitTicks(SETTLE_TICKS)
        onClient(context) { screen.scrollForTest(540f) }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_structured_bestiary")
        onClient(context) { screen.scrollForTest(880f) }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_structured_crimson_isle")

        onClient(context) { screen.selectTabForTest("Inventory") }
        context.waitTicks(SETTLE_TICKS * 2)
        context.takeScreenshot("profile_structured_inventory")
        onClient(context) { screen.hoverFirstInventoryItemForTest() }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_structured_inventory_lore")
        onClient(context) { screen.hoverTooltipZoneForTest(-1) }

        onClient(context) { screen.scrollForTest(620f) }
        context.waitTicks(SETTLE_TICKS * 2)
        context.takeScreenshot("profile_structured_sacks")

        onClient(context) { screen.selectTabForTest("Collections") }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_structured_collections")

        onClient(context) { screen.selectTabForTest("Pets") }
        context.waitTicks(SETTLE_TICKS * 2)
        onClient(context) { check(screen.loadedVisualCountForTest >= 3) { "Pet visuals did not resolve" } }
        context.takeScreenshot("profile_structured_pets")

        onClient(context) { screen.selectTabForTest("Mining") }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_structured_hotm")
        // Down to the tree itself. The nodes are hoverable only where they are drawn, so a tooltip
        // screenshot taken with the grid still below the viewport shows nothing and explains nothing.
        onClient(context) { screen.scrollForTest(300f) }
        context.waitTicks(SETTLE_TICKS)
        onClient(context) {
            check(screen.tooltipZoneCountForTest >= 4) {
                "the Heart of the Mountain nodes are not hoverable: ${screen.tooltipZoneCountForTest} on screen"
            }
            // A levelled node, so the tooltip has a level line and an interpolated description to show.
            screen.hoverTooltipZoneForTest(screen.tooltipZoneCountForTest - 1)
        }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_structured_hotm_perk")
        onClient(context) { screen.hoverTooltipZoneForTest(-1) }

        onClient(context) { screen.selectTabForTest("Farming") }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_structured_farming")

        onClient(context) { screen.selectTabForTest("Foraging") }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_structured_hotf")
        // The forest tree is three rows shorter than the mountain's; its bottom node must sit on the
        // bottom row of its own grid rather than floating three rows above it.
        onClient(context) { screen.scrollForTest(180f) }
        context.waitTicks(SETTLE_TICKS)
        onClient(context) { screen.hoverTooltipZoneForTest(screen.tooltipZoneCountForTest - 1) }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_structured_hotf_perk")
        onClient(context) { screen.hoverTooltipZoneForTest(-1) }

        onClient(context) { screen.scrollForTest(325f) }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_structured_attributes")

        onClient(context) { screen.selectTabForTest("Fishing") }
        context.waitTicks(SETTLE_TICKS * 2)
        context.takeScreenshot("profile_structured_fishing")

        onClient(context) { screen.selectTabForTest("Rift") }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_structured_rift")

        onClient(context) { screen.selectTabForTest("More") }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_structured_experimentation")

        onClient(context) { screen.scrollForTest(300f) }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("profile_structured_easter")

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
            ProfileCollection("WHEAT", "Wheat", 42_000_000, "Farming"),
            ProfileCollection("SEEDS", "Seeds", 26_000_000, "Farming"),
            ProfileCollection("DIAMOND", "Diamond", 18_500_000, "Mining"),
            ProfileCollection("ENDER_STONE", "End Stone", 9_800_000, "Mining"),
            ProfileCollection("BLAZE_ROD", "Blaze Rod", 3_200_000, "Combat"),
            ProfileCollection("SULPHUR", "Gunpowder", 8_200_000, "Combat"),
            ProfileCollection("LILY_PAD", "Lily Pad", 820_000, "Fishing"),
            ProfileCollection("SPONGE", "Sponge", 620_000, "Fishing"),
            ProfileCollection("MOONFLOWER", "Moonflower", 420_000, "Foraging"),
            ProfileCollection("LOG", "Oak Wood", 22_000_000, "Foraging"),
            ProfileCollection("LOG:1", "Spruce Wood", 16_000_000, "Foraging"),
            ProfileCollection("LOG:2", "Birch Wood", 12_000_000, "Foraging"),
            ProfileCollection("LOG:3", "Jungle Wood", 9_000_000, "Foraging"),
        ),
        pets = listOf(
            // Mythic, which the database has no entry for: it resolves only by walking down to legendary.
            ProfilePet(
                "GOLDEN_DRAGON", "Golden Dragon", "MYTHIC", 210_000_000.0, active = true,
                heldItem = "PET_ITEM_TIER_BOOST", heldItemName = "§6Tier Boost", candyUsed = 10,
                level = 142, maxLevel = 200, progress = .38, tierBoosted = true,
            ),
            ProfilePet(
                "ENDER_DRAGON", "Ender Dragon", "MYTHIC", 125_000_000.0,
                heldItem = "PET_ITEM_LUCKY_CLOVER", heldItemName = "§9Lucky Clover",
                level = 100, maxLevel = 100, progress = 1.0,
            ),
            ProfilePet("TIGER", "Tiger", "LEGENDARY", 35_000_000.0, level = 91, progress = .62, skin = "TIGER_SABERTOOTH"),
        ),
        inventories = listOf(
            ProfileInventory("inv_contents", "Inventory", 9, listOf(
                ProfileItemSlot(0, "ASPECT_OF_THE_END", "Aspect of the End"),
                ProfileItemSlot(1, "REVENANT_FLESH", "§5Revenant Flesh", 64, listOf("§7Right-click to view recipes!", "§9RARE")),
                ProfileItemSlot(2, "NULL_SPHERE", "Null Sphere", 32),
                ProfileItemSlot(8, "WOLF_TOOTH", "Wolf Tooth", 12),
                ProfileItemSlot(27, "DERELICT_ASHE", "Derelict Ashe", 4),
                // A pet, already resolved out of its `petInfo` by the backend. Every pet stack calls itself
                // `PET`, which the database has never heard of, and drew the missing-item barrier.
                ProfileItemSlot(28, "TIGER;4", "§6[Lvl 100] §6Tiger", 1, listOf("§7Legendary pet")),
            )),
            ProfileInventory("inv_armor", "Armor", 4, listOf(
                ProfileItemSlot(0, "WOLF_TOOTH", "Helmet"), ProfileItemSlot(1, "NULL_SPHERE", "Chestplate"),
                ProfileItemSlot(2, "REVENANT_FLESH", "Leggings"), ProfileItemSlot(3, "DERELICT_ASHE", "Boots"),
            )),
        ),
        bestiary = listOf(
            ProfileBestiaryLocation("the_end", "The End", 1_240_000, listOf(
                ProfileBestiaryMob("zealot", "Zealot", 820_000), ProfileBestiaryMob("enderman", "Enderman", 420_000),
            )),
            ProfileBestiaryLocation("crimson_isle", "Crimson Isle", 284_000, listOf(
                ProfileBestiaryMob("blaze", "Blaze", 190_000), ProfileBestiaryMob("magma_cube", "Magma Cube", 94_000),
            )),
        ),
        // Rows run top-down, as the game's own menus do: Mining Speed is the bottom of a ten-row tree and
        // Sweep the bottom of a seven-row one. The two trees are deliberately different heights here,
        // because drawing the shorter on the taller's grid is the mistake this shape exists to catch.
        skillTrees = listOf(
            ProfileSkillTree("mining", "Heart of the Mountain", 2, listOf(ProfileSkillTreeSlot(2, "Mining Speed Boost", listOf(
                ProfileSkillTreeNode("mining_speed", "Mining Speed", 50, column = 3, row = 9, maxLevel = 50,
                    itemId = "minecraft:diamond", powder = "MITHRIL", lore = listOf("§7Grants §a+§a1000 §6⸕ Mining Speed§7.")),
                ProfileSkillTreeNode("mining_speed_boost", "Mining Speed Boost", 1, column = 1, row = 8, kind = "ABILITY",
                    itemId = "minecraft:emerald_block", powder = "MITHRIL",
                    lore = listOf("§6Pickaxe Ability: Mining Speed Boost", "§7Grants §a+§a200% §7mining speed for §a10s§7.", "§8Cooldown: §a120s")),
                ProfileSkillTreeNode("mining_fortune", "Mining Fortune", 50, column = 3, row = 8, maxLevel = 50,
                    itemId = "minecraft:diamond", powder = "MITHRIL", lore = listOf("§7Grants §a+§a100 §6☘ Mining Fortune§7.")),
                ProfileSkillTreeNode("professional", "Professional", 140, column = 2, row = 6, maxLevel = 140,
                    itemId = "minecraft:diamond", powder = "GEMSTONE", lore = listOf("§7Gain §a+§a700 §6⸕ Mining Speed §7when mining gemstones.")),
                ProfileSkillTreeNode("core_of_the_mountain", "Core of the Mountain", 7, column = 3, row = 5, maxLevel = 10, kind = "CORE",
                    itemId = "minecraft:redstone_block", powder = "GEMSTONE",
                    lore = listOf("§7§8+§c1 Pickaxe Ability Level", "§7§8+§a1 Forge Slot", "§7§8+§a1 Commission Slot")),
                ProfileSkillTreeNode("great_explorer", "Great Explorer", 20, column = 5, row = 4, maxLevel = 20,
                    itemId = "minecraft:diamond", powder = "GEMSTONE"),
                ProfileSkillTreeNode("strong_arm", "Strong Arm", 0, column = 2, row = 2, maxLevel = 100,
                    itemId = "minecraft:coal", powder = "GLACITE", lore = listOf("§7Grants §a+§a2 §6⸕ Mining Speed§7.")),
                ProfileSkillTreeNode("warm_heart", "Warm Heart", 0, column = 4, row = 2, maxLevel = 50, itemId = "minecraft:coal"),
                ProfileSkillTreeNode("rags_to_riches", "Rags to Riches", 0, column = 3, row = 1, maxLevel = 50, itemId = "minecraft:coal"),
                ProfileSkillTreeNode("mining_master", "Mining Master", 0, column = 3, row = 0, maxLevel = 10, itemId = "minecraft:coal"),
            ))), columns = 7, rows = 10),
            ProfileSkillTree("foraging", "Heart of the Forest", 1, listOf(ProfileSkillTreeSlot(1, "Tree Whisper", listOf(
                ProfileSkillTreeNode("sweep", "Sweep", 34, column = 3, row = 6, maxLevel = 50,
                    itemId = "minecraft:stripped_oak_log", powder = "FOREST_WHISPERS", lore = listOf("§7Grants §a+34 §2∮ Sweep§7.")),
                ProfileSkillTreeNode("damage_boost", "Damage Boost", 1, column = 1, row = 5, kind = "ABILITY",
                    itemId = "minecraft:oak_sapling", powder = "FOREST_WHISPERS", lore = listOf("§6Axe Ability: Damage Boost")),
                ProfileSkillTreeNode("foraging_fortune", "Foraging Fortune", 20, column = 3, row = 5, maxLevel = 50,
                    itemId = "minecraft:stripped_oak_log", powder = "FOREST_WHISPERS", lore = listOf("§7Grants §a+40 §6☘ Foraging Fortune§7.")),
                ProfileSkillTreeNode("efficient_forager", "Efficient Forager", 12, column = 3, row = 3, maxLevel = 100,
                    itemId = "minecraft:stripped_oak_log", powder = "FOREST_WHISPERS"),
                ProfileSkillTreeNode("center_of_the_forest", "Center of the Forest", 4, column = 3, row = 2, maxLevel = 5, kind = "CORE",
                    itemId = "minecraft:oak_wood", powder = "FOREST_WHISPERS", lore = listOf("§7§8+§a1 Forest Slot")),
                ProfileSkillTreeNode("forest_knowledge", "Forest Knowledge", 0, column = 1, row = 1, maxLevel = 50, itemId = "minecraft:pale_oak_button"),
                ProfileSkillTreeNode("seasoned_forager", "Seasoned Forager", 0, column = 5, row = 1, maxLevel = 50, itemId = "minecraft:pale_oak_button"),
                ProfileSkillTreeNode("heartwood", "Heartwood", 0, column = 3, row = 0, maxLevel = 20, itemId = "minecraft:pale_oak_button"),
            ))), columns = 7, rows = 7),
        ),
        sacks = listOf(
            ProfileSack("agronomy", "Agronomy", "LARGE_AGRONOMY_SACK", listOf(
                ProfileSackItem("WHEAT", "Wheat", 640_000), ProfileSackItem("INK_SACK:3", "Cocoa Beans", 128_000),
                ProfileSackItem("SEEDS", "Seeds", 96_000), ProfileSackItem("CACTUS", "Cactus", 18_400),
            )),
            ProfileSack("mining", "Mining", "LARGE_MINING_SACK", listOf(
                ProfileSackItem("DIAMOND", "Diamond", 412_000), ProfileSackItem("COAL", "Coal", 240_000),
                ProfileSackItem("REDSTONE", "Redstone", 88_000),
            )),
            ProfileSack("other", "Other", items = listOf(ProfileSackItem("SOMETHING_NEW", "Something New", 12))),
        ),
        trophyFish = listOf(
            ProfileTrophyFish("blobfish", "Blobfish", 320, 112, 34, 8),
            ProfileTrophyFish("sulphur_skitter", "Sulphur Skitter", 240, 84, 18, 2),
            ProfileTrophyFish("vanille", "Vanille", 98, 32, 8, 1),
        ),
        // The item ids are the ones the database actually files shards under — the ability, not the shard.
        attributes = listOf(
            ProfileAttribute("grove", "Grove", "COMMON", 9, 2, "ATTRIBUTE_SHARD_NATURE_ELEMENTAL;1"),
            ProfileAttribute("mist", "Mist", "UNCOMMON", 7, 4, "ATTRIBUTE_SHARD_FOG_ELEMENTAL;1"),
            ProfileAttribute("flash", "Flash", "LEGENDARY", 5, 8, "ATTRIBUTE_SHARD_LIGHT_ELEMENTAL;1"),
        ),
        rift = ProfileRift(4_200_000, 184, 46, 7, 5, 4, 8_420, listOf(
            ProfileTimecharm("wyldly_supreme", "Supreme Timecharm", 12), ProfileTimecharm("citizen", "SkyBlock Citizen Timecharm", 48),
            ProfileTimecharm("slime", "Globulate Timecharm", 92),
        )),
        experimentation = ProfileExperimentation(3, 12, 1_776_000_000_000, listOf(
            ProfileExperiment("superpairs", "Superpairs", 812, 19), ProfileExperiment("ultrasequencer", "Ultrasequencer", 640, 28),
            ProfileExperiment("chronomatron", "Chronomatron", 728, 25),
        )),
        chocolateFactory = ProfileChocolateFactory(42_000_000, 8_200_000_000, 6, 68, 384, listOf(
            ProfileRabbitEmployee("rabbit_bro", "Rabbit Bro", 218), ProfileRabbitEmployee("rabbit_cousin", "Rabbit Cousin", 184),
            ProfileRabbitEmployee("rabbit_grandma", "Rabbit Grandma", 142),
        )),
        crimsonIsle = ProfileCrimsonIsle(
            selectedFaction = "Mage",
            mageReputation = 12_840,
            barbarianReputation = 3_420,
            kuudra = listOf(
                ProfileKuudraTier("none", "Basic", 184, 6), ProfileKuudraTier("hot", "Hot", 142, 6),
                ProfileKuudraTier("burning", "Burning", 98, 6), ProfileKuudraTier("fiery", "Fiery", 42, 6),
                ProfileKuudraTier("infernal", "Infernal", 17, 5),
            ),
            dojo = listOf(
                ProfileDojoChallenge("mob_kb", "Force", 1_042), ProfileDojoChallenge("wall_jump", "Stamina", 884),
                ProfileDojoChallenge("archer", "Mastery", 761), ProfileDojoChallenge("snake", "Swiftness", 628),
                ProfileDojoChallenge("sword_swap", "Discipline", 512), ProfileDojoChallenge("fireball", "Tenacity", 389),
                ProfileDojoChallenge("lock_head", "Control", -1),
            ),
        ),
        loadouts = listOf(ProfileLoadout("1", "Mining", true, powerStone = "Scorching"), ProfileLoadout("2", "Dungeons")),
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
            ProfileSection("farming_summary", "Farming progress", listOf(ProfileMetric("gold_medals", "Gold medals", 82.0), ProfileMetric("contests", "Recorded contests", 2_104.0))),
            ProfileSection("foraging_summary", "Foraging progress", listOf(ProfileMetric("daily_trees", "Trees cut today", 148.0), ProfileMetric("tree_gifts", "Tree gifts today", 12.0))),
            ProfileSection("trophy_fish", "Trophy fishing", listOf(ProfileMetric("total_caught", "Caught", 3_842.0))),
            ProfileSection("rift", "The Rift", listOf(ProfileMetric("lifetime_motes", "Lifetime motes", 4_200_000.0))),
        ),
    )

    /**
     * The item database, standing in.
     *
     * **It has to be able to miss.** The real one files a pet once per rarity it is *obtained* at, so
     * `GOLDEN_DRAGON;5` and `ENDER_DRAGON;5` genuinely do not exist and a mythic pet only resolves because
     * the lookup walks down to `;4`. A stub that answered every key would draw three correct pets while the
     * game drew three Steve heads, which is the bug this fixture is here to catch.
     */
    private fun visualItem(key: String, skin: String?): SkyBlockItem? {
        if (key in MISSING_FROM_DATABASE) return null
        val vanilla = when (key) {
            "SULPHUR", "GUNPOWDER" -> "minecraft:gunpowder"
            "MOONFLOWER" -> "minecraft:spore_blossom"
            "WATER_LILY", "LILY_PAD" -> "minecraft:lily_pad"
            "SEEDS" -> "minecraft:grass_block"
            "SPONGE" -> "minecraft:sponge"
            "LOG" -> "minecraft:oak_log"
            "LOG:1" -> "minecraft:spruce_log"
            "LOG:2" -> "minecraft:birch_log"
            "LOG:3" -> "minecraft:jungle_log"
            "LOG_2" -> "minecraft:acacia_log"
            "LOG_2:1" -> "minecraft:dark_oak_log"
            "BLOBFISH_BRONZE", "BLOBFISH" -> "minecraft:pufferfish"
            "SULPHUR_SKITTER_BRONZE", "SULPHUR_SKITTER" -> "minecraft:cod"
            "VANILLE_BRONZE", "VANILLE" -> "minecraft:salmon"
            "SHARD_FOREST_FORTUNE", "SHARD_SWEEP", "SHARD_HUNTER_FORTUNE" -> "minecraft:amethyst_shard"
            "REVENANT_FLESH" -> "minecraft:rotten_flesh"
            "TARANTULA_WEB", "NULL_SPHERE" -> "minecraft:paper"
            "WOLF_TOOTH" -> "minecraft:bone"
            "DERELICT_ASHE" -> "minecraft:blaze_powder"
            "GOLDEN_DRAGON;4", "ENDER_DRAGON;4" -> "minecraft:dragon_head"
            "ATTRIBUTE_SHARD_NATURE_ELEMENTAL;1" -> "minecraft:emerald"
            "ATTRIBUTE_SHARD_FOG_ELEMENTAL;1" -> "minecraft:prismarine_crystals"
            "ATTRIBUTE_SHARD_LIGHT_ELEMENTAL;1" -> "minecraft:glowstone_dust"
            "LARGE_AGRONOMY_SACK", "LARGE_MINING_SACK" -> "minecraft:bundle"
            "WHEAT" -> "minecraft:wheat"
            "INK_SACK-3" -> "minecraft:cocoa_beans"
            "CACTUS" -> "minecraft:cactus"
            "DIAMOND" -> "minecraft:diamond"
            "COAL" -> "minecraft:coal"
            "REDSTONE" -> "minecraft:redstone"
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

        /**
         * Keys the real database answers 404 to, verified against it.
         *
         * A Golden Dragon exists only as `;4` and an Ender Dragon as `;3` and `;4`, so both of the mythic
         * pets in the fixture must fall down the ladder to be drawn at all. `INK_SACK:3` is the other
         * shape of the same problem: the colon Hypixel uses cannot be a filename, so the entry is stored
         * with a dash and only the second spelling hits.
         */
        val MISSING_FROM_DATABASE = setOf("GOLDEN_DRAGON;5", "ENDER_DRAGON;5", "SOMETHING_NEW", "INK_SACK:3")
    }
}
