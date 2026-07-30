package dev.th7bo.sidequest.platform.core.asset

import dev.th7bo.sidequest.platform.asset.AssetFetch
import dev.th7bo.sidequest.platform.asset.AssetId
import dev.th7bo.sidequest.platform.asset.AssetKind
import dev.th7bo.sidequest.platform.asset.AssetRejection
import dev.th7bo.sidequest.platform.asset.AssetResult
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * The three layers, and what happens between them.
 *
 * [AssetPolicyTest] covers what is allowed to become an asset; this covers everything around that — whether
 * the network is touched at all, what happens to a file on disk that has gone bad, and whether four callers
 * asking for one badge make one request or four.
 */
class AssetManagerTest {

    private val transport = FakeAssetTransport()
    private val store = FakeAssetStore()

    private fun TestScope.manager(
        memoryBudget: Long = DefaultAssetManager.DEFAULT_MEMORY_BUDGET,
        diskBudget: Long = DefaultAssetManager.DEFAULT_DISK_BUDGET,
        baseUrl: String? = BASE_URL,
        scope: CoroutineScope = backgroundScope,
    ) = DefaultAssetManager(
        transport = transport,
        store = store,
        log = NoopLogger,
        scope = scope,
        baseUrl = { baseUrl },
        memoryBudgetBytes = memoryBudget,
        diskBudgetBytes = diskBudget,
    )

    private fun ready(result: AssetResult) = when (result) {
        is AssetResult.Ready -> result.asset
        is AssetResult.Refused -> fail("expected the asset, got ${result.rejection.explanation}")
    }

    private fun refusal(result: AssetResult) = when (result) {
        is AssetResult.Refused -> result.rejection
        is AssetResult.Ready -> fail("expected a refusal, got ${result.asset}")
    }

    // -- the layers ----------------------------------------------------------

    @Test
    fun `an asset is fetched, then served from memory without touching the network again`() = runTest {
        val bytes = AssetFixtures.png(32, 32)
        val id = AssetFixtures.idOf(bytes)
        transport.serve(id, bytes, "image/png")
        val assets = manager()

        val first = ready(assets.load(id, AssetKind.ICON))
        val second = ready(assets.load(id, AssetKind.ICON))

        assertEquals(id, first.id)
        assertEquals(id, second.id)
        assertEquals(1, transport.requested.size, "the second load must not hit the network")
        assertEquals(1, assets.stats().misses)
        assertEquals(1, assets.stats().hits)
    }

    @Test
    fun `an asset survives into a new session through the disk cache`() = runTest {
        val bytes = AssetFixtures.png(32, 32)
        val id = AssetFixtures.idOf(bytes)
        transport.serve(id, bytes)
        ready(manager().load(id, AssetKind.ICON))
        assertTrue(store.holds(id))

        // A second manager over the same store is what a restart looks like.
        val restarted = manager()
        val recovered = ready(restarted.load(id, AssetKind.ICON))

        assertEquals(id, recovered.id)
        assertEquals(1, transport.requested.size, "the restart should have read from disk, not the network")
    }

    /**
     * A file on disk is re-validated, not trusted.
     *
     * It was written by some earlier version of this mod, or truncated by a full disk, or edited by somebody
     * curious. Re-hashing it costs one pass over bytes already in hand, and skipping that would make the
     * disk cache the one place a corrupt asset gets in unchecked.
     */
    @Test
    fun `a corrupt file on disk is discarded and refetched`() = runTest {
        val bytes = AssetFixtures.png(32, 32)
        val id = AssetFixtures.idOf(bytes)
        transport.serve(id, bytes)
        store.put(id, "not a png at all".toByteArray())

        val asset = ready(manager().load(id, AssetKind.ICON))

        assertEquals(id, asset.id)
        assertEquals(1, transport.requested.size, "it had to go to the network")
        assertTrue(store.holds(id), "and the good copy replaced the bad one")
    }

    @Test
    fun `resident returns nothing before a load and the asset after one`() = runTest {
        val bytes = AssetFixtures.png(32, 32)
        val id = AssetFixtures.idOf(bytes)
        transport.serve(id, bytes)
        val assets = manager()

        assertNull(assets.resident(id), "a render path must not be told an asset is there before it is")
        assets.load(id, AssetKind.ICON)
        assertNotNull(assets.resident(id))
    }

