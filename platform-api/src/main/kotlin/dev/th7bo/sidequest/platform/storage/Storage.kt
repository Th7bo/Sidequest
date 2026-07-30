package dev.th7bo.sidequest.platform.storage

import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.skyblock.SkyBlockProfile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/**
 * Where a piece of stored data belongs.
 *
 * The distinction is not filing tidiness, it is correctness. A profile's data is not the account's:
 * an Ironman profile's progression, coin count and inventory have nothing to do with the main
 * profile's, and a feature that stored them together would show one profile's numbers on another.
 * Making the scope part of getting a repository at all means a feature has to decide, once, rather
 * than getting it wrong per write.
 */
public sealed interface StorageScope {

    /** A path fragment. Stable, because it is a directory name on a real disk. */
    public val path: String

    /** One copy for the installation, whoever is logged in. Keybinds, window sizes, developer flags. */
    public data object Global : StorageScope {
        override val path: String get() = "global"
    }

    /**
     * One copy per Minecraft account.
     *
     * Keyed on UUID, like everything else. An account's friend list and cosmetic loadout follow the
     * account across profiles.
     */
    public data class Account(public val playerId: PlayerId) : StorageScope {
        override val path: String get() = "accounts/${playerId.value}"
    }

    /**
     * One copy per SkyBlock profile, per account.
     *
     * Both parts are needed. A profile name is only unique within an account — two people can both
     * have a "Mango" — so keying on the name alone would merge two players' data the moment the
     * files were ever shared or synced.
     */
    public data class Profile(
        public val playerId: PlayerId,
        public val profile: SkyBlockProfile,
    ) : StorageScope {
        override val path: String get() = "accounts/${playerId.value}/profiles/${profile.name.lowercase()}"
    }

    /**
     * Data that may be thrown away without consequence.
     *
     * Held apart from everything else so it *can* be thrown away: a cache directory that can be
     * deleted wholesale is a cache, and one mixed in with real data is a liability.
     */
    public data object Cache : StorageScope {
        override val path: String get() = "cache"
    }
}

/**
 * Typed, durable storage for one thing.
 *
 * **Features must not read or write JSON.** They get one of these, typed to their own data class,
 * and never see a file. That rule is what stops eleven features each inventing a slightly different
 * atomic-write, each getting the corruption case slightly wrong, and each losing a different user's
 * data on a crash.
 *
 * Every method suspends. Storage is IO, IO is slow, and the client thread is the one place it must
 * never happen — a save that stutters the game is a save that gets disabled.
 */
public interface Repository<T : Any> {

    public val id: SqId

    public val scope: StorageScope

    /**
     * Reads the stored value, or the default when there is none.
     *
     * Never throws for a missing, corrupt or unmigratable file. All three are conditions the caller
     * can do nothing about and the user can: the report says what happened, the value is usable, and
     * the corrupt file is kept.
     */
    public suspend fun load(): StoredValue<T>

    /** Writes [value] atomically. A crash mid-write leaves the previous file intact. */
    public suspend fun save(value: T)

    /**
     * Reads, transforms and writes in one go.
     *
     * The form almost everything should use. Load-then-save from two places races and loses one of
     * the writes; this serialises per repository so it cannot.
     */
    public suspend fun update(transform: (T) -> T): T

    /** Removes the stored data. Returns false when there was none. */
    public suspend fun delete(): Boolean
}

/** A loaded value, and what happened on the way. */
public data class StoredValue<T : Any>(
    public val value: T,
    public val report: StorageReport,
)

/**
 * What a load ran into.
 *
 * Reported rather than thrown, throughout. A feature cannot handle "the file was corrupt" in any
 * useful way, and the user can — but only if somebody tells them, which is what this is for.
 */
public data class StorageReport(
    /** No file at all. A first run, not a failure. */
    public val wasEmpty: Boolean = false,
    /** Where the unreadable file was kept, when one was. */
    public val quarantinedAt: String? = null,
    /** True when the value came from the backup because the live file was unusable. */
    public val recoveredFromBackup: Boolean = false,
    /** Migrations applied, oldest first. */
    public val migrationsApplied: List<String> = emptyList(),
    /** Why the loaded value was rejected, when it was. The default was used instead. */
    public val validationFailure: String? = null,
) {

    /** Whether the load was uneventful. */
    public val isClean: Boolean
        get() = quarantinedAt == null && !recoveredFromBackup && validationFailure == null

    /** Whether anything happened that a user should be told about. */
    public val isWorthReporting: Boolean get() = !isClean

    override fun toString(): String = buildString {
        append(if (wasEmpty) "empty" else "loaded")
        quarantinedAt?.let { append(", unreadable file kept at $it") }
        if (recoveredFromBackup) append(", recovered from backup")
        if (migrationsApplied.isNotEmpty()) append(", migrated: ${migrationsApplied.joinToString()}")
        validationFailure?.let { append(", rejected: $it") }
    }

    public companion object {
        public val Empty: StorageReport = StorageReport(wasEmpty = true)
        public val Clean: StorageReport = StorageReport()
    }
}

/**
 * Upgrades a stored document one version.
 *
 * A chain, applied in order, so a file from any past version reaches the present by composition
 * rather than by a special case per starting version — which is the design that stops being
 * maintainable at about the third schema change.
 *
 * Works on the raw [JsonObject] and not on the typed value, because the typed value is the *new*
 * shape and the whole problem is that the file is in the old one.
 */
public interface StorageMigration {

    public val fromVersion: Int

    public val toVersion: Int get() = fromVersion + 1

    /** Named in the report, so a user's log says what was changed. */
    public val description: String

    /** Must not throw for a document it declares it can read. */
    public fun migrate(document: JsonObject): JsonObject
}

/**
 * Hands out repositories.
 *
 * A feature asks for one by id and scope and gets the same instance back for the same pair, which is
 * what makes [Repository.update] able to serialise writes: two features holding two objects for one
 * file could not.
 */
public interface StorageProvider {

    /**
     * A repository for [T].
     *
     * @param id stable, and part of the file name. Renaming one is a migration, not a refactor.
     * @param default used for a first run and for a value that fails [validate].
     * @param validate returns a reason to reject, or null to accept. For values that parse and are
     *   still wrong — a negative coin count, a debt owed by nobody. Parsing is not validation.
     */
    public fun <T : Any> repository(
        id: SqId,
        scope: StorageScope,
        serializer: KSerializer<T>,
        default: () -> T,
        schemaVersion: Int = 1,
        migrations: List<StorageMigration> = emptyList(),
        validate: (T) -> String? = { null },
    ): Repository<T>

    /**
     * An append-only durable queue, for work that must survive a restart.
     *
     * See [OfflineQueue]. Separate from [repository] because the access pattern is not
     * read-modify-write and treating it as one would rewrite the whole file per append.
     */
    public fun <T : Any> queue(
        id: SqId,
        scope: StorageScope,
        serializer: KSerializer<T>,
        capacity: Int = OfflineQueue.DEFAULT_CAPACITY,
    ): OfflineQueue<T>

    /** Waits for every pending write. Called on shutdown, so nothing is lost on the way out. */
    public suspend fun flush()
}
