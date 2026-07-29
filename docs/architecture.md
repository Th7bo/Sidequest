# Sidequest UI Framework — Architecture

Phase 0 deliverable. This document is the contract the rest of the phases are built
against. Where it disagrees with the code, the code is wrong.

---

## 1. Platform version matrix

Recorded exactly, as required before implementation.

| Item | Value | Notes |
| --- | --- | --- |
| Minecraft | `26.2` and `26.1.2` | Two targets via stonecutter, not one. See deviations. |
| Fabric Loader | `0.19.3` | `depends.fabricloader >= 0.19.3` |
| Fabric API | `0.156.0+26.2` / `0.155.2+26.1.2` | Per-target, from `gradle/<version>.versions.toml` |
| Fabric Language Kotlin | `1.13.13+kotlin.2.4.10` | Supplies the Kotlin runtime at game runtime |
| Kotlin | `2.4.10` | Compiler and stdlib |
| Java (UI modules) | `21` | `:ui-api`, `:ui-core`, `:ui-testkit` |
| Java (Minecraft modules) | `25` | Required by MC 26.1+ |
| Gradle | `9.6.1` | Loom 1.17.17 requires >= 9.5 |
| Loom | `1.17.17` | `net.fabricmc.fabric-loom`, non-remapping variant |
| Mappings | **None — Mojang official names** | MC 26.1+ ships unobfuscated. See deviations. |
| kotlinx.serialization | `1.9.0` | Persistence |
| kotlinx.coroutines | `1.10.2` | Off-thread persistence only |
| JUnit | `5.11.4` | Pure-JVM tests |

### Deviations from `ui.plan` assumptions

Three assumptions in the plan are overridden because the existing project requires it.
The plan permits this ("unless the existing project explicitly requires alternatives").

1. **Java 25, not 21, for Minecraft-facing code.** Minecraft 26.1+ requires a Java 25
   toolchain. The framework-independent modules still target **Java 21** — they are
   plain JVM libraries, and a 21 bytecode target is consumable from 25. This keeps the
   core usable outside the mod (tests, benchmarks, tooling) and matches the plan's
   intent.
2. **Mojang official names, not Yarn.** Minecraft 26.1 and later ship unobfuscated, so
   there are no mappings to choose and Loom needs no remapping step. This is why the
   build uses `net.fabricmc.fabric-loom` rather than `fabric-loom-remap`, and plain
   `implementation` rather than `modImplementation`.
3. **Two Minecraft versions, not one.** The project was set up for `26.1.2` and `26.2`
   before this plan. The framework absorbs this without cost: **no framework module
   is version-dependent**. Only `ui-minecraft` (Phase 3) sits inside the stonecutter
   tree, and it is the only place a `//? if >=26.2` conditional may appear.

---

## 2. Module structure and dependency graph

```
                    ┌──────────┐
                    │  ui-api  │  stable public API, no Minecraft, no runtime internals
                    └────┬─────┘
           ┌─────────────┼──────────────┬────────────────┐
           │             │              │                │
     ┌─────▼─────┐ ┌─────▼──────┐ ┌─────▼───────┐  ┌─────▼──────┐
     │  ui-core  │ │ ui-testkit │ │ui-components│  │ui-minecraft│
     │  runtime  │ │ fakes      │ │  widgets    │  │  adapter   │
     └─────┬─────┘ └────────────┘ └─────┬───────┘  └─────┬──────┘
           │                            │                │
           └──────────┬─────────────────┴────────────────┘
                      │
              ┌───────▼────────┐        ┌──────────┐
              │    ui-editor   │        │ ui-demo  │
              │   HUD editor   │        │ demos    │
              └────────────────┘        └──────────┘
```

Edges (all compile-time, acyclic):

