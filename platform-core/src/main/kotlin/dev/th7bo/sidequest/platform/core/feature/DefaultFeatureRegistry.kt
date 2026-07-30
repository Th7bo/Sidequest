package dev.th7bo.sidequest.platform.core.feature

import dev.th7bo.sidequest.platform.chat.ChatParser
import dev.th7bo.sidequest.platform.command.CommandRegistry
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.event.FeatureStateChangedEvent
import dev.th7bo.sidequest.platform.feature.DuplicateFeatureException
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCycleException
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.FeatureHandle
import dev.th7bo.sidequest.platform.feature.FeatureRefusal
import dev.th7bo.sidequest.platform.feature.FeatureRegistry
import dev.th7bo.sidequest.platform.game.GameVersion
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.log.LogCategory
import dev.th7bo.sidequest.platform.audio.SoundManager
import dev.th7bo.sidequest.platform.cinematic.CinematicDirector
import dev.th7bo.sidequest.platform.notification.NotificationManager
import dev.th7bo.sidequest.platform.rule.RuleEngine
import dev.th7bo.sidequest.platform.party.PartyService
import dev.th7bo.sidequest.platform.permission.PermissionService
import dev.th7bo.sidequest.platform.storage.StorageProvider
import dev.th7bo.sidequest.platform.player.PlayerDirectory
import dev.th7bo.sidequest.platform.player.PlayerTargeting
import dev.th7bo.sidequest.platform.skyblock.GameContextService
import dev.th7bo.sidequest.platform.scheduler.Scheduler
import dev.th7bo.sidequest.platform.core.log.LoggerFactory

/**
 * The feature registry.
 *
 * Enabling is transactional. `onEnable` registers through a context backed by a scope,
 * and if it throws part-way, the scope is cancelled before the failure is reported — so
 * a feature that fails half-set-up leaves nothing behind. Without that, the failure mode
 * is a listener registered by a feature that is not running, firing against state that
 * was never finished initialising.
 *
 * Disabling walks dependents first, so a feature never runs for even one dispatch
 * against a dependency that has already gone.
 */
