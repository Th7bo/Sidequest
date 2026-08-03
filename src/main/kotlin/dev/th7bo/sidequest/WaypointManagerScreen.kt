package dev.th7bo.sidequest

import dev.th7bo.sidequest.platform.core.waypoint.WaypointBook
import dev.th7bo.sidequest.platform.waypoint.SharedWaypoint
import dev.th7bo.sidequest.platform.waypoint.WaypointAudience
import dev.th7bo.sidequest.platform.waypoint.WaypointCollection
import dev.th7bo.sidequest.ui.binding.bind
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.config.option
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.minecraft.MinecraftIcons
import dev.th7bo.sidequest.ui.rendering.Color

/**
 * What the waypoint screen can do to the book behind it.
 *
 * A handful of callbacks rather than the feature itself, because the screen is built from a *snapshot*: the
 * rows describe waypoints as they were when it opened, and every edit has to go somewhere that knows how to
 * save and redraw. Passing the feature would let the screen read a book that had moved on from the one it
 * drew.
 */
public class WaypointActions(
    /**
     * The book as it is *now*.
     *
     * Every value on the screen reads through this rather than from the snapshot the rows were described
     * from. Binding a getter to a captured object was the bug that made every field look frozen: typing wrote
     * through fine, and then the control re-read the stale copy it had closed over and drew the old value
     * back. Structure comes from the snapshot; contents come from here.
     */
    public val current: () -> WaypointBook,
    public val edit: (id: String, change: (SharedWaypoint) -> SharedWaypoint) -> Unit,
    public val delete: (id: String) -> Unit,
    public val editCollection: (id: String, change: (WaypointCollection) -> WaypointCollection) -> Unit,
    public val deleteCollection: (id: String) -> Unit,
    public val addCollection: () -> Unit,
    /** Sets every waypoint's own switch. Collections keep theirs. */
    public val showAll: (shown: Boolean) -> Unit,
    /** Rebuilds and reopens. For the edits that change the screen's own shape. */
    public val reopen: () -> Unit,
)

/**
 * The waypoint manager.
 *
 * **Built as a configuration screen rather than as a bespoke one**, which is the whole reason it is fifty
 * lines of description instead of a thousand lines of layout. The config framework already has a sidebar,
 * search across every row, collapsible sections, dropdowns, colour pickers, text fields and destructive
 * buttons — and every one of them is already tested and already looks like the rest of the mod. A hand-built
 * screen would be a second, worse copy of all of it.
 *
 * It is rebuilt from the book each time it opens, so the structure is a snapshot. Edits that change a row's
 * *contents* — a label, a colour, an audience — write straight through and need nothing further. Edits that
 * change the screen's *shape* — deleting a waypoint, adding a collection — call [WaypointActions.reopen],
 * because a section cannot remove itself from a tree that was described before it existed.
 */
