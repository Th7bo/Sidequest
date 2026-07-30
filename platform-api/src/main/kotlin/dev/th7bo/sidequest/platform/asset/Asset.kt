package dev.th7bo.sidequest.platform.asset

import kotlinx.serialization.Serializable

/**
 * An asset's identity, which is the SHA-256 of its bytes.
 *
 * **Content-addressed, and that decision carries most of this package.** Three requirements fall out of it
 * rather than needing machinery of their own:
 *
 * - *Immutable versions.* Different bytes are a different id. Nobody can change what an id means, so a cached
 *   copy can never be stale and there is no invalidation to get wrong. Editing a cape produces a new asset.
 * - *Integrity.* The id is the checksum. Whatever a download claims to be, it is only accepted if it hashes
 *   back to the id that was asked for, so a corrupted transfer or a substituted file is caught by the same
 *   check, without a signature scheme.
 * - *Deduplication.* Two people using the same icon store it once.
 *
 * A hex string rather than bytes because it is a file name, a map key and a log line far more often than it is
 * arithmetic.
 */
@Serializable
@JvmInline
public value class AssetId(public val value: String) {

    init {
        require(value.length == LENGTH) { "An asset id is a $LENGTH-character SHA-256, got ${value.length}" }
        require(value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "An asset id is lowercase hex, got '$value'"
        }
    }

    /**
     * The first few characters, for logs.
     *
     * Sixty-four hex characters in a log line hide everything around them, and nothing in this mod
     * distinguishes assets by their tail.
     */
    public val short: String get() = value.take(SHORT_LENGTH)

    override fun toString(): String = short

    public companion object {
        public const val LENGTH: Int = 64
        private const val SHORT_LENGTH = 12

        /** Null for anything that is not a well-formed id, so untrusted input has a total parse. */
        public fun parseOrNull(value: String): AssetId? =
            if (value.length == LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }) AssetId(value) else null
    }
}

/**
 * A file format this mod is prepared to handle.
 *
 * The [magic] is what actually decides. A server's `Content-Type` is a claim, and the bytes are the fact — so
 * the declared type is checked only to notice a misconfigured backend, and the sniffed type is what policy
 * runs on. Nothing is dispatched on a file extension anywhere in this package.
 */
public enum class MediaType(
    public val mime: String,
    /** The leading bytes every file of this type starts with. Empty when the format has no signature. */
    public val magic: List<Int>,
) {
    PNG("image/png", listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),

    /** Start of Image, then a marker. The third byte varies, so only the first two are a signature. */
    JPEG("image/jpeg", listOf(0xFF, 0xD8)),

    /** Vorbis or Opus in an Ogg container. Both begin "OggS". */
    OGG("audio/ogg", listOf(0x4F, 0x67, 0x67, 0x53)),

    /**
     * Themes and other structured data.
     *
     * The one type with no signature, because JSON has none. It is validated by parsing instead, which is a
     * stronger check than a magic number would have been.
     */
    JSON("application/json", emptyList()),
    ;

    /** Whether [bytes] begins with this type's signature. False for a file shorter than the signature. */
    public fun matches(bytes: ByteArray): Boolean {
        if (magic.isEmpty()) return false
        if (bytes.size < magic.size) return false
        return magic.withIndex().all { (index, expected) -> (bytes[index].toInt() and 0xFF) == expected }
    }

    public companion object {

        /** The type [bytes] actually is, or null when it is nothing recognised. */
        public fun sniff(bytes: ByteArray): MediaType? = entries.firstOrNull { it.matches(bytes) }

        /** Matches a `Content-Type` header, ignoring any `; charset=` and the like. */
        public fun ofHeader(header: String?): MediaType? {
            val bare = header?.substringBefore(';')?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.mime == bare }
        }
    }
}

// Enum constants are constructed before the enum's own companion is initialised, so these limits cannot live
// in `AssetKind.Companion` — they are read while `AssetKind.ICON` is being built. Top-level and private.
private const val KIB = 1024L
private const val MIB = 1024L * 1024L

/**
 * What an asset is for, and therefore what it is allowed to be.
 *
 * **Every limit lives on the kind, and a caller always names one.** There is no "load this asset" that has not
 * already said what it expects, because a limit that a caller can omit is a limit that a caller will omit —
 * and the first thing anyone would want to skip is the size cap on somebody else's upload.
 *
 * The numbers are sized for a group of friends sharing cosmetics, not for a CDN. They are deliberately mean:
 * raising one later is a line of code, and shipping a 40 MiB ceiling that nothing needs means somebody's disk
 * fills before anybody notices.
 */
