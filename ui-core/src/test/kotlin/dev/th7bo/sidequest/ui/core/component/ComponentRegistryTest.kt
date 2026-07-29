package dev.th7bo.sidequest.ui.core.component

import dev.th7bo.sidequest.ui.binding.asBinding
import dev.th7bo.sidequest.ui.config.Setting
import dev.th7bo.sidequest.ui.config.SettingMetadata
import dev.th7bo.sidequest.ui.config.SettingSerializers
import dev.th7bo.sidequest.ui.config.ToggleSetting
import dev.th7bo.sidequest.ui.core.UiTestHarness
import dev.th7bo.sidequest.ui.core.UiTestHarness.Companion.id
import dev.th7bo.sidequest.ui.core.animation.AnimationHost
import dev.th7bo.sidequest.ui.core.content.SurfaceNode
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.extension.DuplicateRegistrationException
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.theme.DarkTheme
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ComponentRegistryTest {

    private val coreOwner = UiId.of("sidequest", "core")
    private val addonOwner = UiId.of("thirdparty", "addon")

    private lateinit var harness: UiTestHarness
    private lateinit var registry: ComponentRegistry
    private lateinit var context: ComponentContext

    /** A third-party setting type the framework knows nothing about. */
    private class GradientSetting(id: UiId) : Setting<String>(
        id,
        SettingMetadata(constantState("Gradient")),
        mutableStateOf("#000000").asBinding(),
        "#000000",
        SettingSerializers.string,
    )

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        harness = UiTestHarness(Size(200f, 200f))
        registry = ComponentRegistry()
        context = ComponentContext(DarkTheme, AnimationHost(), harness.runtime, isDevelopment = true)
    }

    @AfterEach
    fun tearDown() {
        harness.dispose()
        resetReactiveGraphForTesting()
    }

    private fun toggle(path: String) = ToggleSetting(
        id(path),
        SettingMetadata(constantState("A Toggle")),
        mutableStateOf(false).asBinding(),
        false,
    )

    private fun stubRenderer(marker: String) = SettingRenderer<Setting<*>> { setting, _ ->
        SurfaceNode(setting.id.child(marker)).apply { preferredSize = Size(10f, 10f) }
    }

    @Test
    fun `a registered renderer builds the control`() {
        val scope = RegistrationScope(coreOwner)
        registry.register(scope, ToggleSetting::class, stubRenderer("toggle"))

        val setting = toggle("flag")
        assertTrue(registry.hasRenderer(setting))

        val node = registry.createNode(setting, context)
        assertInstanceOf(SurfaceNode::class.java, node)
        assertEquals(id("flag").child("toggle"), node.id)
    }

    @Test
    fun `a third-party type can be registered without touching framework source`() {
        val scope = RegistrationScope(addonOwner)
        registry.register(scope, GradientSetting::class, stubRenderer("gradient"))

        val node = registry.createNode(GradientSetting(id("grad")), context)

        assertInstanceOf(SurfaceNode::class.java, node)
        assertEquals(addonOwner, registry.ownerOf(GradientSetting::class))
    }

    @Test
    fun `a missing renderer produces a diagnostic placeholder instead of crashing`() {
        val setting = toggle("unrenderable")

        val node = registry.createNode(setting, context)

        val placeholder = assertInstanceOf(MissingComponentNode::class.java, node)
        assertEquals(setting.id, placeholder.settingId)
        assertEquals("ToggleSetting", placeholder.settingType)
        assertTrue(placeholder.problem.contains("No renderer registered"))
        assertEquals(1, registry.recordedFailures.size)
    }

    @Test
    fun `a renderer that throws is contained and recorded`() {
        val scope = RegistrationScope(coreOwner)
        registry.register(scope, ToggleSetting::class) { _, _ ->
            throw IllegalStateException("renderer is broken")
        }

        val node = registry.createNode(toggle("boom"), context)

        val placeholder = assertInstanceOf(MissingComponentNode::class.java, node)
        assertEquals("renderer is broken", placeholder.problem)
        assertEquals("renderer is broken", registry.recordedFailures.single().cause?.message)
    }

    @Test
    fun `one broken control does not stop the rest of the screen rendering`() {
        val scope = RegistrationScope(coreOwner)
        registry.register(scope, GradientSetting::class) { _, _ -> error("broken") }
        registry.register(scope, ToggleSetting::class, stubRenderer("toggle"))

        val nodes = listOf(
            registry.createNode(toggle("a"), context),
            registry.createNode(GradientSetting(id("b")), context),
            registry.createNode(toggle("c"), context),
        )

        assertInstanceOf(SurfaceNode::class.java, nodes[0])
        assertInstanceOf(MissingComponentNode::class.java, nodes[1])
        assertInstanceOf(SurfaceNode::class.java, nodes[2], "the row after the failure still renders")
    }

    @Test
    fun `the placeholder lays out and paints without a registered renderer`() {
        val node = registry.createNode(toggle("missing"), context)
        harness.root = node
        val metrics = harness.frame()

        assertTrue(node.measuredSize.width > 0f)
        assertTrue(node.measuredSize.height > 0f)
        assertTrue(metrics.drawCalls > 0, "the placeholder must be visible, not an invisible gap")
    }

    @Test
    fun `in production the placeholder hides the detail but keeps the space`() {
        val productionContext = ComponentContext(
            DarkTheme,
            AnimationHost(),
            harness.runtime,
            isDevelopment = false,
        )
        val node = registry.createNode(toggle("missing"), productionContext)
        harness.root = node
        harness.frame()

        val texts = harness.renderer.commands
            .filterIsInstance<dev.th7bo.sidequest.ui.testkit.DrawCommand.Text>()
        assertTrue(texts.isEmpty(), "a normal user must not be shown renderer internals")
        assertTrue(node.measuredSize.height > 0f, "but the row must still occupy space")
    }

    @Test
    fun `registering the same type twice names both owners`() {
        registry.register(RegistrationScope(coreOwner), ToggleSetting::class, stubRenderer("first"))

        val failure = assertThrows(DuplicateRegistrationException::class.java) {
            registry.register(RegistrationScope(addonOwner), ToggleSetting::class, stubRenderer("second"))
        }

        assertEquals(coreOwner, failure.existingOwner)
        assertEquals(addonOwner, failure.attemptedOwner)
    }

    @Test
    fun `disposing a scope unregisters its renderers`() {
        val scope = RegistrationScope(addonOwner)
        registry.register(scope, GradientSetting::class, stubRenderer("gradient"))
        assertEquals(1, registry.size)

        scope.dispose()

        assertEquals(0, registry.size)
        assertFalse(registry.hasRenderer(GradientSetting(id("g"))))
        assertTrue(registry.typesOwnedBy(addonOwner).isEmpty())
    }

    @Test
    fun `a module can be unloaded and reloaded`() {
        val first = RegistrationScope(addonOwner)
        registry.register(first, GradientSetting::class, stubRenderer("v1"))
        first.dispose()

        val second = RegistrationScope(addonOwner)
        registry.register(second, GradientSetting::class, stubRenderer("v2"))

        val node = registry.createNode(GradientSetting(id("g")), context)
        assertEquals(id("g").child("v2"), node.id)
    }

    @Test
    fun `registering into a disposed scope is rejected`() {
        val scope = RegistrationScope(coreOwner)
        scope.dispose()

        assertThrows(IllegalStateException::class.java) {
            registry.register(scope, ToggleSetting::class, stubRenderer("x"))
        }
    }

    @Test
    fun `only exactly matching types resolve, so a subtype needs its own renderer`() {
        val scope = RegistrationScope(coreOwner)
        registry.register(scope, ToggleSetting::class, stubRenderer("toggle"))

        assertFalse(
            registry.hasRenderer(GradientSetting(id("g"))),
            "a different setting type must not silently reuse another's renderer",
        )
    }
}
