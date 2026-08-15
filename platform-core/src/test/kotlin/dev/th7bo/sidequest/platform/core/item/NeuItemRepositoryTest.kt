package dev.th7bo.sidequest.platform.core.item

import dev.th7bo.sidequest.platform.asset.AssetFetch
import dev.th7bo.sidequest.platform.asset.AssetTransport
import dev.th7bo.sidequest.platform.backend.HttpExchange
import dev.th7bo.sidequest.platform.testkit.FakeTransport
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Looking up what a dropped item looks like.
 *
 * The fixtures are real entries, trimmed — a Hyperion, a Judgement Core (which is a player head, and the case
 * a flat texture cannot draw) and a Revenant Catalyst. Copied from the live repository rather than invented,
 * because the field that matters most sits inside a *string* of 1.8-era NBT and inventing that shape is how a
 * parser passes its tests and fails on the real thing.
 */
class NeuItemRepositoryTest {

    /**
     * Answers 404 for anything not explicitly served.
     *
     * The fake's own default is an unreachable server, and that would be the wrong shape here: a lookup tries
     * several spellings of a name, and on the real host every spelling that does not exist comes back 404 —
     * which is remembered — rather than as a network failure, which deliberately is not.
     */
    private val transport = FakeTransport().apply {
        fallback = { HttpExchange.Response(status = 404, body = "404: Not Found", headers = emptyMap()) }
    }

    private fun repository(archives: AssetTransport? = null, cache: NeuNameCache? = null) =
        NeuItemRepository(transport, NoopLogger, baseUrl = BASE, archives = archives, cache = cache)

    /** Answers the "which commit is it on" probe, which precedes any download. */
    private fun serveHeadRef(sha: String = "abc123") {
        transport.respond("git/refs/heads/master", """{"object":{"sha":"$sha"}}""")
    }

    private fun serve(name: String, body: String) {
        transport.respond("$BASE/$name.json", body)
    }

    /**
     * Requests for items, ignoring the one-off attempt to fetch the name listing.
     *
     * The repository asks GitHub once per session for every key it has. That is not an item lookup and
     * counting it here would make every assertion about "how many times did it ask" mean something else.
     */
    private fun itemRequests() = transport.requests.filter { it.url.startsWith("$BASE/") }

    // -- resolving a name ----------------------------------------------------

    @Test
    fun `a display name becomes the repository's key`() {
        assertEquals("HYPERION", NeuItemRepository.internalNameFor("Hyperion"))
        assertEquals("REVENANT_CATALYST", NeuItemRepository.internalNameFor("Revenant Catalyst"))
        assertEquals("POCKET_ESPRESSO_MACHINE", NeuItemRepository.internalNameFor("Pocket Espresso Machine"))
    }

    /** Formatting codes reach this from chat, and would otherwise become underscores. */
    @Test
    fun `formatting codes are stripped before the key is built`() {
        assertEquals("HYPERION", NeuItemRepository.internalNameFor("§6Hyperion"))
        assertEquals("JUDGEMENT_CORE", NeuItemRepository.internalNameFor("§r§6Judgement Core§r"))
    }

    @Test
    fun `punctuation collapses rather than producing empty segments`() {
        assertEquals("A_B", NeuItemRepository.internalNameFor("  a  --  b  "))
    }

    /** Verified against the live repository: `GIANTS_SWORD` exists and `GIANT_S_SWORD` does not. */
    @Test
    fun `an apostrophe vanishes rather than becoming an underscore`() {
        assertEquals("GIANTS_SWORD", NeuItemRepository.internalNameFor("Giant's Sword"))
        assertEquals("NECRONS_HANDLE", NeuItemRepository.internalNameFor("Necron's Handle"))
    }

    /**
     * The same apostrophe, filed two different ways.
     *
     * `Giant's Sword` is `GIANTS_SWORD` and `Necron's Handle` is `NECRON_HANDLE` — both verified against the
     * live repository. There is no rule that produces both, because the keys are Hypixel's item ids and were
     * never derived from the names, so the lookup tries the plain reading and then the possessive dropped.
     */
    @Test
    fun `a possessive is offered both ways round`() {
        assertEquals(
            listOf("GIANTS_SWORD", "GIANT_SWORD"),
            NeuItemRepository.candidatesFor("Giant's Sword").take(2),
        )
        assertEquals(
            listOf("NECRONS_HANDLE", "NECRON_HANDLE"),
            NeuItemRepository.candidatesFor("Necron's Handle").take(2),
        )
    }

