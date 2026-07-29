package dev.th7bo.sidequest.features

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

    override fun onEnable(context: FeatureContext) {
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
    }

    private companion object {
        const val ACCENT = 0xA78BFA
    }
}
