package dev.th7bo.sidequest

import dev.th7bo.sidequest.platform.audio.SoundGroup
import dev.th7bo.sidequest.platform.chat.DropRarity
import dev.th7bo.sidequest.platform.cinematic.CinematicSettings
import dev.th7bo.sidequest.platform.core.presence.PresenceDisclosure
import dev.th7bo.sidequest.platform.core.presence.PresenceSettings
import dev.th7bo.sidequest.platform.core.skyblock.LevelPalette
import dev.th7bo.sidequest.platform.cosmetic.CosmeticSettings
import dev.th7bo.sidequest.platform.notification.NotificationSettings
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.ui.rendering.Color

/**
 * Every preference the mod has, as plain properties.
 *
 * **The config file is the source of truth for preferences, and this object is its in-memory shape.** That is
 * a decision rather than an accident, and it is worth stating because the alternative was already half built:
 * several services carried their own settings *and* their own persistence, so a value could be changed in two
 * places and disagree on the next launch. Now the services hold the live value and this holds what the user
 * chose; [applyToPlatform] is the one place the second becomes the first.
 *
 * Data that is not a preference keeps its own storage — a cosmetic loadout, marker store and rule progress are
 * things that *happened*, not things somebody chose, and they belong with the feature that produced them.
 *
 * Plain properties on plain objects, because the config framework binds to them rather than owning them.
 * Nothing here depends on the UI.
 */
public object SidequestSettings {

    // -- appearance ----------------------------------------------------------

    public var theme: String = "dark"
    public var accentColor: Color = Color.parse("#F07FB2")
    public var compactMode: Boolean = false
    public var hudScale: Float = 1.0f

    /**
     * Where the group's backend is.
     *
     * Defaulted to the group's own server rather than left blank, because this is a private mod for one
     * friend group and asking every member to type a URL is asking for one of them to type it wrong. Blank is
     * still supported and means "no backend": the local features are most of the mod, and somebody who clears
     * this should get no errors and no retries rather than a broken-looking client.
     */
    public var backendUrl: String = DEFAULT_BACKEND_URL

    public const val DEFAULT_BACKEND_URL: String = "https://sq.api.th7bo.dev"

    public var debugOverlay: Boolean = false

    /**
     * One switch for every "serious mode".
     *
     * Notifications, sounds and cinematics each carry their own flag, and for a while the honest layout was
     * three toggles with the same label in three categories — which is not a configuration screen, it is a
     * quiz. Somebody turning this on wants the mod to stop being playful; there is no version of that where
     * they want it for toasts and not for sounds.
     */
    public var seriousMode: Boolean = false

    // -- notifications -------------------------------------------------------

    public object Notifications {
        public var isEnabled: Boolean = true
        public var durationSeconds: Int = 5

        /** Whether a toast waits for a safe moment rather than appearing mid-fight. */
        public var queueWhileBusy: Boolean = true
    }

    // -- sound ---------------------------------------------------------------

    public object Sound {
        public var master: Float = 1f
        public var interfaceVolume: Float = 1f
        public var effects: Float = 1f
        public var soundboard: Float = 1f
    }

    // -- cinematics ----------------------------------------------------------

    public object Cinematics {
        public var isEnabled: Boolean = true
        public var letterbox: Boolean = true

        /** Never take the screen; show the compact form of everything instead. */
        public var compactOnly: Boolean = false

        public var queueWhileUnsafe: Boolean = true
        public var recap: Boolean = true
    }

    // -- rare drops ----------------------------------------------------------

    public object Drops {
        public var isEnabled: Boolean = true

        /**
         * The control that makes the feature bearable.
         *
         * Most drops Hypixel calls rare are not worth stopping for, and a mod that animates every one of them
         * gets switched off within an hour of farming.
         */
        public var minimumRarity: DropRarity = DropRarity.VERY_RARE

        public var durationSeconds: Int = 4
        public var useTotemAnimation: Boolean = false
        public var playsSound: Boolean = true
        public var takesScreenshot: Boolean = false

        /**
         * Items never to announce.
         *
         * A list rather than a text field, because it grows from gameplay: the ignore action on a toast adds
         * to it at the moment something has just interrupted somebody. This screen is where it gets pruned.
         */
        public var ignoredItems: List<String> = emptyList()

