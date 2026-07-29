package dev.th7bo.sidequest.ui.core.persistence

import dev.th7bo.sidequest.ui.binding.asBinding
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.ids.ProfileId
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.persistence.ConfigSnapshot
import dev.th7bo.sidequest.ui.persistence.ConfigStore
import dev.th7bo.sidequest.ui.persistence.LoadReport
import dev.th7bo.sidequest.ui.persistence.LoadResult
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.UiThread
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.testkit.ManualScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Phase 2 acceptance criterion "persistence is atomic and off-thread".
 *
 * Atomicity is covered in [PersistenceTest]; this covers the threading half, by
 * recording which thread every store call actually runs on rather than assuming.
 */
class OffThreadPersistenceTest {

    @TempDir
    lateinit var root: Path

    private fun id(path: String) = UiId.of("sidequest", path)

    private lateinit var duration: MutableUiState<Int>
    private lateinit var screen: ConfigScreen
    private lateinit var scheduler: ManualScheduler
    private lateinit var coroutineScope: CoroutineScope

    /** Records the thread of every call, so "off the UI thread" becomes assertable. */
    private class ThreadRecordingStore(private val delegate: ConfigStore) : ConfigStore {

        val saveThreads: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
        val loadThreads: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
        val saveCount = AtomicInteger()

        override suspend fun load(profileId: ProfileId): LoadResult {
            loadThreads.add(Thread.currentThread().name)
            return delegate.load(profileId)
        }

        override suspend fun save(profileId: ProfileId, snapshot: ConfigSnapshot) {
            saveThreads.add(Thread.currentThread().name)
            saveCount.incrementAndGet()
            delegate.save(profileId, snapshot)
        }

        override suspend fun listProfiles(): List<ProfileId> = delegate.listProfiles()
        override suspend fun delete(profileId: ProfileId): Boolean = delegate.delete(profileId)
        override suspend fun copy(from: ProfileId, to: ProfileId) {
            delegate.copy(from, to)
        }
    }

    private lateinit var store: ThreadRecordingStore

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        UiThread.bind()

        duration = mutableStateOf(5)
        screen = configScreen(id("main"), "Sidequest") {
            category(id("general"), "General") {
                section("Interface") {
                    slider(id("general.duration"), "Duration", duration.asBinding(), 1..60, default = 5)
                }
            }
        }

