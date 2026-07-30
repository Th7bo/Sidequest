package dev.th7bo.sidequest.platform.chat

import dev.th7bo.sidequest.platform.event.SidequestEvent

/**
 * What the chat rules turn lines into.
 *
 * These are the point of the whole layer. A feature that wants to know somebody joined the
 * party subscribes to [PartyMemberJoinedEvent] and never learns that the line said
 * "joined the party." — so when Hypixel changes the wording, one pattern changes and no
 * feature does.
 *
 * Only events that a rule actually produces exist here. The rest of the catalogue in the
 * plan arrives with the service that emits it, because an event nobody fills is a guess at a
 * data model.
 */
public sealed class ChatDerivedEvent : SidequestEvent() {
    /** The line this was read from, for logging and for click inspection. */
    public abstract val message: ChatMessage
}

// -- player chat ------------------------------------------------------------

/**
 * Somebody said something, on a channel.
 *
 * [sender] is the name with the rank tags stripped, which is what code should compare
 * against; [senderDisplayName] keeps the tags for anything shown to a player.
 */
public class PlayerChatEvent(
    public val channel: ChatChannel,
    public val senderDisplayName: String,
    public val sender: String,
    public val content: String,
    override val message: ChatMessage,
) : ChatDerivedEvent() {
    override fun describe(): String = "$channel $sender: ${content.take(CONTENT_LIMIT)}"

    private companion object {
        const val CONTENT_LIMIT = 60
    }
}

/** Which channel a message came in on. */
public enum class ChatChannel {
    /** Public chat on the current server. */
    ALL,
    PARTY,
    GUILD,

    /** Co-op chat, shared with the profile's members. */
    COOP,

    /** A private message someone sent us. */
    PRIVATE_INCOMING,

    /** A private message we sent. */
    PRIVATE_OUTGOING,

    /** An NPC line. Hypixel formats these as chat, and they trigger real features. */
    NPC,
}

// -- party ------------------------------------------------------------------

/**
 * Someone invited us to their party.
 *
 * [acceptCommand] comes from the click action on the line rather than being built from
 * [inviter], because that is what the prompt will actually run and it survives rewordings.
 * Null when the line carried no clickable accept — build `/party accept <inviter>` then.
 */
public class PartyInviteEvent(
    public val inviter: String,
    public val acceptCommand: String?,
    override val message: ChatMessage,
) : ChatDerivedEvent() {
    override fun describe(): String = "invite from $inviter"
}

/** A pending invite from [inviter] timed out. */
public class PartyInviteExpiredEvent(
    public val inviter: String,
    override val message: ChatMessage,
) : ChatDerivedEvent()

/** We joined [leader]'s party. */
public class PartyJoinedEvent(
    public val leader: String,
    override val message: ChatMessage,
) : ChatDerivedEvent() {
    override fun describe(): String = "joined ${leader}'s party"
}

/** Somebody else joined the party we are in. */
public class PartyMemberJoinedEvent(
    public val member: String,
    override val message: ChatMessage,
) : ChatDerivedEvent() {
    override fun describe(): String = "$member joined the party"
}

/** Somebody else left, or was removed from, the party we are in. */
public class PartyMemberLeftEvent(
    public val member: String,
    public val reason: PartyLeaveReason,
    override val message: ChatMessage,
) : ChatDerivedEvent() {
    override fun describe(): String = "$member left the party ($reason)"
}

/** Why a member is no longer in the party. Distinguished because features react differently. */
public enum class PartyLeaveReason {
    LEFT,
    KICKED,

    /** Removed for being offline when the party was formed. */
    OFFLINE,

    /** Dropped because they disconnected. */
    DISCONNECTED,
}

/** The party was disbanded. [by] is null when Hypixel did not name who. */
public class PartyDisbandedEvent(
    public val by: String?,
    override val message: ChatMessage,
) : ChatDerivedEvent()

/** We were removed from the party. */
public class PartyKickedEvent(
    override val message: ChatMessage,
) : ChatDerivedEvent()

/** Leadership moved. [previousLeader] is null when the line did not say. */
public class PartyLeaderChangedEvent(
    public val newLeader: String,
    public val previousLeader: String?,
    override val message: ChatMessage,
) : ChatDerivedEvent() {
    override fun describe(): String = "party leader is now $newLeader"
}

// -- drops ------------------------------------------------------------------

/**
 * A rare drop line.
 *
 * One event for every rarity rather than one per tier: features filter on [rarity], and a
 * class per tier would mean every listener enumerating them.
 */
public class RareDropEvent(
    public val itemName: String,
    public val rarity: DropRarity,
    public val amount: Int = 1,
    /** Magic Find shown on the line, as a percentage. Null when it did not say. */
    public val magicFindPercent: Double? = null,
    override val message: ChatMessage,
) : ChatDerivedEvent() {
    override fun describe(): String = "$rarity ${amount}x $itemName"
}

/**
 * How rare Hypixel said the drop was.
 *
 * The wording is Hypixel's, in their order of increasing rarity.
 */
public enum class DropRarity {
    RARE,
    VERY_RARE,
    CRAZY_RARE,
    INSANE_RARE,

    /** `PRAY TO RNGESUS DROP!` — the rarest tier, and yes that is what it says. */
    PRAY_TO_RNGESUS,

    /** A pet drop, which Hypixel announces on its own line. */
    PET,
    ;

    public companion object {
        /** Reads Hypixel's own wording, e.g. `VERY RARE`. Null when it is not one of them. */
        public fun ofWording(wording: String): DropRarity? = when (wording.trim().uppercase()) {
            "RARE" -> RARE
            "VERY RARE" -> VERY_RARE
            "CRAZY RARE" -> CRAZY_RARE
            "INSANE RARE" -> INSANE_RARE
            "PRAY TO RNGESUS" -> PRAY_TO_RNGESUS
            "PET" -> PET
            else -> null
        }
    }
}

// -- progression ------------------------------------------------------------

/** A skill levelled up. [skill] is Hypixel's display name, e.g. `Farming`. */
public class SkillLevelUpEvent(
    public val skill: String,
    public val oldLevel: Int,
    public val newLevel: Int,
    override val message: ChatMessage,
) : ChatDerivedEvent() {
    override fun describe(): String = "$skill $oldLevel to $newLevel"
}

// -- content completion ----------------------------------------------------

/**
 * A dungeon run finished.
 *
 * [floor] is Hypixel's own wording — `Floor V`, or `Entrance` — rather than a number,
 * because the two are not the same thing and converting loses the entrance.
 */
public class DungeonCompletedEvent(
    public val floor: String,
    public val isMasterMode: Boolean,
    override val message: ChatMessage,
) : ChatDerivedEvent() {
    override fun describe(): String = (if (isMasterMode) "M" else "F") + floor
}

/** Kuudra went down. The tier is not on the line; read it from the game context. */
public class KuudraCompletedEvent(
    override val message: ChatMessage,
) : ChatDerivedEvent()

// -- auctions ---------------------------------------------------------------

/** Somebody outbid us. */
public class AuctionOutbidEvent(
    public val bidder: String,
    public val itemName: String,
    public val coins: Long,
    override val message: ChatMessage,
) : ChatDerivedEvent() {
    override fun describe(): String = "$bidder outbid us on $itemName"
}

/** One of our auctions sold. */
public class AuctionSoldEvent(
    public val buyer: String,
    public val itemName: String,
    public val coins: Long,
    override val message: ChatMessage,
) : ChatDerivedEvent() {
    override fun describe(): String = "$buyer bought $itemName for $coins"
}
