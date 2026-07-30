package dev.th7bo.sidequest.platform.cosmetic

import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.lifecycle.Registration
import dev.th7bo.sidequest.platform.player.PlayerId

/**
 * Why a cosmetic is not being drawn.
 *
 * The same "say why" as the rest of this platform, and the case for it is stronger here than almost anywhere
 * else: a cosmetic that does not appear is indistinguishable from a broken mod, and the person affected is
 * usually the one who paid for it. Every one of these turns "it's not working" into a sentence.
 *
 * Ordered roughly by how early it is decided, which is also the order [CosmeticResolution] evaluates them in.
 */
public enum class HiddenReason(public val explanation: String) {
    /** The viewer turned cosmetics off entirely. */
    DISABLED("cosmetics are switched off"),
    SLOT_HIDDEN("that slot is switched off"),
    PLAYER_HIDDEN("that player's cosmetics are switched off"),
    APPEARANCE_OVERRIDES_HIDDEN("skin and cape overrides are switched off"),
    EFFECTS_HIDDEN("particle effects are switched off"),
    JOKE_HIDDEN("joke cosmetics are switched off"),

    /** The wearer chose to keep it to themselves. */
    LOCAL_ONLY("the wearer has it set to only themselves"),
    NOT_A_FRIEND("the wearer shows it to friends only"),

    UNKNOWN_COSMETIC("no such cosmetic is registered"),
    EXPIRED("it ran out"),
    CONDITION_NOT_MET("its condition does not hold"),

    /** Another cosmetic won the conflict. */
    CONFLICT("another cosmetic conflicts with it"),

    /** Its asset has not arrived, or was refused. The one reason that may fix itself. */
    ASSET_MISSING("its image has not downloaded"),
    ;

    /** Whether asking again later could give a different answer. */
    public val mightChange: Boolean
        get() = this == ASSET_MISSING || this == CONDITION_NOT_MET || this == NOT_A_FRIEND
}

/** A cosmetic that will be drawn, and how. */
public data class ShownCosmetic(
    public val cosmetic: Cosmetic,
    /**
     * Whether it may animate.
     *
     * Separate from being shown, because reduced-animation mode stills a cosmetic rather than hiding it — a
     * person who cannot tolerate motion still wants to see what everybody is wearing.
     */
    public val isAnimated: Boolean,
    /** True when this is standing in for one that could not be shown. */
    public val isFallback: Boolean = false,
)

/** One cosmetic that was considered and rejected, with the reason. For the inspector and for support. */
public data class HiddenCosmetic(
    public val cosmeticId: SqId,
    public val slot: CosmeticSlot,
    public val reason: HiddenReason,
    /** More than the reason's own sentence when there is more to say — which condition, which conflict. */
    public val detail: String? = null,
) {
    override fun toString(): String = "$cosmeticId (${reason.explanation}${detail?.let { ": $it" } ?: ""})"
}

/**
 * Everything decided about one player, for one viewer, at one moment.
 *
 * Both halves are kept. A resolution that returned only what to draw would answer "what does this look like"
 * and not "why does it not look like what I expected", and the second question is the one anybody actually
 * asks.
 */
public data class CosmeticResolution(
    public val wearer: PlayerId,
    public val shown: List<ShownCosmetic>,
    public val hidden: List<HiddenCosmetic>,
) {
    public operator fun get(slot: CosmeticSlot): ShownCosmetic? = shown.firstOrNull { it.cosmetic.slot == slot }

    public fun whyNot(cosmeticId: SqId): HiddenReason? = hidden.firstOrNull { it.cosmeticId == cosmeticId }?.reason

    public val isEmpty: Boolean get() = shown.isEmpty()

    public companion object {
        public fun nothing(wearer: PlayerId): CosmeticResolution =
            CosmeticResolution(wearer, emptyList(), emptyList())
    }
}

