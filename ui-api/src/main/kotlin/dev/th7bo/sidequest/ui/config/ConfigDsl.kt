@file:JvmName("ConfigDsl")

package dev.th7bo.sidequest.ui.config

import dev.th7bo.sidequest.ui.binding.Binding
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.validation.Validator

/** Marks the configuration builders so their receivers cannot be nested by accident. */
@DslMarker
public annotation class ConfigDslMarker

/**
 * Builds a configuration screen.
 *
 * ```
 * val screen = configScreen(UiId.of("sidequest", "main"), "Sidequest") {
 *     category(UiId.of("sidequest", "general"), "General", icon = Icons.settings) {
 *         section("Interface") {
 *             toggle(
 *                 id = UiId.of("sidequest", "general.notifications"),
 *                 title = "Show In-Game Notifications",
 *                 value = config.notificationsBinding,
 *             )
 *             slider(
 *                 id = UiId.of("sidequest", "notifications.duration"),
 *                 title = "Notification Duration",
 *                 value = config.durationBinding,
 *                 range = 1..60,
 *                 visibleWhen = config.notificationsState,
 *             )
 *         }
 *     }
 * }
 * ```
 *
 * Duplicate ids anywhere in the screen fail at build time with both locations named.
 */
public fun configScreen(
    id: UiId,
    title: String,
    description: String? = null,
    configure: ConfigScreenBuilder.() -> Unit,
): ConfigScreen = configScreen(id, constantState(title), description?.let(::constantState), configure)

public fun configScreen(
    id: UiId,
    title: UiState<String>,
    description: UiState<String>? = null,
    configure: ConfigScreenBuilder.() -> Unit,
): ConfigScreen = ConfigScreenBuilder(id, title, description).apply(configure).toScreen()

@ConfigDslMarker
public class ConfigScreenBuilder internal constructor(
    private val id: UiId,
    private val title: UiState<String>,
    private val description: UiState<String>? = null,
) {

    private val categories = ArrayList<Category>()

    /** Tracks every id on the screen so duplicates are caught with useful context. */
    private val seenIds = LinkedHashMap<UiId, String>()

    public fun category(
        id: UiId,
        title: String,
        description: String? = null,
        icon: Icon? = null,
        visibleWhen: UiState<Boolean> = constantState(true),
        configure: CategoryBuilder.() -> Unit,
    ): Unit = category(id, constantState(title), description?.let(::constantState), icon, visibleWhen, configure)

    public fun category(
        id: UiId,
        title: UiState<String>,
        description: UiState<String>? = null,
        icon: Icon? = null,
        visibleWhen: UiState<Boolean> = constantState(true),
        configure: CategoryBuilder.() -> Unit,
    ) {
        claim("category", id, title.peek())
        val builder = CategoryBuilder(this.id, id, title, description, icon, visibleWhen, ::claim)
        builder.configure()
        categories.add(builder.toCategory())
    }

    internal fun claim(kind: String, id: UiId, path: String) {
        val existing = seenIds[id]
        if (existing != null) throw DuplicateConfigIdException(kind, id, existing, path)
        seenIds[id] = path
    }

    internal fun toScreen(): ConfigScreen = ConfigScreen(id, title, categories.toList(), description)
}

