package dev.th7bo.sidequest.platform.core.item

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

    private fun repository() = NeuItemRepository(transport, NoopLogger, baseUrl = BASE)

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

    // -- the name listing ----------------------------------------------------

    /**
     * A name whose key nothing could have derived, resolved through the listing.
     *
     * `Rarefinder Chip` is `RAREFINDER_GARDEN_CHIP` — a category word that appears in the key and nowhere
     * on the item. This is the whole reason the listing is fetched: without it, that family needed a rule,
     * and so does every other one nobody has hit yet.
     */
    @Test
    fun `an item is found through the name listing`() = runTest {
        serveListing("RAREFINDER_GARDEN_CHIP", "HYPERION")
        serve("RAREFINDER_GARDEN_CHIP", chipEntry())

        val item = repository().byDisplayName("Rarefinder Chip")

        assertEquals("RAREFINDER_GARDEN_CHIP", item?.internalName)
        // One request, because the listing named a key that exists rather than guessing at one.
        assertEquals(1, itemRequests().size, itemRequests().map { it.url }.toString())
    }

    /**
     * An unreachable listing is not a broken lookup.
     *
     * The hand-written candidates were the whole answer before the listing existed and remain the fallback,
     * so a client that cannot reach GitHub still resolves everything it could resolve before.
     */
    @Test
    fun `a lookup still works when the listing cannot be fetched`() = runTest {
        transport.on("api.github.com") { HttpExchange.Failure("no route to host") }
        serve("HYPERION", HYPERION)

        assertNotNull(repository().byDisplayName("Hyperion"))
    }

    /** Asked for once. A listing that could not be fetched must not be retried on every drop. */
    @Test
    fun `the listing is attempted once per session`() = runTest {
        transport.on("api.github.com") { HttpExchange.Failure("no route to host") }
        val repository = repository()

        repeat(4) { repository.byDisplayName("Whatever It Is") }

        val listingRequests = transport.requests.count { "api.github.com" in it.url }
        assertEquals(1, listingRequests, "it should give up rather than ask again")
    }

    /** Serves a git tree listing naming [keys], the way GitHub answers it. */
    private fun serveListing(vararg keys: String) {
        transport.respond(
            "git/trees/master",
            """{"tree":[{"path":"items","type":"tree","sha":"itemsha"}]}""",
        )
        transport.respond(
            // `trees/<sha>`, which is what the real endpoint answers. This fixture said
            // `trees/master/<sha>` — the shape the code used — and so agreed with the bug instead of
            // catching it. Checked against the live API.
            "git/trees/itemsha",
            keys.joinToString(",", """{"tree":[""", "]}") { """{"path":"$it.json","type":"blob"}""" },
        )
    }

    private fun chipEntry() = """
        {
          "itemid": "minecraft:paper",
          "displayname": "§9Rarefinder Chip",
          "nbttag": "{ExtraAttributes:{id:\"RAREFINDER_GARDEN_CHIP\"},ItemModel:\"hypixel_skyblock:item/island_relevant/garden/chips/rarefinder_chip\"}",
          "internalname": "RAREFINDER_GARDEN_CHIP"
        }
    """.trimIndent()

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
