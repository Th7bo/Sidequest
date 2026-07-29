# Phase 3 — Minecraft adapter

Status: **complete and verified in-game.** All three acceptance criteria pass, the last
of them by launching a real client, opening the screen and capturing what it drew.

---

## What was built

| Plan item | Where |
| --- | --- |
| Minecraft renderer | `ui/minecraft/rendering/MinecraftUiRenderer.kt` |
| Font adapter | `ui/minecraft/rendering/MinecraftTextMeasurer.kt` |
| Texture adapter | `MinecraftUiRenderer.icon` / `.image` |
| Screen lifecycle | `ui/minecraft/screen/SidequestScreen.kt` |
| Input forwarding | `SidequestScreen` + `ui/minecraft/input/KeyMapping.kt` |
| Resource reload handling | `SidequestScreen.onResourceReload` |
| GUI-scale adaptation | `SidequestScreen.resize` → `UiRuntime.viewport` |
| Config screen | `ui/minecraft/screen/SidequestConfigScreen.kt` |
| Keybind + entrypoint | `ui/minecraft/lifecycle/SidequestKeybinds.kt`, `Sidequest.kt` |

---

## The finding that shaped the module

**The rendering API is identical across 26.1.2 and 26.2.** I diffed
`GuiGraphicsExtractor` between the two: the only differences are `entity`, `sign` and
`skin`, none of which the framework uses. `fill`, `enableScissor`, `pose`, `text`,
`blit`, `fillGradient` and `nextStratum` are byte-for-byte the same signatures.

So the adapter contains **zero stonecutter conditionals**. It lives in the stonecutter
tree because it may need them later, not because it needs them now. The architecture
document's claim that "only `ui-minecraft` may contain a version conditional" currently
costs nothing to honour, because there are none.

---

## Adapter decisions

**Rounded rectangles are drawn as horizontal spans.** Minecraft's GUI renderer has no
rounded-rect primitive and no shader hook stable across versions. Corners are cut with
one narrow `fill` per row using a circular profile. At the radii the design language
uses (4–10 units) that is a handful of extra quads and is indistinguishable from a real
rounded rect at GUI scale.

**Blur is a whole-layer effect, and says so.** Minecraft can only blur everything drawn
below the current stratum, not an arbitrary rectangle. `blur()` promotes the stratum and
blurs behind it, which means two blurred regions in one frame produce one blur. That is
documented on the method rather than silently approximated, and
`EffectTokens.blurStrength = 0` disables it wholesale.

**Scissor nesting is maintained by the adapter.** Minecraft's `disableScissor` clears
the scissor outright rather than popping it, so `popClip` re-applies the enclosing
rectangle. Without that, closing an inner clip would un-clip the outer one.

**The frame always unwinds.** `endFrame()` pops any scissor, pose or opacity a throwing
component left behind. That class of bug otherwise surfaces frames later, far from its
cause. `isBalanced` reports whether it had to.

**Delta time is clamped to 100 ms.** A GC pause or a minimised window must not teleport
an animation to its target.

**The GUI scale is not applied by the framework.** Minecraft hands screens an already
GUI-scaled viewport, and the framework's logical units are exactly those. A GUI-scale
change is therefore *the same event* as a resolution change: a new viewport size. That
is why both are testable without Minecraft.

**Text layouts are cached and invalidated on resource reload.** A resource pack can
replace the font and change every glyph width; the cache is an access-ordered LRU so
eviction drops rows that have scrolled away.

---

## Two API changes found by compiling against the real game

- `fabric-key-binding-api-v1` no longer exists; it is `fabric-key-mapping-api-v1` with
  `KeyMappingHelper.registerKeyMapping`.
- `Minecraft` no longer exposes the current screen for reading. The keybind handler does
  not need it: Minecraft only queues keybind presses while no screen is open, so
  `consumeClick()` returning true already means the screen is closed.

`ui-core` also had to promote kotlinx-coroutines from `implementation` to `api`, because
`ConfigPersistenceController` takes a `CoroutineScope` in its public signature.

---

## Packaging

The framework modules are nested into the mod jar with Loom's `include`, so a user
installs one file:

```
Sidequest-26.2-1.0.0.jar
└── META-INF/jars/
    ├── ui-api-1.0.0.jar
    ├── ui-core-1.0.0.jar
    └── ui-components-1.0.0.jar
```

Loom generates a `fabric.mod.json` for each nested jar and lists them in the outer
`jars` array — verified by inspecting the built artifact.

---

## Verification status

| Criterion | Status |
| --- | --- |
| Resolution and GUI-scale changes pass tests | **verified** — `ViewportAdaptationTest` (15 tests) *and* in-game at GUI scale 1, 2 and 3 |
| No file I/O occurs on the render thread | **verified** — `OffThreadPersistenceTest` (Phase 2) |
| Demo screen renders in Minecraft | **verified** — `ConfigScreenRenderTest`, a client gametest that opens the screen and screenshots it |

### How the in-game criterion is verified

`src/gametest/` holds a Fabric client gametest. `./gradlew :26.2:runClientGameTest`
launches a real client, opens the configuration screen, and captures it while:

- switching category,
- searching (filtered results),
- searching for something that matches nothing (empty state),
- changing the GUI scale to 3, 1 and 2.

It also asserts the renderer's clip / transform / opacity stacks are **balanced** after
every step — a stray scissor corrupts later frames and would never surface as an
exception.

