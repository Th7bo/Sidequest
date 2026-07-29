package dev.th7bo.sidequest.ui.components.docs

import dev.th7bo.sidequest.ui.binding.asBinding
import dev.th7bo.sidequest.ui.binding.asReadOnlyBinding
import dev.th7bo.sidequest.ui.binding.bind
import dev.th7bo.sidequest.ui.binding.map
import dev.th7bo.sidequest.ui.components.Icons
import dev.th7bo.sidequest.ui.components.SettingRowNode
import dev.th7bo.sidequest.ui.components.ToggleControlNode
import dev.th7bo.sidequest.ui.components.hud.ProgressHudNode
import dev.th7bo.sidequest.ui.components.registerStandardControls
import dev.th7bo.sidequest.ui.config.Confirmation
import dev.th7bo.sidequest.ui.config.SettingMetadata
import dev.th7bo.sidequest.ui.config.SettingSerializer
import dev.th7bo.sidequest.ui.config.SettingSerializers
import dev.th7bo.sidequest.ui.config.ToggleSetting
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.config.option
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.component.ComponentRegistry
import dev.th7bo.sidequest.ui.core.component.MissingComponentNode
import dev.th7bo.sidequest.ui.core.hud.HudElementNode
import dev.th7bo.sidequest.ui.core.hud.HudLayerNode
import dev.th7bo.sidequest.ui.core.icon.IconRegistry
import dev.th7bo.sidequest.ui.core.notification.NotificationQueue
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.core.tree.LayoutContext
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.core.world.WorldOverlayLayer
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.geometry.Anchor
import dev.th7bo.sidequest.ui.geometry.Constraints
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.hud.HudDefinition
import dev.th7bo.sidequest.ui.hud.HudInstance
import dev.th7bo.sidequest.ui.hud.HudResizeMode
import dev.th7bo.sidequest.ui.hud.UpdatePolicy
import dev.th7bo.sidequest.ui.hud.previewed
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.notification.Notification
import dev.th7bo.sidequest.ui.notification.NotificationSeverity
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.rendering.TextureRef
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.derivedStateOf
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.testkit.FakeTextMeasurer
import dev.th7bo.sidequest.ui.testkit.RecordingRenderer
import dev.th7bo.sidequest.ui.theme.DarkTheme
import dev.th7bo.sidequest.ui.validation.ValidationResult
import dev.th7bo.sidequest.ui.validation.Validator
import dev.th7bo.sidequest.ui.validation.Validators
import dev.th7bo.sidequest.ui.world.DistanceFade
import dev.th7bo.sidequest.ui.world.WorldOverlayDefinition
import dev.th7bo.sidequest.ui.world.WorldPosition
import dev.th7bo.sidequest.ui.world.WorldProjection
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The examples from `docs/guide/` — compiled, and run.
 *
 * Documentation that does not compile is worse than none: it looks authoritative and
 * sends the reader down a path that cannot work. Every non-trivial snippet in the guides
 * has a counterpart here, so an API change that invalidates the docs breaks the build
 * rather than quietly making them wrong.
 *
 * When a guide changes, change this. When this fails, the guide is lying.
 */
class DocumentationExamplesTest {

    private fun id(path: String) = UiId.of("mymod", path)

    private lateinit var runtime: UiRuntime
    private lateinit var renderer: RecordingRenderer
    private lateinit var context: ComponentContext
    private lateinit var scope: RegistrationScope

    private val screen = Size(640f, 360f)

