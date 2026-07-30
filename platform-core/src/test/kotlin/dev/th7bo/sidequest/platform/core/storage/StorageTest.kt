package dev.th7bo.sidequest.platform.core.storage

import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.skyblock.SkyBlockProfile
import dev.th7bo.sidequest.platform.storage.StorageMigration
import dev.th7bo.sidequest.platform.storage.StorageScope
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries

/**
 * The persistence layer, against a real temporary directory.
 *
 * Real files rather than an in-memory fake, on purpose: what is being tested is atomicity, quarantine
 * and recovery, and all three are properties of what ends up on a disk. A fake filesystem would let
 * every one of them pass while being wrong.
 */
class StorageTest {

    @Serializable
    private data class Ledger(val debts: Map<String, Long> = emptyMap(), val note: String = "")

    @Serializable
    private data class Drop(val item: String, val amount: Int = 1)

    @TempDir
    lateinit var root: Path

    private lateinit var storage: JsonFileStorage
    private var clock = 1_000L

    private val ledgerId = SqId.sidequest("debts.ledger")

    @BeforeEach
    fun setUp() {
        storage = JsonFileStorage(root, NoopLogger, now = { clock })
    }

    private fun ledger(
        scope: StorageScope = StorageScope.Global,
        schemaVersion: Int = 1,
        migrations: List<StorageMigration> = emptyList(),
        validate: (Ledger) -> String? = { null },
    ) = storage.repository(
        id = ledgerId,
        scope = scope,
        serializer = Ledger.serializer(),
        default = { Ledger() },
        schemaVersion = schemaVersion,
        migrations = migrations,
        validate = validate,
    )

    private fun fileFor(scope: StorageScope = StorageScope.Global): Path =
        root.resolve(scope.path).resolve("debts_ledger.json")

    // ---------------------------------------------------------------
    // The basics
    // ---------------------------------------------------------------

    @Test
    fun `a first load is empty, not a failure`() = runTest {
        val loaded = ledger().load()
        assertEquals(Ledger(), loaded.value)
        assertTrue(loaded.report.wasEmpty)
        assertTrue(loaded.report.isClean)
    }

    @Test
    fun `what is saved comes back`() = runTest {
        val repository = ledger()
        repository.save(Ledger(mapOf("alice" to 500L), note = "spider slayer"))

        val loaded = repository.load()
        assertEquals(mapOf("alice" to 500L), loaded.value.debts)
        assertEquals("spider slayer", loaded.value.note)
        assertTrue(loaded.report.isClean)
        assertFalse(loaded.report.wasEmpty)
    }

    @Test
    fun `update reads, transforms and writes in one go`() = runTest {
        val repository = ledger()
        repository.save(Ledger(mapOf("alice" to 500L)))

        val updated = repository.update { it.copy(debts = it.debts + ("bob" to 100L)) }

        assertEquals(mapOf("alice" to 500L, "bob" to 100L), updated.debts)
        assertEquals(updated, repository.load().value)
    }

    @Test
    fun `deleting removes the file and the next load is empty`() = runTest {
        val repository = ledger()
        repository.save(Ledger(mapOf("alice" to 1L)))

        assertTrue(repository.delete())
        assertFalse(repository.delete(), "there is nothing left to delete")
        assertTrue(repository.load().report.wasEmpty)
    }

    /**
     * The same id and scope give the same object.
     *
     * Not a cache for speed: two objects over one file could not serialise their writes against each
     * other, and `update` from both would race and lose one.
     */
    @Test
    fun `one repository object per id and scope`() {
        assertTrue(ledger() === ledger())
        assertFalse(ledger() === ledger(scope = StorageScope.Cache))
    }

    // ---------------------------------------------------------------
    // Scopes
    // ---------------------------------------------------------------

