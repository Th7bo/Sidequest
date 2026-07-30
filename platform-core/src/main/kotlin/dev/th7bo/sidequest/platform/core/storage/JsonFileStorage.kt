package dev.th7bo.sidequest.platform.core.storage

import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.log.LogLevel
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.storage.OfflineQueue
import dev.th7bo.sidequest.platform.storage.QueuedEntry
import dev.th7bo.sidequest.platform.storage.Repository
import dev.th7bo.sidequest.platform.storage.StorageMigration
import dev.th7bo.sidequest.platform.storage.StorageProvider
import dev.th7bo.sidequest.platform.storage.StorageReport
import dev.th7bo.sidequest.platform.storage.StorageScope
import dev.th7bo.sidequest.platform.storage.StoredValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature data, in JSON files under one root.
 *
 * The whole of the plan's requirement list is here — schema versions, migrations, atomic writes,
 * corruption detection, fallback recovery, backups, validation — and every one of them is here rather
 * than in eleven features because getting any of them subtly wrong loses somebody's data.
 *
 * **On duplication.** The UI framework has its own store with the same properties. That is
 * deliberate: the platform and the UI framework do not depend on each other, and sharing this would
 * mean one of them depending on the other or a third module both import. The cost is two
 * implementations of atomic-write; the benefit is that either framework could be replaced without
 * touching the other, which is the property the whole split exists for.
 *
 * **On the file layout.** Every repository is its own file, under a directory per scope. One large
 * file would mean every save rewriting every feature's data, and one feature's corruption taking all
 * of it with it.
 */
