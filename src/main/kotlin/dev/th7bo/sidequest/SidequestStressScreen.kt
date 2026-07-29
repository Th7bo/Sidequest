package dev.th7bo.sidequest

import dev.th7bo.sidequest.ui.binding.asBinding
import dev.th7bo.sidequest.ui.components.Icons
import dev.th7bo.sidequest.ui.config.ConfigScreen
import dev.th7bo.sidequest.ui.config.SettingSerializers
import dev.th7bo.sidequest.ui.config.configScreen
import dev.th7bo.sidequest.ui.config.option
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.derivedStateOf
import dev.th7bo.sidequest.ui.state.mutableStateOf

/**
 * The plan's stress configuration, as a screen you can actually open.
 *
 * `StressBenchmarkTest` measures the same shape headlessly, which is where the numbers
 * come from. This exists because a benchmark that only ever runs against a fake renderer
 * proves the framework scales and says nothing about whether the *game* stays responsive
 * with it on screen — scrolling this by hand is the check no test performs.
 *
 * Built lazily. Declaring 1,700 settings costs real memory and there is no reason to pay
 * it unless someone opens the screen.
 */
public object SidequestStressScreen {

    private fun id(path: String) = UiId.of(Sidequest.MOD_ID, "stress.$path")

    public const val TOGGLES: Int = 1_000
    public const val SLIDERS: Int = 500
    public const val DROPDOWNS: Int = 200
    public const val LISTS: Int = 8
    public const val TOTAL: Int = TOGGLES + SLIDERS + DROPDOWNS + LISTS

    /** Drives the nested conditional section, so visibility rules are exercised too. */
    private val showConditional: MutableUiState<Boolean> = mutableStateOf(true, "stress.showConditional")

    private val toggleValues = HashMap<Int, MutableUiState<Boolean>>()
    private val sliderValues = HashMap<Int, MutableUiState<Int>>()
    private val dropdownValues = HashMap<Int, MutableUiState<String>>()
    private val listValues = HashMap<Int, MutableUiState<List<String>>>()

    public val screen: ConfigScreen by lazy { build() }

    private fun build(): ConfigScreen = configScreen(
        id("screen"),
        "Stress Test",
        "$TOTAL settings, for scrolling and searching by hand.",
    ) {
        category(id("toggles"), "Toggles", icon = Icons.sliders) {
            section("$TOGGLES toggles") {
                repeat(TOGGLES) { index ->
                    val state = toggleValues.getOrPut(index) {
                        mutableStateOf(index % 2 == 0, "stress.toggle.$index")
                    }
                    toggle(
                        id("toggle.$index"),
                        "Toggle $index",
                        state.asBinding(),
                        "Stress toggle number $index",
                    )
                }
            }
        }

        category(id("sliders"), "Sliders", icon = Icons.wrench) {
            section("$SLIDERS sliders") {
                repeat(SLIDERS) { index ->
                    val state = sliderValues.getOrPut(index) {
                        mutableStateOf(index % 100, "stress.slider.$index")
                    }
                    slider(
                        id("slider.$index"),
                        "Slider $index",
                        state.asBinding(),
                        0..100,
                        description = "Stress slider number $index",
                    )
                }
            }
        }

        category(id("dropdowns"), "Dropdowns", icon = Icons.palette) {
            section("$DROPDOWNS dropdowns") {
                repeat(DROPDOWNS) { index ->
                    val state = dropdownValues.getOrPut(index) {
                        mutableStateOf("balanced", "stress.dropdown.$index")
                    }
                    dropdown(
                        id("dropdown.$index"),
                        "Dropdown $index",
                        state.asBinding(),
                        listOf(
                            option("performance", "Performance", "performance"),
                            option("balanced", "Balanced", "balanced"),
                            option("quality", "Quality", "quality"),
                        ),
                        description = "Stress dropdown number $index",
                    )
                }
            }
        }

        category(id("mixed"), "Mixed", icon = Icons.eye) {
            section("Large editable lists") {
                repeat(LISTS) { index ->
                    val state = listValues.getOrPut(index) {
                        mutableStateOf(
                            (0 until LIST_LENGTH).map { "entry $it" },
                            "stress.list.$index",
                        )
                    }
                    list(
                        id("list.$index"),
                        "List $index",
                        state.asBinding(),
                        SettingSerializers.string,
                        itemLabel = { it },
                        description = "$LIST_LENGTH entries, each with its own controls",
                        createItem = { "entry ${state.peek().size}" },
                    )
                }
            }

            // Nested conditional visibility: the settings below disappear entirely rather
            // than greying out, so the list has to re-measure around them.
            section("Conditional") {
                toggle(
                    id("show_conditional"),
                    "Show the conditional settings",
                    showConditional.asBinding(),
                    "Turning this off removes the rows below from the list",
                )
                repeat(CONDITIONAL_COUNT) { index ->
                    val state = toggleValues.getOrPut(TOGGLES + index) {
                        mutableStateOf(false, "stress.conditional.$index")
                    }
                    toggle(
                        id("conditional.$index"),
                        "Conditional $index",
                        state.asBinding(),
                        "Only visible while the switch above is on",
                    ) {
                        visibleWhen = showConditional
                    }
                    // Doubly nested: visible only when the parent is on *and* the row
                    // above it is on, which is where a naive visibility model breaks.
                    toggle(
                        id("conditional.$index.child"),
                        "Conditional $index → child",
                        toggleValues.getOrPut(TOGGLES + CONDITIONAL_COUNT + index) {
                            mutableStateOf(false, "stress.conditional.child.$index")
                        }.asBinding(),
                        "Depends on two switches",
                    ) {
                        visibleWhen = derivedStateOf("stress.conditional.$index.visible") {
                            showConditional.value && state.value
                        }
                    }
                }
            }
        }
    }

    private const val LIST_LENGTH = 25
    private const val CONDITIONAL_COUNT = 12
}
