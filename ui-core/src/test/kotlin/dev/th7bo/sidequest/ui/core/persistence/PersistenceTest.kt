package dev.th7bo.sidequest.ui.core.persistence

import dev.th7bo.sidequest.ui.binding.asBinding
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.ids.ProfileId
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.persistence.ConfigSnapshot
import dev.th7bo.sidequest.ui.persistence.Migration
import dev.th7bo.sidequest.ui.persistence.MigrationException
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.testkit.ImmediateScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PersistenceTest {

    @TempDir
    lateinit var root: Path

    private fun id(path: String) = UiId.of("sidequest", path)

    private lateinit var notifications: MutableUiState<Boolean>
    private lateinit var duration: MutableUiState<Int>
    private lateinit var name: MutableUiState<String>
    private lateinit var screen: ConfigScreen

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        notifications = mutableStateOf(true)
        duration = mutableStateOf(5)
        name = mutableStateOf("default name")

        screen = configScreen(id("main"), "Sidequest") {
            category(id("general"), "General") {
                section("Interface") {
                    toggle(id("general.notifications"), "Notifications", notifications.asBinding())
                    slider(id("general.duration"), "Duration", duration.asBinding(), 1..60, default = 5)
                    textField(id("general.name"), "Name", name.asBinding())
                    button(id("general.reset"), "Reset") { }
                }
            }
        }
    }

    @AfterEach
    fun tearDown() {
        resetReactiveGraphForTesting()
    }

    private fun store(version: Int = 1, migrations: List<Migration> = emptyList()) =
        JsonFileConfigStore(root, version, migrations)

    private fun configFile(profile: ProfileId = ProfileId.DEFAULT): Path =
        root.resolve("profiles").resolve(profile.value).resolve("config.json")

    private fun snapshotOf(vararg pairs: Pair<String, Any>): ConfigSnapshot = ConfigSnapshot(
        1,
        pairs.associate { (path, value) ->
            id(path) to when (value) {
                is Boolean -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        },
    )

    // -- round trip ---------------------------------------------------------

    @Test
    fun `a snapshot round-trips through the store`() = runBlocking {
        val store = store()
        val original = snapshotOf(
            "general.notifications" to false,
            "general.duration" to 42,
            "general.name" to "hello",
        )

        store.save(ProfileId.DEFAULT, original)
        val loaded = store.load(ProfileId.DEFAULT)

        assertEquals(3, loaded.snapshot.size)
        assertEquals(false, loaded.snapshot[id("general.notifications")]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("42", loaded.snapshot[id("general.duration")]?.jsonPrimitive?.content)
        assertTrue(loaded.report.isClean)
    }

    @Test
    fun `loading a profile that was never saved is an empty result, not an error`() = runBlocking {
        val loaded = store().load(ProfileId.DEFAULT)

        assertTrue(loaded.report.wasEmpty)
        assertEquals(0, loaded.snapshot.size)
        assertTrue(loaded.report.isClean)
    }

    @Test
    fun `the written file is readable json with a schema version`() = runBlocking {
        store().save(ProfileId.DEFAULT, snapshotOf("general.notifications" to true))

        val document = kotlinx.serialization.json.Json
            .parseToJsonElement(configFile().readText()).jsonObject

        assertEquals(1, document["schemaVersion"]?.jsonPrimitive?.content?.toInt())
        assertNotNull(document["values"]?.jsonObject?.get("sidequest:general.notifications"))
    }

    @Test
    fun `keys are stable ids, so renaming a kotlin property changes nothing on disk`() = runBlocking {
        store().save(ProfileId.DEFAULT, snapshotOf("general.notifications" to true))

        assertTrue(
            configFile().readText().contains("sidequest:general.notifications"),
            "the on-disk key must be the namespaced id",
        )
    }

    // -- atomicity ----------------------------------------------------------

    @Test
    fun `writing leaves no temporary file behind`() = runBlocking {
        val store = store()
        store.save(ProfileId.DEFAULT, snapshotOf("general.duration" to 1))

        val strays = configFile().parent.listDirectoryEntries().filter { it.toString().endsWith(".tmp") }

        assertTrue(strays.isEmpty(), "found leftover temporary files: $strays")
    }

    @Test
    fun `an overwrite replaces the previous contents completely`() = runBlocking {
        val store = store()
        store.save(ProfileId.DEFAULT, snapshotOf("general.duration" to 99999))
        val longVersion = configFile().readText().length

        store.save(ProfileId.DEFAULT, snapshotOf("general.duration" to 1))
        val shortVersion = configFile().readText()

        assertTrue(shortVersion.length < longVersion, "the file must be truncated, not patched")
        assertFalse(shortVersion.contains("99999"), "no remnant of the previous write may survive")
    }

    // -- corruption recovery ------------------------------------------------

    @Test
    fun `a corrupt file is quarantined and defaults are loaded`() = runBlocking {
        configFile().parent.createDirectories()
        configFile().writeText("{ this is not valid json ")

        val loaded = store().load(ProfileId.DEFAULT)

        assertEquals(0, loaded.snapshot.size, "loading falls back to defaults")
        assertNotNull(loaded.report.corruptionBackupPath)
        assertFalse(loaded.report.isClean)
        assertFalse(configFile().exists(), "the bad file is moved aside, not left in place")

        val backups = root.resolve("backups").listDirectoryEntries()
        assertEquals(1, backups.size)
        assertTrue(
            backups.single().readText().contains("not valid json"),
            "the evidence must be preserved, not deleted",
        )
    }

    @Test
    fun `a malformed key is preserved rather than silently dropped`() = runBlocking {
        configFile().parent.createDirectories()
        configFile().writeText(
            """
            {
              "schemaVersion": 1,
              "values": {
                "sidequest:general.duration": 7,
                "not a valid id": 12
              }
            }
            """.trimIndent(),
        )

        val loaded = store().load(ProfileId.DEFAULT)

        assertEquals(1, loaded.snapshot.size, "only the well-formed key becomes a value")
        assertTrue(loaded.report.rejectedValues.isNotEmpty())
        assertTrue(loaded.snapshot.unknownFields.keys.any { it.contains("not a valid id") })
    }

    // -- unknown field preservation ----------------------------------------

    @Test
    fun `fields from another build survive a load and save round trip`() = runBlocking {
        configFile().parent.createDirectories()
        configFile().writeText(
            """
            {
              "schemaVersion": 1,
              "values": { "sidequest:general.duration": 7 },
              "someOtherModuleData": { "keep": "me" }
            }
            """.trimIndent(),
        )

        val store = store()
        val loaded = store.load(ProfileId.DEFAULT)
        assertEquals(1, loaded.snapshot.unknownFields.size)

        store.save(ProfileId.DEFAULT, loaded.snapshot)

        val document = kotlinx.serialization.json.Json
            .parseToJsonElement(configFile().readText()).jsonObject
        assertEquals(
            "me",
            document["someOtherModuleData"]?.jsonObject?.get("keep")?.jsonPrimitive?.content,
            "a downgrade-then-upgrade must not lose another module's settings",
        )
    }

    // -- migrations ---------------------------------------------------------

    private class RenameMigration(
        override val fromVersion: Int,
        private val from: String,
        private val to: String,
    ) : Migration {
        override val description: String get() = "v$fromVersion: rename $from to $to"

        override fun migrate(document: JsonObject): JsonObject {
            val values = document["values"]?.jsonObject ?: return document
            return buildJsonObject {
                put("schemaVersion", JsonPrimitive(toVersion))
                put(
                    "values",
                    buildJsonObject {
                        for ((key, element) in values) put(if (key == from) to else key, element)
                    },
                )
                for ((key, element) in document) {
                    if (key != "schemaVersion" && key != "values") put(key, element)
                }
            }
        }
    }

    @Test
    fun `a chain of migrations is applied in order`() = runBlocking {
        configFile().parent.createDirectories()
        configFile().writeText(
            """
            { "schemaVersion": 1, "values": { "sidequest:old.name": 7 } }
            """.trimIndent(),
        )

        val store = store(
            version = 3,
            migrations = listOf(
                RenameMigration(1, "sidequest:old.name", "sidequest:middle.name"),
                RenameMigration(2, "sidequest:middle.name", "sidequest:general.duration"),
            ),
        )
        val loaded = store.load(ProfileId.DEFAULT)

        assertEquals(
            listOf("v1: rename sidequest:old.name to sidequest:middle.name", "v2: rename sidequest:middle.name to sidequest:general.duration"),
            loaded.report.migrationsApplied,
        )
        assertEquals("7", loaded.snapshot[id("general.duration")]?.jsonPrimitive?.content)
        assertNull(loaded.snapshot[id("old.name")])
    }

    @Test
    fun `a missing migration fails loudly rather than guessing`() {
        configFile().parent.createDirectories()
        configFile().writeText("""{ "schemaVersion": 1, "values": {} }""")

        val failure = assertThrows(MigrationException::class.java) {
            runBlocking { store(version = 5).load(ProfileId.DEFAULT) }
        }
        assertEquals(1, failure.fromVersion)
        assertEquals(5, failure.targetVersion)
    }

    @Test
    fun `a file from a newer build is refused rather than misread`() {
        configFile().parent.createDirectories()
        configFile().writeText("""{ "schemaVersion": 99, "values": {} }""")

        val failure = assertThrows(MigrationException::class.java) {
            runBlocking { store(version = 1).load(ProfileId.DEFAULT) }
        }
        assertEquals(99, failure.fromVersion)
    }

    @Test
    fun `a migration that skips versions is rejected at construction`() {
        val skipping = object : Migration {
            override val fromVersion: Int get() = 1
            override val toVersion: Int get() = 5
            override val description: String get() = "skips"
            override fun migrate(document: JsonObject): JsonObject = document
        }

        assertThrows(IllegalArgumentException::class.java) {
            JsonFileConfigStore(root, 5, listOf(skipping))
        }
    }

    // -- profiles -----------------------------------------------------------

    @Test
    fun `profiles are independent`() = runBlocking {
        val store = store()
        val work = ProfileId("work")

        store.save(ProfileId.DEFAULT, snapshotOf("general.duration" to 1))
        store.save(work, snapshotOf("general.duration" to 2))

        assertEquals("1", store.load(ProfileId.DEFAULT).snapshot[id("general.duration")]?.jsonPrimitive?.content)
        assertEquals("2", store.load(work).snapshot[id("general.duration")]?.jsonPrimitive?.content)
    }

    @Test
    fun `listing finds every stored profile in a stable order`() = runBlocking {
        val store = store()
        store.save(ProfileId("zebra"), snapshotOf("general.duration" to 1))
        store.save(ProfileId.DEFAULT, snapshotOf("general.duration" to 1))
        store.save(ProfileId("alpha"), snapshotOf("general.duration" to 1))

        assertEquals(
            listOf(ProfileId("alpha"), ProfileId("default"), ProfileId("zebra")),
            store.listProfiles(),
        )
    }

    @Test
    fun `copy duplicates a profile's values`() = runBlocking {
        val store = store()
        store.save(ProfileId.DEFAULT, snapshotOf("general.duration" to 33))

        store.copy(ProfileId.DEFAULT, ProfileId("backup"))

        assertEquals(
            "33",
            store.load(ProfileId("backup")).snapshot[id("general.duration")]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `delete removes a profile and reports whether there was anything to remove`() = runBlocking {
        val store = store()
        val temp = ProfileId("temp")
        store.save(temp, snapshotOf("general.duration" to 1))

        assertTrue(store.delete(temp))
        assertFalse(store.delete(temp), "deleting twice reports no change")
        assertTrue(store.load(temp).report.wasEmpty)
    }

    @Test
    fun `the default profile cannot be deleted or renamed`() = runBlocking {
        val store = store()
        val controller = ConfigPersistenceController(
            screen, store, CoroutineScope(Dispatchers.Default), ImmediateScheduler(), schemaVersion = 1,
        )
        val profiles = ProfileManager(store, controller)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { profiles.delete(ProfileId.DEFAULT) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { profiles.rename(ProfileId.DEFAULT, ProfileId("other")) }
        }
        controller.dispose()
    }

    @Test
    fun `the default profile is always listed even before it is written`() = runBlocking {
        val store = store()
        val controller = ConfigPersistenceController(
            screen, store, CoroutineScope(Dispatchers.Default), ImmediateScheduler(), schemaVersion = 1,
        )
        val profiles = ProfileManager(store, controller)

        assertEquals(listOf(ProfileId.DEFAULT), profiles.list())
        controller.dispose()
    }
}
