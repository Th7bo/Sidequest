package dev.th7bo.sidequest.ui.config

import dev.th7bo.sidequest.ui.binding.Binding
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.state.DisposableScope
import dev.th7bo.sidequest.ui.state.Subscription
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.state.derivedStateOf
import dev.th7bo.sidequest.ui.validation.ValidationResult
import dev.th7bo.sidequest.ui.validation.Validator
import kotlinx.serialization.json.JsonElement

/**
 * Everything about a setting that is not its value.
 *
 * Grouped so that a concrete setting type does not need twenty constructor parameters,
 * and so that the common attributes stay identical across every control.
 *
 * Every user-visible string is a [UiState], because titles, descriptions and warnings
 * are all allowed to be dynamic. A constant is just `constantState("...")`, which the
 * DSL supplies automatically.
 */
public class SettingMetadata(
    public val title: UiState<String>,
    public val description: UiState<String>? = null,
    public val tooltip: UiState<String>? = null,
    public val icon: Icon? = null,
    /** Extra search terms beyond the title and description. */
    public val keywords: List<String> = emptyList(),
    /** When false the setting is hidden entirely, and skipped by search and focus. */
    public val visibleWhen: UiState<Boolean> = constantState(true),
    /** When false the control renders greyed out and refuses input. */
    public val enabledWhen: UiState<Boolean> = constantState(true),
    /** A caution shown next to the control. Null when there is nothing to say. */
    public val warning: UiState<String?> = constantState(null),
    /** The change only takes effect after a restart. */
    public val requiresRestart: Boolean = false,
    /** Marked as experimental in the UI. */
    public val isExperimental: Boolean = false,
    /** When set, changing the value asks first. */
    public val confirmation: Confirmation? = null,
    /** When set and not granted, the setting is shown but locked. */
    public val permission: Permission? = null,
    /** Whether and how the user may reset this setting. */
    public val resetBehaviour: ResetBehaviour = ResetBehaviour.TO_DEFAULT,
)

/** A prompt shown before a change is applied. */
public data class Confirmation(
    public val title: String,
    public val message: String,
    public val confirmLabel: String = "Confirm",
    public val cancelLabel: String = "Cancel",
    /** Styles the prompt as dangerous. Does not change behaviour. */
    public val isDestructive: Boolean = false,
)

/** Gate on whether a setting may be edited at all. */
public fun interface Permission {
    /** Evaluated on the UI thread. Must be cheap. */
    public fun isGranted(): Boolean
}

public enum class ResetBehaviour {
    /** "Reset" restores [Setting.defaultValue]. */
    TO_DEFAULT,

    /** The setting has no meaningful default and cannot be reset individually. */
    NOT_RESETTABLE,
}

/**
 * Converts a setting's value to and from its persisted form.
 *
 * Type-specific and registerable, so a third-party setting type can persist itself
 * without the persistence layer knowing anything about it.
 */
public interface SettingSerializer<T> {

    public fun encode(value: T): JsonElement

    /**
     * @throws IllegalArgumentException if [element] cannot be read as a [T]. The caller
     * turns this into a recorded load error and falls back to the default — a bad value
     * on disk must never take the whole config file down with it.
     */
    public fun decode(element: JsonElement): T
}

/**
 * The durable definition of one configurable value.
 *
 * Deliberately free of rendering state: hover, focus, open-dropdown state, cursor
 * position, drag state and animation progress all live in runtime-owned stores keyed by
 * node identity, never here. A `Setting` is safe to build once at startup and keep for
 * the lifetime of the process.
 *
 * Concrete types ([ToggleSetting], [DropdownSetting], and third-party ones) add whatever
 * extra description their control needs. Renderers are looked up by type in the
 * component registry, never by a conditional over an enum.
 */
public abstract class Setting<T>(
    public val id: UiId,
    public val metadata: SettingMetadata,
    public val binding: Binding<T>,
    public val defaultValue: T,
    public val serializer: SettingSerializer<T>,
    /** Synchronous rules applied on every write and before every save. */
    public val validator: Validator<T>? = null,
) {

    /** Set when the setting is attached to a section by the DSL. */
    public var location: SettingLocation? = null
        internal set

    /** Current value. Reads participate in the reactive graph. */
    public val value: T get() = binding.value

    /** Observable current value. */
    public val state: UiState<T> get() = binding.state

    /** True while the value differs from [defaultValue]. */
    public val isModified: UiState<Boolean> by lazy {
        derivedStateOf("${id.value}.isModified") { binding.state.value != defaultValue }
    }

    /** Composite of [SettingMetadata.enabledWhen] and the permission gate. */
    public val isEnabled: UiState<Boolean> by lazy {
        val permission = metadata.permission
        if (permission == null) {
            metadata.enabledWhen
        } else {
            derivedStateOf("${id.value}.isEnabled") { metadata.enabledWhen.value && permission.isGranted() }
        }
    }

    public val isVisible: UiState<Boolean> get() = metadata.visibleWhen

    /** Live validation of the current value. */
    public val validation: UiState<ValidationResult> by lazy {
        val rules = validator
        if (rules == null) {
            constantState(ValidationResult.valid())
        } else {
            derivedStateOf("${id.value}.validation") { rules.validate(id, binding.state.value) }
        }
    }

    /**
     * Writes a new value after validating it.
     *
     * A value with errors is **not** written: invalid input must not reach the durable
     * model, because that is how a config file ends up unloadable.
     *
     * @return the validation result. Callers that want to show the error keep it;
     *   callers that do not can ignore it, but the write genuinely did not happen.
     */
    public fun set(newValue: T): ValidationResult {
        val result = validator?.validate(id, newValue) ?: ValidationResult.valid()
        if (result.isValid) binding.set(newValue)
        return result
    }

    /** Writes without validating. For loading trusted values and for undo. */
    public fun setUnchecked(newValue: T) {
        binding.set(newValue)
    }

    /** Restores [defaultValue], if [ResetBehaviour] allows it. */
    public fun reset(): Boolean {
        if (metadata.resetBehaviour == ResetBehaviour.NOT_RESETTABLE) return false
        binding.set(defaultValue)
        return true
    }

    /** Notified on every change to the value. */
    public fun onChange(scope: DisposableScope, listener: (T) -> Unit): Subscription =
        binding.observe(scope, listener)

    /** Encodes the current value for persistence. */
    public fun encode(): JsonElement = serializer.encode(binding.state.peek())

    /**
     * Decodes and applies a persisted value.
     *
     * @return null on success, or a description of why the value was rejected. The
     * caller records it and leaves the current value alone.
     */
    public fun decodeAndApply(element: JsonElement): String? = try {
        val decoded = serializer.decode(element)
        val result = validator?.validate(id, decoded) ?: ValidationResult.valid()
        if (result.isValid) {
            binding.set(decoded)
            null
        } else {
            result.errors.joinToString("; ") { it.message }
        }
    } catch (failure: IllegalArgumentException) {
        failure.message ?: "unreadable value"
    }

    /** Terms this setting can be found by. Built once by the search index. */
    public open fun searchTerms(): List<String> = buildList {
        add(metadata.title.peek())
        metadata.description?.let { add(it.peek()) }
        addAll(metadata.keywords)
        add(id.path)
    }

    override fun toString(): String = "${this::class.simpleName}($id)"
}

/** Where a setting sits in the screen structure. Assigned when it is added. */
public data class SettingLocation(
    public val screen: UiId,
    public val category: UiId,
    public val section: UiId,
    /** Human-readable trail, e.g. `General > Interface`. Used in search results. */
    public val path: String,
)
