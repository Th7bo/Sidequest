package dev.th7bo.sidequest.platform.feature

import dev.th7bo.sidequest.platform.command.CommandSpec
import dev.th7bo.sidequest.platform.event.DispatchMode
import dev.th7bo.sidequest.platform.event.EventPriority
import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.game.VersionRange
import dev.th7bo.sidequest.platform.id.SqId
import kotlin.reflect.KClass

/**
 * Declares a feature.
 *
 * ```
 * val rareDropAnimation = feature("drops.rare_animation") {
 *     displayName = "Rare drop animation"
 *     category = FeatureCategory.VISUALS
 *     description = "Plays a cinematic when something rare drops"
 *     config = RareDropConfig
 *
 *     listen<ClientTickEvent>(::onTick)
 *     command("testraredrop") { play(sample()) }
 * }
 * ```
 *
 * The listeners and commands declared in the block are registered when the feature is
 * enabled and undone when it is disabled — declaring them is not the same as installing
 * them, which is what makes a feature safe to toggle at runtime.
 */
public fun feature(id: String, build: FeatureBuilder.() -> Unit): Feature =
    FeatureBuilder(SqId.parse(id)).apply(build).build()

/** @see feature */
@FeatureDsl
public class FeatureBuilder internal constructor(private val id: SqId) {

    /** Defaults to the last path segment, title-cased. Override for anything user-visible. */
    public var displayName: String = id.path.substringAfterLast('.').replace('_', ' ')
        .replaceFirstChar { it.uppercase() }

    public var category: FeatureCategory = FeatureCategory.UTILITY

    public var description: String = ""

    public var supportedVersions: VersionRange = VersionRange.Any

    public var isExperimental: Boolean = false

    public var enabledByDefault: Boolean = true

    /** The configuration section this feature's settings live in. */
    public var config: FeatureConfigSection? = null

    private val dependencies = LinkedHashSet<SqId>()
    private val permissions = LinkedHashSet<SqId>()
    private val subscriptions = LinkedHashSet<SqId>()
    private val listeners = ArrayList<DeclaredListener<*>>()
    private val commands = ArrayList<CommandSpec>()
    private var enableBlock: ((FeatureContext) -> Unit)? = null
    private var disableBlock: (() -> Unit)? = null

    /** This feature does not start unless [ids] are enabled first. */
    public fun dependsOn(vararg ids: String) {
        ids.forEach { dependencies.add(SqId.parse(it)) }
    }

    /** Backend permissions this feature needs. Declared, not requested ad hoc. */
    public fun needsPermission(vararg ids: String) {
        ids.forEach { permissions.add(SqId.parse(it)) }
    }

    /** Realtime topics this feature subscribes to. */
    public fun subscribesTo(vararg topics: String) {
        topics.forEach { subscriptions.add(SqId.parse(it)) }
    }

    /** Registers [listener] for [T] while the feature is enabled. */
    public fun <T : SidequestEvent> listen(
        type: KClass<T>,
        priority: EventPriority = EventPriority.NORMAL,
        mode: DispatchMode = DispatchMode.MAIN,
        listener: (T) -> Unit,
    ) {
        listeners.add(DeclaredListener(type, priority, mode, listener))
    }

    public fun command(spec: CommandSpec) {
        commands.add(spec)
    }

    /** The same shape as [FeatureContext.command] — see there for why [completions] is nullable. */
    public fun command(
        name: String,
        description: String = "",
        usage: String = "",
        completions: ((arguments: List<String>) -> List<String>)? = null,
        handler: (List<String>) -> Unit,
    ) {
        commands.add(
            CommandSpec(
                name = name,
                description = description,
                usage = usage,
                takesArguments = usage.isNotEmpty() || completions != null,
                completions = completions ?: { emptyList() },
                handler = handler,
            ),
        )
    }

    /** Anything the declarations above do not cover. Runs after they are installed. */
    public fun onEnable(block: (FeatureContext) -> Unit) {
        enableBlock = block
    }

    /** For state the context does not own. Usually unnecessary — see [Feature.onDisable]. */
    public fun onDisable(block: () -> Unit) {
        disableBlock = block
    }

    internal fun build(): Feature {
        val descriptor = FeatureDescriptor(
            id = id,
            displayName = displayName,
            category = category,
            description = description,
            supportedVersions = supportedVersions,
            dependencies = dependencies,
            configSection = config?.sectionId,
            backendPermissions = permissions,
            networkSubscriptions = subscriptions,
            isExperimental = isExperimental,
            enabledByDefault = enabledByDefault,
        )
        return DeclaredFeature(
            descriptor = descriptor,
            listeners = listeners.toList(),
            commands = commands.toList(),
            enableBlock = enableBlock,
            disableBlock = disableBlock,
        )
    }
}

/** Registers [T] for the feature's lifetime, inferring the type. */
public inline fun <reified T : SidequestEvent> FeatureBuilder.listen(
    priority: EventPriority = EventPriority.NORMAL,
    mode: DispatchMode = DispatchMode.MAIN,
    noinline listener: (T) -> Unit,
): Unit = listen(T::class, priority, mode, listener)

/** A listener recorded at declaration time and installed on enable. */
internal class DeclaredListener<T : SidequestEvent>(
    val type: KClass<T>,
    val priority: EventPriority,
    val mode: DispatchMode,
    val listener: (T) -> Unit,
) {
    fun install(context: FeatureContext) {
        context.listen(type, priority, mode, listener)
    }
}

private class DeclaredFeature(
    override val descriptor: FeatureDescriptor,
    private val listeners: List<DeclaredListener<*>>,
    private val commands: List<CommandSpec>,
    private val enableBlock: ((FeatureContext) -> Unit)?,
    private val disableBlock: (() -> Unit)?,
) : Feature {

    override fun onEnable(context: FeatureContext) {
        for (listener in listeners) listener.install(context)
        for (spec in commands) context.command(spec)
        enableBlock?.invoke(context)
    }

    override fun onDisable() {
        disableBlock?.invoke()
    }
}

@DslMarker
public annotation class FeatureDsl