Screenshots land in `versions/26.2/build/run/clientGameTest/screenshots/` and are copied
to `docs/design/captures/`. They are meant to be looked at: a scissor rectangle a
hundred units out still produces a perfectly valid PNG.

### Other things the launch confirmed

- The mod loads: `Sidequest 1.0.0 loaded for Minecraft 26.2`.
- Persistence works end to end — the client wrote
  `run/26.2/config/sidequest/profiles/default/config.json` with stable namespaced keys
  and the schema version, exactly as designed.

### What else is verified about the in-game path

- Both targets compile against the real, deobfuscated Minecraft jars — this is a genuine
  check, not a stub: `GuiGraphicsExtractor`, `Font`, `Screen`, `KeyEvent`,
  `MouseButtonEvent`, `CharacterEvent` and `KeyMapping` are all resolved from
  `minecraft-merged-deobf`.
- Both jars build and package their nested modules correctly.
- Resolution and GUI-scale behaviour is tested headlessly, because it reduces to a
  viewport change.
- Layout, input, focus, virtualization, search and persistence are covered by the 381
  pure-JVM tests.

### Bugs the screenshots caught

Three defects that every headless test had passed:

1. **The renderer ignored `TextStyle.scale`.** Text was *measured* as scaled but *drawn*
   at Minecraft's single fixed font size, so captions overflowed whatever laid them out
   and section titles came out the same size as body text. The scale is now applied
   through the pose stack.
2. **The empty state never appeared.** The layout observed the search query and results,
   but `rebuild()` runs *after* both are written — so it always saw the previous row
   list. It now observes a `rowsChanged` signal bumped by `rebuild()` itself.
3. **The missing-renderer placeholder ran off the panel.** It sized itself to its text
   with no clamp. A message describing a failure that itself overflows is a second
   failure on top of the first.

Only the first was a renderer bug; the other two were logic bugs that happened to be
invisible without a picture.

### Running it by hand

`./gradlew runActive` launches the active version's client. (Plain `./gradlew runClient`
matches the task in *every* stonecutter node and starts one Minecraft per version.) Then
bind "Open Sidequest Config" in Controls — it ships unbound so it cannot clash.

---

## Gaps closed after the first pass

- **Resource reloads now invalidate the font cache.** `FontReloadListener` is a
  `SimpleSynchronousResourceReloadListener` registered against the client pack type. A
  screen subscribes when shown and closes the handle when removed, so a closed screen's
  caches are not kept alive by the long-lived listener.
- **`isDevelopment` reads `FabricLoader.isDevelopmentEnvironment`**, so the diagnostic
  placeholder no longer appears in production builds.
- **The sidebar and search box are built and drawn.** `ConfigScreenLayoutNode` assembles
  search across the top, categories down the left and the settings list filling the rest.
  Both are keyboard reachable, and a query matching nothing shows an explicit empty state
  rather than a blank panel. Covered by `ScreenChromeTest` (13 tests).

A bug surfaced while testing the chrome: a search returning **no** results fell back to
showing the *unfiltered* list, because filtering keyed off "are there results" rather
than "is there a query". A query that matches nothing must show nothing.

## The overlay layer

Popups cannot be children of the control that opens them: a dropdown inside a clipping,
scrolling list would be cut off at its row's edge and painted under its siblings.

`OverlayRootNode` is the tree root and hosts them as later children, which gives them the
behaviour they need for free — children paint in order, so overlays paint last, and hit
testing walks children in reverse, so overlays are tested first.

- anchored placement flips to the other side when it would not fit, then clamps into the
  viewport;
- `BELOW_END` aligns with the anchor's right edge, for right-aligned controls;
- an outside click dismisses **and is consumed**, so the same press does not also
  activate whatever was underneath;
- Escape closes the topmost popup, handled during capture so a control inside it cannot
  swallow the key first — and the screen's own Escape handler only closes the screen once
  no popup is left;
- controls reach the host through `ComponentContext.overlays`, which is nullable: with no
  overlay layer a dropdown still cycles by keyboard and the colour control still cycles
  its presets, rather than becoming inert.

### A popup never outlives its anchor

Reported from a real session: opening the theme dropdown and then clicking a different
category left the popup floating over the new category's content, detached from the row
that opened it.

A popup is positioned from its anchor's absolute bounds, so an anchor that is no longer
in the tree leaves the popup stranded at a stale position. Two independent guards now
make that unrepresentable:

- `show` registers the dismissal into `anchor.scope`, so disposing the control takes its
  popup with it and no caller has to remember;
- `pruneDetachedOverlays`, run at the start of every measure, drops any overlay whose
  anchor is no longer under the root.

Both are needed. The settings list is **virtualized**, so switching category recycles the
anchor row out of the tree *without disposing it* — lifetime alone would not have caught
the reported case, and detachment alone would not catch a control disposed in place.

Captured in-game as `config-screen-dropdown-after-category-switch.png`, which performs
exactly the reported sequence and asserts the control's own `isOpen` went back to false.

Covered by 25 tests in `OverlayRootTest` and captured in-game as
`config_screen_dropdown_open` and `config_screen_color_popup_open`.

## Remaining gaps

- **Mixins**: the scaffold is wired but empty. Nothing in the framework has needed one —
  the screen is a plain `Screen` subclass and input arrives through its overrides.