    /**
     * A Garden chip is filed under a word that is not in its name.
     *
     * `Rarefinder Chip` is `RAREFINDER_GARDEN_CHIP` — the category is written into the key and nowhere on
     * the item. Verified against the live repository, like every other shape here, because nothing about
     * the words could have told anybody that.
     */
    @Test
    fun `a chip is offered under its garden key`() {
        val candidates = NeuItemRepository.candidatesFor("Rarefinder Chip")

        assertTrue("RAREFINDER_GARDEN_CHIP" in candidates, candidates.toString())
        assertTrue(
            candidates.indexOf("RAREFINDER_GARDEN_CHIP") < candidates.indexOf("RAREFINDER_CHIP;4"),
            "the family key should be tried before the pet ladder: $candidates",
        )
    }

    /** A vinyl moves its category to the front: `Pray For Me Vinyl` is `VINYL_PRAY_FOR_ME`. */
    @Test
    fun `a vinyl is offered with its category first`() {
        val candidates = NeuItemRepository.candidatesFor("Pray For Me Vinyl")

        assertTrue("VINYL_PRAY_FOR_ME" in candidates, candidates.toString())
    }

    /** Nothing else pays for those two rules. */
    @Test
    fun `an ordinary name gains no family keys`() {
        val candidates = NeuItemRepository.candidatesFor("Revenant Catalyst")

        assertTrue(candidates.none { it.contains("GARDEN") || it.startsWith("VINYL_") }, candidates.toString())
    }

    /** The plain reading is always tried first, so a name that resolves costs one request and not seven. */
    @Test
    fun `the plain reading leads`() {
        assertEquals("HYPERION", NeuItemRepository.candidatesFor("Hyperion").first())
    }

    /**
     * Pets are filed by name and rarity, and the plain name does not exist at all.
     *
     * `BABY_YETI` is a 404; `BABY_YETI;3` is a Baby Yeti. Which tier is asked for does not matter, because a
     * pet's skin is the same across its rarities — only the colour of its name changes.
     */
    @Test
    fun `a pet is offered at every rarity`() {
        val candidates = NeuItemRepository.candidatesFor("Baby Yeti")

        assertEquals("BABY_YETI", candidates.first(), "the plain name still leads, in case it exists")
        assertTrue("BABY_YETI;3" in candidates, "epic, which is what a Baby Yeti drops as: $candidates")
        assertTrue("BABY_YETI;4" in candidates, "legendary: $candidates")
        assertEquals(candidates.size, candidates.distinct().size, "no name is asked for twice")
    }

    // -- a pet somebody owns -------------------------------------------------

    /**
     * The rarity a pet is *held* at is not always a rarity it is *filed* at.
     *
     * Verified against the live repository: `GOLDEN_DRAGON` exists only as `;4`, so a Kat-upgraded mythic one
     * — which Hypixel reports as `MYTHIC`, wanting `;5` — has no entry of its own. Stopping at the exact
     * rarity would draw a blank Steve head for it, so the ladder has to be walked.
     */
    @Test
    fun `a pet held at a rarity it is not filed at still resolves`() {
        val candidates = NeuItemRepository.petCandidatesFor("GOLDEN_DRAGON", "MYTHIC")

        assertEquals("GOLDEN_DRAGON;5", candidates.first(), "the rarity it is actually held at leads")
        assertTrue("GOLDEN_DRAGON;4" in candidates, "the rarity it exists at is reachable: $candidates")
        assertEquals(candidates.size, candidates.distinct().size, "no key is asked for twice")
    }

    /** An unknown rarity is not a reason to give up: the ladder alone still finds the animal. */
    @Test
    fun `an unrecognised rarity falls back to the ladder`() {
        val candidates = NeuItemRepository.petCandidatesFor("TIGER", "UNKNOWN")

        assertEquals("TIGER;4", candidates.first())
        assertTrue(candidates.size >= 6, candidates.toString())
    }

    /**
     * A skinned pet does not look like the pet, so its skin is asked for first.
     *
     * Hypixel names the skin without the prefix the database files it under — `WOLF_DOGE` against
     * `PET_SKIN_WOLF_DOGE` — so both spellings are offered rather than one being assumed.
     */
    @Test
    fun `a skin outranks the animal underneath it`() {
        val candidates = NeuItemRepository.petCandidatesFor("WOLF", "LEGENDARY", skin = "WOLF_DOGE")

        assertEquals(listOf("PET_SKIN_WOLF_DOGE", "WOLF_DOGE"), candidates.take(2))
        assertTrue("WOLF;4" in candidates, "the bare wolf is still the fallback: $candidates")
    }

