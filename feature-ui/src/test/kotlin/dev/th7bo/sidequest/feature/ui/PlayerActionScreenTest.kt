package dev.th7bo.sidequest.feature.ui

import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.player.PlayerAction
import dev.th7bo.sidequest.platform.player.PlayerActionContext
import dev.th7bo.sidequest.platform.player.PlayerActionEntry
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.player.PlayerIdentity
import dev.th7bo.sidequest.ui.config.ConfigScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The player action menu.
 *
 * The screen decides almost nothing — the providers decide what is on offer and the registry orders it — so
 * what is worth testing is that it draws whatever it is handed without needing to understand any of it, and
 * that it cannot be taken down by what two unrelated features happened to name their actions.
 */
class PlayerActionScreenTest {

    private val alice = PlayerId("00000000-0000-0000-0000-00000000000a")

    private var ran = mutableListOf<String>()
    private var closed = 0

    private fun context(
        name: String = "Alice",
        nickname: String? = null,
        isSelf: Boolean = false,
        isFriend: Boolean = false,
        isInParty: Boolean = false,
        isOnline: Boolean = false,
    ) = PlayerActionContext(
        player = PlayerIdentity(id = alice, username = name, nickname = nickname),
        isSelf = isSelf,
        isFriend = isFriend,
        isInParty = isInParty,
        isOnline = isOnline,
    )

    private fun entry(id: String, label: String, destructive: Boolean = false) = PlayerActionEntry(
        action = PlayerAction(
            id = SqId.sidequest("action.$id"),
            label = label,
            isDestructive = destructive,
        ),
        run = { ran.add(label) },
    )

    private fun build(
        entries: List<PlayerActionEntry>,
        context: PlayerActionContext = context(),
    ): ConfigScreen = buildPlayerActionScreen(context, entries, onChosen = { closed++ })

    private fun buttons(screen: ConfigScreen) = screen.settings.map { it.id.value }

    // -- drawing what it is handed -------------------------------------------

    @Test
    fun `every action gets a button`() {
        val screen = build(listOf(entry("invite", "Invite to party"), entry("ping", "Ping their position")))

        assertEquals(2, screen.settings.size)
    }

    /**
     * The order it was handed is the order it draws.
     *
     * The registry already sorted them, and a screen that sorted again would be a second opinion about
     * where the destructive button goes — which is the one place a disagreement gets somebody clicking the
     * wrong thing.
     */
    @Test
    fun `the given order is preserved`() {
        val screen = build(listOf(entry("b", "Second"), entry("a", "First")))

        assertEquals(listOf("Second", "First"), screen.settings.map { it.metadata.title.peek() })
    }

    @Test
    fun `choosing an action runs it and then closes`() {
        val screen = build(listOf(entry("invite", "Invite to party")))

        (screen.settings.single() as dev.th7bo.sidequest.ui.config.ButtonSetting).invoke()

        assertEquals(listOf("Invite to party"), ran)
        assertEquals(1, closed)
    }

    // -- the failure it must not have ----------------------------------------

    /**
     * Two features naming their actions similarly must not take the menu down.
     *
     * Ids here are positional for exactly this reason. A duplicate id throws, and on *this* screen that does
     * not cost one row — it makes every action unreachable, including the ones from the features that did
     * nothing wrong.
     */
    @Test
    fun `actions whose ids clean up to the same text still all draw`() {
        val screen = build(
            listOf(
                entry("party.invite", "Invite"),
                entry("party_invite", "Invite again"),
                entry("partyinvite", "Invite once more"),
            ),
        )

        val ids = buttons(screen)
        assertEquals(3, ids.size)
        assertEquals(ids.size, ids.distinct().size, "collided: $ids")
    }

    /** Nothing on offer is a real state, not a broken screen. */
    @Test
    fun `no actions still builds and says so`() {
        val screen = build(emptyList())

        assertTrue(screen.categories.first().sections.any { it.title.peek() == "Nothing to do" })
        // Still closeable. A menu with no way out is worse than one with nothing in it.
        assertEquals(1, screen.settings.size)
    }

    @Test
    fun `looking at yourself says so`() {
        val screen = build(emptyList(), context(isSelf = true))

        val section = screen.categories.first().sections.first { it.title.peek() == "Nothing to do" }
        assertTrue(section.description?.peek()?.contains("This is you") == true)
    }

    // -- what it says --------------------------------------------------------

    @Test
    fun `the screen is titled with who it is about`() {
        assertEquals("Alice", build(emptyList()).title.peek())
    }

    /** A nickname titles the screen, and the real name goes underneath so they can be matched up. */
    @Test
    fun `a nicknamed player shows both names`() {
        val screen = build(emptyList(), context(name = "Alice", nickname = "Ace"))

        assertEquals("Ace", screen.title.peek())
        assertTrue(screen.description?.peek()?.contains("Alice") == true, screen.description?.peek())
    }

    @Test
    fun `the relationship is described`() {
        val screen = build(emptyList(), context(isFriend = true, isInParty = true, isOnline = true))

        val described = screen.description?.peek().orEmpty()
        assertTrue(described.contains("Online"), described)
        assertTrue(described.contains("friend"), described)
        assertTrue(described.contains("party"), described)
    }

    @Test
    fun `somebody offline and unrelated is described plainly`() {
        val described = build(emptyList()).description?.peek().orEmpty()

        assertEquals("Offline", described)
        assertFalse(described.contains("friend"))
    }
}
