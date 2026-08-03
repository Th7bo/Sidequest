package dev.th7bo.sidequest.feature.ui

import dev.th7bo.sidequest.platform.core.waypoint.WaypointBook
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import dev.th7bo.sidequest.platform.waypoint.SharedWaypoint
import dev.th7bo.sidequest.platform.waypoint.WaypointAudience
import dev.th7bo.sidequest.platform.waypoint.WaypointCollection
import dev.th7bo.sidequest.ui.config.ConfigScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Building the waypoint manager.
 *
 * This screen shipped three bugs that only a running client found — a duplicate id when two waypoints shared
 * a name, an id the grammar rejected outright, and every field frozen because its getter read a snapshot.
 * All three are the same kind of mistake and none of them was testable, because the builder lived in the mod
 * module where nothing can run. It lives here now, and these are the tests it should have had.
 */
class WaypointManagerScreenTest {

    private var book = WaypointBook()

    /** Applies edits to [book] the way the feature does, so a test can assert a round trip. */
    private val actions = WaypointActions(
        current = { book },
        edit = { id, change -> book.waypoint(id)?.let { book = book.withWaypoint(change(it)) } },
        delete = { id -> book = book.withoutWaypoint(id) },
        editCollection = { id, change -> book.collection(id)?.let { book = book.withCollection(change(it)) } },
        deleteCollection = { id -> book = book.withoutCollection(id) },
        addCollection = { book = book.withCollection(WaypointCollection(id = "new", name = "New")) },
        showAll = { shown -> book = book.withAllShown(shown) },
        reopen = { },
    )

    private fun waypoint(id: String, label: String, collection: String = "") = SharedWaypoint(
        id = id,
        label = label,
        location = SqLocation(Island.HUB, SqPosition(0.0, 70.0, 0.0)),
        collectionId = collection,
    )

    private fun build(): ConfigScreen = buildWaypointScreen(book, actions)

    // -- the ids -------------------------------------------------------------

    /**
     * Two waypoints with the same name.
     *
     * A section's id is derived from its title unless one is given, and a duplicate id *throws* — so this
     * took the whole screen down before a row was drawn. Two places called "test" is not a mistake anybody
     * needs protecting from; it is a Tuesday.
     */
    @Test
    fun `two waypoints with the same name both get a section`() {
        book = book
            .withWaypoint(waypoint("a", "test"))
            .withWaypoint(waypoint("b", "test"))

        val screen = build()

        val sections = screen.categories.flatMap { it.sections }
        assertEquals(2, sections.count { it.title.peek() == "test" })
        assertEquals(
            sections.size,
            sections.map { it.id }.distinct().size,
            "every section needs its own id",
        )
    }

    /**
     * An id that the id grammar rejects.
     *
     * Waypoints were once keyed `sidequest:wp/kx3l9a`, and `UiId` allows letters, digits, underscores and
     * dots only. Building a screen from a book saved back then must not throw — those books still exist.
     */
    @Test
    fun `an id from the old format does not break the screen`() {
        book = book.withWaypoint(waypoint("sidequest:wp/kx3l9a", "Bazaar"))

        val screen = build()

        // Its own section, by name — the count would also catch the "Everything" section that sits above it.
        assertTrue(screen.categories.first().sections.any { it.title.peek() == "Bazaar" })
    }

    /** Distinct ids that slug the same must still produce distinct screen ids. */
    @Test
    fun `ids that clean up to the same text do not collide`() {
        book = book
            .withWaypoint(waypoint("wp:1", "One"))
            .withWaypoint(waypoint("wp/1", "Two"))

        val ids = build().categories.flatMap { it.sections }.map { it.id }

        assertEquals(ids.size, ids.distinct().size, "collided: $ids")
    }

    @Test
    fun `two collections with the same name both get a category`() {
        book = book
            .withCollection(WaypointCollection(id = "x", name = "Dungeons"))
            .withCollection(WaypointCollection(id = "y", name = "Dungeons"))

        val screen = build()

        assertEquals(2, screen.categories.count { it.title.peek() == "Dungeons" })
        assertEquals(screen.categories.size, screen.categories.map { it.id }.distinct().size)
    }