    /** A skin Hypixel already prefixed is not prefixed twice. */
    @Test
    fun `an already prefixed skin is left alone`() {
        val candidates = NeuItemRepository.petCandidatesFor("WOLF", "LEGENDARY", skin = "PET_SKIN_WOLF_DOGE")

        assertEquals("PET_SKIN_WOLF_DOGE", candidates.first())
        assertTrue(candidates.none { it.startsWith("PET_SKIN_PET_SKIN_") }, candidates.toString())
    }

    // -- reading an entry ----------------------------------------------------

    @Test
    fun `an ordinary item yields its Minecraft id`() = runTest {
        serve("HYPERION", HYPERION)

        val item = repository().byDisplayName("Hyperion")

        assertNotNull(item)
        assertEquals("HYPERION", item!!.internalName)
        assertEquals("minecraft:iron_sword", item.minecraftId)
        assertEquals("Hyperion", item.displayName, "the display name is cleaned of its colour")
        assertFalse(item.isPlayerHead)
    }

    /**
     * The case that justifies the whole repository.
     *
     * Most of what SkyBlock adds is a player head with a custom skin — it is how the game has custom-looking
     * items at all — and the texture is buried in a string of 1.8 NBT rather than in the JSON.
     */
    @Test
    fun `a player head yields its skin`() = runTest {
        serve("JUDGEMENT_CORE", JUDGEMENT_CORE)

        val item = repository().byDisplayName("Judgement Core")

        assertNotNull(item)
        assertEquals("minecraft:player_head", item!!.minecraftId, "not the 1.8 `minecraft:skull`")
        assertTrue(item.isPlayerHead)
        assertEquals("ewogICJ0aW1lc3RhbXAiIDogMTU5", item.skullTexture)
    }

    /**
     * The reason there is no flattening table in this mod.
     *
     * `minecraft:dye` with damage 4 is lapis and with damage 2 is green dye, and nothing about the id says so
     * — but the entry records the modern model it resolves to, so the answer is read rather than derived.
     */
    @Test
    fun `a flattened item is read from its recorded model`() = runTest {
        serve("ENCHANTED_LAPIS_LAZULI", ENCHANTED_LAPIS)

        assertEquals("minecraft:lapis_lazuli", repository().byDisplayName("Enchanted Lapis Lazuli")?.minecraftId)
    }

    /**
     * An item Hypixel draws with its own resource pack.
     *
     * There is no `hypixel_skyblock:` anything on this client, so the vanilla item underneath is drawn
     * instead — a Sniper Bow as a bow. Not what Hypixel shows, and better than an empty slot: it is what the
     * item looked like before the pack existed.
     */
    @Test
    fun `an item with a Hypixel model falls back to its vanilla base`() = runTest {
        serve("SNIPER_BOW", SNIPER_BOW)

        assertEquals("minecraft:bow", repository().byDisplayName("Sniper Bow")?.minecraftId)
    }

    /** The one entry in a sample of twenty-five with no recorded model. Heads are the case worth mapping. */
    @Test
    fun `a legacy skull with no model is still a head`() = runTest {
        serve("PET_SKIN_ROCK_LAUGH", PET_SKIN_NO_MODEL)

        assertEquals("minecraft:player_head", repository().byDisplayName("Pet Skin Rock Laugh")?.minecraftId)
    }

    @Test
    fun `an item with no skull has no texture`() = runTest {
        serve("REVENANT_CATALYST", REVENANT_CATALYST)

        val item = repository().byDisplayName("Revenant Catalyst")

        assertNull(item?.skullTexture)
        assertEquals("minecraft:paper", item?.minecraftId)
    }

    // -- the name index ------------------------------------------------------

    /**
     * A name whose key nothing could have derived, resolved through the database's own display names.
     *
     * `Rarefinder Chip` is `RAREFINDER_GARDEN_CHIP`. What makes this worth a test is not that it resolves —
     * a hand-written rule managed that — but that it resolves in *one* request, because the index named a
     * key that exists rather than guessing at several.
     */
    @Test
    fun `an item is found through the name index`() = runTest {
        serveHeadRef()
        val archives = FakeArchives(tarGz("Rarefinder Chip" to "RAREFINDER_GARDEN_CHIP"))
        serve("RAREFINDER_GARDEN_CHIP", chipEntry())

        val item = repository(archives).byDisplayName("Rarefinder Chip")

        assertEquals("RAREFINDER_GARDEN_CHIP", item?.internalName)
        assertEquals(1, itemRequests().size, itemRequests().map { it.url }.toString())
    }

