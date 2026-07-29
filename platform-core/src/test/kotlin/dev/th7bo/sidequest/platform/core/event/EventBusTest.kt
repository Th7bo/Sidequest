package dev.th7bo.sidequest.platform.core.event

import dev.th7bo.sidequest.platform.event.Cancellable
import dev.th7bo.sidequest.platform.event.DispatchMode
import dev.th7bo.sidequest.platform.event.EventPriority
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.log.LogCategory
import dev.th7bo.sidequest.platform.testkit.RecordingLogSink
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import dev.th7bo.sidequest.platform.core.log.LoggerFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EventBusTest {

    private open class Sample(val label: String = "") : SidequestEvent()
    private class SubSample : Sample("sub")
    private class Unrelated : SidequestEvent()
    private class Vetoable : SidequestEvent(), Cancellable {
        override var isCancelled: Boolean = false
    }

    private lateinit var scheduler: TestScheduler
    private lateinit var sink: RecordingLogSink
    private lateinit var bus: DefaultEventBus

    private val alice = OwnerId(SqId.sidequest("alice"))
    private val bob = OwnerId(SqId.sidequest("bob"))

    @BeforeEach
    fun setUp() {
        scheduler = TestScheduler()
        sink = RecordingLogSink()
        bus = DefaultEventBus(
            scheduler = scheduler,
            log = LoggerFactory(sink).apply { defaultLevel = dev.th7bo.sidequest.platform.log.LogLevel.TRACE }
                .create(LogCategory.EVENT, SqId.sidequest("bus")),
        )
    }

    // ---------------------------------------------------------------
    // Delivery
    // ---------------------------------------------------------------

    @Test
    fun `a listener receives the event it asked for and nothing else`() {
        val seen = mutableListOf<String>()
        bus.on<Sample>(alice) { seen.add("sample:${it.label}") }
        bus.on<Unrelated>(alice) { seen.add("unrelated") }

        bus.post(Sample("one"))

        assertEquals(listOf("sample:one"), seen)
    }

    @Test
    fun `a listener on a supertype sees subtypes`() {
        // Without this, observing a whole family means enumerating it, and every new
        // event in the family is a place someone forgets to add.
        val seen = mutableListOf<String>()
        bus.on<Sample>(alice) { seen.add("base") }
        bus.on<SubSample>(alice) { seen.add("sub") }

        bus.post(SubSample())

        assertEquals(listOf("base", "sub"), seen)
    }

    @Test
    fun `a listener on the root type sees everything`() {
        var count = 0
        bus.on<SidequestEvent>(alice) { count++ }

        bus.post(Sample())
        bus.post(Unrelated())

        assertEquals(2, count)
    }

    @Test
    fun `an event carries where it came from and when`() {
        val posted = bus.post(Sample(), EventSource.PARSER)

        assertEquals(EventSource.PARSER, posted.metadata.source)
        assertEquals(0, posted.metadata.sequence)
        assertEquals(1, bus.post(Sample()).metadata.sequence, "sequence orders a trace exactly")
    }

    @Test
    fun `re-posting an event instance is refused`() {
        // Silently allowing it would rewrite a timestamp a listener already recorded and
        // make two dispatches indistinguishable in the trace.
        val event = Sample()
        bus.post(event)

        assertThrows<IllegalStateException> { bus.post(event) }
    }

    // ---------------------------------------------------------------
    // Ordering
    // ---------------------------------------------------------------

    @Test
    fun `priority decides order, and registration order breaks ties`() {
        val seen = mutableListOf<String>()
        bus.on<Sample>(alice, EventPriority.LATE) { seen.add("late") }
        bus.on<Sample>(alice, EventPriority.FIRST) { seen.add("first") }
        bus.on<Sample>(alice, EventPriority.NORMAL) { seen.add("normal-a") }
        bus.on<Sample>(alice, EventPriority.MONITOR) { seen.add("monitor") }
        bus.on<Sample>(alice, EventPriority.NORMAL) { seen.add("normal-b") }

        bus.post(Sample())

        assertEquals(listOf("first", "normal-a", "normal-b", "late", "monitor"), seen)
    }

    // ---------------------------------------------------------------
    // Cancellation
    // ---------------------------------------------------------------

    @Test
    fun `cancelling stops later listeners but not monitors`() {
        val seen = mutableListOf<String>()
        bus.on<Vetoable>(alice, EventPriority.FIRST) { it.isCancelled = true }
        bus.on<Vetoable>(alice, EventPriority.NORMAL) { seen.add("normal") }
        bus.on<Vetoable>(alice, EventPriority.MONITOR) { seen.add("monitor") }

        val posted = bus.post(Vetoable())

        assertTrue(posted.isCancelled)
        assertEquals(
            listOf("monitor"),
            seen,
            "a monitor records what happened, and 'it was cancelled' is part of that",
        )
    }

    @Test
    fun `the poster can read what listeners decided`() {
        bus.on<Vetoable>(alice) { it.isCancelled = true }
        assertTrue(bus.post(Vetoable()).isCancelled)
    }

    // ---------------------------------------------------------------
    // Isolation
    // ---------------------------------------------------------------

    @Test
    fun `a listener that throws does not stop the others, and is reported`() {
        val seen = mutableListOf<String>()
        bus.on<Sample>(alice, EventPriority.FIRST) { error("alice is broken") }
        bus.on<Sample>(bob) { seen.add("bob") }

        bus.post(Sample())

        assertEquals(listOf("bob"), seen, "one feature must not be able to break another")
        val errors = sink.errors()
        assertEquals(1, errors.size)
        assertTrue(errors.single().message.contains("alice"), "the report names who threw")
    }

    @Test
    fun `a failure is recorded in the trace`() {
        bus.isTracing = true
        bus.on<Sample>(alice) { error("nope") }

        bus.post(Sample())

        val entry = bus.trace().single()
        assertEquals(1, entry.failures.size)
        assertEquals(alice.value, entry.failures.single().owner)
    }

    // ---------------------------------------------------------------
    // Ownership
    // ---------------------------------------------------------------

    @Test
    fun `cancelling a registration stops delivery`() {
        var count = 0
        val registration = bus.on<Sample>(alice) { count++ }

        bus.post(Sample())
        registration.cancel()
        bus.post(Sample())

        assertEquals(1, count)
        assertEquals(0, bus.listenerCount())
    }

    @Test
    fun `unsubscribeAll drops one owner and leaves the rest`() {
        var aliceCount = 0
        var bobCount = 0
        bus.on<Sample>(alice) { aliceCount++ }
        bus.on<Sample>(bob) { bobCount++ }

        bus.unsubscribeAll(alice)
        bus.post(Sample())

        assertEquals(0, aliceCount)
        assertEquals(1, bobCount)
        assertEquals(1, bus.listenerCount())
    }

    @Test
    fun `a listener cancelled mid-dispatch does not still run`() {
        // The dispatch holds a snapshot of the listener list, so removal alone is not
        // enough — an already-resolved entry has to know it is dead.
        val seen = mutableListOf<String>()
        lateinit var second: dev.th7bo.sidequest.platform.lifecycle.Registration

        bus.on<Sample>(alice, EventPriority.FIRST) { second.cancel() }
        second = bus.on<Sample>(bob, EventPriority.LATE) { seen.add("second") }

        bus.post(Sample())

        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun `subscribing from inside a listener is safe`() {
        // Features do this: react to a join by listening for what follows.
        var innerCalls = 0
        bus.on<Sample>(alice) {
            bus.on<Unrelated>(alice) { innerCalls++ }
        }

        bus.post(Sample())
        bus.post(Unrelated())

        assertEquals(1, innerCalls)
    }

    // ---------------------------------------------------------------
    // Threading
    // ---------------------------------------------------------------

    @Test
    fun `a MAIN listener runs inline when already on the client thread`() {
        scheduler.isOnMainThread = true
        var ranInline = false
        bus.on<Sample>(alice, mode = DispatchMode.MAIN) { ranInline = true }

        bus.post(Sample())

        assertTrue(ranInline, "bouncing through the scheduler would cost a tick for nothing")
    }

    @Test
    fun `a MAIN listener is deferred when posted off-thread`() {
        scheduler.isOnMainThread = false
        var ran = false
        bus.on<Sample>(alice, mode = DispatchMode.MAIN) { ran = true }

        bus.post(Sample())
        assertFalse(ran, "it must not run on the posting thread")

        scheduler.runPending()
        assertTrue(ran)
    }

    @Test
    fun `a listener deferred off the posting thread cannot cancel`() {
        scheduler.isOnMainThread = false
        bus.on<Vetoable>(alice, mode = DispatchMode.MAIN) { it.isCancelled = true }

        val posted = bus.post(Vetoable())

        assertFalse(
            posted.isCancelled,
            "the post has already returned, which is why only IMMEDIATE can cancel",
        )
    }

    @Test
    fun `a deferred listener cancelled before it runs does not run`() {
        scheduler.isOnMainThread = false
        var ran = false
        val registration = bus.on<Sample>(alice, mode = DispatchMode.MAIN) { ran = true }

        bus.post(Sample())
        registration.cancel()
        scheduler.runPending()

        assertFalse(ran)
    }

    // ---------------------------------------------------------------
    // Tracing
    // ---------------------------------------------------------------

    @Test
    fun `tracing is off by default and records nothing`() {
        bus.on<Sample>(alice) {}
        bus.post(Sample())

        assertTrue(bus.trace().isEmpty())
    }

    @Test
    fun `a trace entry describes the dispatch`() {
        bus.isTracing = true
        bus.on<Sample>(alice) {}
        bus.on<Sample>(bob) {}

        bus.post(Sample("payload"), EventSource.SIMULATED)

        val entry = bus.trace().single()
        assertEquals("Sample", entry.eventName)
        assertEquals(2, entry.listenersInvoked)
        assertEquals(EventSource.SIMULATED, entry.metadata.source)
        assertFalse(entry.wasCancelled)
    }

    @Test
    fun `the trace is bounded`() {
        val small = DefaultEventBus(
            scheduler,
            dev.th7bo.sidequest.platform.testkit.NoopLogger,
            traceCapacity = 3,
        ).apply { isTracing = true }

        repeat(10) { small.post(Sample("n$it")) }

        val trace = small.trace()
        assertEquals(3, trace.size)
        assertEquals(
            listOf(7L, 8L, 9L),
            trace.map { it.metadata.sequence },
            "the oldest are dropped, so what is kept is what just happened",
        )
    }

    @Test
    fun `an event with no listeners is still traced`() {
        // "Nothing happened when I did X" is answered by seeing the event with zero
        // listeners, which is a different answer from not seeing the event at all.
        bus.isTracing = true
        bus.post(Sample())

        assertEquals(1, bus.trace().size)
        assertEquals(0, bus.trace().single().listenersInvoked)
    }
}
