# Extending the framework

Everything here follows one rule: **register against a scope, and disposing the scope
undoes it**. There is no manual unregister list to keep in sync.

```kotlin
val scope = RegistrationScope(UiId.of("mymod", "root"))
// …register things…
scope.dispose()
```

---

## A custom setting type

A setting is a value plus metadata plus a serializer.

```kotlin
class VolumeSetting(
    id: UiId,
    metadata: SettingMetadata,
    binding: Binding<Float>,
    defaultValue: Float,
    val channels: List<String>,
) : Setting<Float>(id, metadata, binding, defaultValue, SettingSerializers.float)
```

That is enough to persist and to search. It will render as a placeholder until you give it
a renderer.

## A custom renderer

```kotlin
registry.register<VolumeSetting>(scope) { setting, context ->
    SettingRowNode(setting, context, VolumeControlNode(setting, context))
}
```

One renderer per setting type. Registering a second for the same type throws, naming
**both owners** — two mods silently fighting over one control is far worse than a startup
error that says who is involved.

You can register a renderer for someone else's setting type if they have not, which is the
intended way to improve a control without forking the mod that declared it.

### Writing the control node

```kotlin
class VolumeControlNode(
    private val volume: VolumeSetting,
    context: ComponentContext,
) : ControlNode<Float>(volume, context, "volume") {

    override fun activate() { /* Enter and Space, and click */ }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size =
        Size(constraints.maxWidth.coerceAtMost(120f), 12f)

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) {
        renderer.roundedRect(bounds, Corners.all(tokens.radii.small), context.theme.tokens.colors.hoverBackground)
        context.diagnostics.drawCalls++
    }
}
```

Three things worth knowing:

- **`measureSelf` must measure its children**, even if you already know the size. A node
  that skips measuring children leaves them at zero and they silently vanish.
- **Use theme tokens, not literal colours.** A literal captured at construction will not
  restyle when the theme changes.
- **Increment `context.diagnostics.drawCalls`.** It is what the stress instrumentation
  counts, and an uncounted control makes the numbers lie.

If your control handles keys while "editing", handle them **before** calling
`super.onInputEvent`. The base class treats Enter and Space as activation, so calling super
first means Enter toggles editing off instead of reaching you.

---

## A custom serializer

```kotlin
val blockPos = object : SettingSerializer<BlockPos> {
    override fun encode(value: BlockPos): JsonElement = buildJsonObject {
        put("x", value.x); put("y", value.y); put("z", value.z)
    }

    override fun decode(element: JsonElement): BlockPos {
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("Expected an object, got $element")
        return BlockPos(obj.int("x"), obj.int("y"), obj.int("z"))
    }
}
```

`decode` **must throw `IllegalArgumentException`** for input it cannot read. The caller
turns that into a recorded load error and falls back to the default — a bad value on disk
must never take the whole config file down with it.

**Never serialize an enum by its ordinal or its `name`.** Both break the moment the enum is
reordered or a constant renamed. Give every constant an explicit `serializedId` and
serialize that. `Anchor` does exactly this, and carried a plain `@Serializable` — writing
the constant name — until it was caught; renaming `TOP_LEFT` would have invalidated every
saved HUD layout.

---

## A custom theme

```kotlin
val MyTheme = Theme(
    id = UiId.of("mymod", "theme.ocean"),
    name = constantState("Ocean"),
    tokens = ThemeTokens(
        colors = ColorTokens(
            accent = Color.parse("#FF38BDF8"),
            panelBackground = Color.parse("#FF0F172A"),
            // …
        ),
        radii = RadiusTokens(medium = 8.dp),
    ),
)
```

Tokens are the whole styling surface — colours, radii, spacing, typography, motion,
metrics. A component that reads only tokens restyles for free.

---

## A custom component

For things that are not settings — a chart, a map, a panel:

