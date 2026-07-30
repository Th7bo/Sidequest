package dev.th7bo.sidequest.platform.core.preview

import dev.th7bo.sidequest.platform.asset.AssetId
import dev.th7bo.sidequest.platform.cinematic.Cinematic
import dev.th7bo.sidequest.platform.cinematic.CinematicComponent
import dev.th7bo.sidequest.platform.cinematic.CinematicPriority
import dev.th7bo.sidequest.platform.cosmetic.Cosmetic
import dev.th7bo.sidequest.platform.cosmetic.CosmeticCondition
import dev.th7bo.sidequest.platform.cosmetic.CosmeticLoadout
import dev.th7bo.sidequest.platform.cosmetic.CosmeticRarity
import dev.th7bo.sidequest.platform.cosmetic.CosmeticSlot
import dev.th7bo.sidequest.platform.cosmetic.CosmeticVisibility
import dev.th7bo.sidequest.platform.cosmetic.EquippedCosmetic
import dev.th7bo.sidequest.platform.cosmetic.UnlockSource
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.marker.Marker
import dev.th7bo.sidequest.platform.marker.MarkerKind
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Ready-made content, for previewing things that are hard to arrange.
 *
 * A rare drop worth showing off happens once a week; a cinematic for one cannot wait that long to be looked
 * at. Everything here exists so a screen, a layout or an animation can be seen — in a test, in a screenshot,
 * or through a developer command — without playing the game until the real thing occurs.
 *
 * These are **fixtures, not defaults**. The developer commands reach for them deliberately; nothing in the
 * shipped feature set should, and a cosmetic defined here appearing on somebody's character means a wire
 * got crossed.
 *
 * It lives in `platform-core` rather than in the testkit because both sides need it — a test wants a
 * cinematic to render and `/sqtest drop` wants the same one. Two copies would drift, and the copy that
 * drifted would be the one nobody was looking at.
 */
public object PreviewData {

    // -- cinematics ----------------------------------------------------------

    /** The one the whole cinematic system was built for. */
    public val rareDrop: Cinematic = Cinematic(
        id = SqId.sidequest("preview.rare_drop"),
        priority = CinematicPriority.HIGH,
        duration = 4.seconds,
        components = listOf(
            CinematicComponent.Letterbox(),
            CinematicComponent.Title("RARE DROP", colour = 0xFFAA00),
            CinematicComponent.Subtitle("from a Wither Chest"),
            CinematicComponent.RewardReveal("+1 Hyperion", atFraction = 0.55f),
        ),
    )

    /** Numbers, so the counter has something to count. */
    public val bigPayout: Cinematic = Cinematic(
        id = SqId.sidequest("preview.payout"),
        priority = CinematicPriority.NORMAL,
        duration = 4.seconds,
        components = listOf(
            CinematicComponent.Letterbox(),
            CinematicComponent.Title("BAZAAR FLIP", colour = 0x55FF55),
            CinematicComponent.AnimatedNumber(1_234_567, suffix = " coins"),
            CinematicComponent.ProgressBar(0.72f, "Daily goal"),
        ),
    )

    /** Long enough to be interrupted by something, for testing what happens when one is. */
    public val slowAchievement: Cinematic = Cinematic(
        id = SqId.sidequest("preview.achievement"),
        priority = CinematicPriority.LOW,
        duration = 10.seconds,
        components = listOf(
            CinematicComponent.Title("ACHIEVEMENT", colour = 0xAA00AA),
            CinematicComponent.Subtitle("Catacombs Floor 7 — first clear"),
        ),
    )

    public val cinematics: List<Cinematic> = listOf(rareDrop, bigPayout, slowAchievement)

    // -- cosmetics -----------------------------------------------------------

    /**
     * A spread that covers every branch of resolution.
     *
     * Deliberately not "one of each slot". What is useful in a preview is one of each *awkward case* — a
     * friends-only one, a joke one, an animated one, a timed one, a conflicting pair — because those are the
     * ones whose behaviour is worth looking at.
     */
    public val plainBadge: Cosmetic = Cosmetic(
        id = SqId.sidequest("preview.badge.plain"),
        slot = CosmeticSlot.BADGE,
        displayName = "Plain Badge",
        text = "◆",
        rarity = CosmeticRarity.COMMON,
    )

    public val friendsOnlyTitle: Cosmetic = Cosmetic(
        id = SqId.sidequest("preview.title.inner_circle"),
        slot = CosmeticSlot.TITLE,
        displayName = "Inner Circle",
        text = "Inner Circle",
        rarity = CosmeticRarity.EPIC,
        unlockSource = UnlockSource.REPUTATION,
        visibility = CosmeticVisibility.FRIENDS,
    )

    public val jokeHat: Cosmetic = Cosmetic(
        id = SqId.sidequest("preview.badge.rubber_chicken"),
        slot = CosmeticSlot.BADGE,
        displayName = "Rubber Chicken",
        text = "🐔",
        rarity = CosmeticRarity.UNCOMMON,
        unlockSource = UnlockSource.GIFT,
        isJoke = true,
    )

