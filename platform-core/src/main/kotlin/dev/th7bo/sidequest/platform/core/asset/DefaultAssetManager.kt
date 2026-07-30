package dev.th7bo.sidequest.platform.core.asset

import dev.th7bo.sidequest.platform.asset.Asset
import dev.th7bo.sidequest.platform.asset.AssetCacheStats
import dev.th7bo.sidequest.platform.asset.AssetFetch
import dev.th7bo.sidequest.platform.asset.AssetId
import dev.th7bo.sidequest.platform.asset.AssetKind
import dev.th7bo.sidequest.platform.asset.AssetManager
import dev.th7bo.sidequest.platform.asset.AssetRejection
import dev.th7bo.sidequest.platform.asset.AssetResult
import dev.th7bo.sidequest.platform.asset.AssetStore
import dev.th7bo.sidequest.platform.asset.AssetTransport
import dev.th7bo.sidequest.platform.log.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The asset manager.
 *
 * Three layers, asked in order: memory, disk, network. Each is a cache of the one below it and every one of
 * them re-validates, which sounds redundant and is not — a file on disk was written by an earlier version of
 * this mod, or edited, or truncated by a full disk, and the cost of checking is a hash of something already
 * in hand.
 *
 * Four things here are less obvious than the layering:
 *
 * **Concurrent requests for the same asset share one download.** Without that, a cosmetic loadout with the
 * same badge on four slots fetches it four times, in parallel, and writes it to disk four times. The in-flight
 * map is keyed by id and the second caller awaits the first one's result.
 *
 * **Permanent refusals are remembered.** An asset that is the wrong shape will be the wrong shape next frame
 * too, and a render path that asks every frame would otherwise re-download it forever. Refusals that *are*
 * worth retrying — a timeout, an unreachable server — deliberately are not remembered.
 *
 * **Eviction is by bytes, not by count.** A budget in entries is meaningless when one entry is a 4 MiB sound
 * and another is a 2 KiB icon.
 *
 * **Nothing here ever sees a URL.** See [AssetUrls]. A feature names a hash and this decides where that comes
 * from, which is the whole of the plan's "never render arbitrary user-provided URLs" rule.
 */
