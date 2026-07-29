package dev.th7bo.sidequest.platform.core.command

import dev.th7bo.sidequest.platform.command.CommandCollisionException
import dev.th7bo.sidequest.platform.command.CommandRegistry
import dev.th7bo.sidequest.platform.command.CommandSpec
import dev.th7bo.sidequest.platform.command.RegisteredCommand
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.lifecycle.Registration

/**
 * The command registry.
 *
 * Names are claimed exclusively, aliases included. Two features quietly fighting over
 * `/sq` produces behaviour that depends on load order, which is the kind of bug that
 * only reproduces on someone else's machine.
 *
 * [onRegistered] fires when a command is added, so the Minecraft adapter can put it in
 * the game's command tree. There is no matching removal hook on purpose: the adapter
 * resolves through this registry when a command runs, so removing it here is already
 * enough to stop it responding. The registry has no idea Brigadier exists.
 */
public class DefaultCommandRegistry(
    private val onRegistered: (RegisteredCommand) -> Unit = {},
) : CommandRegistry {

    private val byName = LinkedHashMap<String, RegisteredCommand>()
    private val registered = LinkedHashSet<RegisteredCommand>()

    override fun register(owner: OwnerId, spec: CommandSpec): Registration {
        for (name in spec.allNames) {
            byName[name.lowercase()]?.let { existing ->
                throw CommandCollisionException(name, existing.owner, owner)
            }
        }

        val command = RegisteredCommand(owner, spec)
        registered.add(command)
        for (name in spec.allNames) byName[name.lowercase()] = command
        onRegistered(command)

        return Registration {
            if (registered.remove(command)) {
                for (name in spec.allNames) byName.remove(name.lowercase())
            }
        }
    }

    override fun all(): List<RegisteredCommand> = registered.toList()

    override fun get(name: String): RegisteredCommand? = byName[name.removePrefix("/").lowercase()]

    override fun unregisterAll(owner: OwnerId) {
        val removed = registered.filter { it.owner == owner }
        if (removed.isEmpty()) return
        registered.removeAll(removed.toSet())
        for (command in removed) {
            for (name in command.spec.allNames) byName.remove(name.lowercase())
        }
    }
}
