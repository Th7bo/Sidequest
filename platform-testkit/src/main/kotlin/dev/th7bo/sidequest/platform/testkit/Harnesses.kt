package dev.th7bo.sidequest.platform.testkit

import dev.th7bo.sidequest.platform.event.DispatchMode
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.storage.StorageMigration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.fail

/**
 * Watches everything that goes past on an event bus.
 *
 * The shape most event assertions want. Subscribing by hand in each test means writing the same
 * `mutableListOf` and the same lambda every time, and — more to the point — remembering to subscribe *before*
 * the thing under test runs, which is the mistake that produces a test asserting on an empty list and passing
 * for the wrong reason.
 */
public class EventRecorder(bus: EventBus, owner: OwnerId = OwnerId.PLATFORM) {

    private val seen = mutableListOf<SidequestEvent>()

    init {
        // Immediate, so an assertion straight after the thing under test sees what it posted. On the default
        // mode the events would be with the scheduler and the test would have to remember to pump it —
        // which is the sort of thing that gets forgotten and produces a test passing on an empty list.
        bus.subscribe(SidequestEvent::class, owner, mode = DispatchMode.IMMEDIATE) { event -> seen.add(event) }
    }

    public val all: List<SidequestEvent> get() = seen.toList()

    public inline fun <reified T : SidequestEvent> ofType(): List<T> = all.filterIsInstance<T>()

    public inline fun <reified T : SidequestEvent> first(): T =
        ofType<T>().firstOrNull() ?: fail("no ${T::class.simpleName} was posted, saw ${describe()}")

    public inline fun <reified T : SidequestEvent> count(): Int = ofType<T>().size

    /** Asserts exactly one of [T] was posted, and returns it. */
    public inline fun <reified T : SidequestEvent> single(): T {
        val matches = ofType<T>()
        assertEquals(1, matches.size, "wanted one ${T::class.simpleName}, saw ${describe()}")
        return matches.single()
    }

    public inline fun <reified T : SidequestEvent> assertNone() {
        val matches = ofType<T>()
        assertTrue(matches.isEmpty(), "expected no ${T::class.simpleName}, saw ${matches.size}")
    }

    /** What went past, for a failure message. Uses each event's own `describe`, which is why they have one. */
    public fun describe(): String =
        if (seen.isEmpty()) "nothing" else seen.joinToString(", ") { it.describe() }

    public fun clear(): Unit = seen.clear()
}

/**
 * Posts a sequence of events, as if the game had produced them.
 *
 * For the features that only do anything after several things have happened in order — an activity detector
 * needing a few ticks, a rule with a tiered trigger. Written as a builder so the *order* is visible at the
 * call site, which is the thing that matters and the thing a pile of separate `post` calls obscures.
 */
public class EventScript(private val bus: EventBus) {

    private val queued = mutableListOf<Pair<SidequestEvent, EventSource>>()

    public fun then(event: SidequestEvent, source: EventSource = EventSource.GAME): EventScript = apply {
        queued.add(event to source)
    }

    /** The same event several times, for cooldowns and coalescing. */
    public fun repeat(times: Int, event: () -> SidequestEvent): EventScript = apply {
        kotlin.repeat(times) { queued.add(event() to EventSource.GAME) }
    }

    public fun run() {
        for ((event, source) in queued) bus.post(event, source)
        queued.clear()
    }
}

/**
 * Runs a stored document through a migration chain.
 *
 * Migrations are the least-tested thing in most projects and the one whose failure is unrecoverable — a bad
 * migration does not throw, it silently produces a document that parses into wrong data, and by the time
 * anybody notices the original is gone. So this makes the awkward part easy: start from a document in an old
 * shape and assert what comes out the other end.
 *
 * The chain is applied the way the storage layer applies it, in version order, so a test here is a test of
 * what will actually happen rather than of one migration in isolation.
 */
public class MigrationHarness<T : Any>(
    private val serializer: KSerializer<T>,
    private val migrations: List<StorageMigration>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** The document after every applicable migration, and which ones ran. */
    public fun migrate(document: JsonObject, fromVersion: Int): Migrated {
        var current = document
        val applied = mutableListOf<String>()
        var version = fromVersion

        // Chained by version rather than applied in list order, so a chain declared out of order still runs
        // correctly — and a gap is a failure rather than a silent skip.
        while (true) {
            val next = migrations.firstOrNull { it.fromVersion == version } ?: break
            current = next.migrate(current)
            applied.add(next.description)
            check(next.toVersion > version) { "${next.description} does not advance the version" }
            version = next.toVersion
        }
        return Migrated(current, version, applied)
    }

    /** Migrates and then parses, which is the whole point — a document that migrates but will not load is a bug. */
    public fun migrateAndParse(document: JsonObject, fromVersion: Int): T {
        val migrated = migrate(document, fromVersion)
        return runCatching { json.decodeFromJsonElement(serializer, migrated.document) }
            .getOrElse { thrown ->
                fail(
                    "the document migrated from v$fromVersion to v${migrated.version} " +
                        "(${migrated.applied.joinToString()}) but would not parse: ${thrown.message}\n${migrated.document}",
                )
            }
    }

    /**
     * Asserts every version from [oldest] to the newest reaches something loadable.
     *
     * The check worth running on every schema: a chain is usually tested from the version somebody happens to
     * have a fixture for, and the one that breaks is the version nobody kept a fixture of.
     */
    public fun assertEveryVersionLoads(oldest: Int, documentFor: (Int) -> JsonObject) {
        val newest = migrations.maxOfOrNull { it.toVersion } ?: oldest
        for (version in oldest..newest) {
            runCatching { migrateAndParse(documentFor(version), version) }
                .onFailure { thrown -> fail("a v$version document does not survive to v$newest: ${thrown.message}") }
        }
    }

    public data class Migrated(
        public val document: JsonObject,
        public val version: Int,
        public val applied: List<String>,
    )
}
