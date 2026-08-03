package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.ping.PingStyle
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * What the player is looking at, however far away it is.
 *
 * **Not `Minecraft.hitResult`**, which was the first version of this and was wrong. That field is the
 * *interaction* target: the game raycasts it only as far as the player can reach, a few blocks, because its
 * job is deciding what a right-click would touch. Anything further is a `MISS` — so a ping aimed across a
 * courtyard silently fell back to the sender's own feet, which is exactly the bug this replaces.
 *
 * A ping is not an interaction. It reaches as far as somebody can see something worth pointing at, which is
 * why this casts its own ray out to [RANGE] and checks entities along the way.
 */
public object CrosshairTarget {

    /** What the crosshair is on, with what it is. */
    public data class Target(
        public val position: SqPosition,
        /** The entity hit, or null for a block or the sky. Decides the ping's default meaning. */
        public val entity: Entity?,
        /** True when the ray hit nothing at all and the position is a point in the air. */
        public val isEmptyAir: Boolean,
    )

    /**
     * Casts from the player's eyes, or null outside a world.
     *
     * Returns a target even when nothing was hit — a point [RANGE] blocks along the look vector. Pointing at
     * open sky is a real thing to do in a game with a lot of it, and refusing would make the feature feel
     * broken exactly when somebody is pointing at a dragon.
     */
    public fun current(): Target? {
        val client = Minecraft.getInstance()
        val player = client.player ?: return null
        val level = client.level ?: return null

        val eyes = player.getEyePosition(1f)
        val reach = eyes.add(player.getViewVector(1f).scale(RANGE))

        // Blocks first, so the ray stops at a wall rather than picking an entity behind one.
        val block = level.clip(
            ClipContext(eyes, reach, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player),
        )
        val blockedAt = if (block.type == HitResult.Type.MISS) reach else block.location

        // Entities only up to wherever the ray stopped, and only inside that box — searching the whole
        // hundred blocks and then discarding the ones behind the wall would be the same answer for more work.
        val searchBox: AABB = AABB(eyes, blockedAt).inflate(ENTITY_MARGIN)
        val entityHit = ProjectileUtil.getEntityHitResult(
            level,
            player,
            eyes,
            blockedAt,
            searchBox,
            // Anything solid enough to be worth pointing at. The player themselves is excluded by
            // `getEntityHitResult` already; spectators and their own arrows are not worth a ping.
            { candidate -> !candidate.isSpectator && candidate.isPickable },
            ENTITY_PRECISION,
        )

        return when {
            entityHit != null -> Target(entityHit.location.toSq(), entityHit.entity, isEmptyAir = false)
            block.type != HitResult.Type.MISS -> Target(block.location.toSq(), entity = null, isEmptyAir = false)
            else -> Target(blockedAt.toSq(), entity = null, isEmptyAir = true)
        }
    }

    /**
     * The ping this target most likely means.
     *
     * A guess with a low cost of being wrong: somebody who wanted a different one holds the key and picks.
     * Pointing at a player is almost always "go to them" or "look at them", pointing at any other living
     * thing is usually a warning, and pointing at nothing in particular is a place to go.
     */
    public fun suggest(target: Target): PingStyle = when {
        target.entity is Player -> PingStyle.LOOK_HERE
        target.entity != null -> PingStyle.DANGER
        else -> PingStyle.GO_HERE
    }

    private fun Vec3.toSq(): SqPosition = SqPosition(x, y, z)

    /**
     * How far a ping can reach, in blocks.
     *
     * A hundred. Further than anything is legible at, and short enough that the block raycast stays cheap —
     * it walks the voxels between here and there, so the cost is linear in this number.
     */
    private const val RANGE = 100.0

    /** Slack on the entity search box, so something at the very edge of the ray is still found. */
    private const val ENTITY_MARGIN = 1.0

    /** How close the ray must pass to an entity's box. Matches what the game uses for projectiles. */
    private const val ENTITY_PRECISION = 0.3f
}