public enum class AssetKind(
    public val maxBytes: Long,
    public val accepts: Set<MediaType>,
    /**
     * The longest allowed side, in pixels. Zero for a kind that is not an image.
     *
     * Checked from the file header, before anything decodes it. That ordering is the point: a 40,000-square
     * PNG is a few kilobytes on disk and six gigabytes decoded, so a size limit alone does not protect the
     * client — the dimensions have to be read and refused without allocating the pixels.
     */
    public val maxDimension: Int = 0,
) {
    /** UI icons, particles, badges. Small enough to keep many resident. */
    ICON(maxBytes = 64 * KIB, accepts = setOf(MediaType.PNG), maxDimension = 256),

    /**
     * Skins and capes.
     *
     * PNG only, because that is what Minecraft's own skin pipeline takes. 512 covers an HD skin with room
     * over; the vanilla sizes are 64×64 and 64×32.
     */
    SKIN(maxBytes = 256 * KIB, accepts = setOf(MediaType.PNG), maxDimension = 512),

    /** Profile frames, evidence thumbnails, cinematic stills, title backgrounds. */
    IMAGE(maxBytes = 2 * MIB, accepts = setOf(MediaType.PNG, MediaType.JPEG), maxDimension = 2048),

    /** Custom sounds. Ogg only — Minecraft's audio pipeline plays nothing else. */
    SOUND(maxBytes = 4 * MIB, accepts = setOf(MediaType.OGG)),

    /** Themes and other structured documents the group shares. */
    DATA(maxBytes = 256 * KIB, accepts = setOf(MediaType.JSON)),
    ;

    public val isImage: Boolean get() = maxDimension > 0

    /** The most pixels a file of this kind may decode to, which is what memory is actually spent on. */
    public val maxPixels: Long get() = maxDimension.toLong() * maxDimension.toLong()
}

/** How big an image is, read from its header rather than by decoding it. */
public data class ImageSize(public val width: Int, public val height: Int) {
    public val pixels: Long get() = width.toLong() * height.toLong()
    override fun toString(): String = "${width}x$height"
}

/**
 * An asset that passed every check and is ready to use.
 *
 * Holding the bytes rather than a path, because the consumer is a texture upload or a sound buffer and both
 * want bytes. The cache decides how long one of these stays reachable.
 */
public class Asset(
    public val id: AssetId,
    public val kind: AssetKind,
    public val mediaType: MediaType,
    public val bytes: ByteArray,
    /** Present for image kinds, null otherwise. */
    public val size: ImageSize? = null,
) {
    public val byteCount: Int get() = bytes.size

    override fun toString(): String =
        "Asset($id, $kind, ${mediaType.mime}, ${bytes.size} bytes${size?.let { ", $it" } ?: ""})"
}

/**
 * Why an asset was refused.
 *
 * The same "say why" as everywhere else in this platform, and here it matters more than usual: an asset that
 * silently fails to appear looks exactly like a cosmetic that is broken, and the person who uploaded it is the
 * only one who can fix it. The reason has to survive as far as a log line they can be shown.
 */
public sealed interface AssetRejection {

    /** A sentence for a human. */
    public val explanation: String

    /**
     * Whether asking again could ever help.
     *
     * A network blip is worth retrying and a file that is the wrong shape is not, and conflating the two
     * gives you a client that re-downloads a rejected file forever.
     */
    public val isPermanent: Boolean get() = true

    public data class TooLarge(val actualBytes: Long, val limitBytes: Long) : AssetRejection {
        override val explanation: String get() = "$actualBytes bytes exceeds the $limitBytes limit"
    }

    public data class WrongType(val kind: AssetKind, val found: MediaType?) : AssetRejection {
        override val explanation: String
            get() = "$kind accepts ${kind.accepts.joinToString { it.mime }}, the bytes are " +
                (found?.mime ?: "nothing recognised")
    }

    /** The server said one thing and the bytes are another. Suspicious rather than merely wrong. */
    public data class TypeMismatch(val declared: MediaType?, val actual: MediaType?) : AssetRejection {
        override val explanation: String
            get() = "served as ${declared?.mime ?: "no type"} but the bytes are ${actual?.mime ?: "unrecognised"}"
    }

    public data class TooManyPixels(val size: ImageSize, val limit: Int) : AssetRejection {
        override val explanation: String get() = "$size exceeds the ${limit}px limit on either side"
    }

    /** A header that does not parse. Truncated, or not the format it claims. */
    public data class Unreadable(val detail: String) : AssetRejection {
        override val explanation: String get() = "could not be read: $detail"
    }

