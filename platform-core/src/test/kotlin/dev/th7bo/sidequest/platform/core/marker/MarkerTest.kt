package dev.th7bo.sidequest.platform.core.marker

import dev.th7bo.sidequest.platform.core.context.DefaultGameContextService
import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.marker.Acknowledgement
import dev.th7bo.sidequest.platform.marker.Marker
import dev.th7bo.sidequest.platform.marker.MarkerArrivedEvent
import dev.th7bo.sidequest.platform.marker.MarkerKind
import dev.th7bo.sidequest.platform.marker.MarkerPlacedEvent
import dev.th7bo.sidequest.platform.marker.MarkerRemovalReason
import dev.th7bo.sidequest.platform.marker.MarkerRemovedEvent
import dev.th7bo.sidequest.platform.marker.MarkerStore
import dev.th7bo.sidequest.platform.parser.ScoreboardSnapshot
import dev.th7bo.sidequest.platform.parser.TabListSnapshot
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.SkyBlockProfile
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The marker service.
 *
 * Two things are worth reading. The first is every assertion about *islands*: two coordinates in different
 * spaces are not near each other however close the numbers look, and a service that got that wrong would draw
 * a death beam through somebody's private island. The second is the removal reasons — "my waypoint
 * disappeared" is unanswerable unless the service says which of five things happened.
 */
class MarkerTest {

    private lateinit var events: DefaultEventBus
    private lateinit var context: DefaultGameContextService
    private lateinit var service: DefaultMarkerService

    private var clock = 1_000_000L
    private var position: SqPosition? = SqPosition(0.0, 70.0, 0.0)

    private val me = PlayerId.of(UUID.fromString("11111111-1111-4111-8111-111111111111"))
    private val friend = PlayerId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"))

    @BeforeEach
    fun setUp() {
        events = DefaultEventBus(TestScheduler(), NoopLogger)
        context = DefaultGameContextService(events, NoopLogger)
        position = SqPosition(0.0, 70.0, 0.0)
        service = DefaultMarkerService(
            context = context,
            events = events,
            log = NoopLogger,
            localPosition = { position },
            localPlayer = { me },
            now = { clock },
        )
        onIsland("Hub")
    }

    /**
     * Puts the player on an island.
     *
     * Through the *tab list*, because that is where the island name comes from — the scoreboard's area line is
     * the sub-location, and a scoreboard-only setup leaves the island `UNKNOWN`. Worth knowing: it is the
     * difference between these tests exercising the island check and quietly comparing two unknowns.
     */
    private fun onIsland(name: String) {
        context.setOnHypixel(true)
        context.onScoreboard(ScoreboardSnapshot("§e§lSKYBLOCK", listOf(" §7⏣ §bVillage")))
        context.onTabList(TabListSnapshot(listOf("Info", "§b§lArea: §f$name")))
    }

    private fun marker(
        id: String = "m1",
        kind: MarkerKind = MarkerKind.WAYPOINT,
        island: Island = Island.HUB,
        x: Double = 10.0,
        z: Double = 0.0,
        arrivalRadius: Double = 0.0,
        recipients: Set<PlayerId> = emptySet(),
    ) = Marker(
        id = id,
        kind = kind,
        location = SqLocation(island, SqPosition(x, 70.0, z)),
        label = "Test",
        arrivalRadius = arrivalRadius,
        recipients = recipients,
    )

    // -- lifetimes -----------------------------------------------------------

    @Test
    fun `a marker takes its kind's lifetime when it does not name one`() {
        val placed = service.place(marker(kind = MarkerKind.PING))

        assertEquals(30.seconds, placed.lifetime)
        assertEquals(clock, placed.createdAtMillis)
    }

    @Test
    fun `a waypoint is permanent and a ping is not`() {
        service.place(marker(id = "way", kind = MarkerKind.WAYPOINT))
        service.place(marker(id = "ping", kind = MarkerKind.PING))

        clock += 1.minutes.inWholeMilliseconds
        service.tick()

        assertEquals(listOf("way"), service.all().map { it.marker.id })
    }

    @Test
    fun `an expired marker says it expired`() {
        val reasons = mutableListOf<MarkerRemovalReason>()
        events.on<MarkerRemovedEvent>(OwnerId.PLATFORM) { reasons.add(it.reason) }
        service.place(marker(kind = MarkerKind.PING))

        clock += 1.minutes.inWholeMilliseconds
        service.tick()

        assertEquals(listOf(MarkerRemovalReason.EXPIRED), reasons)
    }

    /**
     * A file can hold something that ran out while the game was closed.
     *
     * Checking only on tick would show it for a frame — or, for a marker restored on the title screen where
     * nothing ticks yet, for as long as it took to join a world.
     */
    @Test
    fun `loading drops what expired while the game was closed`() {
        val stale = marker(id = "old", kind = MarkerKind.DEATH).copy(createdAtMillis = clock - 1.minutes.inWholeMilliseconds * 30)
        val fresh = marker(id = "new", kind = MarkerKind.WAYPOINT)

        service.load(MarkerStore(listOf(stale, fresh)))

        assertEquals(listOf("new"), service.all().map { it.marker.id })
    }

