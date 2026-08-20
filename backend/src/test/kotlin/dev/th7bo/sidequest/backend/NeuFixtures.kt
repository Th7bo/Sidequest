package dev.th7bo.sidequest.backend

/**
 * Slices of NotEnoughUpdates' constants, copied verbatim.
 *
 * Shared between the reader's own tests and the profile parser's, because the two are testing the same
 * claim from opposite ends and a second, subtly different copy of the same JSON would be a way for them to
 * disagree. Nothing here is shaped to make a test pass: it is what the live files contain, trimmed.
 */
internal object NeuFixtures {

    /**
     * A slice of `mining/hotm.json`, copied verbatim.
     *
     * Seven nodes and the two furniture columns, chosen to cover every shape the file has: a plain perk, one
     * whose reward clamps, an ability that scales with the tree's core, an unlevelable one that scales with
     * the tree's level, the core with its per-level rewards, and the tier label and spacer that have to be
     * dropped. Nothing is reworded — a fixture written to be easy would prove nothing about the real file.
     */
    val HOTM_TREE = """
        [
          {
            "type": "PERK",
            "id": "quick_forge",
            "location": [
              5,
              2
            ],
            "max_level": 20,
            "cost": {
              "type": "POWDER",
              "kind": "MITHRIL"
            },
            "cost_formula": "floor((level + 1)^3.2)",
            "reward_formula": "min(30,10 + (level * 0.5) + (floor(level/20)*10))",
            "name": "Quick Forge",
            "tooltip": [
              "<gray>Decreases the time it takes to forge by <green>%reward%%<gray>."
            ]
          },
          {
            "type": "PERK",
            "id": "mining_speed",
            "location": [
              3,
              0
            ],
            "max_level": 50,
            "cost": {
              "type": "POWDER",
              "kind": "MITHRIL"
            },
            "cost_formula": "floor((level + 1)^3)",
            "reward_formula": "level * 20",
            "name": "Mining Speed",
            "tooltip": [
              "<gray>Grants <gold>+%reward%⸕ Mining Speed<gray>."
            ]
          },
          {
            "type": "UNLEVELABLE",
            "id": "mineshaft_mayhem",
            "name": "Mineshaft Mayhem",
            "location": [
              6,
              7
            ],
            "tooltip": [
              "<gray>Every time you enter a <aqua>Glacite",
              "<aqua>Mineshaft<gray>, <gray>you receive a random buff.",
              "",
              "<gray>Possible Buffs",
              "<dark_gray> ■ <green>+5% <gray>chance to find a <blue>Suspicious Scrap<gray>.",
              "<dark_gray> ■ <gray>Gain <gold>+100 ☘ Mining Fortune<gray>.",
              "<dark_gray> ■ <gray>Gain <gold>+200 ⸕ Mining Speed<gray>.",
              "<dark_gray> ■ <gray>Gain <aqua>+10❄ Cold Resistance<gray>.",
              "<dark_gray> ■ <gray>Reduce Pickaxe Ability cooldowns by <green>-25%<gray>"
            ]
          },
          {
            "type": "ABILITY",
            "id": "mining_speed_boost",
            "name": "Mining Speed Boost",
            "location": [
              1,
              1
            ],
            "reward_formula": {
              "effect": "200 + effectiveLevel * 50",
              "duration": "10 + effectiveLevel * 5"
            },
            "tooltip": [
              "<gold>Pickaxe Ability: Mining Speed Boost <yellow><bold>RIGHT CLICK",
              "<gray>Grants <green>+%effect%%<gold> ⸕ Mining Speed <gray>for <green>%duration%s<gray>.",
              "<dark_gray>Cooldown: <green>120s"
            ]
          },
          {
            "type": "PERK",
            "id": "mining_fortune",
            "location": [
              3,
              1
            ],
            "max_level": 50,
            "cost": {
              "type": "POWDER",
              "kind": "MITHRIL"
            },
            "cost_formula": "floor((level + 1)^3.05)",
            "reward_formula": "level * 2",
            "name": "Mining Fortune",
            "tooltip": [
              "<gray>Grants <gold>+%reward%☘ Mining Fortune<gray>."
            ]
          },
          {
            "type": "CORE",
            "id": "core_of_the_mountain",
            "name": "Core of the Mountain",
            "location": [
              3,
              4
            ],
            "level": [
              {
                "reward": "<dark_gray>+<purple>1 Token of the Mountain"
              },
              {
                "include": [
                  1
                ],
                "cost": {
                  "type": "POWDER",
                  "kind": "MITHRIL",
                  "amount": 50000
                },
                "reward": "<dark_gray>+<red>1 Pickaxe Ability Level"
              },
              {
                "include": [
                  1,
                  2
                ],
                "cost": {
                  "type": "POWDER",
                  "kind": "MITHRIL",
                  "amount": 100000
                },
                "reward": "<green>+1 Commission Slot"
              },
              {
                "include": [
                  1,
                  2,
                  3
                ],
                "cost": {
                  "type": "POWDER",
                  "kind": "GEMSTONE",
                  "amount": 200000
                },
                "reward": [
                  "<dark_gray>+<dark_green>1 Base Mithril Powder <gray>when mining",
                  "<dark_green>Mithril"
                ]
              },
              {
                "include": [
                  2,
                  3,
                  4
                ],
                "cost": {
                  "type": "POWDER",
                  "kind": "GEMSTONE",
                  "amount": 300000
                },
                "reward": "<dark_gray>+<dark_purple>2 Token of the Mountain"
              },
              {
                "include": [
                  2,
                  3,
                  4,
                  5
                ],
                "cost": {
                  "type": "POWDER",
                  "kind": "GEMSTONE",
                  "amount": 400000
                },
                "reward": [
                  "<dark_gray>+<light_purple>2 Base Gemstone Powder <gray>when",
                  "<gray>mining <light_purple>Gemstones"
                ]
              },
              {
                "include": [
                  2,
                  3,
                  4,
                  6
                ],
                "cost": {
                  "type": "POWDER",
                  "kind": "GEMSTONE",
                  "amount": 500000
                },
                "reward": "<dark_gray>+<dark_purple>3 Token of the Mountain"
              },
              {
                "include": [
                  2,
                  3,
                  4,
                  6,
                  7
                ],
                "cost": {
                  "type": "POWDER",
                  "kind": "GLACITE",
                  "amount": 750000
                },
                "reward": [
                  "<dark_gray>+<aqua>3 Base Glacite Powder <gray>when mining",
                  "<gray>Glacite"
                ]
              },
              {
                "include": [
                  2,
                  3,
                  4,
                  6,
                  7,
                  8
                ],
                "cost": {
                  "type": "POWDER",
                  "kind": "GLACITE",
                  "amount": 1000000
                },
                "reward": [
                  "<dark_gray>+<green>10% chance <gray>for <aqua>Glacite Mineshafts",
                  "<gray>to spawn"
                ]
              },
              {
                "include": [
                  2,
                  3,
                  4,
                  6,
                  8,
                  9
                ],
                "cost": {
                  "type": "POWDER",
                  "kind": "GLACITE",
                  "amount": 1250000
                },
                "reward": "<dark_gray>+<dark_purple>5 Token of the Mountain"
              }
            ]
          },
          {
            "type": "UNLEVELABLE",
            "id": "daily_powder",
            "name": "Daily Powder",
            "location": [
              5,
              4
            ],
            "reward_formula": "hotmLevel * 500",
            "tooltip": [
              "<gray>The first ore you mine each day",
              "<gray>grants <blue>+500 Powder<gray>, multiplied by",
              "<gray>your <purple>HOTM <gray>level.",
              "",
              "<dark_green>Mithril<gray>: <green>%reward% <dark_green>Mithril Powder",
              "<light_purple>Gemstone<gray>: <green>%reward% <light_purple>Gemstone Powder",
              "<aqua>Glacite<gray>: <green>%reward% <dark_green>Glacite Powder"
            ]
          },
          {
            "type": "TIER",
            "location": [
              -2,
              9
            ],
            "name": "Tier 10",
            "rewards": [
              "<dark_gray>+<purple>2 Token of the Mountain",
              "<dark_gray>+<gold>New Forgeable Items"
            ]
          },
          {
            "type": "TIER",
            "location": [
              -2,
              6
            ],
            "name": "Tier 7",
            "rewards": [
              "<dark_gray>+<purple>2 Token of the Mountain",
              "<dark_gray>+<green>1 Forge Slot",
              "<dark_gray>+<gold>New Forgeable Items"
            ]
          }
        ]
    """.trimIndent()