        scheduler = ManualScheduler()
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = ThreadRecordingStore(JsonFileConfigStore(root, currentVersion = 1))
    }

    @AfterEach
    fun tearDown() {
        coroutineScope.cancel()
        resetReactiveGraphForTesting()
    }

    private fun controller(debounceMillis: Long = 40) = ConfigPersistenceController(
        screen = screen,
        store = store,
        coroutineScope = coroutineScope,
        scheduler = scheduler,
        schemaVersion = 1,
        debounceMillis = debounceMillis,
    )

    @Test
    fun `saving runs on a background thread, never the ui thread`() = runBlocking {
        val controller = controller()
        val uiThreadName = Thread.currentThread().name

        controller.saveNow()
        controller.awaitIdle()
        scheduler.drainUntil { controller.writeCount == 1 }

        assertEquals(1, store.saveCount.get())
        assertTrue(store.saveThreads.isNotEmpty())
        assertFalse(
            store.saveThreads.contains(uiThreadName),
            "the store must never be touched from the UI thread, but was on $uiThreadName",
        )
        controller.dispose()
    }

    @Test
    fun `loading runs off the ui thread and applies values back on it`() = runBlocking {
        val uiThreadName = Thread.currentThread().name
        store.save(ProfileId.DEFAULT, ConfigSnapshot(1, mapOf(id("general.duration") to kotlinx.serialization.json.JsonPrimitive(42))))
        store.saveThreads.clear()

        val controller = controller()
        var applied = false
        controller.load(ProfileId.DEFAULT) { applied = true }

        assertTrue(scheduler.drainUntil { applied }, "the load never completed")

        assertFalse(store.loadThreads.contains(uiThreadName), "reading must happen off the UI thread")
        assertEquals(42, duration.value, "but the value must be applied on it")
        assertTrue(
            scheduler.submitterThreadNames.none { it == uiThreadName },
            "the result must be handed back across a thread boundary",
        )
        controller.dispose()
    }

    @Test
    fun `the snapshot is taken on the ui thread so the writer never reads live state`() = runBlocking {
        val controller = controller()

        // Taking a snapshot requires the UI thread; doing it from a background thread
        // must be rejected rather than silently racing the graph.
        var failure: Throwable? = null
        val thread = Thread({
            failure = runCatching { controller.snapshot() }.exceptionOrNull()
        }, "not-the-ui-thread")
        thread.start()
        thread.join()

        assertTrue(
            failure is dev.th7bo.sidequest.ui.state.WrongThreadException,
            "snapshotting off the UI thread must throw, got $failure",
        )
        controller.dispose()
    }

    @Test
    fun `a burst of edits collapses into a single write`() = runBlocking {
        val controller = controller(debounceMillis = 60)
        controller.startAutoSave()

        // Twenty edits in quick succession, as dragging a slider would produce.
        repeat(20) { duration.value = it + 1 }

        assertTrue(controller.hasPendingSave.value)
        controller.awaitIdle()
        scheduler.drainUntil { controller.writeCount >= 1 }

        assertEquals(
            1,
            store.saveCount.get(),
            "a debounced burst must produce one write, not one per edit",
        )
        controller.dispose()
    }

    @Test
    fun `the debounced write contains the final value, not an intermediate one`() = runBlocking {
        val controller = controller(debounceMillis = 60)
        controller.startAutoSave()

        repeat(10) { duration.value = it + 1 }
        controller.awaitIdle()
        scheduler.drainUntil { controller.writeCount >= 1 }

        val reloaded = store.load(ProfileId.DEFAULT)
        assertEquals(
            "10",
            reloaded.snapshot[id("general.duration")]?.jsonPrimitive?.content,
            "the write must reflect where the burst ended",
        )
        controller.dispose()
    }

    @Test
    fun `saving state is observable while a write is in flight`() = runBlocking {
        val slowStore = object : ConfigStore by store {
            override suspend fun save(profileId: ProfileId, snapshot: ConfigSnapshot) {
                delay(80)
                store.save(profileId, snapshot)
            }
        }
        val controller = ConfigPersistenceController(
            screen, slowStore, coroutineScope, scheduler, schemaVersion = 1, debounceMillis = 0,
        )

        controller.saveNow()
        scheduler.drainUntil(timeoutMillis = 1_000) { controller.isSaving.value }
        assertTrue(controller.isSaving.value, "the in-flight flag must be visible to the UI")

        controller.awaitIdle()
        scheduler.drainUntil { !controller.isSaving.value }
        assertFalse(controller.isSaving.value)
        controller.dispose()
    }

    @Test
    fun `a write failure is reported on the ui thread rather than swallowed`() = runBlocking {
        val failingStore = object : ConfigStore by store {
            override suspend fun save(profileId: ProfileId, snapshot: ConfigSnapshot) {
                throw java.io.IOException("disk is full")
            }
        }
        val controller = ConfigPersistenceController(
            screen, failingStore, coroutineScope, scheduler, schemaVersion = 1, debounceMillis = 0,
        )

        var reported: Throwable? = null
        var reportedThread: String? = null
        controller.onSaveFailure = {
            reported = it
            reportedThread = Thread.currentThread().name
        }

        controller.saveNow()
        controller.awaitIdle()
        scheduler.drainUntil { reported != null }

        assertEquals("disk is full", reported?.message)
        assertEquals(
            Thread.currentThread().name,
            reportedThread,
            "the failure callback must arrive on the UI thread",
        )
        assertEquals(0, controller.writeCount, "a failed write must not count as a write")
        controller.dispose()
    }

    @Test
    fun `values absent from a snapshot keep their current value`() {
        val controller = controller()
        duration.value = 33

        // A snapshot written by an older build that did not know about this setting.
        controller.apply(ConfigSnapshot(1, emptyMap()))

        assertEquals(33, duration.value, "an older config file must not wipe newer settings")
        controller.dispose()
    }

    @Test
    fun `an invalid stored value is rejected individually and reported`() {
        val controller = controller()
        duration.value = 20

        val rejected = controller.apply(
            ConfigSnapshot(1, mapOf(id("general.duration") to kotlinx.serialization.json.JsonPrimitive("not a number"))),
        )

        assertEquals(1, rejected.size)
        assertEquals(20, duration.value, "the bad value must not reach the model")
        controller.dispose()
    }

    @Test
    fun `applying a snapshot notifies observers once, not per setting`() {
        val controller = controller()
        val scope = dev.th7bo.sidequest.ui.state.DisposableScope()
        var notifications = 0
        duration.observe(scope) { notifications++ }

        controller.apply(
            ConfigSnapshot(1, mapOf(id("general.duration") to kotlinx.serialization.json.JsonPrimitive(7))),
        )

        assertEquals(1, notifications)
        assertNotEquals(0, duration.value)
        scope.dispose()
        controller.dispose()
    }
}
