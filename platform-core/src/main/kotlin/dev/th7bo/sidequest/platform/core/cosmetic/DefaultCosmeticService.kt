package dev.th7bo.sidequest.platform.core.cosmetic

import dev.th7bo.sidequest.platform.asset.AssetManager
import dev.th7bo.sidequest.platform.cosmetic.Cosmetic
import dev.th7bo.sidequest.platform.cosmetic.CosmeticContext
import dev.th7bo.sidequest.platform.cosmetic.CosmeticLoadout
import dev.th7bo.sidequest.platform.cosmetic.CosmeticResolution
import dev.th7bo.sidequest.platform.cosmetic.CosmeticService
import dev.th7bo.sidequest.platform.cosmetic.CosmeticSettings
import dev.th7bo.sidequest.platform.cosmetic.CosmeticSettingsChangedEvent
import dev.th7bo.sidequest.platform.cosmetic.CosmeticSlot
import dev.th7bo.sidequest.platform.cosmetic.CosmeticStyle
import dev.th7bo.sidequest.platform.cosmetic.CosmeticVisibility
import dev.th7bo.sidequest.platform.cosmetic.EquippedCosmetic
import dev.th7bo.sidequest.platform.cosmetic.HiddenCosmetic
import dev.th7bo.sidequest.platform.cosmetic.HiddenReason
import dev.th7bo.sidequest.platform.cosmetic.LoadoutChangedEvent
import dev.th7bo.sidequest.platform.cosmetic.ShownCosmetic
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.lifecycle.Registration
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.skyblock.GameContext
import dev.th7bo.sidequest.platform.skyblock.GameContextService

/**
 * Decides what a player looks like.
 *
 * The whole of the feature is one function — [resolve] — and everything else here exists to feed it. It is
 * pure over its inputs, which is why it can be tested exhaustively without a game and why the rendering side
 * is a thin bridge.
 *
 * Three rules shape it, in descending order of how much trouble getting them wrong causes.
 *
 * **The viewer always wins.** Every setting in [CosmeticSettings] beats the wearer's own choice. A system
 * where somebody else's preference decides what is on your screen is one that gets turned off wholesale the
 * first time a friend finds something funny, so the specific switches exist to stop people reaching for the
 * master one.
 *
 * **Nothing is hidden silently.** Every rejection is recorded with a reason. A cosmetic that does not appear
 * looks exactly like a broken mod, and the person affected is usually the one who earned it.
 *
 * **Conflicts resolve deterministically.** Render layer, then rarity, then id — so two clients looking at the
 * same player see the same thing. A tie broken by map order would show two people different things and
 * neither could tell which was right.
 */