/**
 * Cosmetics: what people are wearing, and what this client draws of it.
 *
 * The service does not draw anything. It answers "given this wearer, this viewer and these settings, what
 * should appear" — which is arithmetic over data and therefore testable without a game, and is where every
 * decision in this feature actually lives. Rendering is a bridge on the other side of a sink, the same split
 * as notifications, cinematics and markers.
 */
public interface CosmeticService {

    /** Adds a definition. Features and the backend both register through this. */
    public fun register(cosmetic: Cosmetic): Registration

    public fun definition(id: SqId): Cosmetic?

    public fun definitions(): List<Cosmetic>

    /** Everything registered for a slot, for a wardrobe screen. */
    public fun definitionsFor(slot: CosmeticSlot): List<Cosmetic>

    // -- what people are wearing --------------------------------------------

    /** The local player's loadout. */
    public fun loadout(): CosmeticLoadout

    public fun loadoutOf(player: PlayerId): CosmeticLoadout

    /**
     * Puts a cosmetic on.
     *
     * Refuses an unknown id and an id whose slot does not match, so a loadout can never hold something that
     * cannot be resolved. Returns what stopped it, or null on success.
     */
    public fun equip(slot: CosmeticSlot, cosmeticId: SqId): String?

    public fun unequip(slot: CosmeticSlot)

    /** Replaces the local loadout wholesale. For the wardrobe's "wear this set" button. */
    public fun wear(loadout: CosmeticLoadout): CosmeticLoadout

    /** Records what a remote player is wearing, as told by the backend. */
    public fun setRemoteLoadout(player: PlayerId, loadout: CosmeticLoadout)

    public fun forgetRemoteLoadout(player: PlayerId)

    // -- what is actually drawn ---------------------------------------------

    /**
     * What to draw for [wearer], from the local player's point of view.
     *
     * The one method everything visual goes through, and the reason the render side can be thin.
     */
    public fun resolve(wearer: PlayerId): CosmeticResolution

    /**
     * What the local player's *personal* cosmetics do to this client.
     *
     * The counterpart to [resolve] for the slots nobody else can see. Kept separate because it answers a
     * different question — [resolve] is "what does this person look like", this is "what does my interface
     * look like" — and because it is read from render paths that have no player to ask about.
     */
    public fun personalStyle(): CosmeticStyle

    // -- the viewer's preferences -------------------------------------------

    public var settings: CosmeticSettings
}

/**
 * What the personal slots amount to.
 *
 * Flattened into one small value rather than handed out as cosmetics, because the things that read it — a
 * theme provider, the sound manager — want the *effect*, not the cosmetic that caused it. Null everywhere
 * means "leave it alone", so the default is the mod's own appearance rather than a styled one.
 */
public data class CosmeticStyle(
    /** Replaces the interface's accent colour. Drives notifications, cinematics and everything else themed. */
    public val accentColour: Int? = null,
    /**
     * The sound pack in effect, as an id prefix.
     *
     * A pack does not carry its own sounds; it names a *variant*. A request for `sidequest:levelup` under the
     * pack `pack.arcade` looks for `sidequest:pack.arcade.levelup` and takes the ordinary sound when there is
     * no such variant — so a pack that only replaces three sounds replaces three sounds, rather than
     * silencing everything it does not define.
     */
    public val soundPack: String? = null,
) {
    public companion object {
        /** The mod's own look. */
        public val None: CosmeticStyle = CosmeticStyle()
    }
}

/** Posted when the local player's loadout changes, so the backend can publish it. */
public class LoadoutChangedEvent(
    public val loadout: CosmeticLoadout,
) : SidequestEvent() {
    override fun describe(): String = "loadout changed: ${loadout.equipped.size} slot(s) worn"
}

/** Posted when the viewer's cosmetic settings change, so caches over [CosmeticService.resolve] can drop. */
public class CosmeticSettingsChangedEvent(
    public val settings: CosmeticSettings,
) : SidequestEvent() {
    override fun describe(): String =
        if (settings.isEnabled) "cosmetic settings changed" else "cosmetics switched off"
}
