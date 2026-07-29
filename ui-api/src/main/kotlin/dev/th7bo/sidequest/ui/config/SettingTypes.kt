package dev.th7bo.sidequest.ui.config

import dev.th7bo.sidequest.ui.binding.Binding
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.input.Key
import dev.th7bo.sidequest.ui.input.Modifiers
import dev.th7bo.sidequest.ui.rendering.Color
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.constantState
import dev.th7bo.sidequest.ui.validation.Validator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One choice in a dropdown, radio group or segmented control.
 *
 * [serializedId] is what goes on disk. It is required rather than derived, because
 * persisting an enum's ordinal or its `name` breaks the moment the enum is reordered or
 * a constant is renamed.
 */
public class Option<T>(
    public val serializedId: String,
    public val label: UiState<String>,
    public val value: T,
    public val description: UiState<String>? = null,
    public val icon: dev.th7bo.sidequest.ui.rendering.Icon? = null,
) {
    override fun toString(): String = "Option($serializedId)"
}

// ---------------------------------------------------------------------------
// Serializers for the built-in value types
// ---------------------------------------------------------------------------

/** Serializers for the primitive shapes the built-in settings use. */
public object SettingSerializers {

    public val boolean: SettingSerializer<Boolean> = object : SettingSerializer<Boolean> {
        override fun encode(value: Boolean): JsonElement = JsonPrimitive(value)
        override fun decode(element: JsonElement): Boolean = try {
            element.jsonPrimitive.boolean
        } catch (failure: Exception) {
            throw IllegalArgumentException("expected a boolean, got $element", failure)
        }
    }

    public val int: SettingSerializer<Int> = object : SettingSerializer<Int> {
        override fun encode(value: Int): JsonElement = JsonPrimitive(value)
        override fun decode(element: JsonElement): Int = try {
            element.jsonPrimitive.int
        } catch (failure: Exception) {
            throw IllegalArgumentException("expected an integer, got $element", failure)
        }
    }

    public val float: SettingSerializer<Float> = object : SettingSerializer<Float> {
        override fun encode(value: Float): JsonElement = JsonPrimitive(value)
        override fun decode(element: JsonElement): Float = try {
            element.jsonPrimitive.float
        } catch (failure: Exception) {
            throw IllegalArgumentException("expected a number, got $element", failure)
        }
    }

    public val string: SettingSerializer<String> = object : SettingSerializer<String> {
        override fun encode(value: String): JsonElement = JsonPrimitive(value)
        override fun decode(element: JsonElement): String = try {
            element.jsonPrimitive.content
        } catch (failure: Exception) {
            throw IllegalArgumentException("expected a string, got $element", failure)
        }
    }

    public val color: SettingSerializer<Color> = object : SettingSerializer<Color> {
        override fun encode(value: Color): JsonElement = JsonPrimitive(value.toHexString())
        override fun decode(element: JsonElement): Color = try {
            Color.parse(element.jsonPrimitive.content)
        } catch (failure: Exception) {
            throw IllegalArgumentException("expected a colour like #AARRGGBB, got $element", failure)
        }
    }

    public val keybind: SettingSerializer<Keybind> = object : SettingSerializer<Keybind> {
        override fun encode(value: Keybind): JsonElement = buildJsonObject {
            put("key", JsonPrimitive(value.key.name))
            put("modifiers", JsonPrimitive(value.modifiers.bits))
        }

        override fun decode(element: JsonElement): Keybind = try {
            val obj = element.jsonObject
            val keyName = obj.getValue("key").jsonPrimitive.content
            val key = Key.entries.firstOrNull { it.name == keyName }
                ?: throw IllegalArgumentException("unknown key '$keyName'")
            Keybind(key, Modifiers(obj["modifiers"]?.jsonPrimitive?.int ?: 0))
        } catch (failure: Exception) {
            throw IllegalArgumentException("expected a keybind object, got $element", failure)
        }
    }

    /** Serializes an option-backed value by its stable [Option.serializedId]. */
    public fun <T> option(options: () -> List<Option<T>>): SettingSerializer<T> =
        object : SettingSerializer<T> {
            override fun encode(value: T): JsonElement {
                val match = options().firstOrNull { it.value == value }
                    ?: throw IllegalArgumentException("value '$value' is not among the options")
                return JsonPrimitive(match.serializedId)
            }

            override fun decode(element: JsonElement): T {
                val id = element.jsonPrimitive.content
                val match = options().firstOrNull { it.serializedId == id }
                    ?: throw IllegalArgumentException("unknown option id '$id'")
                return match.value
            }
        }

    /** Serializes a list by delegating each element to [element]. */
    public fun <T> list(elementSerializer: SettingSerializer<T>): SettingSerializer<List<T>> =
        object : SettingSerializer<List<T>> {
            override fun encode(value: List<T>): JsonElement =
                kotlinx.serialization.json.JsonArray(value.map(elementSerializer::encode))

            override fun decode(element: JsonElement): List<T> = try {
                element.jsonArray.map(elementSerializer::decode)
            } catch (failure: Exception) {
                throw IllegalArgumentException("expected a list, got $element", failure)
            }
        }
}