    /**
     * `constants/pets.json`, with the ladder cut short.
     *
     * The offsets, the Golden Dragon's two hundred levels and the Bingo pet's flat offsets are verbatim, and
     * so are the rungs — these are the real first twenty of `pet_levels`. Only the *length* is trimmed, from
     * a hundred and nineteen down to twenty, because a test that counts to a hundred proves nothing extra.
     * Twenty is not arbitrary: it is where a legendary pet starts, so it is the shortest ladder on which
     * rarity still visibly matters.
     */
    val PETS = """
        {
          "pet_rarity_offset": {"COMMON": 0, "UNCOMMON": 6, "RARE": 11, "EPIC": 16, "LEGENDARY": 20, "MYTHIC": 20},
          "pet_levels": [100, 110, 120, 130, 145, 160, 175, 190, 210, 230,
                         250, 275, 300, 330, 360, 400, 440, 490, 540, 600],
          "custom_pet_leveling": {
            "GOLDEN_DRAGON": {"type": 1, "pet_levels": [1886700, 1886700, 1886700], "max_level": 200},
            "BINGO": {"rarity_offset": {"COMMON": 0, "UNCOMMON": 0, "RARE": 0, "EPIC": 0, "LEGENDARY": 0}}
          },
          "pet_types": {"TIGER": "COMBAT"},
          "id_to_display_name": {"TYRANNOSAURUS": "T-Rex"},
          "pet_item_display_name_to_id": {
            "§6Tier Boost": "PET_ITEM_TIER_BOOST",
            "§5Quick Claw": "PET_ITEM_QUICK_CLAW",
            "§9Lucky Clover": "PET_ITEM_LUCKY_CLOVER"
          }
        }
    """.trimIndent()