| Module | Depends on | Minecraft? | Stonecutter node? |
| --- | --- | --- | --- |
| `ui-api` | kotlin-stdlib, kotlinx.serialization | no | no |
| `ui-core` | `ui-api`, kotlinx.coroutines | no | no |
| `ui-testkit` | `ui-api`, JUnit | no | no |
| `ui-components` | `ui-api`, `ui-core` | no | no |
| `ui-minecraft` (in the mod's own source set) | `ui-api`, `ui-core`, `ui-components` | **yes** | **yes** |
| `ui-editor` *(Phase 5)* | `ui-api`, `ui-core`, `ui-components` | no | no |
| `ui-demo` *(Phase 7)* | everything | yes | yes |

`ui-testkit` deliberately does **not** depend on `ui-core`: `ui-core`'s own tests
depend on the testkit, and the reverse edge would be a cycle. The testkit therefore
implements only `ui-api` interfaces (renderer, scheduler, clock, input source).

Only `ui-minecraft` and `ui-demo` live inside the stonecutter tree. Everything else is
compiled once and shared by every Minecraft target — which is the whole reason the
"Minecraft classes stay out of the core" rule is worth enforcing mechanically rather
than by convention.

**Packaging** (deferred to Phase 3): the JVM modules are nested into the mod jar via
Loom's `include`. Until the Minecraft adapter exists there is nothing to package, so
the mod does not yet depend on them.

### API surface rule

`ui-api` exposes interfaces and value types. `ui-core` exposes implementations. A type
in `ui-core` may only appear in a `ui-api` signature if it is a documented extension
point. Implementation classes in `ui-core` are `internal` unless they are named in the
public API proposal below.

---

## 3. State model

A typed, pull-based reactive graph with push-based notification. Thread-confined.

### Node kinds

| Kind | Type | Writable | Recomputed |
| --- | --- | --- | --- |
| Source | `MutableUiState<T>` | yes | never |
| Derived | `UiState<T>` from `derivedStateOf {}` / `map` / `combine` | no | lazily, on read |
| Constant | `UiState<T>` from `constantState(v)` | no | never |

`UiState<T>` is covariant in `T` and read-only. `MutableUiState<T>` is invariant and
adds a setter. Nothing else can mutate the graph.

### Versioning and invalidation

Every node carries a monotonically increasing `version`. A derived node caches, along
with its value, the versions of every source it read during its last computation.

- **Write**: if the new value is equal (by the node's configured equality) to the old,
  the write is a no-op — no version bump, no notification. Otherwise the version is
  bumped and dependents are marked *stale* transitively.
- **Read of a derived node**: if it is stale, its recorded dependency versions are
  compared against current versions. If none actually changed (a common case when a
  source was written back to its previous value inside a batch), the cache is reused
  and the value is *not* recomputed.
- Recomputation re-tracks dependencies from scratch, so conditional dependencies
  (`if (a.value) b.value else c.value`) narrow correctly.

This is deliberately not "reevaluate every lambda every frame". A frame that touches
no state performs zero recomputation, which is what the Phase 1 idle-frame acceptance
criterion measures.

### Dependency tracking

Reads are recorded against the innermost computation on a thread-local evaluation
stack. `derivedStateOf { }` opens a tracking frame; `untracked { }` suppresses it.

`visibleWhen = config.showNotifications` (a typed `UiState<Boolean>`) is the preferred
form because the runtime learns the dependency without evaluating anything. The lambda
form `derivedStateOf { ... }` is also fully tracked; what is *not* supported is an
opaque `() -> Boolean` that the runtime must poll. Convenience overloads that accept a
plain lambda wrap it in `derivedStateOf` — they never poll.

### Cycle detection

The evaluation stack doubles as the cycle detector. If a derived node is entered while
already on the stack, `StateCycleException` is thrown carrying the full path
(`a -> b -> c -> a`) using debug names. Detection is exact, not depth-limited.

### Subscriptions, batching, disposal

- `observe(scope) { }` registers a listener and returns a `Subscription`.
- Notifications are **batched**: within `batch { }` (and within a single frame's input
  dispatch), each changed node notifies its listeners at most once, after all writes
  settle. This prevents observers from seeing torn intermediate states.
- Every subscription is owned by a `DisposableScope`. Disposing the scope removes the
  listener and drops the reference. There is no way to register a listener without an
  owner — this is enforced by the signature, not by documentation.

### Thread confinement

The reactive graph belongs to exactly one thread, bound on first use (`UiThread.bind`).
Reads and writes from any other thread throw `WrongThreadException` immediately, with
the expected and actual thread names. There is no "mostly works" mode.

### Snapshot semantics

The framework does **not** implement multi-version snapshot isolation. Because the
graph is single-threaded, a consistent snapshot is simply "the state between two
batches". Persistence takes an immutable `ConfigSnapshot` **on the UI thread** and
hands that value to a background writer; the background writer never touches the graph.

---

## 4. Rendering model

Hybrid retained. The distinction that matters:

| Lives across frames | Rebuilt / discarded |
| --- | --- |
| `UiNode` tree (identity, children, layout cache) | `DrawCommand` list |
| Per-node runtime state (hover, focus, scroll, animation, drag) | Measure results of *dirty* nodes |
| Text layout cache, icon cache, theme resolution cache | Virtualized rows outside overscan |
| Focus owner, pointer owner, selection | — |

### Component identity

A node's identity is `(parentPath, UiId, key)`. `key` disambiguates siblings produced
from a collection. Identity is what preserves focus, scroll offset, and in-flight
animations across a rebuild — including across virtualization, where a row that
scrolls out and back returns to the *same* node if it is still registered, and to a
fresh node only if it was genuinely discarded.

Because focus is stored on the *node* and nodes survive virtualization within the
overscan region, focus survives scrolling. When a focused node is discarded outside
overscan, the focus manager retains its identity path and restores focus if the node
is rematerialized.

### Dirty flags

Each node carries three independent bits:

- `MEASURE` — the node's own size may have changed. Propagates *up* to the root.
- `ARRANGE` — children need repositioning. Propagates *down* from the node.
- `PAINT` — visual only; no geometry change.

A frame runs: `update` (flush state notifications) → `measure` (dirty subtrees only) →
`arrange` (dirty subtrees only) → `paint`. Marking a leaf dirty walks *only* its
ancestor chain, not the tree. A static subtree with no dirty bit is never remeasured;
its cached size is returned directly.

`Diagnostics.nodesMeasured` counts nodes actually measured in a frame. In an idle frame
it is **0**, and Phase 1 has a test asserting exactly that.

### Materialization

Four states, tracked separately as the plan requires:

`registered` (a definition exists) → `materialized` (a `UiNode` exists) →
`measured` (has a valid cached size) → `visible` (intersects the viewport).
`hit-test candidate` is a separate set: visible nodes that opted into input.

Virtualized containers materialize only `visible + overscan`. Registered-but-not-
materialized rows still contribute to scroll extent via an estimated or cached height.

### Animations across target changes

An animation is owned by its node and stores `(current, target, velocity)`. Retargeting
sets a new `target` and keeps `current` — it never restarts from the origin. Because
the node survives, so does the animation.

---

## 5. Threading contract

| Concern | Thread | Enforcement |
| --- | --- | --- |
| Reactive state read/write | UI thread | `UiThread.check()` throws |
| Node tree mutation, measure, arrange, paint | UI thread | same |
| Input dispatch | UI thread | same |
| Minecraft object access | client thread (= UI thread) | adapter boundary |
| Config serialization to a snapshot | UI thread | takes an immutable value |
| Writing the snapshot to disk | background dispatcher | coroutine, `Dispatchers.IO` |
| Delivering a background result | UI thread | via `UiScheduler.submit` |

Rules:

- **No file I/O on the render thread.** Persistence produces an immutable snapshot on
  the UI thread and writes it from a coroutine. The write path never dereferences a
  `UiState`.
- **Cross-thread updates go through `UiScheduler.submit(block)`**, which enqueues the
  block to run at the start of the next frame. There is no other legal way in.
- **Every public callback documents its thread** in KDoc. The default, and the only
  one used by Phase 1, is "invoked on the UI thread".
- The UI thread is bound once. In Minecraft it is the client thread; in tests it is the
  test thread. Nothing in `ui-api`/`ui-core` knows which.

---

## 6. Extension ownership model

Nothing registers globally. Every registration takes a `RegistrationScope`:

```kotlin
val scope = RegistrationScope(UiId.of("sidequest", "core"))
scope.registerComponent<GradientSetting>(renderer, serializer, validator)
scope.registerHud(miningXpHud)
scope.registerTheme(myTheme)
...
scope.dispose()   // every one of the above is gone
```

`RegistrationScope` is a `DisposableScope`. Disposal:

1. Removes each registration from the registry it was added to.
2. Deactivates and disposes any live HUD instances created from its definitions.
3. Removes its entries from the search index.
4. Drops cached renderers, icons and serializers it contributed.
5. Clears its subscription list, releasing the lambdas.

This is what prevents the classic mod-reload leak: a lambda captured from an unloaded
module keeping its classloader alive. Registries hold registrations keyed by owner, so
"release everything owned by X" is a single map removal rather than a scan.

Registering into a disposed scope throws. Registering a duplicate `UiId` into any scope
throws `DuplicateRegistrationException` naming both owners.

---

## 7. Stable identifiers

```kotlin
@JvmInline value class UiId private constructor(val value: String)
```

Format `namespace:path`. `namespace` matches `[a-z0-9_]+`, `path` matches
`[a-z0-9_]+(\.[a-z0-9_]+)*`. Both are validated at construction — an invalid id cannot
exist. `UiId.of(ns, path)` and `UiId.parse("ns:path")` are the only constructors.

Serialized enum-like values use an explicit `serializedId: String`, never `ordinal` and
never `name`. Enum ordinal persistence is listed as a failure mode and is structurally
prevented: the serializer for an option set requires each option to supply an id.

Validated at registration time: duplicate setting ids, duplicate HUD definition ids,
duplicate instance ids, duplicate component registrations, malformed namespaces and
paths. Each produces a distinct exception type carrying the offending id.

---

## 8. Public API proposal

`ui-api` packages and their exported surface:

| Package | Exports |
| --- | --- |
| `ids` | `UiId`, `ProfileId`, id exceptions |
| `state` | `UiState`, `MutableUiState`, `Subscription`, `DisposableScope`, `Disposable`, `mutableStateOf`, `derivedStateOf`, `constantState`, `batch`, `untracked`, `UiThread`, `UiScheduler`, `StateCycleException`, `WrongThreadException` |
| `binding` | `Binding`, `bind(get, set)`, `bind(property)`, `UiState.asBinding`, `Binding.map`, `BindingException` |
| `geometry` | `Dp`, `Vec2`, `Size`, `Rect`, `Insets`, `Constraints`, `Alignment`, `Anchor` |
| `rendering` | `UiRenderer`, `Color`, `TextStyle`, `TextLayout`, `TextMeasurer`, `Icon`, `TextureRef`, `Transform`, `Shadow`, `Gradient` |
| `theme` | `Theme`, `ThemeTokens`, `SpacingScale`, `TypographyScale`, `MotionTokens`, `DarkTheme`, `LightTheme` |
| `input` | `PointerEvent`, `KeyEvent`, `CharEvent`, `ScrollEvent`, `EventPhase`, `Key`, `Modifiers`, `MouseButton` |
| `extension` | `RegistrationScope`, `Registration`, `DuplicateRegistrationException` |
| `diagnostics` | `UiDiagnostics`, `FrameMetrics` |

Kotlin gets the DSL (`configScreen { category { section { toggle(...) } } }`).
Java gets a fluent builder over the identical model objects — the DSL builders are
plain classes with `@JvmStatic` entry points, not Kotlin-only receivers, so there is
one model and two spellings.

Deferred to their phases: `config`, `components`, `layout`, `validation`,
`persistence`, `hud`, `notification`, `overlay`.

---

## 9. Persistence format proposal

Two stores, deliberately separate (feature config and HUD layout have different
churn rates and different blast radius on corruption):

```
config/sidequest/
  profiles/
    default/
      config.json          schemaVersion + settings by UiId
      huds.json            schemaVersion + placements by instance UiId
    <profile>/…
  profiles.json            profile index, active profile
  backups/
    default-config-<epoch>.json
```

- **JSON via kotlinx.serialization.** Keys are stringified `UiId`s, so a renamed
  Kotlin property does not change the on-disk key.
- **`schemaVersion: Int` at the root of every file.** Migrations are a registered
  chain of `Migration(from, to)` applied in order on load.
- **Unknown fields are preserved.** Load parses into `JsonObject`, extracts known keys,
  and retains the remainder in the snapshot so a downgrade-then-upgrade round trip does
  not silently delete another module's settings.
- **Atomic writes**: serialize to `<file>.tmp`, `fsync`, then `ATOMIC_MOVE` over the
  target. A crash mid-write leaves the previous file intact.
- **Corruption recovery**: a parse failure moves the bad file to `backups/` with a
  timestamp, logs a loud error, and loads defaults rather than throwing away the
  evidence.
- **Debounced saving**: a change schedules a save ~750 ms out; further changes reset
  the timer. Snapshot capture is on the UI thread, the write is not.

---

## 10. Performance measurement plan

Budgets are from §38 of the plan. The point of this section is *how they are measured*,
so that a missed budget is reported rather than hidden.

**Instrumentation.** `UiDiagnostics` accumulates per-frame counters and timings:
`updateMs`, `layoutMs`, `renderPrepMs`, `nodesRegistered`, `nodesMaterialized`,
`nodesMeasured`, `nodesVisible`, `hitTestCandidates`, `drawCalls`, `textLayouts`,
`cacheHits`/`cacheMisses`. Timings are captured with `System.nanoTime()` around the
phase boundaries in the frame loop, not by sampling.

**Where measured.** Budgets are verified in two places:

1. *Pure JVM benchmarks* (`ui-core` test source set) driving the runtime against the
   testkit's fake renderer with a synthetic clock. No Minecraft, so these run in CI and
   are deterministic. They measure update/layout/render-prep time and allocation
   behaviour, which is where every budget in §38 except raw FPS actually lives.
2. *In-game stress screens* (Phase 7) for real frame time and draw calls.

**Method.** Each benchmark warms up 200 frames, then measures 1000 frames and reports
mean, p95 and max — not just mean, because a 2 ms mean hiding a 40 ms spike is a
stutter the player sees. Allocation is measured by sampling
`ThreadMXBean.getThreadAllocatedBytes` across the measured window; the assertion is on
*growth rate*, since zero allocation is not a realistic target.

**Reporting.** The benchmark task emits `build/reports/ui-benchmark.md` with the
measured numbers against the budget. A missed budget fails loudly with the measurement
and the identified bottleneck. Numbers are meaningless without hardware, so the report
records CPU model, core count, JVM version and heap settings.

**Test hardware for the recorded baseline** is captured at benchmark run time rather
than asserted here, so the committed numbers always carry their own provenance.

---

## 11. Phase status

| Phase | Status |
| --- | --- |
| 0 — Architecture | complete (this document) |
| 1 — Core runtime | complete — see [phase-1.md](phase-1.md) |
| 2 — Configuration foundation | complete — see [phase-2.md](phase-2.md) |
| 3 — Minecraft adapter | complete, verified in-game — see [phase-3.md](phase-3.md) |
| 4 — HUD runtime | complete, verified in-game — see [phase-4.md](phase-4.md) |
| 5 — HUD editor | not started |
| 6 — Notifications and world overlays | not started |
| 7 — Extended components and tooling | not started |
