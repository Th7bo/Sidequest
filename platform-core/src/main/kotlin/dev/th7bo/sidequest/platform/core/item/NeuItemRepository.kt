package dev.th7bo.sidequest.platform.core.item

import dev.th7bo.sidequest.platform.backend.HttpExchange
import dev.th7bo.sidequest.platform.backend.HttpMethod
import dev.th7bo.sidequest.platform.backend.HttpRequest
import dev.th7bo.sidequest.platform.backend.HttpTransport
import dev.th7bo.sidequest.platform.item.RepositoryStats
import dev.th7bo.sidequest.platform.item.SkyBlockItem
import dev.th7bo.sidequest.platform.item.SkyBlockItemRepository
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.parser.HypixelText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SkyBlock items, from the NotEnoughUpdates repository.
 *
 * **Fetched one item at a time rather than as a repository dump.** Every other mod that uses this data pulls
 * the whole archive — tens of megabytes, thousands of files, on a background thread at startup — because they
 * need to *search* it. This mod needs to answer "what does the thing that just dropped look like", a handful
 * of times a session, so a single small file per item is the right shape: nothing is downloaded until
 * somebody drops something, and what is downloaded is a couple of kilobytes.
 *
 * The trade is that it cannot enumerate. Nothing here can answer "list every sword", and if something ever
 * needs to, this is the wrong implementation rather than one to extend.
 *
 * The data is MIT-licensed, which is why it can be used from a closed-source mod at all — worth stating
 * because the mod it is usually seen alongside is not.
 */
