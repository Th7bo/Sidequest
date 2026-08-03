package dev.th7bo.sidequest.platform.item

/**
 * What a SkyBlock item is, as far as drawing one goes.
 *
 * Deliberately small. The item database it comes from carries recipes, lore, wiki links and a dozen other
 * fields, and holding all of that for every item somebody has ever seen a drop of would be most of a
 * megabyte to answer "what does this look like".
 */
public data class SkyBlockItem(
    /** The database's own key: `HYPERION`, `REVENANT_CATALYST`. Uppercase with underscores. */
    public val internalName: String,
    /** As Hypixel writes it, formatting codes stripped. */
    public val displayName: String,
    /**
     * The Minecraft item to draw it as — `minecraft:iron_sword` for Hyperion.
     *
     * Already modern. The database is 1.8-era and stores ids from before the flattening, but it also records
     * the modern model each entry resolves to, so this is read from that rather than derived. Which matters:
     * `minecraft:dye` with damage 4 is `minecraft:lapis_lazuli` and damage 2 is `minecraft:green_dye`, and
     * the table that says so is hundreds of lines nobody should be maintaining.
     *
     * Not always vanilla-looking, and honestly so. Hypixel now ships its own models, and an item whose model
     * is one of those falls back to the vanilla item underneath it — a Sniper Bow draws as a bow. That is what
     * the item looked like before the resource pack existed, which is a good deal better than nothing.
     */
    public val minecraftId: String,
    /**
     * The base64 skin, for the items that are player heads.
     *
     * Null for everything else. Most of what SkyBlock adds is a head with a custom texture — it is how the
     * game has custom-looking items at all — so this is the field that decides whether an icon looks like the
     * item or like a blank skull.
     */
    public val skullTexture: String? = null,
    /**
     * The model Hypixel draws it with, when that is one of theirs — `hypixel_skyblock:item/…`.
     *
     * Kept rather than discarded because a player running Hypixel's resource pack, or one that restores
     * the original textures, *has* these models. Handing the id to the game then draws the real item
     * instead of the vanilla base it is built on, which is the difference between a Revenant Catalyst and
     * a sheet of paper. Null when the item resolves to a vanilla model, where [minecraftId] is already
     * the answer.
     */
    public val modelId: String? = null,
) {
    public val isPlayerHead: Boolean get() = skullTexture != null
}

/**
 * Looks up SkyBlock items by name.
 *
 * Exists because Hypixel announces a drop as a *display name* and nothing else — `RARE DROP! Revenant
 * Catalyst` — and nothing in Minecraft knows what that is. The vanilla registry answers for maybe a third of
 * what drops; the rest are SkyBlock's own, and they are only nameable through a database of them.
 *
 * Everything suspends. A lookup may be a network round trip the first time, and the render path that wants an
 * icon cannot wait — which is what [resident] is for.
 */
public interface SkyBlockItemRepository {

    /** The item, fetching it if this is the first time anybody asked. Null when there is no such item. */
    public suspend fun byInternalName(internalName: String): SkyBlockItem?

    /**
     * The item somebody would call [displayName].
     *
     * Resolution is the interesting half — see the implementation. A display name is what a human reads and
     * an internal name is what the database is keyed by, and the two agree for most items and not for pets,
     * enchanted books, or anything whose name carries a rarity or a level.
     */
    public suspend fun byDisplayName(displayName: String): SkyBlockItem?

    /**
     * What is already in memory, with no IO at all.
     *
     * For render paths. Null means "not resident", never "does not exist" — the caller draws nothing this
     * frame and the icon appears on a later one, once [prefetch] or an earlier lookup has finished.
     */
    public fun resident(displayName: String): SkyBlockItem?

    /** Fetches ahead of time, ignoring failures. For a drop that is about to be animated. */
    public suspend fun prefetch(displayName: String)

    public fun stats(): RepositoryStats

    public companion object {
        /** A repository that knows nothing. For a platform assembled without one, and for tests. */
        public val None: SkyBlockItemRepository = object : SkyBlockItemRepository {
            override suspend fun byInternalName(internalName: String): SkyBlockItem? = null
            override suspend fun byDisplayName(displayName: String): SkyBlockItem? = null
            override fun resident(displayName: String): SkyBlockItem? = null
            override suspend fun prefetch(displayName: String) {}
            override fun stats(): RepositoryStats = RepositoryStats(0, 0, 0, 0)
        }
    }
}

/** What the repository is holding, for the inspector. */
public data class RepositoryStats(
    public val resident: Int,
    public val hits: Int,
    public val misses: Int,
    /** Names looked up and found not to exist. Remembered, so they are not fetched again. */
    public val known404s: Int,
) {
    override fun toString(): String = "$resident resident, $hits hits, $misses misses, $known404s unknown"
}
