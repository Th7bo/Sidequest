package dev.th7bo.sidequest.backend

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class HypixelProfilesTest {

    @Test
    fun `saved live response remains parseable`() {
        val fixture = System.getenv("SIDEQUEST_LIVE_PROFILE_FIXTURE")
        val uuid = System.getenv("SIDEQUEST_LIVE_PROFILE_UUID")
        assumeTrue(!fixture.isNullOrBlank() && !uuid.isNullOrBlank(), "live fixture not supplied")

        val profile = HypixelProfileParser.parse(
            PlayerIdentity(uuid.orEmpty(), "LiveFixture"),
            null,
            Files.readString(Path.of(fixture.orEmpty())),
            emptyMap(),
        )

        assertTrue(profile.currencies.isNotEmpty())
        assertTrue(profile.sections.isNotEmpty())
    }

    @Test
    fun `official cumulative thresholds produce level and progress`() {
        val definitions = HypixelProfileParser.parseSkillDefinitions(SKILLS)
        val profile = HypixelProfileParser.parse(
            PlayerIdentity(UUID, "Alice"),
            null,
            PROFILES,
            definitions,
            listOf(50.0, 125.0, 1_125.0),
            HypixelProfileParser.parseCollectionDefinitions(COLLECTIONS),
        )

        assertEquals("Apple", profile.profileName)
        assertEquals(25.0, profile.skyBlockLevel)
        assertEquals(1234.0, profile.purse)
        assertEquals(9876.0, profile.bank)
        val farming = profile.skills.single()
        assertEquals(2, farming.level)
        assertEquals(0.5, farming.progress)
        assertEquals(300.0, profile.slayers.single().experience)
        assertEquals(2, profile.slayers.single().level)
        assertEquals(7, profile.slayers.single().bossKills)
        assertEquals(5000.0, profile.dungeons.single().experience)
        assertEquals(3, profile.dungeons.single().level)
        assertEquals("Mage", profile.dungeonClasses.single().name)
        assertEquals(2, profile.collections.size)
        assertEquals("Farming", profile.collections.first { it.id == "WHEAT" }.category)
        assertEquals("Tiger", profile.pets.first().name, "the active pet leads")
        assertEquals(120, profile.fairySouls)
        assertEquals(734, profile.magicalPower)
        assertEquals("SILKY", profile.selectedPower)
        assertEquals(2, profile.profiles.size)
        assertTrue(profile.mining.isNotEmpty())
        assertTrue(profile.currencies.any { it.name == "Mithril" })
        assertEquals(
            84.0,
            profile.currencies.first { it.name == "Wither Current" }.value,
        )
        assertTrue(profile.bestiary.isEmpty())
        assertTrue(profile.inventories.isEmpty())
        // Sacks are their own structure now, not one of the generic open-ended sections. With no table to
        // group by they all land in "Other", which is the point: a missing table hides nothing.
        assertTrue(profile.sections.none { it.id == "sacks" })
        assertEquals(1753L, profile.sacks.single { it.id == "other" }.total)
        assertEquals(3, profile.trophyFish.single { it.id == "blobfish" }.bronze)
        assertEquals("Mage", profile.crimsonIsle?.selectedFaction)
        assertEquals(12, profile.crimsonIsle?.kuudra?.first { it.id == "hot" }?.completions)
        assertEquals(820, profile.crimsonIsle?.dojo?.first { it.id == "mob_kb" }?.points)
        assertEquals(
            1.0,
            profile.sections.first { it.id == "objectives" }.metrics.first { it.id == "objectives_complete" }.value,
        )
    }

    // -- the NotEnoughUpdates tables, applied to a profile ---------------------

    private fun parseWithTables(): dev.th7bo.sidequest.protocol.SkyBlockProfile = HypixelProfileParser.parse(
        PlayerIdentity(UUID, "Alice"),
        null,
        PROFILES,
        HypixelProfileParser.parseSkillDefinitions(SKILLS),
        neu = NeuTables(
            sacks = NeuConstants.parseSacks(NeuFixtures.SACKS),
            shards = NeuConstants.parseShards(NeuFixtures.SHARDS),
            mining = NeuConstants.parseTreeLayout(NeuFixtures.HOTM, "hotm"),
        ),
    )

    /**
     * Sacks arrive as one flat map and leave grouped by the sack they belong to.
     *
     * The Fishing entry is the one that matters: Hypixel spells a variant item `INK_SACK:3` and the table
     * spells the same thing `INK_SACK-3`, so a comparison that does not fold the two drops it into "Other"
     * and the grouping quietly stops being a grouping.
     */
    @Test
    fun `sack counts are grouped by the sack they came from`() {
        val profile = parseWithTables()

        val farming = profile.sacks.single { it.name == "Agronomy" }
        assertEquals("LARGE_AGRONOMY_SACK", farming.itemId)
        assertEquals(listOf("WHEAT", "INK_SACK:3"), farming.items.map { it.id })
        assertEquals(1000L, farming.items.first().amount)

        assertEquals(listOf("DIAMOND"), profile.sacks.single { it.name == "Mining" }.items.map { it.id })
    }

    /** An item the table does not place still appears, rather than vanishing from the player's own profile. */
    @Test
    fun `an unplaced item lands in Other rather than disappearing`() {
        val other = parseWithTables().sacks.single { it.id == "other" }

        assertEquals(listOf("SOMETHING_NEW"), other.items.map { it.id })
    }

    /**
     * Hypixel's node ids are not NotEnoughUpdates' perk keys, for eleven of the forty-six.
     *
     * Quick Forge arrives as `forge_time`. Reading it under its layout key finds nothing and reports a
     * maxed perk as untaken, which looks like the player never bought it.
     */
    @Test
    fun `Heart of the Mountain levels are read under Hypixel's own node ids`() {
        val tree = parseWithTables().skillTrees.single { it.id == "mining" }
        val nodes = tree.slots.first { it.slot == 1 }.nodes.associateBy { it.id }

        assertEquals(20, nodes.getValue("quick_forge").level, "read from forge_time")
        assertEquals(7, nodes.getValue("core_of_the_mountain").level, "read from special_0")
        assertEquals(30, nodes.getValue("mining_fortune").level, "an id that does not differ still works")
        assertEquals(7, tree.columns)
        assertEquals(10, tree.rows)
    }

    /** The tooltip is the layout's own formula at the level the player has, not a stored string. */
    @Test
    fun `a perk carries its description at the level the player has it`() {
        val nodes = parseWithTables().skillTrees.single { it.id == "mining" }
            .slots.first { it.slot == 1 }.nodes.associateBy { it.id }

        val fortune = nodes.getValue("mining_fortune")
        assertEquals(listOf("§7Grants §a+§a60 §6☘ Mining Fortune§7."), fortune.lore)
        assertEquals("MITHRIL", fortune.powder)
        assertEquals("minecraft:emerald", fortune.itemId)
        assertEquals(50, fortune.maxLevel)
    }

    /** Peak of the Mountain is `special_0`, and the pickaxe abilities scale against it. */
    @Test
    fun `Peak of the Mountain changes what an ability reports`() {
        val infusion = parseWithTables().skillTrees.single { it.id == "mining" }
            .slots.first { it.slot == 1 }.nodes.single { it.id == "gemstone_infusion" }

        assertEquals(listOf("§6Pickaxe Ability: Gemstone Infusion", "§8Duration: §a25s"), infusion.lore)
    }

    /**
     * A field Hypixel sends as null must arrive as null, not as the word.
     *
     * **`JsonNull` is a `JsonPrimitive`, and its content is the four-character string `"null"`.** So every
     * absent optional came back as text reading "null" and every caller took it for a real value. The
     * damage was worst where a value is used as an identity: a pet with no skin claimed a skin of "null",
     * all forty in a stable agreed on it, and the screen's icon cache — keyed by skin where there is one —
     * collapsed into a single entry holding whichever pet was resolved first. Every pet then wore the
     * active pet's face, while its name and rarity stayed correct, because only the icon was shared.
     */
    @Test
    fun `an explicitly null field is absent rather than the word null`() {
        val pets = parseWithTables().pets

        assertEquals(null, pets.first { it.type == "TIGER" }.skin, "a null skin is no skin")
        assertEquals(null, pets.first { it.type == "TIGER" }.heldItem)
        assertEquals("WOLF_DOGE", pets.first { it.type == "WOLF" }.skin, "a real skin still arrives")
        assertTrue(pets.none { it.skin == "null" }, "the word leaked through: $pets")
    }

    /** Whatever a screen keys a pet's picture on, no two of these pets may agree on it. */
    @Test
    fun `no two pets share an identity`() {
        val pets = parseWithTables().pets
        val identities = pets.map { listOf(it.type, it.rarity, it.skin.orEmpty()) }

        assertEquals(pets.size, identities.distinct().size, "two pets are indistinguishable: $identities")
    }

    /**
     * A count Hypixel wrote with a decimal point is still a count.
     *
     * SkyBlock stores most of a profile as doubles, so `2850.0` and `2850` both turn up for a value that is
     * conceptually an integer — and `"2850.0".toIntOrNull()` is null, which every call site turns into a
     * default of zero. A player with real Crimson Isle reputation read as having none.
     */
    @Test
    fun `whole numbers written as decimals are still read`() {
        val crimson = requireNotNull(parseWithTables().crimsonIsle)

        assertEquals(8200, crimson.mageReputation, "written as 8200.0 by Hypixel")
        assertEquals(12, crimson.kuudra.single { it.id == "hot" }.completions)
        assertEquals(820, crimson.dojo.single { it.id == "mob_kb" }.points)
    }

    /** Siding with one faction drives the other's standing below zero; that is a fact, not an error. */
    @Test
    fun `a negative reputation is reported rather than clamped`() {
        assertEquals(-1300, requireNotNull(parseWithTables().crimsonIsle).barbarianReputation)
    }

    /**
     * A shard resolves to the item it is actually filed under, not to `SHARD_<name>`.
     *
     * That derived id does not exist in the item database for any shard in the game, which is why the whole
     * tab was drawing the same fallback amethyst.
     */
    @Test
    fun `an attribute shard names a real item`() {
        val attributes = parseWithTables().attributes

        val grove = attributes.single { it.name == "Grove" }
        assertEquals("ATTRIBUTE_SHARD_NATURE_ELEMENTAL;1", grove.itemId)
        assertEquals("COMMON", grove.rarity)
        assertEquals(4, grove.syphoned)
        assertEquals(12, grove.owned)
        assertTrue(attributes.none { it.itemId.startsWith("SHARD_") }, "nothing fell back: $attributes")
    }

    @Test
    fun `named profile is matched without case sensitivity`() {
        val profile = HypixelProfileParser.parse(
            PlayerIdentity(UUID, "Alice"),
            "BANANA",
            PROFILES,
            HypixelProfileParser.parseSkillDefinitions(SKILLS),
        )
        assertEquals("Banana", profile.profileName)
        assertTrue(!profile.selected)
    }

    @Test
    fun `unknown named profile is not silently replaced by selected`() {
        assertThrows(ProfileLookupFailure.NotFound::class.java) {
            HypixelProfileParser.parse(
                PlayerIdentity(UUID, "Alice"),
                "Pear",
                PROFILES,
                HypixelProfileParser.parseSkillDefinitions(SKILLS),
            )
        }
    }

    @Test
    fun `server cache shares one upstream lookup across clients`() = runTest {
        val calls = mutableMapOf<String, Int>()
        val upstream = ProfileUpstream { url, headers ->
            calls[url] = calls.getOrDefault(url, 0) + 1
            when {
                "lookup/name" in url -> UpstreamResponse(200, """{"id":"$UUID","name":"Alice"}""", emptyMap())
                "session/minecraft/profile" in url -> UpstreamResponse(
                    200,
                    """{"properties":[{"name":"textures","value":"signed-skin","signature":"skin-signature"}]}""",
                    emptyMap(),
                )
                "resources/skyblock/skills" in url -> UpstreamResponse(200, SKILLS, emptyMap())
                "resources/skyblock/collections" in url -> UpstreamResponse(200, COLLECTIONS, emptyMap())
                "resources/skyblock/items" in url -> UpstreamResponse(200, ITEMS, emptyMap())
                "constants/leveling.json" in url -> UpstreamResponse(200, """{"catacombs":[50,75,1000]}""", emptyMap())
                "constants/sacks.json" in url -> UpstreamResponse(200, NeuFixtures.SACKS, emptyMap())
                "constants/attribute_shards.json" in url -> UpstreamResponse(200, NeuFixtures.SHARDS, emptyMap())
                "constants/hotmlayout.json" in url -> UpstreamResponse(200, NeuFixtures.HOTM, emptyMap())
                "constants/hotflayout.json" in url -> UpstreamResponse(200, NeuFixtures.HOTF, emptyMap())
                "skyblock/garden" in url -> UpstreamResponse(
                    200,
                    """{"success":true,"garden":{"garden_experience":1234,"unlocked_plots_ids":[1,2]}}""",
                    emptyMap(),
                )
                "skyblock/museum" in url -> UpstreamResponse(
                    200,
                    """{"success":true,"profile":{"value":9999,"appraisal":true,"items":{"A":{}}}}""",
                    emptyMap(),
                )
                "skyblock/profiles" in url -> {
                    assertEquals("secret", headers["API-Key"])
                    UpstreamResponse(200, PROFILES, emptyMap())
                }
                else -> error("unexpected $url")
            }
        }
        val service = HypixelProfileService("secret", now = { 1_000L }, upstream = upstream)

        val profile = service.lookup("Alice", null)
        service.lookup("alice", null)

        assertEquals(13, calls.values.sum())
        assertEquals(1, calls.entries.single { "skyblock/profiles" in it.key }.value)
        // The description tables are the same for everybody and for every profile, so a second lookup — and
        // by extension a fifth friend opening the same screen — must not fetch a single one of them again.
        val constants = calls.entries.filter { "constants/" in it.key }
        assertEquals(5, constants.size, "one request per table: $constants")
        assertTrue(constants.all { it.value == 1 }, "a table was fetched twice: $constants")
        assertEquals(1234.0, profile.garden.first { it.id == "garden_experience" }.value)
        assertEquals(9999.0, profile.museum.first { it.id == "value" }.value)
        assertEquals("signed-skin", profile.skinTexture)
        assertEquals("skin-signature", profile.skinSignature)
        // The tables actually reached the parse, rather than being fetched and dropped.
        assertEquals("LARGE_AGRONOMY_SACK", profile.sacks.single { it.name == "Agronomy" }.itemId)
        assertEquals(
            "ATTRIBUTE_SHARD_NATURE_ELEMENTAL;1",
            profile.attributes.single { it.name == "Grove" }.itemId,
        )
    }

    private companion object {
        const val UUID = "0123456789abcdef0123456789abcdef"
        val SKILLS = """
            {
              "success": true,
              "skills": {
                "FARMING": {
                  "name": "Farming",
                  "maxLevel": 3,
                  "levels": [
                    {"level": 1, "totalExpRequired": 50.0},
                    {"level": 2, "totalExpRequired": 175.0},
                    {"level": 3, "totalExpRequired": 375.0}
                  ]
                }
              }
            }
        """.trimIndent()
        val COLLECTIONS = """
            {"success":true,"collections":{
              "FARMING":{"name":"Farming","items":{"WHEAT":{"name":"Wheat"}}},
              "MINING":{"name":"Mining","items":{"DIAMOND":{"name":"Diamond"}}}
            }}
        """.trimIndent()
        val ITEMS = """{"success":true,"items":[{"id":"SHARD_TEST","name":"Test Shard","tier":"RARE"}]}"""
        val PROFILES = """
            {
              "success": true,
              "profiles": [
                {
                  "profile_id": "apple-id",
                  "cute_name": "Apple",
                  "selected": true,
                  "banking": {"balance": 9876.0},
                  "members": {
                    "$UUID": {
                      "last_save": 123456,
                      "leveling": {"experience": 2500},
                      "profile": {"first_join": 1700000000000, "cookie_buff_active": true},
                      "fairy_soul": {"total_collected": 120, "fairy_exchanges": 15},
                      "accessory_bag_storage": {"highest_magical_power": 734, "selected_power": "SILKY"},
                      "currencies": {
                        "coin_purse": 1234.0,
                        "essence": {
                          "mithril": 42,
                          "wither": {"current": 84, "lifetime": 126}
                        }
                      },
                      "player_data": {"experience": {"SKILL_FARMING": 275.0}},
                      "collection": {"WHEAT": 10000, "DIAMOND": 5000},
                      "pets_data": {"pets": [
                        {"type":"TIGER","tier":"LEGENDARY","exp":12345,"active":true,"skin":null,"heldItem":null},
                        {"type":"HEDGEHOG","tier":"EPIC","exp":500,"skin":null},
                        {"type":"WOLF","tier":"LEGENDARY","exp":100,"skin":"WOLF_DOGE"}
                      ]},
                      "mining_core": {
                        "experience": 4567,
                        "powder_mithril": 890,
                        "nodes": {
                          "mining_fortune": 30,
                          "forge_time": 20,
                          "special_0": 7,
                          "gemstone_infusion": 1,
                          "toggle_gemstone_infusion": true
                        }
                      },
                      "attributes": {"stacks": {"shard_grove": 4}},
                      "shards": {"owned": [{"type": "SHARD_GROVE", "amount_owned": 12}]},
                      "player_stats": {"kills_zombie": 10, "kills_spider": 5, "deaths_void": 2},
                      "bestiary": {"milestone": {"last_claimed_milestone": 7}},
                      "trophy_fish": {"total_caught": 12, "blobfish_bronze": 3},
                      "inventory": {
                        "inv_contents": {"type": 0, "data": "opaque-nbt"},
                        "wardrobe_equipped_slot": 2,
                        "sacks_counts": {
                          "WHEAT": 1000,
                          "DIAMOND": 50,
                          "INK_SACK:3": 700,
                          "SOMETHING_NEW": 3,
                          "COAL": 0
                        }
                      },
                      "objectives": {
                        "talk_to_guide": {"status": "COMPLETE", "completed_at": 123},
                        "reach_hub": {"status": "ACTIVE", "progress": 0.5}
                      },
                      "slayer": {"slayer_bosses": {"zombie": {
                        "xp": 300,
                        "claimed_levels": {"level_1": true, "level_2": true},
                        "boss_kills_tier_0": 5,
                        "boss_kills_tier_1": 2
                      }}},
                      "dungeons": {
                        "dungeon_types": {"catacombs": {"experience": 5000, "tier_completions": {"1": 8}}},
                        "player_classes": {"mage": {"experience": 2500}}
                      },
                      "nether_island_player_data": {
                        "selected_faction": "mages",
                        "mages_reputation": 8200.0,
                        "barbarians_reputation": -1300.0,
                        "kuudra_completed_tiers": {"hot": 12.0, "highest_wave_hot": 5},
                        "dojo": {"dojo_points_mob_kb": 820.0, "dojo_time_mob_kb": 9132}
                      }
                    }
                  }
                },
                {
                  "profile_id": "banana-id",
                  "cute_name": "Banana",
                  "selected": false,
                  "members": {"$UUID": {}}
                }
              ]
            }
        """.trimIndent()
    }
}
