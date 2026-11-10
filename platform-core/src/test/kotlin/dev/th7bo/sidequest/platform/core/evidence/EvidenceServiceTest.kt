package dev.th7bo.sidequest.platform.core.evidence

import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.evidence.EvidenceCreatedEvent
import dev.th7bo.sidequest.platform.evidence.EvidenceEntry
import dev.th7bo.sidequest.platform.evidence.EvidenceKind
import dev.th7bo.sidequest.platform.evidence.EvidenceLocker
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.testkit.EventRecorder
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The evidence locker.
 *
 * The assertions that matter are about hiding versus removing — the difference between "not in view" and
 * "never happened" is the whole reason [EvidenceEntry.isHiddenLocally] exists as a flag rather than as an
 * absence — and about search, which is the one method whose wrongness looks like missing data.
 */
class EvidenceServiceTest {

    private lateinit var bus: DefaultEventBus
    private lateinit var events: EventRecorder
    private lateinit var service: DefaultEvidenceService

    private val me = PlayerId.of(UUID.fromString("11111111-1111-4111-8111-111111111111"))
    private val andy = PlayerId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"))

    @BeforeEach
    fun setUp() {
        bus = DefaultEventBus(dev.th7bo.sidequest.platform.testkit.TestScheduler(), NoopLogger)
        events = EventRecorder(bus)
        service = DefaultEvidenceService(bus, NoopLogger, nextId = { "ev${idCounter++}" })
    }

    private var idCounter = 0

    private fun entry(
        title: String,
        kind: EvidenceKind = EvidenceKind.INCIDENT,
        players: List<PlayerId> = listOf(me),
        tags: Set<String> = emptySet(),
        atMillis: Long = 1_000L,
    ) = EvidenceEntry(
        id = "",
        kind = kind,
        title = title,
        atMillis = atMillis,
        players = players,
        island = Island.HUB,
        tags = tags,
    )

    // -- recording ------------------------------------------------------------

    @Test
    fun `recording assigns an id and keeps it newest first`() {
        val first = service.record(entry("wrong lobby", atMillis = 1_000L))!!
        val second = service.record(entry("clutched it", atMillis = 2_000L))!!

        assertTrue(first.id.isNotBlank())
        assertEquals(listOf(second.id, first.id), service.all().map { it.id })
    }

    @Test
    fun `an untitled entry is refused rather than filed`() {
        assertNull(service.record(entry("")))
        assertEquals(0, service.all().size)
        events.assertNone<EvidenceCreatedEvent>()
    }

    @Test
    fun `recording announces what was recorded`() {
        service.record(entry("a moment"))!!

        val event = events.single<EvidenceCreatedEvent>()
        assertEquals(EvidenceKind.INCIDENT, event.entry.kind)
    }

    // -- hiding ---------------------------------------------------------------

    /** The entry stays in the file. "Not in view" is not "never happened". */
    @Test
    fun `hiding removes from view but not from the locker`() {
        val stored = service.record(entry("regrettable"))!!

        assertTrue(service.hide(stored.id))
        assertEquals(0, service.all().size)
        assertEquals(1, service.all(includeHidden = true).size)

        // Search honours the same line, or hiding would be a filter only lists know about.
        assertEquals(0, service.search("regrettable").size)
        assertEquals(1, service.search("regrettable", includeHidden = true).size)
    }

    @Test
    fun `unhide puts it back`() {
        val stored = service.record(entry("fine actually"))!!
        service.hide(stored.id)
        assertTrue(service.unhide(stored.id))

        assertEquals(1, service.all().size)
    }

    // -- search ---------------------------------------------------------------

    @Test
    fun `search reaches titles, captions, tags and kinds`() {
        service.record(entry("Wrong Lobby Specialist", tags = setOf("andy")))
        service.record(
            entry("something else").copy(caption = "he did it again"),
        )
        service.record(entry("the drop", kind = EvidenceKind.RARE_DROP))

        assertEquals(1, service.search("lobby").size)
        assertEquals(1, service.search("again").size)
        assertEquals(1, service.search("andy").size)
        assertEquals(1, service.search("drop").size)
        // And nothing matches nonsense.
        assertEquals(0, service.search("hyperion").size)
    }

    @Test
    fun `tagging refuses duplicates and unknown ids`() {
        val stored = service.record(entry("tagged once"))!!

        assertTrue(service.tag(stored.id, "joke"))
        assertFalse(service.tag(stored.id, "JOKE"))
        assertFalse(service.tag("no-such-id", "joke"))

        assertEquals(setOf("joke"), service.get(stored.id)?.tags)
    }

    // -- asking about people --------------------------------------------------

    @Test
    fun `involving finds entries by player`() {
        service.record(entry("mine"))
        service.record(entry("his", players = listOf(andy)))

        assertEquals(1, service.involving(me).size)
        assertEquals(1, service.involving(andy).size)
    }

    // -- persistence ----------------------------------------------------------

    @Test
    fun `every change hands the caller the whole locker`() {
        var saved: EvidenceLocker? = null
        service.onStoreChanged = { saved = it }

        val stored = service.record(entry("kept"))!!
        service.hide(stored.id)

        assertEquals(1, saved?.size)
        assertTrue(saved?.entries?.single()?.isHiddenLocally == true)
    }

    @Test
    fun `a round trip through the locker keeps everything`() {
        val a = service.record(entry("one", atMillis = 3_000L))!!
        val b = service.record(entry("two", atMillis = 1_000L))!!
        service.hide(b.id)
        val stored = EvidenceLocker(service.all(includeHidden = true))

        val fresh = DefaultEvidenceService(bus, NoopLogger)
        fresh.load(stored)

        assertEquals(2, fresh.all(includeHidden = true).size)
        assertEquals(a.id, fresh.all(includeHidden = true).first().id)
        assertFalse(fresh.all().contains(fresh.get(b.id)))
    }
}
