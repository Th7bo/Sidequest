package dev.th7bo.sidequest.platform.core.cosmetic

import dev.th7bo.sidequest.platform.asset.Asset
import dev.th7bo.sidequest.platform.asset.AssetCacheStats
import dev.th7bo.sidequest.platform.asset.AssetId
import dev.th7bo.sidequest.platform.asset.AssetKind
import dev.th7bo.sidequest.platform.asset.AssetManager
import dev.th7bo.sidequest.platform.asset.AssetRejection
import dev.th7bo.sidequest.platform.asset.AssetResult
import dev.th7bo.sidequest.platform.asset.MediaType
import dev.th7bo.sidequest.platform.cosmetic.Cosmetic
import dev.th7bo.sidequest.platform.cosmetic.CosmeticCondition
import dev.th7bo.sidequest.platform.cosmetic.CosmeticLoadout
import dev.th7bo.sidequest.platform.cosmetic.CosmeticRarity
import dev.th7bo.sidequest.platform.cosmetic.CosmeticSettings
import dev.th7bo.sidequest.platform.cosmetic.CosmeticSlot
import dev.th7bo.sidequest.platform.cosmetic.CosmeticVisibility
import dev.th7bo.sidequest.platform.cosmetic.EquippedCosmetic
import dev.th7bo.sidequest.platform.cosmetic.HiddenReason
import dev.th7bo.sidequest.platform.core.context.DefaultGameContextService
import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.hours

/**
 * What a player actually looks like.
 *
 * Resolution is the whole of this feature — everything else feeds it — and it is pure over its inputs, so the
 * interesting cases are all reachable without a game. The ones worth the most are about the viewer's
 * preferences beating the wearer's, because that is the rule people notice when it is wrong.
 */
class CosmeticResolutionTest {

    private val me = PlayerId.of(UUID.nameUUIDFromBytes("me".toByteArray()))
    private val friend = PlayerId.of(UUID.nameUUIDFromBytes("friend".toByteArray()))
    private val stranger = PlayerId.of(UUID.nameUUIDFromBytes("stranger".toByteArray()))

    private lateinit var assets: FakeAssets
    private lateinit var service: DefaultCosmeticService
    private var friends = mutableSetOf(friend)

    @BeforeEach
    fun setUp() {
        assets = FakeAssets()
        val scheduler = TestScheduler()
        val events = DefaultEventBus(scheduler, NoopLogger)
        service = DefaultCosmeticService(
            context = DefaultGameContextService(events, NoopLogger),
            assets = assets,
            events = events,
            log = NoopLogger,
            localPlayer = { me },
            isFriend = { it in friends },
            now = { NOW },
        )
    }

    private fun cosmetic(
        name: String,
        slot: CosmeticSlot = CosmeticSlot.BADGE,
        visibility: CosmeticVisibility = CosmeticVisibility.EVERYONE,
        assetId: AssetId? = null,
        isJoke: Boolean = false,
        isAnimated: Boolean = false,
        rarity: CosmeticRarity = CosmeticRarity.COMMON,
        renderLayer: Int = 0,
        conflicts: Set<SqId> = emptySet(),
        condition: CosmeticCondition = CosmeticCondition.Always,
        fallbackId: SqId? = null,
        duration: kotlin.time.Duration? = null,
    ): Cosmetic = Cosmetic(
        id = SqId.sidequest(name),
        slot = slot,
        displayName = name,
        assetId = assetId,
        rarity = rarity,
        visibility = visibility,
        duration = duration,
        conflicts = conflicts,
        condition = condition,
        renderLayer = renderLayer,
        fallbackId = fallbackId,
        isJoke = isJoke,
        isAnimated = isAnimated,
    ).also { service.register(it) }

    /** Puts [cosmetic] on [wearer], as if the backend had said so. */
    private fun wornBy(wearer: PlayerId, vararg worn: Cosmetic, equippedAt: Long = NOW) {
        val loadout = CosmeticLoadout(
            worn.associate { it.slot to EquippedCosmetic(it.id, equippedAt) },
        )
        if (wearer == me) service.wear(loadout) else service.setRemoteLoadout(wearer, loadout)
    }

    // -- the viewer wins -----------------------------------------------------

