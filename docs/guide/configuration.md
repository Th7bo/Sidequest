# Configuration screens

## Structure

A screen has categories; a category has sections; a section has settings.

```kotlin
configScreen(id, "My Mod", "An optional subtitle shown under the title") {
    category(id, "General", description = "Core behaviour", icon = Icons.gear) {
        section("Interface", description = "How it looks") {
            // settings
        }
    }
}
```

Categories are the sidebar. Sections are the cards inside the scrolling list. Both take an
optional `icon`; a section without one reserves the space so titles stay aligned but draws
no icon block.

Sections can collapse:

```kotlin
section("Advanced", collapsible = true, startsCollapsed = true) { ... }
```

A collapsed section's content is *detached* from the tree rather than hidden, so it costs
nothing to measure. On a screen of mostly-closed sections that is the difference between
cheap and merely quiet.

---

## Setting types

All of these live inside a `section { }` block.

### Toggle

```kotlin
toggle(id, "Enabled", bind(config::enabled), description = "…")
```

### Button

```kotlin
button(id, "Reset everything", label = "Reset", destructive = true) {
    config.resetAll()
}
```

The trailing lambda is the action. `destructive = true` styles it as dangerous; it does
not change behaviour on its own — pair it with a confirmation (below).

### Sliders

```kotlin
slider(id, "Duration", bind(config::duration), 1..30, format = { "$it s" })
decimalSlider(id, "Opacity", bind(config::opacity), 0f..1f, step = 0.05f)
```

`step` snaps the value. `format` controls the readout only.

### Text

```kotlin
textField(id, "Name", bind(config::name), placeholder = "Your name")
textField(id, "Token", bind(config::token), masked = true)
textArea(id, "Notes", bind(config::notes), visibleLines = 4)
```

A text area sizes to `visibleLines` rather than to its content, so the row does not jump
around as you type. Enter inserts a newline; Escape stops editing.

### Dropdown

```kotlin
dropdown(
    id, "Theme", bind(config::theme),
    listOf(
        option("dark", "Dark", "dark"),
        option("light", "Light", "light"),
    ),
)
```

The first argument of `option` is the **serialized id** — what goes on disk. It is
required rather than derived, because persisting an enum's ordinal or its `name` breaks
the moment the enum is reordered or a constant is renamed.

Past about a dozen options a dropdown becomes searchable automatically. Force it either
way with `searchable = true` / `false`. Filtering is a case-insensitive substring —
deliberately not fuzzy, because a filter that reorders by score makes the list jump around
as you type. Enter takes the only remaining match.

### Colour and keybind

```kotlin
colorPicker(id, "Accent", bind(config::accent), presets = listOf(Color.parse("#FF8B5CF6")))
keybind(id, "Open menu", bind(config::menuKey))
```

Both open in the overlay layer, so they escape the row's clip.

### List

```kotlin
list(
    id, "Blocked players", bind(config::blocked),
    SettingSerializers.string,
    itemLabel = { it },
    createItem = { "new entry" },
    reorderable = true,
    maxItems = 50,
)
```

`createItem = null` hides the add button. Reorder arrows are disabled at the ends rather
than hidden, so they do not shift sideways as an entry moves. List contents contribute to
search, so a player can find a rule by its text.

### Static content

```kotlin
description(id, "Prose that is not a setting.")
divider(id)
warning(id, "Warning", "Something worth pausing over")
error(id, "Error", "Something that is actually wrong")
```

---

## Metadata

Every setting takes a trailing block for the less common options.

```kotlin
toggle(id, "Experimental thing", bind(config::flag)) {
    tooltip("Shown on hover")
    keywords("beta", "unstable")     // extra search terms
    warning("May cause visual glitches")
    experimental = true
    requiresRestart = true
    enabledWhen = config.advancedModeState
    visibleWhen = config.advancedModeState
    confirmation = Confirmation(
        title = "Are you sure?",
        message = "This cannot be undone.",
        isDestructive = true,
    )
}
```

---

## Conditional visibility

`visibleWhen` and `enabledWhen` take a `UiState<Boolean>`, not a lambda. That is
deliberate: the runtime learns the dependency without evaluating anything, so it knows
exactly when to re-evaluate rather than polling every frame.

```kotlin
val advanced = mutableStateOf(false, "advanced")

toggle(id, "Advanced mode", advanced.asBinding())
toggle(id2, "Only when advanced", bind(config::thing)) {
    visibleWhen = advanced
}
```

Compose them with `derivedStateOf`:

```kotlin
visibleWhen = derivedStateOf("bothOn") { advanced.value && config.enabledState.value }
```

**Hidden versus disabled.** `visibleWhen = false` removes the row from the list entirely —
it is skipped by search and by focus traversal. `enabledWhen = false` leaves it visible,
greyed out, and refusing input. Use *hidden* when the setting is meaningless in the
current configuration, *disabled* when it is meaningful but currently unavailable — the
second tells the player something exists, which is usually what you want.

---

## Search

Search is built from what settings declare — title, description, keywords, id path, and
for lists their contents. You get it for free; there is nothing to wire.

```kotlin
controller.search("notification")
controller.clearSearch()
```

A query that matches nothing shows an empty state rather than the unfiltered list.
Filtering keys off *whether there is a query*, not whether it matched, because silently
showing everything when a search fails contradicts what the player typed.

---

## Showing the screen

```kotlin
class MyConfigScreen : SidequestConfigScreen(
    definition = screen,
    theme = Sidequest.activeTheme(),
    persistence = myPersistenceController,   // optional; saves on close
)
```

The screen's Escape handling closes an open popup before it closes the screen — without
that, the first Escape with a dropdown open would take the whole screen away, which is
never what anyone means by it.

---

## A full example

The mod's own configuration is in `src/main/kotlin/dev/th7bo/sidequest/SidequestConfig.kt`.
Every standard control on one screen is in `SidequestGallery.kt`, which is built through
this DSL and nothing else — if something there had needed a node class, that would have
been a gap in the DSL rather than a reason to reach past it.
