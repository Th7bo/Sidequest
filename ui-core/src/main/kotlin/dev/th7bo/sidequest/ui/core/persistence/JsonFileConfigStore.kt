package dev.th7bo.sidequest.ui.core.persistence

import dev.th7bo.sidequest.ui.ids.ProfileId
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.persistence.ConfigSnapshot
import dev.th7bo.sidequest.ui.persistence.ConfigStore
import dev.th7bo.sidequest.ui.persistence.LoadReport
import dev.th7bo.sidequest.ui.persistence.LoadResult
import dev.th7bo.sidequest.ui.persistence.Migration
import dev.th7bo.sidequest.ui.persistence.MigrationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * A [ConfigStore] backed by one JSON file per profile.
 *
 * Layout:
 * ```
 * <root>/profiles/<profile>/<fileName>
 * <root>/backups/<profile>-<fileName>-<epochMillis>
 * ```
 *
 * Writes go to a temporary file, are flushed to disk, then moved over the target with
 * `ATOMIC_MOVE`. A crash at any point leaves either the old file or the new one, never
 * a half-written one.
 *
 * Both `load` and `save` suspend onto [ioDispatcher]; neither touches the reactive
 * graph, which is what keeps file I/O off the render thread.
 */
public class JsonFileConfigStore(
    private val root: Path,
    /** The schema version this build writes. */
    private val currentVersion: Int,
    migrations: List<Migration> = emptyList(),
    private val fileName: String = DEFAULT_FILE_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConfigStore {

    private val migrationsByVersion: Map<Int, Migration> = migrations.associateBy { it.fromVersion }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        // Unknown keys are the point: they are preserved, not rejected.
        ignoreUnknownKeys = true
    }

    init {
        for (migration in migrations) {
            require(migration.toVersion == migration.fromVersion + 1) {
                "Migration '${migration.description}' must step exactly one version, " +
                    "but goes ${migration.fromVersion} -> ${migration.toVersion}"
            }
        }
    }

    private fun profileDirectory(profileId: ProfileId): Path =
        root.resolve(PROFILES_DIRECTORY).resolve(profileId.value)

    private fun fileFor(profileId: ProfileId): Path = profileDirectory(profileId).resolve(fileName)

    override suspend fun load(profileId: ProfileId): LoadResult = withContext(ioDispatcher) {
        val file = fileFor(profileId)
        if (!file.exists()) {
            return@withContext LoadResult(
                ConfigSnapshot.empty(currentVersion),
                LoadReport(wasEmpty = true),
            )
        }

        val document = try {
            json.parseToJsonElement(file.readText()).jsonObject
        } catch (failure: Exception) {
            // Quarantine rather than delete: the file is the only copy of the user's
            // settings, and "it was corrupt" is far easier to act on with the evidence.
            val backup = quarantine(profileId, file, failure)
            return@withContext LoadResult(
                ConfigSnapshot.empty(currentVersion),
                LoadReport(corruptionBackupPath = backup.toString()),
            )
        }

        val (migrated, applied) = migrate(document)
        val values = LinkedHashMap<UiId, JsonElement>()
        val unknown = LinkedHashMap<String, JsonElement>()
        val rejected = LinkedHashMap<UiId, String>()

        val valuesObject = migrated[VALUES_KEY] as? JsonObject
        if (valuesObject != null) {
            for ((key, element) in valuesObject) {
                val id = UiId.parseOrNull(key)
                if (id == null) {
                    // A key that is not even a well-formed id belongs to something else;
                    // keep it verbatim rather than discarding another module's data.
                    unknown["$VALUES_KEY.$key"] = element
                    rejected[UiId.of("sidequest", "unparseable")] = "malformed key '$key'"
                } else {
                    values[id] = element
                }
            }
        }

        for ((key, element) in migrated) {
            if (key != VALUES_KEY && key != VERSION_KEY) unknown[key] = element
        }

        LoadResult(
            ConfigSnapshot(currentVersion, values, unknown),
            LoadReport(migrationsApplied = applied, rejectedValues = rejected),
        )
    }

    /** Steps [document] up to [currentVersion], one migration at a time. */
    private fun migrate(document: JsonObject): Pair<JsonObject, List<String>> {
        var version = (document[VERSION_KEY] as? JsonPrimitive)?.let {
            runCatching { it.int }.getOrNull()
        } ?: currentVersion

        if (version > currentVersion) {
            // A file from a newer build. Reading it would risk misinterpreting values,
            // so it is left alone and the caller starts from defaults.
            throw MigrationException(
                version,
                currentVersion,
                "Stored configuration is version $version but this build reads $currentVersion",
            )
        }

        var current = document
        val applied = ArrayList<String>()
        while (version < currentVersion) {
            val migration = migrationsByVersion[version] ?: throw MigrationException(
                version,
                currentVersion,
                "No migration registered from version $version; cannot reach $currentVersion",
            )
            current = migration.migrate(current)
            applied.add(migration.description)
            version = migration.toVersion
        }
        return current to applied
    }

    private fun quarantine(profileId: ProfileId, file: Path, cause: Exception): Path {
        val backups = root.resolve(BACKUPS_DIRECTORY)
        backups.createDirectories()
        val target = backups.resolve("${profileId.value}-$fileName-${System.currentTimeMillis()}")
        return try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
            target
        } catch (moveFailure: IOException) {
            moveFailure.addSuppressed(cause)
            throw moveFailure
        }
    }

    override suspend fun save(profileId: ProfileId, snapshot: ConfigSnapshot) {
        withContext(ioDispatcher) {
            val directory = profileDirectory(profileId)
            directory.createDirectories()

            val document = buildJsonObject {
                put(VERSION_KEY, JsonPrimitive(snapshot.schemaVersion))
                put(
                    VALUES_KEY,
                    buildJsonObject {
                        for ((id, element) in snapshot.values) put(id.value, element)
                    },
                )
                // Round-trip anything this build did not understand.
                for ((key, element) in snapshot.unknownFields) {
                    if (key.startsWith("$VALUES_KEY.")) continue
                    put(key, element)
                }
            }

            writeAtomically(fileFor(profileId), json.encodeToString(JsonObject.serializer(), document))
        }
    }

    /**
     * Writes [content] to [target] atomically.
     *
     * Temp file, force to disk, then `ATOMIC_MOVE`. Without the force, a power loss
     * after the rename can leave a file that exists but contains nothing.
     */
    private fun writeAtomically(target: Path, content: String) {
        val temporary = target.resolveSibling("${target.name}$TEMP_SUFFIX")
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8)))
                channel.force(true)
            }

            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
                // Some filesystems cannot do it; a plain replace is still better than
                // writing in place, and the failure mode is documented rather than silent.
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.deleteIfExists()
        }
    }

    override suspend fun listProfiles(): List<ProfileId> = withContext(ioDispatcher) {
        val profiles = root.resolve(PROFILES_DIRECTORY)
        if (!profiles.exists()) return@withContext emptyList()
        profiles.listDirectoryEntries()
            .filter { it.resolve(fileName).isRegularFile() }
            .mapNotNull { runCatching { ProfileId(it.name) }.getOrNull() }
            .sortedBy { it.value }
    }

    override suspend fun delete(profileId: ProfileId): Boolean = withContext(ioDispatcher) {
        val file = fileFor(profileId)
        if (!file.exists()) return@withContext false
        Files.delete(file)
        runCatching { Files.delete(profileDirectory(profileId)) }
        true
    }

    override suspend fun copy(from: ProfileId, to: ProfileId) {
        val source = load(from)
        save(to, source.snapshot)
    }

    public companion object {
        public const val DEFAULT_FILE_NAME: String = "config.json"
        public const val PROFILES_DIRECTORY: String = "profiles"
        public const val BACKUPS_DIRECTORY: String = "backups"
        private const val VERSION_KEY = "schemaVersion"
        private const val VALUES_KEY = "values"
        private const val TEMP_SUFFIX = ".tmp"
    }
}
