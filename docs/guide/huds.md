# HUD elements

A HUD is something drawn during gameplay: a progress card, a timer, a counter. Like a
setting, it is **declared** rather than drawn, and holds no rendering state.

## Declaring one

```kotlin
val miningXpDefinition = HudDefinition(
    id = UiId.of("mymod", "hud.mining_xp"),
    title = constantState("Mining XP"),
    category = constantState("Skills"),
    icon = Icons.gear,
    defaultAnchor = Anchor.BOTTOM_CENTER,
    defaultOffset = Vec2(0f, -90f),
    scaleRange = 0.5f..2.5f,
    resizeMode = HudResizeMode.SCALE_ONLY,
    keywords = listOf("mining", "experience"),
)
```

The definition says what the HUD *is*. Where it currently sits is a `HudPlacement`, owned
by the runtime and keyed by **instance** id — because one definition can back several
placed copies.

## Building the content

```kotlin
fun createMiningXpHud(context: ComponentContext): HudElementNode {
    val instance = HudInstance(UiId.of("mymod", "hud.mining_xp.default"), miningXpDefinition.id)

    return HudElementNode(instance, miningXpDefinition) { element ->
        ProgressHudNode(
            id = instance.instanceId.child("content"),
            componentContext = context,
            title = constantState("Mining XP"),
            current = previewed(miningXp, 28_450L, element.isEditing),
            maximum = previewed(miningXpRequired, 60_000L, element.isEditing),
            subtitle = derivedStateOf("level") { "Lv. ${miningLevel.value}" },
            icon = Icons.gear,
        )
    }
}
```

The content builder receives the element, which is how content reaches `isEditing` and
`isSelected`.

`ProgressHudNode` is a *specialised builder* over the standard primitives — `SurfaceNode`,
`TextNode`, `RowNode`, `ProgressBarNode` — not a separate rendering path. Compose your own
the same way if none of the builders fit.

---

## Preview data

```kotlin
current = previewed(live = miningXp, sample = 28_450L, isEditing = element.isEditing)
```

A HUD arranged from a menu often has nothing real to show — no skill XP outside a world,
no timer that has started. An element rendering `0 / 0` cannot be positioned sensibly, so
`previewed` swaps in representative values exactly while the editor is open, and returns
to live data the moment it closes.

It is a derived state, so it costs nothing when `isEditing` never changes.

---

## Reactive updates

The values are ordinary `UiState`. Write to them and the HUD follows:

```kotlin
val miningXp = mutableStateOf(0L, "miningXp")
// later, from a packet handler or a tick:
miningXp.value = 28_450L
```

Elements are measured against the screen and never against each other, so updating one
HUD's data **cannot** invalidate another's layout. That is a structural property, not an
optimisation — there is no shared layout parent that would have to remeasure.

### Update policy

```kotlin
HudDefinition(..., updatePolicy = UpdatePolicy.OnChange)
```

| Policy | When content is re-evaluated |
| --- | --- |
| `OnChange` | only when a dependency changes — **the default, and the cheapest** |
| `EveryTick` | once per client tick |
| `Interval(millis)` | at a fixed wall-clock interval |
| `EveryFrame` | every frame — deliberately opt-in |
| `Manual` | only when you ask |

`EveryFrame` is opt-in because a HUD that recomputes every frame can never be idle, and an
idle frame doing no layout work is the property the whole runtime is built around.

---

## Registering it

```kotlin
SidequestHudLayer.onPopulate = { layer, context ->
    layer.add(createMiningXpHud(context))
}
SidequestHudLayer.register { Sidequest.activeTheme() }
```

For a scope-owned registry that unloads cleanly, use `HudRegistry`:

```kotlin
hudRegistry.register(scope, definition) { instance, context -> buildContent(instance, context) }
// scope.dispose() removes the definition *and* takes down live instances built from it
```

---

## Placement

```kotlin
element.moveTo(Vec2(120f, 40f), screenSize)   // absolute, keeping the current anchor
element.reanchor(Anchor.BOTTOM_RIGHT, screenSize)  // re-anchors *without moving*
element.rescale(1.5f)
element.resize(Size(140f, 60f))
element.reset()
```

**Scale and resize are different operations.** Scale is a transform: it costs a repaint
and no measure pass, and hit testing stays correct because the input dispatcher
un-transforms the pointer on the way down. Resize changes the space the content lays out
in, and does re-measure. A definition declares which it supports through `resizeMode`.

`reanchor` recomputes the offset so the element does not move — the anchor becomes a
statement about what it is pinned to, not a jump.

---

## The editor

```kotlin
val session = HudEditorSession(layer, { viewport })
session.setEditing(true)    // elements swap to preview data
```

The editor is a `HudEditorSession` (pure state and arithmetic) plus
`HudEditorOverlayNode` (the chrome). Selection, dragging, scaling, snapping, guides, safe
areas, locking, z-order and undo all live on the session, which is why they are testable
without a renderer.

Drag arithmetic stays entirely in screen space, so an element scaled 2× moves exactly as
far as the cursor does. Snapping resolves each axis independently — an element can be
centred horizontally while hugging the bottom edge — and is applied once to the primary
element with the correction shared, so a multi-selection cannot tear itself apart.

One gesture is one undo entry covering every element it touched. Escape cancels a gesture
and discards the entry.

Call `setEditing(false)` when the editor closes, or HUDs will keep showing samples over
the actual game.

---

## Multiple instances

```kotlin
HudDefinition(..., allowsMultipleInstances = true)
```

Placement is keyed by *instance* id, so two copies of one definition keep separate
positions, scales and visibility. Add them like any other element:

```kotlin
layer.add(HudElementNode(HudInstance(id("timer.1"), definition.id), definition) { … })
layer.add(HudElementNode(HudInstance(id("timer.2"), definition.id), definition) { … })
```

**Not yet built:** the editor has no "add instance" button. Creating instances works from
code; there is no UI for it.

---

## Visibility

```kotlin
HudDefinition(..., visibleWhen = derivedStateOf("inMines") { area.value == "Mines" })
```

A hidden element is not measured, arranged or painted, and contributes nothing.

---

## Theming

HUDs read the same theme tokens as configuration screens, so a card matches the config
screen without doing anything. Use tokens rather than literal colours:

```kotlin
renderer.roundedRect(bounds, Corners.all(tokens.radii.large), palette.elevatedPanelBackground)
```

A literal colour captured at construction will not restyle when the theme changes. For
`SurfaceNode`, pass `colorToken = { it.colors.panelBackground }` rather than a `Color`.
