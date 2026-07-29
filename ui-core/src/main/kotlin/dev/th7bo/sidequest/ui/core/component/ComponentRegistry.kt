package dev.th7bo.sidequest.ui.core.component

import dev.th7bo.sidequest.ui.config.Setting
import dev.th7bo.sidequest.ui.core.animation.AnimationHost
import dev.th7bo.sidequest.ui.core.tree.UiNode
import dev.th7bo.sidequest.ui.extension.DuplicateRegistrationException
import dev.th7bo.sidequest.ui.extension.Registration
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.UiScheduler
import dev.th7bo.sidequest.ui.theme.Theme
import kotlin.reflect.KClass

/**
 * Builds the node subtree that presents one setting.
 *
 * Renderers are looked up by the setting's concrete type, never by a conditional over
 * an enum, so a third party can add a control without touching framework source.
 */
public fun interface SettingRenderer<in S : Setting<*>> {

    /**
     * Creates the nodes for [setting]. Called once when the row is materialized, not
     * every frame — the returned subtree is retained and reacts to state on its own.
     */
    public fun createNode(setting: S, context: ComponentContext): UiNode
}

/** What a renderer is given when it builds a control. */
public class ComponentContext(
    public val theme: Theme,
    public val animations: AnimationHost,
    /** For handing background results back onto the UI thread. */
    public val scheduler: UiScheduler,
    /**
     * Where popups go.
     *
     * Null when the host has no overlay layer — a control must then degrade to whatever
     * it can do inline rather than failing, which is why this is nullable rather than
     * required.
     */
    public val overlays: dev.th7bo.sidequest.ui.core.overlay.OverlayHost? = null,
    /**
     * Resolves icon ids to something drawable.
     *
     * Empty by default rather than null: an unregistered icon already degrades to a
     * placeholder, so components never need to check.
     */
    public val icons: dev.th7bo.sidequest.ui.core.icon.IconRegistry =
        dev.th7bo.sidequest.ui.core.icon.IconRegistry(),
    /** True in development builds; enables diagnostic placeholders. */
    public val isDevelopment: Boolean = true,
)

/**
 * The typed component registry.
 *
 * Maps setting types to the renderers that present them. Registration is owned by a
 * [RegistrationScope], so a module that unloads takes its controls with it and leaves no
 * cached renderer objects or captured lambdas behind.
 *
 * ```
 * registry.register(scope, GradientSetting::class, GradientSettingRenderer())
 * ```
 */
public class ComponentRegistry {

    private val renderers = LinkedHashMap<KClass<out Setting<*>>, Entry>()
    private val byOwner = LinkedHashMap<UiId, MutableSet<KClass<out Setting<*>>>>()

    /** Problems encountered while building controls, for the diagnostics viewer. */
    private val failures = ArrayList<ComponentFailure>()

    public val size: Int get() = renderers.size

    public val recordedFailures: List<ComponentFailure> get() = failures.toList()

    /**
     * Registers [renderer] for settings of exactly [type].
     *
     * @throws DuplicateRegistrationException if the type is taken, naming both owners.
     * @throws IllegalStateException if [scope] is already disposed.
     */
    public fun <S : Setting<*>> register(
        scope: RegistrationScope,
        type: KClass<S>,
        renderer: SettingRenderer<S>,
    ): Registration {
        check(!scope.isDisposed) { "Cannot register a renderer for ${type.simpleName} into a disposed scope" }

        val existing = renderers[type]
        if (existing != null) {
            throw DuplicateRegistrationException(
                "component renderer",
                type.qualifiedName ?: type.toString(),
                existing.owner,
                scope.owner,
            )
        }

        @Suppress("UNCHECKED_CAST")
        val entry = Entry(type, renderer as SettingRenderer<Setting<*>>, scope.owner, this)
        renderers[type] = entry
        byOwner.getOrPut(scope.owner) { LinkedHashSet() }.add(type)
        scope.register(entry)
        return entry
    }

    /** Convenience for Kotlin call sites: the type comes from the reified parameter. */
    public inline fun <reified S : Setting<*>> register(
        scope: RegistrationScope,
        renderer: SettingRenderer<S>,
    ): Registration = register(scope, S::class, renderer)

    public fun hasRenderer(setting: Setting<*>): Boolean = renderers.containsKey(setting::class)

    public fun ownerOf(type: KClass<out Setting<*>>): UiId? = renderers[type]?.owner

    public fun typesOwnedBy(owner: UiId): Set<KClass<out Setting<*>>> = byOwner[owner].orEmpty().toSet()

    /**
     * Builds the control for [setting].
     *
     * Never throws. A missing renderer, or a renderer that throws, produces a
     * [MissingComponentNode] describing the problem — one broken third-party control
     * must not take the whole configuration screen down with it.
     */
    public fun createNode(setting: Setting<*>, context: ComponentContext): UiNode {
        val entry = renderers[setting::class]
            ?: return recordAndDescribe(
                setting,
                "No renderer registered for ${setting::class.simpleName}",
                null,
                context,
            )

        return try {
            entry.renderer.createNode(setting, context)
        } catch (failure: Throwable) {
            recordAndDescribe(
                setting,
                failure.message ?: failure::class.simpleName ?: "renderer threw",
                failure,
                context,
            )
        }
    }

    private fun recordAndDescribe(
        setting: Setting<*>,
        problem: String,
        cause: Throwable?,
        context: ComponentContext,
    ): UiNode {
        failures.add(ComponentFailure(setting.id, setting::class, problem, cause))
        return MissingComponentNode(
            settingId = setting.id,
            settingType = setting::class.simpleName ?: "unknown",
            problem = problem,
            isDevelopment = context.isDevelopment,
        )
    }

    /** Clears the recorded failure list. */
    public fun clearFailures() {
        failures.clear()
    }

    internal fun remove(type: KClass<out Setting<*>>, owner: UiId) {
        renderers.remove(type)
        byOwner[owner]?.let { types ->
            types.remove(type)
            if (types.isEmpty()) byOwner.remove(owner)
        }
    }

    private class Entry(
        private val type: KClass<out Setting<*>>,
        val renderer: SettingRenderer<Setting<*>>,
        override val owner: UiId,
        private var registry: ComponentRegistry?,
    ) : Registration {

        override fun dispose() {
            registry?.remove(type, owner)
            registry = null
        }
    }
}

/** A control that could not be built. Surfaced in the developer diagnostics view. */
public class ComponentFailure(
    public val settingId: UiId,
    public val settingType: KClass<out Setting<*>>,
    public val problem: String,
    public val cause: Throwable?,
) {
    override fun toString(): String = "$settingId (${settingType.simpleName}): $problem"
}
