package dev.th7bo.sidequest.ui.core.persistence

import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.Setting
import dev.th7bo.sidequest.ui.config.isPersistent
import dev.th7bo.sidequest.ui.ids.ProfileId
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.persistence.ConfigSnapshot
import dev.th7bo.sidequest.ui.persistence.ConfigStore
import dev.th7bo.sidequest.ui.persistence.LoadReport
import dev.th7bo.sidequest.ui.state.Disposable
import dev.th7bo.sidequest.ui.state.DisposableScope
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.UiScheduler
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.UiThread
import dev.th7bo.sidequest.ui.state.batch
import dev.th7bo.sidequest.ui.state.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Binds a [ConfigScreen] to a [ConfigStore].
 *
 * Responsibilities, all of which exist to keep the render thread clear:
 *
 * - **Snapshotting** happens on the UI thread and produces an immutable value.
 * - **Writing** happens in a coroutine; the write path never dereferences a `UiState`.
 * - **Debouncing** collapses a burst of edits — dragging a slider, for instance — into
 *   one write instead of one per frame.
 *
 * Loading applies values through each setting's own serializer and validator, so a bad
 * value on disk is rejected individually and reported, not allowed to take the whole
 * file down.
 */
public class ConfigPersistenceController(
    private val screen: ConfigScreen,
    private val store: ConfigStore,
    private val coroutineScope: CoroutineScope,
    /** Used to hand results back to the UI thread. */
    private val scheduler: UiScheduler,
    private val schemaVersion: Int,
    /** How long to wait after the last edit before writing. */
    public val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) : Disposable {

    private val scope = DisposableScope()

    private val savingState: MutableUiState<Boolean> = mutableStateOf(false, "isSaving")
    private val pendingState: MutableUiState<Boolean> = mutableStateOf(false, "hasPendingSave")

    /** True while a write is in flight. */
    public val isSaving: UiState<Boolean> get() = savingState

    /** True while an edit is waiting out the debounce window. */
    public val hasPendingSave: UiState<Boolean> get() = pendingState

    /** The profile currently loaded. */
    public var activeProfile: ProfileId = ProfileId.DEFAULT
        private set

    /** Fields loaded from disk that this build did not recognise, carried through on save. */
    private var unknownFields: Map<String, JsonElement> = emptyMap()

    private var pendingSave: Job? = null

    /** Number of completed writes. Exposed so tests can assert debouncing. */
    public var writeCount: Int = 0
        private set

    /** Called after every load, with whatever went wrong. */
    public var onLoadReport: ((LoadReport) -> Unit)? = null

    /** Called when a write fails, on the UI thread. */
    public var onSaveFailure: ((Throwable) -> Unit)? = null

    /**
     * Starts watching every persistent setting.
     *
     * Must be called after loading, or the load itself would schedule a save.
     */
    public fun startAutoSave() {
        UiThread.check()
        for (setting in screen.settings) {
            if (!setting.isPersistent()) continue
            setting.onChange(scope) { scheduleSave() }
        }
    }

    /** Takes an immutable snapshot of the current values. UI thread only. */
    public fun snapshot(): ConfigSnapshot {
        UiThread.check()
        val values = LinkedHashMap<UiId, JsonElement>(screen.settingCount)
        for (setting in screen.settings) {
            if (!setting.isPersistent()) continue
            values[setting.id] = setting.encode()
        }
        return ConfigSnapshot(schemaVersion, values, unknownFields)
    }

    /**
     * Loads [profileId] and applies it.
     *
     * Reading happens off the UI thread; applying is scheduled back onto it, because
     * writing to settings touches the reactive graph.
     */
    public fun load(profileId: ProfileId = activeProfile, onComplete: (() -> Unit)? = null) {
        coroutineScope.launch {
            val result = store.load(profileId)
            scheduler.submit {
                val rejected = apply(result.snapshot)
                activeProfile = profileId
                unknownFields = result.snapshot.unknownFields

                val report = LoadReport(
                    wasEmpty = result.report.wasEmpty,
                    corruptionBackupPath = result.report.corruptionBackupPath,
                    migrationsApplied = result.report.migrationsApplied,
                    rejectedValues = result.report.rejectedValues + rejected,
                )
                onLoadReport?.invoke(report)
                onComplete?.invoke()
            }
        }
    }

    /**
     * Applies [snapshot] to the screen.
     *
     * Settings absent from the snapshot keep their current value rather than being
     * reset — a config file written by an older build must not wipe newer settings.
     *
     * @return ids that could not be applied, with the reason.
     */
    public fun apply(snapshot: ConfigSnapshot): Map<UiId, String> {
        UiThread.check()
        val rejected = LinkedHashMap<UiId, String>()

        // One batch, so observers see the loaded configuration settle once.
        batch {
            for (setting in screen.settings) {
                if (!setting.isPersistent()) continue
                val element = snapshot[setting.id] ?: continue
                setting.decodeAndApply(element)?.let { problem -> rejected[setting.id] = problem }
            }
        }
        return rejected
    }

    /** Queues a debounced write. Repeated calls within the window collapse into one. */
    public fun scheduleSave() {
        UiThread.check()
        pendingSave?.cancel()
        pendingState.value = true

        val snapshotAtRequest = snapshot()
        pendingSave = coroutineScope.launch {
            delay(debounceMillis)
            writeNow(snapshotAtRequest)
        }
    }

    /** Writes immediately, bypassing the debounce. Used on screen close. */
    public fun saveNow(onComplete: (() -> Unit)? = null) {
        UiThread.check()
        pendingSave?.cancel()
        val snapshotAtRequest = snapshot()
        pendingSave = coroutineScope.launch {
            writeNow(snapshotAtRequest)
            onComplete?.let { scheduler.submit(it) }
        }
    }

    private suspend fun writeNow(snapshot: ConfigSnapshot) {
        scheduler.submit {
            pendingState.value = false
            savingState.value = true
        }
        try {
            store.save(activeProfile, snapshot)
            scheduler.submit { writeCount++ }
        } catch (failure: Throwable) {
            scheduler.submit { onSaveFailure?.invoke(failure) }
        } finally {
            scheduler.submit { savingState.value = false }
        }
    }

    /** Waits for any in-flight write to finish. For shutdown and for tests. */
    public suspend fun awaitIdle() {
        pendingSave?.join()
    }

    override fun dispose() {
        pendingSave?.cancel()
        scope.dispose()
    }

    public companion object {
        public const val DEFAULT_DEBOUNCE_MILLIS: Long = 750
    }
}

