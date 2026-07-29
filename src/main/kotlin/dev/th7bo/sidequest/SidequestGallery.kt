package dev.th7bo.sidequest

import dev.th7bo.sidequest.ui.binding.asBinding
import dev.th7bo.sidequest.ui.components.Icons
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.Keybind
import dev.th7bo.sidequest.ui.config.SettingSerializers
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.config.option
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.mutableStateOf

/**
 * Every standard control, on one screen.
 *
 * The plan's required "component gallery" demonstration, and more usefully a place where
 * a control that renders badly is obvious at a glance. Built entirely through the public
 * configuration DSL — if something here needed a node class, that would be a gap in the
 * DSL rather than a reason to reach past it.
 *
 * The values are throwaway state rather than real settings, so opening the gallery cannot
 * change anything the player cares about.
 */
public object SidequestGallery {

    private fun id(path: String) = UiId.of(Sidequest.MOD_ID, "gallery.$path")

    private val toggleValue = mutableStateOf(true, "gallery.toggle")
    private val disabledToggle = mutableStateOf(false, "gallery.disabledToggle")
    private val intValue = mutableStateOf(42, "gallery.int")
    private val floatValue = mutableStateOf(0.65f, "gallery.float")
    private val textValue = mutableStateOf("Steve", "gallery.text")
    private val secretValue = mutableStateOf("hunter2", "gallery.secret")
    private val notesValue = mutableStateOf("Multiple lines\nof text\nlive here", "gallery.notes")
    private val choiceValue = mutableStateOf("balanced", "gallery.choice")
    private val woodValue = mutableStateOf("acacia", "gallery.wood")
    private val accentValue = mutableStateOf(Color.parse("#FF8B5CF6"), "gallery.accent")
    private val keybindValue = mutableStateOf(Keybind(Key.G), "gallery.keybind")
    private val entriesValue = mutableStateOf(listOf("first", "second", "third"), "gallery.entries")

    private var addCounter = 0

    /** Rebuilt lazily so the gallery costs nothing until it is opened. */
    public val screen: ConfigScreen by lazy { build() }

    private fun build(): ConfigScreen = configScreen(id("screen"), "Component Gallery", "Every standard control, on one screen.") {
        category(id("inputs"), "Inputs", icon = Icons.sliders) {
            section("Booleans") {
                toggle(id("toggle"), "Toggle", toggleValue.asBinding(), "The standard on/off control")
                toggle(
                    id("disabled"),
                    "Disabled toggle",
                    disabledToggle.asBinding(),
                    "Greyed out, and refuses input rather than silently ignoring it",
                ) {
                    enabledWhen = constantState(false)
                }
            }

            section("Numbers") {
                slider(
                    id("int_slider"),
                    "Integer slider",
                    intValue.asBinding(),
                    0..100,
                    description = "Whole numbers, with a live readout",
                )
                decimalSlider(
                    id("float_slider"),
                    "Decimal slider",
                    floatValue.asBinding(),
                    0f..1f,
                    step = 0.05f,
                    description = "Fractional values, snapped to a step",
                )
            }

            section("Text") {
                textField(
                    id("text"),
                    "Single-line field",
                    textValue.asBinding(),
                    description = "Shows a placeholder when empty",
                    placeholder = "Your name",
                )
                textField(
                    id("secret"),
                    "Masked field",
                    secretValue.asBinding(),
                    description = "Characters are hidden as you type",
                    masked = true,
                )
                textArea(
                    id("notes"),
                    "Multiline area",
                    notesValue.asBinding(),
                    description = "Enter inserts a newline rather than committing",
                    visibleLines = 3,
                )
            }
        }

        category(id("choices"), "Choices", icon = Icons.palette) {
            section("Pickers") {
                dropdown(
                    id("choice"),
                    "Dropdown",
                    choiceValue.asBinding(),
                    listOf(
                        option("performance", "Performance", "performance"),
                        option("balanced", "Balanced", "balanced"),
                        option("quality", "Quality", "quality"),
                    ),
                    description = "A short list, shown in full",
                )
                dropdown(
                    id("wood"),
                    "Searchable dropdown",
                    woodValue.asBinding(),
                    WOODS.map { option(it.lowercase(), it, it.lowercase()) },
                    description = "Type to filter once a list gets long",
                    searchable = true,
                )
                colorPicker(
                    id("accent"),
                    "Colour picker",
                    accentValue.asBinding(),
                    description = "Opens a swatch grid in the overlay layer",
                )
                keybind(
                    id("keybind"),
                    "Keybind",
                    keybindValue.asBinding(),
                    description = "Click, then press a key to rebind",
                )
            }

            section("Collections") {
                list(
                    id("entries"),
                    "Editable list",
                    entriesValue.asBinding(),
                    SettingSerializers.string,
                    itemLabel = { it },
                    description = "Add, remove and reorder entries",
                    createItem = { "entry ${++addCounter}" },
                )
            }
        }

        category(id("feedback"), "Feedback", icon = Icons.bell) {
            section("Static content") {
                description(id("desc"), "A description block carries prose that is not a setting.")
                divider(id("divider"))
                warning(id("warn"), "Warning", "Something worth pausing over")
                error(id("err"), "Error", "Something that is actually wrong")
            }

            section("Actions") {
                button(
                    id("post"),
                    "Post a notification",
                    label = "Send",
                    description = "Shows a toast in the top-right corner",
                ) {
                    SidequestWorld.notify(
                        "gallery",
                        "Hello from the gallery",
                        "Posted by the demo button",
                    )
                }
            }
        }
    }

    private val WOODS = listOf(
        "Acacia", "Birch", "Cherry", "Crimson", "Dark Oak", "Jungle",
        "Mangrove", "Oak", "Pale Oak", "Spruce", "Warped",
    )
}
