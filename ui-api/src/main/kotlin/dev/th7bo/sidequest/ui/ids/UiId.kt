package dev.th7bo.sidequest.ui.ids

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A namespaced, validated identifier: `namespace:path.segments`.
 *
 * Unqualified strings are never used as the primary identifier anywhere in the
 * framework. An invalid [UiId] cannot be constructed, so any code holding one may
 * assume it is well formed.
 *
 * ```
 * UiId.of("sidequest", "general.notifications")   // sidequest:general.notifications
 * UiId.parse("thirdparty:widget.gradient")
 * ```
 */
@Serializable(with = UiIdSerializer::class)
@JvmInline
public value class UiId private constructor(public val value: String) : Comparable<UiId> {

    /** The owning namespace, e.g. `sidequest`. */
    public val namespace: String get() = value.substring(0, value.indexOf(SEPARATOR))

    /** The dotted path within the namespace, e.g. `general.notifications`. */
    public val path: String get() = value.substring(value.indexOf(SEPARATOR) + 1)

    /** Appends a path segment: `sidequest:hud` + `mining_xp` -> `sidequest:hud.mining_xp`. */
    public fun child(segment: String): UiId = of(namespace, "$path.$segment")

    override fun compareTo(other: UiId): Int = value.compareTo(other.value)

    override fun toString(): String = value

    public companion object {
        private const val SEPARATOR = ':'

        private val NAMESPACE_PATTERN = Regex("[a-z0-9_]+")
        private val PATH_PATTERN = Regex("[a-z0-9_]+(\\.[a-z0-9_]+)*")

        /**
         * @throws InvalidUiIdException if [namespace] or [path] is malformed.
         */
        public fun of(namespace: String, path: String): UiId {
            if (!NAMESPACE_PATTERN.matches(namespace)) {
                throw InvalidUiIdException(
                    "$namespace$SEPARATOR$path",
                    "namespace '$namespace' must match ${NAMESPACE_PATTERN.pattern}",
                )
            }
            if (!PATH_PATTERN.matches(path)) {
                throw InvalidUiIdException(
                    "$namespace$SEPARATOR$path",
                    "path '$path' must match ${PATH_PATTERN.pattern}",
                )
            }
            return UiId("$namespace$SEPARATOR$path")
        }

        /**
         * @throws InvalidUiIdException if [value] is not exactly `namespace:path`.
         */
        public fun parse(value: String): UiId {
            val separator = value.indexOf(SEPARATOR)
            if (separator <= 0 || separator == value.length - 1) {
                throw InvalidUiIdException(value, "expected the form 'namespace${SEPARATOR}path'")
            }
            if (value.indexOf(SEPARATOR, separator + 1) >= 0) {
                throw InvalidUiIdException(value, "contains more than one '$SEPARATOR'")
            }
            return of(value.substring(0, separator), value.substring(separator + 1))
        }

        /** Null instead of throwing, for parsing untrusted input such as config files. */
        public fun parseOrNull(value: String): UiId? = try {
            parse(value)
        } catch (_: InvalidUiIdException) {
            null
        }
    }
}

/** Thrown when an identifier fails validation. Never caught internally. */
public class InvalidUiIdException(
    public val offendingValue: String,
    public val problem: String,
) : IllegalArgumentException("Invalid UiId '$offendingValue': $problem")

internal object UiIdSerializer : KSerializer<UiId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.th7bo.sidequest.ui.UiId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UiId) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): UiId = UiId.parse(decoder.decodeString())
}
