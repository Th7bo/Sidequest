package dev.th7bo.sidequest.platform.core.asset

import dev.th7bo.sidequest.platform.asset.Asset
import dev.th7bo.sidequest.platform.asset.AssetId
import dev.th7bo.sidequest.platform.asset.AssetKind
import dev.th7bo.sidequest.platform.asset.AssetRejection
import dev.th7bo.sidequest.platform.asset.MediaType
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Decides whether some bytes are allowed to become an asset.
 *
 * Pulled out of the manager and made pure so it can be tested exhaustively against hostile input, which is
 * the only way anybody is going to write a test for a truncated JPEG or a PNG claiming to be 40,000 pixels
 * across. It knows nothing about caches, transports or coroutines.
 *
 * The order of the checks is deliberate and is the cheap-and-decisive-first rule the rest of this platform
 * uses: size, then what the bytes actually are, then the format's own header, then the hash. Each step is
 * more expensive than the last and each one that fails saves the ones after it.
 */
internal object AssetPolicy {

    /**
     * A lenient parser, on purpose.
     *
     * This is validating that a data asset is *well-formed JSON*, not that it matches any particular schema —
     * the schema belongs to whatever consumes it, and rejecting unknown keys here would mean an older client
     * refuses a theme that gained a field.
     */
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /**
     * Checks [bytes] and produces an asset, or says why not.
     *
     * @param declaredType the server's `Content-Type`, which is checked for agreement but never trusted on
     *   its own — see [AssetRejection.TypeMismatch].
     */
    fun accept(
        id: AssetId,
        kind: AssetKind,
        bytes: ByteArray,
        declaredType: String? = null,
    ): Result<Asset, AssetRejection> {
        // 1. Size. Cheapest possible, and the one an oversized file fails.
        if (bytes.size > kind.maxBytes) {
            return Result.Rejected(AssetRejection.TooLarge(bytes.size.toLong(), kind.maxBytes))
        }

        // 2. What the bytes are. JSON has no signature, so a data kind is identified by parsing instead —
        //    which is the stronger test anyway, and is done below rather than here.
        val sniffed = MediaType.sniff(bytes)
        val actual = sniffed ?: MediaType.JSON.takeIf { MediaType.JSON in kind.accepts && isJson(bytes) }
        if (actual == null || actual !in kind.accepts) {
            return Result.Rejected(AssetRejection.WrongType(kind, sniffed))
        }

        // 3. Agreement with the server's claim.
        //
        //    A mismatch is refused rather than shrugged off. The bytes are what would be used, so a
        //    disagreement never changes what happens next — but it means the backend is either misconfigured
        //    or serving something it did not mean to, and neither should pass quietly.
        val declared = MediaType.ofHeader(declaredType)
        if (declaredType != null && declared != actual) {
            return Result.Rejected(AssetRejection.TypeMismatch(declared, actual))
        }

        // 4. The format's own header. For images this is the decompression-bomb gate: dimensions are read
        //    from the header and refused *before* anything allocates pixels for them.
        var size: dev.th7bo.sidequest.platform.asset.ImageSize? = null
        if (kind.isImage) {
            size = ImageHeaders.read(bytes)
                ?: return Result.Rejected(AssetRejection.Unreadable("the ${actual.mime} header does not parse"))
            if (size.width > kind.maxDimension || size.height > kind.maxDimension) {
                return Result.Rejected(AssetRejection.TooManyPixels(size, kind.maxDimension))
            }
        }
        if (actual == MediaType.OGG && AudioHeaders.ogg(bytes) == null) {
            return Result.Rejected(
                AssetRejection.Unreadable("an Ogg container, but not of Vorbis or Opus audio"),
            )
        }

        // 5. The hash, last because it is the only check that reads every byte.
        //
        //    This is what makes the id mean something. Up to here the file is merely *a* well-formed asset of
        //    the right kind; this is what makes it the one that was asked for.
        val actualId = hash(bytes)
        if (actualId != id) {
            return Result.Rejected(AssetRejection.HashMismatch(id, actualId))
        }

        return Result.Accepted(Asset(id = id, kind = kind, mediaType = actual, bytes = bytes, size = size))
    }

    /** The SHA-256 of [bytes], as an [AssetId]. The one place an id is minted. */
    fun hash(bytes: ByteArray): AssetId {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return AssetId(digest.joinToString("") { "%02x".format(it) })
    }

    private fun isJson(bytes: ByteArray): Boolean = try {
        json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        true
    } catch (_: Exception) {
        // Deliberately broad. This is a predicate over bytes off the network, and every way a parse can fail
        // — malformed, wrong charset, absurdly nested — has the same answer: it is not JSON.
        false
    }

    /** A tiny either, so the policy can return a reason without an exception or a nullable pair. */
    sealed interface Result<out T, out E> {
        data class Accepted<T>(val value: T) : Result<T, Nothing>
        data class Rejected<E>(val reason: E) : Result<Nothing, E>
    }
}

/**
 * Turns an asset id into the one URL it may be fetched from.
 *
 * **This is where "never render arbitrary user-provided URLs" is actually enforced.** The manager's API takes
 * no URL, so the only way one enters the mod is here, and here it is built rather than received: the base
 * comes from the configured backend and the path is a hash that has already been validated as 64 hex
 * characters. There is no input to this that a remote party controls.
 *
 * The check on the way out is belt and braces. It costs nothing and it means a future change that starts
 * accepting a path from somewhere still cannot produce a URL pointing at another host.
 */
internal class AssetUrls(private val baseUrl: () -> String?) {

    /** The URL for [id], or null when there is no backend to fetch from. */
    fun urlFor(id: AssetId): String? {
        val base = baseUrl()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val url = "$base$PATH${id.value}"
        return url.takeIf { isUnder(base, it) }
    }

    /**
     * Whether [url] is really under [base].
     *
     * Compared on the origin — scheme, host and port — rather than as a string prefix. A prefix test passes
     * `https://sq.api.th7bo.dev.evil.example/…` against a base of `https://sq.api.th7bo.dev`, which is the
     * exact mistake this method exists to not make.
     */
    private fun isUnder(base: String, url: String): Boolean {
        val baseOrigin = originOf(base) ?: return false
        val urlOrigin = originOf(url) ?: return false
        return baseOrigin == urlOrigin
    }

    private fun originOf(url: String): String? {
        val scheme = url.substringBefore("://", missingDelimiterValue = "")
        if (scheme.isEmpty()) return null
        val rest = url.substringAfter("://")
        // Stops at whichever comes first, so neither a path nor a query nor a fragment can smuggle a host
        // past the comparison — `https://good.example@evil.example/` is caught by the `@` too.
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.isEmpty() || '@' in authority) return null
        return "$scheme://${authority.lowercase()}"
    }

    private companion object {
        /** Where the backend serves assets. Content-addressed, so the path is the hash and nothing else. */
        const val PATH = "/v1/assets/"
    }
}