    /** Stands in for a mod's own config object. */
    private object MyModConfig {
        var enabled: Boolean = true
        var duration: Int = 5
        var durationTicks: Int = 100
        var username: String = "Steve"
    }

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        renderer = RecordingRenderer(screen, FakeTextMeasurer())
        runtime = UiRuntime(DarkTheme).apply {
            viewport = screen
            prepare(renderer)
        }
        context = ComponentContext(DarkTheme, runtime.animations, runtime)
        scope = RegistrationScope(id("root"))
        MyModConfig.enabled = true
        MyModConfig.duration = 5
        MyModConfig.durationTicks = 100
    }

    @AfterEach
    fun tearDown() {
        scope.dispose()
        runtime.dispose()
        resetReactiveGraphForTesting()
    }

    private fun frame(delta: Float = 1f / 60f) = run {
        renderer.beginFrame(delta)
        val metrics = runtime.frame(renderer, delta)
        renderer.endFrame()
        metrics
    }

    private fun toggleSetting() = ToggleSetting(
        id("enabled"),
        SettingMetadata(constantState("Enabled")),
        bind(MyModConfig::enabled),
        true,
    )

    // ---------------------------------------------------------------
    // getting-started.md
    // ---------------------------------------------------------------

    @Test
    fun `the first screen from getting started builds`() {
        val definition = configScreen(
            id("config"),
            "My Mod",
            "Configure how My Mod behaves.",
        ) {
            category(id("general"), "General", icon = Icons.gear) {
                section("Behaviour") {
                    toggle(
                        id("enabled"),
                        "Enabled",
                        bind(MyModConfig::enabled),
                        description = "Turns the whole thing on and off",
                    )
                    slider(
                        id("duration"),
                        "Duration",
                        bind(MyModConfig::duration),
                        1..30,
                        description = "How long notifications stay on screen",
                        format = { "$it s" },
                    )
                }
            }
        }

        assertEquals(2, definition.settingCount)
        assertEquals("Configure how My Mod behaves.", definition.description?.peek())
    }

    @Test
    fun `derived state from getting started tracks its dependency`() {
        val level = mutableStateOf(42, "level")
        val label = derivedStateOf("levelLabel") { "Lv. ${level.value}" }

        assertEquals("Lv. 42", label.peek())
        level.value = 43
        assertEquals("Lv. 43", label.peek())
    }

    @Test
    fun `a disposed scope releases everything registered through it`() {
        val moduleScope = RegistrationScope(id("module"))
        val registry = ComponentRegistry()
        val overlays = WorldOverlayLayer()

        registry.registerStandardControls(moduleScope)
        overlays.register(
            moduleScope,
            WorldOverlayDefinition(id("w"), constantState(WorldPosition.Origin), constantState("W")),
        )
        assertEquals(1, overlays.size)
        assertTrue(registry.hasRenderer(toggleSetting()))

        moduleScope.dispose()

        assertEquals(0, overlays.size)
        assertFalse(registry.hasRenderer(toggleSetting()))
    }

    // ---------------------------------------------------------------
    // configuration.md
    // ---------------------------------------------------------------

    @Test
    fun `every documented setting type is declarable and renders`() {
        val notes = mutableStateOf("line one\nline two", "notes")
        val blocked = mutableStateOf(listOf("a", "b"), "blocked")
        val theme = mutableStateOf("dark", "theme")
        val accent = mutableStateOf(Color.parse("#FF8B5CF6"), "accent")
        val opacity = mutableStateOf(0.5f, "opacity")
        val token = mutableStateOf("", "token")

        val definition = configScreen(id("all"), "Everything") {
            category(id("cat"), "Category", icon = Icons.gear) {
                section("Controls") {
                    toggle(id("toggle"), "Toggle", bind(MyModConfig::enabled))
                    button(id("button"), "Reset", label = "Reset", destructive = true) { }
                    slider(id("slider"), "Duration", bind(MyModConfig::duration), 1..30, format = { "$it s" })
                    decimalSlider(id("decimal"), "Opacity", opacity.asBinding(), 0f..1f, step = 0.05f)
                    textField(id("text"), "Name", bind(MyModConfig::username), placeholder = "Your name")
                    textField(id("masked"), "Token", token.asBinding(), masked = true)
                    textArea(id("area"), "Notes", notes.asBinding(), visibleLines = 4)
                    dropdown(
                        id("dropdown"), "Theme", theme.asBinding(),
                        listOf(option("dark", "Dark", "dark"), option("light", "Light", "light")),
                    )
                    colorPicker(id("color"), "Accent", accent.asBinding())
                    list(
                        id("list"), "Blocked", blocked.asBinding(),
                        SettingSerializers.string,
                        itemLabel = { it },
                        createItem = { "new entry" },
                        reorderable = true,
                        maxItems = 50,
                    )
                }
                section("Static") {
                    description(id("desc"), "Prose that is not a setting.")
                    divider(id("divider"))
                    warning(id("warn"), "Warning", "Something worth pausing over")
                    error(id("err"), "Error", "Something that is actually wrong")
                }
            }
        }

        // Every one of them must build a real control, not the placeholder.
        val registry = ComponentRegistry().apply { registerStandardControls(scope) }
        for (setting in definition.settings) {
            val node = registry.createNode(setting, context)
            var missing = false
            node.forEachInTree { if (it is MissingComponentNode) missing = true }
            assertFalse(missing, "${setting.id} rendered as a placeholder")
        }
    }

    @Test
    fun `the metadata block from configuration compiles and applies`() {
        val advanced = mutableStateOf(false, "advanced")

        val definition = configScreen(id("meta"), "Meta") {
            category(id("cat"), "Category") {
                section("Section") {
                    toggle(id("thing"), "Experimental thing", bind(MyModConfig::enabled)) {
                        tooltip("Shown on hover")
                        keywords("beta", "unstable")
                        warning("May cause visual glitches")
                        experimental = true
                        requiresRestart = true
                        enabledWhen = advanced
                        visibleWhen = advanced
                        confirmation = Confirmation(
                            title = "Are you sure?",
                            message = "This cannot be undone.",
                            isDestructive = true,
                        )
                    }
                }
            }
        }

        val setting = definition.settings.single()
        assertFalse(setting.metadata.visibleWhen.peek(), "hidden while advanced is off")
        advanced.value = true
        assertTrue(setting.metadata.visibleWhen.peek())
    }

    @Test
    fun `composed conditional visibility from configuration works`() {
        val advanced = mutableStateOf(false, "advanced")
        val enabled = mutableStateOf(true, "enabled")
        val visible = derivedStateOf("bothOn") { advanced.value && enabled.value }

        assertFalse(visible.peek())
        advanced.value = true
        assertTrue(visible.peek())
        enabled.value = false
        assertFalse(visible.peek())
    }

    // ---------------------------------------------------------------
    // bindings.md
    // ---------------------------------------------------------------

    @Test
    fun `all the binding forms from the guide work`() {
        val property = bind(MyModConfig::enabled)
        assertTrue(property.value)
        property.set(false)
        assertFalse(MyModConfig.enabled)

        val pair = bind(
            get = { MyModConfig.duration },
            set = { MyModConfig.duration = it },
            debugName = "duration",
        )
        pair.set(9)
        assertEquals(9, MyModConfig.duration)

        // Changed behind the framework's back: the mirror is stale until refreshed.
        MyModConfig.duration = 11
        assertEquals(9, pair.value, "the mirror has not been told")
        pair.refresh()
        assertEquals(11, pair.value)

        val state = mutableStateOf(true, "state")
        assertTrue(state.asBinding().isWritable)
        assertFalse(state.asReadOnlyBinding().isWritable)
    }

    @Test
    fun `the map example converts ticks to seconds in both directions`() {
        val seconds = bind(MyModConfig::durationTicks).map(
            to = { it / 20f },
            from = { (it * 20f).toInt() },
        )

        assertEquals(5f, seconds.value)
        seconds.set(2f)
        assertEquals(40, MyModConfig.durationTicks)
    }

    @Test
    fun `the documented validators behave as described`() {
        assertFalse(Validators.notBlank().validate(id("x"), "   ").isValid)
        assertTrue(Validators.notBlank().validate(id("x"), "ok").isValid)
        assertFalse(Validators.intRange(1..99).validate(id("x"), 500).isValid)
        assertFalse(Validators.length(2..4).validate(id("x"), "toolong").isValid)
        assertFalse(Validators.oneOf(listOf("a", "b")).validate(id("x"), "c").isValid)
        assertFalse(
            Validators.satisfies<Int>("must be even") { it % 2 == 0 }
                .validate(id("x"), 3).isValid,
        )
    }

    @Test
    fun `the custom validator example compiles and rejects`() {
        val reserved = setOf("admin", "root")
        val noReservedNames = Validator<String> { field, value ->
            if (value.lowercase() in reserved) {
                ValidationResult.error(field, "'$value' is reserved")
            } else {
                ValidationResult.valid()
            }
        }

        assertFalse(noReservedNames.validate(id("name"), "Admin").isValid)
        assertTrue(noReservedNames.validate(id("name"), "Steve").isValid)
    }

    @Test
    fun `the cross-field example relates both fields`() {
        val maximum = mutableStateOf(10, "maximum")
        val validator = Validators.crossField<Int, Int>(
            other = id("maximum"),
            otherValue = { maximum.value },
            message = "Minimum cannot exceed maximum",
        ) { value, other -> value <= other }

        assertTrue(validator.validate(id("minimum"), 5).isValid)
        val rejected = validator.validate(id("minimum"), 20)
        assertFalse(rejected.isValid)
        assertTrue(
            rejected.issues.single().relatedFields.contains(id("maximum")),
            "the issue should name the other side",
        )
    }

    // ---------------------------------------------------------------
    // extending.md
    // ---------------------------------------------------------------

    @Test
    fun `the custom serializer example round-trips and rejects bad input`() {
        val serializer = object : SettingSerializer<Pair<Int, Int>> {
            override fun encode(value: Pair<Int, Int>): JsonElement = buildJsonObject {
                put("x", JsonPrimitive(value.first))
                put("y", JsonPrimitive(value.second))
            }

            override fun decode(element: JsonElement): Pair<Int, Int> {
                val obj = element as? JsonObject
                    ?: throw IllegalArgumentException("Expected an object, got $element")
                return Pair(
                    (obj.getValue("x") as JsonPrimitive).content.toInt(),
                    (obj.getValue("y") as JsonPrimitive).content.toInt(),
                )
            }
        }

        assertEquals(Pair(3, 4), serializer.decode(serializer.encode(Pair(3, 4))))

        // The documented contract: unreadable input throws IllegalArgumentException, so
        // the caller can record it and fall back rather than losing the whole file.
        val failure = runCatching { serializer.decode(JsonPrimitive("nonsense")) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "got $failure")
    }

    @Test
    fun `the custom renderer example registers and is used`() {
        val registry = ComponentRegistry()
        registry.register<ToggleSetting>(scope) { setting, componentContext ->
            SettingRowNode(setting, componentContext, ToggleControlNode(setting, componentContext))
        }

        val node = registry.createNode(toggleSetting(), context)
        var missing = false
        node.forEachInTree { if (it is MissingComponentNode) missing = true }
        assertFalse(missing)
    }

    @Test
    fun `the custom component example observes and invalidates`() {
        val samples = mutableStateOf(listOf(1f, 2f, 3f), "samples")

        class SparklineNode(nodeId: UiId) : UiNode(nodeId) {
            var observed = 0

            init {
                samples.observe(scope) { observed++ }
            }

            override fun measureSelf(constraints: Constraints, context: LayoutContext): Size =
                Size(constraints.maxWidth, 24f)
        }

        val node = SparklineNode(id("sparkline"))
        runtime.root = node
        frame()

        samples.value = listOf(4f, 5f)
        assertEquals(1, node.observed, "the observer should have fired exactly once")
    }

    @Test
    fun `the icon registration example resolves and unregisters`() {
        val registry = IconRegistry()
        val sword = Icon(id("icon.sword"))
        registry.registerTexture(scope, sword.id, TextureRef(sword.id))

        assertEquals(1, registry.size)
        scope.dispose()
        assertEquals(0, registry.size, "disposing the scope unregisters the icon")
    }

    @Test
    fun `the idle-frame assertion from the performance guide holds`() {
        val definition = configScreen(id("idle"), "Idle") {
            category(id("cat"), "Category") {
                section("Section") {
                    toggle(id("a"), "A", bind(MyModConfig::enabled))
                }
            }
        }
        val registry = ComponentRegistry().apply { registerStandardControls(scope) }
        runtime.root = registry.createNode(definition.settings.single(), context)

        frame()
        val idle = frame()
        assertEquals(0, idle.nodesMeasured)
    }

    // ---------------------------------------------------------------
    // huds.md
    // ---------------------------------------------------------------

    @Test
    fun `the hud declaration and preview example from the guide works`() {
        val miningXp = mutableStateOf(0L, "miningXp")
        val miningXpRequired = mutableStateOf(1L, "miningXpRequired")
        val miningLevel = mutableStateOf(3, "miningLevel")

        val definition = HudDefinition(
            id = id("hud.mining_xp"),
            title = constantState("Mining XP"),
            category = constantState("Skills"),
            icon = Icons.gear,
            defaultAnchor = Anchor.BOTTOM_CENTER,
            defaultOffset = Vec2(0f, -90f),
            scaleRange = 0.5f..2.5f,
            resizeMode = HudResizeMode.SCALE_ONLY,
            updatePolicy = UpdatePolicy.OnChange,
            keywords = listOf("mining", "experience"),
        )

        val layer = HudLayerNode(id("layer")) { screen }
        runtime.root = layer

        val instance = HudInstance(id("hud.mining_xp.default"), definition.id)
        val element = HudElementNode(instance, definition) { node ->
            ProgressHudNode(
                id = instance.instanceId.child("content"),
                componentContext = context,
                title = constantState("Mining XP"),
                current = previewed(miningXp, 28_450L, node.isEditing, "xp.preview"),
                maximum = previewed(miningXpRequired, 60_000L, node.isEditing, "required.preview"),
                subtitle = derivedStateOf("level") { "Lv. ${miningLevel.value}" },
                icon = Icons.gear,
            )
        }
        layer.add(element)
        frame()

        assertEquals(0f, progressOf(element), "live data is 0 of 1, so the bar is empty")

        element.setEditing(true)
        settle()
        assertTrue(progressOf(element) > 0f, "preview data should fill the bar")

        element.setEditing(false)
        settle()
        assertEquals(0f, progressOf(element), "and live data comes back")
    }

    /** Runs enough frames for the progress animation to reach its target. */
    private fun settle() {
        repeat(SETTLE_FRAMES) { frame(1f / 10f) }
    }

    private fun progressOf(element: HudElementNode): Float {
        var found: ProgressHudNode? = null
        element.forEachInTree { if (found == null && it is ProgressHudNode) found = it }
        return found?.fillFraction ?: -1f
    }

    @Test
    fun `the placement calls from the hud guide behave as described`() {
        val definition = HudDefinition(
            id("hud.a"),
            constantState("A"),
            defaultAnchor = Anchor.TOP_LEFT,
            resizeMode = HudResizeMode.SCALE_AND_RESIZE,
        )
        val layer = HudLayerNode(id("layer")) { screen }
        runtime.root = layer
        val element = HudElementNode(HudInstance(id("a"), definition.id), definition) {
            ProgressHudNode(id("a.content"), context, constantState("A"), constantState(1L), constantState(2L))
        }
        layer.add(element)
        frame()

        element.moveTo(Vec2(120f, 40f), screen)
        frame()
        val before = element.placement.peek().resolve(element.scaledSize, screen)

        // Documented: re-anchoring does not move the element.
        element.reanchor(Anchor.BOTTOM_RIGHT, screen)
        frame()
        val after = element.placement.peek().resolve(element.scaledSize, screen)
        assertEquals(before.x, after.x, TOLERANCE)
        assertEquals(before.y, after.y, TOLERANCE)
        assertEquals(Anchor.BOTTOM_RIGHT, element.placement.peek().anchor)

        element.rescale(1.5f)
        assertEquals(1.5f, element.placement.peek().scale)

        element.reset()
        assertEquals(definition.defaultPlacement(), element.placement.peek())
    }

    // ---------------------------------------------------------------
    // notifications-and-overlays.md
    // ---------------------------------------------------------------

    @Test
    fun `the notification examples from the guide behave as documented`() {
        val queue = NotificationQueue()

        queue.post(
            Notification(
                id = id("notify.saved"),
                title = constantState("Waypoint saved"),
                message = constantState("at 120, 64, -300"),
                severity = NotificationSeverity.SUCCESS,
            ),
        )
        assertEquals(1, queue.showing.peek().size)

        repeat(3) {
            queue.post(
                Notification(
                    id = id("notify.pickup"),
                    title = constantState("Picked up Diamond"),
                    duration = 2.seconds,
                    coalesceKey = "pickup",
                ),
            )
        }
        assertEquals(3, queue.showing.peek().first { it.id == id("notify.pickup") }.count)

        // Pausing stops the clock, so nothing expires unseen behind a screen.
        queue.isPaused = true
        repeat(10) { queue.tick(1f) }
        assertTrue(queue.showing.peek().any { it.id == id("notify.pickup") })
    }

    @Test
    fun `the world overlay example fades and culls as documented`() {
        val overlays = WorldOverlayLayer()
        overlays.register(
            scope,
            WorldOverlayDefinition(
                id = id("waypoint.home"),
                position = mutableStateOf(WorldPosition(120.0, 64.0, -300.0), "home"),
                label = constantState("Home"),
                color = Color.parse("#FF8B5CF6"),
                fade = DistanceFade(nearDistance = 64.0, farDistance = 512.0, minimumDistance = 2.0),
                showsDistance = true,
            ),
        )

        fun resolveAt(distance: Double) =
            overlays.resolve({ WorldProjection(Vec2(320f, 180f), distance, false) }, screen)

        assertEquals(1, resolveAt(10.0).size, "inside the fade range")
        assertEquals(0, resolveAt(1.0).size, "below the minimum distance it hides entirely")
        assertEquals(0, resolveAt(1000.0).size, "beyond the far distance it is culled, not transparent")
    }

    private companion object {
        const val TOLERANCE = 0.01f
        const val SETTLE_FRAMES = 40
    }
}
