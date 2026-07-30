package dev.th7bo.sidequest.platform.asset

/**
 * Fetches bytes.
 *
 * Separate from [dev.th7bo.sidequest.platform.backend.HttpTransport] rather than an addition to it, because
 * that one speaks JSON — a `String` body and a `String` response — and widening it to carry binary would put
 * a nullable `ByteArray` on every API call in the mod to serve this one caller.
 *
 * The important part of the contract is [maxBytes]. An implementation **must stop reading** once it has that
 * many bytes and report [AssetFetch.TooLarge], rather than buffering the whole response and letting the
 * caller check afterwards. Checking afterwards means a client can be made to allocate as much memory as
 * somebody's upload feels like, which is the entire problem a size limit exists to solve.
 */
public interface AssetTransport {

    /**
     * Gets [url], reading at most [maxBytes].
     *
     * Must not throw for a network failure — the same reasoning as the JSON transport. A group's server is in
     * somebody's cupboard and being unreachable is routine.
     */
    public suspend fun fetch(url: String, maxBytes: Long): AssetFetch

    public companion object {
        /** A transport that always fails. The default, so an unconfigured mod is merely without assets. */
        public val Unavailable: AssetTransport = object : AssetTransport {
            override suspend fun fetch(url: String, maxBytes: Long): AssetFetch =
                AssetFetch.Failure("no asset transport configured")
        }
    }
}

/** What a fetch produced. */
public sealed interface AssetFetch {

    public data class Body(
        public val bytes: ByteArray,
        /** The `Content-Type` header, if there was one. A claim, never a conclusion. */
        public val declaredType: String? = null,
    ) : AssetFetch {
        // `equals` and `hashCode` are written out because a data class compares a ByteArray by identity, and
        // silently-identity-compared bytes in a test fixture are a bad afternoon.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Body) return false
            return bytes.contentEquals(other.bytes) && declaredType == other.declaredType
        }

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + (declaredType?.hashCode() ?: 0)

        override fun toString(): String = "Body(${bytes.size} bytes, $declaredType)"
    }

    /** The response was, or claimed to be, over the limit. Reported rather than truncated. */
    public data class TooLarge(public val limitBytes: Long, public val declaredBytes: Long? = null) : AssetFetch

    /** No usable response: unreachable, refused, a 404, a 500. */
    public data class Failure(public val reason: String, public val status: Int? = null) : AssetFetch
}

/**
 * Somewhere to keep asset bytes between sessions.
 *
 * Deliberately not a [dev.th7bo.sidequest.platform.storage.Repository]. That one is typed, JSON, versioned and
 * migratable, all of which is exactly right for a config file and all of which is wrong for a blob: an asset
 * has no schema to migrate, and base64-ing a 2 MiB Ogg through a JSON document to reuse the machinery would
 * cost a third again in size for nothing.
 *
 * Keys are asset ids, so an implementation can use the id as the file name and the store needs no index of
 * its own. Nothing here validates — the manager does that, on the way in and again on the way out.
 */
public interface AssetStore {

    /** The bytes, or null when nothing is stored under [id]. Must not throw for an unreadable file. */
    public suspend fun read(id: AssetId): ByteArray?

    public suspend fun write(id: AssetId, bytes: ByteArray)

    public suspend fun delete(id: AssetId)

    /** Every id held, with its size. Used to rebuild the eviction order at startup. */
    public suspend fun list(): List<StoredAsset>

    public suspend fun clear()

    public companion object {
        /** A store that keeps nothing. Every load then goes to the network, which still works. */
        public val None: AssetStore = object : AssetStore {
            override suspend fun read(id: AssetId): ByteArray? = null
            override suspend fun write(id: AssetId, bytes: ByteArray) {}
            override suspend fun delete(id: AssetId) {}
            override suspend fun list(): List<StoredAsset> = emptyList()
            override suspend fun clear() {}
        }
    }
}

/** One thing on disk. */
public data class StoredAsset(
    public val id: AssetId,
    public val bytes: Long,
    /**
     * When it was last read, as far as the store knows.
     *
     * Best-effort: a file system's access time is often disabled, so this may be the write time instead. The
     * eviction order is a heuristic and does not need better than that — being wrong about it costs one
     * re-download.
     */
    public val lastUsedMillis: Long,
)
