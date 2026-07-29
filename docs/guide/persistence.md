# Persistence and profiles

## Where things live

```
config/<modid>/
├── profiles/
│   ├── default/
│   │   ├── config.json     settings
│   │   └── huds.json       HUD placements
│   └── pvp/
│       └── …
└── backups/
    └── default-config.json-1738245912345
```

Everything is per-profile. The `backups` directory holds quarantined files — see
[corruption](#corruption), below.

---

## Setting it up

```kotlin
val persistence = ConfigPersistenceController(
    screen = myConfigScreen,
    store = JsonFileConfigStore(
        root = FabricLoader.getInstance().configDir.resolve("mymod"),
        currentVersion = CONFIG_SCHEMA_VERSION,
        migrations = listOf(/* … */),
    ),
    coroutineScope = ioScope,
    scheduler = clientScheduler,
    schemaVersion = CONFIG_SCHEMA_VERSION,
)

persistence.load {
    // Auto-save only *after* the initial load, or loading would immediately
    // schedule a write of what was just read.
    persistence.startAutoSave()
}
```

`clientScheduler` is whatever hands work back to the client thread — in the mod,
`Minecraft.execute`.

Writing happens off the UI thread; snapshotting and applying happen on it, because both
touch the reactive graph.

---

## What is guaranteed

**Writes are atomic.** The file is written to a temporary path, forced to disk, then moved
into place with `ATOMIC_MOVE`. A crash mid-write leaves either the old file or the new
one, never a half-written one.

**Unknown fields survive.** A file written by a *newer* build carries settings this build
does not know about. They are preserved and written back untouched, so downgrading and
re-upgrading does not silently discard configuration.

**Absent settings keep their defaults.** A setting missing from the file keeps its current
value rather than being reset. A file written before a setting existed must not drag that
setting to zero the first time a new build runs.

**One bad value does not cost the file.** A value that fails to deserialize is reported
through `LoadReport.rejectedValues` and that setting keeps its default. The rest of the
file still applies.

---

## Auto-save and manual save

```kotlin
persistence.scheduleSave()   // debounced; coalesces a burst of edits into one write
persistence.saveNow()        // immediate, e.g. on screen close
persistence.markSaved()      // marks the current state as the saved point
```

`hasUnsavedChanges` is observable, so a screen can show a dot next to Save.

---

## Corruption

If the file cannot be parsed, it is **moved** to `backups/<profile>-<file>-<timestamp>`
and the load returns defaults with `LoadReport.corruptionBackupPath` set.

Moved, not deleted, and not overwritten in place. Someone who hand-edited their config and
made a typo has lost nothing, and "it was corrupt" is far easier to act on with the
evidence still there.

```kotlin
persistence.onLoadReport = { report ->
    when {
        report.corruptionBackupPath != null ->
            logger.error("Config was unreadable; kept at {}", report.corruptionBackupPath)
        report.rejectedValues.isNotEmpty() ->
            logger.warn("{} value(s) rejected: {}", report.rejectedValues.size, report.rejectedValues)
        report.migrationsApplied.isNotEmpty() ->
            logger.info("Migrated: {}", report.migrationsApplied.joinToString())
    }
}
```

Log it. A silent repair is a bug report you will never receive.

---

## Schema migrations

A migration steps the document **exactly one version**. Chains are applied in order.

```kotlin
class RenameDurationSetting : Migration {
    override val fromVersion = 1
    override val description = "Rename notification.time to notification.duration"

    override fun migrate(document: JsonObject): JsonObject {
        val values = document["values"]?.jsonObject ?: return document
        val old = values["mymod:notification.time"] ?: return document
        return buildJsonObject {
            for ((key, value) in document) put(key, value)
            put("values", buildJsonObject {
                for ((key, value) in values) if (key != "mymod:notification.time") put(key, value)
                put("mymod:notification.duration", old)
            })
        }
    }
}
```

Register them in file order:

```kotlin
JsonFileConfigStore(root, currentVersion = 3, migrations = listOf(
    RenameDurationSetting(),   // 1 -> 2
    SplitThemeSetting(),       // 2 -> 3
))
```

The one-step rule is enforced at construction: a migration declaring a jump is rejected
with a message rather than silently skipping versions.

### When you need one

- **Renaming a `UiId`.** The Kotlin property name is irrelevant; the id is what is stored.
- **Changing a value's shape** — a string becoming an object, a scalar becoming a list.
- **Changing an enum's `serializedId`.**

You do *not* need one for adding a setting (absent values keep defaults) or for removing
one (unknown fields are preserved, so a later re-add finds its old value).

---

## Profiles

```kotlin
val profiles = ProfileManager(store)

profiles.list()                              // suspend
profiles.create(ProfileId("pvp"), copyFrom = ProfileId.DEFAULT)
profiles.duplicate(ProfileId("pvp"), ProfileId("pvp-backup"))
profiles.rename(ProfileId("pvp"), ProfileId("combat"))
profiles.delete(ProfileId("combat"))
profiles.export(ProfileId.DEFAULT)           // returns a ConfigSnapshot
profiles.import(ProfileId("shared"), snapshot)
```

Switching the active profile is a load:

```kotlin
persistence.load(ProfileId("pvp")) { /* applied */ }
```

**Not yet wired:** the configuration screen declares profile action buttons but they are
not connected to `ProfileManager`. The manager itself is tested and usable from code.

---

## HUD placement

HUD positions persist through the same store, in `huds.json`:

```kotlin
val hudLayout = HudLayoutPersistence(
    layer = hudLayer,
    store = JsonFileConfigStore(root, HUD_SCHEMA_VERSION, fileName = HudLayoutPersistence.FILE_NAME),
    coroutineScope = ioScope,
    scheduler = clientScheduler,
    schemaVersion = HUD_SCHEMA_VERSION,
)
hudLayout.load(ProfileId.DEFAULT)
```

Placement is stored as an **anchor plus an offset**, never absolute pixels, so an element
keeps its relationship to the screen edge it was placed against when the resolution or GUI
scale changes.

A placement for a HUD that is not currently loaded is preserved rather than dropped — if a
module is disabled for one session, rewriting the file must not cost it its position.
