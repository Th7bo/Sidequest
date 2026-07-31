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

    private val transport = FakeTransport()

    private fun repository() = NeuItemRepository(transport, NoopLogger, baseUrl = BASE)

    private fun serve(name: String, body: String) {
        transport.respond("$BASE/$name.json", body)
    }

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
        assertEquals(listOf("GIANTS_SWORD", "GIANT_SWORD"), NeuItemRepository.candidatesFor("Giant's Sword"))
        assertEquals(listOf("NECRONS_HANDLE", "NECRON_HANDLE"), NeuItemRepository.candidatesFor("Necron's Handle"))
    }

    /** A name with nothing ambiguous in it is asked for once, not twice. */
    @Test
    fun `an unambiguous name has a single candidate`() {
        assertEquals(listOf("HYPERION"), NeuItemRepository.candidatesFor("Hyperion"))
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
        assertEquals(2, transport.requests.size, "the wrong spelling is tried first, and only once")

        repository.byDisplayName("Necron's Handle")
        assertEquals(2, transport.requests.size, "neither spelling is asked for again")
    }

    @Test
    fun `a second lookup is answered from memory`() = runTest {
        serve("HYPERION", HYPERION)
        val repository = repository()

        repository.byDisplayName("Hyperion")
        repository.byDisplayName("Hyperion")

        assertEquals(1, transport.requests.size, "the second must not hit the network")
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
        transport.respond("$BASE/NOT_A_THING.json", body = "404: Not Found", status = 404)
        val repository = repository()

        repeat(5) { assertNull(repository.byDisplayName("Not A Thing")) }

        assertEquals(1, transport.requests.size)
        assertEquals(1, repository.stats().known404s)
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
        assertEquals(0, repository.stats().known404s, "a blip must not be remembered as absence")

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