/** A captured key combination. */
public data class Keybind(
    public val key: Key,
    public val modifiers: Modifiers = Modifiers.None,
) {
    /** True when nothing is bound. */
    public val isUnbound: Boolean get() = key == Key.UNKNOWN

    override fun toString(): String =
        if (isUnbound) "Unbound"
        else if (modifiers.isEmpty) key.name
        else "$modifiers+${key.name}"

    public companion object {
        public val UNBOUND: Keybind = Keybind(Key.UNKNOWN)
    }
}

// ---------------------------------------------------------------------------
// Built-in setting types
// ---------------------------------------------------------------------------

/** An on/off switch. */
public class ToggleSetting(
    id: UiId,
    metadata: SettingMetadata,
    binding: Binding<Boolean>,
    defaultValue: Boolean,
) : Setting<Boolean>(id, metadata, binding, defaultValue, SettingSerializers.boolean) {

    /** Flips the value. Convenience for the control's activation handler. */
    public fun toggle() {
        setUnchecked(!value)
    }
}

/**
 * An action rather than a value.
 *
 * Modelled as a setting so that it appears in search, respects visibility and enabled
 * rules, and lives in the same structure as everything else. Its "value" is the number
 * of times it has been invoked, which makes it observable without a special case.
 */
public class ButtonSetting(
    id: UiId,
    metadata: SettingMetadata,
    binding: Binding<Int>,
    public val label: UiState<String>,
    public val isDestructive: Boolean = false,
    public val onInvoke: () -> Unit,
) : Setting<Int>(id, metadata, binding, 0, SettingSerializers.int) {

    /** Runs the action and bumps the invocation counter. */
    public fun invoke() {
        onInvoke()
        setUnchecked(value + 1)
    }

    /** A button's counter is not configuration; it is never written to disk. */
    public val isTransient: Boolean get() = true
}

/** A whole-number slider. */
public class IntSliderSetting(
    id: UiId,
    metadata: SettingMetadata,
    binding: Binding<Int>,
    defaultValue: Int,
    public val range: IntRange,
    public val step: Int = 1,
    /** Formats the value for the readout, e.g. `"$it ms"`. */
    public val format: (Int) -> String = { it.toString() },
    validator: Validator<Int>? = null,
) : Setting<Int>(id, metadata, binding, defaultValue, SettingSerializers.int, validator) {

    init {
        require(step > 0) { "Slider step must be positive, was $step" }
        require(defaultValue in range) { "Default $defaultValue is outside $range for $id" }
    }

    /** Maps a 0..1 track position onto a stepped value in range. */
    public fun valueAtFraction(fraction: Float): Int {
        val span = range.last - range.first
        val raw = range.first + fraction.coerceIn(0f, 1f) * span
        val stepped = Math.round((raw - range.first) / step) * step + range.first
        return stepped.coerceIn(range.first, range.last)
    }

    /** The 0..1 track position of [forValue]. */
    public fun fractionOf(forValue: Int): Float {
        val span = (range.last - range.first).toFloat()
        return if (span <= 0f) 0f else ((forValue - range.first) / span).coerceIn(0f, 1f)
    }
}

/** A fractional slider. */
public class FloatSliderSetting(
    id: UiId,
    metadata: SettingMetadata,
    binding: Binding<Float>,
    defaultValue: Float,
    public val range: ClosedFloatingPointRange<Float>,
    public val step: Float = 0f,
    public val format: (Float) -> String = { String.format("%.2f", it) },
    validator: Validator<Float>? = null,
) : Setting<Float>(id, metadata, binding, defaultValue, SettingSerializers.float, validator) {

    init {
        require(step >= 0f) { "Slider step must not be negative, was $step" }
        require(defaultValue in range) { "Default $defaultValue is outside $range for $id" }
    }

    public fun valueAtFraction(fraction: Float): Float {
        val span = range.endInclusive - range.start
        val raw = range.start + fraction.coerceIn(0f, 1f) * span
        if (step <= 0f) return raw.coerceIn(range.start, range.endInclusive)
        val stepped = Math.round((raw - range.start) / step) * step + range.start
        return stepped.coerceIn(range.start, range.endInclusive)
    }

    public fun fractionOf(forValue: Float): Float {
        val span = range.endInclusive - range.start
        return if (span <= 0f) 0f else ((forValue - range.start) / span).coerceIn(0f, 1f)
    }
}

/** A single-line text field. */
public class TextSetting(
    id: UiId,
    metadata: SettingMetadata,
    binding: Binding<String>,
    defaultValue: String,
    public val placeholder: UiState<String> = constantState(""),
    public val maxLength: Int = DEFAULT_MAX_LENGTH,
    /** Renders the content masked. Does not change how it is stored. */
    public val isMasked: Boolean = false,
    validator: Validator<String>? = null,
) : Setting<String>(id, metadata, binding, defaultValue, SettingSerializers.string, validator) {

    public companion object {
        public const val DEFAULT_MAX_LENGTH: Int = 256
    }
}

