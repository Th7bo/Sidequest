# Getting started

## Installation

Sidequest's UI framework ships inside the mod jar (jar-in-jar), so a mod that depends on
Sidequest gets it automatically.

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.th7bo:sidequest:1.0.0")
}
```

```json
// fabric.mod.json
{
  "depends": {
    "sidequest": "*",
    "fabric-language-kotlin": ">=1.13.13"
  }
}
```

Minecraft 26.1 and later ship unobfuscated, so there is no remapping step and no mappings
to choose. Use plain `implementation`, not `modImplementation`.

---

## The mental model

Four ideas explain most of the framework. If you only read one section, read this one.

### 1. You declare settings; you do not draw them

A `Setting` is a value plus metadata. It holds **no rendering state** — no position, no
focus, no animation progress. That is why the same declaration can drive the screen, the
search index and the file on disk without any of them knowing about each other.

```kotlin
toggle(UiId.of("mymod", "enabled"), "Enabled", bind(config::enabled))
```

You never construct a widget class. Rendering is looked up in a registry by setting type,
which is also what lets someone else register a better renderer for your setting.

### 2. State is pull-based and tracked

`UiState<T>` is read-only and observable. `MutableUiState<T>` adds a setter. Derived state
recomputes lazily, on read, and only when something it actually read has changed.

```kotlin
val level = mutableStateOf(42, "level")
val label = derivedStateOf("levelLabel") { "Lv. ${level.value}" }
```

Reading `.value` inside `derivedStateOf` records a dependency. Reading `.peek()` does not
— use `peek()` when you want the current value without subscribing.

A frame that touches no state performs no recomputation and no layout. That is measured,
not aspirational: see [performance](performance.md).

### 3. The node tree is retained

Nodes persist across frames. That is what preserves focus, scroll position and in-flight
animations. What gets rebuilt every frame is the list of draw commands, not the tree.

Changing something marks it dirty and walks *up* to the root, stopping at the first
ancestor already marked. Touching a leaf costs a short walk up the spine, not a traversal.

### 4. Registration is scope-owned

Anything you register — a setting renderer, a HUD, a world overlay — is registered
*against a `RegistrationScope`*. Disposing that scope removes everything registered
through it.

```kotlin
val scope = RegistrationScope(UiId.of("mymod", "root"))
// ...register things...
scope.dispose()   // and they are all gone, including anything live built from them
```

This is the whole answer to "how do I unload cleanly". There is no manual unregister list
to keep in sync.

---

## Your first screen

```kotlin
object MyModConfig {
    var enabled: Boolean = true
    var duration: Int = 5
    var theme: String = "dark"
}

val screen = configScreen(
    UiId.of("mymod", "config"),
    "My Mod",
    "Configure how My Mod behaves.",
) {
    category(UiId.of("mymod", "general"), "General", icon = Icons.gear) {
        section("Behaviour") {
            toggle(
                UiId.of("mymod", "enabled"),
                "Enabled",
                bind(MyModConfig::enabled),
                description = "Turns the whole thing on and off",
            )
            slider(
                UiId.of("mymod", "duration"),
                "Duration",
                bind(MyModConfig::duration),
                1..30,
                description = "How long notifications stay on screen",
                format = { "$it s" },
            )
        }
    }
}
```

That gives you a screen with a sidebar, search, per-setting reset, and everything needed
for persistence. To show it:

```kotlin
class MyModConfigScreen : SidequestConfigScreen(screen, Sidequest.activeTheme())

// from a keybind, a command, or a mod menu integration:
client.setScreenAndShow(MyModConfigScreen())
```

---

## Identifiers

Every setting, category, section, HUD and overlay has a `UiId`:

```kotlin
UiId.of("mymod", "general.notifications.duration")
```

Ids are the stable name for a thing. They key the persisted file, the search index, and
the component registry, so **changing an id is a breaking change** — the saved value under
the old id will no longer be found. Renaming a Kotlin property is free; renaming a `UiId`
needs a [migration](persistence.md#schema-migrations).

Every id on one screen shares a namespace, and duplicates are rejected at build time with
a message naming both owners. Section ids are derived under a `section.` segment
specifically so a section titled "Notifications" cannot collide with a setting whose path
is `notifications`.

---

## Threading

Everything in the framework is **confined to the UI thread** and checks it. Reading or
writing state from another thread throws rather than corrupting quietly.

Work that must not block rendering — writing a config file, for instance — runs on a
background scope and hands its result back through a `UiScheduler`, which in the mod is
`Minecraft.execute`. You do not need to think about this unless you are writing something
that does its own I/O; if you are, see [persistence](persistence.md).

---

## Where to go next

- [Configuration screens](configuration.md) — every setting type, conditional visibility
- [Bindings and validation](bindings.md) — connecting settings to your own data
- [HUD elements](huds.md) — drawing during gameplay
- [Extending the framework](extending.md) — your own controls and renderers
