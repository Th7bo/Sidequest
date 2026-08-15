package dev.th7bo.sidequest.platform.core.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two doors into the embedded browser.
 *
 * This file is longer than the code it tests, on purpose. Sidequest is about to contain a real Chromium, and
 * the difference between "a SkyCrypt viewer" and "a web browser somebody can steer" is entirely these two
 * functions. Both halves are here: what a person may type in, and where the page may go afterwards.
 *
 * The host cases are the ones worth reading. Every hostile address below *contains the string*
 * `sky.shiiyu.moe` — that is the whole trick, and it is why the check parses rather than searches.
 */
class SkyCryptUrlsTest {

    // -- what may be typed in -------------------------------------------------

    @Test
    fun `ordinary Minecraft names are accepted`() {
        assertTrue(SkyCryptUrls.isValidUsername("chrooted"))
        assertTrue(SkyCryptUrls.isValidUsername("Th7bo"))
        assertTrue(SkyCryptUrls.isValidUsername("_a_b_c_"))
        assertTrue(SkyCryptUrls.isValidUsername("A".repeat(16)))
    }

    @Test
    fun `a name that is not a Minecraft name is refused`() {
        assertFalse(SkyCryptUrls.isValidUsername(""))
        assertFalse(SkyCryptUrls.isValidUsername("A".repeat(17)))
        assertFalse(SkyCryptUrls.isValidUsername("has space"))
        assertFalse(SkyCryptUrls.isValidUsername("hyphen-ated"))
        assertFalse(SkyCryptUrls.isValidUsername("émile"))
    }

    /**
     * The reason the check exists rather than an escape.
     *
     * Each of these is an attempt to leave the path segment the name was meant to fill.
     */
    @Test
    fun `a name cannot climb out of the path`() {
        assertFalse(SkyCryptUrls.isValidUsername(".."))
        assertFalse(SkyCryptUrls.isValidUsername("../../etc"))
        assertFalse(SkyCryptUrls.isValidUsername("a/b"))
        assertFalse(SkyCryptUrls.isValidUsername("a?x=1"))
        assertFalse(SkyCryptUrls.isValidUsername("a#frag"))
        assertFalse(SkyCryptUrls.isValidUsername("a%2e%2e"))
        assertFalse(SkyCryptUrls.isValidUsername("a\\b"))
        assertFalse(SkyCryptUrls.isValidUsername("a@evil.example"))
    }

    /**
     * A trailing newline is not a valid name.
     *
     * Specifically guarded because the obvious spelling gets it wrong: with `^`/`$` anchors, Java's regex
     * matches `$` before a final line terminator, so `"chrooted\n"` would pass. `Regex.matches` does not.
     */
    @Test
    fun `a trailing newline does not sneak through`() {
        assertFalse(SkyCryptUrls.isValidUsername("chrooted\n"))
        assertFalse(SkyCryptUrls.isValidUsername("chrooted\r\n"))
    }

    // -- building the address -------------------------------------------------

    @Test
    fun `a stats URL names the player and the profile`() {
        assertEquals("https://sky.shiiyu.moe/stats/chrooted", SkyCryptUrls.statsUrl("chrooted"))
        assertEquals("https://sky.shiiyu.moe/stats/chrooted/Grapes", SkyCryptUrls.statsUrl("chrooted", "Grapes"))
    }

    @Test
    fun `a profile UUID is a profile too`() {
        val uuid = "b876ec32-e396-476b-a115-8438d83c67d4"
        assertEquals("https://sky.shiiyu.moe/stats/chrooted/$uuid", SkyCryptUrls.statsUrl("chrooted", uuid))
    }

    @Test
    fun `bad input produces no URL at all`() {
        assertNull(SkyCryptUrls.statsUrl("../evil"))
        assertNull(SkyCryptUrls.statsUrl(""))
        assertNull(SkyCryptUrls.statsUrl("chrooted", "../.."))
        assertNull(SkyCryptUrls.statsUrl("chrooted", "a/b"))
    }