    /**
     * The rule the whole settings type exists for.
     *
     * Somebody who has turned off particles does not see particles, whatever the wearer chose. A system where
     * another person's preference decides what is on your screen is one that gets turned off wholesale.
     */
    @Test
    fun `a viewer who hides effects sees none, whatever the wearer set`() {
        val aura = cosmetic("aura", slot = CosmeticSlot.AURA, visibility = CosmeticVisibility.EVERYONE)
        wornBy(friend, aura)
        service.settings = CosmeticSettings(showEffects = false)

        val resolved = service.resolve(friend)

        assertTrue(resolved.isEmpty)
        assertEquals(HiddenReason.EFFECTS_HIDDEN, resolved.whyNot(aura.id))
    }

    @Test
    fun `hiding skin overrides covers capes too, because the slot says so`() {
        val skin = cosmetic("skin", slot = CosmeticSlot.SKIN)
        val cape = cosmetic("cape", slot = CosmeticSlot.CAPE)
        val badge = cosmetic("badge", slot = CosmeticSlot.BADGE)
        wornBy(friend, skin, cape, badge)
        service.settings = CosmeticSettings(showAppearanceOverrides = false)

        val resolved = service.resolve(friend)

        assertEquals(HiddenReason.APPEARANCE_OVERRIDES_HIDDEN, resolved.whyNot(skin.id))
        assertEquals(HiddenReason.APPEARANCE_OVERRIDES_HIDDEN, resolved.whyNot(cape.id))
        assertNotNull(resolved[CosmeticSlot.BADGE], "a badge is neither a skin nor a cape")
    }

    @Test
    fun `joke cosmetics can be hidden without hiding everything`() {
        val joke = cosmetic("rubber_chicken", isJoke = true)
        val serious = cosmetic("crown", slot = CosmeticSlot.TITLE)
        wornBy(friend, joke, serious)
        service.settings = CosmeticSettings(showJokeCosmetics = false)

        val resolved = service.resolve(friend)

        assertEquals(HiddenReason.JOKE_HIDDEN, resolved.whyNot(joke.id))
        assertNotNull(resolved[CosmeticSlot.TITLE])
    }

    @Test
    fun `an individual player can be muted`() {
        val badge = cosmetic("badge")
        wornBy(friend, badge)
        wornBy(stranger, badge)
        service.settings = CosmeticSettings(hiddenPlayers = setOf(stranger))

        assertNotNull(service.resolve(friend)[CosmeticSlot.BADGE])
        assertEquals(HiddenReason.PLAYER_HIDDEN, service.resolve(stranger).whyNot(badge.id))
    }

    @Test
    fun `the master switch hides everything and still says why`() {
        val badge = cosmetic("badge")
        wornBy(friend, badge)
        service.settings = CosmeticSettings.AllOff

        val resolved = service.resolve(friend)

        assertTrue(resolved.isEmpty)
        assertEquals(HiddenReason.DISABLED, resolved.whyNot(badge.id))
    }

    /**
     * Reduced animation stills a cosmetic rather than hiding it.
     *
     * An accessibility setting before a performance one: somebody who cannot tolerate motion still wants to
     * see what people are wearing. Hiding would be the easier implementation and the wrong one.
     */
    @Test
    fun `reduced animation keeps the cosmetic and stops it moving`() {
        val animated = cosmetic("swirl", slot = CosmeticSlot.AURA, isAnimated = true)
        wornBy(friend, animated)
        service.settings = CosmeticSettings(reducedAnimation = true)

        val shown = service.resolve(friend)[CosmeticSlot.AURA]

        assertNotNull(shown, "it should still be visible")
        assertFalse(shown!!.isAnimated, "but not moving")
    }

    // -- the wearer's half ---------------------------------------------------

    @Test
    fun `a friends-only cosmetic is hidden from a stranger and shown to a friend`() {
        val badge = cosmetic("badge", visibility = CosmeticVisibility.FRIENDS)
        wornBy(friend, badge)
        wornBy(stranger, badge)

        assertNotNull(service.resolve(friend)[CosmeticSlot.BADGE])
        assertEquals(HiddenReason.NOT_A_FRIEND, service.resolve(stranger).whyNot(badge.id))
    }