    /**
     * A profile's data is not the account's.
     *
     * An Ironman profile's ledger has nothing to do with the main profile's, and a feature that stored
     * them together would show one profile's numbers on the other.
     */
    @Test
    fun `scopes do not see each other's data`() = runTest {
        val account = PlayerId("11111111-1111-4111-8111-111111111111")
        val mango = ledger(StorageScope.Profile(account, SkyBlockProfile("Mango")))
        val lemon = ledger(StorageScope.Profile(account, SkyBlockProfile("Lemon")))
        val global = ledger(StorageScope.Global)

        mango.save(Ledger(mapOf("alice" to 1L)))
        lemon.save(Ledger(mapOf("bob" to 2L)))

        assertEquals(mapOf("alice" to 1L), mango.load().value.debts)
        assertEquals(mapOf("bob" to 2L), lemon.load().value.debts)
        assertTrue(global.load().report.wasEmpty)
    }

    /**
     * A profile is keyed on the account as well as the name.
     *
     * A profile name is only unique within an account — two people can both have a "Mango" — so keying
     * on the name alone would merge two players' data the moment the files were shared or synced.
     */
    @Test
    fun `two accounts can hold a profile of the same name`() = runTest {
        val alice = PlayerId("11111111-1111-4111-8111-111111111111")
        val bob = PlayerId("22222222-2222-4222-8222-222222222222")
        val profile = SkyBlockProfile("Mango")

        ledger(StorageScope.Profile(alice, profile)).save(Ledger(mapOf("x" to 1L)))
        ledger(StorageScope.Profile(bob, profile)).save(Ledger(mapOf("y" to 2L)))

        assertEquals(mapOf("x" to 1L), ledger(StorageScope.Profile(alice, profile)).load().value.debts)
        assertEquals(mapOf("y" to 2L), ledger(StorageScope.Profile(bob, profile)).load().value.debts)
    }

    // ---------------------------------------------------------------
    // Atomic writes and backups
    // ---------------------------------------------------------------

    /** A crash mid-write must leave the previous file, never a truncated one. */
    @Test
    fun `no temporary file survives a completed write`() = runTest {
        ledger().save(Ledger(mapOf("alice" to 1L)))

        val leftovers = root.resolve("global").listDirectoryEntries()
            .filter { it.fileName.toString().endsWith(".tmp") }
        assertEquals(emptyList<Path>(), leftovers)
    }

    @Test
    fun `the previous version is kept as a backup`() = runTest {
        val repository = ledger()
        repository.save(Ledger(mapOf("first" to 1L)))
        repository.save(Ledger(mapOf("second" to 2L)))

        val backup = fileFor().resolveSibling("${fileFor().fileName}.bak")
        assertTrue(Files.exists(backup))
        assertTrue("first" in Files.readString(backup))
    }

    // ---------------------------------------------------------------
    // Corruption, quarantine and recovery
    // ---------------------------------------------------------------

    /**
     * A corrupt file is kept, not deleted.
     *
     * Somebody whose ledger stopped loading wants the file. A mod that deleted it has destroyed the
     * only copy of data it was trusted with.
     */
    @Test
    fun `an unreadable file is quarantined and reported`() = runTest {
        val repository = ledger()
        repository.save(Ledger(mapOf("alice" to 1L)))
        // Corrupt both the file and its backup, so there is nothing to recover from.
        Files.writeString(fileFor(), "{ not json at all")
        Files.deleteIfExists(fileFor().resolveSibling("${fileFor().fileName}.bak"))

        val loaded = repository.load()

        assertEquals(Ledger(), loaded.value, "the default is used")
        assertNotNull(loaded.report.quarantinedAt)
        assertFalse(loaded.report.isClean)
        assertTrue(Files.exists(Path.of(loaded.report.quarantinedAt!!)), "the evidence is kept")
    }

