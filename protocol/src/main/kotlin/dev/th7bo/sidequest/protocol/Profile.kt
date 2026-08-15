package dev.th7bo.sidequest.protocol

import kotlinx.serialization.Serializable

/** A compact, display-ready view of one member on one SkyBlock profile. */
@Serializable
public data class SkyBlockProfile(
    public val username: String,
    public val uuid: String,
    public val profileId: String,
    public val profileName: String,
    public val gameMode: String? = null,
    public val selected: Boolean = false,
    public val lastSaveMillis: Long? = null,
    public val skyBlockLevel: Double? = null,
    public val purse: Double? = null,
    public val bank: Double? = null,
    public val skills: List<ProfileSkill> = emptyList(),
    public val slayers: List<ProfileProgress> = emptyList(),
    public val dungeons: List<ProfileProgress> = emptyList(),
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
)
