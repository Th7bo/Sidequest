package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.parser.ScoreboardSnapshot
import dev.th7bo.sidequest.platform.parser.TabListSnapshot
import dev.th7bo.sidequest.platform.player.PlayerId
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

/**
 * Reads the sidebar scoreboard.
 *
 * The awkward part is that a scoreboard line is not one string. Minecraft stores the
 * entry name and the team prefix/suffix separately, and Hypixel puts most of the visible
 * text in the team parts — reading only the entry gives back the invisible padding
 * characters and nothing else. [PlayerTeam.formatNameForTeam] is what assembles the line
 * the player actually sees.
 *
 * `listPlayerScores` returns highest score first, which is the order the sidebar draws
 * top to bottom — so no reversal. That is worth stating because it has not always been
 * true and reads like it could go either way; the in-game test pins it, and caught this
 * being wrong the first time.
 */
object MinecraftScoreboardReader {

    fun read(): ScoreboardSnapshot {
        val client = Minecraft.getInstance()
        val level = client.level ?: return ScoreboardSnapshot.Empty
        val scoreboard = level.scoreboard
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return ScoreboardSnapshot.Empty

        val lines = scoreboard.listPlayerScores(objective)
            .asSequence()
            .filterNot { it.isHidden }
            .map { entry ->
                val team = scoreboard.getPlayersTeam(entry.owner())
                PlayerTeam.formatNameForTeam(team, entry.ownerName()).string
            }
            .toList()

        return ScoreboardSnapshot(
            rawTitle = objective.displayName.string,
            rawLines = lines,
        )
    }
}

/**
 * Reads the tab list.
 *
 * Only the *listed* players, which is what the game draws. Hypixel's widgets — `Area:`,
 * `Profile:`, `Server:` — are entries with a display name and no real player behind them,
 * which is why the display name is what gets read rather than the profile name.
 *
 * Sorted by tab-list order, so the entries arrive in the order they are drawn. The
 * underlying collection has no meaningful order of its own, and a widget that appears
 * before another on screen should appear before it here.
 */
object MinecraftTabListReader {

    fun read(): TabListSnapshot {
        val connection = Minecraft.getInstance().connection ?: return TabListSnapshot.Empty

        val entries = connection.listedOnlinePlayers
            .sortedWith(compareBy({ it.tabListOrder }, { it.profile.name }))
            .map { info -> (info.tabListDisplayName ?: return@map info.profile.name).string }

        return TabListSnapshot(rawEntries = entries)
    }
}

/**
 * Who is on this server, with their identities.
 *
 * Distinct from the tab list *snapshot*, which is text: Hypixel fills the tab list with widgets and
 * decorated display names, and none of that carries a UUID. The player list does, and a UUID is the
 * only thing worth keying anything on.
 *
 * This is where the directory learns almost everything it knows. Being in a lobby with somebody is
 * the client's one reliable source of "this name belongs to this account".
 */
object MinecraftPlayerListReader {

    /** Every player the client has been told about, as id and name. */
    fun read(): List<Pair<PlayerId, String>> {
        val connection = Minecraft.getInstance().connection ?: return emptyList()
        return connection.onlinePlayers.map { info ->
            PlayerId(info.profile.id.toString()) to info.profile.name
        }
    }
}
