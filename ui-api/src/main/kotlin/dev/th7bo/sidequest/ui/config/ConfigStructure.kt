package dev.th7bo.sidequest.ui.config

import dev.th7bo.sidequest.ui.binding.RefreshableBinding
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.validation.ValidationResult

/** Thrown when two settings, sections or categories claim the same identifier. */
public class DuplicateConfigIdException(
    public val kind: String,
    public val id: UiId,
    public val existingPath: String,
    public val attemptedPath: String,
) : IllegalStateException(
    "Duplicate $kind id '$id': already defined at '$existingPath', " +
        "redefined at '$attemptedPath'",
)

/** A titled group of settings within a category. */
public class Section(
    public val id: UiId,
    public val title: UiState<String>,
    public val description: UiState<String>? = null,
    /** Shown in the section's card header. */
    public val icon: Icon? = null,
    /** Whether the section can be folded away, and whether it starts folded. */
    public val isCollapsible: Boolean = false,
    public val startsCollapsed: Boolean = false,
    public val visibleWhen: UiState<Boolean> = constantState(true),
    public val settings: List<Setting<*>>,
) {
    override fun toString(): String = "Section($id, ${settings.size} settings)"
}

/** A top-level grouping, shown in the sidebar. */
public class Category(
    public val id: UiId,
    public val title: UiState<String>,
    public val description: UiState<String>? = null,
    public val icon: Icon? = null,
    public val visibleWhen: UiState<Boolean> = constantState(true),
    public val sections: List<Section>,
) {

    /** Every setting in the category, in declaration order. */
    public val settings: List<Setting<*>> = sections.flatMap { it.settings }

    override fun toString(): String = "Category($id, ${sections.size} sections)"
}

/**
 * A complete configuration screen.
 *
 * Built once by the DSL, then handed to the runtime. Structure is immutable; the values
 * behind it are not.
 */
public class ConfigScreen(
    public val id: UiId,
    public val title: UiState<String>,
    public val categories: List<Category>,
    /** Shown under the title. Null leaves the header showing the title alone. */
    public val description: UiState<String>? = null,
) {

    /** Every setting on the screen, in declaration order. */
    public val settings: List<Setting<*>> = categories.flatMap { it.settings }

    private val byId: Map<UiId, Setting<*>> = settings.associateBy { it.id }

    public val settingCount: Int get() = settings.size

    public operator fun get(settingId: UiId): Setting<*>? = byId[settingId]

    /** Finds a setting and checks its value type in one step. */
    @Suppress("UNCHECKED_CAST")
    public fun <T> typed(settingId: UiId): Setting<T>? = byId[settingId] as? Setting<T>

    public fun category(categoryId: UiId): Category? = categories.firstOrNull { it.id == categoryId }

    /**
     * Re-reads every binding that can be re-read.
     *
     * A screen is built once and kept, and a property binding caches — it only consults its getter when
     * asked. So anything that changes a setting *from outside the screen* leaves the screen showing what the
     * value used to be, indefinitely.
     *
     * That is not hypothetical: the Ignore button on a rare drop's notification appends to the ignored-items
     * list, and the settings screen went on showing the list as it was when the game started.
     *
     * Called when a screen is opened. Cheap — one getter call per setting, none of which touch disk.
     */
    public fun refreshBindings() {
        for (setting in settings) (setting.binding as? RefreshableBinding<*>)?.refresh()
    }

    /**
     * Validates every setting's current value.
     *
     * Run before saving: a screen with errors must not be written to disk.
     */
    public fun validateAll(): ValidationResult =
        settings.fold(ValidationResult.valid()) { accumulated, setting ->
            accumulated + setting.validation.peek()
        }

    /** Settings whose value differs from their default. */
    public fun modifiedSettings(): List<Setting<*>> = settings.filter { it.isModified.peek() }

    /** Resets every resettable setting on the screen. */
    public fun resetAll(): Int = settings.count { it.reset() }

    override fun toString(): String = "ConfigScreen($id, $settingCount settings)"
}

/**
 * Settings excluded from persistence.
 *
 * Buttons and notices carry no durable value; writing them would put meaningless keys in
 * every config file.
 */
public fun Setting<*>.isPersistent(): Boolean = when (this) {
    is ButtonSetting -> false
    is NoticeSetting -> false
    else -> true
}