        public var ignoredIslands: List<Island> = emptyList()
    }

    // -- garden --------------------------------------------------------------

    public object Garden {
        /**
         * Whether view bobbing is turned off on the Garden.
         *
         * Borrowed rather than taken: whatever you had comes back when you leave, and changing it yourself
         * while there stops the mod touching it for the rest of the visit.
         */
        public var suppressViewBobbing: Boolean = true
    }

    // -- SkyBlock levels -----------------------------------------------------

    public object Levels {
        public var isEnabled: Boolean = true

        public var inNametags: Boolean = true

        /**
         * The tab list.
         *
         * Separate from the nametag because they are separately annoying: a tab list is a dense grid where
         * extra colour is noise, a nametag is one name where it is information.
         */
        public var inTabList: Boolean = true

        /**
         * How a level is coloured.
         *
         * Tiered by default, because it is what the game does and a mod that silently rewrote a familiar
         * signal would make everybody's level unreadable at a glance.
         */
        public var palette: LevelPalette = LevelPalette.TIERED
    }

    // -- title screen --------------------------------------------------------

    public object TitleScreen {
        /** Off by default. Replacing the first thing somebody sees is not a decision to make for them. */
        public var isEnabled: Boolean = false

        /**
         * Whether it drifts.
         *
         * Separate from [isEnabled] because they answer different questions. Somebody who finds slow motion
         * uncomfortable still gets the nebula; somebody who wants Mojang's panorama turns the whole thing off.
         */
        public var animate: Boolean = true

        /** The three colours a sky needs: what is empty, what is cloud, and what is bright. */
        public var deepColour: Color = Color.parse("#0B0A14")
        public var cloudColour: Color = Color.parse("#4C3A8C")
        public var highlightColour: Color = Color.parse("#A78BFA")
    }

    // -- playtime ------------------------------------------------------------

    public object Playtime {
        public var isEnabled: Boolean = true

        /**
         * How many days of history to keep.
         *
         * A preference rather than a constant because the two reasonable answers are far apart: somebody who
         * wants a monthly view needs months of it, and somebody who does not want a record of their hours
         * sitting on disk should be able to keep almost none.
         */
        public var retentionDays: Int = 90
    }

    // -- Discord -------------------------------------------------------------

    /**
     * What goes on the Discord profile.
     *
     * **The widest audience anything in this mod publishes to.** Everything else — presence, pings, debts —
     * reaches a friend group that opted into a shared backend; this reaches everybody on somebody's Discord
     * friend list, including people who have never heard of SkyBlock. That is why these are their own
     * switches rather than a reuse of [dev.th7bo.sidequest.platform.permission.Permission]: the same person
     * can reasonably tell six friends where they are and not want it above their name in a work server.
     */
    public object Discord {
        /** Off until somebody asks for it, and until they have supplied an application id. */
        public var isEnabled: Boolean = false

        /**
         * The Discord application the presence belongs to.
         *
         * Defaulted to the group's own application for the same reason [backendUrl] is defaulted to the
         * group's own server: this is a private mod for one friend group, and asking every member to visit
         * the Discord developer portal is asking for five of them not to. One application is registered
         * once, its art is uploaded once, and everybody else inherits it — which is exactly what every mod
         * that ships rich presence does.
         *
         * Left as a setting so somebody can point it at their own application, not because they should have
         * to. Blank is supported and means the feature stays off.
         */
        public var applicationId: String = DEFAULT_APPLICATION_ID

        public var showIsland: Boolean = true
        public var showActivity: Boolean = true

        /** Off by default: people name profiles things they would not choose as a public label. */
        public var showProfile: Boolean = false

        /** A count, never names — see [PresenceDisclosure.showParty]. */
        public var showParty: Boolean = true

        public var showElapsed: Boolean = true

        /**
         * Sidequest's own Discord application.
         *
         * Registered once so that nobody in the group ever opens this setting — the same reasoning as
         * [backendUrl], and the reason every mod that ships rich presence hardcodes one.
         *
         * Two things follow from it that are easy to miss. The application's **name** is the bold first line
         * of every card this mod draws, so renaming it renames the presence for everyone. And every asset
         * key in `docs/guide/discord-rich-presence.md` resolves against **this application's** art library,
         * so art uploaded anywhere else will not appear.
         */
        public const val DEFAULT_APPLICATION_ID: String = "1533887312373616650"