    /** Two sacks from `constants/sacks.json`, contents trimmed. */
    val SACKS = """
        {
          "sacks": {
            "Agronomy": {
              "item": "LARGE_AGRONOMY_SACK",
              "contents": ["BROWN_MUSHROOM", "CACTUS", "INK_SACK-2", "INK_SACK-3", "CARROT_ITEM", "WHEAT", "SEEDS"]
            },
            "Enchanted Agronomy": {
              "item": "LARGE_ENCHANTED_AGRONOMY_SACK",
              "contents": ["ENCHANTED_SEEDS", "ENCHANTED_WHEAT", "ENCHANTED_CACTUS"]
            },
            "Mining": {
              "item": "LARGE_MINING_SACK",
              "contents": ["COAL", "DIAMOND", "IRON_INGOT"]
            }
          }
        }
    """.trimIndent()

    /** Three entries from `constants/attribute_shards.json`, verbatim. */
    val SHARDS = """
        {
          "attribute_levelling": {"COMMON": [1, 3, 5]},
          "unconsumable_attributes": ["SHARD_CHAMELEON"],
          "attributes": [
            {
              "bazaarName": "SHARD_GROVE", "displayName": "Grove", "rarity": "COMMON",
              "internalName": "ATTRIBUTE_SHARD_NATURE_ELEMENTAL;1", "abilityName": "Nature Elemental",
              "alignment": "Forest", "family": ["Elemental"], "shardId": "C1"
            },
            {
              "bazaarName": "SHARD_MIST", "displayName": "Mist", "rarity": "COMMON",
              "internalName": "ATTRIBUTE_SHARD_FOG_ELEMENTAL;1", "abilityName": "Fog Elemental",
              "alignment": "Water", "family": ["Elemental"], "shardId": "C2"
            },
            {
              "bazaarName": "SHARD_FLASH", "displayName": "Flash", "rarity": "LEGENDARY",
              "internalName": "ATTRIBUTE_SHARD_LIGHT_ELEMENTAL;1", "abilityName": "Light Elemental",
              "alignment": "Combat", "family": ["Elemental"], "shardId": "C3"
            }
          ]
        }
    """.trimIndent()
    }
