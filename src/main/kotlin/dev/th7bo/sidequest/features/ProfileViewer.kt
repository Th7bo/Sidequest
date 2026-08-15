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
            // The same list the window's arrows walk: recent lookups, then friends. The directory knows
            // everybody the client has seen, which on a busy hub is several hundred names and useless as a
            // completion.
            completions = { arguments ->
                if (arguments.size <= 1) {
                    val typed = arguments.firstOrNull().orEmpty().lowercase()
                    quickSwitch().filter { it.lowercase().startsWith(typed) }
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
    /**
     * Who the quick-switch offers, newest lookup first, then friends.
     *
     * One list rather than two. A "recents" control and a "friends" control side by side would be two
     * places to press for the same thing, and the person you looked at a minute ago and the person on your
     * friend list are both just "somebody I want to see again".
     *
     * Deduplicated case-insensitively, because Minecraft names are compared that way and the same person
     * typed two ways is not two people.
     */
    fun quickSwitch(): List<String> {
        val seen = HashSet<String>()
        return buildList {
            for (name in SidequestSettings.Profiles.recentPlayers) {
                if (seen.add(name.lowercase())) add(name)
            }
            for (friend in context.players.all().filter { it.isCustomFriend }.map { it.username }.sorted()) {
                if (seen.add(friend.lowercase())) add(friend)
            }
        }
    }

    /**
     * Files somebody under "looked at recently".
     *
     * Called by the screen as well as by the command, because the search box inside the window is a way of
     * looking somebody up that never passes through here otherwise.
     */
    fun remember(name: String) {
        if (!SkyCryptUrls.isValidUsername(name)) return
        val kept = buildList {
            add(name)
            for (existing in SidequestSettings.Profiles.recentPlayers) {
                if (!existing.equals(name, ignoreCase = true)) add(existing)
            }
        }.take(SidequestSettings.Profiles.MAX_RECENT)

        if (kept != SidequestSettings.Profiles.recentPlayers) {
            SidequestSettings.Profiles.recentPlayers = kept
            saveSettings()
        }
    }

    /**
     * Writes the settings after a change made from outside the settings screen.
     *
     * Supplied rather than reached for: this module's features do not import the mod object, and a recent
     * list that only reached disk when somebody happened to open the configuration would lose itself on
     * every restart.
     */
    var saveSettings: () -> Unit = {}

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
        remember(name)
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
