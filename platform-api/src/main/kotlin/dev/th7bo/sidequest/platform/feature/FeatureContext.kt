package dev.th7bo.sidequest.platform.feature

import dev.th7bo.sidequest.platform.asset.AssetManager
import dev.th7bo.sidequest.platform.log.ErrorLog
import dev.th7bo.sidequest.platform.audio.SoundManager
import dev.th7bo.sidequest.platform.cosmetic.CosmeticService
import dev.th7bo.sidequest.platform.chat.ChatParser
import dev.th7bo.sidequest.platform.chat.ChatRule
import dev.th7bo.sidequest.platform.command.CommandRegistry
import dev.th7bo.sidequest.platform.command.CommandSpec
import dev.th7bo.sidequest.platform.event.DispatchMode
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventPriority
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.game.GameVersion
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.item.SkyBlockItemRepository
import dev.th7bo.sidequest.platform.lifecycle.Registration
import dev.th7bo.sidequest.platform.lifecycle.RegistrationScope
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.cinematic.CinematicDirector
import dev.th7bo.sidequest.platform.marker.MarkerService
import dev.th7bo.sidequest.platform.notification.NotificationManager
import dev.th7bo.sidequest.platform.rule.RuleEngine
import dev.th7bo.sidequest.platform.party.PartyService
import dev.th7bo.sidequest.platform.permission.Permission
import dev.th7bo.sidequest.platform.permission.PermissionService
import dev.th7bo.sidequest.platform.player.PlayerActionRegistry
import dev.th7bo.sidequest.platform.player.PlayerDirectory
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.player.PlayerTargeting
import dev.th7bo.sidequest.platform.storage.Repository
import dev.th7bo.sidequest.platform.storage.StorageMigration
import dev.th7bo.sidequest.platform.storage.StorageProvider
import dev.th7bo.sidequest.platform.storage.StorageScope
import kotlinx.serialization.KSerializer
import dev.th7bo.sidequest.platform.skyblock.GameContextService
import dev.th7bo.sidequest.platform.scheduler.Debounced
import dev.th7bo.sidequest.platform.scheduler.Scheduler
import dev.th7bo.sidequest.platform.scheduler.SchedulerThread
import dev.th7bo.sidequest.platform.scheduler.Throttled
import kotlin.time.Duration

/**
 * Everything a running feature is allowed to touch.
 *
 * This is the only handle a feature gets, and it is deliberately narrow. A feature
 * cannot reach Minecraft, the filesystem, the network or another feature through it —
 * those arrive as services, each with its own boundary. What it can do is listen,
 * schedule, log and register commands, and every one of those is recorded against the
 * feature so that disabling it undoes the lot.
 *
 * The context is invalidated when the feature is disabled. Using it afterwards throws
 * rather than silently registering into a dead scope.
 */
public interface FeatureContext {

    public val descriptor: FeatureDescriptor

    public val owner: OwnerId get() = descriptor.owner

    /** The Minecraft version actually running. Already known to be in range. */
    public val gameVersion: GameVersion

    /** Prefixed with the feature id, so a log line always says who wrote it. */
    public val log: Logger

    /** Cancelled wholesale on disable. Add anything the helpers below do not cover. */
    public val scope: RegistrationScope

    // -- events -------------------------------------------------------------

    /** The bus, for posting. Prefer [listen] over subscribing directly. */
    public val events: EventBus

    /** Subscribes for as long as this feature is enabled. */
    public fun <T : SidequestEvent> listen(
        type: kotlin.reflect.KClass<T>,
        priority: EventPriority = EventPriority.NORMAL,
        mode: DispatchMode = DispatchMode.MAIN,
        listener: (T) -> Unit,
    ): Registration

    /** Posts an event. Source defaults to [EventSource.DERIVED] — it came from a feature. */
    public fun <T : SidequestEvent> post(event: T, source: EventSource = EventSource.DERIVED): T =
        events.post(event, source)

    // -- scheduling ---------------------------------------------------------

    /** The scheduler, for anything the helpers below do not cover. */
    public val scheduler: Scheduler

    public fun onMain(block: () -> Unit): Registration

    public fun async(block: suspend () -> Unit): Registration

