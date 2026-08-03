package dev.th7bo.sidequest.platform.player

import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.lifecycle.Registration

/**
 * Something you can do to a player.
 *
 * The plan asks for a player action menu opened from five places — a keybind on the crosshair, a command,
 * the friend hub, a clicked name in chat, the party list — offering seventeen actions that belong to a dozen
 * different features. And it asks for one thing about how: *"actions should register as modular providers."*
 *
 * That instruction is the whole design. A menu with a hardcoded list would have to import the debt tracker,
 * the soundboard, the cosmetics and the achievements to offer their actions — every feature reachable from
 * one screen, which is the shape that makes a mod impossible to take apart. Instead each feature contributes
 * what it can do and the menu knows none of them by name. It also means the menu is never full of buttons
 * for features that do not exist yet: an unbuilt feature simply registers nothing.
 */
public data class PlayerAction(
    public val id: SqId,
    public val label: String,
    public val description: String = "",
    public val iconId: SqId? = null,
    /**
     * Where it sits. Lower first, ties broken by label.
     *
     * Explicit rather than registration order, because registration order is whatever the feature list
     * happens to be that week — and a menu whose buttons move between sessions is one nobody builds muscle
     * memory for.
     */
    public val order: Int = DEFAULT_ORDER,
    /** Drawn as a warning, and sorted last whatever its order says. */
    public val isDestructive: Boolean = false,
) {
    public companion object {
        public const val DEFAULT_ORDER: Int = 100
    }
}

/**
 * What a provider is told about the player, so it can decide what to offer.
 *
 * Assembled once by the registry and handed to every provider, rather than each one asking the services
 * itself. Two reasons: a provider becomes a pure function of this and is therefore testable without a game,
 * and the answers cannot differ between providers — one offering "remove friend" while another offers "add
 * friend" for the same person would be two readings of one question.
 */
public data class PlayerActionContext(
    public val player: PlayerIdentity,
    /**
     * Whether this is the player themselves.
     *
     * Almost every action is nonsense aimed at yourself, and checking it once here is better than each
     * provider remembering to. Nothing stops a provider offering something for the local player — viewing
     * your own profile is reasonable — but it has to say so deliberately.
     */
    public val isSelf: Boolean = false,
    public val isFriend: Boolean = false,
    public val isInParty: Boolean = false,
    public val isOnline: Boolean = false,
    /** Whether the local player leads the party, since inviting and kicking need it. */
    public val isPartyLeader: Boolean = false,
)

/** An action, and what it does. */
public class PlayerActionEntry(
    public val action: PlayerAction,
    /**
     * Performs it.
     *
     * Takes nothing: the provider closed over the target when it offered the action, so nothing between
     * offering and running can substitute a different player. A menu that passed the target back in would
     * be a menu that could get it wrong.
     */
    public val run: () -> Unit,
)

/**
 * Contributes actions for a player.
 *
 * Returns the ones that make sense for [context] and nothing else. An action that is offered and then
 * refuses is worse than one that was never offered — the person clicking it has already decided.
 */
public fun interface PlayerActionProvider {
    public fun actionsFor(context: PlayerActionContext): List<PlayerActionEntry>
}

/**
 * Where providers register, and where the menu asks.
 *
 * The only thing the menu and the features share. Neither knows the other exists.
 */
public interface PlayerActionRegistry {

    /** Adds a provider. Disposing the registration removes it, so a disabled feature stops contributing. */
    public fun register(owner: OwnerId, provider: PlayerActionProvider): Registration

    /**
     * Everything on offer for this player, ordered.
     *
     * A provider that throws is skipped rather than taking the menu with it: one broken feature must not
     * make the whole menu fail to open, because the menu is how you reach the other eleven.
     */
    public fun actionsFor(context: PlayerActionContext): List<PlayerActionEntry>
}