    // -- islands -------------------------------------------------------------

    /**
     * The bug this whole design exists to prevent.
     *
     * A marker on another island is *not here*, not merely far away — and the distance is null rather than a
     * large number, because a large number sorts, compares and passes a widened range check.
     */
    @Test
    fun `a marker on another island has no distance and is not visible`() {
        service.place(marker(island = Island.CATACOMBS, x = 10.0))

        val tracked = service[("m1")]

        assertNotNull(tracked)
        assertNull(tracked!!.distance, "a marker in another coordinate space has no distance")
        assertFalse(tracked.isVisible)
        assertTrue(service.visible().isEmpty())
    }

    @Test
    fun `the same coordinates on this island are near`() {
        service.place(marker(island = Island.HUB, x = 10.0))

        assertEquals(10.0, service["m1"]!!.distance)
        assertEquals(1, service.visible().size)
    }

    /** A private island is per profile, so the same coordinates on two profiles are two places. */
    @Test
    fun `a private island marker from another profile is not here`() {
        context.onTabList(
            TabListSnapshot(listOf("Info", "§b§lArea: §fPrivate Island", "§b§lProfile: §fMango")),
        )
        service.place(
            marker(id = "other").copy(
                location = SqLocation(
                    Island.PRIVATE_ISLAND,
                    SqPosition(1.0, 70.0, 0.0),
                    profile = SkyBlockProfile("Papaya"),
                ),
            ),
        )

        assertNull(service["other"]!!.distance)
    }

    @Test
    fun `nothing is visible with no position`() {
        service.place(marker())
        position = null

        assertTrue(service.visible().isEmpty())
        assertNull(service["m1"]!!.distance)
    }

    // -- range ---------------------------------------------------------------

    @Test
    fun `beyond the kind's range it is not drawn`() {
        service.place(marker(id = "near", kind = MarkerKind.PING, x = 10.0))
        service.place(marker(id = "far", kind = MarkerKind.PING, x = 1000.0))

        assertEquals(listOf("near"), service.visible().map { it.marker.id })
    }

    @Test
    fun `an explicit range overrides the kind's`() {
        service.place(marker(id = "far", kind = MarkerKind.PING, x = 1000.0).copy(visibilityRange = 2000.0))

        assertEquals(listOf("far"), service.visible().map { it.marker.id })
    }

    /** Far to near, so a nearer marker draws over a more distant one. */
    @Test
    fun `visible markers are ordered far to near`() {
        service.place(marker(id = "near", x = 10.0))
        service.place(marker(id = "mid", x = 50.0))
        service.place(marker(id = "far", x = 200.0))

        assertEquals(listOf("far", "mid", "near"), service.visible().map { it.marker.id })
    }

    // -- arrival -------------------------------------------------------------

    @Test
    fun `arriving fires once, not every tick`() {
        val arrivals = mutableListOf<String>()
        events.on<MarkerArrivedEvent>(OwnerId.PLATFORM) { arrivals.add(it.marker.id) }
        service.place(marker(kind = MarkerKind.WAYPOINT, x = 3.0, arrivalRadius = 5.0))

        repeat(5) { service.tick() }

        assertEquals(listOf("m1"), arrivals)
    }

    @Test
    fun `a marker without an arrival radius never arrives`() {
        val arrivals = mutableListOf<String>()
        events.on<MarkerArrivedEvent>(OwnerId.PLATFORM) { arrivals.add(it.marker.id) }
        service.place(marker(x = 0.0, arrivalRadius = 0.0))

        service.tick()

        assertTrue(arrivals.isEmpty())
    }

    /**
     * Navigation clears itself; a waypoint does not.
     *
     * Walking past a waypoint is not asking for it to be deleted — the player put it there. A navigation
     * request that stayed after arrival would leave an arrow pointing at the player's own feet.
     */
    @Test
    fun `navigation clears on arrival and a waypoint stays`() {
        service.place(marker(id = "nav", kind = MarkerKind.NAVIGATION, x = 2.0, arrivalRadius = 5.0))
        service.place(marker(id = "way", kind = MarkerKind.WAYPOINT, x = 2.0, arrivalRadius = 5.0))

        service.tick()

        assertEquals(listOf("way"), service.all().map { it.marker.id })
    }

    @Test
    fun `arrival on another island does not count`() {
        val arrivals = mutableListOf<String>()
        events.on<MarkerArrivedEvent>(OwnerId.PLATFORM) { arrivals.add(it.marker.id) }
        service.place(marker(island = Island.CATACOMBS, x = 0.0, arrivalRadius = 5.0))

        service.tick()

        assertTrue(arrivals.isEmpty(), "standing at the same numbers elsewhere is not arriving")
    }

    // -- leaving the area ----------------------------------------------------