    /** The kind is part of the question, so a cached icon does not answer a request for a sound. */
    @Test
    fun `a cached asset is still checked against the kind that is asked for`() = runTest {
        val bytes = AssetFixtures.png(32, 32)
        val id = AssetFixtures.idOf(bytes)
        transport.serve(id, bytes)
        val assets = manager()
        assets.load(id, AssetKind.ICON)

        val reason = refusal(assets.load(id, AssetKind.SOUND))

        assertInstanceOf(AssetRejection.WrongType::class.java, reason)
    }

    // -- one download per asset ---------------------------------------------

    /**
     * Four slots wearing the same badge make one request.
     *
     * Without the in-flight map this is four parallel downloads of one file, four validations and four writes
     * of the same bytes — and a cosmetic loadout is exactly the shape that produces it.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)  // `runCurrent`, to observe the shared wait
    fun `concurrent requests for one asset share a single download`() = runTest {
        val bytes = AssetFixtures.png(32, 32)
        val id = AssetFixtures.idOf(bytes)
        transport.serve(id, bytes)
        val gate = CompletableDeferred<Unit>()
        transport.gate = gate
        val assets = manager()

        val callers = (1..4).map { async { assets.load(id, AssetKind.ICON) } }
        runCurrent()
        assertEquals(1, transport.requested.size, "all four should be waiting on one request")

        gate.complete(Unit)
        val results = callers.map { ready(it.await()) }

        assertEquals(1, transport.requested.size)
        assertEquals(1, store.writes, "and it is written once")
        assertTrue(results.all { it.id == id }, "every caller gets the asset")
    }

    // -- refusals ------------------------------------------------------------

    /**
     * A permanent refusal is remembered; a temporary one is not.
     *
     * A render path asks every frame. Re-downloading a file that is the wrong shape sixty times a second is
     * the failure mode, and remembering a *timeout* instead would leave a server that blipped for a moment
     * having broken every cosmetic until the next restart.
     */
    @Test
    fun `a permanently refused asset is not fetched again`() = runTest {
        val bytes = AssetFixtures.ogg()
        val id = AssetFixtures.idOf(bytes)
        transport.serve(id, bytes)
        val assets = manager()

        assertInstanceOf(AssetRejection.WrongType::class.java, refusal(assets.load(id, AssetKind.ICON)))
        assertInstanceOf(AssetRejection.WrongType::class.java, refusal(assets.load(id, AssetKind.ICON)))

        assertEquals(1, transport.requested.size, "the second ask should have been answered from the refusal")
    }

    @Test
    fun `an unreachable server is retried`() = runTest {
        val bytes = AssetFixtures.png(32, 32)
        val id = AssetFixtures.idOf(bytes)
        transport.serve(id, AssetFetch.Failure("connection refused"))
        val assets = manager()

        val reason = refusal(assets.load(id, AssetKind.ICON))
        assertFalse(reason.isPermanent, "a network failure is not the asset's fault")

        // And the retry succeeds once the server is back, which it could not if the failure were remembered.
        transport.serve(id, bytes)
        assertEquals(id, ready(assets.load(id, AssetKind.ICON)).id)
    }

    @Test
    fun `an asset the server substitutes is refused on its hash`() = runTest {
        val wanted = AssetFixtures.unrelatedId("the one we asked for")
        transport.serve(wanted, AssetFixtures.png(32, 32))

        val reason = refusal(manager().load(wanted, AssetKind.ICON))

        assertInstanceOf(AssetRejection.HashMismatch::class.java, reason)
        assertFalse(store.holds(wanted), "and nothing that failed validation reaches the disk")
    }

    @Test
    fun `an oversized response is refused without being buffered`() = runTest {
        val id = AssetFixtures.unrelatedId("huge")
        transport.serve(id, AssetFetch.TooLarge(AssetKind.ICON.maxBytes, declaredBytes = 50L * 1024 * 1024))

        val reason = refusal(manager().load(id, AssetKind.ICON))

        val large = assertInstanceOf(AssetRejection.TooLarge::class.java, reason)
        assertEquals(AssetKind.ICON.maxBytes, large.limitBytes)
    }

