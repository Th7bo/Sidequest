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
}