    @Test
    fun `leaving the area drops the transient kinds and keeps waypoints`() {
        service.place(marker(id = "way", kind = MarkerKind.WAYPOINT))
        service.place(marker(id = "ping", kind = MarkerKind.PING))
        service.place(marker(id = "rally", kind = MarkerKind.RALLY))
        service.place(marker(id = "death", kind = MarkerKind.DEATH))

        service.onIslandChanged()

        // A death marker survives on purpose: walking back for your items is the whole reason it exists, and
        // that walk starts by leaving wherever you respawned.
        assertEquals(setOf("way", "death"), service.all().map { it.marker.id }.toSet())
    }

    @Test
    fun `leaving says why they went`() {
        val reasons = mutableListOf<MarkerRemovalReason>()
        events.on<MarkerRemovedEvent>(OwnerId.PLATFORM) { reasons.add(it.reason) }
        service.place(marker(kind = MarkerKind.PING))

        service.onIslandChanged()

        assertEquals(listOf(MarkerRemovalReason.LEFT_AREA), reasons)
    }

    // -- recipients ----------------------------------------------------------

    @Test
    fun `a marker aimed at somebody else is not shown here`() {
        service.place(marker(id = "mine", recipients = emptySet()))
        service.place(marker(id = "theirs", recipients = setOf(friend)))
        service.place(marker(id = "ours", recipients = setOf(me, friend)))

        assertEquals(setOf("mine", "ours"), service.visible().map { it.marker.id }.toSet())
    }

    // -- acknowledgement -----------------------------------------------------

    @Test
    fun `acknowledgements are recorded per viewer`() {
        service.place(marker(id = "rally", kind = MarkerKind.RALLY))

        service.acknowledge("rally", me, Acknowledgement.COMING)
        service.acknowledge("rally", friend, Acknowledgement.DECLINED)

        assertEquals(
            mapOf(me to Acknowledgement.COMING, friend to Acknowledgement.DECLINED),
            service["rally"]!!.acknowledgements,
        )
    }

    @Test
    fun `acknowledging something that is gone does nothing`() {
        service.acknowledge("no-such-marker", me, Acknowledgement.SEEN)

        assertTrue(service.all().isEmpty())
    }

    // -- replacement and routes ----------------------------------------------

    @Test
    fun `placing over an existing id announces the replacement`() {
        val reasons = mutableListOf<MarkerRemovalReason>()
        events.on<MarkerRemovedEvent>(OwnerId.PLATFORM) { reasons.add(it.reason) }
        service.place(marker(id = "shared", x = 10.0))

        service.place(marker(id = "shared", x = 20.0))

        assertEquals(listOf(MarkerRemovalReason.REPLACED), reasons)
        assertEquals(1, service.all().size)
        assertEquals(20.0, service["shared"]!!.marker.location.position.x)
    }

    @Test
    fun `a route comes back in order`() {
        service.place(marker(id = "third").copy(routeOrder = 3))
        service.place(marker(id = "first").copy(routeOrder = 1))
        service.place(marker(id = "second").copy(routeOrder = 2))
        service.place(marker(id = "loose"))

        assertEquals(listOf("first", "second", "third"), service.route().map { it.marker.id })
    }

    // -- persistence ---------------------------------------------------------

    /**
     * Only what is worth keeping.
     *
     * A ping restored on the next login points at somewhere nobody remembers, and a death marker outlives the
     * items it was about.
     */
    @Test
    fun `only waypoints are persisted`() {
        var saved: MarkerStore? = null
        service.onStoreChanged = { saved = it }

        service.place(marker(id = "way", kind = MarkerKind.WAYPOINT))
        service.place(marker(id = "ping", kind = MarkerKind.PING))
        service.place(marker(id = "death", kind = MarkerKind.DEATH))

        assertEquals(listOf("way"), saved!!.markers.map { it.id })
    }

    @Test
    fun `placing and removing are announced`() {
        val seen = mutableListOf<String>()
        events.on<MarkerPlacedEvent>(OwnerId.PLATFORM) { seen.add("placed") }
        events.on<MarkerRemovedEvent>(OwnerId.PLATFORM) { seen.add("removed:${it.reason}") }

        val placed = service.place(marker())
        service.remove(placed.id)

        assertEquals(listOf("placed", "removed:DELETED"), seen)
    }

    @Test
    fun `an id is generated when none is given`() {
        val placed = service.place(marker(id = ""))

        assertTrue(placed.id.isNotEmpty())
        assertNotNull(service[placed.id])
    }

    @Test
    fun `removing every marker of a kind reports how many went`() {
        service.place(marker(id = "a", kind = MarkerKind.PING))
        service.place(marker(id = "b", kind = MarkerKind.PING))
        service.place(marker(id = "c", kind = MarkerKind.WAYPOINT))

        assertEquals(2, service.removeAll(MarkerKind.PING))
        assertEquals(listOf("c"), service.all().map { it.marker.id })
    }

    @Test
    fun `ticking with nothing held does nothing`() {
        service.tick()

        assertTrue(service.all().isEmpty())
    }
}