```kotlin
class SparklineNode(id: UiId, private val samples: UiState<List<Float>>) : UiNode(id) {

    init {
        samples.observe(scope) { invalidatePaint() }
    }

    override fun measureSelf(constraints: Constraints, context: LayoutContext): Size =
        Size(constraints.maxWidth, 24f)

    override fun paintSelf(renderer: UiRenderer, bounds: Rect, context: RenderContext) { … }
}
```

`scope` is the node's own `DisposableScope`. Anything registered there is released when
the node is disposed, which is what stops observers outliving their node.

Choose your invalidation:

| Call | When |
| --- | --- |
| `invalidatePaint()` | appearance changed, size and position did not |
| `invalidateArrange()` | children need repositioning, this node's size did not change |
| `invalidateMeasure()` | this node's size may have changed |

Reaching for `invalidateMeasure` when `invalidatePaint` would do is the easiest way to
lose the idle-frame guarantee.

---

## Popups and overlays

Content that must escape its parent's clip — a dropdown inside a scrolling list — goes in
the overlay layer:

```kotlin
context.overlays?.show(
    key = setting.id,
    anchor = thisNode,
    content = myPopupNode,
    placement = OverlayPlacement.BELOW_END,
    onDismiss = { isOpen = false },
)
```

`ComponentContext.overlays` is **nullable**. With no overlay layer a dropdown should still
cycle by keyboard rather than becoming inert — degrade, do not crash.

An overlay is dismissed automatically when its anchor is disposed *or* leaves the tree.
Both are needed: the settings list is virtualized, so switching category recycles a row out
of the tree without disposing it.

---

## Icons

```kotlin
val sword = Icon(UiId.of("mymod", "icon.sword"))
iconRegistry.registerTexture(scope, sword.id, TextureRef(sword.id))
```

The texture path is resolved from the id: `mymod:icon.sword` becomes
`assets/mymod/textures/gui/icon/sword.png`.

For something drawn rather than blitted, register a painter instead:

```kotlin
iconRegistry.registerPainter(scope, id) { renderer, bounds, tint ->
    renderer.roundedRect(bounds, Corners.all(2.dp), tint)
}
```

---

## Safe unregistration

This is the part most frameworks get wrong, so it is worth being explicit.

```kotlin
class MyModule {
    private val scope = RegistrationScope(UiId.of("mymod", "module"))

    fun enable() {
        registry.register<MySetting>(scope) { … }
        hudRegistry.register(scope, myHudDefinition) { … }
        overlayLayer.register(scope, myWaypoint)
        iconRegistry.registerTexture(scope, myIcon.id, TextureRef(myIcon.id))
    }

    fun disable() {
        scope.dispose()
    }
}
```

After `dispose()`:

- the setting renderer is gone, and its settings fall back to a placeholder
- the HUD definition is gone, **and live instances built from it are taken down**
- the waypoint stops resolving on the next frame
- the icon is unregistered

Disposal collects failures rather than stopping at the first, so one bad disposer cannot
strand the rest. Registering into an already-disposed scope throws immediately — silently
dropping a registration into a dead scope is how leaks and ghost listeners start.

---

## Testing without a game

The testkit gives you everything the framework needs:

```kotlin
val renderer = RecordingRenderer(Size(640f, 360f), FakeTextMeasurer())
val runtime = UiRuntime(DarkTheme).apply {
    viewport = Size(640f, 360f)
    prepare(renderer)
}
runtime.root = myNode

renderer.beginFrame(1f / 60f)
val metrics = runtime.frame(renderer, 1f / 60f)
renderer.endFrame()

assertEquals(0, metrics.nodesMeasured)          // idle frames do no layout
assertTrue(renderer.commands.any { it is DrawCommand.Text })
```

`RecordingRenderer` keeps every draw command, so you can assert on what would have been
drawn. `metrics` carries measure, arrange, paint and draw-call counts.

Call `resetReactiveGraphForTesting()` between tests — the graph is global, and a leaked
subscription from one test will otherwise fire in the next.
