package dev.th7bo.sidequest.platform.core.profile

import java.net.URI

/**
 * Where the optional external-browser fallback points.
 *
 * Pure string work, kept away from Minecraft so it can be tested — which matters more here than the size of
 * the file suggests. This is the one place in the mod that turns something a **person typed** into a URL the
 * **operating system opens**. The native viewer does not consume URLs at all.
 *
 * Two separate jobs, and they fail in opposite directions:
 *
 * - [statsUrl] **refuses** input it does not recognise rather than escaping it. A Minecraft username is
 *   sixteen characters of `[A-Za-z0-9_]` and nothing else, so anything else is a mistake to reject, not a
 *   string to encode. Escaping invites the question "did I escape it correctly"; refusing does not.
 * - [isAllowed] checks the address again at the operating-system boundary. It is strict about hosts because
 *   every classic URL trick is an attempt to make a hostile host read as a friendly one.
 */
public object SkyCryptUrls {

    /** SkyCrypt's own host. */
    public const val HOST: String = "sky.shiiyu.moe"

    /**
     * Everything under this is SkyCrypt too.
     *
     * The stats page pulls images from `cms.shiiyu.moe`, and following a link to another host on the same
     * domain is still staying inside the site the viewer is for. The leading dot is load-bearing: without
     * it, `notshiiyu.moe` would pass, and so would anybody who registered it.
     */
    private const val DOMAIN_SUFFIX: String = ".shiiyu.moe"

    /**
     * A Minecraft username.
     *
     * Deliberately without `^`/`$`: [Regex.matches] already requires the whole input to match, and in Java's
     * flavour a trailing `$` also matches *before a final newline* — so anchors here would be the more
     * permissive spelling, which is the opposite of what they look like.
     */
    private val USERNAME = Regex("[A-Za-z0-9_]{1,16}")

    /** A profile: either one of Hypixel's fruit names, or a profile UUID. */
    private val PROFILE = Regex("[A-Za-z0-9_-]{1,36}")

    public fun isValidUsername(name: String): Boolean = USERNAME.matches(name)

    public fun isValidProfile(name: String): Boolean = PROFILE.matches(name)

    /**
     * The stats page for a player, or null when the input is not something to build a URL from.
     *
     * Null rather than a best effort. A caller that gets null tells the player their input was not a name,
     * which is both true and useful; a caller that got a mangled URL would open a browser on a 404.
     */
    public fun statsUrl(username: String, profile: String? = null): String? {
        if (!isValidUsername(username)) return null
        if (profile != null && !isValidProfile(profile)) return null
        val base = "https://$HOST/stats/$username"
        return if (profile == null) base else "$base/$profile"
    }

    /**
     * Whether the leash should have an opinion about [url] at all.
     *
     * A browser's current address is not always a page: `about:blank` before the first load, and a
     * `chrome-error://` placeholder when a request fails. Snapping back on those would fight the browser
     * during ordinary loading, so only real web addresses are judged.
     */
    public fun isJudgeable(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /**
     * Whether the browser may stay on [url].
     *
     * **Host is compared after parsing, never by looking for a substring.** Every entry in the test file is
     * a way of writing a hostile address that contains the friendly one:
     * `https://sky.shiiyu.moe@evil.example` is a request to `evil.example` with a username, and
     * `https://sky.shiiyu.moe.evil.example` is a subdomain of somebody else's domain. Both contain the
     * string `sky.shiiyu.moe`; neither is SkyCrypt.
     *
     * Plain `http` is refused as well as foreign hosts. SkyCrypt is served over TLS, so an unencrypted
     * address is either a downgrade or a redirect somewhere that is not SkyCrypt.
     */
    public fun isAllowed(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == HOST || host.endsWith(DOMAIN_SUFFIX)
    }

    /** The host of an `https` URL, lowercased, or null when it is not one. */
    private fun hostOf(url: String): String? {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("https", ignoreCase = true)) return null
        // `getHost` is null for an authority Java cannot parse as a hostname, which is itself a refusal.
        return parsed.host?.lowercase()
    }
}