    public fun after(
        delay: Duration,
        thread: SchedulerThread = SchedulerThread.MAIN,
        block: () -> Unit,
    ): Registration

    public fun every(
        period: Duration,
        initialDelay: Duration = period,
        thread: SchedulerThread = SchedulerThread.MAIN,
        block: () -> Unit,
    ): Registration

    public fun debounce(
        delay: Duration,
        thread: SchedulerThread = SchedulerThread.MAIN,
        block: () -> Unit,
    ): Debounced

    public fun throttle(
        interval: Duration,
        thread: SchedulerThread = SchedulerThread.MAIN,
        block: () -> Unit,
    ): Throttled

    // -- commands -----------------------------------------------------------

    /** The registry, for anything [command] does not cover. */
    public val commands: CommandRegistry

    /** Registers a client command for as long as this feature is enabled. */
    public fun command(spec: CommandSpec): Registration

    // -- game context -------------------------------------------------------

    /**
     * Where the player is, as one authoritative value.
     *
     * A feature asks this instead of deciding for itself what "in a dungeon" means — which
     * is how a mod ends up with four slightly different answers to the same question, three
     * of them wrong in different situations. Anything that *acts* should check
     * [dev.th7bo.sidequest.platform.skyblock.GameContext.isReliable] first.
     */
    public val gameContext: GameContextService

    // -- players ------------------------------------------------------------

    /**
     * Who is who, keyed on UUID.
     *
     * Anything a feature stores about a player keys on
     * [dev.th7bo.sidequest.platform.player.PlayerId], never on a username: Minecraft names can be
     * released and claimed by somebody else, so a record against a name can end up against a
     * stranger.
     */
    public val players: PlayerDirectory

    /** Finding the player somebody means: crosshair, last target, or a typed name. */
    public val targeting: PlayerTargeting

    /**
     * Where a feature contributes what it can do to a player.
     *
     * A feature registers what it offers and never learns who else offered anything. That is what keeps the
     * action menu from having to import every feature it can reach — see
     * [PlayerActionRegistry][dev.th7bo.sidequest.platform.player.PlayerActionRegistry].
     */
    public val playerActions: PlayerActionRegistry

    /**
     * The party.
     *
     * Read this instead of watching for party chat. A feature that parses "joined the party." is
     * one of six copies of the same pattern, five of which will not be fixed when Hypixel rewords
     * it.
     */
    public val party: PartyService

    // -- telling the player something ---------------------------------------

    /**
     * Notifications.
     *
     * A feature says what happened; the manager decides whether to show it, how, and whether now is a bad
     * moment. A feature that drew its own toast would be one the "serious mode" switch does not reach.
     */
    public val notifications: NotificationManager

    /**
     * Sounds.
     *
     * Cooldowns, volume groups, mute controls and asset fallback come with it. A feature that asked the game
     * directly would have none of them — and the one that most needs them is anything playing a sound in
     * somebody else's ears.
     */
    public val sounds: SoundManager

    /**
     * Cinematics: the things worth stopping the game for.
     *
     * A feature submits and does not decide. Whether now is a safe moment to cover the screen is one question
     * with one answer, and eleven features each deciding it would be eleven chances to get somebody killed at
     * a boss.
     */
    public val cinematics: CinematicDirector

    /**
     * Waypoints, pings, death markers, navigation and rally targets.
     *
     * One system for all of them. A feature places a marker and does not decide when it goes away, who may see
     * it, or whether the player has got there — seven features each tracking their own would be seven answers
     * to "when does this disappear", and the one that gets it wrong leaves markers on screen forever.
     */
    public val markers: MarkerService

    /**
     * Rules: when this, then that.
     *
     * A feature contributes rules the same way it contributes chat patterns, rather than wiring its own
     * listener and remembering its own cooldown. Seven features each implementing "fire once, then not for a
     * minute" is seven slightly different ideas of what a cooldown means.
     */
    public val rules: RuleEngine

    /**
     * Images, sounds and other files the group shares.
     *
     * A feature names a hash and gets bytes. It cannot name a URL, and that is the point rather than an
     * inconvenience: an asset comes from the configured backend or it does not come at all, so no cosmetic
     * anybody uploads can make a client fetch from somewhere else.
     */
    public val assets: AssetManager

