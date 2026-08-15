package dev.th7bo.sidequest.backend

/**
 * Slices of NotEnoughUpdates' constants, copied verbatim.
 *
 * Shared between the reader's own tests and the profile parser's, because the two are testing the same
 * claim from opposite ends and a second, subtly different copy of the same JSON would be a way for them to
 * disagree. Nothing here is shaped to make a test pass: it is what the live files contain, trimmed.
 */
internal object NeuFixtures {

    /** Verbatim from `constants/hotmlayout.json`. Both layout files ship the same prelude. */
    val PRELUDE = listOf(
        "(defun min (l r) (if (lt l r) l r))",
        "(defun max (l r) (if (lt l r) r l))",
        "(defun round-decimals (number places) (/ (round (* number (pow 10 places))) (pow 10 places)))",
        "(defun id (x) (if true x x))",
        "(defun npi (level0 maxLevel) (if (= level0 0) :COAL (if (= level0 maxLevel) :DIAMOND :EMERALD)))",
        "(defun api (level0) (if (= level0 0) :COAL_BLOCK :EMERALD_BLOCK))",
    )

    /** Two real perks: one plain formula, one with a level-gated ladder of lore. */
    val HOTM = """
        {
          "prelude": [
            "(defun min (l r) (if (lt l r) l r))",
            "(defun max (l r) (if (lt l r) r l))",
            "(defun round-decimals (number places) (/ (round (* number (pow 10 places))) (pow 10 places)))",
            "(defun id (x) (if true x x))",
            "(defun npi (level0 maxLevel) (if (= level0 0) :COAL (if (= level0 maxLevel) :DIAMOND :EMERALD)))",
            "(defun api (level0) (if (= level0 0) :COAL_BLOCK :EMERALD_BLOCK))"
          ],
          "hotm": {
            "powders": {"MITHRIL": {"costLine": "§7Cost: §2{cost} Mithril Powder"}},
            "perks": {
              "mining_speed": {
                "name": "Mining Speed", "x": 3, "y": 9, "maxLevel": 50, "powder": "MITHRIL",
                "item": "(npi level0 maxLevel)", "cost": "(pow (+ level 2) 3)", "stat": "(* level 20)",
                "lore": ["§7Grants §a+§a{stat} §6⸕ Mining Speed§7."]
              },
              "mining_fortune": {
                "name": "Mining Fortune", "x": 3, "y": 8, "maxLevel": 50, "powder": "MITHRIL",
                "item": "(npi level0 maxLevel)", "cost": "(pow (+ level 2) 3.05)", "stat": "(* level 2)",
                "lore": ["§7Grants §a+§a{stat} §6☘ Mining Fortune§7."]
              },
              "quick_forge": {
                "name": "Quick Forge", "x": 5, "y": 7, "maxLevel": 20, "powder": "MITHRIL",
                "item": "(npi level0 maxLevel)", "cost": "(pow (+ level 2) 3.2)",
                "stat": "(if (lt level 20) (round-decimals (+ 10 (* level 0.5)) 1) 30)",
                "lore": ["§7Decreases forge time by §a{stat}%§7."]
              },
              "mineshaft_mayhem": {
                "name": "Mineshaft Mayhem", "x": 6, "y": 2, "maxLevel": 1, "powder": "GLACITE",
                "item": "(api level0)", "cost": "0", "lore": ["§7Increases §bMineshaft §7spawn chance."]
              },
              "gemstone_infusion": {
                "name": "Gemstone Infusion", "x": 0, "y": 0, "maxLevel": 1, "powder": "GLACITE",
                "item": "(api level0)", "cost": "0", "statDuration": "(if (lt potm 2) \"20\" \"25\")",
                "lore": ["§6Pickaxe Ability: Gemstone Infusion", "§8Duration: §a{statDuration}s"]
              },
              "core_of_the_mountain": {
                "name": "Core of the Mountain", "x": 3, "y": 5, "maxLevel": 10,
                "powder": "(if (lt level 4) MITHRIL (if (lt level 8) GEMSTONE GLACITE))",
                "item": ":REDSTONE_BLOCK",
                "cost": "(list.at (list.new 0 50000 100000 200000 300000 400000 600000 750000 1000000 1250000 0) level)",
                "lore": [
                  {"text": "§7§8+§c1 Pickaxe Ability Level", "onlyIf": "(gt level 0)"},
                  {"text": "§7§8+§a1 Forge Slot", "onlyIf": "(gt level 1)"},
                  {"text": "§7§8+§a1 Commission Slot", "onlyIf": "(gt level 2)"},
                  {"text": "§7§8+§21 Base Mithril Powder", "onlyIf": "(gt level 3)"},
                  {"text": "§8+§51 Token of the Mountain", "onlyIf": "(gt level 4)"},
                  {"text": "§7§8+§d2 Base Gemstone Powder", "onlyIf": "(gt level 5)"},
                  {"text": "§8+§51 Token of the Mountain", "onlyIf": "(gt level 6)"},
                  {"text": "§7§8+§b3 Base Glacite Powder", "onlyIf": "(gt level 7)"},
                  {"text": "§7§8+§a10% chance §7for §bGlacite Mineshafts§7.", "onlyIf": "(gt level 8)"},
                  {"text": "§8+§52 Token of the Mountain", "onlyIf": "(gt level 9)"}
                ]
              }
            }
          }
        }
    """.trimIndent()

    /** The corners of the Heart of the Forest grid, which is three rows shorter than the mountain's. */
    val HOTF = """
        {
          "prelude": ["(defun id (x) (if true x x))"],
          "hotf": {
            "powders": {"FOREST_WHISPERS": {"costLine": "§7Cost: §b{cost} Forest Whispers"}},
            "perks": {
              "half_empty": {
                "name": "Half Empty", "x": 1, "y": 0, "maxLevel": 25, "powder": "FOREST_WHISPERS",
                "item": ":OAK_LOG", "cost": "", "statSweep": "(id level)",
                "lore": ["§7Gain §a{statSweep} §2∮ Sweep§7."]
              },
              "sweep": {
                "name": "Sweep", "x": 3, "y": 6, "maxLevel": 50, "powder": "FOREST_WHISPERS",
                "item": ":OAK_LOG", "cost": "", "stat": "(id level)",
                "lore": ["§7Grants §a+{stat} §2∮ Sweep§7."]
              },
              "maniac_slicer": {
                "name": "Maniac Slicer", "x": 6, "y": 2, "maxLevel": 1, "powder": "FOREST_WHISPERS",
                "item": ":OAK_SAPLING", "cost": "0", "lore": ["§6Axe Ability: Maniac Slicer"]
              }
            }
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
