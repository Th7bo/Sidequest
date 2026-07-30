package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.platform.audio.SoundSink
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.notification.NotificationPriority
import dev.th7bo.sidequest.platform.skyblock.SqPosition
import dev.th7bo.sidequest.platform.text.ClickAction
import dev.th7bo.sidequest.platform.text.ClickActionType
import dev.th7bo.sidequest.platform.text.SqStyle
import dev.th7bo.sidequest.platform.text.SqText
import dev.th7bo.sidequest.ui.core.notification.NotificationQueue
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.mutableStateOf
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import kotlin.time.Duration.Companion.milliseconds
import dev.th7bo.sidequest.platform.notification.Notification as SqNotification
import dev.th7bo.sidequest.platform.notification.NotificationSink as SqNotificationSink
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.notification.NotificationSeverity
import dev.th7bo.sidequest.ui.notification.Notification as UiNotification

/**
 * Hands a platform notification to the UI framework's toast queue.
 *
 * The two `Notification` types are deliberately separate, which is why this class exists at all. The
 * platform's is a *decision-ready value*: serialisable, no callbacks in its persisted form, carrying a
 * category and a delivery mode. The UI framework's is a *live widget model* with observable state and a
 * duration. Merging them would mean either the platform depending on the UI framework, or the UI framework
 * knowing what a SkyBlock island is.
 *
 * So this is the translation, and it is the only place the two vocabularies meet.
 */
class MinecraftNotificationSink(
    /**
     * The logger, resolved on use rather than taken at construction.
     *
     * A supplier because the platform takes this sink as a constructor argument, so asking the platform for a
     * logger *while building it* is a cycle. It crashed the game on startup with a `StackOverflowError` before
     * this was a supplier, which is the worst kind of failure and one no headless test could have found.
     */
    private val log: () -> Logger,
    /** The queue lives on the HUD layer, which does not exist until the first frame in a world. */
    private val queue: () -> NotificationQueue?,
) : SqNotificationSink {

    /** The inbox, until a screen exists to show it. Bounded, newest last. */
    private val stored = ArrayDeque<SqNotification>()

    val inboxSize: Int get() = stored.size

    fun inbox(): List<SqNotification> = stored.toList()

    override fun toast(notification: SqNotification) {
        // Logged as well as shown. A notification is the mod's most visible output, and "it did not appear"
        // is only answerable if there is a record of whether it was ever offered.
        log().debug { "toast: [${notification.category}] ${notification.title}" }

        val target = queue()
        if (target == null) {
            // No HUD yet — the main menu, or the first frame of a world. Chat rather than dropped: the
            // player still needs telling, and this is the one path where a toast has nowhere to go.
            log().debug { "No HUD layer yet; sending '${notification.title}' to chat instead" }
            Sidequest.tellPlayer(notification.title, notification.subtitle)
            return
        }

        target.post(
            UiNotification(
                id = uiIdFor(notification.id),
                title = mutableStateOf(notification.title),
                message = notification.subtitle?.let { mutableStateOf(it) },
                severity = notification.priority.toSeverity(),
                icon = notification.iconId?.let { Icon(UiId.parse(it.value)) },
                duration = notification.effectiveDurationMillis().milliseconds,
                // The platform has already decided what coalesces, and it distinguishes grouping from
                // deduplication. Handing the grouping key over lets the UI count repeats without the two
                // layers disagreeing about which notifications are the same.
                coalesceKey = notification.groupingKey,
                /*
                 * Deliberately not interactive, however tempting it looks.
                 *
                 * `NotificationRegionNode` does handle a pointer press — but nothing delivers input to the
                 * *live* HUD, and during gameplay the cursor is grabbed, so there is no pointer to click with.
                 * A click would swing the player's sword. Setting this would mark the toast interactive and
                 * make it hover and highlight as though it could be pressed, which is worse than plain: it
                 * would look broken rather than look like a message.
                 *
                 * Actions are offered in chat instead, where a click genuinely works. See `offerActions`.
                 */
                onActivate = null,
            ),
        )

        if (notification.actions.isNotEmpty()) offerActions(notification)
    }

    /**
     * Puts a notification's actions somewhere they can actually be pressed.
     *
     * Chat, with a click event per action running a hidden client command. It is the only surface in the game
     * that is clickable while the cursor is grabbed, it persists so a player who was looking elsewhere can
     * still act, and the whole path already exists — `SqText` carries a click action and the command bridge
     * registers real Fabric client commands, so the click is intercepted client-side rather than sent to
     * Hypixel.
     */
    private fun offerActions(notification: SqNotification) {
        val parts = mutableListOf(
            SqText.of(notification.title + " ", SqStyle(color = ACCENT, bold = true)),
        )
        for (action in notification.actions) {
            parts.add(
                SqText.of("[" + action.label + "] ", SqStyle(color = ACTION, underlined = true))
                    .copy(
                        clickAction = ClickAction(
                            ClickActionType.RUN_COMMAND,
                            "/" + ACTION_COMMAND + " " + notification.id + " " + action.id,
                        ),
                    ),
            )
        }
        Sidequest.platform.client.sendClientMessage(SqText.join(*parts.toTypedArray()))
    }

    override fun inbox(notification: SqNotification) {
        stored.addLast(notification)
        while (stored.size > INBOX_LIMIT) stored.removeFirst()
    }

    override fun dismiss(notificationId: String) {
        queue()?.dismiss(uiIdFor(notificationId))
    }

    /**
     * Turns a platform notification id into a valid [UiId].
     *
     * The two id types have different rules and neither is wrong. A platform notification id is arbitrary — a
     * UUID by default — because it only has to be unique. A `UiId` path is `[a-z0-9_]` separated by dots,
     * because it is also a texture path and a configuration key.
     *
     * So the mapping has to be total, and it has to be *stable*: [dismiss] builds the same id from the same
     * input and has to arrive at the same value, or a toast could never be taken down. Anything outside the
     * allowed set becomes an underscore, which for a UUID means the hyphens and nothing else.
     *
     * This was a crash, not a theoretical concern: the default ids are UUIDs and `UiId` rejected every one of
     * them.
     */
    private fun uiIdFor(notificationId: String): UiId =
        UiId.of("sidequest", "notification." + notificationId.lowercase().map { character ->
            if (character in 'a'..'z' || character in '0'..'9' || character == '_') character else '_'
        }.joinToString(""))

    /**
     * Maps a priority onto the UI's severity.
     *
     * Not the same idea — priority is about interrupting, severity is about colour — but they correlate
     * closely enough that a second field on every notification would be ceremony.
     */
    private fun NotificationPriority.toSeverity(): NotificationSeverity = when (this) {
        NotificationPriority.LOW -> NotificationSeverity.INFO
        NotificationPriority.NORMAL -> NotificationSeverity.INFO
        NotificationPriority.HIGH -> NotificationSeverity.WARNING
        NotificationPriority.URGENT -> NotificationSeverity.ERROR
    }

    private companion object {
        const val INBOX_LIMIT = 200
        const val ACCENT = 0xA78BFA
        const val ACTION = 0x60A5FA
    }
}

