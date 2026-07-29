package dev.th7bo.sidequest.platform.game

import kotlinx.serialization.Serializable

/**
 * A Minecraft version, as a comparable value.
 *
 * Features declare the versions they support, and the registry refuses to enable one
 * outside its range. The alternative is a feature that loads everywhere and throws a
 * `NoSuchMethodError` on the version where the method it needs was renamed — a crash
 * that reads as a mod bug rather than as an unsupported combination.
 */
@Serializable
public data class GameVersion(
    public val major: Int,
    public val minor: Int,
    public val patch: Int = 0,
) : Comparable<GameVersion> {

    override fun compareTo(other: GameVersion): Int = compareValuesBy(
        this,
        other,
        GameVersion::major,
        GameVersion::minor,
        GameVersion::patch,
    )

    override fun toString(): String = if (patch == 0) "$major.$minor" else "$major.$minor.$patch"

    public companion object {
        /**
         * Parses `major.minor[.patch]`.
         *
         * Snapshot and pre-release suffixes are truncated at the first non-numeric part
         * rather than rejected: `26.2-rc1` is close enough to `26.2` for a support range,
         * and refusing to parse it would take the mod down on a version it would run on.
         */
        public fun parse(value: String): GameVersion {
            val parts = value.trim().split('.', '-', '+', ' ')
            val numbers = parts.map { it.toIntOrNull() }.takeWhile { it != null }.filterNotNull()
            require(numbers.size >= 2) { "Cannot read a Minecraft version from '$value'" }
            return GameVersion(numbers[0], numbers[1], numbers.getOrElse(2) { 0 })
        }
    }
}

/**
 * An inclusive range of supported versions, either end open.
 *
 * Open-ended upward by default: a feature that only touches stable API should keep
 * working on the next version, and having to widen a range for every release is how
 * ranges end up rubber-stamped and meaningless.
 */
@Serializable
public data class VersionRange(
    public val min: GameVersion? = null,
    public val max: GameVersion? = null,
) {
    public operator fun contains(version: GameVersion): Boolean =
        (min == null || version >= min) && (max == null || version <= max)

    override fun toString(): String = when {
        min == null && max == null -> "any"
        max == null -> ">=$min"
        min == null -> "<=$max"
        min == max -> "$min"
        else -> "$min..$max"
    }

    public companion object {
        public val Any: VersionRange = VersionRange()

        public fun atLeast(version: String): VersionRange = VersionRange(min = GameVersion.parse(version))

        public fun exactly(version: String): VersionRange =
            GameVersion.parse(version).let { VersionRange(it, it) }

        public fun between(min: String, max: String): VersionRange =
            VersionRange(GameVersion.parse(min), GameVersion.parse(max))
    }
}
