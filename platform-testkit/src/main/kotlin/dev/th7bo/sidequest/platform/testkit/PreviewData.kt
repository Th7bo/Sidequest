package dev.th7bo.sidequest.platform.testkit

import dev.th7bo.sidequest.platform.asset.Asset
import dev.th7bo.sidequest.platform.asset.AssetCacheStats
import dev.th7bo.sidequest.platform.asset.AssetId
import dev.th7bo.sidequest.platform.asset.AssetKind
import dev.th7bo.sidequest.platform.asset.AssetManager
import dev.th7bo.sidequest.platform.asset.AssetRejection
import dev.th7bo.sidequest.platform.asset.AssetResult
import dev.th7bo.sidequest.platform.asset.MediaType

/**
 * An asset manager holding whatever a test hands it.
 *
 * Fetches nothing and validates nothing — [dev.th7bo.sidequest.platform.asset.AssetManager] implementations
 * are tested against the real one. This is for everything *downstream* of assets, which only ever asks
 * whether one is there.
 */
public class FakeAssetManager(resident: Map<AssetId, Asset> = emptyMap()) : AssetManager {

    private val held = LinkedHashMap<AssetId, Asset>(resident)

    /** Makes an asset available, as if it had downloaded. */
    public fun hold(id: AssetId, kind: AssetKind = AssetKind.ICON, bytes: ByteArray = ByteArray(4)) {
        held[id] = Asset(id, kind, MediaType.PNG, bytes)
    }

    /** Takes one away, for testing what happens when a cache is evicted mid-session. */
    public fun drop(id: AssetId) {
        held.remove(id)
    }

    override suspend fun load(id: AssetId, kind: AssetKind): AssetResult =
        held[id]?.let { AssetResult.Ready(it) } ?: AssetResult.Refused(id, AssetRejection.NoSource)

    override fun resident(id: AssetId): Asset? = held[id]

    override suspend fun preload(ids: Collection<AssetId>, kind: AssetKind) {
        for (id in ids) if (id !in held) hold(id, kind)
    }

    override fun releaseMemory(): Unit = held.clear()

    override suspend fun clear(): Unit = held.clear()

    override fun stats(): AssetCacheStats =
        AssetCacheStats(held.size, held.values.sumOf { it.byteCount.toLong() }, 0, 0, 0, 0)
}
