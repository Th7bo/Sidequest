# Phase 2 — Configuration foundation

Status: **complete**. 366 tests, all passing. Clean build across both Minecraft targets.

| Module | Tests |
| --- | --- |
| `:ui-api` | 150 |
| `:ui-core` | 190 |
| `:ui-components` | 26 |

---

## What was built

| Plan item | Where |
| --- | --- |
| Screen DSL | `ui-api` `config/ConfigDsl.kt` |
| Categories, sections | `ui-api` `config/ConfigStructure.kt` |
| Scrolling, virtualization | `ui-core` `virtualization/VirtualListNode.kt` |
| Search | `ui-core` `search/SearchIndex.kt` |
| Validation | `ui-api` `validation/Validation.kt` |
| Persistence | `ui-api` `persistence/`, `ui-core` `persistence/` |
| Profiles | `ui-core` `persistence/ConfigPersistence.kt` (`ProfileManager`) |
| Undo | `ui-core` `undo/UndoStack.kt` |
| Component registry | `ui-core` `component/ComponentRegistry.kt` |
| Phase-one controls | `ui-components` `Controls.kt`, `SettingRowNode.kt` |
| Screen assembly | `ui-components` `ConfigScreenNode.kt` |

A new module, `:ui-components`, depends on `:ui-api` + `:ui-core` and still contains no
Minecraft code.

---

## Acceptance criteria

All four are demonstrated end to end in `Phase2AcceptanceTest`, through a real assembled
screen rather than at the layer each feature lives in.

### 1,000-toggle stress test remains responsive

A 1,005-setting screen:

- materializes **fewer than 30** rows;
- measures **exactly as many nodes** as a 30-setting screen — asserted by comparing
  `nodesMeasured` between the two;
- does **zero** layout work on an idle frame;
- keeps the materialized window bounded while scrolling 8,000 units;
- re-measures **fewer than 20** nodes when one toggle changes.

### Search navigation works with virtualization

- Searching filters the row list and ranks the intended match first.
- `navigateTo` a setting 800 rows down scrolls it into view and materializes it, having
  started with no node at all.
- A result in another category switches category and is then actually drawn.
- Navigating to a setting hidden by the active filter drops the filter rather than
  failing.
- A row surviving a filter change keeps its node, and therefore its state.

### Persistence is atomic and off-thread

Atomicity (`PersistenceTest`): temp file → `force(true)` → `ATOMIC_MOVE`, no stray
`.tmp` files, full truncation on overwrite, corrupt files quarantined with contents
intact, migrations applied in order, unknown fields round-tripped.

Threading (`OffThreadPersistenceTest`), using a store that records the thread of every
call:

- `save`/`load` never run on the UI thread;
- values are applied back **on** it;
- `snapshot()` from a background thread throws `WrongThreadException`, so the writer
  structurally cannot read live state;
- 20 rapid edits produce **one** write containing the final value;
- a write failure surfaces on the UI thread and does not count as a write.

### Keyboard navigation works

Tab and Shift+Tab traverse controls; Space and Enter activate; arrows adjust sliders and
cycle dropdowns without opening them; Home/End jump to slider bounds; typing edits text
fields with validation still running; Escape clears focus; a disabled control refuses
keyboard activation until enabled.

---

## Design decisions made during implementation

**Section ids are namespaced under `section.`.** A section titled "Notifications"
derived the id `general.notifications`, which collided with a *setting* whose path was
`notifications`. Every id on a screen shares one namespace, so this was easy to hit by
accident — it caught me twice while writing tests. Explicit section ids are unaffected.

**`UiScheduler` is a `fun interface`.** It was an ordinary interface, forcing an
anonymous object at every call site for a single-method callback.

**Unsaved-change tracking is derived from the undo stack.** A single "steps since save"
counter yields all three interaction modes the plan asks for: immediate persistence,
apply-and-cancel (`revertToSaved` walks in either direction), and save-and-close. No
separate staging layer.

**Buttons and notices are excluded from persistence.** Neither carries a durable value;
writing them would put meaningless keys in every config file. `Setting.isPersistent()`
makes that a property of the type rather than a filter every caller repeats.

**Filtering lives in the row provider, not the list.** `VirtualListNode` stays a
general-purpose virtualizer; the screen owns what "which rows are showing" means.

**Prefix-sum row offsets with binary search.** Finding a row's position by walking from
row zero would make arrange O(materialized × rowCount) — exactly the cost virtualization
exists to avoid. Rebuilt only when a height or the row count actually changes.

---

## Bugs the tests caught

**`SliderControlNode` called an abstract method from a property initializer.** The base
class built its readout by calling `readoutText()`, which the subclass implemented in
terms of a field that did not exist yet — an NPE on construction. Notably, the screen
still rendered: the component registry caught it, substituted a `MissingComponentNode`
naming the setting and the problem, and every other row drew normally. That is the
containment requirement working before it was deliberately tested. The readout is now a
constructor parameter.

**`ComponentContext` shadowed override parameter names.** Controls stored it as
`context`, colliding with `measureSelf(constraints, context)`. Renamed to
`componentContext` so overrides match their supertype.

---

## Known gaps, deliberately left for later phases

- **Phase-one controls not yet implemented**: searchable dropdown (the setting type and
  its `isSearchable` flag exist; the filter popup does not), multiline text area,
  colour picker, tabs, expandable panel, editable and reorderable lists. `ListSetting`
  and `TextAreaSetting` exist as models with no renderer, so they currently produce the
  diagnostic placeholder rather than failing.
- **Dropdown popup**: `DropdownControlNode` owns open/closed state and cycles by
  keyboard, but the expanded overlay is not drawn — it must escape the row's clip, which
  needs the screen-level overlay layer.
- **Sticky headers and collapsible sections**: `Section.isCollapsible` is modelled and
  not yet honoured by the row provider.
- **Sidebar and search box as components**: `ConfigScreenController` exposes category
  selection and search as API; the widgets that drive them are not built.
- **Async validation**: `AsyncValidator` is defined; nothing runs it yet.
- **Benchmark report**: budgets are verified as counter assertions, not timings written
  to `build/reports/ui-benchmark.md`.
