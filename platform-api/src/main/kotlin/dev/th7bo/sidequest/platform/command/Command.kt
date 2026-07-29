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
    /** Hidden from completion. For developer commands. */
    public val isHidden: Boolean = false,
    /** Suggestions for the argument at a given index, for tab completion. */
    public val completions: (arguments: List<String>) -> List<String> = { emptyList() },
    public val handler: (arguments: List<String>) -> Unit,
) {
    init {
        require(name.isNotBlank()) { "A command needs a name" }
        require(!name.startsWith("/")) { "Command '$name' must not include the leading slash" }
        require(name.none { it.isWhitespace() }) { "Command '$name' must be a single word" }
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
