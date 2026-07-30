package dev.th7bo.sidequest.platform.player

import dev.th7bo.sidequest.platform.skyblock.SqPosition

/**
 * A player the client can currently see, and where they are relative to us.
 *
 * Deliberately not a Minecraft entity. A feature that gets handed an entity holds a reference into
 * the world that goes stale the moment the player walks out of render distance, and the crash that
 * follows reproduces once a week. This is a reading, taken at a moment.
 */
public data class TargetedPlayer(
    public val id: PlayerId,
    /** The name as the client knows it. Resolve through [PlayerDirectory] for anything durable. */
    public val username: String,
    public val position: SqPosition,
    /** Blocks from the local player's eyes. */
    public val distance: Double,
    /**
     * Whether nothing solid is between us.
     *
     * Not the same as being visible: a player behind a wall is in the list with this false, which
     * is what a feature wanting "who can I point at" needs and what a feature wanting "who is
     * nearby" should ignore.
     */
    public val hasLineOfSight: Boolean,
) {
    override fun toString(): String = "$username at ${"%.1f".format(distance)}m"
}

/**
 * Finding the player somebody means.
 *
 * Every social feature needs this and the naive version is wrong in the same way each time: a
 * feature that asks "who is under my crosshair" by taking the nearest entity in front of the camera
 * picks the wrong player in a crowd, and one that resolves a typed name without a fallback fails
 * silently on a rename.
 *
 * So the shape here is a *chain*: crosshair, then the last player targeted, then a name. The plan
 * calls the pieces "raycast player under crosshair", "last-targeted player" and "command-based
 * fallback", and this is those three behind one interface.
 */
public interface PlayerTargeting {

    /**
     * The player under the crosshair, if any.
     *
     * Line of sight is required — a player behind a wall is not under the crosshair however well
     * the angles line up.
     */
    public val crosshairTarget: TargetedPlayer?

    /**
     * The last player [crosshairTarget] returned, for as long as they are still visible.
     *
     * The reason this exists: a player has to look away from somebody to open a GUI about them.
     * Without a remembered target every action menu would be empty by the time it opened.
     */
    public val lastTargeted: TargetedPlayer?

    /** Players within [maxDistance] blocks, nearest first. */
    public fun nearby(maxDistance: Double = DEFAULT_RANGE): List<TargetedPlayer>

    /** Whether nothing solid is between the local player and [id]. False when they are not loaded. */
    public fun hasLineOfSight(id: PlayerId): Boolean

    /**
     * A visible player by name, for a typed command.
     *
     * The fallback in the chain, and only ever a convenience: it can only find players the client
     * can see. Case-insensitive, because nobody types a name the way it is capitalised.
     */
    public fun resolveVisible(username: String): TargetedPlayer?

    /**
     * The best guess at who is meant, given an optional explicit name.
     *
     * The form a command uses: `/sqfriend` with no argument means whoever is under the crosshair,
     * and with one means that player. One place decides the precedence so eight commands do not
     * each decide it differently.
     */
    public fun resolveTarget(username: String? = null): TargetedPlayer? = when {
        username != null -> resolveVisible(username)
        else -> crosshairTarget ?: lastTargeted
    }

    public companion object {
        /**
         * How far to look, in blocks.
         *
         * Beyond this the client does not reliably have the entity anyway, so a longer range buys
         * inconsistency rather than reach.
         */
        public const val DEFAULT_RANGE: Double = 64.0

        /** A targeting service that never finds anybody. For tests and for a headless platform. */
        public val None: PlayerTargeting = object : PlayerTargeting {
            override val crosshairTarget: TargetedPlayer? = null
            override val lastTargeted: TargetedPlayer? = null
            override fun nearby(maxDistance: Double): List<TargetedPlayer> = emptyList()
            override fun hasLineOfSight(id: PlayerId): Boolean = false
            override fun resolveVisible(username: String): TargetedPlayer? = null
        }
    }
}
