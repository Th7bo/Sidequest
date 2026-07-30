package dev.th7bo.sidequest.platform.skyblock

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A point in the world.
 *
 * Doubles, because a player is not on a block boundary and a waypoint placed at a player's feet
 * has to come back to the same place.
 */
@Serializable
public data class SqPosition(
    public val x: Double,
    public val y: Double,
    public val z: Double,
) {

    public fun distanceTo(other: SqPosition): Double = sqrt(squaredDistanceTo(other))

    /** Squared distance. For comparisons and filters, where the square root is wasted work. */
    public fun squaredDistanceTo(other: SqPosition): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }

    /**
     * Distance ignoring height.
     *
     * The more useful measure for most of SkyBlock: "is that player near me" on an island with
     * a hundred blocks of vertical build is not a question about `y`.
     */
    public fun horizontalDistanceTo(other: SqPosition): Double {
        val dx = x - other.x
        val dz = z - other.z
        return sqrt(dx * dx + dz * dz)
    }

    public fun toBlock(): SqBlockPosition =
        SqBlockPosition(floorToInt(x), floorToInt(y), floorToInt(z))

    /** Rounded to one decimal, for anything a player reads. */
    public fun format(): String = "%.1f, %.1f, %.1f".format(x, y, z)

    override fun toString(): String = format()

    public companion object {
        public val Origin: SqPosition = SqPosition(0.0, 0.0, 0.0)

        /** Floor, not truncate: `-0.5` is in block `-1`, and truncation puts it in `0`. */
        private fun floorToInt(value: Double): Int {
            val truncated = value.toInt()
            return if (value < 0 && value != truncated.toDouble()) truncated - 1 else truncated
        }
    }
}

/** A block, for anything that snaps to the grid. */
@Serializable
public data class SqBlockPosition(
    public val x: Int,
    public val y: Int,
    public val z: Int,
) {
    public fun center(): SqPosition = SqPosition(x + 0.5, y + 0.5, z + 0.5)

    /** Chebyshev distance, which is what "within N blocks" means for a cuboid check. */
    public fun chebyshevDistanceTo(other: SqBlockPosition): Int =
        maxOf(abs(x - other.x), abs(y - other.y), abs(z - other.z))

    override fun toString(): String = "$x, $y, $z"
}

/**
 * A position *and* the island it is on.
 *
 * The island is not decoration. Every SkyBlock island is its own world with its own coordinate
 * space, so `(0, 70, 0)` is a different place on the Hub than in the Rift, and a waypoint shared
 * without its island is a waypoint that sends someone somewhere arbitrary. Anything that crosses
 * the wire, or gets stored, or gets shown to another player, carries one of these rather than a
 * bare [SqPosition].
 *
 * The profile is included for the same reason one step further out: a private island's
 * coordinates mean nothing on another profile's private island.
 */
@Serializable
public data class SqLocation(
    public val island: Island,
    public val position: SqPosition,
    /**
     * The profile the position was taken on, where it matters.
     *
     * Only meaningful for islands that are per-profile — a private island, a garden. Null
     * elsewhere, because the Hub is the Hub on every profile.
     */
    public val profile: SkyBlockProfile? = null,
) {

    /**
     * Whether [other] describes a place in the same coordinate space as this.
     *
     * The check anything positional has to make before comparing or navigating. Two positions on
     * different islands are not near each other however close the numbers look.
     */
    public fun isSameSpaceAs(other: SqLocation): Boolean = when {
        island != other.island -> false
        // A per-profile island is only the same space on the same profile. Unknown profiles are
        // treated as possibly-the-same rather than definitely-not: refusing to navigate is worse
        // than navigating to somewhere the player can see is wrong.
        !island.isPerProfile -> true
        profile == null || other.profile == null -> true
        else -> profile == other.profile
    }

    /** Distance to [other], or null when the two are not in the same coordinate space. */
    public fun distanceTo(other: SqLocation): Double? =
        if (isSameSpaceAs(other)) position.distanceTo(other.position) else null

    override fun toString(): String = "${island.displayName} ($position)"
}

/**
 * A dungeon room, by the id Hypixel puts on the scoreboard.
 *
 * The only stable identifier for a room there is client-side. Dungeon layouts are generated, so
 * a position means nothing between runs, and this does — the same room id is the same room.
 */
@Serializable
@JvmInline
public value class DungeonRoom(public val id: String) {
    public val isKnown: Boolean get() = id.isNotEmpty()

    override fun toString(): String = id

    public companion object {
        public val Unknown: DungeonRoom = DungeonRoom("")
    }
}

/**
 * Somewhere worth going, named.
 *
 * The shape shared by waypoints, pings and NPC locations, so those three do not each invent it.
 * Carries an [SqLocation] rather than a position for the reason described there.
 */
@Serializable
public data class Destination(
    public val location: SqLocation,
    public val label: String = "",
    /** Free text: what is there, or why it was marked. */
    public val note: String? = null,
) {
    override fun toString(): String = label.ifEmpty { location.toString() }
}
