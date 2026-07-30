package dev.th7bo.sidequest.platform.core.feature

import dev.th7bo.sidequest.platform.chat.ChatParser
import dev.th7bo.sidequest.platform.chat.ChatRule
import dev.th7bo.sidequest.platform.command.CommandRegistry
import dev.th7bo.sidequest.platform.command.CommandSpec
import dev.th7bo.sidequest.platform.event.DispatchMode
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventPriority
import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.game.GameVersion
import dev.th7bo.sidequest.platform.lifecycle.Registration
import dev.th7bo.sidequest.platform.lifecycle.RegistrationScope
import dev.th7bo.sidequest.platform.lifecycle.ownedBy
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.asset.AssetManager
import dev.th7bo.sidequest.platform.cosmetic.CosmeticService
import dev.th7bo.sidequest.platform.log.ErrorLog
import dev.th7bo.sidequest.platform.audio.SoundManager
import dev.th7bo.sidequest.platform.cinematic.CinematicDirector
import dev.th7bo.sidequest.platform.marker.MarkerService
import dev.th7bo.sidequest.platform.notification.NotificationManager
import dev.th7bo.sidequest.platform.rule.RuleEngine
import dev.th7bo.sidequest.platform.party.PartyService
import dev.th7bo.sidequest.platform.permission.PermissionService
import dev.th7bo.sidequest.platform.storage.Repository
import dev.th7bo.sidequest.platform.storage.StorageMigration
import dev.th7bo.sidequest.platform.storage.StorageProvider
import dev.th7bo.sidequest.platform.storage.StorageScope
import kotlinx.serialization.KSerializer
import dev.th7bo.sidequest.platform.player.PlayerDirectory
import dev.th7bo.sidequest.platform.player.PlayerTargeting
import dev.th7bo.sidequest.platform.skyblock.GameContextService
import dev.th7bo.sidequest.platform.scheduler.Debounced
import dev.th7bo.sidequest.platform.scheduler.Scheduler
import dev.th7bo.sidequest.platform.scheduler.SchedulerThread
import dev.th7bo.sidequest.platform.scheduler.Throttled
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * The handle a running feature holds.
 *
 * Every registration made through it goes into [scope], and [tearDown] cancels the lot.
 * The belt-and-braces sweep afterwards — `unsubscribeAll`, `cancelAll`,
 * `unregisterAll` — exists because the scope only knows about handles the feature was
 * given: anything that got registered by a path that did not return one would otherwise
 * survive. The two mechanisms overlap on purpose.
 *
 * Using the context after teardown throws. Silently registering into a dead scope would
 * leave a feature believing it is listening when it is not.
 */
internal class DefaultFeatureContext(
    override val descriptor: FeatureDescriptor,
    override val gameVersion: GameVersion,
    override val events: EventBus,
    override val scheduler: Scheduler,
    override val commands: CommandRegistry,
    override val chat: ChatParser,
    override val gameContext: GameContextService,
    override val players: PlayerDirectory,
    override val targeting: PlayerTargeting,
    override val party: PartyService,
    override val notifications: NotificationManager,
    override val sounds: SoundManager,
    override val cinematics: CinematicDirector,
    override val markers: MarkerService,
    override val rules: RuleEngine,
    override val assets: AssetManager,
    override val cosmetics: CosmeticService,
    override val errors: ErrorLog,
    override val storage: StorageProvider,
    override val permissions: PermissionService,
    override val log: Logger,
) : FeatureContext {

    override val scope: RegistrationScope = RegistrationScope(descriptor.id.value)

    override fun <T : SidequestEvent> listen(
        type: KClass<T>,
        priority: EventPriority,
        mode: DispatchMode,
        listener: (T) -> Unit,
    ): Registration {
        checkAlive()
        return events.subscribe(type, owner, priority, mode, listener).ownedBy(scope)
    }

    override fun onMain(block: () -> Unit): Registration {
        checkAlive()
        return scheduler.onMain(owner, block).ownedBy(scope)
    }

    override fun async(block: suspend () -> Unit): Registration {
        checkAlive()
        return scheduler.async(owner, block).ownedBy(scope)
    }

    override fun after(delay: Duration, thread: SchedulerThread, block: () -> Unit): Registration {
        checkAlive()
        return scheduler.after(owner, delay, thread, block).ownedBy(scope)
    }

    override fun every(
        period: Duration,
        initialDelay: Duration,
        thread: SchedulerThread,
        block: () -> Unit,
    ): Registration {
        checkAlive()
        return scheduler.every(owner, period, initialDelay, thread, block).ownedBy(scope)
    }

    override fun debounce(delay: Duration, thread: SchedulerThread, block: () -> Unit): Debounced {
        checkAlive()
        return scheduler.debounce(owner, delay, thread, block).also { scope.add(it) }
    }

    override fun throttle(interval: Duration, thread: SchedulerThread, block: () -> Unit): Throttled {
        checkAlive()
        return scheduler.throttle(owner, interval, thread, block).also { scope.add(it) }
    }

    override fun command(spec: CommandSpec): Registration {
        checkAlive()
        return commands.register(owner, spec).ownedBy(scope)
    }

    override fun <T : Any> store(
        name: String,
        scope: StorageScope,
        serializer: KSerializer<T>,
        default: () -> T,
        schemaVersion: Int,
        migrations: List<StorageMigration>,
        validate: (T) -> String?,
    ): Repository<T> {
        checkAlive()
        // Namespaced under the feature id, so two features naming a file "data" get two files. Not
        // added to the scope: storage outlives the feature being disabled, and a repository holds no
        // resources to release.
        return storage.repository(
            id = descriptor.id.child(name),
            scope = scope,
            serializer = serializer,
            default = default,
            schemaVersion = schemaVersion,
            migrations = migrations,
            validate = validate,
        )
    }

    override fun chatRule(rule: ChatRule<*>): Registration {
        checkAlive()
        return chat.register(rule, owner).ownedBy(scope)
    }

    /** Undoes everything this feature registered. Safe to call more than once. */
    fun tearDown() {
        if (!scope.isClosed) {
            try {
                scope.cancel()
            } catch (thrown: Throwable) {
                // Collected by the scope; reported here so one bad disposer does not stop
                // the sweep below from running.
                log.error(thrown) { "Failures while tearing down ${descriptor.id}" }
            }
        }

        // The sweep. See the class comment: the scope only knows about handles the
        // feature was handed back.
        events.unsubscribeAll(owner)
        scheduler.cancelAll(owner)
        commands.unregisterAll(owner)
        chat.unregisterAll(owner)
    }

    private fun checkAlive() {
        check(!scope.isClosed) {
            "${descriptor.id} used its FeatureContext after being disabled. " +
                "Hold nothing across onEnable/onDisable that the context gave you."
        }
    }
}
