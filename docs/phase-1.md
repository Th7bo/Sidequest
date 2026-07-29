# Phase 1 — Core runtime

Status: **complete**. 192 tests, all passing, no Minecraft dependency anywhere in the
framework modules.

---

## What was built

| Plan item | Where |
| --- | --- |
| IDs | `ui-api` `ids/UiId.kt`, `ids/ProfileId.kt` |
| Typed state | `ui-api` `state/` — `UiState`, `ReactiveGraph`, `StateNode`, `States` |
| Bindings | `ui-api` `binding/Binding.kt` |
| Component identity | `ui-core` `tree/UiNode.kt` — `(parent path, id, key)` |
| Runtime tree | `ui-core` `tree/UiNode.kt`, `runtime/UiRuntime.kt` |
| Dirty invalidation | `ui-core` `tree/UiNode.kt` — measure / arrange / paint bits |
| Layout primitives | `ui-core` `layout/` — column, row, box, padding, spacer, fixed size |
| Fake renderer | `ui-testkit` `RecordingRenderer`, `FakeTextMeasurer`, `AsciiCanvas` |
| Central input system | `ui-core` `input/InputDispatcher.kt` |
| Focus | `ui-core` `focus/FocusManager.kt` |
| Theme tokens | `ui-api` `theme/` — `ThemeTokens`, `DarkTheme`, `LightTheme`, `HighContrastDarkTheme` |
| Basic animation | `ui-core` `animation/Animation.kt` |
| Diagnostics | `ui-api` `diagnostics/UiDiagnostics.kt` |

---

## Acceptance criteria

### Pure JVM tests pass

192 tests across `:ui-api` (115) and `:ui-core` (77). `./gradlew build` runs them along
with both Minecraft targets.

### Fake-renderer demo works

`ui-core/src/test/.../demo/` builds a compact progress card — a scaled-down stand-in for
the Phase 4 mining-XP HUD — from Phase 1 primitives only, and renders it headlessly:

```
+--------------------------------------------------------------------------------+
|                    .........................................                   |
|                    .:::::::::::::::::::::::::::::::::::::::.                   |
|                    .:::::::Mining XP::::::700 / 1000:::::::.                   |
|                    .:::::::::::::::::::::::::::::::::::::::.                   |
|                    .:::::::+++++++++++++++++++++---------::.                   |
|                    .:::::::::::::::::::::::::::::::::::::::.                   |
|                    .........................................                   |
+--------------------------------------------------------------------------------+
```

`.` is the card border, `:` the elevated surface, `+` the accent fill and `-` the track
— the shade comes from each colour's luminance, so a component that invented its own
colour would be visible as a wrong shade. The demo asserts that the card is themed,
animated, reactive, centred, and that it returns to zero layout work when it settles.

### No Minecraft dependency in core modules

Verified two ways: no `net.minecraft`, `net.fabricmc` or `com.mojang` reference exists
in `ui-api`, `ui-core` or `ui-testkit` sources, and their compile classpaths contain
only kotlin-stdlib, kotlinx-serialization, kotlinx-coroutines and JUnit. The modules sit
outside the stonecutter tree entirely, so this is structural rather than a convention.

### No full-tree rebuild during idle frames

`InvalidationTest` asserts this directly rather than by inspection:

- a 20-row tree measures `0` and arranges `0` nodes on an idle frame;
- 30 consecutive idle frames stay at zero;
- changing **one** label in a 20-row tree measures exactly **3** nodes (the label and
  its two ancestors) — every sibling is a cache hit;
- two changes in different rows measure 5, not the tree, because invalidation stops at
  the first already-dirty ancestor;
- idle frames re-measure no text at all.

Painting still happens every frame, because Minecraft clears the screen every frame. The
contract is that the tree is not rebuilt, and that is what is measured.

---

## Design decisions made during implementation

**Pull-based reactivity with a version cut-off.** A write marks dependents stale but
computes nothing. A derivation recomputes only when read *and* one of its recorded
dependency versions has actually moved. A derivation whose recomputation produces an
equal value does not bump its own version, so the change stops there instead of
cascading — `ReactiveStateTest` pins this with a source that changes from 4 to 6 while
`isEven` stays true and the downstream label does not recompute.

**Absolute positions are never cached.** Nodes store parent-relative bounds; absolute
positions are accumulated during the paint and hit-test walks. Moving a container is
therefore O(1) rather than O(subtree).

**Pointer positions are rewritten per node.** `PointerEvent.rootPosition` is fixed;
`position` is re-localised immediately before each handler runs. A handler compares
against its own bounds and never needs to know its transform — which is what makes hit
testing correct inside a scaled subtree, tested at 2× scale.

**Theme colours resolve at paint time.** `SurfaceNode.colorToken` takes a
`(ThemeTokens) -> Color` rather than a literal colour. This was found by a test: a card
built with literal token *values* did not restyle on a runtime theme switch, because the
colour had been captured at construction. The literal `color` property remains for cases
where a colour genuinely is fixed.

**Fixed-size nodes still measure their children.** `SurfaceNode` originally returned its
preferred size without measuring, which left children unmeasured and therefore invisible
— a silent failure. Both `SurfaceNode` and `BoxNode` now always measure.

**Weighted children need a bounded axis.** A weighted child in an unbounded axis falls
back to its intrinsic size rather than expanding to infinity. The demo's header is
pinned to the card's content width for exactly this reason: without it, the weighted
spacer would stretch the card to whatever the screen offered, and a HUD card has to stay
compact.

---

## Known gaps, deliberately left for later phases

- **Virtualization** (Phase 2). The materialized / measured / visible distinction exists
  in `UiDiagnostics` and the node lifecycle, but no container culls rows yet.
- **Scroll containers** (Phase 2). `BoxNode` with `clipsChildren` and a fixed size is the
  minimal viewport primitive; scrolling, sticky headers and nested scroll are Phase 2.
- **Text measurement in production** (Phase 3). `FakeTextMeasurer` has fixed metrics; the
  real caching measurer belongs to the Minecraft adapter.
- **Packaging into the mod jar** (Phase 3). The mod does not yet depend on the UI
  modules, because there is nothing Minecraft-facing to wire them into.
- **Benchmarks** (Phase 2+). The measurement plan is written; the harness runs correctness
  assertions on counters, not yet timing budgets.
