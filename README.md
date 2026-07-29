# Sidequest

A Minecraft mod and the UI framework behind it. Configuration screens, HUD elements, a
HUD editor, notifications and world overlays — declared in Kotlin, strongly typed, and
tested without a game running.

Targets Minecraft **26.1.2** and **26.2** from one source tree.

```kotlin
val screen = configScreen(UiId.of("mymod", "config"), "My Mod") {
    category(UiId.of("mymod", "general"), "General", icon = Icons.gear) {
        section("Interface") {
            toggle(UiId.of("mymod", "notifications"), "Show notifications", bind(config::notifications))
            slider(UiId.of("mymod", "duration"), "Duration", bind(config::duration), 1..30)
        }
    }
}
```

That is the whole API for a working, searchable, persistable screen.

---

## Documentation

| Guide | What it covers |
| --- | --- |
| [Getting started](docs/guide/getting-started.md) | Installation, your first screen, the mental model |
| [Configuration screens](docs/guide/configuration.md) | Categories, sections, every setting type, conditional visibility |
| [Bindings and validation](docs/guide/bindings.md) | Property references, getter/setter pairs, validators, failure behaviour |
| [Persistence and profiles](docs/guide/persistence.md) | Where files live, schema migrations, profiles, corruption handling |
| [HUD elements](docs/guide/huds.md) | Declaring a HUD, preview data, the editor, multiple instances |
| [Notifications and world overlays](docs/guide/notifications-and-overlays.md) | Toasts, queue behaviour, waypoints, projection |
| [Extending the framework](docs/guide/extending.md) | Custom components, renderers, serializers, themes, safe unregistration |
| [Performance](docs/guide/performance.md) | What is guaranteed, what to avoid, measured numbers |
| [Common failure cases](docs/guide/failure-cases.md) | Things that go wrong, and what they look like |

Design notes and per-phase engineering decisions live in [`docs/`](docs/) —
[architecture.md](docs/architecture.md) is the contract the implementation is built
against, and `phase-N.md` records why each phase was built the way it was.

---

## Layout

```
ui-api/          public surface — state, geometry, theming, config model. No Minecraft.
ui-core/         runtime — retained tree, layout, input, persistence, HUD, overlays.
ui-components/   the widgets. Config screen, controls, HUD cards, editor chrome.
ui-testkit/      fakes — recording renderer, fake text measurer, test schedulers.
src/main/        the mod. The only code that knows about Minecraft.
src/gametest/    client gametests that launch a real client and capture screenshots.
```

Only `src/main` sits inside the stonecutter tree. Everything else is a plain JVM library
compiled once and shared by both Minecraft versions — which is why the framework is
testable without a game at all.

---

## Building

```bash
./gradlew build                  # compile both versions, run 643 headless tests
./gradlew runActive              # launch the client on the active version (26.2)
./gradlew :26.1.2:runClient      # or the other one
./gradlew :26.2:runClientGameTest  # launch a real client, run in-game tests, capture screenshots
```

Requires JDK 25 (Minecraft 26.1+ needs it). The framework modules target Java 21 so they
stay usable outside the mod.

---

## In-game keybinds

All unbound by default, so nothing clashes with an existing setup. Bind them under
**Options → Controls → Sidequest**.

| Action | What it opens |
| --- | --- |
| Open Sidequest Config | The mod's own configuration screen |
| Open HUD Editor | Drag, scale and anchor HUDs against the live game |
| Open Component Gallery | Every standard control on one screen |
| Open Stress Test Screen | 1,700 settings, for checking responsiveness by hand |

---

## Status

All seven planned phases are complete, each with its acceptance criteria implemented,
tested and demonstrated in game. 643 headless tests and 5 client gametests, which capture
screenshots that are checked into [`docs/design/captures`](docs/design/captures).

Measured performance is in [`docs/reports/ui-benchmark.md`](docs/reports/ui-benchmark.md).
The short version: an idle frame over 1,700 settings measures zero nodes, that screen
materialises 95 of 1,701 rows, and search across all of them takes under a millisecond.

Deliberately not built yet: the plan's phase-two control list (radio groups, tables, tree
views, range sliders and so on) and the Minecraft-specific selectors (item, block, entity,
sound). Neither is blocked — nothing has needed them. See
[docs/phase-7.md](docs/phase-7.md) for the full list.
