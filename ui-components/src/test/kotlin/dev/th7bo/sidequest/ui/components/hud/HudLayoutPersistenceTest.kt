package dev.th7bo.sidequest.ui.components.hud

import dev.th7bo.sidequest.ui.components.Icons
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.hud.HudContext
import dev.th7bo.sidequest.ui.core.hud.HudElementNode
import dev.th7bo.sidequest.ui.core.hud.HudLayerNode
import dev.th7bo.sidequest.ui.core.hud.HudLayoutPersistence
import dev.th7bo.sidequest.ui.core.persistence.JsonFileConfigStore
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.geometry.Anchor
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.geometry.Vec2
import dev.th7bo.sidequest.ui.hud.HudDefinition
import dev.th7bo.sidequest.ui.hud.HudInstance
import dev.th7bo.sidequest.ui.hud.HudPlacement
import dev.th7bo.sidequest.ui.hud.HudResizeMode
import dev.th7bo.sidequest.ui.ids.ProfileId
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.persistence.ConfigSnapshot
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.testkit.FakeTextMeasurer
import dev.th7bo.sidequest.ui.testkit.RecordingRenderer
import dev.th7bo.sidequest.ui.theme.DarkTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * HUD placement has to survive quitting the game.
 *
 * The store itself is already covered by the configuration persistence tests; what is
 * tested here is the layer adapter — that a placement round-trips exactly, that a HUD
 * the file has never seen keeps its default, and that a placement belonging to a HUD
 * which is not currently on the layer is not quietly discarded.
 */
class HudLayoutPersistenceTest {

    private fun id(path: String) = UiId.of("sidequest", path)

    @TempDir
    lateinit var root: Path

    private lateinit var runtime: UiRuntime
    private lateinit var context: ComponentContext
    private lateinit var layer: HudLayerNode

