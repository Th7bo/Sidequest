package dev.th7bo.sidequest.platform.command

import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.lifecycle.Registration

/**
 * A client command.
 *
 * Declared as data rather than registered against Brigadier directly, so the same
 * declaration can be listed in the inspector, checked for collisions across features,
 * and re-registered when the client rebuilds its command tree — none of which is
 * possible once a feature has handed a lambda straight to the game.
 */
public data class CommandSpec(
    /** Without a leading slash. */
    public val name: String,
    public val aliases: Set<String> = emptySet(),
    public val description: String = "",
    /** Shown when the handler reports bad input, e.g. `<player> [amount]`. */
    public val usage: String = "",
    /**
     * Left out of the mod's own listings. For the commands that exist only so a chat component has something
     * to click.
     *
     * Not "hidden from completion", which is what this once claimed. A client command is a literal node in
     * Brigadier's tree and the client completes its own tree, so leaving the node out is the only way to hide
     * one — and that would stop the chat click working, which is the whole reason such a command exists.
     */
    public val isHidden: Boolean = false,
    /**
     * Whether this takes arguments at all.
     *
     * False means the command is a bare word, and the game is told so: `/sqdiag foo` is then rejected as bad
     * input rather than silently ignored, and the client stops offering an `<arguments>` hint for a command
     * that has none. Declared rather than guessed from whether a handler happens to read its list, because a
     * guess would be wrong exactly where it mattered.
     */
    public val takesArguments: Boolean = false,
    /**
     * Suggestions for the next word, given the words already finished.
     *
     * The first call gets an empty list; the call for the second word gets the first word, and so on — so a
     * command dispatches on `arguments.size` and `arguments.first()` rather than tracking a cursor. Filtering
     * by the partial word being typed is the bridge's job and not this function's, which is what lets a
     * completion source be a plain list.
     */
    public val completions: (arguments: List<String>) -> List<String> = { emptyList() },
    public val handler: (arguments: List<String>) -> Unit,
) {
    init {
        require(name.isNotBlank()) { "A command needs a name" }
        require(!name.startsWith("/")) { "Command '$name' must not include the leading slash" }
        require(name.none { it.isWhitespace() }) { "Command '$name' must be a single word" }
        // A usage string on a command the game will refuse arguments for is a contradiction, and one that only
        // shows up when somebody types the usage and is told they are wrong.
        require(usage.isEmpty() || takesArguments) {
            "Command '$name' declares the usage '$usage' but not that it takes arguments"
        }
    }

    /** Every name this command answers to. */
    public val allNames: Set<String> get() = aliases + name
}

/**
 * Where commands are registered.
 *
 * Collisions are rejected rather than resolved: two features silently fighting over
 * `/sq` is far worse than a startup error naming both. Same rule as the component
 * registry in the UI framework, for the same reason.
 */
public interface CommandRegistry {

    public fun register(owner: OwnerId, spec: CommandSpec): Registration

    /** Everything registered, for the inspector and for building the game's command tree. */
    public fun all(): List<RegisteredCommand>

    /** Looks up by name or alias. */
    public operator fun get(name: String): RegisteredCommand?

    public fun unregisterAll(owner: OwnerId)
}

public data class RegisteredCommand(
    public val owner: OwnerId,
    public val spec: CommandSpec,
)

/** Two features asked for the same command name. */
public class CommandCollisionException(
    public val name: String,
    public val existing: OwnerId,
    public val attempted: OwnerId,
) : IllegalStateException(
    "Command '$name' is already registered by $existing; $attempted cannot also claim it",
)
