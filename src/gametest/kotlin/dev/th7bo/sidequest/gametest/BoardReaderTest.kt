package dev.th7bo.sidequest.gametest

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.platform.minecraft.MinecraftScoreboardReader
import dev.th7bo.sidequest.platform.minecraft.MinecraftTabListReader
import dev.th7bo.sidequest.platform.core.parser.ScoreboardParser
import dev.th7bo.sidequest.platform.skyblock.SubLocation
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.numbers.BlankFormat
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.ScoreHolder
import net.minecraft.world.scores.criteria.ObjectiveCriteria

/**
 * Reads a real scoreboard and a real tab list.
 *
 * The parsers are covered headlessly against recorded fixtures, which is where the rules
 * belong. What no fixture can cover is the step before them: whether the adapter pulls
 * the *right strings* out of the game. A scoreboard line is not one string — Minecraft
 * keeps the entry name and the team prefix and suffix separately, and Hypixel puts almost
 * all the visible text in the team parts. An adapter that reads only the entry gets back
 * invisible padding and nothing else, and every fixture in the suite would still pass.
 *
 * So this builds a scoreboard shaped the way Hypixel builds one, and checks it comes back
 * out the other side.
 */
class BoardReaderTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        context.waitTicks(SETTLE_TICKS)

        context.worldBuilder().create().use {
            context.waitTicks(SETTLE_TICKS)

            // 1. No sidebar: empty, not a crash and not a guess.
            onClient(context) {
                val snapshot = MinecraftScoreboardReader.read()
                check(snapshot.isEmpty) { "Expected no sidebar in a fresh world, got $snapshot" }
            }

            // 2. A scoreboard built the way Hypixel builds one, read back in display order.
            onClient(context) { client ->
                val scoreboard = checkNotNull(client.level).scoreboard
                val objective = scoreboard.addObjective(
                    "sidequest_test",
                    ObjectiveCriteria.DUMMY,
                    Component.literal("SKYBLOCK").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                    ObjectiveCriteria.RenderType.INTEGER,
                    false,
                    BlankFormat.INSTANCE,
                )
                scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, objective)

                // The text lives in the team prefix, exactly as Hypixel sends it. The
                // entry name itself is padding — which is the whole point of the test.
                addTeamLine(scoreboard, objective, entry = "§0§a", prefix = "§707/16/24 §7mini123A", score = 3)
                addTeamLine(scoreboard, objective, entry = "§0§b", prefix = " §7⏣ §bDwarven Mines", score = 2)
                addTeamLine(scoreboard, objective, entry = "§0§c", prefix = "Purse: §65,000,000", score = 1)
            }
            context.waitTicks(SETTLE_TICKS)

            onClient(context) {
                val snapshot = MinecraftScoreboardReader.read()

                check(snapshot.title == "SKYBLOCK") { "Read the title as '${snapshot.title}'" }
                check(snapshot.rawLines.size == 3) { "Read ${snapshot.rawLines.size} line(s): ${snapshot.rawLines}" }

                // Top to bottom, the way it is drawn. The game hands them over reversed.
                check(snapshot.lines.first().contains("07/16/24")) {
                    "Lines are upside down: ${snapshot.lines}"
                }

                // And the whole point: the text from the team prefix survived.
                val reading = ScoreboardParser.parse(snapshot)
                check(reading.isSkyBlock) { "The title did not read as SkyBlock: '${snapshot.title}'" }
                check(reading.subLocation == SubLocation("Dwarven Mines")) {
                    "Read the area as ${reading.subLocation} from ${snapshot.rawLines}"
                }
                check(reading.serverId?.value == "mini123A") { "Read the server as ${reading.serverId}" }
            }

            // 3. The tab list reader sees the real connection.
            onClient(context) { client ->
                val snapshot = MinecraftTabListReader.read()
                val self = checkNotNull(client.player).gameProfile.name
                check(snapshot.rawEntries.any { it.contains(self) }) {
                    "The local player is not in the tab list: ${snapshot.rawEntries}"
                }
            }

            // 4. And singleplayer is not mistaken for Hypixel. A mod that thinks a local
            //    world is SkyBlock would run every feature in the wrong place.
            onClient(context) {
                val platformContext = Sidequest.platform.gameContext.context
                check(!platformContext.isOnHypixel) { "A singleplayer world was taken for Hypixel" }
                check(!platformContext.isInSkyBlock) { "A singleplayer world was taken for SkyBlock" }
            }
        }

        context.waitTicks(SETTLE_TICKS)
    }

    /** Adds a line the way Hypixel does: padding as the entry, real text in the team prefix. */
    private fun addTeamLine(
        scoreboard: net.minecraft.world.scores.Scoreboard,
        objective: Objective,
        entry: String,
        prefix: String,
        score: Int,
    ) {
        val team = scoreboard.addPlayerTeam("sq_$score")
        team.setPlayerPrefix(Component.literal(prefix))
        scoreboard.addPlayerToTeam(entry, team)
        scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(entry), objective).set(score)
    }

    private fun onClient(
        context: ClientGameTestContext,
        action: (net.minecraft.client.Minecraft) -> Unit,
    ) {
        context.runOnClient<RuntimeException> { client -> action(client) }
    }

    private companion object {
        const val SETTLE_TICKS = 10
    }
}
