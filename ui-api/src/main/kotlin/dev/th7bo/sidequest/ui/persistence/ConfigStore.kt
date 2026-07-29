package dev.th7bo.sidequest.ui.persistence

import dev.th7bo.sidequest.ui.ids.ProfileId
import dev.th7bo.sidequest.ui.ids.UiId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * An immutable point-in-time copy of a configuration.
 *
 * Snapshots are what cross the thread boundary: they are taken on the UI thread and
 * handed to a background writer, which never touches a `UiState`. That is the whole
 * threading contract for persistence in one type.
 *
 * [unknownFields] holds keys the running build did not recognise. Preserving them means
 * a user who downgrades, launches, and upgrades again does not silently lose another
 * module's settings.
 */
public class ConfigSnapshot(
    public val schemaVersion: Int,
    public val values: Map<UiId, JsonElement>,
    public val unknownFields: Map<String, JsonElement> = emptyMap(),
) {

    public val size: Int get() = values.size

    public operator fun get(id: UiId): JsonElement? = values[id]

    public operator fun contains(id: UiId): Boolean = values.containsKey(id)

    public fun withVersion(version: Int): ConfigSnapshot =
        ConfigSnapshot(version, values, unknownFields)

    public fun withValues(newValues: Map<UiId, JsonElement>): ConfigSnapshot =
        ConfigSnapshot(schemaVersion, newValues, unknownFields)

    override fun toString(): String =
        "ConfigSnapshot(v$schemaVersion, ${values.size} values, ${unknownFields.size} unknown)"

    public companion object {
        public fun empty(schemaVersion: Int): ConfigSnapshot =
            ConfigSnapshot(schemaVersion, emptyMap())
    }
}

/**
 * Reads and writes configuration for one profile.
 *
 * Implementations must write atomically: a crash mid-save leaves the previous file
 * intact, never a truncated one.
 */
public interface ConfigStore {

    /**
     * Loads [profileId], returning an empty snapshot if nothing is stored yet.
     *
     * Runs off the UI thread. A corrupt file is quarantined and reported through
     * [LoadReport] rather than thrown away or thrown at the caller.
     */
    public suspend fun load(profileId: ProfileId): LoadResult

    /** Writes [snapshot] atomically. Runs off the UI thread. */
    public suspend fun save(profileId: ProfileId, snapshot: ConfigSnapshot)

    /** Profiles that currently have stored data. */
    public suspend fun listProfiles(): List<ProfileId>

    /** Removes a profile's stored data. Returns false if there was nothing to delete. */
    public suspend fun delete(profileId: ProfileId): Boolean

    /** Copies [from] onto [to], overwriting whatever was there. */
    public suspend fun copy(from: ProfileId, to: ProfileId)
}

/** The outcome of a load, including anything that went wrong along the way. */
public class LoadResult(
    public val snapshot: ConfigSnapshot,
    public val report: LoadReport,
)

/**
 * What happened during a load.
 *
 * Problems are reported rather than thrown: one unreadable value must not stop the rest
 * of a configuration from loading, but it must not vanish silently either.
 */
public class LoadReport(
    /** True when there was no stored file at all — a first run, not a failure. */
    public val wasEmpty: Boolean = false,
    /** Set when the file could not be parsed and was moved aside. */
    public val corruptionBackupPath: String? = null,
    /** Migrations applied, oldest first. */
    public val migrationsApplied: List<String> = emptyList(),
    /** Individual values that could not be read, by id. */
    public val rejectedValues: Map<UiId, String> = emptyMap(),
) {

    public val isClean: Boolean
        get() = corruptionBackupPath == null && rejectedValues.isEmpty()

    override fun toString(): String = buildString {
        append(if (wasEmpty) "empty" else "loaded")
        corruptionBackupPath?.let { append(", corrupt file quarantined at $it") }
        if (migrationsApplied.isNotEmpty()) append(", migrated: ${migrationsApplied.joinToString()}")
        if (rejectedValues.isNotEmpty()) append(", ${rejectedValues.size} value(s) rejected")
    }
}

/**
 * Upgrades a stored document from one schema version to the next.
 *
 * Migrations are registered as a chain and applied in order, so a file from any past
 * version reaches the current one by composition rather than by a special case per
 * starting point.
 */
public interface Migration {

    /** The version this migration reads. */
    public val fromVersion: Int

    /** The version it produces. Must be `fromVersion + 1`. */
    public val toVersion: Int get() = fromVersion + 1

    /** Shown in [LoadReport.migrationsApplied]. */
    public val description: String

    /** Transforms the raw document. Must not throw for input it declares it can read. */
    public fun migrate(document: JsonObject): JsonObject
}

/** Thrown when a migration chain cannot reach the current version. */
public class MigrationException(
    public val fromVersion: Int,
    public val targetVersion: Int,
    message: String,
) : IllegalStateException(message)