        /**
         * The settings as the feature reads them: one value, snapshotted on the client thread.
         *
         * **An empty field falls back to the shipped application rather than to nothing.** A default on the
         * property alone is not enough: a config file written before this default existed — or by anybody
         * who cleared the box — has an empty string *stored*, and a stored empty string beats a default
         * forever. Resolving it here means the setting behaves the way an override should, and there is no
         * config file that can silently pin the feature off.
         *
         * [isEnabled] is the switch for turning it off. Blanking a text field is not.
         */
        public fun snapshot(): PresenceSettings = PresenceSettings(
            isEnabled = isEnabled,
            applicationId = applicationId.trim().ifEmpty { DEFAULT_APPLICATION_ID },
            disclosure = PresenceDisclosure(
                showIsland = showIsland,
                showActivity = showActivity,
                showProfile = showProfile,
                showParty = showParty,
                showElapsed = showElapsed,
            ),
        )
    }

    // -- profile viewer ------------------------------------------------------

    /**
     * Looking somebody up on SkyCrypt.
     *
     * The in-game window needs the `mcef-modern` mod, which brings a couple of hundred megabytes of
     * Chromium with it. Nobody is made to install that — with the mod absent, or with [preferInGame] off,
     * the viewer opens the same page in the player's own browser instead.
     */
    public object Profiles {
        public var isEnabled: Boolean = true

        /**
         * Whether to use the in-game window when it is available.
         *
         * Off means "always use my real browser", which is a reasonable preference and not a degraded mode:
         * a second monitor beats a window inside the game, and alt-tabbing costs nothing on one.
         */
        public var preferInGame: Boolean = true
    }

    // -- cosmetics -----------------------------------------------------------

    public object Cosmetics {
        public var isEnabled: Boolean = true
        public var showAppearanceOverrides: Boolean = true
        public var showEffects: Boolean = true
        public var showJokeCosmetics: Boolean = true
        public var reducedAnimation: Boolean = false
    }

    /**
     * Pushes every preference into the services that act on it.
     *
     * Called after the config loads and after any setting changes. One function rather than a callback per
     * setting: a screen with fifty settings would otherwise need fifty wires, and the forty-ninth is the one
     * somebody forgets — which produces a setting that looks like it works and does nothing until a restart.
     *
     * Cheap enough to run on every change. Each assignment is a data-class copy and a field write; nothing
     * here touches disk or the network.
     */
    public fun applyToPlatform() {
        val platform = Sidequest.platformOrNull ?: return

        platform.applyPreferences(
            notifications = NotificationSettings(
                seriousMode = seriousMode,
                queueWhileBusy = Notifications.queueWhileBusy,
                // Kept from the live value rather than reset: per-category delivery is set by features, not
                // by this screen, and overwriting it here would quietly undo them.
                perCategory = platform.notifications.settings.perCategory,
            ),
            sounds = platform.sounds.settings.copy(
                masterVolume = Sound.master,
                seriousMode = seriousMode,
                groupVolumes = mapOf(
                    SoundGroup.INTERFACE to Sound.interfaceVolume,
                    SoundGroup.EFFECTS to Sound.effects,
                    SoundGroup.SOUNDBOARD to Sound.soundboard,
                ),
            ),
            cinematics = CinematicSettings(
                isEnabled = Cinematics.isEnabled,
                letterbox = Cinematics.letterbox,
                compactOnly = Cinematics.compactOnly,
                queueWhileUnsafe = Cinematics.queueWhileUnsafe,
                recap = Cinematics.recap,
                seriousMode = seriousMode,
            ),
            cosmetics = CosmeticSettings(
                isEnabled = Cosmetics.isEnabled,
                showAppearanceOverrides = Cosmetics.showAppearanceOverrides,
                showEffects = Cosmetics.showEffects,
                showJokeCosmetics = Cosmetics.showJokeCosmetics,
                reducedAnimation = Cosmetics.reducedAnimation,
                // Preserved for the same reason as `perCategory`: these are set by right-clicking a player,
                // not by this screen.
                hiddenSlots = platform.cosmetics.settings.hiddenSlots,
                hiddenPlayers = platform.cosmetics.settings.hiddenPlayers,
            ),
        )
    }
}