    @Test
    fun `a local-only cosmetic is visible to its wearer and nobody else`() {
        val badge = cosmetic("badge", visibility = CosmeticVisibility.LOCAL_ONLY)
        wornBy(me, badge)
        wornBy(friend, badge)

        assertNotNull(service.resolve(me)[CosmeticSlot.BADGE], "trying something on has to be visible to you")
        assertEquals(HiddenReason.LOCAL_ONLY, service.resolve(friend).whyNot(badge.id))
    }

    /**
     * Visibility is meaningless for a personal slot.
     *
     * A notification style changes the viewer's own client; nobody else can see it. Applying "friends only" to
     * one would hide your own interface from you whenever you were not on your own friend list — which is
     * always, since nobody friends themselves.
     */
    @Test
    fun `a personal slot ignores visibility entirely`() {
        val style = cosmetic(
            "neon_toasts",
            slot = CosmeticSlot.NOTIFICATION_STYLE,
            visibility = CosmeticVisibility.FRIENDS,
        )
        friends.clear()
        wornBy(me, style)

        assertNotNull(
            service.resolve(me)[CosmeticSlot.NOTIFICATION_STYLE],
            "your own toast style is not subject to whether you are your own friend",
        )
    }

    // -- expiry and conditions ----------------------------------------------

    @Test
    fun `a timed cosmetic stops showing once it runs out`() {
        val timed = cosmetic("weekend_crown", duration = 2.hours)
        wornBy(friend, timed, equippedAt = NOW - 3.hours.inWholeMilliseconds)

        assertEquals(HiddenReason.EXPIRED, service.resolve(friend).whyNot(timed.id))
    }

    /**
     * A loadout with no equip time is treated as permanent.
     *
     * That case is a loadout saved before the stamp existed. Showing something slightly too long is a smaller
     * wrong than silently stripping everybody's cosmetics the first time they update.
     */
    @Test
    fun `a timed cosmetic with no equip time is not treated as expired`() {
        val timed = cosmetic("weekend_crown", duration = 2.hours)
        wornBy(friend, timed, equippedAt = 0)

        assertNotNull(service.resolve(friend)[CosmeticSlot.BADGE])
    }

    @Test
    fun `a condition that does not hold hides the cosmetic and names itself`() {
        val partyOnly = cosmetic("party_hat", condition = CosmeticCondition.InParty)
        wornBy(friend, partyOnly)

        val resolved = service.resolve(friend)

        assertEquals(HiddenReason.CONDITION_NOT_MET, resolved.whyNot(partyOnly.id))
        assertEquals(
            "not in a party",
            resolved.hidden.single { it.cosmeticId == partyOnly.id }.detail,
            "the reason has to name which condition, or it explains nothing",
        )
    }

    // -- assets --------------------------------------------------------------

    @Test
    fun `a cosmetic whose asset has not arrived is hidden until it has`() {
        val id = AssetId("a".repeat(64))
        val badge = cosmetic("badge", assetId = id)
        wornBy(friend, badge)

        assertEquals(HiddenReason.ASSET_MISSING, service.resolve(friend).whyNot(badge.id))
        assertTrue(HiddenReason.ASSET_MISSING.mightChange, "and it is the reason that fixes itself")

        assets.hold(id)
        assertNotNull(service.resolve(friend)[CosmeticSlot.BADGE])
    }

    @Test
    fun `a missing asset falls back to another cosmetic`() {
        val plain = cosmetic("plain_badge")
        val fancy = cosmetic("fancy_badge", assetId = AssetId("b".repeat(64)), fallbackId = plain.id)
        wornBy(friend, fancy)

        val shown = service.resolve(friend)[CosmeticSlot.BADGE]

        assertEquals(plain.id, shown?.cosmetic?.id)
        assertTrue(shown!!.isFallback, "and it is marked as a stand-in rather than passed off as the real one")
    }

    /**
     * A fallback never works around the viewer.
     *
     * Substituting something for a cosmetic that was hidden *because the viewer asked* would put back exactly
     * what they switched off, which is the one outcome the settings exist to prevent.
     */
    @Test
    fun `a cosmetic hidden by the viewer does not fall back to something else`() {
        val plain = cosmetic("plain_aura", slot = CosmeticSlot.AURA)
        val fancy = cosmetic("fancy_aura", slot = CosmeticSlot.AURA, fallbackId = plain.id)
        wornBy(friend, fancy)
        service.settings = CosmeticSettings(showEffects = false)

        assertTrue(service.resolve(friend).isEmpty, "switching effects off must not summon the fallback")
    }