public class NeuItemRepository(
    private val transport: HttpTransport,
    private val log: Logger,
    /** Where the item files live. Overridden in tests; pinned to a tag would be the way to freeze it. */
    private val baseUrl: String = DEFAULT_BASE_URL,
) : SkyBlockItemRepository {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val items = HashMap<String, SkyBlockItem>()

    /**
     * Names the repository does not have.
     *
     * Remembered, and that is not an optimisation: a drop whose name does not resolve happens constantly —
     * every enchanted book, every renamed item — and a render path asking once a frame would otherwise send a
     * request a frame, forever, for something that will never exist.
     */
    private val missing = HashSet<String>()

    /** One fetch per name, however many callers arrive while it is in flight. */
    private val inFlight = HashMap<String, CompletableDeferred<SkyBlockItem?>>()

    /**
     * One monitor over all four of the above.
     *
     * A plain lock rather than a coroutine `Mutex`, and deliberately: nothing held under it suspends, and
     * [resident] and [stats] are not suspending functions at all — a `Mutex` would leave them reading state
     * that the suspending paths guard with something else, which is a data race dressed as thread safety.
     */
    private val lock = Any()

    private var hits = 0
    private var misses = 0

    override fun resident(displayName: String): SkyBlockItem? = synchronized(lock) {
        candidatesFor(displayName).firstNotNullOfOrNull { items[it] }
    }

    /**
     * Tries each plausible key until one exists.
     *
     * There is no rule that turns a display name into a repository key — the keys are Hypixel's own item ids
     * and they are only consistent by habit. `Giant's Sword` is `GIANTS_SWORD` and `Necron's Handle` is
     * `NECRON_HANDLE`, from the same apostrophe. The mod that usually reads this data resolves it by
     * downloading the whole archive and building a name index; this asks for two or three names instead,
     * which lands on the same answer for a fraction of the bytes.
     *
     * A wrong guess costs one request *ever*, because [missing] remembers it. So the cost of the extra
     * candidates is a handful of requests over the life of an installation, and the benefit is the items
     * whose key nobody could have derived.
     */
    override suspend fun byDisplayName(displayName: String): SkyBlockItem? {
        for (candidate in candidatesFor(displayName)) {
            byInternalName(candidate)?.let { return it }
        }
        return null
    }

    override suspend fun prefetch(displayName: String) {
        runCatching { byDisplayName(displayName) }
    }

    override suspend fun byInternalName(internalName: String): SkyBlockItem? {
        if (internalName.isEmpty()) return null

        val (deferred, isOwner) = synchronized(lock) {
            items[internalName]?.let {
                hits++
                return it
            }
            if (internalName in missing) return null

            inFlight[internalName]?.let { return@synchronized it to false }
            val fresh = CompletableDeferred<SkyBlockItem?>()
            inFlight[internalName] = fresh
            fresh to true
        }
        if (!isOwner) return deferred.await()

        val result = try {
            fetch(internalName)
        } catch (error: Throwable) {
            // Completed whatever happens, or every other caller waiting on it hangs for the life of the game.
            synchronized(lock) { inFlight.remove(internalName) }
            deferred.complete(null)
            throw error
        }

        synchronized(lock) { inFlight.remove(internalName) }
        deferred.complete(result)
        return result
    }

    private suspend fun fetch(internalName: String): SkyBlockItem? {
        synchronized(lock) { misses++ }
        val url = "$baseUrl/$internalName.json"

        return when (val response = transport.send(HttpRequest(HttpMethod.GET, url))) {
            is HttpExchange.Failure -> {
                // Not remembered as missing: the network being down is not the item not existing, and
                // conflating the two would leave every icon blank until a restart.
                log.debug { "Could not reach the item repository for $internalName: ${response.reason}" }
                null
            }

            is HttpExchange.Response -> when {
                response.status == NOT_FOUND -> {
                    synchronized(lock) { missing.add(internalName) }
                    log.trace { "No repository entry for $internalName" }
                    null
                }
                !response.isSuccess -> {
                    log.debug { "Item repository answered ${response.status} for $internalName" }
                    null
                }
                else -> parse(internalName, response.body)?.also { item ->
                    synchronized(lock) { items[internalName] = item }
                    log.debug { "Learned $internalName (${item.minecraftId})" }
                }
            }
        }
    }

    /**
     * Reads the fields worth keeping.
     *
     * Hand-parsed rather than deserialized into a data class, because the entry carries a dozen fields this
     * mod has no use for — recipes, lore, wiki links — and the one field that matters most is inside a
     * *string* of 1.8-era NBT rather than in the JSON.
     */
    private fun parse(internalName: String, body: String): SkyBlockItem? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val legacyId = root["itemid"]?.jsonPrimitive?.content ?: return@runCatching null
        val display = root["displayname"]?.jsonPrimitive?.content ?: internalName
        val nbt = root["nbttag"]?.jsonPrimitive?.content.orEmpty()

        val model = modelFrom(nbt)
        SkyBlockItem(
            internalName = internalName,
            displayName = HypixelText.clean(display),
            minecraftId = modernIdFor(legacyId, model),
            skullTexture = skullTextureFrom(nbt),
            // Only Hypixel's own. A vanilla model is already what `minecraftId` names, and repeating it
            // here would have the client set a component that changes nothing.
            modelId = model?.takeUnless { it.startsWith(VANILLA) },
        )
    }.getOrElse { thrown ->
        log.warn(thrown) { "Could not read the repository entry for $internalName" }
        null
    }

    /**
     * Digs the base64 skin out of an item's NBT.
     *
     * The NBT is a *string* in the 1.8 textual format, not JSON, so this is a targeted extraction rather than
     * a parse. Writing an NBT parser to reach one field would be a lot of code to be wrong in more ways.
     */
    private fun skullTextureFrom(nbt: String): String? = quotedAfter(nbt, TEXTURE_MARKER)

    /**
     * The modern model the entry resolves to, if it records one.
     *
     * Almost all of them do — every entry sampled but one — which is what makes the flattening table
     * unnecessary. Read out of the NBT string for the same reason as the skin: it is in there, not in the
     * JSON around it.
     */
    private fun modelFrom(nbt: String): String? = quotedAfter(nbt, MODEL_MARKER)

    /** The quoted string that follows [marker]. The whole of the NBT reading this needs. */
    private fun quotedAfter(nbt: String, marker: String): String? {
        val at = nbt.indexOf(marker)
        if (at < 0) return null
        val start = nbt.indexOf('"', at + marker.length)
        if (start < 0) return null
        val end = nbt.indexOf('"', start + 1)
        if (end < 0) return null
        return nbt.substring(start + 1, end).takeIf { it.isNotEmpty() }
    }

    override fun stats(): RepositoryStats = synchronized(lock) {
        RepositoryStats(resident = items.size, hits = hits, misses = misses, known404s = missing.size)
    }

    public companion object {
        /**
         * Where the item files are.
         *
         * `master` rather than a pinned commit, deliberately: the repository gains items as Hypixel adds them,
         * and a pin would mean every new item is invisible until somebody updates the mod. The cost is that a
         * bad commit upstream shows up here — acceptable, because the worst it produces is a missing icon.
         */
        public const val DEFAULT_BASE_URL: String =
            "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/items"

        private const val NOT_FOUND = 404

        /** Where the skin sits in the 1.8 NBT string: `Properties:{textures:[0:{Name:"textures",…Value:"…"`. */
        private const val TEXTURE_MARKER = "Value:"

        /** Where the modern model sits: `ItemModel:"minecraft:player_head"`. */
        private const val MODEL_MARKER = "ItemModel:"

        private const val VANILLA = "minecraft:"

        /**
         * What to actually draw, given the entry's 1.8 id and the model it records.
         *
         * The model wins when it is a vanilla one, because it is the answer rather than a guess at it. When it
         * is one of Hypixel's own — `hypixel_skyblock:item/fishing/bait/carrot_bait` — there is nothing on
         * this client to draw, so the 1.8 base is used instead and the item at least looks like the right kind
         * of thing.
         *
         * The only legacy id mapped by hand is the one that carries almost all of SkyBlock: a custom-skinned
         * head. The rest are either unchanged since 1.8 or covered by the model, and inventing a table for the
         * remainder would be a lot of code to be subtly wrong in.
         */
        private fun modernIdFor(legacyId: String, model: String?): String {
            if (model != null && model.startsWith(VANILLA)) return model
            return if (legacyId == LEGACY_SKULL) PLAYER_HEAD else legacyId
        }

        private const val LEGACY_SKULL = "minecraft:skull"
        private const val PLAYER_HEAD = "minecraft:player_head"

        /**
         * A display name, as the repository most likely keys it.
         *
         * `Revenant Catalyst` becomes `REVENANT_CATALYST`, which is the convention for the overwhelming
         * majority of entries. Note that an apostrophe *vanishes* rather than becoming an underscore:
         * `Giant's Sword` is `GIANTS_SWORD`, not `GIANT_S_SWORD`.
         *
         * This is a guess, not a translation — see [candidatesFor] for why one is the best that can be done.
         */
        public fun internalNameFor(displayName: String): String = HypixelText.clean(displayName)
            .uppercase()
            .filterNot { it == '\'' || it == '’' }
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
            .replace(UNDERSCORE_RUN, "_")
            .trim('_')

        /**
         * Every key an item called [displayName] might be filed under, likeliest first.
         *
         * The keys are Hypixel's own item ids, and nothing derives them from the name — `Giant's Sword` is
         * `GIANTS_SWORD` while `Necron's Handle` is `NECRON_HANDLE`, from the same possessive. So the list is
         * short and deliberately conservative: the plain reading, then the possessive dropped.
         *
         * Pets are filed by name *and* rarity — `BABY_YETI;3`, with plain `BABY_YETI` not existing at all — so
         * the ladder is appended. **The tier does not have to be the right one:** a pet's skin is identical
         * across its rarities and only the colour of its name changes, so whichever exists draws the correct
         * picture. That is what makes trying them a lookup rather than a guess.
         *
         * Nothing here guesses at enchanted books (a level in roman numerals) or gemstones (a stat). Those
         * need their own handling, and a wrong guess is worse than a miss — it draws a confident icon of the
         * wrong item, which reads as a bug in the drop rather than in the lookup.
         *
         * The tail is only reached by a name that has already missed, and a miss is remembered, so the long
         * list costs a handful of 404s once per unresolvable name rather than once per lookup.
         */
        public fun candidatesFor(displayName: String): List<String> {
            val cleaned = HypixelText.clean(displayName)
            val plain = internalNameFor(cleaned)
            val withoutPossessive = internalNameFor(POSSESSIVE.replace(cleaned, ""))

            return buildList {
                add(plain)
                add(withoutPossessive)
                // The families whose key is not the name. Before the pet ladder, so a chip costs one extra
                // request rather than seven — the ladder is six 404s that only a pet ever gets past.
                addAll(familyKeys(cleaned, plain))
                if (plain.isNotEmpty()) for (tier in PET_TIERS) add("$plain;$tier")
            }.filter { it.isNotEmpty() }.distinct()
        }

        /**
         * Keys for the item families the database files under something other than their name.
         *
         * Each of these was found by looking, not reasoned about: `Rarefinder Chip` is filed as
         * `RAREFINDER_GARDEN_CHIP` and `Pray For Me Vinyl` as `VINYL_PRAY_FOR_ME`, both verified against the
         * live repository. There is no rule that produces either from the words — one expands a category
         * that is not written down, the other moves it to the front — which is why this is a short list of
         * observed shapes and not a clever transformation.
         *
         * Anything not in a known family contributes nothing, so the cost is zero for every other item.
         */
        private fun familyKeys(cleaned: String, plain: String): List<String> {
            val words = cleaned.trim().split(' ').filter { it.isNotEmpty() }
            if (words.size < 2) return emptyList()
            val last = words.last().lowercase()
            val rest = internalNameFor(words.dropLast(1).joinToString(" "))
            if (rest.isEmpty()) return emptyList()

            return when (last) {
                // A Garden chip. The word "Garden" appears in the key and never in the name.
                "chip" -> listOf(rest + "_GARDEN_CHIP")
                // A vinyl. The category leads the key and trails the name.
                "vinyl" -> listOf("VINYL_" + rest)
                else -> emptyList()
            }.filterNot { it == plain }
        }

        /**
         * The rarities a pet can be filed under, likeliest first.
         *
         * Legendary and epic lead because they are what a pet drop is usually announcing. The numbers are the
         * database's own ladder: common, uncommon, rare, epic, legendary, mythic.
         */
        private val PET_TIERS = listOf(4, 3, 2, 1, 0, 5)

        private val UNDERSCORE_RUN = Regex("_{2,}")

        /** A trailing `'s` on a word: the `Necron's` in `Necron's Handle`. */
        private val POSSESSIVE = Regex("['’]s\\b", RegexOption.IGNORE_CASE)
    }
}