    /**
     * An unreachable database is not a broken lookup.
     *
     * The hand-written candidates were the whole answer before the index existed and remain the fallback,
     * so a client that cannot fetch nine megabytes still resolves everything it resolved before.
     */
    @Test
    fun `a lookup still works when the archive cannot be fetched`() = runTest {
        val archives = FakeArchives(bytes = null)
        serve("HYPERION", HYPERION)

        assertNotNull(repository(archives).byDisplayName("Hyperion"))
    }

    /** Fetched once. Nine megabytes per rare drop would be the worst bug in the mod. */
    @Test
    fun `the archive is fetched once per session`() = runTest {
        serveHeadRef()
        val archives = FakeArchives(tarGz("Hyperion" to "HYPERION"))
        serve("HYPERION", HYPERION)
        val repository = repository(archives)

        repeat(4) { repository.byDisplayName("Hyperion") }

        assertEquals(1, archives.fetches, "it should hold the index rather than refetch it")
    }

    /** An archive that fails to download is not retried on every drop either. */
    @Test
    fun `a failed archive fetch is not retried`() = runTest {
        val archives = FakeArchives(bytes = null)
        val repository = repository(archives)

        repeat(4) { repository.byDisplayName("Whatever It Is") }

        assertEquals(1, archives.fetches, "it should give up rather than ask again")
    }

    /** Answers with an archive, or refuses. Counts how often it was asked. */
    private class FakeArchives(private val bytes: ByteArray?) : AssetTransport {
        var fetches = 0
            private set

        override suspend fun fetch(url: String, maxBytes: Long): AssetFetch {
            fetches++
            return bytes?.let { AssetFetch.Body(it) } ?: AssetFetch.Failure("no route to host")
        }
    }

    /**
     * A `.tar.gz` holding one item file per pair.
     *
     * Synthetic, and only proves the repository *uses* what it reads. That the reader copes with what
     * GitHub actually serves is `NeuArchiveRealTest`, against a real archive — a tar written by the same
     * test that reads it would only prove the two agree.
     */
    private fun tarGz(vararg pairs: Pair<String, String>): ByteArray {
        val tar = java.io.ByteArrayOutputStream()
        for ((display, key) in pairs) {
            val body = ("{\"internalname\":\"" + key + "\",\"displayname\":\"" + display +
                "\",\"itemid\":\"minecraft:paper\"}").toByteArray()
            val header = ByteArray(BLOCK)
            val name = ("repo/items/" + key + ".json").toByteArray()
            name.copyInto(header, 0, 0, minOf(name.size, 100))
            // Size as octal text, and the type byte, at the offsets a tar header defines.
            "%011o ".format(body.size).toByteArray().copyInto(header, 124)
            header[156] = '0'.code.toByte()
            // The checksum field counts as spaces while the sum is taken.
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            val sum = header.sumOf { it.toInt() and 0xFF }
            "%06o  ".format(sum).toByteArray().copyInto(header, 148)

            tar.write(header)
            tar.write(body)
            val padding = (BLOCK - body.size % BLOCK) % BLOCK
            tar.write(ByteArray(padding))
        }
        tar.write(ByteArray(BLOCK * 2))

        val gzipped = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(gzipped).use { it.write(tar.toByteArray()) }
        return gzipped.toByteArray()
    }

    /** A real entry, trimmed. The chip whose key nothing could have derived. */
    private fun chipEntry(): String = CHIP_ENTRY

    // -- fetching ------------------------------------------------------------

    /**
     * The case the candidate list exists for.
     *
     * `NECRONS_HANDLE` does not exist and `NECRON_HANDLE` does, so the first ask 404s and the second answers.
     * A single-guess lookup would draw no icon for one of SkyBlock's better-known items.
     */
    @Test
    fun `a name filed under its other spelling is still found`() = runTest {
        transport.respond("$BASE/NECRONS_HANDLE.json", body = "404: Not Found", status = 404)
        serve("NECRON_HANDLE", NECRON_HANDLE)
        val repository = repository()

        val item = repository.byDisplayName("Necron's Handle")

        assertEquals("NECRON_HANDLE", item?.internalName)
        assertEquals(2, itemRequests().size, "the wrong spelling is tried first, and only once")

        repository.byDisplayName("Necron's Handle")
        assertEquals(2, itemRequests().size, "neither spelling is asked for again")
    }