public fun buildWaypointScreen(book: WaypointBook, actions: WaypointActions): ConfigScreen {
    val loose = book.waypoints.filter { it.collectionId.isEmpty() }

    return configScreen(
        id("waypoints"),
        "Waypoints",
        "Places you saved, and who can see them.",
    ) {
        category(
            id("waypoints.all"),
            "All",
            description = "${book.waypoints.size} saved",
            icon = MinecraftIcons.waypoints,
        ) {
            if (book.waypoints.isEmpty()) {
                section("Nothing saved yet", description = "Stand somewhere and run /sqwp add <name>.") {
                    button(id("waypoints.help"), "How it works", label = "Got it") { }
                }
            }
            if (book.waypoints.isNotEmpty()) {
                section("Everything", description = "Applies to every waypoint's own switch", collapsible = true) {
                    button(id("waypoints.show_all"), "Show them all", label = "Show all") {
                        actions.showAll(true)
                        actions.reopen()
                    }
                    button(id("waypoints.hide_all"), "Hide them all", label = "Hide all") {
                        actions.showAll(false)
                        actions.reopen()
                    }
                }
            }
            for (waypoint in loose) waypointSection(waypoint, book, actions)
        }

        for (collection in book.collections.sortedBy { it.name.lowercase() }) {
            val contents = book.inCollection(collection.id)
            category(
                id("waypoints.c." + slug(collection.id)),
                collection.name,
                description = folderLabel(collection, contents.size),
                icon = MinecraftIcons.waypoints,
            ) {
                section("Collection", description = "Applies to everything in it", collapsible = true) {
                    textField(
                        id = id("waypoints.c.${slug(collection.id)}.name"),
                        title = "Name",
                        value = bind(
                            get = { liveCollection(actions, collection).name },
                            set = { value -> actions.editCollection(collection.id) { it.copy(name = value) } },
                            debugName = "collection.name",
                        ),
                    )
                    textField(
                        id = id("waypoints.c.${slug(collection.id)}.folder"),
                        title = "Folder",
                        description = "Slash-separated. Leave empty for the top level.",
                        value = bind(
                            get = { liveCollection(actions, collection).folder },
                            set = { value -> actions.editCollection(collection.id) { it.copy(folder = value.trim('/')) } },
                            debugName = "collection.folder",
                        ),
                        placeholder = "dungeons/f7",
                    )
                    toggle(
                        id = id("waypoints.c.${slug(collection.id)}.visible"),
                        title = "Show these",
                        description = "Off hides them without deleting anything",
                        value = bind(
                            get = { liveCollection(actions, collection).isVisible },
                            set = { value -> actions.editCollection(collection.id) { it.copy(isVisible = value) } },
                            debugName = "collection.visible",
                        ),
                    )
                    button(
                        id = id("waypoints.c.${slug(collection.id)}.delete"),
                        title = "Delete this collection",
                        label = "Delete",
                        // Said plainly on the button rather than behind a confirmation, because it is not a
                        // destructive action: the waypoints survive and move to All.
                        description = "The ${contents.size} waypoints in it move to All",
                        destructive = true,
                    ) {
                        actions.deleteCollection(collection.id)
                        actions.reopen()
                    }
                }

                for (waypoint in contents) waypointSection(waypoint, book, actions)
            }
        }

        category(id("waypoints.new"), "New", icon = MinecraftIcons.features) {
            section("Collections", description = "Group waypoints so you can hide or share them together") {
                button(id("waypoints.new.collection"), "Add a collection", label = "Add") {
                    actions.addCollection()
                    actions.reopen()
                }
            }
        }
    }
}

/**
 * One waypoint, as a collapsible section.
 *
 * Collapsed to start with. Somebody opening this screen is usually looking for one waypoint among many, and a
 * page of expanded forms is harder to scan than a list of names — the search box already reaches every field
 * inside them whether they are open or not.
 */
private fun dev.th7bo.sidequest.ui.config.CategoryBuilder.waypointSection(
    waypoint: SharedWaypoint,
    book: WaypointBook,
    actions: WaypointActions,
) {
    val prefix = "waypoints.w." + slug(waypoint.id)
    section(
        waypoint.label.ifBlank { "Unnamed" },
        description = describe(waypoint),
        icon = MinecraftIcons.waypoints,
        collapsible = true,
        startsCollapsed = true,
    ) {
        toggle(
            id = id("$prefix.shown"),
            title = "Show this one",
            description = "Only on your screen. Anyone you shared it with still has it.",
            value = bind(
                get = { live(actions, waypoint)?.isVisible ?: true },
                set = { value -> actions.edit(waypoint.id) { it.copy(isVisible = value) } },
                debugName = "waypoint.visible",
            ),
        )
        textField(
            id = id("$prefix.label"),
            title = "Name",
            value = bind(
                get = { live(actions, waypoint)?.label ?: waypoint.label },
                set = { value -> actions.edit(waypoint.id) { it.copy(label = value) } },
                debugName = "waypoint.label",
            ),
        )
        dropdown(
            id = id("$prefix.audience"),
            title = "Shared with",
            description = "Friends and party are worked out when somebody looks, not when you choose",
            value = bind(
                get = { (live(actions, waypoint) ?: waypoint).audience.key },
                set = { key -> actions.edit(waypoint.id) { it.copy(audience = audienceOf(key)) } },
                debugName = "waypoint.audience",
            ),
            options = AUDIENCE_OPTIONS,
        )
        dropdown(
            id = id("$prefix.collection"),
            title = "Collection",
            value = bind(
                get = { live(actions, waypoint)?.collectionId ?: waypoint.collectionId },
                set = { value -> actions.edit(waypoint.id) { it.copy(collectionId = value) } },
                debugName = "waypoint.collection",
            ),
            options = listOf(option("", "None", "")) +
                book.collections.map { option(it.id, it.name, it.id) },
        )
        colorPicker(
            id = id("$prefix.colour"),
            title = "Colour",
            value = bind(
                get = { Color(live(actions, waypoint)?.colour ?: DEFAULT_WAYPOINT_COLOUR) },
                set = { value -> actions.edit(waypoint.id) { it.copy(colour = value.argb) } },
                debugName = "waypoint.colour",
            ),
        )
        textField(
            id = id("$prefix.note"),
            title = "Note",
            description = "Only for you. A note never leaves this client, even when the waypoint is shared.",
            value = bind(
                get = { live(actions, waypoint)?.note.orEmpty() },
                set = { value -> actions.edit(waypoint.id) { it.copy(note = value.ifBlank { null }) } },
                debugName = "waypoint.note",
            ),
            placeholder = "he undercuts",
        )
        button(
            id = id("$prefix.delete"),
            title = "Delete",
            label = "Delete",
            destructive = true,
        ) {
            actions.delete(waypoint.id)
            actions.reopen()
        }
    }
}