    /**
     * The bytes do not hash to the id they were fetched for.
     *
     * The one rejection that is a security event rather than a mistake. Everything else means somebody
     * uploaded the wrong file; this means what arrived is not what was asked for.
     */
    public data class HashMismatch(val expected: AssetId, val actual: AssetId) : AssetRejection {
        override val explanation: String get() = "expected $expected, the bytes hash to $actual"
    }

    /** Could not be fetched. The only rejection worth retrying. */
    public data class Unavailable(val reason: String) : AssetRejection {
        override val explanation: String get() = reason
        override val isPermanent: Boolean get() = false
    }

    /** No backend is configured, so there is nowhere an asset could come from. */
    public data object NoSource : AssetRejection {
        override val explanation: String get() = "no backend is configured"
        override val isPermanent: Boolean get() = false
    }
}

/**
 * The result of asking for an asset.
 *
 * Sealed rather than a nullable [Asset], so a caller cannot treat "not here" as "not yet" — the two want
 * different behaviour and the difference is invisible in a null.
 */
public sealed interface AssetResult {

    public data class Ready(public val asset: Asset) : AssetResult

    /** Refused, and it will be refused again. */
    public data class Refused(public val id: AssetId, public val rejection: AssetRejection) : AssetResult

    public companion object {
        /** The asset if it is there, or null. For call sites that genuinely have a fallback in hand. */
        public fun AssetResult.orNull(): Asset? = (this as? Ready)?.asset
    }
}

/** What the cache is holding, for the developer inspector and for tests. */
public data class AssetCacheStats(
    public val entries: Int,
    public val bytes: Long,
    public val budgetBytes: Long,
    /** Assets served without touching the network since the last reset. */
    public val hits: Int,
    public val misses: Int,
    /** Entries dropped to stay inside the budget. */
    public val evictions: Int,
) {
    public val usedFraction: Float get() = if (budgetBytes <= 0) 0f else bytes.toFloat() / budgetBytes
    override fun toString(): String = "$entries entries, $bytes/$budgetBytes bytes, $hits hits, $misses misses"
}

/**
 * Fetches, checks, caches and hands out assets.
 *
 * **No method takes a URL, and that is the security boundary of this package.** The plan's rule is "never
 * render arbitrary user-provided URLs", and the way to keep a rule like that is to make breaking it require a
 * new method rather than a careless argument. An id is a hash; where it is fetched from is decided here,
 * against the configured backend, and a feature cannot influence it.
 *
 * Everything suspends. An asset is a network round trip and a disk read, and neither may happen on the client
 * thread — a cosmetic that stutters the game is a cosmetic that gets turned off.
 */
public interface AssetManager {

    /**
     * The asset, from memory, disk or the network in that order.
     *
     * @param kind what the caller expects. Decides every limit, and is checked against the bytes rather than
     *   trusted — asking for a [AssetKind.SOUND] and receiving a PNG is a refusal, not a reinterpretation.
     */
    public suspend fun load(id: AssetId, kind: AssetKind): AssetResult

    /**
     * The asset if it is already in memory, without any IO.
     *
     * For render paths, which cannot suspend and cannot wait. A null means "not resident", never "does not
     * exist" — the caller draws its fallback this frame and the asset appears on a later one.
     */
    public fun resident(id: AssetId): Asset?

    /**
     * Fetches [ids] ahead of time, ignoring failures.
     *
     * For things that will be needed at a moment when waiting would show: a cosmetic loadout at login, a
     * cinematic's images before it plays. Returns when they are all settled.
     */
    public suspend fun preload(ids: Collection<AssetId>, kind: AssetKind)

    /** Drops everything held in memory, keeping the disk cache. For a low-memory response. */
    public fun releaseMemory()

    /** Deletes the disk cache as well. */
    public suspend fun clear()

    public fun stats(): AssetCacheStats

    public companion object {

        /**
         * A manager that has nothing and fetches nothing.
         *
         * For a platform assembled without a backend, and for tests of everything that merely *holds* one.
         * It refuses rather than returns null, so a caller written against it still has to handle the case
         * where an asset is not there — which is the case that actually happens in production.
         */
        public val None: AssetManager = object : AssetManager {
            override suspend fun load(id: AssetId, kind: AssetKind): AssetResult =
                AssetResult.Refused(id, AssetRejection.NoSource)

            override fun resident(id: AssetId): Asset? = null
            override suspend fun preload(ids: Collection<AssetId>, kind: AssetKind) {}
            override fun releaseMemory() {}
            override suspend fun clear() {}
            override fun stats(): AssetCacheStats = AssetCacheStats(0, 0, 0, 0, 0, 0)
        }
    }
}