    @Test
    fun `without a backend there is nowhere to fetch from`() = runTest {
        val id = AssetFixtures.unrelatedId()

        val reason = refusal(manager(baseUrl = null).load(id, AssetKind.ICON))

        assertEquals(AssetRejection.NoSource, reason)
        assertTrue(transport.requested.isEmpty(), "and no request was attempted")
    }

    // -- eviction ------------------------------------------------------------

    /**
     * The budget is in bytes, because a budget in entries is meaningless.
     *
     * One entry here is a 4 MiB sound and another is a 2 KiB icon; a cache that holds "fifty of them" holds
     * somewhere between 100 KiB and 200 MiB.
     */
    @Test
    fun `memory eviction drops the least recently used until the byte budget holds`() = runTest {
        val assets = manager(memoryBudget = 2_500)
        val ids = (1..3).map { index ->
            val bytes = AssetFixtures.paddedPng(1_000, filler = index.toByte())
            AssetFixtures.idOf(bytes).also { transport.serve(it, bytes) }
        }

        assets.load(ids[0], AssetKind.ICON)
        assets.load(ids[1], AssetKind.ICON)
        // Touching the first makes the second the least recently used, which is the part a naive
        // insertion-order cache gets wrong.
        assets.load(ids[0], AssetKind.ICON)
        assets.load(ids[2], AssetKind.ICON)

        assertNotNull(assets.resident(ids[0]), "recently used, so kept")
        assertNotNull(assets.resident(ids[2]), "just added")
        assertNull(assets.resident(ids[1]), "least recently used, so dropped")
        assertTrue(assets.stats().bytes <= 2_500)
    }

    @Test
    fun `an evicted asset comes back from disk rather than the network`() = runTest {
        val assets = manager(memoryBudget = 1_500)
        val ids = (1..2).map { index ->
            val bytes = AssetFixtures.paddedPng(1_000, filler = index.toByte())
            AssetFixtures.idOf(bytes).also { transport.serve(it, bytes) }
        }

        assets.load(ids[0], AssetKind.ICON)
        assets.load(ids[1], AssetKind.ICON)
        assertNull(assets.resident(ids[0]), "the premise: it was evicted from memory")

        ready(assets.load(ids[0], AssetKind.ICON))

        assertEquals(2, transport.requested.size, "it should have come from disk, not a third request")
    }

    @Test
    fun `disk eviction keeps the cache inside its budget`() = runTest {
        val assets = manager(diskBudget = 2_500)
        val ids = (1..4).map { index ->
            val bytes = AssetFixtures.paddedPng(1_000, filler = index.toByte())
            AssetFixtures.idOf(bytes).also { transport.serve(it, bytes) }
        }

        for (id in ids) assets.load(id, AssetKind.ICON)

        assertTrue(store.count <= 2, "four kilobytes of assets should not fit in a 2,500-byte cache")
        assertTrue(store.holds(ids.last()), "and the newest is the one kept")
    }

    @Test
    fun `releasing memory keeps the disk cache`() = runTest {
        val bytes = AssetFixtures.png(32, 32)
        val id = AssetFixtures.idOf(bytes)
        transport.serve(id, bytes)
        val assets = manager()
        assets.load(id, AssetKind.ICON)

        assets.releaseMemory()

        assertNull(assets.resident(id))
        assertTrue(store.holds(id), "a low-memory response must not cost a re-download")
        assertEquals(0, assets.stats().entries)
    }

    @Test
    fun `clearing removes everything including the refusals`() = runTest {
        val good = AssetFixtures.png(32, 32)
        val goodId = AssetFixtures.idOf(good)
        val sound = AssetFixtures.ogg()
        val soundId = AssetFixtures.idOf(sound)
        transport.serve(goodId, good)
        transport.serve(soundId, sound)
        val assets = manager()
        assets.load(goodId, AssetKind.ICON)
        refusal(assets.load(soundId, AssetKind.ICON))

        assets.clear()

        assertEquals(0, store.count)
        assertNull(assets.resident(goodId))
        // The refusal is gone too, so a re-uploaded file gets a fresh chance rather than being permanently
        // condemned by a cache the user just asked to be emptied.
        refusal(assets.load(soundId, AssetKind.ICON))
        assertEquals(3, transport.requested.size)
    }

