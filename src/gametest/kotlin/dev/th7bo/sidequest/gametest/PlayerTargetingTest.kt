package dev.th7bo.sidequest.gametest

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.platform.player.PlayerId
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/**
 * Player targeting, against a real world.
 *
 * There is one thing here a fake cannot check and it is the important one: that the raycast and the
 * line-of-sight test run at all on a real client without throwing. Both reach into the level, both
 * use APIs that have moved between Minecraft versions, and both are on the path of every social
 * feature the mod will have.
 *
 * A second player cannot be spawned in a client gametest, so what is asserted is the shape of the
 * answers rather than a successful pick: an empty world has nobody to target, and the correct answer
 * to "who is under my crosshair" there is *nobody* — not a crash, and not the local player.
 */
class PlayerTargetingTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        context.waitTicks(SETTLE_TICKS)

        // 1. With no world, everything answers safely rather than throwing.
        context.runOnClient<RuntimeException> {
            val targeting = Sidequest.platform.targeting
            check(targeting.crosshairTarget == null) { "Found a target with no world loaded" }
            check(targeting.nearby().isEmpty()) { "Found players with no world loaded" }
            check(!targeting.hasLineOfSight(UNKNOWN_PLAYER)) { "Claimed line of sight to nobody" }
        }

        context.worldBuilder().create().use {
            context.waitTicks(SETTLE_TICKS)

            context.runOnClient<RuntimeException> { client ->
                val targeting = Sidequest.platform.targeting

                // 2. The raycast runs against a real level. Nobody else is here, so the answer is
                //    nobody — and specifically not the local player, who is always under their own
                //    crosshair if the exclusion is missing.
                val target = targeting.crosshairTarget
                check(target == null) { "Targeted '${target?.username}' in an empty world" }

                val nearby = targeting.nearby()
                check(nearby.isEmpty()) { "Found ${nearby.size} nearby player(s) in an empty world" }

                // 3. The name fallback does not resolve the local player either.
                val self = checkNotNull(client.player).gameProfile.name
                check(targeting.resolveVisible(self) == null) { "Resolved the local player as a target" }
                check(targeting.resolveTarget(self) == null) { "Resolved the local player as a target" }
                check(targeting.resolveTarget() == null) { "Invented a target with nothing to target" }
            }

            // 4. The local player is in the directory, learned from the tab list. Which is the whole
            //    point of the directory: identities come from being in a lobby with somebody.
            context.runOnClient<RuntimeException> { client ->
                val self = checkNotNull(client.player)
                val known = Sidequest.platform.players.byId(PlayerId(self.uuid.toString()))
                check(known != null) { "The local player is not in the directory" }
                check(known.username == self.gameProfile.name) {
                    "The directory has the local player as '${known.username}'"
                }
            }

            // 5. And the activity detector ran on a real client without a Hypixel scoreboard to read.
            //    Singleplayer is not SkyBlock, so the honest answer is that nothing is known.
            context.runOnClient<RuntimeException> {
                val activity = Sidequest.platform.gameContext.context.activity
                check(!activity.isReliable) { "Claimed to know the activity in singleplayer: $activity" }
            }
        }

        context.waitTicks(SETTLE_TICKS)
    }

    private companion object {
        const val SETTLE_TICKS = 10
        val UNKNOWN_PLAYER = PlayerId("00000000-0000-4000-8000-000000000000")
    }
}