    /**
     * A pet drop, all the way through.
     *
     * `§6§lPET DROP! §r§5Baby Yeti` reaches the repository as `Baby Yeti`, and nothing about that name says
     * it is a pet or which rarity it dropped at. The plain name 404s, the legendary tier 404s, and the epic
     * one answers — which is the picture, because a pet's skin does not vary by tier.
     */
    @Test
    fun `a pet resolves through its rarity suffix`() = runTest {
        serve("BABY_YETI;3", BABY_YETI)
        val repository = repository()

        val item = repository.byDisplayName("Baby Yeti")

        assertEquals("BABY_YETI;3", item?.internalName)
        assertEquals("minecraft:player_head", item?.minecraftId)
        assertEquals("ewogICJ0aW1lc3Rh", item?.skullTexture)

        assertEquals(
            listOf("$BASE/BABY_YETI.json", "$BASE/BABY_YETI;4.json", "$BASE/BABY_YETI;3.json"),
            itemRequests().map { it.url },
            "it stops at the first tier that exists, rather than walking the whole ladder",
        )

        repository.byDisplayName("Baby Yeti")
        assertEquals(3, itemRequests().size, "the two misses are remembered along with the hit")
    }

    @Test
    fun `a second lookup is answered from memory`() = runTest {
        serve("HYPERION", HYPERION)
        val repository = repository()

        repository.byDisplayName("Hyperion")
        repository.byDisplayName("Hyperion")

        assertEquals(1, itemRequests().size, "the second must not hit the network")
        assertEquals(1, repository.stats().hits)
    }

    /**
     * A name the repository does not have is remembered.
     *
     * Not an optimisation: a drop whose name does not resolve is the common case — every enchanted book,
     * every renamed item — and a render path asking once a frame would send a request a frame, forever, for
     * something that will never exist.
     */
    @Test
    fun `a missing item is asked for once`() = runTest {
        val repository = repository()

        assertNull(repository.byDisplayName("Not A Thing"))
        val asked = itemRequests().size
        assertTrue(asked > 1, "it tried more than one spelling before giving up")

        repeat(5) { assertNull(repository.byDisplayName("Not A Thing")) }

        assertEquals(asked, itemRequests().size, "every spelling it tried is remembered as missing")
        assertEquals(asked, repository.stats().known404s)
    }

    /**
     * A network failure is not the item not existing.
     *
     * Conflating them would leave every icon blank until a restart, because the first lookup during a
     * hiccup would poison the name permanently.
     */
    @Test
    fun `an unreachable repository is retried`() = runTest {
        transport.on("$BASE/HYPERION.json") { HttpExchange.Failure("connection refused") }
        val repository = repository()

        assertNull(repository.byDisplayName("Hyperion"))

        // The proof that the failure was not recorded as absence: the same name resolves once the host is
        // back. Had the two been conflated, this would stay null for the life of the session.
        serve("HYPERION", HYPERION)
        assertNotNull(repository.byDisplayName("Hyperion"))
    }

    @Test
    fun `a malformed entry is refused rather than throwing`() = runTest {
        serve("HYPERION", "{ this is not json")

        assertNull(repository().byDisplayName("Hyperion"))
    }

    @Test
    fun `an entry with no item id is refused`() = runTest {
        serve("HYPERION", """{"displayname":"§6Hyperion","internalname":"HYPERION"}""")

        assertNull(repository().byDisplayName("Hyperion"))
    }

    @Test
    fun `resident answers without any IO`() = runTest {
        serve("HYPERION", HYPERION)
        val repository = repository()

        assertNull(repository.resident("Hyperion"), "nothing is resident before it is fetched")
        repository.byDisplayName("Hyperion")
        assertNotNull(repository.resident("Hyperion"))
    }