@ConfigDslMarker
public class CategoryBuilder internal constructor(
    private val screenId: UiId,
    private val id: UiId,
    private val title: UiState<String>,
    private val description: UiState<String>?,
    private val icon: Icon?,
    private val visibleWhen: UiState<Boolean>,
    private val claim: (String, UiId, String) -> Unit,
) {

    private val sections = ArrayList<Section>()

    /**
     * A titled group.
     *
     * The section id is derived from the category id and [title] unless one is given
     * explicitly. Derived ids sit under a `section.` segment so that a section titled
     * "Notifications" cannot collide with a setting whose path is `notifications` —
     * every id on a screen shares one namespace, and that clash is otherwise very easy
     * to hit by accident.
     */
    public fun section(
        title: String,
        id: UiId = this.id.child("section").child(slug(title)),
        description: String? = null,
        icon: Icon? = null,
        collapsible: Boolean = false,
        startsCollapsed: Boolean = false,
        visibleWhen: UiState<Boolean> = constantState(true),
        /**
         * A description that changes while the screen is open.
         *
         * Wins over [description] when both are given, and exists because a plain string is *frozen at
         * build time* — which is fine for "Applies to every waypoint" and quietly wrong for anything
         * describing live state. A friend list that said "Online" when the screen opened and kept saying
         * it afterwards is the case that prompted this: a screen confidently wrong about who is playing is
         * worse than one that says nothing.
         *
         * Only pass a state that actually notifies. A derivation over a value nothing writes back to
         * recomputes on read and never invalidates the text node, which is the same staleness with more
         * machinery in the way.
         */
        descriptionState: UiState<String>? = null,
        configure: SectionBuilder.() -> Unit,
    ) {
        val categoryTitle = this.title.peek()
        claim("section", id, "$categoryTitle > $title")

        val builder = SectionBuilder(
            screenId = screenId,
            categoryId = this.id,
            sectionId = id,
            path = "$categoryTitle > $title",
            claim = claim,
        )
        builder.configure()
        sections.add(
            Section(
                id = id,
                title = constantState(title),
                description = descriptionState ?: description?.let(::constantState),
                icon = icon,
                isCollapsible = collapsible,
                startsCollapsed = startsCollapsed,
                visibleWhen = visibleWhen,
                settings = builder.settings(),
            ),
        )
    }

    internal fun toCategory(): Category =
        Category(id, title, description, icon, visibleWhen, sections.toList())

    private fun slug(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifEmpty { "section" }
}

/**
 * Adds settings to a section.
 *
 * Every builder here follows the same shape: an id, a title, the binding, then whatever
 * is specific to the control, then the shared optional metadata. A normal setting is one
 * call; the low-level [Setting] classes are never needed for standard controls.
 */
@ConfigDslMarker
public class SectionBuilder internal constructor(
    private val screenId: UiId,
    private val categoryId: UiId,
    private val sectionId: UiId,
    private val path: String,
    private val claim: (String, UiId, String) -> Unit,
) {

    private val entries = ArrayList<Setting<*>>()

    internal fun settings(): List<Setting<*>> = entries.toList()

    /** Adds a setting built elsewhere — the extension point for custom setting types. */
    public fun <T> add(setting: Setting<T>): Setting<T> {
        claim("setting", setting.id, path)
        setting.location = SettingLocation(screenId, categoryId, sectionId, path)
        entries.add(setting)
        return setting
    }

    // -- controls -----------------------------------------------------------

    public fun toggle(
        id: UiId,
        title: String,
        value: Binding<Boolean>,
        description: String? = null,
        default: Boolean = value.state.peek(),
        block: MetadataBuilder.() -> Unit = {},
    ): ToggleSetting = add(
        ToggleSetting(id, metadata(title, description, block), value, default),
    ) as ToggleSetting

    public fun button(
        id: UiId,
        title: String,
        label: String = title,
        description: String? = null,
        destructive: Boolean = false,
        block: MetadataBuilder.() -> Unit = {},
        onInvoke: () -> Unit,
    ): ButtonSetting {
        var counter = 0
        val binding = dev.th7bo.sidequest.ui.binding.bind(
            get = { counter },
            set = { counter = it },
            debugName = id.value,
        )
        return add(
            ButtonSetting(
                id = id,
                metadata = metadata(title, description, block),
                binding = binding,
                label = constantState(label),
                isDestructive = destructive,
                onInvoke = onInvoke,
            ),
        ) as ButtonSetting
    }

    public fun slider(
        id: UiId,
        title: String,
        value: Binding<Int>,
        range: IntRange,
        step: Int = 1,
        description: String? = null,
        default: Int = value.state.peek(),
        format: (Int) -> String = { it.toString() },
        validator: Validator<Int>? = null,
        block: MetadataBuilder.() -> Unit = {},
    ): IntSliderSetting = add(
        IntSliderSetting(
            id, metadata(title, description, block), value, default, range, step, format, validator,
        ),
    ) as IntSliderSetting

    public fun decimalSlider(
        id: UiId,
        title: String,
        value: Binding<Float>,
        range: ClosedFloatingPointRange<Float>,
        step: Float = 0f,
        description: String? = null,
        default: Float = value.state.peek(),
        format: (Float) -> String = { String.format("%.2f", it) },
        validator: Validator<Float>? = null,
        block: MetadataBuilder.() -> Unit = {},
    ): FloatSliderSetting = add(
        FloatSliderSetting(
            id, metadata(title, description, block), value, default, range, step, format, validator,
        ),
    ) as FloatSliderSetting

    public fun textField(
        id: UiId,
        title: String,
        value: Binding<String>,
        description: String? = null,
        default: String = value.state.peek(),
        placeholder: String = "",
        maxLength: Int = TextSetting.DEFAULT_MAX_LENGTH,
        masked: Boolean = false,
        validator: Validator<String>? = null,
        block: MetadataBuilder.() -> Unit = {},
    ): TextSetting = add(
        TextSetting(
            id, metadata(title, description, block), value, default,
            constantState(placeholder), maxLength, masked, validator,
        ),
    ) as TextSetting

    public fun textArea(
        id: UiId,
        title: String,
        value: Binding<String>,
        description: String? = null,
        default: String = value.state.peek(),
        placeholder: String = "",
        visibleLines: Int = TextAreaSetting.DEFAULT_VISIBLE_LINES,
        validator: Validator<String>? = null,
        block: MetadataBuilder.() -> Unit = {},
    ): TextAreaSetting = add(
        TextAreaSetting(
            id, metadata(title, description, block), value, default,
            constantState(placeholder), visibleLines, TextAreaSetting.DEFAULT_MAX_LENGTH, validator,
        ),
    ) as TextAreaSetting

    public fun <T> dropdown(
        id: UiId,
        title: String,
        value: Binding<T>,
        options: List<Option<T>>,
        description: String? = null,
        default: T = value.state.peek(),
        searchable: Boolean = options.size > SEARCHABLE_THRESHOLD,
        block: MetadataBuilder.() -> Unit = {},
    ): DropdownSetting<T> = dropdown(
        id, title, value, constantState(options), description, default, searchable, block,
    )

    public fun <T> dropdown(
        id: UiId,
        title: String,
        value: Binding<T>,
        options: UiState<List<Option<T>>>,
        description: String? = null,
        default: T = value.state.peek(),
        searchable: Boolean = false,
        block: MetadataBuilder.() -> Unit = {},
    ): DropdownSetting<T> {
        @Suppress("UNCHECKED_CAST")
        return add(
            DropdownSetting(id, metadata(title, description, block), value, default, options, searchable),
        ) as DropdownSetting<T>
    }

    /**
     * An editable list of values.
     *
     * [ListSetting] and its renderer have both existed since phase 2; this is the builder that was never
     * written, so the control could only be reached by constructing the setting by hand. The first thing that
     * actually needed one was the rare-drop ignore list — a set that grows from gameplay and has to be
     * editable somewhere other than a chat command.
     *
     * @param createItem supplies a new entry when the user presses add. Null hides the add button, which is
     *   right for a list that is only ever added to from elsewhere and pruned here.
     */
    public fun <T> list(
        id: UiId,
        title: String,
        value: Binding<List<T>>,
        elementSerializer: SettingSerializer<T>,
        itemLabel: (T) -> String,
        description: String? = null,
        default: List<T> = value.state.peek(),
        itemKey: (T) -> Any = { it as Any },
        createItem: (() -> T)? = null,
        isReorderable: Boolean = true,
        maxItems: Int = Int.MAX_VALUE,
        validator: Validator<List<T>>? = null,
        block: MetadataBuilder.() -> Unit = {},
    ): ListSetting<T> {
        @Suppress("UNCHECKED_CAST")
        return add(
            ListSetting(
                id = id,
                metadata = metadata(title, description, block),
                binding = value,
                defaultValue = default,
                elementSerializer = elementSerializer,
                itemLabel = itemLabel,
                itemKey = itemKey,
                createItem = createItem,
                isReorderable = isReorderable,
                maxItems = maxItems,
                validator = validator,
            ),
        ) as ListSetting<T>
    }

    /**
     * Zero or more values chosen from a fixed set, through a searchable list.
     *
     * The control for "which of these forty do you want". A [list] is the wrong shape for that: its rows are
     * not editable and `createItem` can only produce one fixed value, so choosing three islands means adding
     * the same island three times with no way to change any of them.
     */
    public fun <T> multiSelect(
        id: UiId,
        title: String,
        value: Binding<List<T>>,
        options: List<Option<T>>,
        elementSerializer: SettingSerializer<T>,
        description: String? = null,
        default: List<T> = value.state.peek(),
        searchable: Boolean = options.size > SEARCHABLE_THRESHOLD,
        summarise: ((List<T>) -> String)? = null,
        validator: Validator<List<T>>? = null,
        block: MetadataBuilder.() -> Unit = {},
    ): MultiSelectSetting<T> {
        val setting = MultiSelectSetting(
            id = id,
            metadata = metadata(title, description, block),
            binding = value,
            defaultValue = default,
            options = constantState(options),
            elementSerializer = elementSerializer,
            isSearchable = searchable,
            summarise = summarise ?: { chosen ->
                when (chosen.size) {
                    0 -> "None"
                    // Naming the single choice rather than counting it: "Garden" says more than "1 selected",
                    // and one is the common case for a list somebody is curating by hand.
                    1 -> options.firstOrNull { it.value == chosen.first() }?.label?.peek() ?: "1 selected"
                    else -> "${chosen.size} selected"
                }
            },
            validator = validator,
        )
        @Suppress("UNCHECKED_CAST")
        return add(setting) as MultiSelectSetting<T>
    }

    public fun colorPicker(
        id: UiId,
        title: String,
        value: Binding<Color>,
        description: String? = null,
        default: Color = value.state.peek(),
        allowAlpha: Boolean = true,
        presets: List<Color> = emptyList(),
        block: MetadataBuilder.() -> Unit = {},
    ): ColorSetting = add(
        ColorSetting(id, metadata(title, description, block), value, default, allowAlpha, presets),
    ) as ColorSetting

    public fun keybind(
        id: UiId,
        title: String,
        value: Binding<Keybind>,
        description: String? = null,
        default: Keybind = value.state.peek(),
        allowModifierOnly: Boolean = false,
        block: MetadataBuilder.() -> Unit = {},
    ): KeybindSetting = add(
        KeybindSetting(id, metadata(title, description, block), value, default, allowModifierOnly),
    ) as KeybindSetting

    public fun <T> list(
        id: UiId,
        title: String,
        value: Binding<List<T>>,
        elementSerializer: SettingSerializer<T>,
        itemLabel: (T) -> String,
        description: String? = null,
        default: List<T> = value.state.peek(),
        itemKey: (T) -> Any = { it as Any },
        createItem: (() -> T)? = null,
        reorderable: Boolean = true,
        maxItems: Int = Int.MAX_VALUE,
        block: MetadataBuilder.() -> Unit = {},
    ): ListSetting<T> {
        @Suppress("UNCHECKED_CAST")
        return add(
            ListSetting(
                id, metadata(title, description, block), value, default, elementSerializer,
                itemLabel, itemKey, createItem, reorderable, maxItems,
            ),
        ) as ListSetting<T>
    }

    // -- static content -----------------------------------------------------

    public fun description(
        id: UiId,
        body: String,
        block: MetadataBuilder.() -> Unit = {},
    ): NoticeSetting = notice(id, NoticeSetting.Kind.DESCRIPTION, "", body, block)

    public fun warning(
        id: UiId,
        title: String,
        body: String = "",
        block: MetadataBuilder.() -> Unit = {},
    ): NoticeSetting = notice(id, NoticeSetting.Kind.WARNING, title, body, block)

    public fun error(
        id: UiId,
        title: String,
        body: String = "",
        block: MetadataBuilder.() -> Unit = {},
    ): NoticeSetting = notice(id, NoticeSetting.Kind.ERROR, title, body, block)

    public fun divider(id: UiId): NoticeSetting =
        notice(id, NoticeSetting.Kind.DIVIDER, "", "") { }

    private fun notice(
        id: UiId,
        kind: NoticeSetting.Kind,
        title: String,
        body: String,
        block: MetadataBuilder.() -> Unit,
    ): NoticeSetting = add(
        NoticeSetting(id, metadata(title, null, block), kind, constantState(body)),
    ) as NoticeSetting

    private fun metadata(
        title: String,
        description: String?,
        block: MetadataBuilder.() -> Unit,
    ): SettingMetadata = MetadataBuilder(constantState(title), description?.let(::constantState))
        .apply(block)
        .toMetadata()

    private companion object {
        /** Past this many options a dropdown gets a filter box by default. */
        const val SEARCHABLE_THRESHOLD = 12
    }
}

/**
 * Optional per-setting metadata.
 *
 * Everything here has a sensible default, so the common case never mentions it:
 *
 * ```
 * toggle(id, "Enabled", binding) {
 *     visibleWhen = advancedMode
 *     requiresRestart = true
 *     keywords("chat", "message")
 * }
 * ```
 */
@ConfigDslMarker
public class MetadataBuilder internal constructor(
    private val title: UiState<String>,
    private var description: UiState<String>?,
) {

    public var tooltip: UiState<String>? = null
    public var icon: Icon? = null
    public var visibleWhen: UiState<Boolean> = constantState(true)
    public var enabledWhen: UiState<Boolean> = constantState(true)
    public var warning: UiState<String?> = constantState(null)
    public var requiresRestart: Boolean = false
    public var experimental: Boolean = false
    public var confirmation: Confirmation? = null
    public var permission: Permission? = null
    public var resetBehaviour: ResetBehaviour = ResetBehaviour.TO_DEFAULT

    private val extraKeywords = ArrayList<String>()

    /** Additional terms this setting should be findable by. */
    public fun keywords(vararg terms: String) {
        extraKeywords.addAll(terms)
    }

    public fun tooltip(text: String) {
        tooltip = constantState(text)
    }

    public fun warning(text: String) {
        warning = constantState(text)
    }

    public fun description(text: String) {
        description = constantState(text)
    }

    internal fun toMetadata(): SettingMetadata = SettingMetadata(
        title = title,
        description = description,
        tooltip = tooltip,
        icon = icon,
        keywords = extraKeywords.toList(),
        visibleWhen = visibleWhen,
        enabledWhen = enabledWhen,
        warning = warning,
        requiresRestart = requiresRestart,
        isExperimental = experimental,
        confirmation = confirmation,
        permission = permission,
        resetBehaviour = resetBehaviour,
    )
}

/** Shorthand for building an option list from an enum-like set of values. */
public fun <T> options(vararg entries: Pair<String, T>): List<Option<T>> =
    entries.map { (id, value) -> Option(id, constantState(id.replace('_', ' ')), value) }

/** Builds one option. */
public fun <T> option(
    id: String,
    label: String,
    value: T,
    description: String? = null,
    icon: Icon? = null,
): Option<T> = Option(id, constantState(label), value, description?.let(::constantState), icon)
