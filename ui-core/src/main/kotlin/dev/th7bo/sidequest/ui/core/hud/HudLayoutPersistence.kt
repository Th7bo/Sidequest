package dev.th7bo.sidequest.ui.core.hud

import dev.th7bo.sidequest.ui.hud.HudPlacement
import dev.th7bo.sidequest.ui.ids.ProfileId
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.persistence.ConfigSnapshot
import dev.th7bo.sidequest.ui.persistence.ConfigStore
import dev.th7bo.sidequest.ui.persistence.LoadReport
import dev.th7bo.sidequest.ui.state.Disposable
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.UiScheduler
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.UiThread
import dev.th7bo.sidequest.ui.state.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Loads and saves where HUD elements sit.
 *
 * Deliberately built on [ConfigStore] rather than a second persistence path: atomic
 * writes, migrations, unknown-field preservation and corruption quarantine are already
 * solved there, and a HUD layout that survives a crash mid-write matters for exactly the
 * same reasons a config file does. Only the file name differs.
 *
 * One placement per instance id, so two copies of the same definition keep separate
 * positions — which is the whole reason placement is keyed by instance and not by
 * definition.
 */
public class HudLayoutPersistence(
    private val layer: HudLayerNode,
    private val store: ConfigStore,
    private val coroutineScope: CoroutineScope,
    /** Used to hand results back to the UI thread. */
    private val scheduler: UiScheduler,
    private val schemaVersion: Int,
) : Disposable {

    private val savingState: MutableUiState<Boolean> = mutableStateOf(false, "hudLayout.isSaving")

    /** True while a write is in flight. */
    public val isSaving: UiState<Boolean> get() = savingState

    /** Notified after each load, so the caller can log or surface a repair. */
    public var onLoadReport: ((LoadReport) -> Unit)? = null

    /**
     * Placements read from disk for instances that do not exist right now.
     *
     * Kept and written back rather than dropped. A HUD belonging to a module that is
     * currently disabled must not lose its position just because it was not on the layer
     * the one time the file happened to be rewritten.
     */
    private var orphanedPlacements: Map<UiId, JsonElement> = emptyMap()

    /**
     * What the most recent [apply] actually put on the layer.
     *
     * Recorded because "the element is still where the file said" is not the same claim
     * as "the file was read correctly" — anything may legitimately move a HUD afterwards.
     * This is the load itself, which is what a persistence test wants to assert against.
     */
    public var lastLoaded: Map<UiId, HudPlacement> = emptyMap()
        private set

    private val json = Json { ignoreUnknownKeys = true }

    /** The current layout, ready to write. */
    public fun snapshot(): ConfigSnapshot {
        UiThread.check()
        val values = LinkedHashMap<UiId, JsonElement>(orphanedPlacements)
        for (element in layer.ordered) {
            values[element.instance.instanceId] =
                json.encodeToJsonElement(HudPlacement.serializer(), element.placement.peek())
        }
        return ConfigSnapshot(schemaVersion, values)
    }

    /**
     * Applies [snapshot] to the layer.
     *
     * An element with no entry keeps its current placement rather than being reset: a
     * layout written before a HUD existed must not drag that HUD to the origin.
     *
     * @return instance ids whose stored placement could not be read, with the reason.
     */
    public fun apply(snapshot: ConfigSnapshot): Map<UiId, String> {
        UiThread.check()
        val rejected = LinkedHashMap<UiId, String>()
        val orphans = LinkedHashMap<UiId, JsonElement>()
        val applied = LinkedHashMap<UiId, HudPlacement>()

        for ((id, encoded) in snapshot.values) {
            val element = layer[id]
            if (element == null) {
                orphans[id] = encoded
                continue
            }
            val placement = try {
                json.decodeFromJsonElement(HudPlacement.serializer(), encoded)
            } catch (failure: IllegalArgumentException) {
                // One unreadable entry must not cost the whole layout. The element keeps
                // its default and the reason is reported.
                rejected[id] = failure.message ?: "unreadable placement"
                continue
            }
            element.setPlacement(placement)
            applied[id] = placement
        }

        lastLoaded = applied
        orphanedPlacements = orphans
        return rejected
    }

    /** Reads off the UI thread and applies back on it. */
    public fun load(profileId: ProfileId, onComplete: (() -> Unit)? = null) {
        coroutineScope.launch {
            val result = store.load(profileId)
            scheduler.submit {
                val rejected = apply(result.snapshot)
                onLoadReport?.invoke(
                    LoadReport(
                        wasEmpty = result.report.wasEmpty,
                        corruptionBackupPath = result.report.corruptionBackupPath,
                        migrationsApplied = result.report.migrationsApplied,
                        rejectedValues = result.report.rejectedValues + rejected,
                    ),
                )
                onComplete?.invoke()
            }
        }
    }

    /** Writes the current layout. Snapshotting happens on the UI thread; the write does not. */
    public fun saveNow(profileId: ProfileId, onComplete: (() -> Unit)? = null) {
        UiThread.check()
        val pending = snapshot()
        savingState.value = true
        coroutineScope.launch {
            try {
                store.save(profileId, pending)
            } finally {
                scheduler.submit {
                    savingState.value = false
                    onComplete?.invoke()
                }
            }
        }
    }

    override fun dispose() {
        orphanedPlacements = emptyMap()
        lastLoaded = emptyMap()
        onLoadReport = null
    }

    public companion object {
        /** The layout file, alongside the configuration file in the profile directory. */
        public const val FILE_NAME: String = "huds.json"
    }
}
