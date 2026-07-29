package dev.th7bo.sidequest.platform.feature

import dev.th7bo.sidequest.platform.game.VersionRange
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId

/**
 * What a feature is, independent of whether it is running.
 *
 * Everything here is declaration, not behaviour: the registry can answer "what does this
 * mod do, what does it need, and what would it be allowed to talk to" without loading a
 * single feature. That is what makes a settings screen, a permission audit and a
 * dependency check possible without side effects.
 */
public data class FeatureDescriptor(
    /** Stable. It ends up in config files and in backend records; renaming it is a migration. */
    public val id: SqId,
    public val displayName: String,
    public val category: FeatureCategory,
    public val description: String,

    /** Versions this feature is known to work on. Outside it, it is not enabled. */
    public val supportedVersions: VersionRange = VersionRange.Any,

    /** Features that must be enabled first. Cycles are rejected at registration. */
    public val dependencies: Set<SqId> = emptySet(),

    /** The configuration section this feature's settings live in, if it has any. */
    public val configSection: SqId? = null,

    /**
     * Backend permissions this feature needs.
     *
     * Declared up front so the set of things the mod could ask the backend for is
     * knowable from the registry, rather than discovered by watching traffic.
     */
    public val backendPermissions: Set<SqId> = emptySet(),

    /** Realtime topics this feature subscribes to. Same reasoning as the permissions. */
    public val networkSubscriptions: Set<SqId> = emptySet(),

    /** Off by default and marked in the UI. For anything not ready to be trusted. */
    public val isExperimental: Boolean = false,

    /** Whether the feature starts enabled the first time it is seen. */
    public val enabledByDefault: Boolean = true,
) {
    public val owner: OwnerId = OwnerId(id)

    init {
        require(displayName.isNotBlank()) { "Feature $id has no display name" }
        require(id !in dependencies) { "Feature $id depends on itself" }
    }
}

/** Top-level grouping, for the configuration screen and the inspector. */
public enum class FeatureCategory(public val displayName: String) {
    /** Rendering, animations, cinematics, cosmetics. */
    VISUALS("Visuals"),

    /** HUD elements and overlays. */
    HUD("HUD"),

    /** Chat handling and messages. */
    CHAT("Chat"),

    /** Progression: achievements, titles, currency, stats. */
    PROGRESSION("Progression"),

    /** Friends, parties, pings, waypoints, debts. */
    SOCIAL("Social"),

    /** Quality-of-life helpers for specific SkyBlock activities. */
    UTILITY("Utility"),

    /** Sounds and the soundboard. */
    AUDIO("Audio"),

    /** Diagnostics and developer tooling. */
    DEVELOPER("Developer"),
}

/**
 * A unit of behaviour that can be turned on and off at runtime.
 *
 * A feature receives a [FeatureContext] when it starts and registers everything through
 * it. It is given no way to register anything else: there is no global bus, no global
 * scheduler and no static command table, so "registered something the registry does not
 * know about" is not a mistake a feature is able to make.
 *
 * [onEnable] may be called more than once over a session, with [onDisable] in between.
 * Hold no state across the pair that the context did not give you.
 */
public interface Feature {

    public val descriptor: FeatureDescriptor

    /** Registers listeners, jobs and commands. Everything registered is torn down on disable. */
    public fun onEnable(context: FeatureContext)

    /**
     * Optional cleanup for state the context does not own.
     *
     * Almost always empty. Anything registered through the context is already gone by
     * the time this returns — this is for a cache a feature chose to keep itself, which
     * is usually a sign the state belongs in a repository instead.
     */
    public fun onDisable() {}
}

/**
 * A configuration section owned by a feature.
 *
 * A reference rather than the settings themselves: the configuration framework lives in
 * its own module and the platform does not depend on it, so what crosses the boundary is
 * an identifier the config layer resolves. Keeps the two frameworks independently
 * replaceable, which is the whole reason they are separate modules.
 */
public interface FeatureConfigSection {
    public val sectionId: SqId
}
