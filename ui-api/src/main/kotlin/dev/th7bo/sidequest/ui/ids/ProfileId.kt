package dev.th7bo.sidequest.ui.ids

import kotlinx.serialization.Serializable

/**
 * Identifies a configuration profile. Profile ids are user-facing, so they are
 * validated to be filesystem-safe rather than being namespaced like a [UiId].
 */
@Serializable
@JvmInline
public value class ProfileId(public val value: String) {

    init {
        require(value.isNotEmpty()) { "Profile id must not be empty" }
        require(PATTERN.matches(value)) {
            "Profile id '$value' must match ${PATTERN.pattern}"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val PATTERN = Regex("[a-zA-Z0-9_-]{1,64}")

        /** The profile every installation starts with; it can never be deleted. */
        public val DEFAULT: ProfileId = ProfileId("default")
    }
}
