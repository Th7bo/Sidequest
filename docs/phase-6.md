# Phase 6 — Notifications and world overlays

Status: **complete and verified in-game.** Both acceptance criteria pass, and the
projection was checked against a real camera rather than only against a fake.

---

## What was built

| Plan item | Where |
| --- | --- |
| Notification model | `ui-api/.../notification/Notification.kt` |
| Notification queue | `ui-core/.../notification/NotificationQueue.kt` |
| Notification region | `ui-components/.../notification/NotificationRegionNode.kt` |
| World-overlay API | `ui-api/.../world/WorldOverlay.kt` |
| Overlay registry and resolver | `ui-core/.../world/WorldOverlayLayer.kt` |
| Waypoint rendering | `ui-components/.../world/WaypointLayerNode.kt` |
| Distance fading | `DistanceFade` |
| Screen-edge indicators | `WorldOverlayLayer.edgePosition` |
| Projection arithmetic | `ui-api/.../world/PerspectiveProjector.kt` |
| Minecraft camera adapter | `ui/minecraft/world/MinecraftWorldProjector.kt` |
| Waypoint demo | `SidequestWorld.kt` |

---

## Decisions

**`WorldPosition` is not a `Vec2`, and never becomes one.** A world position and a screen
position must not be assignable to each other. A world overlay's screen position is
*derived* every frame by projection and is never stored — `ResolvedOverlay` is thrown away
at the end of the frame. That is the whole of the first acceptance criterion: the two
placement models cannot bleed into each other because there is no type through which they
could.

**`isBehind` is separate from "outside the viewport".** A point behind the camera still
projects to a coordinate that looks perfectly valid and is mirrored through the centre.
Treating it as a position is how waypoints end up on the wrong side of the screen;
`WorldProjection` carries the flag so that bug is not writable.

**The projector is built from the camera basis, not the render matrices.** Matrices are
the obvious route and the wrong one: they are not reliably available during HUD
extraction and get rebound between versions, whereas `position`, `xRot`, `yRot` and
`getFov` are stable and mean what they say. Keeping the arithmetic explicit also means
the in-game projection is the same one the headless tests exercise through a fake.

**The projector is rebuilt every frame.** The camera moves; a cached projector is a stale
basis.

**This is the project's first and only version conditional.** 26.1.2 names the accessor
`getMainCamera()` and 26.2 renamed it to `mainCamera()`. Everything on `Camera` itself —
`position`, `xRot`, `yRot`, `getFov` — is identical, so the difference is contained to a
single expression in `MinecraftWorldProjector.activeCamera` rather than spreading through
the projection. Phases 3 to 5 needed none at all; this vindicates the architecture rule
that reserved conditionals for the adapter without ever having had to use it.

**The waypoint layer is immediate-mode.** A waypoint's screen position changes on every
frame the camera moves, so a retained node per overlay would be re-arranged every frame
and the invalidation machinery would earn nothing. Nothing is stored between frames,
which is also what guarantees a projected coordinate cannot be mistaken for a placement.

**Overlays resolve far to near.** Nearer waypoints paint over more distant ones, which a
registration-order walk does not give you. Ties break on `priority`.

**A fully faded overlay is culled, not drawn transparent.** Zero-opacity draw calls cost
the same as visible ones.

**Registration is scope-owned.** `WorldOverlayLayer` delegates to `OwnedRegistry`, so the
second acceptance criterion is a property of the existing ownership model rather than new
code — a module that unloads takes its waypoints with it.

**The notification queue holds no rendering state.** Timing, coalescing, priority and
overflow are the hard parts and none of them need a renderer, so all of them are tested
against a clock the test controls.

**Repeats coalesce and restart the timer.** Ten "picked up 1 diamond" toasts are one
toast counting to ten. The timer restarts rather than continuing, because the newest
occurrence is what the player is reacting to and it deserves a full duration rather than
a leftover sliver.

**Severity outranks arrival, and `DROP_OLDEST` will not evict an important notification
for a trivial one.** An error the player never saw because three info toasts were ahead
of it is the failure mode the ordering exists to prevent.

**Pausing stops the clock.** A notification that expired behind the configuration screen
was never actually seen, and silently discarding it is the one behaviour a notification
system must not have.

**Dropped notifications are counted, not hidden.** `droppedCount` is public so a runaway
emitter is visible rather than mysterious.

**Timeouts run off the render clock, not ticks.** A notification lasts the same
wall-clock time whether or not the server is keeping up.

**World overlays draw under the HUD, notifications above it.** A waypoint is part of the
scene and a notification is an interruption, so the stacking follows what each is for
rather than registration order.

---

## Acceptance criteria

| Criterion | How it is verified |
| --- | --- |
| Screen-space and world-space placement remain separate | resolving one definition against two cameras gives two screen positions and mutates nothing; a world overlay's screen position is independent of the viewport, where a `HudPlacement`'s is not; a point behind the camera is never reported on screen |
| Overlays dispose cleanly with their registration scope | disposing a module scope removes its overlays and only its overlays, and a disposed overlay stops resolving on the next frame |

47 tests in `Phase6AcceptanceTest`; 583 across the framework, 0 failures.

---

## Visual result

`docs/design/captures/notifications-*.png` and `waypoint-*.png`.

- **notifications-stack** — three severities together, each with its own bar colour
- **notifications-coalesced** — five identical pickups as one toast with a count
- **waypoint-ahead** — a waypoint 20 blocks straight ahead, landing exactly on the crosshair
- **waypoint-behind** — after a 180° turn, an edge indicator inside the viewport
- **waypoint-disposed** — the overlay gone with its scope

The waypoint landing on the crosshair is the real check on the projector: a sign error in
the camera basis is invisible to a fake projector and unmistakable here.

### What the capture caught

**The toast stack was drawn in arrival order, not the queue's priority order.** The queue
sorted correctly and the region only ever *appended* new nodes, so an error rendered
below the routine chatter it was supposed to outrank — the ordering existed and was
invisible. The region now detaches and re-adds in the queue's order, preserving node
identity so a coalesced repeat still updates in place. The headless test asserted the
queue's ordering and never looked at what was drawn; there is now one that does.

**Toasts were left-aligned inside a right-anchored stack**, so their right edges went
ragged where they met the screen edge. They now align to whichever edge the region is
anchored against.

---

## Still open

- The edge indicator is a two-span arrow rather than a real triangle; the renderer has no
  polygon primitive and adding one for a shape this small was not worth it yet.
- Notifications are not persisted, deliberately — a toast that survives a restart is a
  log entry, not a notification.
- The waypoint demo is placed programmatically; there is no command or key to drop one at
  the player's position.