    /** A second corruption must not overwrite the evidence of the first. */
    @Test
    fun `two corruptions produce two quarantined files`() = runTest {
        val repository = ledger()

        Files.createDirectories(fileFor().parent)
        Files.writeString(fileFor(), "broken one")
        val first = repository.load().report.quarantinedAt

        clock += 1_000
        Files.writeString(fileFor(), "broken two")
        val second = repository.load().report.quarantinedAt

        assertNotNull(first)
        assertNotNull(second)
        assertFalse(first == second)
        assertTrue(Files.exists(Path.of(first!!)))
        assertTrue(Files.exists(Path.of(second!!)))
    }

    /**
     * The fallback chain: live file, then backup, then defaults.
     *
     * A corrupt live file with a good backup loses at most the last save, which is a far better outcome
     * than losing a ledger.
     */
    @Test
    fun `a corrupt file falls back to the backup`() = runTest {
        val repository = ledger()
        repository.save(Ledger(mapOf("first" to 1L)))
        repository.save(Ledger(mapOf("second" to 2L)))
        Files.writeString(fileFor(), "{ corrupt")

        val loaded = repository.load()

        assertEquals(mapOf("first" to 1L), loaded.value.debts, "the backup holds the save before last")
        assertTrue(loaded.report.recoveredFromBackup)
        assertNotNull(loaded.report.quarantinedAt)
    }

    /** After a recovery the value is written back, so the next load is clean rather than another recovery. */
    @Test
    fun `a recovery leaves the store in a clean state`() = runTest {
        val repository = ledger()
        repository.save(Ledger(mapOf("first" to 1L)))
        repository.save(Ledger(mapOf("second" to 2L)))
        Files.writeString(fileFor(), "{ corrupt")

        repository.load()
        val second = repository.load()

        assertTrue(second.report.isClean)
        assertEquals(mapOf("first" to 1L), second.value.debts)
    }

    // ---------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------

    /**
     * Parsing is not validation.
     *
     * A ledger with a negative debt parses perfectly and is still nonsense. The default is safer than a
     * value the feature has said it cannot work with.
     */
    @Test
    fun `a value that parses and is still wrong is rejected`() = runTest {
        val repository = ledger(validate = { value ->
            if (value.debts.values.any { it < 0 }) "a debt cannot be negative" else null
        })
        repository.save(Ledger(mapOf("alice" to -5L)))

        val loaded = repository.load()

        assertEquals(Ledger(), loaded.value)
        assertEquals("a debt cannot be negative", loaded.report.validationFailure)
        assertTrue(Files.exists(fileFor()), "the file is left alone so it can be inspected")
    }

    // ---------------------------------------------------------------
    // Migrations
    // ---------------------------------------------------------------

    private fun renameNote(from: Int) = object : StorageMigration {
        override val fromVersion: Int = from
        override val description: String = "v$from: rename comment to note"
        override fun migrate(document: JsonObject): JsonObject = buildJsonObject {
            put("schemaVersion", JsonPrimitive(from + 1))
            val value = document["value"]!!.jsonObject
            put(
                "value",
                buildJsonObject {
                    put("debts", value["debts"]!!)
                    put("note", value["comment"] ?: JsonPrimitive(""))
                },
            )
        }
    }

    @Test
    fun `a stored document is migrated to the current version`() = runTest {
        Files.createDirectories(fileFor().parent)
        Files.writeString(
            fileFor(),
            """{"schemaVersion":1,"value":{"debts":{"alice":500},"comment":"old field"}}""",
        )

        val loaded = ledger(schemaVersion = 2, migrations = listOf(renameNote(1))).load()

        assertEquals("old field", loaded.value.note)
        assertEquals(mapOf("alice" to 500L), loaded.value.debts)
        assertEquals(listOf("v1: rename comment to note"), loaded.report.migrationsApplied)
    }

