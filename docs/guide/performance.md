# Performance

## What is guaranteed

These are structural properties, asserted by tests, and true on any hardware.

**An idle frame does no layout work.** A frame in which no state changed measures zero
nodes and arranges zero nodes. Not "few" — zero. This is asserted over a 1,700-setting
screen.

**Only the visible slice is materialised.** A 1,701-row list builds about 95 nodes for a
360-unit viewport. Scrolling the whole list does not grow that count; rows recycle.

**Invalidation is local.** Marking a node dirty walks up to the root and stops at the first
ancestor already dirty. Touching a leaf costs a short walk up the spine, not a traversal.

**One HUD's data cannot invalidate another's layout.** Elements are measured against the
screen and never against each other. There is no shared layout parent that would have to
remeasure — so this is structural, not an optimisation that could regress.

**Persistence never blocks the render thread.** Writes happen on a background scope;
snapshotting and applying happen on the UI thread because both touch the reactive graph.

**A no-op write is free.** Writing a value equal to the current one bumps no version and
notifies nobody.

---

## Measured numbers

From `StressBenchmarkTest`, which builds the plan's stress configuration — 1,000 toggles,
500 sliders, 200 dropdowns, 50 HUDs, 200 world overlays. Full report:
[`docs/reports/ui-benchmark.md`](../reports/ui-benchmark.md).

| Measurement | Result | Plan budget |
| --- | --- | --- |
| Idle frame | 0.111 ms | under 1 ms |
| Idle nodes measured | 0 | no idle rebuild |
| Nodes materialised for 1,700 settings | 95 | — |
| Search round trip over 1,700 settings | 0.89 ms | under 50 ms for 5,000 |
| Peak nodes while scrolling the full list | 109 | bounded |
| 50 HUDs, one value changed | 8 nodes measured | — |
| 50 HUDs, all 50 values changed | 0.137 ms | under 1 ms |
| Resolve 200 world overlays | 0.065 ms | — |

Measured on JVM 21, Linux amd64, 16 cores. **These are one machine's numbers.** They are
recorded to be read, not to gate a build — which is why the test asserts scaling properties
instead. A test that fails because CI was busy teaches nobody anything.

To regenerate:

```bash
./gradlew :ui-components:test --tests '*StressBenchmark*'
cat ui-components/build/reports/ui-benchmark.md
```

There is also a stress *screen* you can open in game (bind "Open Stress Test Screen"). A
benchmark against a fake renderer proves the framework scales and says nothing about
whether the game stays responsive with it on screen; scrolling that by hand is the check
no test performs.

---

## What to avoid

**`UpdatePolicy.EveryFrame`.** A HUD that recomputes every frame can never be idle, and the
idle-frame guarantee is the thing everything else is built on. Use `OnChange` unless you
genuinely need per-frame interpolation.

**Reaching for `invalidateMeasure` when `invalidatePaint` would do.** A colour change is
not a size change. Measuring is the expensive one.

**Reading `.value` where `.peek()` would do.** `.value` inside a derived computation
records a dependency. If you only want the current value and do not want to recompute when
it changes, use `.peek()` — an unwanted dependency is a recomputation you did not ask for.

**Doing work in `paintSelf`.** Paint runs every frame the node is dirty. Compute in derived
state, which is cached and recomputed only when its inputs change.

**Building all tabs or sections up front.** Tabs and expandable panels build lazily. A
collapsed panel *detaches* its body rather than hiding it, because an invisible child still
sits in the tree being walked.

**Forgetting to count draw calls.** `context.diagnostics.drawCalls++` in custom paint code.
An uncounted control makes the instrumentation lie, which is worse than not having it.

---

## Diagnostics

`UiRuntime.frame()` returns metrics for the frame:

| Field | Meaning |
| --- | --- |
| `nodesMeasured` | nodes that ran `measureSelf` |
| `nodesArranged` | nodes repositioned |
| `nodesVisible` | nodes painted |
| `drawCalls` | primitives submitted |
| `hitTestCandidates` | interactive nodes considered |
| `cacheHits` / `cacheMisses` | measure-cache effectiveness |

A useful assertion in your own tests:

```kotlin
frame()                                  // settle
val idle = frame()
assertEquals(0, idle.nodesMeasured)      // nothing changed, so nothing was laid out
```

If that fails, something in your tree is invalidating itself every frame — usually a
derived state reading a value that changes constantly, or a `paintSelf` calling
`invalidateMeasure`.