    /** Every id on the screen, settings included, has to be unique — the builder rejects duplicates. */
    @Test
    fun `a busy book produces no duplicate ids at all`() {
        book = book.withCollection(WaypointCollection(id = "c1", name = "Same"))
            .withCollection(WaypointCollection(id = "c2", name = "Same"))
        repeat(6) { index ->
            book = book.withWaypoint(waypoint("w$index", "Same", collection = if (index % 2 == 0) "c1" else "c2"))
        }

        val screen = build()

        val everyId = screen.categories.map { it.id } +
            screen.categories.flatMap { category -> category.sections.map { it.id } } +
            screen.settings.map { it.id }
        assertEquals(everyId.size, everyId.distinct().size, "duplicates: ${everyId.groupBy { it }.filter { it.value.size > 1 }.keys}")
    }

    // -- the bindings --------------------------------------------------------

    /**
     * Typing writes through, and reading back shows what was typed.
     *
     * The frozen-field bug. `MirrorBinding.set` deliberately re-reads after writing, so a getter closed over
     * a stale snapshot silently undid every edit — the field looked dead when it was in fact working and
     * immediately reverting itself.
     */
    @Test
    fun `editing a name sticks`() {
        book = book.withWaypoint(waypoint("a", "Old"))
        val screen = build()

        val name = screen.settings.first { it.id.value.endsWith(".label") }
        @Suppress("UNCHECKED_CAST")
        (name.binding as dev.th7bo.sidequest.ui.binding.Binding<String>).set("New")

        assertEquals("New", book.waypoint("a")?.label, "the book took it")
        assertEquals("New", name.binding.state.peek(), "and the control shows it")
    }

    @Test
    fun `toggling a waypoint's visibility sticks`() {
        book = book.withWaypoint(waypoint("a", "Spot"))
        val screen = build()

        val shown = screen.settings.first { it.id.value.endsWith(".shown") }
        @Suppress("UNCHECKED_CAST")
        (shown.binding as dev.th7bo.sidequest.ui.binding.Binding<Boolean>).set(false)

        assertFalse(book.waypoint("a")!!.isVisible)
        assertEquals(false, shown.binding.state.peek())
    }

    @Test
    fun `changing the audience sticks`() {
        book = book.withWaypoint(waypoint("a", "Spot"))
        val screen = build()

        val audience = screen.settings.first { it.id.value.endsWith(".audience") }
        @Suppress("UNCHECKED_CAST")
        (audience.binding as dev.th7bo.sidequest.ui.binding.Binding<String>).set("friends")

        assertEquals(WaypointAudience.Friends, book.waypoint("a")?.audience)
    }

    @Test
    fun `renaming a collection sticks`() {
        book = book.withCollection(WaypointCollection(id = "c", name = "Old"))
        val screen = build()

        val name = screen.settings.first { it.id.value.contains("waypoints.c.") && it.id.value.endsWith(".name") }
        @Suppress("UNCHECKED_CAST")
        (name.binding as dev.th7bo.sidequest.ui.binding.Binding<String>).set("New")

        assertEquals("New", book.collection("c")?.name)
    }

    // -- the shape -----------------------------------------------------------

    @Test
    fun `an empty book still builds`() {
        val screen = build()

        assertNotNull(screen.categories.firstOrNull())
        assertTrue(screen.categories.first().sections.isNotEmpty(), "it should say there is nothing yet")
    }

    /** A waypoint in a collection belongs to that collection's category, not to All. */
    @Test
    fun `waypoints are filed under their collection`() {
        book = book
            .withCollection(WaypointCollection(id = "c", name = "Dungeons"))
            .withWaypoint(waypoint("a", "Loose"))
            .withWaypoint(waypoint("b", "Filed", collection = "c"))

        val screen = build()

        val all = screen.categories.first { it.title.peek() == "All" }
        val dungeons = screen.categories.first { it.title.peek() == "Dungeons" }
        assertTrue(all.sections.any { it.title.peek() == "Loose" })
        assertFalse(all.sections.any { it.title.peek() == "Filed" })
        assertTrue(dungeons.sections.any { it.title.peek() == "Filed" })
    }

    /** A hidden waypoint says so on its collapsed header, since that is the only place you would see it. */
    @Test
    fun `a hidden waypoint is marked on its header`() {
        book = book.withWaypoint(waypoint("a", "Spot").copy(isVisible = false))

        val section = build().categories.first().sections.first { it.title.peek() == "Spot" }

        assertTrue(
            section.description?.peek()?.contains("hidden") == true,
            "expected the header to say so, was ${section.description?.peek()}",
        )
    }
}
