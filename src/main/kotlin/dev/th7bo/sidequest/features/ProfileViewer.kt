package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.SidequestSettings
import dev.th7bo.sidequest.platform.core.notification.notification
import dev.th7bo.sidequest.platform.core.profile.SkyCryptUrls
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.player.PlayerAction
import dev.th7bo.sidequest.platform.player.PlayerActionEntry

/**
 * Somebody's SkyBlock stats, without leaving the game.
 *
 * The feature owns *who* is being looked at and nothing about how a page is drawn — opening the screen is a
 * callback the mod supplies, for the usual reason that a feature cannot reach Minecraft. What lives here is
 * the part worth testing by reading: which name gets looked up, and what happens when it is not a name.
 *
 * **A username is validated before it becomes a URL**, in [SkyCryptUrls], which is also where the embedded
 * browser's allowlist lives. A profile viewer that will fetch whatever it is handed is a browser, and this
 * one is deliberately not that.
 */
class ProfileViewer(
    /** Who the player is, so the bare command means "me". */
    private val localName: () -> String?,
    /** Opens the viewer. Supplied by the mod, which is the only thing that may touch a screen. */
    private val open: (username: String, profile: String?) -> Unit,
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("profile.viewer"),
        displayName = "Profile viewer",
        category = FeatureCategory.SOCIAL,
        description = "Opens a player's SkyCrypt stats in a window inside the game",
    )

    private lateinit var context: FeatureContext

    override fun onEnable(context: FeatureContext) {
        this.context = context

        context.command(
            name = "sqprofile",
            description = "Opens a player's SkyBlock stats",
            usage = "[player] [profile]",
            // Friends first, because they are who somebody looks up. The directory knows everybody the
            // client has seen, which on a busy hub is several hundred names and useless as a completion.
            completions = { arguments ->
                if (arguments.size <= 1) {
                    val typed = arguments.firstOrNull().orEmpty().lowercase()
                    context.players.all()
                        .filter { it.isCustomFriend }
                        .map { it.username }
                        .filter { it.lowercase().startsWith(typed) }
                        .sorted()
                } else {
                    emptyList()
                }
            },
        ) { arguments -> onCommand(arguments) }

        context.playerActions.register(context.owner) { target ->
            listOf(
                PlayerActionEntry(
                    PlayerAction(
                        id = SqId.sidequest("action.profile.view"),
                        label = "View SkyBlock stats",
                        description = "Opens their SkyCrypt page",
                        order = ORDER,
                    ),
                ) {
                    show(target.player.username)
                },
            )
        }
    }

    private fun onCommand(arguments: List<String>) {
        // No argument is "me". The commonest use by a distance, and it is the one that should not need
        // typing your own name.
        val name = arguments.firstOrNull() ?: localName() ?: run {
            say("Join a world first, or name somebody: /sqprofile <player>")
            return
        }
        show(name, arguments.getOrNull(1))
    }

    /**
     * Opens the viewer for [name].
     *
     * The refusal is the interesting half. `SkyCryptUrls` will not build an address from something that is
     * not a Minecraft username, and rather than let that surface as a blank page, it is reported here in the
     * terms the player can act on.
     */
    fun show(name: String, profile: String? = null) {
        if (!SidequestSettings.Profiles.isEnabled) {
            say("The profile viewer is off. Turn it on under Network · Profile viewer.")
            return
        }
        if (!SkyCryptUrls.isValidUsername(name)) {
            say("\"$name\" is not a Minecraft username.")
            return
        }
        if (profile != null && !SkyCryptUrls.isValidProfile(profile)) {
            say("\"$profile\" is not a profile name.")
            return
        }
        open(name, profile)
    }

    private fun say(message: String) {
        context.notifications.notify(
            notification(
                category = NotificationCategory.DEBUG,
                title = "Profile viewer",
                subtitle = message,
            ),
        )
    }

    private companion object {
        /** Below the friend actions: looking somebody up is a lookup, not a decision about them. */
        const val ORDER = 40
    }
}
