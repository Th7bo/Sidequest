# Common failure cases

What goes wrong, what it looks like, and what to do. Most of these are real bugs that were
hit while building the framework.

---

## A setting renders as a grey placeholder

**Looks like:** a row with the setting's title and a box saying no renderer is registered.

**Means:** no `SettingRenderer` is registered for that setting's type. This is deliberate —
one broken third-party control should not take down the whole screen.

**Fix:** register one.

```kotlin
registry.register<MySetting>(scope) { setting, context -> … }
```

This is worth a test, because it fails silently by design:

```kotlin
val node = registry.createNode(setting, context)
var missing = false
node.forEachInTree { if (it is MissingComponentNode) missing = true }
assertFalse(missing)
```

`TextAreaSetting` and `ListSetting` shipped for several phases in exactly this state — the
model was complete, the renderer was never registered, and nothing complained.

---

## A control appears but is invisible

**Means:** a custom node's `measureSelf` returned a size without measuring its children.
Unmeasured children stay at zero and draw nothing.

**Fix:** always measure children, even when the parent's size is already known.

```kotlin
override fun measureSelf(constraints: Constraints, context: LayoutContext): Size {
    val child = children.first()
    child.measure(constraints, context)   // do this even if you return a fixed size
    return Size(120f, 40f)
}
```

---

## Colours do not change with the theme

**Means:** a literal `Color` was captured at construction.

**Fix:** read tokens at paint time, or pass a token accessor.

```kotlin
SurfaceNode(id).apply { colorToken = { it.colors.panelBackground } }   // restyles
SurfaceNode(id).apply { color = Color.parse("#FF181825") }            // does not
```

---

## A popup floats over unrelated content after switching category

**Was a real bug.** A dropdown left open while the category changed stayed on screen,
detached from the row that opened it, drawn at a stale position.

**Cause:** the settings list is virtualized, so switching category recycles the anchor row
out of the tree *without disposing it*. Tying the popup to the anchor's lifetime alone does
not catch that.

**Now handled** by two guards in `OverlayRootNode`: the dismissal is registered into the
anchor's scope, *and* every measure drops overlays whose anchor is no longer under the
root. If you build your own overlay host, you need both.

---

## A scaled element is drawn in one place and clickable in another

**Was a real bug.** `UiNode.paint` composed its transform as
`translation(position).then(transform)`. Since `a.then(b)` applies `a` first, that
translated *then* scaled, multiplying the node's screen position by the scale factor.

**Fix:** `transform.then(translation(position))` — scale first, then translate. This is
also the order `toLocalFromParent` inverts, so paint and hit testing agree.

**Why it survived:** the existing tests anchored at the origin, where `0 × 2` is still `0`.
Test transforms away from the origin.

---

## A HUD shows "0 / 0" in the editor

**Means:** the content is reading live data that does not exist outside a world.

**Fix:** wrap the values in `previewed`.

```kotlin
current = previewed(live = miningXp, sample = 28_450L, isEditing = element.isEditing)
```

Make sure the editor actually calls `session.setEditing(true)` when it opens and `false`
when it closes — the flags are only useful if something sets them.

---

## Text overflows its container

**Was a real bug**, twice. Once from a fixed-width wrapper around content that grew, and
once from the renderer ignoring `TextStyle.scale` — text was measured scaled and drawn
unscaled.

**Fix for the first:** size the container to the content and stretch the fixed-size sibling
instead, using `fillCrossAxis`.

```kotlin
progressBar.fillCrossAxis = true   // spans the widest sibling in a column
```

---

## A saved config is not loaded after renaming something

**Means:** a `UiId` changed. The Kotlin property name is irrelevant; the id is what is
stored.

**Fix:** write a [migration](persistence.md#schema-migrations). Or, before release, keep
the id and rename only the property.

---

## Everything resets after a crash mid-write

**Should not happen.** Writes are atomic: temp file, force, `ATOMIC_MOVE`. If it does, the
old file survives, not a half-written one.

**If the file was hand-edited and is now invalid**, it is *moved* to
`config/<modid>/backups/` and defaults are loaded, with `LoadReport.corruptionBackupPath`
set. Log that report — a silent repair is a bug report you will never receive.

---

## An enum value is not restored

**Means:** the enum was serialized by ordinal or by `name`, and something was reordered or
renamed.

**Fix:** give every constant an explicit `serializedId` and serialize that.

`Anchor` carried a plain `@Serializable` — which writes the constant name — despite its own
documentation saying `serializedId` was "what goes on disk". Renaming `TOP_LEFT` would have
invalidated every saved HUD layout.

---

## Enter does nothing in my text control

**Means:** the control called `super.onInputEvent(event)` first. `ControlNode` treats Enter
and Space as "activate", so it toggled editing off before your handler ran.

**Fix:** handle keys before delegating, while editing.

```kotlin
override fun onInputEvent(event: InputEvent) {
    if (!isEditing) { super.onInputEvent(event); return }
    // …handle Enter, Backspace, Escape…
}
```

---

## An idle frame is laying nodes out

**Means:** something invalidates itself every frame.

**Find it** by asserting the guarantee and looking at what breaks:

```kotlin
frame()
assertEquals(0, frame().nodesMeasured)
```

Usual causes: a derived state reading a constantly-changing value (a clock, a frame
counter), `paintSelf` calling `invalidateMeasure`, or a HUD on
`UpdatePolicy.EveryFrame`.

---

## Registering something throws at startup

**`DuplicateRegistrationException`** — two registrations claim the same id or setting type.
The message names **both owners**. Two mods silently fighting over one control is far worse
than a startup error that says who is involved.

**"Cannot register into a disposed scope"** — the scope was already disposed. Silently
dropping the registration would leave a feature mysteriously absent.

---

## State throws about the wrong thread

Everything is confined to the UI thread and checks it. Reading or writing from another
thread throws rather than corrupting quietly.

**Fix:** hand the work back through a scheduler.

```kotlin
scheduler.submit { myState.value = computedOffThread }
```

---

## A test passes alone and fails in a suite

**Means:** the reactive graph leaked between tests. It is global.

**Fix:** `resetReactiveGraphForTesting()` in both `@BeforeEach` and `@AfterEach`, and
dispose the runtime.

---

## A gametest fails for reasons unrelated to itself

**Client gametests share one client and one HUD layer**, and run in the order listed in
`src/gametest/resources/fabric.mod.json`. State carries across.

A persistence check that asserted "the element is where the file said" failed because an
earlier test legitimately moved it — which looked exactly like a persistence bug and was
not. Assert against what the *load applied*, not where things currently sit, and reset
placements at the start of a test whose captures should be deterministic.
