package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.binding.bind
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.core.component.ComponentContext
import dev.th7bo.sidequest.ui.core.component.ComponentRegistry
import dev.th7bo.sidequest.ui.core.runtime.UiRuntime
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.geometry.Size
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.testkit.FakeTextMeasurer
import dev.th7bo.sidequest.ui.testkit.RecordingRenderer
import dev.th7bo.sidequest.ui.theme.DarkTheme
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Folding a section away.
 *
 * `Section.isCollapsible` and the `collapsible` DSL parameter had both existed since phase 2 and the renderer
 * ignored them — so a screen could declare a folding section and get a permanently open one. Nothing caught
 * that, because every test asserted on the *model*, which was correct the whole time.
 *
 * These assert on the rows the list is actually asked to build.
 */
class CollapsibleSectionTest {

    private fun id(path: String) = UiId.of("sidequest", path)

    private lateinit var runtime: UiRuntime
    private lateinit var context: ComponentContext
    private lateinit var scope: RegistrationScope
    private lateinit var registry: ComponentRegistry

    private class Holder {
        var first = true
        var second = false
    }

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        val renderer = RecordingRenderer(Size(400f, 300f), FakeTextMeasurer())
        runtime = UiRuntime(DarkTheme).apply {
            viewport = Size(400f, 300f)
            prepare(renderer)
        }
        context = ComponentContext(DarkTheme, runtime.animations, runtime)
        scope = RegistrationScope(id("collapse"))
        registry = ComponentRegistry().apply { registerStandardControls(scope) }
    }

    @AfterEach
    fun tearDown() {
        scope.dispose()
        runtime.dispose()
        resetReactiveGraphForTesting()
    }

    private fun screenOf(holder: Holder, startsCollapsed: Boolean): ConfigScreen =
        configScreen(id("config"), "Test") {
            category(id("c"), "Category") {
                section("Folding", id = id("c.folding"), collapsible = true, startsCollapsed = startsCollapsed) {
                    toggle(id("c.folding.first"), "First", bind(holder::first))
                    toggle(id("c.folding.second"), "Second", bind(holder::second))
                }
                section("Fixed", id = id("c.fixed")) {
                    toggle(id("c.fixed.only"), "Only", bind(holder::first))
                }
            }
        }

    private fun providerFor(screen: ConfigScreen): ConfigRowProvider =
        ConfigRowProvider(screen, registry, context).also { it.rebuild(screen.categories.first()) }

    private fun entryIds(provider: ConfigRowProvider) =
        provider.currentRows.filterIsInstance<ConfigRow.Entry>().map { it.setting.id }

    private fun headerCount(provider: ConfigRowProvider) =
        provider.currentRows.count { it is ConfigRow.Header }

    @Test
    fun `an open section contributes its settings`() {
        val provider = providerFor(screenOf(Holder(), startsCollapsed = false))

        assertEquals(3, entryIds(provider).size, "two folding, one fixed")
        assertEquals(2, headerCount(provider))
    }

    /**
     * The regression: a section that starts folded shows only its header.
     *
     * Before the renderer honoured it, this returned all three settings and the `startsCollapsed` flag was
     * decoration.
     */
    @Test
    fun `a section that starts collapsed contributes only its header`() {
        val provider = providerFor(screenOf(Holder(), startsCollapsed = true))

        assertEquals(listOf(id("c.fixed.only")), entryIds(provider), "the folded section's settings are gone")
        assertEquals(2, headerCount(provider), "but its header stays, or there is nothing to unfold")
    }

    @Test
    fun `a collapsed header is a complete card rather than the top of one`() {
        val provider = providerFor(screenOf(Holder(), startsCollapsed = true))

        val folded = provider.currentRows.filterIsInstance<ConfigRow.Header>()
            .single { it.section.id == id("c.folding") }

        // Otherwise it draws with a square bottom edge, waiting for rows that are not coming.
        assertEquals(CardSegment.SINGLE, folded.segment)
    }

    /**
     * Searching opens everything.
     *
     * A result hidden inside a folded section is a search that appears to have found nothing, which is worse
     * than no search at all.
     */
    @Test
    fun `a search reaches into folded sections`() {
        val screen = screenOf(Holder(), startsCollapsed = true)
        val provider = ConfigRowProvider(screen, registry, context)

        provider.rebuild(screen.categories.first(), visibleIds = setOf(id("c.folding.second")))

        assertEquals(listOf(id("c.folding.second")), entryIds(provider))
    }

    @Test
    fun `a section that is not collapsible is never folded`() {
        val screen = screenOf(Holder(), startsCollapsed = true)
        val provider = providerFor(screen)

        val fixed = provider.currentRows.filterIsInstance<ConfigRow.Header>()
            .single { it.section.id == id("c.fixed") }

        assertFalse(fixed.section.isCollapsible)
        assertEquals(CardSegment.TOP, fixed.segment, "it still has rows under it")
    }

    /**
     * The header has to be hit-testable, or the chevron is decoration.
     *
     * `UiNode.interactive` defaults to false and the hit test consults it, so the first version drew a fold
     * indicator that nothing could press — the model folded correctly and no click ever reached it. Every
     * other test here calls the fold directly and would have passed forever.
     */
    @Test
    fun `a folding header takes clicks and a fixed one does not`() {
        val screen = screenOf(Holder(), startsCollapsed = false)
        val category = screen.categories.first()

        val folding = SectionCardHeaderNode(
            section = category.sections.first { it.id == id("c.folding") },
            componentContext = context,
            isCollapsed = { false },
            onToggle = {},
        )
        val fixed = SectionCardHeaderNode(
            section = category.sections.first { it.id == id("c.fixed") },
            componentContext = context,
        )

        assertTrue(folding.interactive, "a folding header must be reachable by a pointer")
        assertFalse(fixed.interactive, "and one that does nothing must not swallow clicks")
    }

    /** Folding is per screen, not per section: two screens showing one config fold independently. */
    @Test
    fun `two providers over one screen fold independently`() {
        val screen = screenOf(Holder(), startsCollapsed = false)
        val a = providerFor(screen)
        val b = providerFor(screen)

        var rebuilt = false
        a.onFoldChanged = { rebuilt = true; a.rebuild(screen.categories.first()) }
        a.foldForTesting(id("c.folding"))

        assertTrue(rebuilt, "the provider has to tell its screen, since it cannot invalidate a list itself")
        assertEquals(1, entryIds(a).size, "folded here")
        assertEquals(3, entryIds(b).size, "and not there")
    }
}