    // -- preload -------------------------------------------------------------

    @Test
    fun `preload fetches everything and survives one of them failing`() = runTest {
        val good = (1..3).map { index ->
            val bytes = AssetFixtures.paddedPng(500, filler = index.toByte())
            AssetFixtures.idOf(bytes).also { transport.serve(it, bytes) }
        }
        val broken = AssetFixtures.unrelatedId("missing")
        val assets = manager()

        assets.preload(good + broken, AssetKind.ICON)

        for (id in good) assertNotNull(assets.resident(id), "$id should be resident after a preload")
        assertNull(assets.resident(broken))
    }

    @Test
    fun `preload asks for a repeated asset once`() = runTest {
        val bytes = AssetFixtures.png(32, 32)
        val id = AssetFixtures.idOf(bytes)
        transport.serve(id, bytes)

        manager().preload(listOf(id, id, id), AssetKind.ICON)

        assertEquals(1, transport.requested.size)
    }

    // -- where assets come from ---------------------------------------------

    /**
     * The plan's rule is "never render arbitrary user-provided URLs", and this is where it is kept.
     *
     * Not by validating URLs that arrive — none do. The manager's API takes an id, the id is a hash, and the
     * URL is built here from the configured backend. There is no input a remote party controls.
     */
    @Test
    fun `an asset url is built from the configured backend and the hash`() {
        val urls = AssetUrls { BASE_URL }
        val id = AssetFixtures.unrelatedId()

        assertEquals("$BASE_URL/v1/assets/${id.value}", urls.urlFor(id))
    }

    @Test
    fun `a trailing slash on the backend does not double up`() {
        val id = AssetFixtures.unrelatedId()

        assertEquals(AssetUrls { BASE_URL }.urlFor(id), AssetUrls { "$BASE_URL/" }.urlFor(id))
    }

    /**
     * The origin check is not a string prefix.
     *
     * `https://sq.api.th7bo.dev.evil.example` starts with the configured base and is a different host. A
     * prefix test passes it, which is the exact mistake the comparison is written to avoid — so it is worth a
     * test even though nothing today can supply the input.
     */
    @Test
    fun `a lookalike host is not treated as the backend`() {
        val lookalike = AssetUrls { "https://sq.api.th7bo.dev.evil.example" }
        val id = AssetFixtures.unrelatedId()

        val url = lookalike.urlFor(id)

        assertNotNull(url)
        assertTrue(url!!.startsWith("https://sq.api.th7bo.dev.evil.example/"), "it fetches from its own base")
        assertFalse(
            url.startsWith("$BASE_URL/"),
            "and is never mistaken for the real backend",
        )
    }

    @Test
    fun `a backend with no scheme yields no url at all`() {
        assertNull(AssetUrls { "sq.api.th7bo.dev" }.urlFor(AssetFixtures.unrelatedId()))
        assertNull(AssetUrls { "" }.urlFor(AssetFixtures.unrelatedId()))
        assertNull(AssetUrls { "   " }.urlFor(AssetFixtures.unrelatedId()))
        assertNull(AssetUrls { null }.urlFor(AssetFixtures.unrelatedId()))
    }

    /** Credentials in the authority are how a URL is made to look like one host and reach another. */
    @Test
    fun `a base url carrying userinfo is refused`() {
        assertNull(AssetUrls { "https://sq.api.th7bo.dev@evil.example" }.urlFor(AssetFixtures.unrelatedId()))
    }

    /**
     * An id cannot escape the assets path.
     *
     * Belt and braces — [AssetId] already refuses anything that is not 64 hex characters, so this is
     * unreachable. It is a test of that guarantee rather than of the URL builder, because the guarantee is
     * what the builder relies on to do no escaping of its own.
     */
    @Test
    fun `an id cannot contain a path`() {
        assertNull(AssetId.parseOrNull("../../../etc/passwd"))
        assertNull(AssetId.parseOrNull("a".repeat(60) + "/../"))
    }

    private companion object {
        const val BASE_URL = "https://sq.api.th7bo.dev"
    }
}