public class DefaultCosmeticService(
    private val context: GameContextService,
    private val assets: AssetManager,
    private val events: EventBus,
    private val log: Logger,
    private val localPlayer: () -> PlayerId?,
    /** Whether the wearer is on the local player's custom friend list. */
    private val isFriend: (PlayerId) -> Boolean = { false },
    private val isInParty: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) : CosmeticService {

    private val definitions = LinkedHashMap<SqId, Cosmetic>()

    private var localLoadout: CosmeticLoadout = CosmeticLoadout.Empty

    private val remoteLoadouts = HashMap<PlayerId, CosmeticLoadout>()

    /** Called after a local change, so whoever owns storage can persist. */
    public var onLoadoutChanged: ((CosmeticLoadout) -> Unit)? = null

    override var settings: CosmeticSettings = CosmeticSettings.Default
        set(value) {
            if (field == value) return
            field = value
            events.post(CosmeticSettingsChangedEvent(value), EventSource.DERIVED)
        }

    // -- definitions ---------------------------------------------------------

    override fun register(cosmetic: Cosmetic): Registration {
        val previous = definitions.put(cosmetic.id, cosmetic)
        if (previous != null) log.debug { "Replaced the definition of ${cosmetic.id}" }
        return Registration { definitions.remove(cosmetic.id) }
    }

    override fun definition(id: SqId): Cosmetic? = definitions[id]

    override fun definitions(): List<Cosmetic> = definitions.values.toList()

    override fun definitionsFor(slot: CosmeticSlot): List<Cosmetic> =
        definitions.values.filter { it.slot == slot }.sortedWith(
            compareByDescending<Cosmetic> { it.rarity.ordinal }.thenBy { it.displayName },
        )

    // -- loadouts ------------------------------------------------------------

    override fun loadout(): CosmeticLoadout = localLoadout

    override fun loadoutOf(player: PlayerId): CosmeticLoadout =
        if (player == localPlayer()) localLoadout else remoteLoadouts[player] ?: CosmeticLoadout.Empty

    override fun equip(slot: CosmeticSlot, cosmeticId: SqId): String? {
        val cosmetic = definitions[cosmeticId] ?: return "no such cosmetic: $cosmeticId"
        // Checked rather than trusted, so a loadout can never hold something that cannot resolve. The
        // alternative is a silently empty slot and no way to find out why.
        if (cosmetic.slot != slot) return "${cosmetic.displayName} goes in ${cosmetic.slot}, not $slot"

        localLoadout = localLoadout.with(slot, EquippedCosmetic(cosmeticId, now()))
        announce()
        return null
    }

    override fun unequip(slot: CosmeticSlot) {
        if (localLoadout[slot] == null) return
        localLoadout = localLoadout.without(slot)
        announce()
    }

    override fun wear(loadout: CosmeticLoadout): CosmeticLoadout {
        // Filtered rather than rejected wholesale: a saved loadout naming one cosmetic that has since been
        // removed should put on the rest, not fail entirely and leave somebody looking like nobody.
        val kept = loadout.equipped.filter { (slot, entry) ->
            val cosmetic = definitions[entry.cosmeticId]
            when {
                cosmetic == null -> {
                    log.debug { "Dropping ${entry.cosmeticId} from a loadout: not registered" }
                    false
                }
                cosmetic.slot != slot -> {
                    log.debug { "Dropping ${entry.cosmeticId} from a loadout: it belongs in ${cosmetic.slot}" }
                    false
                }
                else -> true
            }
        }
        localLoadout = CosmeticLoadout(kept)
        announce()
        return localLoadout
    }

    override fun setRemoteLoadout(player: PlayerId, loadout: CosmeticLoadout) {
        remoteLoadouts[player] = loadout
    }

    override fun forgetRemoteLoadout(player: PlayerId) {
        remoteLoadouts.remove(player)
    }

    private fun announce() {
        events.post(LoadoutChangedEvent(localLoadout), EventSource.DERIVED)
        onLoadoutChanged?.invoke(localLoadout)
    }

    // -- resolution ----------------------------------------------------------

    override fun resolve(wearer: PlayerId): CosmeticResolution {
        val loadout = loadoutOf(wearer)
        if (loadout.isEmpty) return CosmeticResolution.nothing(wearer)

        // The master switch, first and cheapest. Reported rather than returning nothing, so somebody who has
        // forgotten they turned cosmetics off can be told.
        if (!settings.isEnabled) {
            return CosmeticResolution(
                wearer = wearer,
                shown = emptyList(),
                hidden = loadout.equipped.map { (slot, entry) ->
                    HiddenCosmetic(entry.cosmeticId, slot, HiddenReason.DISABLED)
                },
            )
        }

        val viewContext = Context(wearer, localPlayer(), context.context, isInParty(), isFriend(wearer), now())
        val candidates = ArrayList<ShownCosmetic>()
        val hidden = ArrayList<HiddenCosmetic>()

        for ((slot, entry) in loadout.equipped) {
            val decided = consider(slot, entry, viewContext)
            when (decided) {
                is Decision.Show -> candidates.add(decided.shown)
                is Decision.Hide -> {
                    hidden.add(decided.hidden)
                    // A fallback gets one chance, and only one. Chaining them would let a cycle of two
                    // cosmetics each falling back to the other loop forever.
                    fallbackFor(decided.hidden, slot, viewContext)?.let { candidates.add(it) }
                }
            }
        }

        val kept = resolveConflicts(candidates, hidden)
        return CosmeticResolution(wearer, kept, hidden)
    }

    /**
     * The personal slots, flattened.
     *
     * Goes through [resolve] rather than reading the loadout directly, so a personal cosmetic obeys the same
     * rules as every other one — the master switch turns it off, a condition can gate it, a missing asset
     * hides it. A second path that read the loadout would be a second set of rules to keep in step.
     */
    override fun personalStyle(): CosmeticStyle {
        val me = localPlayer() ?: return CosmeticStyle.None
        val resolution = resolve(me)
        if (resolution.shown.isEmpty()) return CosmeticStyle.None

        // The notification style wins the accent, and the cinematic style is the fallback. Two cosmetics
        // asking for different accents is a thing somebody can wear, so the order is fixed rather than
        // whichever the map happened to yield.
        val accent = resolution[CosmeticSlot.NOTIFICATION_STYLE]?.cosmetic?.accentColour
            ?: resolution[CosmeticSlot.CINEMATIC_STYLE]?.cosmetic?.accentColour

        // The pack's own path is the prefix, so a pack needs no field of its own to say what it is called.
        val pack = resolution[CosmeticSlot.SOUND_PACK]?.cosmetic?.id?.path

        return if (accent == null && pack == null) CosmeticStyle.None else CosmeticStyle(accent, pack)
    }

    private sealed interface Decision {
        data class Show(val shown: ShownCosmetic) : Decision
        data class Hide(val hidden: HiddenCosmetic) : Decision
    }

    /**
     * One cosmetic, from the viewer's point of view.
     *
     * Ordered so the viewer's own preferences are decided before the wearer's, which is not an optimisation —
     * it is the rule that the viewer wins, written as control flow so it cannot be got wrong later.
     */
    private fun consider(slot: CosmeticSlot, entry: EquippedCosmetic, view: Context): Decision {
        fun hide(reason: HiddenReason, detail: String? = null) =
            Decision.Hide(HiddenCosmetic(entry.cosmeticId, slot, reason, detail))

        val cosmetic = definitions[entry.cosmeticId] ?: return hide(HiddenReason.UNKNOWN_COSMETIC)

        // -- the viewer's half, which wins --
        if (slot in settings.hiddenSlots) return hide(HiddenReason.SLOT_HIDDEN)
        if (view.wearer in settings.hiddenPlayers) return hide(HiddenReason.PLAYER_HIDDEN)
        if (slot.isAppearanceOverride && !settings.showAppearanceOverrides) {
            return hide(HiddenReason.APPEARANCE_OVERRIDES_HIDDEN)
        }
        if (slot.isEffect && !settings.showEffects) return hide(HiddenReason.EFFECTS_HIDDEN)
        if (cosmetic.isJoke && !settings.showJokeCosmetics) return hide(HiddenReason.JOKE_HIDDEN)

        // -- the wearer's half --
        //
        // Only for worn slots. A notification style is about the viewer's own client, so "who may see this" is
        // meaningless for it — and applying visibility there would hide your own interface from you whenever
        // you were not your own friend.
        val isSelf = view.viewer != null && view.viewer == view.wearer
        if (slot.isWorn && !isSelf) {
            when (cosmetic.visibility) {
                CosmeticVisibility.LOCAL_ONLY -> return hide(HiddenReason.LOCAL_ONLY)
                CosmeticVisibility.FRIENDS -> if (!view.isFriend) return hide(HiddenReason.NOT_A_FRIEND)
                CosmeticVisibility.EVERYONE -> Unit
            }
        }

        cosmetic.duration?.let { duration ->
            // A zero stamp means the equip time was never recorded — a loadout from an older format. Treated
            // as permanent rather than as expired: showing something too long is a smaller wrong than
            // silently stripping somebody's cosmetics on an upgrade.
            if (entry.equippedAtMillis > 0 &&
                view.nowMillis - entry.equippedAtMillis > duration.inWholeMilliseconds
            ) {
                return hide(HiddenReason.EXPIRED)
            }
        }

        if (!cosmetic.condition.test(view)) {
            return hide(HiddenReason.CONDITION_NOT_MET, cosmetic.condition.explain(view))
        }

        // The asset last, because it is the only check that is not a pure comparison — and the only one whose
        // answer changes on its own once a download finishes.
        if (cosmetic.assetId != null && assets.resident(cosmetic.assetId!!) == null) {
            return hide(HiddenReason.ASSET_MISSING)
        }

        return Decision.Show(
            ShownCosmetic(
                cosmetic = cosmetic,
                // Stilled rather than hidden. Somebody who cannot tolerate motion still wants to see what
                // people are wearing.
                isAnimated = cosmetic.isAnimated && !settings.reducedAnimation,
            ),
        )
    }

    /**
     * The stand-in for something that could not be shown.
     *
     * Deliberately not recursive, and only for reasons a fallback can actually help with. Falling back because
     * the *viewer* switched the slot off would put a cosmetic on screen that they asked not to see, which is
     * the one outcome this whole type is designed to prevent.
     */
    private fun fallbackFor(hidden: HiddenCosmetic, slot: CosmeticSlot, view: Context): ShownCosmetic? {
        if (hidden.reason !in FALLBACK_REASONS) return null
        val original = definitions[hidden.cosmeticId] ?: return null
        val fallback = original.fallbackId?.let { definitions[it] } ?: return null
        if (fallback.slot != slot) return null

        // The fallback is checked too — one level, no chaining — because a fallback whose own asset is missing
        // is not an improvement on the thing it is replacing.
        val decision = consider(slot, EquippedCosmetic(fallback.id, view.nowMillis), view)
        return (decision as? Decision.Show)?.shown?.copy(isFallback = true)
    }

    /**
     * Drops the losers of any conflict.
     *
     * One per slot is already guaranteed by the loadout's shape, so this is only about the cross-slot
     * declarations — an aura that cannot be worn with a particular cape.
     */
    private fun resolveConflicts(
        candidates: List<ShownCosmetic>,
        hidden: MutableList<HiddenCosmetic>,
    ): List<ShownCosmetic> {
        if (candidates.size < 2) return candidates

        // Strongest first, so the winner of any pair is whichever is reached first.
        val ordered = candidates.sortedWith(
            compareByDescending<ShownCosmetic> { it.cosmetic.renderLayer }
                .thenByDescending { it.cosmetic.rarity.ordinal }
                .thenBy { it.cosmetic.id.toString() },
        )

        val kept = ArrayList<ShownCosmetic>(ordered.size)
        for (candidate in ordered) {
            val loser = kept.firstOrNull { winner ->
                candidate.cosmetic.id in winner.cosmetic.conflicts ||
                    winner.cosmetic.id in candidate.cosmetic.conflicts
            }
            if (loser == null) {
                kept.add(candidate)
                continue
            }
            hidden.add(
                HiddenCosmetic(
                    candidate.cosmetic.id,
                    candidate.cosmetic.slot,
                    HiddenReason.CONFLICT,
                    "${loser.cosmetic.displayName} won",
                ),
            )
        }
        return kept
    }

    private class Context(
        override val wearer: PlayerId,
        override val viewer: PlayerId?,
        override val game: GameContext,
        override val isInParty: Boolean,
        override val isFriend: Boolean,
        override val nowMillis: Long,
    ) : CosmeticContext

    private companion object {
        /**
         * The rejections a fallback may answer.
         *
         * Every one is about the cosmetic being unusable rather than unwanted. A viewer's preference is never
         * in here, because working around one would defeat it.
         */
        val FALLBACK_REASONS = setOf(
            HiddenReason.ASSET_MISSING,
            HiddenReason.UNKNOWN_COSMETIC,
            HiddenReason.EXPIRED,
            HiddenReason.CONDITION_NOT_MET,
        )
    }
}
