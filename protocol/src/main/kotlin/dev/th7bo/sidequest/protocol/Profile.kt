package dev.th7bo.sidequest.protocol

import kotlinx.serialization.Serializable

/** A compact, display-ready view of one member on one SkyBlock profile. */
@Serializable
public data class SkyBlockProfile(
    public val username: String,
    public val uuid: String,
    public val profileId: String,
    public val profileName: String,
    /** Mojang's signed `textures` property, used by Minecraft to render the player's real head. */
    public val skinTexture: String? = null,
    /** Signature paired with [skinTexture], retained so secure-profile clients accept the texture. */
    public val skinSignature: String? = null,
    public val gameMode: String? = null,
    public val selected: Boolean = false,
    public val lastSaveMillis: Long? = null,
    public val skyBlockLevel: Double? = null,
    public val purse: Double? = null,
    public val bank: Double? = null,
    public val firstJoinMillis: Long? = null,
    public val fairySouls: Int? = null,
    public val fairyExchanges: Int? = null,
    public val cookieBuffActive: Boolean? = null,
    public val magicalPower: Int? = null,
    public val selectedPower: String? = null,
    public val profiles: List<ProfileChoice> = emptyList(),
    public val skills: List<ProfileSkill> = emptyList(),
    public val slayers: List<ProfileSlayer> = emptyList(),
    public val dungeons: List<ProfileProgress> = emptyList(),
    public val dungeonClasses: List<ProfileProgress> = emptyList(),
    public val collections: List<ProfileCollection> = emptyList(),
    public val pets: List<ProfilePet> = emptyList(),
    public val inventories: List<ProfileInventory> = emptyList(),
    public val bestiary: List<ProfileBestiaryLocation> = emptyList(),
    public val skillTrees: List<ProfileSkillTree> = emptyList(),
    public val loadouts: List<ProfileLoadout> = emptyList(),
    /** Sack contents, grouped by the sack they live in rather than as one flat pile of counts. */
    public val sacks: List<ProfileSack> = emptyList(),
    public val trophyFish: List<ProfileTrophyFish> = emptyList(),
    public val attributes: List<ProfileAttribute> = emptyList(),
    public val rift: ProfileRift? = null,
    public val experimentation: ProfileExperimentation? = null,
    public val chocolateFactory: ProfileChocolateFactory? = null,
    public val crimsonIsle: ProfileCrimsonIsle? = null,
    public val currencies: List<ProfileMetric> = emptyList(),
    public val mining: List<ProfileMetric> = emptyList(),
    public val garden: List<ProfileMetric> = emptyList(),
    public val museum: List<ProfileMetric> = emptyList(),
    public val stats: List<ProfileMetric> = emptyList(),
    /** Additional public profile categories, kept grouped so new API areas do not require a protocol redesign. */
    public val sections: List<ProfileSection> = emptyList(),
)

@Serializable
public data class ProfileChoice(
    public val id: String,
    public val name: String,
    public val gameMode: String? = null,
    public val selected: Boolean = false,
)

/** A skill whose level was calculated against Hypixel's current cumulative thresholds. */
@Serializable
public data class ProfileSkill(
    public val id: String,
    public val name: String,
    public val level: Int,
    public val maxLevel: Int,
    public val experience: Double,
    /** Progress through the current level, from zero to one. One at the cap. */
    public val progress: Double,
)

/** Progression for systems whose public API already supplies a level, or only exposes XP. */
@Serializable
public data class ProfileProgress(
    public val id: String,
    public val name: String,
    public val level: Int? = null,
    public val experience: Double? = null,
    public val details: List<ProfileMetric> = emptyList(),
)

@Serializable
public data class ProfileSlayer(
    public val id: String,
    public val name: String,
    public val experience: Double,
    public val level: Int? = null,
    public val bossKills: Int = 0,
)

@Serializable
public data class ProfileCollection(
    public val id: String,
    public val name: String,
    public val amount: Long,
    public val category: String = "Other",
)

@Serializable
public data class ProfileInventory(
    public val id: String,
    public val name: String,
    public val columns: Int = 9,
    public val slots: List<ProfileItemSlot> = emptyList(),
)

@Serializable
public data class ProfileItemSlot(
    public val slot: Int,
    public val internalName: String? = null,
    public val displayName: String? = null,
    public val count: Int = 1,
    public val lore: List<String> = emptyList(),
)

@Serializable
public data class ProfileCrimsonIsle(
    public val selectedFaction: String? = null,
    public val mageReputation: Int = 0,
    public val barbarianReputation: Int = 0,
    public val kuudra: List<ProfileKuudraTier> = emptyList(),
    public val dojo: List<ProfileDojoChallenge> = emptyList(),
)

@Serializable
public data class ProfileKuudraTier(
    public val id: String,
    public val name: String,
    public val completions: Int = 0,
    public val highestWave: Int = 0,
)

@Serializable
public data class ProfileDojoChallenge(
    public val id: String,
    public val name: String,
    public val points: Int = -1,
    public val time: Int = -1,
)

@Serializable
public data class ProfileBestiaryLocation(
    public val id: String,
    public val name: String,
    public val kills: Long,
    public val mobs: List<ProfileBestiaryMob> = emptyList(),
)