    @Test
    fun `a fallback pointing at a cosmetic that is also broken shows nothing`() {
        val alsoBroken = cosmetic("also_broken", assetId = AssetId("c".repeat(64)))
        val broken = cosmetic("broken", assetId = AssetId("d".repeat(64)), fallbackId = alsoBroken.id)
        wornBy(friend, broken)

        // One level, deliberately. Chained fallbacks let two cosmetics pointing at each other loop forever.
        assertTrue(service.resolve(friend).isEmpty)
    }

    // -- conflicts -----------------------------------------------------------

    @Test
    fun `a conflict is resolved by render layer and the loser is told why`() {
        val cape = cosmetic("wings", slot = CosmeticSlot.CAPE, renderLayer = 5)
        val aura = cosmetic("halo", slot = CosmeticSlot.AURA, renderLayer = 1, conflicts = setOf(cape.id))
        wornBy(friend, cape, aura)

        val resolved = service.resolve(friend)

        assertNotNull(resolved[CosmeticSlot.CAPE], "the higher layer wins")
        assertEquals(HiddenReason.CONFLICT, resolved.whyNot(aura.id))
        assertEquals("wings won", resolved.hidden.single { it.cosmeticId == aura.id }.detail)
    }

    /**
     * Two clients must resolve a conflict the same way.
     *
     * A tie broken by map order would show two people different things and neither could tell which was
     * right, so the order runs all the way down to the id.
     */
    @Test
    fun `a conflict at the same layer is broken deterministically by rarity`() {
        val common = cosmetic("common_aura", slot = CosmeticSlot.AURA, rarity = CosmeticRarity.COMMON)
        val mythic = cosmetic(
            "mythic_cape",
            slot = CosmeticSlot.CAPE,
            rarity = CosmeticRarity.MYTHIC,
            conflicts = setOf(common.id),
        )
        wornBy(friend, common, mythic)

        repeat(5) {
            val resolved = service.resolve(friend)
            assertNotNull(resolved[CosmeticSlot.CAPE], "the rarer one wins, every time")
            assertEquals(HiddenReason.CONFLICT, resolved.whyNot(common.id))
        }
    }

    @Test
    fun `cosmetics that do not conflict are all shown`() {
        val cape = cosmetic("cape", slot = CosmeticSlot.CAPE)
        val badge = cosmetic("badge", slot = CosmeticSlot.BADGE)
        val title = cosmetic("title", slot = CosmeticSlot.TITLE)
        wornBy(friend, cape, badge, title)

        assertEquals(3, service.resolve(friend).shown.size)
    }

    // -- personal slots ------------------------------------------------------

    /**
     * A style recolours your own interface and nothing else.
     *
     * This is what makes notification style and cinematic style need no code of their own: both are an
     * accent, and everything the mod draws already takes its accent from the theme.
     */
    @Test
    fun `a worn style supplies the interface accent`() {
        val style = Cosmetic(
            id = SqId.sidequest("neon"),
            slot = CosmeticSlot.NOTIFICATION_STYLE,
            displayName = "Neon",
            accentColour = 0xFF2D95,
        ).also { service.register(it) }
        wornBy(me, style)

        assertEquals(0xFF2D95, service.personalStyle().accentColour)
    }

    @Test
    fun `a sound pack is named by its own id path`() {
        val pack = Cosmetic(
            id = SqId.sidequest("pack.arcade"),
            slot = CosmeticSlot.SOUND_PACK,
            displayName = "Arcade",
        ).also { service.register(it) }
        wornBy(me, pack)

        assertEquals("pack.arcade", service.personalStyle().soundPack)
    }