/**
 * Creates, renames, duplicates, deletes and switches profiles.
 *
 * Kept separate from the controller because profile management is an operation on the
 * *store*, while the controller owns one loaded profile at a time.
 */
public class ProfileManager(
    private val store: ConfigStore,
    private val controller: ConfigPersistenceController,
) {

    /** Profiles that currently have stored data, always including the default. */
    public suspend fun list(): List<ProfileId> {
        val stored = store.listProfiles()
        return if (ProfileId.DEFAULT in stored) stored else listOf(ProfileId.DEFAULT) + stored
    }

    /** Creates [id] from the current values, or from [copyFrom] if given. */
    public suspend fun create(id: ProfileId, copyFrom: ProfileId? = null) {
        require(id != ProfileId.DEFAULT) { "The default profile always exists" }
        if (copyFrom != null) store.copy(copyFrom, id) else store.copy(controller.activeProfile, id)
    }

    public suspend fun duplicate(source: ProfileId, target: ProfileId) {
        store.copy(source, target)
    }

    public suspend fun rename(from: ProfileId, to: ProfileId) {
        require(from != ProfileId.DEFAULT) { "The default profile cannot be renamed" }
        store.copy(from, to)
        store.delete(from)
    }

    /**
     * Deletes [id].
     *
     * @throws IllegalArgumentException for the default profile, which must always exist
     * so that there is somewhere to fall back to.
     */
    public suspend fun delete(id: ProfileId): Boolean {
        require(id != ProfileId.DEFAULT) { "The default profile cannot be deleted" }
        return store.delete(id)
    }

    /** Exports a profile's stored values without touching the loaded one. */
    public suspend fun export(id: ProfileId): ConfigSnapshot = store.load(id).snapshot

    /** Writes [snapshot] into [id], replacing whatever was there. */
    public suspend fun import(id: ProfileId, snapshot: ConfigSnapshot) {
        store.save(id, snapshot)
    }
}
