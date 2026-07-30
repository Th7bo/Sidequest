package dev.th7bo.sidequest.platform.skyblock

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A SkyBlock island.
 *
 * The list and the [apiName] values come from SkyHanni's `IslandType`, which has years of
 * corrections in it that a fresh list would spend the same years rediscovering.
 *
 * An enum rather than a string because features branch on it constantly, and a typo in a
 * string comparison fails silently — the feature simply never triggers, which is the
 * hardest kind of bug to notice.
 *
 * [apiName] is Hypixel's own identifier and is what gets persisted and sent to the
 * backend. Never the constant name and never the ordinal: both break the moment the enum
 * is renamed or reordered, and this one *will* grow.
 */
@Serializable(with = IslandSerializer::class)
public enum class Island(
    public val displayName: String,
    /** Hypixel's identifier, where one exists. Null for client-side distinctions. */
    public val apiName: String?,
    /** Stable id for persistence. Hypixel's where there is one, ours otherwise. */
    public val serializedId: String,
) {
    // -- general ------------------------------------------------------------
    PRIVATE_ISLAND("Private Island", "dynamic", "private_island"),
    PRIVATE_ISLAND_GUEST("Private Island Guest", null, "private_island_guest"),
    HUB("Hub", "hub", "hub"),
    DARK_AUCTION("Dark Auction", "dark_auction", "dark_auction"),
    WINTER("Jerry's Workshop", "winter", "winter"),

    // -- farming ------------------------------------------------------------
    THE_FARMING_ISLANDS("The Farming Islands", "farming_1", "farming_1"),
    GARDEN("Garden", "garden", "garden"),
    GARDEN_GUEST("Garden Guest", null, "garden_guest"),

    // -- mining -------------------------------------------------------------
    GOLD_MINES("Gold Mine", "mining_1", "mining_1"),
    DEEP_CAVERNS("Deep Caverns", "mining_2", "mining_2"),
    DWARVEN_MINES("Dwarven Mines", "mining_3", "mining_3"),
    CRYSTAL_HOLLOWS("Crystal Hollows", "crystal_hollows", "crystal_hollows"),
    MINESHAFT("Mineshaft", "mineshaft", "mineshaft"),

    // -- fishing ------------------------------------------------------------
    BACKWATER_BAYOU("Backwater Bayou", "fishing_1", "fishing_1"),
    LOTUS_ATOLL("Lotus Atoll", "lotus_atoll", "lotus_atoll"),

    // -- foraging -----------------------------------------------------------
    THE_PARK("The Park", "foraging_1", "foraging_1"),
    GALATEA("Galatea", "foraging_2", "foraging_2"),
    TORRHUS_CANYON("Torrhus Canyon", "foraging_3", "foraging_3"),

    // -- combat -------------------------------------------------------------
    SPIDER_DEN("Spider's Den", "combat_1", "combat_1"),
    THE_END("The End", "combat_3", "combat_3"),
    CRIMSON_ISLE("Crimson Isle", "crimson_isle", "crimson_isle"),

    // -- dungeons -----------------------------------------------------------
    DUNGEON_HUB("Dungeon Hub", "dungeon_hub", "dungeon_hub"),
    CATACOMBS("Catacombs", "dungeon", "dungeon"),
    KUUDRA_ARENA("Kuudra", "kuudra", "kuudra"),

    // -- special ------------------------------------------------------------
    THE_RIFT("The Rift", "rift", "rift"),
    SAFARI("Safari", "safari", "safari"),

    /** Not in SkyBlock at all — a normal Minecraft world, or another Hypixel game. */
    NONE("", null, "none"),

    /**
     * Somewhere the mod does not recognise.
     *
     * Deliberately reachable. A new island, an event map or a wording change degrades to
     * "I do not know where I am" rather than to a wrong answer. Every feature reading
     * this treats unknown as "do nothing", and a *wrong* island is the one outcome that
     * makes a feature act incorrectly rather than not at all.
     */
    UNKNOWN("???", null, "unknown"),
    ;

    /** False for [NONE] and [UNKNOWN], which are answers about not knowing. */
    public val isRealIsland: Boolean get() = this != NONE && this != UNKNOWN

    /** The guest version of this island, or itself where there is none. */
    public val guestVariant: Island
        get() = when (this) {
            PRIVATE_ISLAND -> PRIVATE_ISLAND_GUEST
            GARDEN -> GARDEN_GUEST
            else -> this
        }

    /** True on your own island or garden, or a visit to someone else's. */
    public val isPersonalIsland: Boolean get() = this in PERSONAL

    /**
     * Whether interrupting the player here is rude.
     *
     * Read by the cinematic and notification policies: a full-screen animation during a
     * Kuudra run costs someone the run. Kept as a property of the island rather than a
     * check scattered through features, so there is one place to be wrong.
     */
    public val isHazardous: Boolean get() = this in HAZARDOUS

    /**
     * Whether the player is busy enough that anything non-urgent should wait.
     *
     * Broader than [isHazardous] — an auction or a mineshaft run is not dangerous, but it
     * is not a moment to celebrate a skill level-up over either.
     */
    public val isBusy: Boolean get() = isHazardous || this in BUSY

    public companion object {
        private val BY_SERIALIZED_ID: Map<String, Island> = entries.associateBy { it.serializedId }
        private val BY_API_NAME: Map<String, Island> =
            entries.mapNotNull { island -> island.apiName?.let { it to island } }.toMap()
        private val BY_DISPLAY_NAME: Map<String, Island> =
            entries.filter { it.displayName.isNotEmpty() }.associateBy { it.displayName }

        private val PERSONAL = setOf(PRIVATE_ISLAND, PRIVATE_ISLAND_GUEST, GARDEN, GARDEN_GUEST)
        private val HAZARDOUS = setOf(CATACOMBS, KUUDRA_ARENA)
        private val BUSY = setOf(DARK_AUCTION, MINESHAFT, THE_RIFT, NONE, UNKNOWN)

        /** Resolves a persisted id. Unrecognised ids become [UNKNOWN] rather than throwing. */
        public fun ofSerializedId(id: String): Island = BY_SERIALIZED_ID[id] ?: UNKNOWN

        /** Resolves Hypixel's identifier, e.g. `mining_3`. */
        public fun ofApiName(apiName: String): Island = BY_API_NAME[apiName] ?: UNKNOWN

        /**
         * Resolves the name shown in game, e.g. `Dwarven Mines`.
         *
         * The path the tab-list parser takes. Unknown names become [UNKNOWN], because
         * Hypixel adding an island should make features stand down, not misbehave.
         */
        public fun ofDisplayName(name: String): Island = BY_DISPLAY_NAME[name.trim()] ?: UNKNOWN
    }
}

