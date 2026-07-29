package dev.th7bo.sidequest.platform.minecraft

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.th7bo.sidequest.platform.command.CommandRegistry
import dev.th7bo.sidequest.platform.command.CommandSpec
import dev.th7bo.sidequest.platform.log.Logger
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component
import java.util.concurrent.CompletableFuture

/**
 * Makes the platform's commands into real client commands.
 *
 * Without this the [CommandRegistry] is bookkeeping: it knows a feature asked for
 * `/sqdiag` and the game does not, so typing it produces "Unknown command". That was the
 * shipped state until a player typed one, and the in-game test missed it because it
 * asserted the *registry* contained the command rather than that the *game* would run it.
 *
 * Two properties of Brigadier shape the design.
 *
 * **Nodes cannot be removed.** So a node holds only a name and looks the command up in
 * the registry each time it runs. A disabled feature therefore stops answering
 * immediately even though its node is still in the tree — capturing the handler in the
 * node would leave a disabled feature responding, which is the one guarantee the whole
 * ownership design exists to provide.
 *
 * **The tree is built per connection.** The callback covers everything registered before
 * the join, which is every feature enabled at startup. A feature enabled later is
 * injected into the dispatcher the callback handed over.
 */
class MinecraftCommandBridge(
    private val registry: CommandRegistry,
    private val log: Logger,
) {

    private var installed = false

    /**
     * The dispatcher for the current connection.
     *
     * Kept so a command registered after the join can still be added. Cleared implicitly
     * by the next callback, which brings a fresh dispatcher — the tree is rebuilt per
     * connection, so holding the old one across a server hop would register into
     * something nothing reads.
     */
    private var dispatcher: CommandDispatcher<FabricClientCommandSource>? = null

    /** Names in the current tree, so re-registering does not stack duplicate nodes. */
    private val registeredNames = HashSet<String>()

    fun install() {
        check(!installed) { "The command bridge is already installed" }
        installed = true

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            this.dispatcher = dispatcher
            registeredNames.clear()
            for (command in registry.all()) {
                for (name in command.spec.allNames) register(dispatcher, name)
            }
            log.debug { "Registered ${registeredNames.size} client command name(s)" }
        }
    }

    /**
     * Adds [spec] to the live command tree, if there is one.
     *
     * Before the first join there is no dispatcher and nothing to do; the callback picks
     * the command up then.
     */
    fun onRegistered(spec: CommandSpec) {
        val target = dispatcher ?: return
        for (name in spec.allNames) register(target, name)
        log.debug { "Added /${spec.name} to the live command tree" }
    }

    private fun register(dispatcher: CommandDispatcher<FabricClientCommandSource>, name: String) {
        if (!registeredNames.add(name)) return
        dispatcher.register(node(name))
    }

    private fun node(name: String): LiteralArgumentBuilder<FabricClientCommandSource> =
        LiteralArgumentBuilder.literal<FabricClientCommandSource>(name)
            // Bare, with no arguments.
            .executes { context -> run(context, name, emptyList()) }
            .then(
                // One greedy argument rather than a typed grammar. The platform's command
                // model is a name and a list of words, so a richer grammar here would be a
                // second, competing definition of what a command is.
                RequiredArgumentBuilder
                    .argument<FabricClientCommandSource, String>(ARGUMENTS, StringArgumentType.greedyString())
                    .suggests { context, builder -> suggest(name, context, builder) }
                    .executes { context ->
                        val typed = StringArgumentType.getString(context, ARGUMENTS)
                        run(context, name, typed.split(' ').filter { it.isNotEmpty() })
                    },
            )

    /**
     * Runs a handler, resolved now rather than when the node was built.
     *
     * A handler that throws is reported and the command still counts as handled. Letting
     * it escape surfaces as a Brigadier parse error, which tells the player their command
     * was wrong when in fact the mod was.
     */
    private fun run(
        context: CommandContext<FabricClientCommandSource>,
        name: String,
        arguments: List<String>,
    ): Int {
        val command = registry[name]
        if (command == null) {
            // Its feature has been disabled since the tree was built. Said plainly rather
            // than silently doing nothing, which reads as the mod being broken.
            context.source.sendError(Component.literal("/$name is unavailable — its feature is disabled."))
            return SUCCESS
        }

        try {
            command.spec.handler(arguments)
        } catch (thrown: Throwable) {
            log.error(thrown) { "/$name failed" }
            context.source.sendError(Component.literal("/$name failed. See the log for details."))
        }
        return SUCCESS
    }

    private fun suggest(
        name: String,
        context: CommandContext<FabricClientCommandSource>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val spec = registry[name]?.spec ?: return builder.buildFuture()

        val typed = runCatching { StringArgumentType.getString(context, ARGUMENTS) }.getOrDefault("")
        val words = typed.split(' ')
        // Only the word being typed is completed, so a suggestion replaces that word
        // rather than the whole argument.
        val partial = words.last()
        val scoped = SuggestionsBuilder(builder.input, builder.start + typed.length - partial.length)

        for (suggestion in spec.completions(words.dropLast(1))) {
            if (suggestion.startsWith(partial, ignoreCase = true)) scoped.suggest(suggestion)
        }
        return scoped.buildFuture()
    }

    private companion object {
        const val ARGUMENTS = "arguments"

        /** Brigadier's convention for "this ran". */
        const val SUCCESS = 1
    }
}
