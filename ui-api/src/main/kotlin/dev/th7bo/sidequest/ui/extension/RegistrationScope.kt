package dev.th7bo.sidequest.ui.extension

import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.Disposable
import dev.th7bo.sidequest.ui.state.DisposableScope

/**
 * The owner token for everything a module contributes at runtime.
 *
 * Components, renderers, validators, serializers, icons, HUD definitions, search
 * providers, notification types, world overlays and context-menu actions all register
 * through a scope. Disposing the scope removes every one of them and drops the
 * references — which is what stops an unloaded module's lambdas from pinning its
 * classloader.
 *
 * ```
 * val scope = RegistrationScope(UiId.of("sidequest", "mining"))
 * registry.register(scope, hudId, miningXpHud)
 * ...
 * scope.dispose()   // the HUD is gone, along with its listeners and cached renderers
 * ```
 */
public class RegistrationScope(
    /** Identifies the owning module. Reported in duplicate-registration errors. */
    public val owner: UiId,
) : DisposableScope() {

    override fun toString(): String = "RegistrationScope($owner${if (isDisposed) ", disposed" else ""})"
}

/** A live registration. Disposing it removes just this entry. */
public interface Registration : Disposable {
    public val owner: UiId
}

/**
 * A registry whose entries are owned by a [RegistrationScope].
 *
 * Entries are indexed by owner as well as by key, so "release everything owned by X"
 * is a map lookup rather than a scan of every registration in the process.
 */
public open class OwnedRegistry<K : Any, V : Any>(
    /** Used in error messages, e.g. `"component"` or `"HUD definition"`. */
    private val entryKind: String,
) {

    private val entries = LinkedHashMap<K, Entry<K, V>>()
    private val byOwner = LinkedHashMap<UiId, MutableSet<K>>()

    /** Live entry count. */
    public val size: Int get() = entries.size

    /** All registered keys, in registration order. */
    public val keys: Set<K> get() = entries.keys

    public operator fun get(key: K): V? = entries[key]?.value

    public operator fun contains(key: K): Boolean = entries.containsKey(key)

    /** The module that registered [key], if any. */
    public fun ownerOf(key: K): UiId? = entries[key]?.owner

    /** Keys contributed by [owner]. */
    public fun keysOwnedBy(owner: UiId): Set<K> = byOwner[owner].orEmpty().toSet()

    /**
     * Registers [value] under [key], owned by [scope].
     *
     * @throws IllegalStateException if [scope] is already disposed.
     * @throws DuplicateRegistrationException if [key] is taken, naming both owners so
     *   the conflict can be resolved without guessing.
     */
    public fun register(scope: RegistrationScope, key: K, value: V): Registration {
        check(!scope.isDisposed) { "Cannot register $entryKind '$key' into a disposed scope" }
        val existing = entries[key]
        if (existing != null) {
            throw DuplicateRegistrationException(entryKind, key.toString(), existing.owner, scope.owner)
        }

        val entry = Entry(key, value, scope.owner, this)
        entries[key] = entry
        byOwner.getOrPut(scope.owner) { LinkedHashSet() }.add(key)
        scope.register(entry)
        return entry
    }

    /** Called by an [Entry] when it is disposed. */
    internal fun remove(key: K, owner: UiId) {
        entries.remove(key)
        byOwner[owner]?.let { keysForOwner ->
            keysForOwner.remove(key)
            if (keysForOwner.isEmpty()) byOwner.remove(owner)
        }
    }

    /** Snapshot of the current entries. Safe to iterate while registrations change. */
    public fun snapshot(): Map<K, V> = entries.mapValues { it.value.value }

    private class Entry<K : Any, V : Any>(
        private val key: K,
        val value: V,
        override val owner: UiId,
        private var registry: OwnedRegistry<K, V>?,
    ) : Registration {

        override fun dispose() {
            registry?.remove(key, owner)
            registry = null
        }
    }
}

/** Two modules claimed the same identifier. */
public class DuplicateRegistrationException(
    public val entryKind: String,
    public val key: String,
    public val existingOwner: UiId,
    public val attemptedOwner: UiId,
) : IllegalStateException(
    "Duplicate $entryKind '$key': already registered by '$existingOwner', " +
        "'$attemptedOwner' tried to register it again",
)
