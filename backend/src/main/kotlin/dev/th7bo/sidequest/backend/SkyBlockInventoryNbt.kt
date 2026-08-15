package dev.th7bo.sidequest.backend

import dev.th7bo.sidequest.protocol.ProfileItemSlot
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

/** Small, bounded NBT reader for Hypixel's compressed inventory payloads. */
internal object SkyBlockInventoryNbt {
    fun decode(encoded: String): List<ProfileItemSlot> = runCatching { decodeStrict(encoded) }.getOrDefault(emptyList())

    internal fun decodeStrict(encoded: String): List<ProfileItemSlot> {
        val compressed = Base64.getDecoder().decode(encoded)
        require(compressed.size <= MAX_COMPRESSED)
        return DataInputStream(GZIPInputStream(ByteArrayInputStream(compressed))).use { input ->
            val reader = Reader(input)
            require(reader.byte() == COMPOUND)
            reader.string()
            val root = reader.compound(0)
            val items = root["i"] as? List<*> ?: return emptyList()
            items.take(MAX_SLOTS).mapIndexedNotNull { index, raw ->
                val item = raw as? Map<*, *> ?: return@mapIndexedNotNull null
                if (item.isEmpty()) return@mapIndexedNotNull null
                val tag = item["tag"] as? Map<*, *>
                val attributes = tag?.get("ExtraAttributes") as? Map<*, *>
                val display = tag?.get("display") as? Map<*, *>
                val internalName = attributes?.get("id") as? String
                // Keep Hypixel's legacy formatting intact. Minecraft's native tooltip renderer understands
                // the same colours once the client converts these lines to components.
                val displayName = display?.get("Name") as? String
                val lore = (display?.get("Lore") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                ProfileItemSlot(
                    slot = (item["Slot"] as? Number)?.toInt() ?: index,
                    internalName = internalName,
                    displayName = displayName,
                    count = ((item["Count"] as? Number)?.toInt() ?: 1).coerceAtLeast(1),
                    lore = lore,
                )
            }
        }
    }

    private class Reader(private val input: DataInputStream) {
        private var bytes = 0
        fun byte(): Int = read(1) { input.readUnsignedByte() }
        private fun short(): Short = read(2) { input.readShort() }
        private fun int(): Int = read(4) { input.readInt() }
        private fun long(): Long = read(8) { input.readLong() }
        fun string(): String {
            val size = read(2) { input.readUnsignedShort() }
            require(size <= MAX_STRING)
            val data = ByteArray(size)
            read(size) { input.readFully(data) }
            return data.toString(Charsets.UTF_8)
        }

        fun compound(depth: Int): Map<String, Any?> {
            require(depth <= MAX_DEPTH)
            val result = LinkedHashMap<String, Any?>()
            repeat(MAX_ENTRIES) {
                val type = byte()
                if (type == END) return result
                result[string()] = payload(type, depth + 1)
            }
            error("NBT compound too large")
        }

        private fun payload(type: Int, depth: Int): Any? = when (type) {
            BYTE -> byte().toByte()
            SHORT -> short()
            INT -> int()
            LONG -> long()
            FLOAT -> Float.fromBits(int())
            DOUBLE -> Double.fromBits(long())
            BYTE_ARRAY -> ByteArray(length()).also { data -> read(data.size) { input.readFully(data) } }
            STRING -> string()
            LIST -> {
                val elementType = byte()
                List(length().coerceAtMost(MAX_ENTRIES)) { payload(elementType, depth + 1) }
            }
            COMPOUND -> compound(depth)
            INT_ARRAY -> IntArray(length().coerceAtMost(MAX_ENTRIES)) { int() }
            LONG_ARRAY -> LongArray(length().coerceAtMost(MAX_ENTRIES)) { long() }
            else -> error("Unknown NBT tag $type")
        }

        private fun length(): Int = int().also { require(it in 0..MAX_ENTRIES) }
        private inline fun <T> read(count: Int, block: () -> T): T {
            bytes += count
            require(bytes <= MAX_DECOMPRESSED)
            return block()
        }
    }

    private const val END = 0
    private const val BYTE = 1
    private const val SHORT = 2
    private const val INT = 3
    private const val LONG = 4
    private const val FLOAT = 5
    private const val DOUBLE = 6
    private const val BYTE_ARRAY = 7
    private const val STRING = 8
    private const val LIST = 9
    private const val COMPOUND = 10
    private const val INT_ARRAY = 11
    private const val LONG_ARRAY = 12
    private const val MAX_COMPRESSED = 2_000_000
    private const val MAX_DECOMPRESSED = 16_000_000
    private const val MAX_STRING = 32_768
    private const val MAX_ENTRIES = 16_384
    private const val MAX_SLOTS = 2_000
    private const val MAX_DEPTH = 32
}