    public val animatedAura: Cosmetic = Cosmetic(
        id = SqId.sidequest("preview.aura.embers"),
        slot = CosmeticSlot.AURA,
        displayName = "Embers",
        rarity = CosmeticRarity.LEGENDARY,
        unlockSource = UnlockSource.ACHIEVEMENT,
        isAnimated = true,
        renderLayer = 1,
    )

    /** Conflicts with the aura, so conflict resolution has a real pair to resolve. */
    public val conflictingCape: Cosmetic = Cosmetic(
        id = SqId.sidequest("preview.cape.wings"),
        slot = CosmeticSlot.CAPE,
        displayName = "Wings",
        rarity = CosmeticRarity.MYTHIC,
        unlockSource = UnlockSource.EVENT,
        conflicts = setOf(SqId.sidequest("preview.aura.embers")),
        renderLayer = 5,
    )

    public val expiringCrown: Cosmetic = Cosmetic(
        id = SqId.sidequest("preview.title.weekend_crown"),
        slot = CosmeticSlot.NAMETAG_PREFIX,
        displayName = "Weekend Crown",
        text = "♛ ",
        rarity = CosmeticRarity.RARE,
        unlockSource = UnlockSource.EVENT,
        duration = 48.hours,
    )

    /** Needs an asset, so the "not downloaded yet" path has something to take. */
    public val assetBackedBadge: Cosmetic = Cosmetic(
        id = SqId.sidequest("preview.badge.custom"),
        slot = CosmeticSlot.BADGE,
        displayName = "Custom Badge",
        assetId = previewAssetId("badge"),
        rarity = CosmeticRarity.RARE,
        fallbackId = SqId.sidequest("preview.badge.plain"),
    )

    public val dungeonOnlyAura: Cosmetic = Cosmetic(
        id = SqId.sidequest("preview.aura.dungeon"),
        slot = CosmeticSlot.AURA,
        displayName = "Catacombs Glow",
        rarity = CosmeticRarity.EPIC,
        condition = CosmeticCondition.OnIsland(setOf(Island.CATACOMBS)),
    )

    public val cosmetics: List<Cosmetic> = listOf(
        plainBadge,
        friendsOnlyTitle,
        jokeHat,
        animatedAura,
        conflictingCape,
        expiringCrown,
        assetBackedBadge,
        dungeonOnlyAura,
    )

    /** Something worn in several slots at once, for looking at a whole character. */
    public fun fullLoadout(equippedAtMillis: Long = 0L): CosmeticLoadout = CosmeticLoadout(
        mapOf(
            CosmeticSlot.CAPE to EquippedCosmetic(conflictingCape.id, equippedAtMillis),
            CosmeticSlot.BADGE to EquippedCosmetic(plainBadge.id, equippedAtMillis),
            CosmeticSlot.TITLE to EquippedCosmetic(friendsOnlyTitle.id, equippedAtMillis),
            CosmeticSlot.AURA to EquippedCosmetic(animatedAura.id, equippedAtMillis),
            CosmeticSlot.NAMETAG_PREFIX to EquippedCosmetic(expiringCrown.id, equippedAtMillis),
        ),
    )

    // -- waypoints -----------------------------------------------------------

    /**
     * A field of markers, for looking at overlays at once.
     *
     * Spread over distance on purpose: the fade, the ordering and the edge indicators all only do anything
     * when things are at different ranges, and a row of markers at the same distance shows none of it.
     */
    public fun markerField(
        island: Island = Island.HUB,
        around: SqPosition = SqPosition(0.0, 70.0, 0.0),
    ): List<Marker> = listOf(
        marker("near", MarkerKind.WAYPOINT, island, around.offset(8.0, 0.0, 8.0), "Bazaar"),
        marker("mid", MarkerKind.WAYPOINT, island, around.offset(-40.0, 4.0, 25.0), "Auction House"),
        marker("far", MarkerKind.WAYPOINT, island, around.offset(120.0, -6.0, -80.0), "Fairy Soul"),
        marker("above", MarkerKind.PING, island, around.offset(0.0, 30.0, 12.0), "up here"),
        marker("behind", MarkerKind.RALLY, island, around.offset(0.0, 0.0, -35.0), "regroup"),
        marker("route1", MarkerKind.NAVIGATION, island, around.offset(20.0, 0.0, 0.0), "1"),
        marker("route2", MarkerKind.NAVIGATION, island, around.offset(60.0, 0.0, 20.0), "2"),
    )

    private fun marker(
        name: String,
        kind: MarkerKind,
        island: Island,
        position: SqPosition,
        label: String,
    ) = Marker(
        id = "preview.$name",
        kind = kind,
        location = SqLocation(island = island, position = position),
        label = label,
    )

    private fun SqPosition.offset(dx: Double, dy: Double, dz: Double) = SqPosition(x + dx, y + dy, z + dz)

    // -- assets --------------------------------------------------------------

    /**
     * A stable, well-formed id for a preview asset.
     *
     * Real ids are SHA-256 of real bytes; these are not, and could not be — the point of a preview asset is
     * that nobody uploaded one. The shape is right, which is all anything checks before trying to fetch it.
     */
    public fun previewAssetId(name: String): AssetId {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return AssetId(digest.digest("preview:$name".toByteArray()).joinToString("") { "%02x".format(it) })
    }
}
