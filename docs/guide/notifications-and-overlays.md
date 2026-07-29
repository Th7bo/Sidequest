# Notifications and world overlays

Two systems that both draw during gameplay, and are deliberately kept apart: a
notification is anchored to the *screen*, a world overlay to a *block*.

---

## Notifications

### Posting one

```kotlin
SidequestHudLayer.notifications.post(
    Notification(
        id = UiId.of("mymod", "notify.saved"),
        title = constantState("Waypoint saved"),
        message = constantState("at 120, 64, -300"),
        severity = NotificationSeverity.SUCCESS,
    ),
)
```

`post` returns the live entry if it went on screen, or null if it was queued or dropped.

### Severity

`INFO`, `SUCCESS`, `WARNING`, `ERROR`. Severity decides the bar colour *and* the ordering:
higher severity sorts above what is already showing. An error the player never saw because
three info toasts were ahead of it is the failure this prevents.

### Coalescing

```kotlin
Notification(id, title, coalesceKey = "pickup")
```

Notifications sharing a key merge rather than stacking — ten "picked up 1 diamond" toasts
become one toast counting to ten. The timer **restarts** on each repeat, because the newest
occurrence is what the player is reacting to and it deserves a full duration rather than a
leftover sliver.

### Duration

```kotlin
duration = 5.seconds   // default
duration = null        // stays until dismissed
```

Timeouts run off the render clock rather than ticks, so a notification lasts the same
wall-clock time whether or not the server is keeping up.

### Overflow

```kotlin
NotificationQueue(maxVisible = 3, overflowPolicy = OverflowPolicy.QUEUE, maxPending = 64)
```

| Policy | Behaviour |
| --- | --- |
| `QUEUE` | wait for a slot — nothing lost, a burst shows slowly |
| `DROP_OLDEST` | replace the oldest showing |
| `DROP_NEWEST` | refuse the arrival |

`DROP_OLDEST` will not evict an important notification for a trivial one — an info toast
cannot push out an error, though the reverse is allowed.

Dropped notifications increment `droppedCount`, which is public. A runaway emitter should
be visible rather than mysterious.

### Pausing

```kotlin
queue.isPaused = true    // e.g. while a screen is open
```

A notification that expired behind the configuration screen was never actually seen, and
silently discarding it is the one behaviour a notification system must not have.

### Interaction

```kotlin
Notification(id, title, onActivate = { openRelevantScreen() }, onDismiss = { … })
```

`onDismiss` fires however it goes away, including on timeout — a timeout is still a
dismissal.

### Where they appear

```kotlin
NotificationRegionNode(id, queue, context, anchor = Anchor.TOP_RIGHT, margin = 8f)
```

Anchored like a HUD, and for the same reason. Toasts align to whichever edge the region is
anchored against, and the stack is drawn in the queue's priority order.

---

## World overlays

Something drawn at a position in the world — a waypoint, a marker, a label on a block.

### Declaring one

```kotlin
val waypoint = WorldOverlayDefinition(
    id = UiId.of("mymod", "waypoint.home"),
    position = mutableStateOf(WorldPosition(120.0, 64.0, -300.0), "home"),
    label = constantState("Home"),
    color = Color.parse("#FF8B5CF6"),
    fade = DistanceFade(nearDistance = 64.0, farDistance = 512.0, minimumDistance = 2.0),
    showsDistance = true,
)

overlayLayer.register(scope, waypoint)
```

Registration is scope-owned: `scope.dispose()` takes the waypoint with it.

### World space is not screen space

`WorldPosition` is deliberately **not** a `Vec2`. A world overlay's screen position is
derived every frame by projection and never stored — the resolved value is thrown away at
the end of the frame.

That is not pedantry. It is what makes it impossible to accidentally persist a projected
coordinate, or to feed a screen position into something expecting world coordinates.

### Distance fading

```kotlin
DistanceFade(nearDistance = 64.0, farDistance = 512.0, minimumDistance = 2.0)
```

Fully opaque up to `nearDistance`, fading to nothing at `farDistance`. Below
`minimumDistance` it hides entirely, so a marker you are standing on does not cover the
screen. A fully faded overlay is **culled**, not drawn transparent — a zero-opacity draw
call costs the same as a visible one.

### Edge indicators

When the target is off screen or behind the camera, an arrow is clamped to the viewport
edge pointing at it.

```kotlin
edgeIndicator = EdgeIndicator(margin = 12f, size = 8f)
edgeIndicator = EdgeIndicator.Disabled   // off-screen means simply not drawn
```

A point behind the camera still projects to a coordinate that looks perfectly valid and is
mirrored through the centre. `WorldProjection.isBehind` is tracked separately from "outside
the viewport" precisely so that coordinate is never mistaken for a position — treating it
as one is how waypoints end up on the wrong side of the screen.

### Ordering

Overlays resolve **far to near**, so nearer ones paint over more distant ones. Ties break
on `priority`.

### Projection

The projector is built from the camera's own basis — position, rotation, field of view —
rather than from render matrices, which are not reliably available during HUD extraction
and get rebound between versions. It is rebuilt every frame, because the camera moves and
a cached projector is a stale basis.

For testing, supply your own:

```kotlin
val fake = WorldProjector { WorldProjection(Vec2(320f, 180f), distance = 10.0, isBehind = false) }
```

Everything except the projection itself — fading, culling, ordering, edge clamping — is
arithmetic over the result, so all of it is testable against a fake.

---

## Layering

World overlays draw **under** the HUD; notifications draw **above** it. A waypoint is part
of the scene and a notification is an interruption, so the stacking follows what each one
is for rather than registration order.