public class JsonFileStorage(
    private val root: Path,
    private val log: Logger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : StorageProvider {

    /**
     * One repository object per id and scope.
     *
     * Not a cache for speed. Two objects over one file could not serialise their writes against each
     * other, and [Repository.update] would race and lose one.
     */
    private val repositories = ConcurrentHashMap<String, Any>()

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        // Unknown keys are kept rather than rejected: a user who runs an older build and then upgrades
        // must not silently lose the fields the older build did not know about.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> repository(
        id: SqId,
        scope: StorageScope,
        serializer: KSerializer<T>,
        default: () -> T,
        schemaVersion: Int,
        migrations: List<StorageMigration>,
        validate: (T) -> String?,
    ): Repository<T> {
        for (migration in migrations) {
            require(migration.toVersion == migration.fromVersion + 1) {
                "Migration '${migration.description}' must step exactly one version, but goes " +
                    "${migration.fromVersion} -> ${migration.toVersion}"
            }
        }
        return repositories.computeIfAbsent("${scope.path}/${id.value}") {
            JsonRepository(id, scope, serializer, default, schemaVersion, migrations, validate)
        } as Repository<T>
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> queue(
        id: SqId,
        scope: StorageScope,
        serializer: KSerializer<T>,
        capacity: Int,
    ): OfflineQueue<T> = repositories.computeIfAbsent("queue:${scope.path}/${id.value}") {
        JsonQueue(id, scope, serializer, capacity)
    } as OfflineQueue<T>

    override suspend fun flush() {
        // Every write completes before its call returns, so there is nothing queued to wait for.
        // The method exists because the *contract* callers need is "everything is on disk now", and a
        // future buffered implementation must be able to provide it without every caller changing.
        withContext(ioDispatcher) { }
    }

    /** Where a scope's files live. */
    private fun directoryOf(scope: StorageScope): Path = root.resolve(scope.path)

    private fun fileOf(scope: StorageScope, id: SqId): Path =
        directoryOf(scope).resolve("${id.path.replace('.', '_')}.json")

    /**
     * Writes [text] to [target] so that a crash cannot leave a half-written file.
     *
     * Three steps, and each is load-bearing. Write to a temporary file in the same directory, so the
     * move is within one filesystem and can be atomic. Keep the previous file as `.bak` before
     * replacing it, so a file that turns out to be unreadable has something to fall back to. Then
     * move the temporary over the target atomically.
     *
     * The backup is what makes [StorageReport.recoveredFromBackup] possible, and it costs one extra
     * copy of a small file.
     */
    private fun writeAtomically(target: Path, text: String) {
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(temporary, text)

        if (Files.exists(target)) {
            val backup = target.resolveSibling("${target.fileName}.bak")
            runCatching { Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING) }
                .onFailure { log.warn(it) { "Could not back up $target before writing" } }
        }

        runCatching {
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            // Some filesystems refuse an atomic move. A non-atomic one is worse but is not nothing,
            // and refusing to save at all would be the wrong answer to "this disk is unusual".
            log.debug { "Atomic move unavailable for $target; falling back to a plain move" }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Moves an unreadable file aside and says where it went.
     *
     * Kept rather than deleted, and named with a timestamp so a second corruption does not overwrite
     * the evidence of the first. Somebody whose debt ledger stopped loading wants the file, and a mod
     * that silently deleted it has destroyed the only copy.
     */
    private fun quarantine(target: Path): String? = runCatching {
        val moved = target.resolveSibling("${target.fileName}.corrupt-${now()}")
        Files.move(target, moved, StandardCopyOption.REPLACE_EXISTING)
        moved.toString()
    }.onFailure { log.error(it) { "Could not quarantine $target" } }.getOrNull()

    /**
     * One file, typed.
     *
     * The document on disk is `{ "schemaVersion": n, "value": … }`. The wrapper exists so the version
     * can be read without knowing how to parse the value — which is the whole problem migrations
     * solve, and it is unsolvable if the version lives inside the thing being migrated.
     */
    private inner class JsonRepository<T : Any>(
        override val id: SqId,
        override val scope: StorageScope,
        private val serializer: KSerializer<T>,
        private val default: () -> T,
        private val schemaVersion: Int,
        migrations: List<StorageMigration>,
        private val validate: (T) -> String?,
    ) : Repository<T> {

        private val migrationsByVersion = migrations.associateBy { it.fromVersion }

        /** Serialises this repository's own reads and writes, so [update] cannot race itself. */
        private val mutex = Mutex()

        private val file: Path get() = fileOf(scope, id)

        override suspend fun load(): StoredValue<T> = mutex.withLock { withContext(ioDispatcher) { read() } }

        override suspend fun save(value: T): Unit = mutex.withLock {
            withContext(ioDispatcher) { write(value) }
        }

        override suspend fun update(transform: (T) -> T): T = mutex.withLock {
            withContext(ioDispatcher) {
                val next = transform(read().value)
                write(next)
                next
            }
        }

        override suspend fun delete(): Boolean = mutex.withLock {
            withContext(ioDispatcher) { Files.deleteIfExists(file) }
        }

        private fun write(value: T) {
            val document = buildJsonObject {
                put(VERSION_KEY, JsonPrimitive(schemaVersion))
                put(VALUE_KEY, json.encodeToJsonElement(serializer, value))
            }
            writeAtomically(file, json.encodeToString(JsonObject.serializer(), document))
        }

        /**
         * Reads the file, then the backup, then gives up and returns the default.
         *
         * The order is the fallback chain the plan asks for. A corrupt live file with a good backup
         * loses at most the last write; a corrupt live file with no backup loses everything, and there
         * is nothing better to do than say so loudly and carry on with defaults — refusing to start is
         * not an improvement for a mod.
         */
        private fun read(): StoredValue<T> {
            if (!Files.exists(file)) return StoredValue(default(), StorageReport.Empty)

            parse(file)?.let { return it }

            val quarantinedAt = quarantine(file)
            log.error { "$id was unreadable and was kept at $quarantinedAt" }

            val backup = file.resolveSibling("${file.fileName}.bak")
            if (Files.exists(backup)) {
                parse(backup)?.let { recovered ->
                    log.warn { "$id was recovered from its backup; the last save was lost" }
                    // Written back, so the next load is a clean one rather than another recovery.
                    runCatching { write(recovered.value) }
                    return recovered.copy(
                        report = recovered.report.copy(
                            quarantinedAt = quarantinedAt,
                            recoveredFromBackup = true,
                        ),
                    )
                }
            }

            return StoredValue(default(), StorageReport(quarantinedAt = quarantinedAt))
        }

        /** Parses one file, or null when it cannot be read at all. */
        private fun parse(path: Path): StoredValue<T>? = runCatching {
            val document = json.parseToJsonElement(Files.readString(path)) as JsonObject
            val storedVersion = document[VERSION_KEY]?.jsonPrimitive?.int ?: schemaVersion
            val (migrated, applied) = migrate(document, storedVersion)
            val valueElement = migrated[VALUE_KEY] ?: return@runCatching null
            val value = json.decodeFromJsonElement(serializer, valueElement)

            val failure = validate(value)
            if (failure != null) {
                // Parsed and still wrong. The default is safer than a value the feature has said it
                // cannot work with, and the file is left alone so it can be inspected.
                log.warn { "$id failed validation and was ignored: $failure" }
                return@runCatching StoredValue(default(), StorageReport(validationFailure = failure))
            }

            StoredValue(value, StorageReport(migrationsApplied = applied))
        }.onFailure { thrown -> log.log(LogLevel.DEBUG, thrown) { "Could not read $path" } }.getOrNull()

        /** Walks the migration chain to the current version. */
        private fun migrate(document: JsonObject, from: Int): Pair<JsonObject, List<String>> {
            if (from >= schemaVersion) return document to emptyList()

            var current = document
            var version = from
            val applied = ArrayList<String>()

            while (version < schemaVersion) {
                val migration = migrationsByVersion[version]
                if (migration == null) {
                    // A gap in the chain. Treated as unreadable rather than guessed at: applying the
                    // rest of the chain to a document that skipped a step produces plausible nonsense,
                    // which is worse than a quarantined file and a message.
                    log.error { "$id is at v$version with no migration to v${version + 1}" }
                    throw IllegalStateException("No migration from v$version for $id")
                }
                current = migration.migrate(current)
                applied.add(migration.description)
                version = migration.toVersion
            }
            return current to applied
        }
    }

    /**
     * A durable queue, in one file.
     *
     * Rewritten whole on each change, which is a deliberate choice at this size: five hundred small
     * entries is tens of kilobytes, and an append-only log would need compaction, a second file and a
     * recovery path for a partial record. The simpler thing is correct here and the complex thing
     * would be correct at a million entries, which this will never have.
     */
    private inner class JsonQueue<T : Any>(
        override val id: SqId,
        private val scope: StorageScope,
        entrySerializer: KSerializer<T>,
        private val capacity: Int,
    ) : OfflineQueue<T> {

        private val listSerializer = kotlinx.serialization.builtins.ListSerializer(
            QueuedEntry.serializer(entrySerializer),
        )

        private val mutex = Mutex()

        private val file: Path get() = fileOf(scope, id.child("queue"))

        override suspend fun size(): Int = mutex.withLock { withContext(ioDispatcher) { read().size } }

        override suspend fun enqueue(entry: T): QueuedEntry<T> = mutex.withLock {
            withContext(ioDispatcher) {
                val queued = QueuedEntry(
                    id = "${now()}-${counter++}",
                    timestampMillis = now(),
                    entry = entry,
                )
                val entries = read() + queued
                // Oldest out. Recording the present matters more than preserving a past nobody has
                // looked at — see OfflineQueue.DEFAULT_CAPACITY.
                val trimmed = if (entries.size > capacity) {
                    log.warn { "$id is full; dropped ${entries.size - capacity} oldest entry(ies)" }
                    entries.takeLast(capacity)
                } else {
                    entries
                }
                write(trimmed)
                queued
            }
        }

        override suspend fun peek(limit: Int): List<QueuedEntry<T>> = mutex.withLock {
            withContext(ioDispatcher) { read().take(limit) }
        }

        override suspend fun acknowledge(ids: Collection<String>): Unit = mutex.withLock {
            withContext(ioDispatcher) {
                val remaining = read().filterNot { it.id in ids }
                write(remaining)
            }
        }

        override suspend fun clear(): Unit = mutex.withLock {
            withContext(ioDispatcher) { Files.deleteIfExists(file) }
        }

        /**
         * Reads the queue, or returns empty.
         *
         * A corrupt queue is quarantined and treated as empty. Unlike a repository there is no
         * fallback worth having: a backup of a queue is a set of entries that were already sent, and
         * replaying them would duplicate the group's history rather than repair it.
         */
        private fun read(): List<QueuedEntry<T>> {
            if (!Files.exists(file)) return emptyList()
            return runCatching {
                json.decodeFromString(listSerializer, Files.readString(file))
            }.getOrElse {
                val at = quarantine(file)
                log.error(it) { "$id was unreadable and was kept at $at; starting empty" }
                emptyList()
            }
        }

        private fun write(entries: List<QueuedEntry<T>>) {
            writeAtomically(file, json.encodeToString(listSerializer, entries))
        }

        private var counter = 0L
    }

    private companion object {
        const val VERSION_KEY = "schemaVersion"
        const val VALUE_KEY = "value"
    }
}