    /**
     * What SkyBlock's own items are.
     *
     * Hypixel announces things by display name and nothing else, and the game knows perhaps a third of those
     * names. This is how a feature turns `Revenant Catalyst` into something drawable — and, since a lookup can
     * be a network round trip, why the useful move is to ask early and read the answer later.
     */
    public val items: SkyBlockItemRepository

    /**
     * Cosmetics: what people are wearing, and what this client draws of it.
     *
     * The service decides; nothing draws here. A feature that granted a cosmetic and also rendered it would
     * be the second place deciding whether the viewer wants to see it, and the viewer's answer has to have
     * exactly one place it is consulted.
     */
    public val cosmetics: CosmeticService

    /**
     * What has gone wrong this session, grouped.
     *
     * Read-only on purpose. A feature reports a problem by logging it — this is the same problems, counted,
     * for whoever is showing them to a person.
     */
    public val errors: ErrorLog

    // -- storage ------------------------------------------------------------

    /**
     * Durable storage, for anything [store] does not cover.
     *
     * **Features must not read or write JSON.** Eleven features each inventing an atomic-write is
     * eleven chances to get the corruption case wrong, each losing a different user's data.
     */
    public val storage: StorageProvider

    /**
     * A repository for this feature's own data.
     *
     * Namespaced under the feature's id, so two features cannot collide however they name their files.
     *
     * @param default used on a first run and when a stored value fails [validate].
     * @param validate a reason to reject a value that parses and is still wrong. Parsing is not
     *   validation: a negative coin count deserialises perfectly.
     */
    public fun <T : Any> store(
        name: String,
        scope: StorageScope,
        serializer: KSerializer<T>,
        default: () -> T,
        schemaVersion: Int = 1,
        migrations: List<StorageMigration> = emptyList(),
        validate: (T) -> String? = { null },
    ): Repository<T>

    // -- permissions --------------------------------------------------------

    /**
     * Who may do what, and what the local player has agreed to reveal.
     *
     * Ask before acting and before revealing. A permission check copied into a feature is one that does
     * not get updated when the rules change, and the failure is silent — the feature keeps working, it
     * just stops asking.
     */
    public val permissions: PermissionService

    /** Whether the local player has agreed to share [permission] with [viewer]. */
    public fun shares(permission: Permission, viewer: PlayerId): Boolean =
        permissions.shares(permission, viewer)

    // -- chat ---------------------------------------------------------------

    /** The chat parser, for anything [chatRule] does not cover. */
    public val chat: ChatParser

    /**
     * Registers a chat rule for as long as this feature is enabled.
     *
     * A feature declares the shape of a line and the event it means, and never touches a
     * chat string again. Reading chat any other way — subscribing to
     * [dev.th7bo.sidequest.platform.chat.ChatMessageEvent] and matching a private regex —
     * is what the parser exists to stop, because those copies are the ones nobody fixes
     * when Hypixel rewords a message.
     */
    public fun chatRule(rule: ChatRule<*>): Registration
}

/** Subscribes to [T], inferring the type. The form feature code uses. */
public inline fun <reified T : SidequestEvent> FeatureContext.listen(
    priority: EventPriority = EventPriority.NORMAL,
    mode: DispatchMode = DispatchMode.MAIN,
    noinline listener: (T) -> Unit,
): Registration = listen(T::class, priority, mode, listener)

/**
 * Registers a command with a name and a handler.
 *
 * Pass [completions] and the command gets real tab completion; pass neither it nor [usage] and the command is
 * declared as a bare word, which is what stops the game offering an argument hint for something that takes
 * none. Nullable rather than defaulted to an empty list, because "no completions" and "no arguments" are
 * different claims and the difference is what the grammar is built from.
 */
public fun FeatureContext.command(
    name: String,
    description: String = "",
    usage: String = "",
    completions: ((arguments: List<String>) -> List<String>)? = null,
    handler: (List<String>) -> Unit,
): Registration = command(
    CommandSpec(
        name = name,
        description = description,
        usage = usage,
        takesArguments = usage.isNotEmpty() || completions != null,
        completions = completions ?: { emptyList() },
        handler = handler,
    ),
)