    /** A file from two versions back reaches the present by composition, not by a special case. */
    @Test
    fun `a chain of migrations is applied in order`() = runTest {
        Files.createDirectories(fileFor().parent)
        Files.writeString(
            fileFor(),
            """{"schemaVersion":1,"value":{"debts":{"alice":500},"comment":"from v1"}}""",
        )

        val bumpOnly = object : StorageMigration {
            override val fromVersion: Int = 2
            override val description: String = "v2: no change"
            override fun migrate(document: JsonObject): JsonObject = buildJsonObject {
                put("schemaVersion", JsonPrimitive(3))
                put("value", document["value"]!!)
            }
        }

        val loaded = ledger(schemaVersion = 3, migrations = listOf(renameNote(1), bumpOnly)).load()

        assertEquals("from v1", loaded.value.note)
        assertEquals(listOf("v1: rename comment to note", "v2: no change"), loaded.report.migrationsApplied)
    }

    /**
     * A gap in the chain is a corrupt file, not a guess.
     *
     * Applying the rest of the chain to a document that skipped a step produces plausible nonsense,
     * which is worse than a quarantined file and a message somebody can act on.
     */
    @Test
    fun `a missing migration quarantines rather than guesses`() = runTest {
        Files.createDirectories(fileFor().parent)
        Files.writeString(fileFor(), """{"schemaVersion":1,"value":{"debts":{},"note":""}}""")

        val loaded = ledger(schemaVersion = 3, migrations = listOf(renameNote(1))).load()

        assertEquals(Ledger(), loaded.value)
        assertNotNull(loaded.report.quarantinedAt)
    }

    @Test
    fun `a migration that skips a version is refused at registration`() {
        val skipping = object : StorageMigration {
            override val fromVersion: Int = 1
            override val toVersion: Int = 3
            override val description: String = "skips v2"
            override fun migrate(document: JsonObject): JsonObject = document
        }
        val thrown = runCatching { ledger(schemaVersion = 3, migrations = listOf(skipping)) }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(thrown is IllegalArgumentException)
    }

    /** A file written by a newer build is left alone rather than mangled by a downgrade. */
    @Test
    fun `a document from a future version is read as-is`() = runTest {
        Files.createDirectories(fileFor().parent)
        Files.writeString(
            fileFor(),
            """{"schemaVersion":9,"value":{"debts":{"alice":1},"note":"from the future"}}""",
        )

        val loaded = ledger(schemaVersion = 1).load()

        assertEquals("from the future", loaded.value.note)
        assertEquals(emptyList<String>(), loaded.report.migrationsApplied)
    }

    /**
     * Fields the running build does not know are preserved.
     *
     * A user who runs an older build and then upgrades must not silently lose the settings the older
     * build could not see.
     */
    @Test
    fun `an unknown field does not stop the rest from loading`() = runTest {
        Files.createDirectories(fileFor().parent)
        Files.writeString(
            fileFor(),
            """{"schemaVersion":1,"value":{"debts":{"alice":1},"note":"n","futureField":42}}""",
        )

        val loaded = ledger().load()
        assertEquals(mapOf("alice" to 1L), loaded.value.debts)
        assertTrue(loaded.report.isClean)
    }

    // ---------------------------------------------------------------
    // The offline queue
    // ---------------------------------------------------------------

    private fun queue(capacity: Int = 500) = storage.queue(
        id = SqId.sidequest("sync.outbox"),
        scope = StorageScope.Global,
        serializer = Drop.serializer(),
        capacity = capacity,
    )

    @Test
    fun `entries come back in the order they went in`() = runTest {
        val outbox = queue()
        outbox.enqueue(Drop("Hyperion"))
        clock += 1
        outbox.enqueue(Drop("Necron's Blade"))

        assertEquals(2, outbox.size())
        assertEquals(listOf("Hyperion", "Necron's Blade"), outbox.peek().map { it.entry.item })
    }

    /**
     * Peek does not remove.
     *
     * The caller has not sent them yet. Removing on read would lose the batch to any failure between
     * here and the network, which is the exact situation the queue exists for.
     */
    @Test
    fun `peeking twice returns the same entries`() = runTest {
        val outbox = queue()
        outbox.enqueue(Drop("Hyperion"))

        assertEquals(1, outbox.peek().size)
        assertEquals(1, outbox.peek().size)
        assertEquals(1, outbox.size())
    }

