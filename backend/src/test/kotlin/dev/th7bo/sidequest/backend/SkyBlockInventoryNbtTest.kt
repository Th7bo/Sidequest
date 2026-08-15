package dev.th7bo.sidequest.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

class SkyBlockInventoryNbtTest {
    @Test
    fun `decodes item slots and skyblock identifiers`() {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { gzip ->
            DataOutputStream(gzip).use { out ->
                out.writeByte(10); out.writeUTF("")
                out.writeByte(9); out.writeUTF("i"); out.writeByte(10); out.writeInt(1)
                out.writeByte(1); out.writeUTF("Slot"); out.writeByte(4)
                out.writeByte(1); out.writeUTF("Count"); out.writeByte(3)
                out.writeByte(10); out.writeUTF("tag")
                out.writeByte(10); out.writeUTF("ExtraAttributes")
                out.writeByte(8); out.writeUTF("id"); out.writeUTF("ASPECT_OF_THE_END")
                out.writeByte(0)
                out.writeByte(10); out.writeUTF("display")
                out.writeByte(8); out.writeUTF("Name"); out.writeUTF("§5Aspect of the End")
                out.writeByte(0)
                out.writeByte(0)
                out.writeByte(0)
                out.writeByte(0)
            }
        }

        val item = SkyBlockInventoryNbt.decodeStrict(Base64.getEncoder().encodeToString(bytes.toByteArray())).single()
        assertEquals(4, item.slot)
        assertEquals(3, item.count)
        assertEquals("ASPECT_OF_THE_END", item.internalName)
        assertEquals("§5Aspect of the End", item.displayName)
    }

    /**
     * Every pet stack calls itself `PET`.
     *
     * Its identity is in `ExtraAttributes.petInfo`, which is a *string* of JSON rather than a compound —
     * confirmed against SkyCrypt, which parses the same field the same way. Reading the plain id gave a
     * name the item database has never heard of, so a pet in an inventory drew the missing-item barrier
     * while the same pet on the Pets tab drew correctly.
     */
    @Test
    fun `a pet stack is identified by what is inside it`() {
        val info = """{"type":"GOLDEN_DRAGON","active":true,"exp":2.1E8,"tier":"MYTHIC","candyUsed":0}"""

        assertEquals("GOLDEN_DRAGON;5", SkyBlockInventoryNbt.petIdentity(mapOf("id" to "PET", "petInfo" to info)))
    }

    /** A skinned pet does not look like the pet, so the skin wins — under the prefix the database uses. */
    @Test
    fun `a pet skin outranks the animal`() {
        val info = """{"type":"WOLF","tier":"LEGENDARY","skin":"WOLF_DOGE"}"""

        assertEquals("PET_SKIN_WOLF_DOGE", SkyBlockInventoryNbt.petIdentity(mapOf("petInfo" to info)))
    }

    /**
     * A null skin is not a skin.
     *
     * Hypixel writes `"skin":null` rather than omitting the field, and a reader that searched for the next
     * quotation mark after the colon would run on into the following field and call the pet `PET_SKIN_EPIC`.
     */
    @Test
    fun `a null skin falls through to the animal`() {
        val info = """{"type":"TIGER","skin":null,"tier":"EPIC","exp":1.0}"""

        assertEquals("TIGER;3", SkyBlockInventoryNbt.petIdentity(mapOf("petInfo" to info)))
    }

    /** Anything that is not a pet is left exactly as it was. */
    @Test
    fun `an ordinary item is not mistaken for a pet`() {
        assertEquals(null, SkyBlockInventoryNbt.petIdentity(mapOf("id" to "HYPERION")))
        assertEquals(null, SkyBlockInventoryNbt.petIdentity(null))
        assertEquals(null, SkyBlockInventoryNbt.petIdentity(mapOf("petInfo" to """{"tier":"EPIC"}""")))
    }
}