/** This waypoint as it stands now, or null if it has since been deleted. */
private fun live(actions: WaypointActions, waypoint: SharedWaypoint): SharedWaypoint? =
    actions.current().waypoint(waypoint.id)

/** The same for a collection, falling back to the snapshot so a getter never has nothing to answer with. */
private fun liveCollection(actions: WaypointActions, collection: WaypointCollection): WaypointCollection =
    actions.current().collection(collection.id) ?: collection

/**
 * Any string, as a legal [UiId] path segment.
 *
 * The grammar allows letters, digits and underscores and nothing else, and a violation *throws* — which is
 * what a waypoint id carrying a colon and a slash did, taking the whole screen with it before a single row
 * was drawn. Ids are generated clean now; this is the guard that keeps an old book, or a future format, from
 * doing the same thing again.
 */
private fun slug(raw: String): String {
    val cleaned = raw.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_')
    return cleaned.ifEmpty { "unnamed" }
}

/** The line under a waypoint's name: where it is, and who can see it. */
private fun describe(waypoint: SharedWaypoint): String = buildString {
    append(waypoint.location.island.displayName)
    append(" · ")
    append(AUDIENCE_LABELS[waypoint.audience.key] ?: "Private")
    // Said on the collapsed header, because a hidden waypoint's whole problem is that you cannot see it —
    // having to open each one to find which is switched off would be the same problem again.
    if (!waypoint.isVisible) append(" · hidden")
}

private fun folderLabel(collection: WaypointCollection, count: Int): String {
    val where = collection.folder.ifEmpty { "Top level" }
    val hidden = if (collection.isVisible) "" else " · hidden"
    return "$where · $count$hidden"
}

/**
 * The audience as a plain string, and back.
 *
 * A dropdown needs a value it can compare and serialise, and [WaypointAudience] is a sealed hierarchy with a
 * data class in it — two `Selected` sets with the same members are equal, but the dropdown has no option for
 * an arbitrary set anyway. So the picker speaks in keys and the four it offers round-trip exactly.
 *
 * `Selected` deliberately has no entry: choosing specific people needs a player picker, and a dropdown that
 * silently emptied the list would be worse than not offering it here.
 */
private val WaypointAudience.key: String
    get() = when (this) {
        is WaypointAudience.Private -> "private"
        is WaypointAudience.Friends -> "friends"
        is WaypointAudience.Party -> "party"
        is WaypointAudience.Group -> "group"
        is WaypointAudience.Selected -> "selected"
    }

private fun audienceOf(key: String): WaypointAudience = when (key) {
    "friends" -> WaypointAudience.Friends
    "party" -> WaypointAudience.Party
    "group" -> WaypointAudience.Group
    else -> WaypointAudience.Private
}

private val AUDIENCE_LABELS = mapOf(
    "private" to "Private",
    "friends" to "Friends",
    "party" to "Party",
    "group" to "The group",
    "selected" to "Chosen people",
)

private val AUDIENCE_OPTIONS = listOf(
    option("private", "Only me", "private"),
    option("friends", "My friends", "friends"),
    option("party", "My party", "party"),
    option("group", "The group", "group"),
)

/** White. A waypoint with no colour of its own is not a waypoint with an opinion about colour. */
private const val DEFAULT_WAYPOINT_COLOUR = 0xFFFFFFFF.toInt()

private fun id(path: String): UiId = UiId.of(Sidequest.MOD_ID, path)
