package dev.th7bo.sidequest.platform.feature

import dev.th7bo.sidequest.platform.id.SqId

/**
 * Where features are declared, enabled and torn down.
 *
 * Registration is separate from enabling: everything is declared at startup so the mod
 * knows its own shape — dependencies, permissions, config sections — and only then is
 * anything started. That order is what makes a dependency cycle a startup error rather
 * than an enable-time surprise, and what lets a settings screen list a feature the user
 * has switched off.
 */
public interface FeatureRegistry {

    /**
     * Declares [feature]. Does not start it.
     *
     * @throws DuplicateFeatureException if the id is taken.
     */
    public fun register(feature: Feature)

    /** Everything declared, in registration order. */
    public fun all(): List<FeatureHandle>

    public operator fun get(id: SqId): FeatureHandle?

    /**
     * Enables [id] and, first, anything it depends on.
     *
     * @return why it did not start, or null if it did.
     */
    public fun enable(id: SqId): FeatureRefusal?

    /**
     * Disables [id] and anything that depends on it.
     *
     * Dependents go first, so a feature is never left running against a dependency that
     * has already been torn down.
     */
    public fun disable(id: SqId)

    /**
     * Enables everything declared that should be on, in dependency order.
     *
     * Called once after registration. A feature that refuses is reported and skipped —
     * one broken feature must not stop the rest of the mod from starting.
     */
    public fun enableAll(): List<FeatureRefusal>

    /** Disables everything, dependents first. For shutdown and for tests. */
    public fun disableAll()
}

/** A declared feature and its current state. */
public interface FeatureHandle {
    public val descriptor: FeatureDescriptor
    public val isEnabled: Boolean

    /** Why it is not enabled, when it was asked to be and refused. */
    public val refusal: FeatureRefusal?
}

/**
 * Why a feature did not start.
 *
 * A value rather than an exception because most of these are normal: a feature for a
 * newer Minecraft version is not an error, it is information for the settings screen.
 */
public data class FeatureRefusal(
    public val featureId: SqId,
    public val reason: Reason,
    public val detail: String,
) {
    public enum class Reason {
        /** The running Minecraft version is outside the feature's supported range. */
        UNSUPPORTED_VERSION,

        /** A declared dependency is not registered. */
        MISSING_DEPENDENCY,

        /** A declared dependency is registered but refused to enable. */
        DEPENDENCY_REFUSED,

        /** Switched off by the user or by config. */
        DISABLED_BY_CONFIG,

        /** `onEnable` threw. The feature is left disabled and its registrations undone. */
        FAILED_TO_ENABLE,
    }

    override fun toString(): String = "$featureId: $reason — $detail"
}

public class DuplicateFeatureException(
    public val id: SqId,
) : IllegalStateException("Feature $id is already registered")

/** Feature declarations form a cycle, so no valid start order exists. */
public class FeatureCycleException(
    public val cycle: List<SqId>,
) : IllegalStateException("Feature dependency cycle: ${cycle.joinToString(" -> ")}")