    @Test
    fun `acknowledging removes exactly those entries`() = runTest {
        val outbox = queue()
        val first = outbox.enqueue(Drop("Hyperion"))
        clock += 1
        outbox.enqueue(Drop("Necron's Blade"))

        outbox.acknowledge(listOf(first.id))

        assertEquals(listOf("Necron's Blade"), outbox.peek().map { it.entry.item })
    }

    @Test
    fun `acknowledging something that is not there is harmless`() = runTest {
        val outbox = queue()
        outbox.enqueue(Drop("Hyperion"))
        outbox.acknowledge(listOf("not-an-id"))
        assertEquals(1, outbox.size())
    }

    /**
     * At capacity the oldest is dropped, not the newest refused.
     *
     * A queue that refuses new entries when full stops recording the present in order to preserve a
     * past nobody has looked at, and the present is what the player is doing right now.
     */
    @Test
    fun `a full queue drops the oldest entry`() = runTest {
        val outbox = queue(capacity = 3)
        repeat(5) { index ->
            clock += 1
            outbox.enqueue(Drop("item$index"))
        }

        assertEquals(3, outbox.size())
        assertEquals(listOf("item2", "item3", "item4"), outbox.peek().map { it.entry.item })
    }

    /** The timestamp is when it happened, which is the whole reason for queueing. */
    @Test
    fun `an entry remembers when it happened, not when it is sent`() = runTest {
        clock = 2_000_000
        val queued = queue().enqueue(Drop("Hyperion"))
        clock = 9_000_000

        assertEquals(2_000_000, queued.timestampMillis)
        assertEquals(2_000_000, queue().peek().single().timestampMillis)
    }

    @Test
    fun `entries survive a new storage instance`() = runTest {
        queue().enqueue(Drop("Hyperion"))

        val reopened = JsonFileStorage(root, NoopLogger, now = { clock }).queue(
            id = SqId.sidequest("sync.outbox"),
            scope = StorageScope.Global,
            serializer = Drop.serializer(),
        )
        assertEquals(listOf("Hyperion"), reopened.peek().map { it.entry.item })
    }

    /**
     * A corrupt queue starts empty and does not fall back to a backup.
     *
     * Unlike a repository there is no fallback worth having: a backup of a queue holds entries that
     * were already sent, and replaying them would duplicate the group's history rather than repair it.
     */
    @Test
    fun `a corrupt queue starts empty rather than replaying a backup`() = runTest {
        val outbox = queue()
        outbox.enqueue(Drop("Hyperion"))
        clock += 1
        outbox.enqueue(Drop("Necron's Blade"))

        val file = root.resolve("global").resolve("sync_outbox_queue.json")
        assertTrue(Files.exists(file)) { "expected the queue at $file, found ${root.resolve("global").listDirectoryEntries()}" }
        Files.writeString(file, "not json")

        assertEquals(0, outbox.size())
        outbox.enqueue(Drop("after the corruption"))
        assertEquals(listOf("after the corruption"), outbox.peek().map { it.entry.item })
    }

    @Test
    fun `clearing empties the queue`() = runTest {
        val outbox = queue()
        outbox.enqueue(Drop("Hyperion"))
        outbox.clear()
        assertEquals(0, outbox.size())
    }

    @Test
    fun `the schema version is written where it can be read without parsing the value`() = runTest {
        ledger(schemaVersion = 7).save(Ledger())
        val document = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(fileFor())).jsonObject
        assertEquals(7, document["schemaVersion"]?.jsonPrimitive?.int)
        assertNotNull(document["value"])
    }

    @Test
    fun `flush returns rather than hanging`() = runTest {
        ledger().save(Ledger())
        storage.flush()
        assertNull(null)
    }
}
