package dev.th7bo.sidequest.platform.core.player

import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.player.PlayerAction
import dev.th7bo.sidequest.platform.player.PlayerActionContext
import dev.th7bo.sidequest.platform.player.PlayerActionEntry
import dev.th7bo.sidequest.platform.player.PlayerActionProvider
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.player.PlayerIdentity
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Gathering what every feature can do to a player.
 *
 * The registry itself decides almost nothing — which is the point of it — so what is worth testing is the
 * handful of promises the menu relies on: a stable order, no duplicates, and that one broken contributor
 * cannot stop the menu opening.
 */
class DefaultPlayerActionRegistryTest {

    private val registry = DefaultPlayerActionRegistry(NoopLogger)

    private val owner = OwnerId(SqId.sidequest("test"))

    private val context = PlayerActionContext(
        player = PlayerIdentity(id = PlayerId("00000000-0000-0000-0000-00000000000a"), username = "Alice"),
    )

    private var ran = mutableListOf<String>()

    private fun entry(
        name: String,
        order: Int = PlayerAction.DEFAULT_ORDER,
        destructive: Boolean = false,
    ) = PlayerActionEntry(
        action = PlayerAction(
            // Lowercased, because `SqId` rejects anything else — while the label keeps its case, which is
            // the point of the ordering test below: the tie-break has to ignore case or a capitalised label
            // sorts above every lowercase one and the menu reads as unsorted.
            id = SqId.sidequest("action.${name.lowercase()}"),
            label = name,
            order = order,
            isDestructive = destructive,
        ),
        run = { ran.add(name) },
    )

    private fun provider(vararg entries: PlayerActionEntry) = PlayerActionProvider { entries.toList() }

    private fun labels() = registry.actionsFor(context).map { it.action.label }

    // -- gathering -----------------------------------------------------------

    @Test
    fun `nothing registered offers nothing`() {
        assertTrue(registry.actionsFor(context).isEmpty())
    }

    @Test
    fun `every provider contributes`() {
        registry.register(owner, provider(entry("ping")))
        registry.register(owner, provider(entry("invite")))

        assertEquals(setOf("ping", "invite"), labels().toSet())
    }

    @Test
    fun `a removed provider stops contributing`() {
        val registration = registry.register(owner, provider(entry("ping")))
        registry.register(owner, provider(entry("invite")))

        registration.cancel()

        assertEquals(listOf("invite"), labels())
    }

    /** A provider that has nothing to offer for this player is normal, not an error. */
    @Test
    fun `a provider offering nothing is fine`() {
        registry.register(owner, provider())
        registry.register(owner, provider(entry("ping")))

        assertEquals(listOf("ping"), labels())
    }

    // -- the promises the menu relies on -------------------------------------

    /**
     * One broken provider does not stop the menu opening.
     *
     * The menu is how somebody reaches eleven other features. A contributor that throws taking all of them
     * out would turn one feature's bug into every feature being unreachable.
     */
    @Test
    fun `a provider that throws is skipped and the rest still appear`() {
        registry.register(owner) { error("this provider is broken") }
        registry.register(owner, provider(entry("ping")))

        assertEquals(listOf("ping"), labels())
    }

    @Test
    fun `order decides position, then the label`() {
        registry.register(owner, provider(entry("zebra", order = 1)))
        registry.register(owner, provider(entry("Beta", order = 5), entry("alpha", order = 5)))

        assertEquals(listOf("zebra", "alpha", "Beta"), labels())
    }

    /**
     * Destructive actions sort last regardless of their order.
     *
     * So "remove" never lands where "invite" was a moment ago. A menu whose dangerous button moves under the
     * cursor is one that eventually gets clicked by accident.
     */
    @Test
    fun `a destructive action goes last however low its order`() {
        registry.register(owner, provider(entry("remove", order = 0, destructive = true)))
        registry.register(owner, provider(entry("ping", order = 500)))

        assertEquals(listOf("ping", "remove"), labels())
    }

    /** Two features offering one action is a developer's mistake, not something to show twice. */
    @Test
    fun `the same action from two providers appears once`() {
        registry.register(owner, provider(entry("ping")))
        registry.register(owner, provider(entry("ping")))

        assertEquals(listOf("ping"), labels())
    }

    /** Running an action runs the provider's own block, not something the registry reconstructed. */
    @Test
    fun `an action runs what its provider supplied`() {
        registry.register(owner, provider(entry("ping")))

        registry.actionsFor(context).single().run()

        assertEquals(listOf("ping"), ran)
    }

    /** The context reaches the provider unchanged, since every decision it makes is based on it. */
    @Test
    fun `the context is handed to providers as given`() {
        var seen: PlayerActionContext? = null
        registry.register(owner) { given -> seen = given; emptyList() }

        val asked = context.copy(isFriend = true, isOnline = true, isPartyLeader = true)
        registry.actionsFor(asked)

        assertEquals(asked, seen)
    }
}
