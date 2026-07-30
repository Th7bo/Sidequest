package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.core.player.DefaultPlayerDirectory
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.player.PlayerTargeting
import dev.th7bo.sidequest.platform.player.TargetedPlayer
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Finds the player somebody means.
 *
 * The crosshair pick is a real raycast against each player's bounding box rather than the
 * angle-to-look-vector approximation most mods use. The approximation picks the wrong player in a
 * crowd — two people in a line and it prefers whichever is closer to the centre of the screen
 * regardless of who is in front — and a friend-group mod whose "point at somebody" feature targets
 * the person behind them is worse than one without the feature.
 *
 * Every reading is taken at the moment it is asked for, and nothing here hands out an entity. A
 * feature holding an entity holds a reference into the world that goes stale when the player leaves
 * render distance, and the crash from that reproduces once a week and never in a test.
 */
class MinecraftPlayerTargeting(
    private val directory: DefaultPlayerDirectory,
    private val client: Minecraft = Minecraft.getInstance(),
) : PlayerTargeting {

    private var remembered: TargetedPlayer? = null

    override val crosshairTarget: TargetedPlayer?
        get() {
            val target = pickUnderCrosshair()
            // Remembered only when there is one: looking away has to keep the last target, which is
            // the whole reason `lastTargeted` exists.
            if (target != null) remembered = target
            return target
        }

    override val lastTargeted: TargetedPlayer?
        get() {
            // Re-read rather than returned as recorded. A remembered target's distance and line of
            // sight are stale the instant the player moves, and a GUI showing 3m for somebody who
            // walked away is worse than showing nothing.
            val previous = remembered ?: return null
            return findById(previous.id)?.also { remembered = it }
        }

    /**
     * The player whose bounding box the look vector enters first.
     *
     * Boxes are inflated slightly, because a raycast against an exact hitbox demands more precision
     * than a player aiming at somebody actually has, and the failure is the feature seeming not to
     * work.
     */
    private fun pickUnderCrosshair(): TargetedPlayer? {
        val self = client.player ?: return null
        val eyes = self.eyePosition
        val reach = eyes.add(self.getViewVector(1.0f).scale(PlayerTargeting.DEFAULT_RANGE))

        var closest: AbstractClientPlayer? = null
        var closestDistance = Double.MAX_VALUE

        for (other in otherPlayers()) {
            val box = other.boundingBox.inflate(AIM_TOLERANCE)
            val hit = box.clip(eyes, reach).orElse(null) ?: continue
            val distance = eyes.distanceToSqr(hit)
            if (distance < closestDistance) {
                closest = other
                closestDistance = distance
            }
        }

        val target = closest ?: return null
        // Required, not reported: somebody behind a wall is not under the crosshair however well the
        // angles line up.
        if (!hasLineOfSight(self, target)) return null
        return target.toTarget(eyes)
    }

    override fun nearby(maxDistance: Double): List<TargetedPlayer> {
        val self = client.player ?: return emptyList()
        val eyes = self.eyePosition
        val limit = maxDistance * maxDistance
        return otherPlayers()
            .filter { eyes.distanceToSqr(it.position()) <= limit }
            .map { it.toTarget(eyes) }
            .sortedBy { it.distance }
    }

    override fun hasLineOfSight(id: PlayerId): Boolean {
        val self = client.player ?: return false
        val other = findEntity(id) ?: return false
        return hasLineOfSight(self, other)
    }

    override fun resolveVisible(username: String): TargetedPlayer? {
        val self = client.player ?: return null
        val eyes = self.eyePosition
        return otherPlayers()
            .firstOrNull { it.gameProfile.name.equals(username, ignoreCase = true) }
            ?.toTarget(eyes)
    }

    /**
     * Everybody except us.
     *
     * The local player is excluded once, here, rather than at four call sites — a targeting service
     * that can return the player who asked is a source of features that address themselves.
     */
    private fun otherPlayers(): List<AbstractClientPlayer> {
        val level = client.level ?: return emptyList()
        val self = client.player ?: return emptyList()
        return level.players().filter { it.uuid != self.uuid }
    }

    /**
     * Whether the world is between two players.
     *
     * `COLLIDER` and not `OUTLINE`: the question is whether they are behind a wall, and glass panes
     * and fences are things you can see through even though they collide. Fluids are ignored for the
     * same reason — a player standing in water is still pointable-at.
     */
    private fun hasLineOfSight(from: Entity, to: Entity): Boolean {
        if (from.level() !== to.level()) return false
        val start = from.eyePosition
        val end = to.eyePosition
        val hit = from.level().clip(
            ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, from),
        )
        return hit.type == HitResult.Type.MISS
    }

    private fun findEntity(id: PlayerId): AbstractClientPlayer? =
        otherPlayers().firstOrNull { it.uuid.toString() == id.value }

    private fun findById(id: PlayerId): TargetedPlayer? {
        val self = client.player ?: return null
        return findEntity(id)?.toTarget(self.eyePosition)
    }

    /**
     * A reading of one player.
     *
     * The directory is told about them on the way past. Every player the client can see is a player
     * worth remembering the name of, and this is the cheapest place in the mod to learn it.
     */
    private fun AbstractClientPlayer.toTarget(eyes: Vec3): TargetedPlayer {
        val id = PlayerId(uuid.toString())
        directory.remember(id, gameProfile.name)
        return TargetedPlayer(
            id = id,
            username = gameProfile.name,
            position = SqPosition(x, y, z),
            distance = eyes.distanceTo(position()),
            hasLineOfSight = client.player?.let { hasLineOfSight(it, this) } == true,
        )
    }

    private companion object {
        /**
         * How much slack to give a hitbox, in blocks.
         *
         * Enough that aiming at somebody works, small enough that two players standing together are
         * still told apart.
         */
        const val AIM_TOLERANCE = 0.3
    }
}