/** A multi-line text area. */
public class TextAreaSetting(
    id: UiId,
    metadata: SettingMetadata,
    binding: Binding<String>,
    defaultValue: String,
    public val placeholder: UiState<String> = constantState(""),
    public val visibleLines: Int = DEFAULT_VISIBLE_LINES,
    public val maxLength: Int = DEFAULT_MAX_LENGTH,
    validator: Validator<String>? = null,
) : Setting<String>(id, metadata, binding, defaultValue, SettingSerializers.string, validator) {

    public companion object {
        public const val DEFAULT_VISIBLE_LINES: Int = 4
        public const val DEFAULT_MAX_LENGTH: Int = 4096
    }
}

/**
 * A choice from a list.
 *
 * [options] is a state, so the available choices can depend on other settings — the
 * "dynamic options" requirement — without the control needing to know why they changed.
 */
public class DropdownSetting<T>(
    id: UiId,
    metadata: SettingMetadata,
    binding: Binding<T>,
    defaultValue: T,
    public val options: UiState<List<Option<T>>>,
    /** Shows a filter box. Worth enabling past roughly a dozen options. */
    public val isSearchable: Boolean = false,
    validator: Validator<T>? = null,
) : Setting<T>(
    id,
    metadata,
    binding,
    defaultValue,
    SettingSerializers.option { options.peek() },
    validator,
) {

    /** The option matching the current value, if the value is still among them. */
    public fun selectedOption(): Option<T>? = options.peek().firstOrNull { it.value == value }

    override fun searchTerms(): List<String> =
        super.searchTerms() + options.peek().map { it.label.peek() }
}

/** A colour, with optional alpha editing. */
public class ColorSetting(
    id: UiId,
    metadata: SettingMetadata,
    binding: Binding<Color>,
    defaultValue: Color,
    public val allowAlpha: Boolean = true,
    /** Offered as one-click swatches above the picker. */
    public val presets: List<Color> = emptyList(),
) : Setting<Color>(id, metadata, binding, defaultValue, SettingSerializers.color)

/** A key combination. */
public class KeybindSetting(
    id: UiId,
    metadata: SettingMetadata,
    binding: Binding<Keybind>,
    defaultValue: Keybind,
    /** Whether modifier-only combinations are acceptable. */
    public val allowModifierOnly: Boolean = false,
) : Setting<Keybind>(id, metadata, binding, defaultValue, SettingSerializers.keybind)

/**
 * An editable list of values.
 *
 * [itemLabel] gives each entry a display string, and items are identified by
 * [itemKey] rather than by index so that reordering moves rows instead of recreating
 * them.
 */
public class ListSetting<T>(
    id: UiId,
    metadata: SettingMetadata,
    binding: Binding<List<T>>,
    defaultValue: List<T>,
    elementSerializer: SettingSerializer<T>,
    public val itemLabel: (T) -> String,
    public val itemKey: (T) -> Any = { it as Any },
    /** Supplies a new entry when the user presses add. Null hides the add button. */
    public val createItem: (() -> T)? = null,
    public val isReorderable: Boolean = true,
    public val maxItems: Int = Int.MAX_VALUE,
    validator: Validator<List<T>>? = null,
) : Setting<List<T>>(
    id,
    metadata,
    binding,
    defaultValue,
    SettingSerializers.list(elementSerializer),
    validator,
) {

    public fun add(item: T): Boolean {
        val current = value
        if (current.size >= maxItems) return false
        setUnchecked(current + item)
        return true
    }

    public fun removeAt(index: Int): Boolean {
        val current = value
        if (index !in current.indices) return false
        setUnchecked(current.toMutableList().apply { removeAt(index) })
        return true
    }

    /** Moves the entry at [from] to [to], shifting the rest. */
    public fun move(from: Int, to: Int): Boolean {
        val current = value
        if (!isReorderable || from !in current.indices || to !in current.indices || from == to) return false
        setUnchecked(current.toMutableList().apply { add(to, removeAt(from)) })
        return true
    }

    public fun replaceAt(index: Int, item: T): Boolean {
        val current = value
        if (index !in current.indices) return false
        setUnchecked(current.toMutableList().apply { set(index, item) })
        return true
    }

    override fun searchTerms(): List<String> = super.searchTerms() + value.map(itemLabel)
}

/**
 * Static content in the settings flow: a description block, a warning, an error, or a
 * divider.
 *
 * Modelled as a setting so it participates in layout, virtualization and search in
 * exactly the same way as a control, rather than being a special case in the renderer.
 */
public class NoticeSetting(
    id: UiId,
    metadata: SettingMetadata,
    public val kind: Kind,
    public val body: UiState<String> = constantState(""),
) : Setting<Int>(
    id,
    metadata,
    dev.th7bo.sidequest.ui.binding.bind(get = { 0 }, set = { }, debugName = id.value),
    0,
    SettingSerializers.int,
) {

    public enum class Kind { DESCRIPTION, WARNING, ERROR, DIVIDER }

    /** Notices hold no configuration, so they are never written to disk. */
    public val isTransient: Boolean get() = true

    override fun searchTerms(): List<String> =
        if (kind == Kind.DIVIDER) emptyList() else super.searchTerms() + body.peek()
}
