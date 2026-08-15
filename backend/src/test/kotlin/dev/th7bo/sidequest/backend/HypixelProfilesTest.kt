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
        assertEquals("Tiger", profile.pets.single().name)
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
        assertTrue(profile.sections.any { it.id == "sacks" })
        assertEquals(3, profile.trophyFish.single { it.id == "blobfish" }.bronze)
        assertEquals("Mage", profile.crimsonIsle?.selectedFaction)
        assertEquals(12, profile.crimsonIsle?.kuudra?.first { it.id == "hot" }?.completions)
        assertEquals(820, profile.crimsonIsle?.dojo?.first { it.id == "mob_kb" }?.points)
        assertEquals(
            1.0,
            profile.sections.first { it.id == "objectives" }.metrics.first { it.id == "objectives_complete" }.value,
        )
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
                "NotEnoughUpdates-REPO" in url -> UpstreamResponse(
                    200,
                    """{"catacombs":[50,75,1000]}""",
                    emptyMap(),
                )
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

        assertEquals(9, calls.values.sum())
        assertEquals(1, calls.entries.single { "skyblock/profiles" in it.key }.value)
        assertEquals(1234.0, profile.garden.first { it.id == "garden_experience" }.value)
        assertEquals(9999.0, profile.museum.first { it.id == "value" }.value)
        assertEquals("signed-skin", profile.skinTexture)
        assertEquals("skin-signature", profile.skinSignature)
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
                      "pets_data": {"pets": [{"type":"TIGER","tier":"LEGENDARY","exp":12345,"active":true}]},
                      "mining_core": {"experience": 4567, "powder_mithril": 890},
                      "player_stats": {"kills_zombie": 10, "kills_spider": 5, "deaths_void": 2},
                      "bestiary": {"milestone": {"last_claimed_milestone": 7}},
                      "trophy_fish": {"total_caught": 12, "blobfish_bronze": 3},
                      "inventory": {
                        "inv_contents": {"type": 0, "data": "opaque-nbt"},
                        "wardrobe_equipped_slot": 2,
                        "sacks_counts": {"WHEAT": 1000, "DIAMOND": 50}
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
                        "mages_reputation": 8200,
                        "barbarians_reputation": 1300,
                        "kuudra_completed_tiers": {"hot": 12, "highest_wave_hot": 5},
                        "dojo": {"dojo_points_mob_kb": 820, "dojo_time_mob_kb": 9132}
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