public class DefaultFeatureRegistry(
    private val gameVersion: GameVersion,
    private val events: EventBus,
    private val scheduler: Scheduler,
    private val commands: CommandRegistry,
    private val chat: ChatParser,
    private val gameContext: GameContextService,
    private val players: PlayerDirectory,
    private val targeting: PlayerTargeting,
    private val party: PartyService,
    private val notifications: NotificationManager,
    private val sounds: SoundManager,
    private val cinematics: CinematicDirector,
    private val rules: RuleEngine,
    private val storage: StorageProvider,
    private val permissions: PermissionService,
    private val loggers: LoggerFactory,
    /** Whether a feature should start. Backed by config once the config bridge exists. */
    private val isEnabledByUser: (FeatureDescriptor) -> Boolean = { it.enabledByDefault },
) : FeatureRegistry {

    private class Handle(
        val feature: Feature,
        override val descriptor: FeatureDescriptor,
    ) : FeatureHandle {
        var context: DefaultFeatureContext? = null
        override val isEnabled: Boolean get() = context != null
        override var refusal: FeatureRefusal? = null
    }

    private val handles = LinkedHashMap<SqId, Handle>()
    private val log = loggers.create(LogCategory.FEATURE, SqId.sidequest("features"))

    override fun register(feature: Feature) {
        val descriptor = feature.descriptor
        if (handles.containsKey(descriptor.id)) throw DuplicateFeatureException(descriptor.id)
        handles[descriptor.id] = Handle(feature, descriptor)

        // Checked on every registration rather than once at the end: the exception can
        // then name the feature that closed the loop, which is the one worth looking at.
        detectCycle(descriptor.id)
        log.debug { "Registered ${descriptor.id} (${descriptor.category})" }
    }

    override fun all(): List<FeatureHandle> = handles.values.toList()

    override fun get(id: SqId): FeatureHandle? = handles[id]

    override fun enable(id: SqId): FeatureRefusal? = enable(id, ArrayList())

    private fun enable(id: SqId, path: MutableList<SqId>): FeatureRefusal? {
        val handle = handles[id] ?: return FeatureRefusal(
            id,
            FeatureRefusal.Reason.MISSING_DEPENDENCY,
            "No feature with id $id is registered",
        )
        if (handle.isEnabled) return null

        val descriptor = handle.descriptor

        if (gameVersion !in descriptor.supportedVersions) {
            return refuse(
                handle,
                FeatureRefusal.Reason.UNSUPPORTED_VERSION,
                "Runs on ${descriptor.supportedVersions}, this is $gameVersion",
            )
        }

        if (!isEnabledByUser(descriptor)) {
            return refuse(handle, FeatureRefusal.Reason.DISABLED_BY_CONFIG, "Switched off")
        }

        // Dependencies first, depth-first. The cycle check at registration guarantees
        // this terminates.
        path.add(id)
        for (dependency in descriptor.dependencies) {
            val failure = enable(dependency, path)
            if (failure != null) {
                path.removeLast()
                return refuse(
                    handle,
                    FeatureRefusal.Reason.DEPENDENCY_REFUSED,
                    "Needs $dependency, which refused: ${failure.reason}",
                )
            }
        }
        path.removeLast()

        val context = DefaultFeatureContext(
            descriptor = descriptor,
            gameVersion = gameVersion,
            events = events,
            scheduler = scheduler,
            commands = commands,
            chat = chat,
            gameContext = gameContext,
            players = players,
            targeting = targeting,
            party = party,
            notifications = notifications,
            sounds = sounds,
            cinematics = cinematics,
            rules = rules,
            storage = storage,
            permissions = permissions,
            log = loggers.create(LogCategory.FEATURE, descriptor.id),
        )

        return try {
            handle.feature.onEnable(context)
            handle.context = context
            handle.refusal = null
            log.info { "Enabled ${descriptor.id}" }
            events.post(FeatureStateChangedEvent(descriptor.id, isEnabled = true), EventSource.DERIVED)
            null
        } catch (thrown: Throwable) {
            // Undo whatever it managed to register before failing. A half-enabled feature
            // is worse than a disabled one: its listeners fire against state that was
            // never finished.
            context.tearDown()
            refuse(
                handle,
                FeatureRefusal.Reason.FAILED_TO_ENABLE,
                thrown.message ?: thrown::class.simpleName ?: "threw",
            ).also { log.error(thrown) { "${descriptor.id} threw while enabling" } }
        }
    }

    override fun disable(id: SqId) {
        val handle = handles[id] ?: return
        if (!handle.isEnabled) return

        // Dependents first: a feature must never run against a dependency that is gone.
        for (dependent in handles.values) {
            if (dependent.isEnabled && id in dependent.descriptor.dependencies) {
                disable(dependent.descriptor.id)
            }
        }

        handle.context?.tearDown()
        handle.context = null

        try {
            handle.feature.onDisable()
        } catch (thrown: Throwable) {
            // Already unregistered by this point, so the failure cannot leave anything
            // live — it is reported and the disable stands.
            log.error(thrown) { "$id threw while disabling; its registrations were already removed" }
        }

        log.info { "Disabled $id" }
        events.post(FeatureStateChangedEvent(id, isEnabled = false), EventSource.DERIVED)
    }

    override fun enableAll(): List<FeatureRefusal> {
        val refusals = ArrayList<FeatureRefusal>()
        for (id in handles.keys.toList()) {
            enable(id)?.let { refusals.add(it) }
        }
        if (refusals.isNotEmpty()) {
            log.info { "${refusals.size} feature(s) did not start: ${refusals.joinToString()}" }
        }
        return refusals
    }

    override fun disableAll() {
        for (id in handles.keys.toList().asReversed()) disable(id)
    }

    private fun refuse(handle: Handle, reason: FeatureRefusal.Reason, detail: String): FeatureRefusal =
        FeatureRefusal(handle.descriptor.id, reason, detail).also { handle.refusal = it }

    /**
     * Walks dependencies from [start] looking for a way back to it.
     *
     * Missing dependencies are ignored here: a feature may legitimately be registered
     * before the one it needs, and whether it is *present* is decided at enable time.
     */
    private fun detectCycle(start: SqId) {
        val path = ArrayList<SqId>()
        val visiting = HashSet<SqId>()

        fun visit(id: SqId) {
            if (!visiting.add(id)) {
                throw FeatureCycleException(path.dropWhile { it != id } + id)
            }
            path.add(id)
            handles[id]?.descriptor?.dependencies?.forEach(::visit)
            path.removeLast()
            visiting.remove(id)
        }

        visit(start)
    }
}
