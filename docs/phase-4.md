# Phase 4 — HUD runtime

Status: **complete and verified in-game.** All four acceptance criteria pass, the last
of them by launching a client, entering a world and capturing the HUD drawn over it.

---

## What was built

| Plan item | Where |
| --- | --- |
| `HudDefinition` / `HudInstance` | `ui-api/.../hud/HudDefinition.kt` |
| `HudPlacement` (anchor + offset + scale) | same |
| `UpdatePolicy` | same |
| `HudLayoutSnapshot` (persistence shape) | same |
| `HudRegistry`, scope-owned | `ui-core/.../hud/HudRuntime.kt` |
| `HudElementNode` | same |
| `HudLayerNode` | same |
| Progress HUD component | `ui-components/.../hud/ProgressHud.kt` |
| Minecraft HUD layer | `ui/minecraft/hud/SidequestHudLayer.kt` |
| Sample registration | `SidequestHuds.kt` |

---

## Decisions

**Placement is an anchor plus an offset, never absolute pixels.** A HUD placed against
the bottom-right corner has to stay against it when the window resizes or the GUI scale
changes. Storing `x = 1180` would put it off screen on a smaller window; storing
`BOTTOM_RIGHT + (-20, -20)` survives both. `withAnchor` recomputes the offset so changing
the anchor in the editor does not move the element — the anchor becomes a statement about
what the element is pinned to, not a jump.

**Scale is a transform, resize is a constraint.** `HudElementNode` applies scale as a
`Transform`, so changing it costs a repaint and no measure pass; the input dispatcher
already un-transforms the pointer on the way down, so hit testing stays correct for free.
Resizing, by contrast, sets tight constraints and does re-measure — because it changes the
space the content lays out in. Two different operations, deliberately not merged.

**The anchor arithmetic uses the *scaled* size.** A 2× HUD occupies twice the pixels, so
resolving its anchor against the unscaled size would let it drift off the edge it is
pinned to. `scaledSize` is what `resolve` gets.

**Elements are measured against the screen, never against each other.** `HudLayerNode`
measures each child independently and arranges by resolved anchor. There is no shared
layout parent, which is what makes "one HUD's data changed" strictly local: it can never
invalidate another element's layout. This is the property the whole runtime is built
around, and the layer is where it would have been easiest to lose.

**`UpdatePolicy.EveryFrame` is opt-in.** A HUD that recomputes every frame can never be
idle, and an idle frame doing no layout work is the framework's core performance claim.
The default is `OnChange`.

**The whole layer is one Fabric HUD element.** Registering one Fabric element per
definition would hand ordering to Minecraft and lose the z-order the editor controls.
`SidequestHudLayer` registers once via `HudElementRegistry.addLast` and draws the tree.

**HUDs hide while a screen is open.** The config screen dims the world; leaving HUDs
painted on top of it would defeat that. The layer returns early when `client.level` is
null and the runtime skips the frame.

**The progress HUD composes standard primitives.** `ProgressHudNode` is `SurfaceNode`,
`TextNode`, `RowNode`, `ColumnNode` and `ProgressBarNode` — a specialised *builder*, not a
second rendering path. Its only custom paint is the card and the accent icon block.

---

## Acceptance criteria

| Criterion | How it is verified |
| --- | --- |
| A HUD keeps its screen position across resolution and GUI-scale changes | `Phase4AcceptanceTest` resolves the same placement against several screen sizes and asserts the anchor-relative distance is preserved |
| Scaling repaints without re-laying out | the test counts measure passes across a scale change and asserts zero |
| Updating one HUD's data does not invalidate another | the test mutates one element's state and asserts the sibling's measure count is unchanged |
| The mining XP HUD matches the design reference in game | `HudRenderTest` enters a singleplayer world and captures the layer at default placement, after a data change, scaled, and re-anchored to the top left |

39 tests in `Phase4AcceptanceTest`; 457 across the framework, 0 failures.

---

## Visual result

`docs/design/captures/hud-mining-xp-*.png`, against
`docs/design/reference-mining-xp-hud.png`.

The card, the accent-tinted icon block, the title with its level chip, the right-aligned
readout with the current value in the accent colour, and the thin progress bar all match
the reference. The reference's pickaxe glyph is drawn as the gear from the icon sheet —
the sprite sheet has no pickaxe yet, which is an asset gap, not a framework one.

### Two bugs the capture found that no headless test did

**The readout overflowed the card.** The title row was wrapped in a fixed
`TRACK_WIDTH` box so the bar and the text would line up, which meant the text ran past
the card's right edge as soon as the numbers got long. The fix inverted the
relationship: the title row sizes to its text, and the bar stretches to match it via a
new `UiNode.fillCrossAxis` — the cross-axis counterpart to `layoutWeight`, handled in
`LinearLayoutNode` by re-measuring flagged children once the widest sibling is known.
Now the card is as wide as its text needs and the bar always spans it.

**A scaled HUD slid off the bottom of the screen.** `UiNode.paint` pushed
`Transform.translation(position).then(transform)`. Since `a.then(b)` applies `a` first,
that translated *then* scaled — multiplying the node's screen position by the scale, so
a 1.6× HUD anchored 90 units above the bottom edge was drawn 1.6× further down and
clipped. The correct composition is `transform.then(Transform.translation(position))`,
which is also the order `toLocalFromParent` inverts, so paint and hit testing now agree.

The existing scaled-hit-test cases missed this because both anchor at `TOP_LEFT` with a
zero offset, and zero times any scale is still zero. The regression test
(`a scaled hud stays anchored, and paint agrees with hit testing`) anchors at
`BOTTOM_RIGHT` with a non-zero offset on both axes and asserts against the `Transform`
the renderer actually received — `absoluteBounds` and `hitTest` both read the arrange
result, which was never wrong, so asserting on either would have passed against the bug.

---

## Still open

- Placement is not yet persisted. `HudLayoutSnapshot` defines the shape; wiring it to
  `ConfigStore` belongs with the editor in phase 5, which is what writes it.
- `HudContext.isEditing` / `isSelected` exist and are threaded through, but nothing sets
  them until the editor does.
- `UpdatePolicy` is honoured as a declaration; the tick and interval drivers land with the
  editor's preview loop.