/**
 * The command a notification's chat action runs.
 *
 * Hidden, because it is not for typing — it exists so a chat component has something to click. Named here
 * rather than in two places so the sink and the platform cannot disagree about it.
 */
const val ACTION_COMMAND: String = "sqaction"


/**
 * Plays sounds through Minecraft.
 *
 * As thin as the HTTP transport, and for the same reason: cooldowns, volume groups, pools, mute controls and
 * serious mode are all in `DefaultSoundManager` where a test can drive them with no audio device. What is
 * here is the resolution of an id and one call into the game.
 *
 * Returns false rather than throwing for a sound that does not resolve, which is what turns a missing remote
 * asset into a fallback instead of an exception in the middle of a feature.
 */
class MinecraftSoundSink(
    /** Resolved on use, for the same reason as the notification sink's. See there. */
    private val log: () -> Logger,
    private val client: Minecraft = Minecraft.getInstance(),
) : SoundSink {

    override fun play(resource: String, volume: Float, pitch: Float, position: SqPosition?): Boolean {
        val id = Identifier.tryParse(resource) ?: run {
            log().debug { "Not a valid sound id: '$resource'" }
            return false
        }
        // Registered sounds only. A resource pack's sound that is not in the registry cannot be played this
        // way, and reporting that honestly is what lets the fallback happen.
        val event = BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null) ?: run {
            log().debug { "No sound registered as '$resource'" }
            return false
        }

        val manager = client.soundManager
        val instance = if (position != null) {
            SimpleSoundInstance(
                event,
                SoundSource.MASTER,
                volume,
                pitch,
                net.minecraft.util.RandomSource.create(),
                position.x,
                position.y,
                position.z,
            )
        } else {
            // The non-positional constructor, so the sound is heard rather than located. A notification chime
            // that came from a direction would be worse than one that came from nowhere.
            SimpleSoundInstance.forUI(event, volume, pitch)
        }

        return runCatching { manager.play(instance); true }
            .onFailure { log().debug(it) { "Could not play '$resource'" } }
            .getOrDefault(false)
    }

    override fun stop(resource: String) {
        val id = Identifier.tryParse(resource) ?: return
        runCatching { client.soundManager.stop(id, SoundSource.MASTER) }
    }

    /** Kept so the sink can be asked whether an id would resolve, for the diagnostics command. */
    fun resolves(resource: String): Boolean {
        val id = Identifier.tryParse(resource) ?: return false
        return BuiltInRegistries.SOUND_EVENT.getOptional(id).isPresent
    }

    }
