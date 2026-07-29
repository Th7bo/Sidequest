package dev.th7bo.sidequest.ui.config

import dev.th7bo.sidequest.ui.binding.asBinding
import dev.th7bo.sidequest.ui.binding.bind
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.state.map
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.not
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import dev.th7bo.sidequest.ui.validation.Severity
import dev.th7bo.sidequest.ui.validation.Validators
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConfigDslTest {

    private fun id(path: String) = UiId.of("sidequest", path)

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
    }

    @AfterEach
    fun tearDown() {
        resetReactiveGraphForTesting()
    }

    private class DemoConfig {
        var notifications = true
        var duration = 5
        var compact = false
        var accent = Color.parse("#8B5CF6")
        var theme = "dark"
    }

    @Test
    fun `a normal screen is a handful of lines and produces a full model`() {
        val config = DemoConfig()

        val screen = configScreen(id("main"), "Sidequest") {
            category(id("general"), "General") {
                section("Interface") {
                    toggle(
                        id = id("general.notifications"),
                        title = "Show In-Game Notifications",
                        value = bind(config::notifications),
                        description = "Pop up a card when something notable happens",
                    )
                    slider(
                        id = id("general.duration"),
                        title = "Notification Duration",
                        value = bind(config::duration),
                        range = 1..60,
                    )
                    colorPicker(
                        id = id("general.accent"),
                        title = "Accent Colour",
                        value = bind(config::accent),
                    )
                }
            }
        }

        assertEquals(3, screen.settingCount)
        assertEquals(1, screen.categories.size)
        assertEquals(1, screen.categories[0].sections.size)

        val toggle = screen[id("general.notifications")]
        assertNotNull(toggle)
        assertTrue(toggle is ToggleSetting)
        assertEquals("Show In-Game Notifications", toggle!!.metadata.title.peek())
        assertEquals("General > Interface", toggle.location?.path)
    }

    @Test
    fun `duplicate setting ids name both locations`() {
        val config = DemoConfig()

        val failure = assertThrows(DuplicateConfigIdException::class.java) {
            configScreen(id("main"), "Sidequest") {
                category(id("general"), "General") {
                    section("First") {
                        toggle(id("dup"), "One", bind(config::notifications))
                    }
                    section("Second") {
                        toggle(id("dup"), "Two", bind(config::compact))
                    }
                }
            }
        }

        assertEquals("setting", failure.kind)
        assertEquals("General > First", failure.existingPath)
        assertEquals("General > Second", failure.attemptedPath)
    }

    @Test
    fun `duplicate category ids are caught too`() {
        assertThrows(DuplicateConfigIdException::class.java) {
            configScreen(id("main"), "Sidequest") {
                category(id("general"), "General") { section("A") { } }
                category(id("general"), "General Again") { section("B") { } }
            }
        }
    }

    @Test
    fun `sections derive a stable id from their title`() {
        val screen = configScreen(id("main"), "Sidequest") {
            category(id("general"), "General") {
                section("Chat & Messages") { }
            }
        }

        assertEquals(
            UiId.of("sidequest", "general.section.chat_messages"),
            screen.categories[0].sections[0].id,
        )
    }

    @Test
    fun `conditional visibility is a typed dependency, not a polled lambda`() {
        val showNotifications = mutableStateOf(true, "showNotifications")
        val compact = mutableStateOf(false, "compact")
        val duration = mutableStateOf(5)

        val screen = configScreen(id("main"), "Sidequest") {
            category(id("general"), "General") {
                section("Interface") {
                    slider(
                        id = id("notifications.duration"),
                        title = "Notification Duration",
                        value = duration.asBinding(),
                        range = 1..60,
                    ) {
                        visibleWhen = showNotifications
                        enabledWhen = compact.map { !it }
                    }
                }
            }
        }

        val setting = screen[id("notifications.duration")]!!
        assertTrue(setting.isVisible.value)
        assertTrue(setting.isEnabled.value)

        showNotifications.value = false
        assertFalse(setting.isVisible.value)

        compact.value = true
        assertFalse(setting.isEnabled.value)
    }

    @Test
    fun `dynamic options recompute when their source changes`() {
        val advanced = mutableStateOf(false)
        val selected = mutableStateOf("basic")
        val optionList = advanced.map { isAdvanced ->
            if (isAdvanced) {
                listOf(option("basic", "Basic", "basic"), option("expert", "Expert", "expert"))
            } else {
                listOf(option("basic", "Basic", "basic"))
            }
        }

        val screen = configScreen(id("main"), "Sidequest") {
            category(id("general"), "General") {
                section("Mode") {
                    dropdown(id("general.mode"), "Mode", selected.asBinding(), optionList)
                }
            }
        }

        val dropdown = screen[id("general.mode")] as DropdownSetting<*>
        assertEquals(1, dropdown.options.value.size)

        advanced.value = true
        assertEquals(2, dropdown.options.value.size)
    }

    @Test
    fun `an invalid write is rejected and leaves the model untouched`() {
        val duration = mutableStateOf(5)
        val screen = configScreen(id("main"), "Sidequest") {
            category(id("general"), "General") {
                section("Interface") {
                    slider(
                        id = id("general.duration"),
                        title = "Duration",
                        value = duration.asBinding(),
                        range = 1..60,
                        validator = Validators.intRange(1..60),
                    )
                }
            }
        }

        val setting = screen.typed<Int>(id("general.duration"))!!

        val result = setting.set(500)

        assertFalse(result.isValid)
        assertEquals(5, setting.value, "an invalid value must never reach the model")
        assertEquals(id("general.duration"), result.errors.single().field)
        assertNotNull(result.errors.single().remediation)
    }

    @Test
    fun `a valid write goes through and reports success`() {
        val duration = mutableStateOf(5)
        val setting = IntSliderSetting(
            id("general.duration"),
            SettingMetadata(dev.th7bo.sidequest.ui.state.constantState("Duration")),
            duration.asBinding(),
            5,
            1..60,
            validator = Validators.intRange(1..60),
        )

        assertTrue(setting.set(30).isValid)
        assertEquals(30, setting.value)
    }

    @Test
    fun `isModified tracks divergence from the default`() {
        val enabled = mutableStateOf(false)
        val setting = ToggleSetting(
            id("f.enabled"),
            SettingMetadata(dev.th7bo.sidequest.ui.state.constantState("Enabled")),
            enabled.asBinding(),
            defaultValue = false,
        )

        assertFalse(setting.isModified.value)
        setting.toggle()
        assertTrue(setting.isModified.value)
        setting.reset()
        assertFalse(setting.isModified.value)
    }

    @Test
    fun `a not-resettable setting refuses reset`() {
        val text = mutableStateOf("current")
        val setting = TextSetting(
            id("f.name"),
            SettingMetadata(
                dev.th7bo.sidequest.ui.state.constantState("Name"),
                resetBehaviour = ResetBehaviour.NOT_RESETTABLE,
            ),
            text.asBinding(),
            defaultValue = "",
        )

        assertFalse(setting.reset())
        assertEquals("current", setting.value)
    }

    @Test
    fun `a permission gate disables the setting without hiding it`() {
        val granted = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val setting = ToggleSetting(
            id("f.admin"),
            SettingMetadata(
                dev.th7bo.sidequest.ui.state.constantState("Admin Only"),
                permission = { granted.peek() },
            ),
            enabled.asBinding(),
            defaultValue = true,
        )

        assertTrue(setting.isVisible.value, "a locked setting is still shown")
        assertFalse(setting.isEnabled.value)
    }

    @Test
    fun `slider fraction mapping round-trips and respects the step`() {
        val value = mutableStateOf(0)
        val setting = IntSliderSetting(
            id("f.volume"),
            SettingMetadata(dev.th7bo.sidequest.ui.state.constantState("Volume")),
            value.asBinding(),
            0,
            0..100,
            step = 5,
        )

        assertEquals(0, setting.valueAtFraction(0f))
        assertEquals(100, setting.valueAtFraction(1f))
        assertEquals(50, setting.valueAtFraction(0.5f))
        assertEquals(35, setting.valueAtFraction(0.34f), "must snap to the nearest step")
        assertEquals(0.5f, setting.fractionOf(50))
    }

    @Test
    fun `a slider default outside its range fails at construction`() {
        val value = mutableStateOf(500)
        assertThrows(IllegalArgumentException::class.java) {
            IntSliderSetting(
                id("f.bad"),
                SettingMetadata(dev.th7bo.sidequest.ui.state.constantState("Bad")),
                value.asBinding(),
                defaultValue = 500,
                range = 0..100,
            )
        }
    }

    @Test
    fun `buttons and notices are excluded from persistence`() {
        var invoked = 0
        val screen = configScreen(id("main"), "Sidequest") {
            category(id("general"), "General") {
                section("Actions") {
                    button(id("general.reset"), "Reset Everything", destructive = true) { invoked++ }
                    description(id("general.blurb"), "These settings affect the whole mod.")
                    divider(id("general.rule"))
                    toggle(id("general.flag"), "A Flag", mutableStateOf(false).asBinding())
                }
            }
        }

        val persistent = screen.settings.filter { it.isPersistent() }
        assertEquals(listOf(id("general.flag")), persistent.map { it.id })

        (screen[id("general.reset")] as ButtonSetting).invoke()
        assertEquals(1, invoked)
    }

    @Test
    fun `validateAll aggregates issues across the whole screen`() {
        val name = mutableStateOf("")
        val duration = mutableStateOf(999)

        val screen = configScreen(id("main"), "Sidequest") {
            category(id("general"), "General") {
                section("Interface") {
                    textField(
                        id = id("general.name"),
                        title = "Name",
                        value = name.asBinding(),
                        validator = Validators.notBlank(),
                    )
                    slider(
                        id = id("general.duration"),
                        title = "Duration",
                        value = duration.asBinding(),
                        range = 1..60,
                        default = 30,
                        validator = Validators.intRange(1..60),
                    )
                }
            }
        }

        val result = screen.validateAll()

        assertFalse(result.isValid)
        assertEquals(2, result.errors.size)
        assertEquals(
            setOf(id("general.name"), id("general.duration")),
            result.errors.map { it.field }.toSet(),
        )
    }

    @Test
    fun `warnings do not make a screen invalid`() {
        val duration = mutableStateOf(200)
        val setting = IntSliderSetting(
            id("f.duration"),
            SettingMetadata(dev.th7bo.sidequest.ui.state.constantState("Duration")),
            duration.asBinding(),
            5,
            0..1000,
            validator = Validators.intRange(1..60, severity = Severity.WARNING),
        )

        val result = setting.validation.value
        assertTrue(result.isValid, "a warning must not block")
        assertTrue(result.hasWarnings)
    }

    @Test
    fun `modifiedSettings and resetAll operate across categories`() {
        val a = mutableStateOf(false)
        val b = mutableStateOf(0)

        val screen = configScreen(id("main"), "Sidequest") {
            category(id("one"), "One") {
                section("S") { toggle(id("one.a"), "A", a.asBinding(), default = false) }
            }
            category(id("two"), "Two") {
                section("S") { slider(id("two.b"), "B", b.asBinding(), 0..10, default = 0) }
            }
        }

        assertTrue(screen.modifiedSettings().isEmpty())

        a.value = true
        b.value = 7
        assertEquals(2, screen.modifiedSettings().size)

        assertEquals(2, screen.resetAll())
        assertTrue(screen.modifiedSettings().isEmpty())
    }

    @Test
    fun `metadata block sets restart, experimental and keywords`() {
        val flag = mutableStateOf(false)
        val screen = configScreen(id("main"), "Sidequest") {
            category(id("general"), "General") {
                section("Advanced") {
                    toggle(id("general.gpu"), "Use GPU", flag.asBinding()) {
                        requiresRestart = true
                        experimental = true
                        tooltip("Requires a compatible driver")
                        keywords("graphics", "render", "performance")
                    }
                }
            }
        }

        val setting = screen[id("general.gpu")]!!
        assertTrue(setting.metadata.requiresRestart)
        assertTrue(setting.metadata.isExperimental)
        assertEquals("Requires a compatible driver", setting.metadata.tooltip?.peek())
        assertTrue(setting.searchTerms().containsAll(listOf("graphics", "render", "performance")))
    }

    @Test
    fun `typed lookup returns null for a mismatched type rather than casting blindly`() {
        val flag = mutableStateOf(false)
        val screen = configScreen(id("main"), "Sidequest") {
            category(id("general"), "General") {
                section("S") { toggle(id("general.flag"), "Flag", flag.asBinding()) }
            }
        }

        assertSame(screen[id("general.flag")], screen.typed<Boolean>(id("general.flag")))
        assertNull(screen[id("general.missing")])
    }
}
