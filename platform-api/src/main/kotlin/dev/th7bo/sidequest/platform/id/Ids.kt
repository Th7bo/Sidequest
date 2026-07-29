package dev.th7bo.sidequest.platform.id

import kotlinx.serialization.Serializable

/**
 * A stable, namespaced identifier.
 *
 * Stable is the operative word: these end up in configuration files, in persisted
 * records and on the wire to the backend, so renaming one is a migration rather than a
 * refactor. That is why the value is validated on construction instead of being any old
 * string — a typo that only shows up as a silently-missing registration months later is
 * the failure this prevents.
 *
 * The format is `namespace:path`, both lowercase, with `.` and `_` allowed in the path
 * for hierarchy (`sidequest:drops.rare_animation`).
 */
@Serializable
@JvmInline
public value class SqId private constructor(public val value: String) {

    public val namespace: String get() = value.substringBefore(':')

    public val path: String get() = value.substringAfter(':')

    /** A child identifier, for anything scoped under this one. */
    public fun child(suffix: String): SqId = of(namespace, "$path.$suffix")

    override fun toString(): String = value

    public companion object {
        /** Namespace of everything the mod itself declares. */
        public const val SIDEQUEST: String = "sidequest"

        private val NAMESPACE = Regex("[a-z0-9_]+")
        private val PATH = Regex("[a-z0-9_.\\-/]+")

        public fun of(namespace: String, path: String): SqId {
            require(NAMESPACE.matches(namespace)) {
                "Namespace '$namespace' must match ${NAMESPACE.pattern}"
            }
            require(PATH.matches(path)) { "Path '$path' must match ${PATH.pattern}" }
            return SqId("$namespace:$path")
        }

        /** Shorthand for an identifier in the mod's own namespace. */
        public fun sidequest(path: String): SqId = of(SIDEQUEST, path)

        /** Parses `namespace:path`, defaulting the namespace to [SIDEQUEST]. */
        public fun parse(value: String): SqId {
            val separator = value.indexOf(':')
            return if (separator < 0) {
                sidequest(value)
            } else {
                of(value.substring(0, separator), value.substring(separator + 1))
            }
        }
    }
}

/**
 * Who registered something.
 *
 * Every registration the platform accepts — a listener, a job, a command — carries one
 * of these, which is what makes wholesale teardown possible: unloading a feature means
 * dropping everything tagged with its owner, rather than trusting the feature to have
 * remembered each handle it was given.
 */
@Serializable
@JvmInline
public value class OwnerId(public val value: SqId) {
    override fun toString(): String = value.value

    public companion object {
        /** The platform itself, for registrations that outlive every feature. */
        public val PLATFORM: OwnerId = OwnerId(SqId.sidequest("platform"))
    }
}