    /** Whatever comes out is something the leash will then accept — the two halves must agree. */
    @Test
    fun `every URL this builds is one the browser is allowed to stay on`() {
        for (name in listOf("chrooted", "Th7bo", "_x_", "A".repeat(16))) {
            val url = SkyCryptUrls.statsUrl(name, "Grapes")!!
            assertTrue(SkyCryptUrls.isAllowed(url), url)
            assertTrue(SkyCryptUrls.isJudgeable(url), url)
        }
    }

    // -- where the page may go ------------------------------------------------

    @Test
    fun `SkyCrypt and its subdomains are allowed`() {
        assertTrue(SkyCryptUrls.isAllowed("https://sky.shiiyu.moe/stats/chrooted"))
        assertTrue(SkyCryptUrls.isAllowed("https://sky.shiiyu.moe/"))
        assertTrue(SkyCryptUrls.isAllowed("https://cms.shiiyu.moe/api/media/file/x.png"))
        assertTrue(SkyCryptUrls.isAllowed("https://SKY.SHIIYU.MOE/stats/chrooted"))
    }

    /**
     * The classic pair.
     *
     * Both of these contain `sky.shiiyu.moe` as a substring and neither is SkyCrypt. A check written with
     * `url.contains(HOST)` — which is the obvious thing to write — passes both.
     */
    @Test
    fun `a hostile host that contains the friendly one is refused`() {
        // Userinfo: everything before the @ is a username, and the real host is what follows.
        assertFalse(SkyCryptUrls.isAllowed("https://sky.shiiyu.moe@evil.example/"))
        assertFalse(SkyCryptUrls.isAllowed("https://sky.shiiyu.moe:pass@evil.example/"))
        // A subdomain of somebody else's domain.
        assertFalse(SkyCryptUrls.isAllowed("https://sky.shiiyu.moe.evil.example/"))
        // The domain suffix without the separating dot.
        assertFalse(SkyCryptUrls.isAllowed("https://notshiiyu.moe/"))
        assertFalse(SkyCryptUrls.isAllowed("https://evilshiiyu.moe/stats/x"))
        // The name only in the path or the query.
        assertFalse(SkyCryptUrls.isAllowed("https://evil.example/sky.shiiyu.moe"))
        assertFalse(SkyCryptUrls.isAllowed("https://evil.example/?x=https://sky.shiiyu.moe"))
    }

    @Test
    fun `plain http is refused even for the right host`() {
        assertFalse(SkyCryptUrls.isAllowed("http://sky.shiiyu.moe/stats/chrooted"))
    }

    @Test
    fun `a scheme that is not the web is refused`() {
        assertFalse(SkyCryptUrls.isAllowed("file:///etc/passwd"))
        assertFalse(SkyCryptUrls.isAllowed("javascript:alert(1)"))
        assertFalse(SkyCryptUrls.isAllowed("data:text/html,<h1>hi</h1>"))
        assertFalse(SkyCryptUrls.isAllowed("about:blank"))
        assertFalse(SkyCryptUrls.isAllowed(""))
        assertFalse(SkyCryptUrls.isAllowed("not a url at all"))
    }

    /**
     * Loading states are not navigation.
     *
     * A browser reports `about:blank` before its first paint and a `chrome-error://` page when a request
     * fails. Judging those would make the leash fight the browser every time a page was slow.
     */
    @Test
    fun `blank and error pages are not judged`() {
        assertFalse(SkyCryptUrls.isJudgeable("about:blank"))
        assertFalse(SkyCryptUrls.isJudgeable("chrome-error://chromewebdata/"))
        assertFalse(SkyCryptUrls.isJudgeable(""))

        // But a real address is, whether or not it turns out to be allowed.
        assertTrue(SkyCryptUrls.isJudgeable("https://evil.example/"))
        assertTrue(SkyCryptUrls.isJudgeable("http://sky.shiiyu.moe/"))
    }
}
