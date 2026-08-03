package dev.th7bo.sidequest.platform.core.player

import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.lifecycle.Registration
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.player.PlayerActionContext
import dev.th7bo.sidequest.platform.player.PlayerActionEntry
import dev.th7bo.sidequest.platform.player.PlayerActionProvider
import dev.th7bo.sidequest.platform.player.PlayerActionRegistry
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Collects what every feature can do to a player.
 *
 * Small on purpose. All the interesting decisions are in the providers — this only has to gather them, put
 * them in a stable order, and refuse to let one bad one take the menu down.
 *
 * Thread-safe by using a copy-on-write list: providers are registered on the client thread as features start
 * and read on the client thread when a menu opens, but a feature enabled from a background job would
 * otherwise be a concurrent modification while somebody has the menu open.
 */
public class DefaultPlayerActionRegistry(
    private val log: Logger,
) : PlayerActionRegistry {

    private val providers = CopyOnWriteArrayList<Registered>()

    private class Registered(val owner: OwnerId, val provider: PlayerActionProvider)

    override fun register(owner: OwnerId, provider: PlayerActionProvider): Registration {
        val entry = Registered(owner, provider)
        providers.add(entry)
        return Registration { providers.remove(entry) }
    }

    /**
     * Everything on offer, in a stable order.
     *
     * **A provider that throws is skipped, not fatal.** The menu is how somebody reaches eleven other
     * features, and letting one broken contributor make it fail to open would take all of them out. The
     * failure is logged rather than swallowed silently — a provider that never appears and never complains
     * is worse than one that appears wrong.
     */
    override fun actionsFor(context: PlayerActionContext): List<PlayerActionEntry> {
        val gathered = ArrayList<PlayerActionEntry>()
        for (registered in providers) {
            val offered = runCatching { registered.provider.actionsFor(context) }
                .onFailure { log.warn(it) { "A player action provider from ${registered.owner} failed" } }
                .getOrNull()
                ?: continue
            gathered.addAll(offered)
        }

        return gathered
            // Keyed by id, first registration wins. Two features offering the same action is a mistake, and
            // showing it twice would make the mistake the user's problem rather than the developer's.
            .distinctBy { it.action.id }
            .sortedWith(
                // Destructive last whatever its order, so "remove" never lands where "invite" was a moment
                // ago. The order field decides everything above that; the label breaks ties, because
                // registration order is whatever the feature list happens to be that week.
                compareBy<PlayerActionEntry> { it.action.isDestructive }
                    .thenBy { it.action.order }
                    .thenBy { it.action.label.lowercase() },
            )
    }

    /** How many providers are registered. For diagnostics. */
    public val providerCount: Int get() = providers.size
}
