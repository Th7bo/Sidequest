package dev.th7bo.sidequest.backend

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HypixelProfilesTest {

    @Test
    fun `official cumulative thresholds produce level and progress`() {
        val definitions = HypixelProfileParser.parseSkillDefinitions(SKILLS)
        val profile = HypixelProfileParser.parse(
            PlayerIdentity(UUID, "Alice"),
            null,
            PROFILES,
            definitions,
        )

        assertEquals("Apple", profile.profileName)
        assertEquals(25.0, profile.skyBlockLevel)
        assertEquals(1234.0, profile.purse)
        assertEquals(9876.0, profile.bank)
        val farming = profile.skills.single()
        assertEquals(2, farming.level)
        assertEquals(0.5, farming.progress)
        assertEquals(300.0, profile.slayers.single().experience)
        assertEquals(5000.0, profile.dungeons.single().experience)
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
                "resources/skyblock/skills" in url -> UpstreamResponse(200, SKILLS, emptyMap())
                "skyblock/profiles" in url -> {
                    assertEquals("secret", headers["API-Key"])
                    UpstreamResponse(200, PROFILES, emptyMap())
                }
                else -> error("unexpected $url")
            }
        }
        val service = HypixelProfileService("secret", now = { 1_000L }, upstream = upstream)

        service.lookup("Alice", null)
        service.lookup("alice", null)

        assertEquals(3, calls.values.sum())
        assertEquals(1, calls.entries.single { "skyblock/profiles" in it.key }.value)
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
                      "currencies": {"coin_purse": 1234.0},
                      "player_data": {"experience": {"SKILL_FARMING": 275.0}},
                      "slayer": {"slayer_bosses": {"zombie": {"xp": 300}}},
                      "dungeons": {"dungeon_types": {"catacombs": {"experience": 5000}}}
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
