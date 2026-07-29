# Phase 5 — HUD editor

Status: **complete and verified in-game.** All three acceptance criteria pass, the last
of them by driving the editor in a running client and capturing each state.

---

## What was built

| Plan item | Where |
| --- | --- |
| Selection, multi-selection, marquee | `ui-core/.../hud/editor/HudEditorSession.kt` |
| Dragging | same — `beginDrag` / `updateDrag` |
| Scaling, resizing by handle | same — `beginScale` / `beginResize` |
| Locking, z-order, nudging, reset | same |
| Undo integration | same, plus `HudPlacementEdit` |
| Snapping, guides, safe areas | `ui-core/.../hud/editor/HudSnapping.kt` |
| Selection chrome and input routing | `ui-components/.../hud/HudEditorOverlayNode.kt` |
| Inspector | `ui-components/.../hud/HudInspectorNode.kt` |
| Editor screen | `ui-components/.../hud/HudEditorScreenNode.kt` |
| Minecraft screen and keybind | `ui/minecraft/screen/SidequestHudEditorScreen.kt`, `SidequestKeybinds.kt` |

---

## Decisions

**The session holds no rendering state.** Every acceptance criterion is arithmetic over
placements, so all three are assertable without a renderer or a running game. The chrome
is a view over the session, not the other way round — which is why the in-game test can
drive the same API the mouse does and assert on the result.

**Drag arithmetic stays entirely in screen space.** The pointer's offset within each
element is captured at grab time and preserved for the whole gesture, so an element
scaled 2× moves exactly as far as the cursor does. The test is parameterised over five
scales and all nine anchors deliberately: a stray multiply or divide by the element
scale drifts proportionally, and at 1× it would pass.

**Snapping resolves each axis independently.** An element can be centred horizontally
while hugging the bottom edge; a combined "nearest point" search cannot produce that.
Ties go to the earlier candidate, so a screen edge beats an element line sitting on top
of it and the guide names the more meaningful one.

**Snapping is resolved once and shared.** The correction is computed against the primary
element and applied to the whole selection. Snapping each member independently would
tear a multi-selection apart the moment two of them snapped to different lines.

**Guides appear only while a snap is active.** A guide that is always on teaches nothing;
one that appears exactly when the snap takes hold explains what just happened.

**Safe areas are advisory.** The vanilla hotbar, chat and effect regions are drawn and
offered as snap targets, but nothing stops a HUD being placed over them. Someone who
wants a HUD on the hotbar is allowed to have one — what the editor prevents is doing it
*by accident*.

**Scale holds the opposite corner still.** Dragging a handle grows the element away from
that corner rather than jumping, and the element is re-pinned after rescaling because
its extent changed. Scale is driven from the width ratio alone so a diagonal drag cannot
produce two different answers.

**One gesture is one undo entry, covering every element it touched.** Undoing a
multi-selection drag puts all of them back at once. Cancelling with Escape restores the
starting placements and discards the entry through a new `UndoStack.abortGesture`, so
Ctrl+Z cannot re-apply a drag the user explicitly abandoned.

**Locking is never a one-way door.** A locked element refuses drags, scales and nudges,
but the lock toggle itself still applies to it — being unable to unlock something would
be a trap.

**The editor does not re-parent the HUD layer, and draws no scrim.** Minecraft renders
the HUD layer beneath the screen, so the chrome lands on top of the real HUDs for free
and the layer keeps its single owner. A HUD editor that dims the game would be showing
you something other than what you are arranging.

**The inspector reuses the configuration screen's controls.** Its rows are ordinary
`Setting` objects bound to the session, so a slider here is the same
`FloatSliderControlNode` a config screen uses. It is rebuilt when the selection changes
because the bindings are per-selection — the scale slider's range is the *narrowest*
across the selection, so it can never ask for a scale one of the definitions would
refuse.

---

## Acceptance criteria

| Criterion | How it is verified |
| --- | --- |
| Drag cursor alignment remains correct at all scales | `Phase5AcceptanceTest` grabs a fixed fraction of the element and asserts that same fraction is still under the cursor after each move — parameterised over 0.5×–3.5× and all nine anchors |
| Multi-selection transformations are reversible | a three-element drag is asserted to be one undo entry, and undo/redo restores and reapplies every member together |
| Placement survives resolution and GUI-scale changes | an edited placement is resolved against four screen sizes and asserted to keep its distance from the anchored edge |

56 tests in `Phase5AcceptanceTest`; 518 across the framework, 0 failures.

---

## Visual result

`docs/design/captures/hud-editor-*.png`.

- **idle** — safe areas and faint outlines on every element, nothing selected
- **selected** — accent outline, corner handles, and the inspector with a live offset readout
- **snapping** — both centre guides drawn, element locked to the screen centre
- **scaled** — the element grown from a corner handle, opposite corner unmoved
- **reanchored** — the anchor changed from the inspector without the element moving

The in-game test asserts what the captures show: that the drag snapped (`guides` is
non-empty), that the element ended within half a unit of the centre line, and that
undoing the scale gesture changed the scale back.

### What the capture caught

The first run showed the inspector's right-hand buttons running off the screen edge and
its row titles truncated to `An…` and `…`. The panel was using the configuration
screen's side-by-side `SettingRowNode`, which needs room for a title, a description and
a control — at 180 units wide the title lost. The inspector now stacks caption above
control and its position is clamped into the viewport rather than trusted.

The same run also exposed test-order dependence: the gametests share one client and one
HUD layer, so the editor started from wherever `HudRenderTest` had left the element. It
now resets every placement first, because a capture that depends on test order is worth
very little.

---

## Still open

- Placement is still not persisted between sessions. `HudLayoutSnapshot` defines the
  shape and the editor's `onSave` hook is wired to the config store, but the
  serialisation round-trip is not written yet.
- Multi-instance HUDs can be created by the runtime but the editor has no "add instance"
  affordance.
- Rotation is not supported and is not planned; `Transform` has no rotation term.
