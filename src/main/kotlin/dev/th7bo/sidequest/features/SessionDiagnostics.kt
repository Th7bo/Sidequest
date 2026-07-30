package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.platform.chat.ChatParser
import dev.th7bo.sidequest.platform.event.ClientTickEvent
import dev.th7bo.sidequest.platform.event.MinecraftDisconnectEvent
import dev.th7bo.sidequest.platform.event.MinecraftJoinEvent
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.feature.listen
import dev.th7bo.sidequest.platform.game.GameClient
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.text.SqStyle
import dev.th7bo.sidequest.platform.text.SqText
import kotlin.time.Duration.Companion.seconds

/**
 * The first real feature, and the proof that the platform boundary holds.
 *
 * It counts ticks and sessions and reports them on demand. Trivial on purpose — what it
 * demonstrates is that a feature can do useful work while importing nothing from
 * Minecraft: no `Minecraft.getInstance()`, no Fabric callback, no thread handling. Every
 * one of those arrives through the context or through [GameClient].
 *
 * It is also the thing the in-game test drives, because the guarantees worth checking on
 * a real client — that a tick event actually fires, that a command actually registers —
 * are exactly the ones a fake cannot check.
 */
class SessionDiagnostics(
    private val client: GameClient,
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("dev.session_diagnostics"),
        displayName = "Session diagnostics",
        category = FeatureCategory.DEVELOPER,
        description = "Counts ticks and sessions, and reports them with /sqdiag",
    )

    /** Ticks seen since this feature was enabled. */
    var ticks: Long = 0
        private set

    /** Times a world or server has been joined this session. */
    var joins: Int = 0
        private set

    /** Seconds since the feature was enabled, from its own repeating job. */
    var uptimeSeconds: Int = 0
        private set

    var lastServer: String? = null
        private set

    /** Set on enable so the report can read the parser's counters. */
    private var chat: ChatParser? = null

    override fun onEnable(context: FeatureContext) {
        chat = context.chat
        context.listen<ClientTickEvent> { ticks = it.tick }

        context.listen<MinecraftJoinEvent> { event ->
            joins++
            lastServer = event.serverAddress
            context.log.info { "Joined ${event.serverAddress ?: "singleplayer"} (session join #$joins)" }
        }

        context.listen<MinecraftDisconnectEvent> {
            context.log.info { "Disconnected after $ticks tick(s)" }
        }

        // A repeating job rather than counting ticks: it says what it means, and it keeps
        // working if the client is paused or the tick rate changes.
        context.every(1.seconds) { uptimeSeconds++ }

        context.command("sqdiag", "Prints Sidequest session diagnostics") { report() }

        // Turning chat tracing on from in game is worth a command of its own: when a rule
        // stops firing, the question is whether Hypixel changed the message or the line
        // never arrived, and only the log distinguishes them.
        context.command("sqchat", "Toggles chat parser debug logging") { reportChat(context) }

        // The board parsers' debug output. Written to the log rather than to chat: it is
        // dozens of lines, and the raw forms are what make it useful — which is exactly what
        // chat would mangle.
        context.command("sqboard", "Dumps the scoreboard and tab list as the parsers see them") {
            reportBoards(context)
        }
    }

    /** Writes what the board parsers currently see to the log, and says where to look. */
    private fun reportBoards(context: FeatureContext) {
        val lines = context.gameContext.describeSources()
        for (line in lines) context.log.info { line }
        client.sendClientMessage(
            SqText.join(
                SqText.of("[Sidequest] ", SqStyle(color = ACCENT, bold = true)),
                SqText.of("wrote ${lines.size} line(s) of board state to the log"),
            ),
        )
    }

    /** Toggles chat tracing and reports the parser's counters. */
    private fun reportChat(context: FeatureContext) {
        val parser = context.chat
        parser.isDebugLogging = !parser.isDebugLogging
        val stats = parser.stats
        client.sendClientMessage(
            SqText.join(
                SqText.of("[Sidequest] ", SqStyle(color = ACCENT, bold = true)),
                SqText.of(
                    "chat tracing ${if (parser.isDebugLogging) "on" else "off"} — " +
                        "${parser.patterns().size} pattern(s), " +
                        "${stats.received} line(s), " +
                        "${stats.classified} classified, " +
                        "${stats.unclassified} not, " +
                        "${stats.duplicates} duplicate(s)",
                ),
            ),
        )
    }

    /** Writes the current numbers to the local chat. Client-side only. */
    fun report() {
        client.sendClientMessage(
            SqText.join(
                SqText.of("[Sidequest] ", SqStyle(color = ACCENT, bold = true)),
                SqText.of(summary()),
            ),
        )
    }

    fun summary(): String = buildString {
        append("uptime ${uptimeSeconds}s, ")
        append("$ticks tick(s), ")
        append("$joins join(s)")
        lastServer?.let { append(", last server $it") }
        chat?.let { append(", ${it.stats.received} chat line(s)") }
    }

    private companion object {
        const val ACCENT = 0xA78BFA
    }
}