    private companion object {
        /** A tar block. See tarGz. */
        const val BLOCK = 512

        val CHIP_ENTRY = """
            {
              "itemid": "minecraft:paper",
              "displayname": "\u00A79Rarefinder Chip",
              "internalname": "RAREFINDER_GARDEN_CHIP"
            }
        """.trimIndent()


        const val BASE = "https://example.invalid/items"

        /** Trimmed from the live entry: the fields this mod reads, in the shape the repository writes them. */
        val HYPERION = """
            {
              "itemid": "minecraft:iron_sword",
              "displayname": "§6Hyperion",
              "nbttag": "{ExtraAttributes:{id:\"HYPERION\"},HideFlags:254,ItemModel:\"hypixel_skyblock:item/uncategorized/hyperion\",Unbreakable:1B}",
              "internalname": "HYPERION"
            }
        """.trimIndent()

        /**
         * Note the `Signature` sitting between the name and the value — the real shape, and the reason the
         * skin is found by scanning for `Value:` rather than by assuming it follows `Name:"textures"`.
         */
        val JUDGEMENT_CORE = """
            {
              "itemid": "minecraft:skull",
              "displayname": "§6Judgement Core",
              "nbttag": "{ExtraAttributes:{id:\"JUDGEMENT_CORE\"},HideFlags:254,ItemModel:\"minecraft:player_head\",SkullOwner:{Id:\"6e38aa1a-fa96-3f13-a59e-0cf8f5d57b61\",Properties:{textures:[0:{Name:\"textures\",Signature:\"Xn1qJGjSWlfMy4Ea\",Value:\"ewogICJ0aW1lc3RhbXAiIDogMTU5\"}]}}}",
              "internalname": "JUDGEMENT_CORE"
            }
        """.trimIndent()

        val ENCHANTED_LAPIS = """
            {
              "itemid": "minecraft:dye",
              "damage": 4,
              "displayname": "§aEnchanted Lapis Lazuli",
              "nbttag": "{ExtraAttributes:{id:\"ENCHANTED_LAPIS_LAZULI\"},HideFlags:254,ItemModel:\"minecraft:lapis_lazuli\"}",
              "internalname": "ENCHANTED_LAPIS_LAZULI"
            }
        """.trimIndent()

        val SNIPER_BOW = """
            {
              "itemid": "minecraft:bow",
              "displayname": "§9Sniper Bow",
              "nbttag": "{ExtraAttributes:{id:\"SNIPER_BOW\"},HideFlags:254,ItemModel:\"hypixel_skyblock:item/uncategorized/sniper_bow\"}",
              "internalname": "SNIPER_BOW"
            }
        """.trimIndent()

        /**
         * A pet, at one of its rarities.
         *
         * Note the display name carries a `{LVL}` placeholder and the level is not part of the key — the
         * whole reason a pet has to be looked up by `NAME;TIER` rather than by anything in the drop line.
         */
        val BABY_YETI = """
            {
              "itemid": "minecraft:skull",
              "damage": 3,
              "displayname": "§7[Lvl {LVL}] §5Baby Yeti",
              "nbttag": "{ExtraAttributes:{id:\"BABY_YETI;3\"},HideFlags:254,ItemModel:\"minecraft:player_head\",SkullOwner:{Id:\"7dc4f3ba-6b6f-330a-9a22-791218c81017\",Properties:{textures:[0:{Name:\"textures\",Signature:\"AqUDIyzA4nHkaO0b\",Value:\"ewogICJ0aW1lc3Rh\"}]}}}",
              "internalname": "BABY_YETI;3"
            }
        """.trimIndent()

        /** No `ItemModel`, and its skin sits directly after the index with no `Name` in front of it. */
        val PET_SKIN_NO_MODEL = """
            {
              "itemid": "minecraft:skull",
              "damage": 3,
              "displayname": "§9Laughing Rock Skin",
              "nbttag": "{overrideMeta:1b,HideFlags:254,SkullOwner:{Id:\"bec85db9-fd91-396d-861e-8ec67ac1eda9\",Properties:{textures:[0:{Value:\"eyJ0ZXh0dXJlcyI6\"}]}}}",
              "internalname": "PET_SKIN_ROCK_LAUGH"
            }
        """.trimIndent()

        /** The one whose key nobody could have derived. Its `nbttag` also carries an escaped apostrophe. */
        val NECRON_HANDLE = """
            {
              "itemid": "minecraft:stick",
              "displayname": "§6Necron's Handle",
              "nbttag": "{ExtraAttributes:{id:\"NECRON_HANDLE\"},HideFlags:254,display:{Name:\"§6Necron's Handle\"}}",
              "internalname": "NECRON_HANDLE"
            }
        """.trimIndent()

        val REVENANT_CATALYST = """
            {
              "itemid": "minecraft:paper",
              "displayname": "§5Revenant Catalyst",
              "nbttag": "{ExtraAttributes:{id:\"REVENANT_CATALYST\"},HideFlags:254,ItemModel:\"hypixel_skyblock:item/slayer/zombie/catalysts/revenant_catalyst\"}",
              "internalname": "REVENANT_CATALYST"
            }
        """.trimIndent()
    }
}
