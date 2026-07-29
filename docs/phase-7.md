# Phase 7 — Extended components and tooling

Status: **complete and verified in-game.** Every phase-one control from the plan now has
a working renderer, and the component gallery proves it by rendering all of them at once.

---

## What was built

| Plan item | Where |
| --- | --- |
| Multiline text area | `ui-components/.../ExtendedControls.kt` |
| Editable and reorderable list | same |
| Searchable dropdown filter | `ui-components/.../PopupNodes.kt` |
| Tabs | `ui-components/.../Containers.kt` |
| Expandable panel | same |
| Component gallery | `SidequestGallery.kt` |
| Stress-test instrumentation | `ui-components/.../StressBenchmarkTest.kt` |
| Benchmark report | `docs/reports/ui-benchmark.md` |

---

## The gap this phase closed

`TextAreaSetting` and `ListSetting` were fully modelled back in phase 2 — value types,
serializers, `add`/`removeAt`/`move`, `maxItems`, search contribution — but neither was
ever registered with the component registry. Both therefore degraded to
`MissingComponentNode`, which is exactly the graceful failure that machinery exists for,
and exactly why nothing had complained.

Two tests now close that hole: a headless one asserting every phase-one setting type
builds a real control rather than a placeholder, and an in-game one that walks the whole
gallery tree and fails if any node is a `MissingComponentNode`.

---

## Decisions

**A text area handles keys before the base control, not after.** `ControlNode` treats
Enter and Space as "activate". Calling `super.onInputEvent` first meant Enter toggled
editing *off* instead of inserting a newline — the ordering is the difference between a
working text area and one you cannot type into. Caught by the test that asserts Enter
inserts rather than commits.

**A text area sizes to its declared line count, not to its content.** The row's height
must not jump around as the value is typed.

**Tabs and expandable panels build lazily and then keep it.** Building every tab up front
pays the layout cost of screens nobody opened; discarding on switch would throw away
scroll position and in-flight animation, which is the retained tree's whole point.

**A collapsed panel detaches its body rather than hiding it.** An invisible child still
sits in the tree being walked. On a screen of mostly-closed sections, detaching is the
difference between cheap and merely quiet.

**Dropdown filtering is a case-insensitive substring, not fuzzy matching.** A filter that
reorders results by score makes the list jump around as you type, which is worse than a
short list you can predict. An empty result says "No matches" rather than showing a bare
box, for the same reason the settings list has an empty state: silence looks like a bug.
Enter takes the only remaining match, and does nothing while several still match —
choosing arbitrarily between two would be worse than doing nothing.

**List rows are rebuilt rather than diffed.** A settings list is short by nature —
`maxItems` exists to keep it so — and a diff would buy nothing over rebuilding a handful
of rows while costing the identity bugs that come with it. Reorder buttons are *disabled*
at the ends rather than hidden, so they do not shift sideways as an entry moves.

---

## Stress instrumentation

`StressBenchmarkTest` builds the configuration from the plan — 1,000 toggles, 500
sliders, 200 dropdowns, 50 HUDs, 200 world overlays — measures it, and writes
`docs/reports/ui-benchmark.md`.

**The assertions are about scaling properties, not wall-clock budgets.** A build agent's
timings say nothing about a player's machine, and a test that fails because CI was busy
teaches nobody anything. What is asserted is what holds on any hardware:

- an idle frame over 1,700 settings measures and arranges **zero** nodes
- a 1,701-row list materialises a bounded slice, not the whole list
- scrolling the entire list does not grow the node count — rows recycle
- one HUD's data changing does not re-measure the other 49
- overlays beyond the fade distance are culled, not drawn transparent

Measured on this machine (JVM 21, Linux amd64, 16 cores), against the plan's budgets:

| Measurement | Result | Plan budget |
| --- | --- | --- |
| Idle frame | 0.111 ms | under 1 ms |
| Idle nodes measured | 0 | no idle rebuild |
| Nodes materialised for 1,700 settings | 95 | — |
| Search round trip over 1,700 settings | 0.89 ms | under 50 ms for 5,000 |
| Peak nodes while scrolling the full list | 109 | bounded |
| 50 HUDs, one value changed | 8 nodes measured | — |
| 50 HUDs, all values changed | 0.137 ms | under 1 ms |
| Resolve 200 world overlays | 0.065 ms | — |

Comfortably inside every budget, with the caveat that these are one machine's numbers and
are recorded to be read rather than to gate a build.

---

## Component gallery

`SidequestGallery` builds every standard control through the **public configuration DSL**
only. If something there had needed a node class, that would have been a gap in the DSL
rather than a reason to reach past it. Its values are throwaway state, so opening the
gallery cannot dirty a real config file, and it is deliberately not wired to persistence.

Captured as `docs/design/captures/gallery-*.png`.

### What the capture caught

**Section headers drew an empty accent-tinted square when no icon was set.** The block
was always painted so headers with and without icons kept their titles aligned. The space
is still reserved, but the block is now only drawn when there is a glyph to put in it — an
empty tinted square reads as a *missing* icon rather than a deliberate absence.

**The screen subtitle was hardcoded.** `ScreenChrome` carried the literal string
"Configure how Sidequest looks and behaves.", so every screen built on the framework
claimed to be Sidequest's own configuration — including the gallery. `ConfigScreen` now
carries an optional `description` and the header reads it.

---

## Still open

The plan's phase-two and Minecraft-specific control lists are deliberately untouched. Its
own instruction is not to add advanced components before the earlier criteria pass, and
the more useful reading of that is not to add them before something needs them:

- phase-two controls: radio group, segmented control, multi-select, range slider, numeric
  field, stepper, duration input, password field, regex field, URL field, tag input, tree
  view, table, item grid, gradient editor, theme/font/icon selectors
- Minecraft-specific: item, block, entity, sound and particle selectors, chat colour,
  formatting preview, inventory slot, server selector, command and coordinate input

Also outstanding:

- the colour control offers presets rather than a continuous hue/saturation picker
- profile action buttons are declared but not wired to `ProfileManager`
- `AsyncValidator` exists and nothing uses it