    /**
     * Owned by the test rather than a global scope: these cases drive `snapshot` and
     * `apply` directly, so nothing is actually launched, and a leaked scope outliving
     * the test would be a worse problem than the convenience is worth.
     */
    private val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())

    private val screen = Size(640f, 360f)

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        val renderer = RecordingRenderer(screen, FakeTextMeasurer())
        runtime = UiRuntime(DarkTheme).apply {
            viewport = screen
            prepare(renderer)
        }
        context = ComponentContext(DarkTheme, runtime.animations, runtime)
        layer = HudLayerNode(id("hud_layer")) { screen }
        runtime.root = layer
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
        runtime.dispose()
        resetReactiveGraphForTesting()
    }

    private fun hud(name: String, anchor: Anchor = Anchor.TOP_LEFT): HudElementNode {
        val definition = HudDefinition(
            id = id("hud.$name"),
            title = constantState(name),
            icon = Icons.gear,
            defaultAnchor = anchor,
            scaleRange = 0.25f..4f,
            resizeMode = HudResizeMode.SCALE_AND_RESIZE,
        )
        val instance = HudInstance(id("instance.$name"), definition.id)
        val content = ProgressHudNode(
            id = id("$name.content"),
            componentContext = context,
            title = constantState(name),
            current = constantState(10L),
            maximum = constantState(100L),
            icon = Icons.gear,
        )
        val element = HudElementNode(instance, definition, {
            HudContext(screen, 2f, 0f, context)
        }, content)
        layer.add(element)
        return element
    }

    private fun store(version: Int = 1) = JsonFileConfigStore(
        root = root,
        currentVersion = version,
        fileName = HudLayoutPersistence.FILE_NAME,
    )

    private fun persistence(version: Int = 1) = HudLayoutPersistence(
        layer = layer,
        store = store(version),
        coroutineScope = testScope,
        scheduler = runtime,
        schemaVersion = version,
    )

    private fun layoutFile(): Path =
        root.resolve("profiles").resolve(ProfileId.DEFAULT.value).resolve(HudLayoutPersistence.FILE_NAME)

    @Test
    fun `a placement survives a save and load`() {
        val element = hud("mining")
        val moved = HudPlacement(
            anchor = Anchor.BOTTOM_RIGHT,
            offset = Vec2(-24f, -48f),
            scale = 1.75f,
            zIndex = 3,
            opacity = 0.8f,
            locked = true,
        )
        element.setPlacement(moved)

        val saved = persistence().snapshot()

        // A fresh element, as after a restart: back at its definition default.
        element.reset()
        assertEquals(element.definition.defaultPlacement(), element.placement.peek())

        val rejected = persistence().apply(saved)
        assertTrue(rejected.isEmpty(), "nothing should have been rejected, got $rejected")
        assertEquals(moved, element.placement.peek(), "every field must round-trip")
    }

    @ParameterizedTest
    @EnumSource(Anchor::class)
    fun `every anchor round-trips`(anchor: Anchor) {
        val element = hud("mining")
        element.setPlacement(HudPlacement(anchor = anchor, offset = Vec2(7f, -13f)))

        val saved = persistence().snapshot()
        element.reset()
        persistence().apply(saved)

        assertEquals(anchor, element.placement.peek().anchor)
        assertEquals(Vec2(7f, -13f), element.placement.peek().offset)
    }

    @Test
    fun `a resizable hud keeps its size`() {
        val element = hud("panel")
        element.resize(Size(140f, 60f))
        val expected = element.placement.peek().size
        assertNotNull(expected)

        val saved = persistence().snapshot()
        element.reset()
        persistence().apply(saved)

        assertEquals(expected, element.placement.peek().size)
    }

    @Test
    fun `it actually reaches the disk and comes back`() = runBlocking {
        val element = hud("mining")
        element.setPlacement(HudPlacement(anchor = Anchor.CENTER, offset = Vec2(5f, 6f), scale = 2f))

        val written = persistence().snapshot()
        store().save(ProfileId.DEFAULT, written)

        assertTrue(layoutFile().toFile().exists(), "the layout file should exist at ${layoutFile()}")

        element.reset()
        val result = store().load(ProfileId.DEFAULT)
        persistence().apply(result.snapshot)

        assertEquals(Anchor.CENTER, element.placement.peek().anchor)
        assertEquals(Vec2(5f, 6f), element.placement.peek().offset)
        assertEquals(2f, element.placement.peek().scale)
    }

    @Test
    fun `a hud absent from the file keeps its default rather than jumping to the origin`() {
        val known = hud("mining")
        known.setPlacement(HudPlacement(anchor = Anchor.CENTER, offset = Vec2(3f, 3f)))
        val saved = persistence().snapshot()

        // A HUD added by a later build, which the saved file has never seen.
        val added = hud("compass", anchor = Anchor.BOTTOM_RIGHT)
        val before = added.placement.peek()

        persistence().apply(saved)

        assertEquals(before, added.placement.peek(), "an unknown HUD must keep its default placement")
        assertEquals(Anchor.CENTER, known.placement.peek().anchor)
    }

    @Test
    fun `a placement for a hud that is not loaded is preserved, not dropped`() {
        val element = hud("mining")
        element.setPlacement(HudPlacement(anchor = Anchor.CENTER))

        // As if a second mod's HUD had been placed and that mod is now disabled.
        val foreign = id("instance.from_another_mod")
        val withForeign = ConfigSnapshot(
            schemaVersion = 1,
            values = persistence().snapshot().values + mapOf(
                foreign to kotlinx.serialization.json.Json.encodeToJsonElement(
                    HudPlacement.serializer(),
                    HudPlacement(anchor = Anchor.TOP_RIGHT, offset = Vec2(-9f, 9f)),
                ),
            ),
        )

        val controller = persistence()
        controller.apply(withForeign)
        val rewritten = controller.snapshot()

        assertTrue(
            foreign in rewritten.values,
            "a placement for an unloaded HUD must survive a rewrite, or disabling a mod " +
                "for one session would lose its layout",
        )
    }

    @Test
    fun `an unreadable entry is reported without costing the rest of the layout`() {
        val good = hud("mining")
        val bad = hud("broken")

        val snapshot = ConfigSnapshot(
            schemaVersion = 1,
            values = mapOf(
                good.instance.instanceId to kotlinx.serialization.json.Json.encodeToJsonElement(
                    HudPlacement.serializer(),
                    HudPlacement(anchor = Anchor.CENTER, offset = Vec2(4f, 4f)),
                ),
                bad.instance.instanceId to kotlinx.serialization.json.Json.parseToJsonElement("""{"anchor":"nonsense"}"""),
            ),
        )

        val rejected = persistence().apply(snapshot)

        assertEquals(setOf(bad.instance.instanceId), rejected.keys, "only the bad entry should be rejected")
        assertEquals(Anchor.CENTER, good.placement.peek().anchor, "the good entry must still apply")
        assertEquals(bad.definition.defaultPlacement(), bad.placement.peek(), "the bad one keeps its default")
    }

    @Test
    fun `a corrupt file is quarantined rather than losing the layout silently`() = runBlocking {
        hud("mining")
        val file = layoutFile()
        file.parent.toFile().mkdirs()
        file.writeText("{ this is not json")

        val result = store().load(ProfileId.DEFAULT)

        assertNotNull(result.report.corruptionBackupPath, "the unreadable file should have been kept")
        assertTrue(result.snapshot.values.isEmpty(), "and the layout falls back to defaults")
    }

    @Test
    fun `two instances of one definition keep separate placements`() {
        val first = hud("timer")
        val definition = first.definition
        val second = HudElementNode(
            HudInstance(id("instance.timer2"), definition.id),
            definition,
            { HudContext(screen, 2f, 0f, context) },
            ProgressHudNode(
                id = id("timer2.content"),
                componentContext = context,
                title = constantState("timer"),
                current = constantState(1L),
                maximum = constantState(2L),
            ),
        )
        layer.add(second)

        first.setPlacement(HudPlacement(anchor = Anchor.TOP_LEFT, offset = Vec2(1f, 1f)))
        second.setPlacement(HudPlacement(anchor = Anchor.BOTTOM_RIGHT, offset = Vec2(-2f, -2f)))

        val saved = persistence().snapshot()
        first.reset()
        second.reset()
        persistence().apply(saved)

        assertEquals(Vec2(1f, 1f), first.placement.peek().offset)
        assertEquals(Vec2(-2f, -2f), second.placement.peek().offset)
    }

    @Test
    fun `the written file is readable json keyed by instance id`() = runBlocking {
        val element = hud("mining")
        element.setPlacement(HudPlacement(anchor = Anchor.BOTTOM_CENTER, offset = Vec2(0f, -90f)))

        store().save(ProfileId.DEFAULT, persistence().snapshot())
        val text = layoutFile().readText()

        assertTrue(element.instance.instanceId.value in text, "the instance id should key the entry")
        assertTrue("bottom_center" in text, "the anchor should be stored by its serialized id, not its ordinal")
    }
}