@Serializable
public data class ProfileBestiaryMob(
    public val id: String,
    public val name: String,
    public val kills: Long,
)

/**
 * One sack and what is in it.
 *
 * Hypixel reports sack contents as a single flat map of item to count, with nothing saying which sack any of
 * it came from. The grouping is added here, from NotEnoughUpdates' own sack table, because five hundred
 * counts in one alphabetical list is a dump rather than a view.
 */
@Serializable
public data class ProfileSack(
    public val id: String,
    public val name: String,
    /** The sack item itself, for drawing the group's icon. Empty for the catch-all group. */
    public val itemId: String = "",
    public val items: List<ProfileSackItem> = emptyList(),
) {
    public val total: Long get() = items.sumOf { it.amount }
}

@Serializable
public data class ProfileSackItem(
    /** The item id as Hypixel reports it, which is also what the item database is asked for. */
    public val id: String,
    public val name: String,
    public val amount: Long,
)

@Serializable
public data class ProfileSkillTree(
    public val id: String,
    public val name: String,
    public val selectedSlot: Int = 1,
    public val slots: List<ProfileSkillTreeSlot> = emptyList(),
    /** The extent of the grid the nodes sit on. Measured from the tree, not assumed: the two differ. */
    public val columns: Int = 7,
    public val rows: Int = 10,
)

@Serializable
public data class ProfileSkillTreeSlot(
    public val slot: Int,
    public val selectedAbility: String? = null,
    public val nodes: List<ProfileSkillTreeNode> = emptyList(),
)

@Serializable
public data class ProfileSkillTreeNode(
    public val id: String,
    public val name: String,
    public val level: Int,
    public val enabled: Boolean? = null,
    public val column: Int = 0,
    /** Counted from the top of the panel down, which is how the game's own menu is laid out. */
    public val row: Int = 0,
    public val maxLevel: Int = 1,
    public val kind: String = "PERK",
    /** The Minecraft item the real menu draws this node with at its current level. */
    public val itemId: String? = null,
    /** Which powder buys the next level: `MITHRIL`, `GEMSTONE`, `GLACITE`, `FOREST_WHISPERS`. */
    public val powder: String? = null,
    /** The perk's description at this level, already interpolated. Hypixel colour codes intact. */
    public val lore: List<String> = emptyList(),
)

@Serializable
public data class ProfileLoadout(
    public val id: String,
    public val name: String,
    public val equipped: Boolean = false,
    public val pet: String? = null,
    public val powerStone: String? = null,
    public val armor: List<ProfileItemSlot> = emptyList(),
    public val equipment: List<ProfileItemSlot> = emptyList(),
)

@Serializable
public data class ProfileTrophyFish(
    public val id: String,
    public val name: String,
    public val bronze: Int = 0,
    public val silver: Int = 0,
    public val gold: Int = 0,
    public val diamond: Int = 0,
) {
    public val total: Int get() = bronze + silver + gold + diamond
}

@Serializable
public data class ProfileAttribute(
    public val id: String,
    public val name: String,
    public val rarity: String,
    public val syphoned: Int = 0,
    public val owned: Int = 0,
    public val itemId: String,
)

@Serializable
public data class ProfileRift(
    public val lifetimeMotes: Long = 0,
    public val visits: Int = 0,
    public val enigmaSouls: Int = 0,
    public val foundCats: Int = 0,
    public val unlockedEyes: Int = 0,
    public val grubberStacks: Int = 0,
    public val secondsSitting: Long = 0,
    public val timecharms: List<ProfileTimecharm> = emptyList(),
)

@Serializable
public data class ProfileTimecharm(
    public val id: String,
    public val name: String,
    public val visits: Int = 0,
    public val timestamp: Long? = null,
)

@Serializable
public data class ProfileExperimentation(
    public val serumsDrank: Int = 0,
    public val resetsUsed: Int = 0,
    public val lastAttemptMillis: Long? = null,
    public val experiments: List<ProfileExperiment> = emptyList(),
)

@Serializable
public data class ProfileExperiment(
    public val id: String,
    public val name: String,
    public val attempts: Int = 0,
    public val bestScore: Int = 0,
)

@Serializable
public data class ProfileChocolateFactory(
    public val chocolate: Long = 0,
    public val totalChocolate: Long = 0,
    public val prestige: Int = 0,
    public val barnCapacity: Int = 18,
    public val rabbitsCollected: Int = 0,
    public val employees: List<ProfileRabbitEmployee> = emptyList(),
)

@Serializable
public data class ProfileRabbitEmployee(
    public val id: String,
    public val name: String,
    public val level: Int,
)

@Serializable
public data class ProfilePet(
    public val type: String,
    public val name: String,
    public val rarity: String,
    public val experience: Double,
    public val active: Boolean = false,
    public val heldItem: String? = null,
    public val skin: String? = null,
    public val candyUsed: Int = 0,
)

/** A named value from one of Hypixel's open-ended progression objects. */
@Serializable
public data class ProfileMetric(
    public val id: String,
    public val name: String,
    public val value: Double? = null,
    public val text: String? = null,
)

/** A bounded, display-ready summary of one open-ended Hypixel profile object. */
@Serializable
public data class ProfileSection(
    public val id: String,
    public val name: String,
    public val metrics: List<ProfileMetric> = emptyList(),
)