public class DefaultAssetManager(
    private val transport: AssetTransport,
    private val store: AssetStore,
    private val log: Logger,
    /** Where downloads and preloads run. Owned by whoever constructs this, so a shutdown cancels them. */
    private val scope: CoroutineScope,
    baseUrl: () -> String?,
    /** How much may be held in memory. */
    private val memoryBudgetBytes: Long = DEFAULT_MEMORY_BUDGET,
    /** How much may be kept on disk between sessions. */
    private val diskBudgetBytes: Long = DEFAULT_DISK_BUDGET,
    private val now: () -> Long = System::currentTimeMillis,
) : AssetManager {

    private val urls = AssetUrls(baseUrl)

    /**
     * The resident assets, in least-recently-used order.
     *
     * `LinkedHashMap` in access order does the ordering, so a read is a reorder and eviction is "drop from
     * the front". Reimplementing that with a timestamp per entry and a sort is the usual alternative and it
     * is slower and wrong more often.
     */
    private val memory = object : LinkedHashMap<AssetId, Asset>(INITIAL_CAPACITY, LOAD_FACTOR, true) {}

    private var memoryBytes = 0L

    /** Ids known to be unusable, with why. Only ever holds permanent rejections. */
    private val refused = HashMap<AssetId, AssetRejection>()

    /** Downloads in progress, so four callers wanting one asset make one request. */
    private val inFlight = HashMap<AssetId, CompletableDeferred<AssetResult>>()

    /** What is on disk and how big, so eviction does not have to stat the directory on every write. */
    private val onDisk = LinkedHashMap<AssetId, Long>()
    private var diskBytes = 0L
    private var diskLoaded = false

    private val lock = Mutex()

    private var hits = 0
    private var misses = 0
    private var evictions = 0

    override fun resident(id: AssetId): Asset? = synchronized(memory) { memory[id] }

    override suspend fun load(id: AssetId, kind: AssetKind): AssetResult {
        synchronized(memory) { memory[id] }?.let { asset ->
            // A cached asset still has to be the kind that was asked for. The same bytes are a legitimate
            // icon and an illegitimate sound, and the cache is keyed on the bytes.
            if (asset.kind == kind) {
                synchronized(this) { hits++ }
                return AssetResult.Ready(asset)
            }
            return AssetResult.Refused(id, AssetRejection.WrongType(kind, asset.mediaType))
        }

        lock.withLock { refused[id] }?.let { return AssetResult.Refused(id, it) }

        // One download per id. Whoever gets here first creates the deferred and does the work; everybody else
        // waits on it, which is what stops a loadout of four identical badges making four requests.
        val (deferred, isOwner) = lock.withLock {
            inFlight[id]?.let { return@withLock it to false }
            val fresh = CompletableDeferred<AssetResult>()
            inFlight[id] = fresh
            fresh to true
        }
        if (!isOwner) return deferred.await()

        val result = try {
            resolve(id, kind)
        } catch (error: Throwable) {
            // The deferred must be completed even on a cancellation, or every other caller waiting on it
            // hangs for the life of the process.
            deferred.complete(AssetResult.Refused(id, AssetRejection.Unavailable(error.message ?: "failed")))
            lock.withLock { inFlight.remove(id) }
            throw error
        }

        lock.withLock { inFlight.remove(id) }
        deferred.complete(result)
        return result
    }

    /** Disk, then network. Called once per id at a time, by whichever caller owns the in-flight entry. */
    private suspend fun resolve(id: AssetId, kind: AssetKind): AssetResult {
        loadDiskIndexOnce()

        store.read(id)?.let { bytes ->
            when (val checked = AssetPolicy.accept(id, kind, bytes)) {
                is AssetPolicy.Result.Accepted -> {
                    synchronized(this) { hits++ }
                    admit(checked.value)
                    return AssetResult.Ready(checked.value)
                }
                is AssetPolicy.Result.Rejected -> {
                    // A cached file that no longer passes is a corrupt or superseded one. Dropping it and
                    // going to the network is right; keeping it would make the failure permanent.
                    log.warn { "Cached asset $id is unusable (${checked.reason.explanation}); refetching" }
                    store.delete(id)
                    lock.withLock { onDisk.remove(id)?.let { diskBytes -= it } }
                }
            }
        }

        synchronized(this) { misses++ }

        val url = urls.urlFor(id) ?: return refuse(id, AssetRejection.NoSource)

        val rejection = when (val fetched = transport.fetch(url, kind.maxBytes)) {
            is AssetFetch.Body -> when (val checked = AssetPolicy.accept(id, kind, fetched.bytes, fetched.declaredType)) {
                is AssetPolicy.Result.Accepted -> {
                    admit(checked.value)
                    persist(checked.value)
                    log.debug { "Fetched asset $id (${checked.value.byteCount} bytes, $kind)" }
                    return AssetResult.Ready(checked.value)
                }
                is AssetPolicy.Result.Rejected -> checked.reason
            }

            is AssetFetch.TooLarge ->
                AssetRejection.TooLarge(fetched.declaredBytes ?: kind.maxBytes, fetched.limitBytes)

            is AssetFetch.Failure -> AssetRejection.Unavailable(fetched.reason)
        }

        return refuse(id, rejection)
    }

    /**
     * Records a rejection and reports it.
     *
     * Only permanent ones are remembered. A server that was down for a minute must not have poisoned every
     * cosmetic for the rest of the session.
     */
    private suspend fun refuse(id: AssetId, rejection: AssetRejection): AssetResult {
        if (rejection.isPermanent) {
            lock.withLock { refused[id] = rejection }
            log.warn { "Refused asset $id: ${rejection.explanation}" }
        } else {
            log.debug { "Asset $id unavailable: ${rejection.explanation}" }
        }
        return AssetResult.Refused(id, rejection)
    }

    /** Puts an asset in memory, evicting from the least recently used end until it fits. */
    private fun admit(asset: Asset) {
        synchronized(memory) {
            memory.remove(asset.id)?.let { memoryBytes -= it.byteCount }
            // An asset larger than the whole budget is held anyway rather than admitted and immediately
            // dropped: it was asked for, and thrashing it in and out would be worse than briefly exceeding.
            memory[asset.id] = asset
            memoryBytes += asset.byteCount

            val iterator = memory.entries.iterator()
            while (memoryBytes > memoryBudgetBytes && memory.size > 1 && iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == asset.id) continue
                iterator.remove()
                memoryBytes -= entry.value.byteCount
                evictions++
            }
        }
    }

    /** Writes to disk and evicts until the disk budget holds. */
    private suspend fun persist(asset: Asset) {
        loadDiskIndexOnce()
        store.write(asset.id, asset.bytes)

        val doomed = lock.withLock {
            onDisk.remove(asset.id)?.let { diskBytes -= it }
            onDisk[asset.id] = asset.byteCount.toLong()
            diskBytes += asset.byteCount

            val doomed = ArrayList<AssetId>()
            val iterator = onDisk.entries.iterator()
            while (diskBytes > diskBudgetBytes && iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == asset.id) continue
                iterator.remove()
                diskBytes -= entry.value
                doomed.add(entry.key)
            }
            doomed
        }

        // Deleted outside the lock: this is file IO, and holding the lock across it would block every other
        // asset request for the duration.
        for (id in doomed) {
            store.delete(id)
            synchronized(this) { evictions++ }
        }
        if (doomed.isNotEmpty()) log.debug { "Evicted ${doomed.size} asset(s) from the disk cache" }
    }

    /**
     * Reads what is already on disk, once.
     *
     * Oldest first, so the map's iteration order is the eviction order from the start rather than only after
     * everything has been touched once this session.
     */
    private suspend fun loadDiskIndexOnce() {
        if (lock.withLock { diskLoaded }) return
        val stored = store.list().sortedBy { it.lastUsedMillis }
        lock.withLock {
            if (diskLoaded) return
            for (entry in stored) {
                onDisk[entry.id] = entry.bytes
                diskBytes += entry.bytes
            }
            diskLoaded = true
        }
        log.debug { "Asset cache holds ${stored.size} file(s), $diskBytes bytes" }
    }

    override suspend fun preload(ids: Collection<AssetId>, kind: AssetKind) {
        if (ids.isEmpty()) return
        // Run on the manager's own scope rather than the caller's: a preload is speculative, and a caller
        // that gives up waiting should not cancel a download that a later frame will want.
        val distinct = ids.distinct()
        distinct.map { id -> scope.async { runCatching { load(id, kind) } } }.awaitAll()
        log.debug { "Preloaded ${distinct.size} asset(s) of $kind" }
    }

    override fun releaseMemory() {
        synchronized(memory) {
            evictions += memory.size
            memory.clear()
            memoryBytes = 0
        }
    }

    override suspend fun clear() {
        releaseMemory()
        store.clear()
        lock.withLock {
            refused.clear()
            onDisk.clear()
            diskBytes = 0
            // Left loaded: the index is now known to be empty, and re-listing an empty directory to discover
            // that would be the only effect of clearing this.
            diskLoaded = true
        }
    }

    override fun stats(): AssetCacheStats = synchronized(memory) {
        AssetCacheStats(
            entries = memory.size,
            bytes = memoryBytes,
            budgetBytes = memoryBudgetBytes,
            hits = hits,
            misses = misses,
            evictions = evictions,
        )
    }

    public companion object {
        /**
         * How much stays in memory.
         *
         * Sixteen mebibytes. Enough for a party's worth of cosmetics resident at once, small enough that it
         * is invisible next to Minecraft's own footprint — this competes with the game's heap, and an asset
         * cache that causes a garbage-collection stutter has cost more than it saved.
         */
        public const val DEFAULT_MEMORY_BUDGET: Long = 16L * 1024 * 1024

        /** How much stays on disk. Generous by comparison: it is somebody's SSD, not their heap. */
        public const val DEFAULT_DISK_BUDGET: Long = 256L * 1024 * 1024

        private const val INITIAL_CAPACITY = 32
        private const val LOAD_FACTOR = 0.75f
    }
}