    /**
     * The notification style wins the accent, deterministically.
     *
     * Two styles asking for different colours is something a person can wear, so the order is fixed rather
     * than whichever the map happened to yield first.
     */
    @Test
    fun `the notification style beats the cinematic style for the accent`() {
        val toasts = Cosmetic(
            id = SqId.sidequest("toast_style"),
            slot = CosmeticSlot.NOTIFICATION_STYLE,
            displayName = "Toasts",
            accentColour = 0x111111,
        ).also { service.register(it) }
        val cinematics = Cosmetic(
            id = SqId.sidequest("cine_style"),
            slot = CosmeticSlot.CINEMATIC_STYLE,
            displayName = "Cinematics",
            accentColour = 0x222222,
        ).also { service.register(it) }
        wornBy(me, toasts, cinematics)

        repeat(5) { assertEquals(0x111111, service.personalStyle().accentColour) }
    }

    /** Personal cosmetics obey the same rules as every other one, including the master switch. */
    @Test
    fun `turning cosmetics off also turns off the style`() {
        val style = Cosmetic(
            id = SqId.sidequest("neon"),
            slot = CosmeticSlot.NOTIFICATION_STYLE,
            displayName = "Neon",
            accentColour = 0xFF2D95,
        ).also { service.register(it) }
        wornBy(me, style)
        assertNotNull(service.personalStyle().accentColour)

        service.settings = CosmeticSettings.AllOff

        assertNull(service.personalStyle().accentColour, "the master switch has to reach the style too")
    }

    @Test
    fun `wearing nothing personal leaves the mod looking like itself`() {
        assertEquals(dev.th7bo.sidequest.platform.cosmetic.CosmeticStyle.None, service.personalStyle())
    }

    // -- loadouts ------------------------------------------------------------

    @Test
    fun `equipping refuses an unknown cosmetic and one in the wrong slot`() {
        val badge = cosmetic("badge", slot = CosmeticSlot.BADGE)

        assertNotNull(service.equip(CosmeticSlot.BADGE, SqId.sidequest("nope")), "an unknown id is refused")
        assertNotNull(service.equip(CosmeticSlot.CAPE, badge.id), "and so is the wrong slot")
        assertNull(service.equip(CosmeticSlot.BADGE, badge.id))
        assertEquals(badge.id, service.loadout()[CosmeticSlot.BADGE]?.cosmeticId)
    }

    /**
     * A loadout naming a cosmetic that has since gone puts on the rest.
     *
     * Failing the whole thing would leave somebody looking like nobody because one badge was retired.
     */
    @Test
    fun `wearing a loadout drops what no longer exists and keeps the rest`() {
        val badge = cosmetic("badge", slot = CosmeticSlot.BADGE)

        val worn = service.wear(
            CosmeticLoadout(
                mapOf(
                    CosmeticSlot.BADGE to EquippedCosmetic(badge.id, NOW),
                    CosmeticSlot.CAPE to EquippedCosmetic(SqId.sidequest("retired"), NOW),
                ),
            ),
        )

        assertEquals(setOf(CosmeticSlot.BADGE), worn.equipped.keys)
    }

    @Test
    fun `a slot holds one cosmetic, so equipping replaces`() {
        val first = cosmetic("first")
        val second = cosmetic("second")

        service.equip(CosmeticSlot.BADGE, first.id)
        service.equip(CosmeticSlot.BADGE, second.id)

        assertEquals(1, service.loadout().equipped.size)
        assertEquals(second.id, service.loadout()[CosmeticSlot.BADGE]?.cosmeticId)
    }

    @Test
    fun `an empty loadout resolves to nothing without any work`() {
        assertTrue(service.resolve(stranger).isEmpty)
        assertTrue(service.resolve(stranger).hidden.isEmpty())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

/** An asset manager that holds exactly what it is told to. */
private class FakeAssets : AssetManager {

    private val held = HashMap<AssetId, Asset>()

    fun hold(id: AssetId) {
        held[id] = Asset(id, AssetKind.ICON, MediaType.PNG, ByteArray(4))
    }

    override suspend fun load(id: AssetId, kind: AssetKind): AssetResult =
        held[id]?.let { AssetResult.Ready(it) } ?: AssetResult.Refused(id, AssetRejection.NoSource)

    override fun resident(id: AssetId): Asset? = held[id]
    override suspend fun preload(ids: Collection<AssetId>, kind: AssetKind) {}
    override fun releaseMemory(): Unit = held.clear()
    override suspend fun clear(): Unit = held.clear()
    override fun stats(): AssetCacheStats = AssetCacheStats(held.size, 0, 0, 0, 0, 0)
}