/** Serializes by [Island.serializedId], so the enum can be renamed and reordered freely. */
internal object IslandSerializer : KSerializer<Island> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.th7bo.sidequest.platform.skyblock.Island", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Island) {
        encoder.encodeString(value.serializedId)
    }

    override fun deserialize(decoder: Decoder): Island = Island.ofSerializedId(decoder.decodeString())
}

/**
 * A named area within an island.
 *
 * A string, not an enum: there are hundreds, Hypixel adds them constantly, and no feature
 * needs to enumerate them — they compare against the one or two they care about. An enum
 * here would be maintenance with no payoff.
 */
@Serializable
@JvmInline
public value class SubLocation(public val name: String) {
    public val isKnown: Boolean get() = name.isNotEmpty()

    override fun toString(): String = name

    public companion object {
        public val Unknown: SubLocation = SubLocation("")
    }
}

/**
 * A Hypixel server instance, e.g. `mini123A`.
 *
 * The sharpest signal of a lobby change there is: the island can stay the same across a
 * hop, and several things — party state, mob timers, anything cached about who is
 * nearby — are only valid within one instance.
 */
@Serializable
@JvmInline
public value class ServerId(public val value: String) {
    public val isKnown: Boolean get() = value.isNotEmpty()

    override fun toString(): String = value

    public companion object {
        public val Unknown: ServerId = ServerId("")
    }
}

/**
 * A SkyBlock profile, identified by its name.
 *
 * The name is what the game shows and the only identifier available client-side without
 * an API call. Stable enough to key session state on; anything persisted long-term keys
 * on the account UUID *plus* this, never on this alone.
 */
@Serializable
@JvmInline
public value class SkyBlockProfile(public val name: String) {
    public val isKnown: Boolean get() = name.isNotEmpty()

    override fun toString(): String = name

    public companion object {
        public val Unknown: SkyBlockProfile = SkyBlockProfile("")
    }
}

/**
 * A profile's game mode.
 *
 * Worth knowing because it changes what a feature should do rather than only what it shows:
 * an Ironman profile cannot trade, so a debt or a lending feature has nothing to offer there,
 * and a Bingo profile's progression is not the account's.
 *
 * Read from the **word** Hypixel prints, not the symbol beside it. The symbols — `♲ ☀ Ⓑ` —
 * are exactly the sort of thing their texture pack has been moving into private-use glyphs;
 * the words have not changed.
 */
@Serializable
public enum class ProfileType(public val displayName: String) {
    NORMAL("Normal"),
    IRONMAN("Ironman"),
    STRANDED("Stranded"),
    BINGO("Bingo"),
    ;

    public companion object {
        /** Reads Hypixel's own wording. Null when it is not one of them. */
        public fun ofWording(wording: String): ProfileType? =
            entries.firstOrNull { it.displayName.equals(wording.trim(), ignoreCase = true) }
    }
}
