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
