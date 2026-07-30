package dev.th7bo.sidequest.platform.core.asset

import dev.th7bo.sidequest.platform.asset.ImageSize

/**
 * Reads what a file says about itself, without decoding it.
 *
 * The whole reason this exists rather than a call to an image library: **an image's dimensions have to be
 * known before anything allocates its pixels.** A 40,000-square PNG of one flat colour compresses to a few
 * kilobytes, so a size limit lets it straight through, and decoding it asks for six gigabytes. Reading the
 * twenty-fourth byte of the file instead costs nothing and is the only check that actually helps.
 *
 * Everything here is total. A truncated or malformed file returns null; nothing throws, because every input
 * arrives from the network and a parser that throws on hostile input is a crash waiting to be triggered.
 */
internal object ImageHeaders {

    /**
     * A PNG's dimensions.
     *
     * The IHDR chunk is mandated to be first, so width and height are at fixed offsets: an 8-byte signature,
     * a 4-byte chunk length, the 4-byte type "IHDR", then two big-endian 32-bit integers.
     */
    fun png(bytes: ByteArray): ImageSize? {
        if (bytes.size < PNG_HEADER_BYTES) return null
        if (!bytes.startsWith(PNG_IHDR_OFFSET, IHDR)) return null

        val width = bytes.intAt(PNG_WIDTH_OFFSET) ?: return null
        val height = bytes.intAt(PNG_HEIGHT_OFFSET) ?: return null
        return sizeOrNull(width, height)
    }

    /**
     * A JPEG's dimensions.
     *
     * No fixed offset — a JPEG is a chain of marker segments and the frame header can be anywhere in it, past
     * however much EXIF a phone decided to include. So this walks the chain, which is also what makes the
     * bound below necessary.
     */
    fun jpeg(bytes: ByteArray): ImageSize? {
        if (bytes.size < 4) return null
        if ((bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) return null

        var offset = 2
        var segments = 0
        while (offset + 3 < bytes.size) {
            // A segment starts with 0xFF. Any number of 0xFF bytes may pad the gap before one.
            if ((bytes[offset].toInt() and 0xFF) != 0xFF) return null
            var markerAt = offset
            while (markerAt < bytes.size && (bytes[markerAt].toInt() and 0xFF) == 0xFF) markerAt++
            if (markerAt >= bytes.size) return null

            val marker = bytes[markerAt].toInt() and 0xFF
            // Standalone markers carry no length field, so there is nothing to skip over.
            if (marker == 0x01 || marker in 0xD0..0xD9) {
                offset = markerAt + 1
                continue
            }
            // Start of Scan: the compressed data begins and there is no frame header after it.
            if (marker == 0xDA) return null

            val length = bytes.shortAt(markerAt + 1) ?: return null
            // A segment's length includes its own two bytes; anything less is malformed and would not advance.
            if (length < 2) return null

            if (marker in SOF_MARKERS) {
                // precision(1), height(2), width(2)
                val height = bytes.shortAt(markerAt + 4) ?: return null
                val width = bytes.shortAt(markerAt + 6) ?: return null
                return sizeOrNull(width, height)
            }

            offset = markerAt + 1 + length
            // A bound on the walk. A file made of thousands of tiny segments is not a photograph, and an
            // unbounded loop over attacker-supplied structure is worth refusing on principle.
            if (++segments > MAX_SEGMENTS) return null
        }
        return null
    }

    /** Dispatches on what the bytes are, which by this point has already been sniffed rather than declared. */
    fun read(bytes: ByteArray): ImageSize? = png(bytes) ?: jpeg(bytes)

    private fun sizeOrNull(width: Int, height: Int): ImageSize? =
        if (width <= 0 || height <= 0) null else ImageSize(width, height)

    private fun ByteArray.startsWith(offset: Int, expected: ByteArray): Boolean {
        if (size < offset + expected.size) return false
        return expected.indices.all { this[offset + it] == expected[it] }
    }

    /** A big-endian 32-bit read, or null past the end. Returns null for a negative, which is never a size. */
    private fun ByteArray.intAt(offset: Int): Int? {
        if (offset + 4 > size) return null
        val value = ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)
        return if (value < 0) null else value
    }

    private fun ByteArray.shortAt(offset: Int): Int? {
        if (offset + 2 > size) return null
        return ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)
    }

    private val IHDR = byteArrayOf('I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte())

    private const val PNG_IHDR_OFFSET = 12
    private const val PNG_WIDTH_OFFSET = 16
    private const val PNG_HEIGHT_OFFSET = 20
    private const val PNG_HEADER_BYTES = 24

    /** Start-of-frame markers. The gaps are other things: 0xC4 is a Huffman table, 0xC8 is reserved. */
    private val SOF_MARKERS = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)

    private const val MAX_SEGMENTS = 256
}

/**
 * The same idea for audio: check the container really holds audio, without decoding any.
 *
 * "Is it an Ogg file" is a four-byte check and is not enough on its own — Ogg is a container and can carry
 * anything. So this also reads the first packet's codec identifier, which is what distinguishes a sound
 * somebody uploaded from an Ogg stream of something that is not sound at all.
 */
internal object AudioHeaders {

    /** The codecs Minecraft's audio pipeline can actually play. */
    enum class Codec { VORBIS, OPUS }

    /**
     * The codec inside an Ogg stream, or null when it is not one this mod plays.
     *
     * The page header is 27 bytes plus a segment table, and the first packet follows it — so the codec
     * identifier's position depends on the segment count and cannot be a fixed offset.
     */
    fun ogg(bytes: ByteArray): Codec? {
        if (bytes.size < OGG_HEADER_BYTES) return null
        if (bytes[0] != 'O'.code.toByte() || bytes[1] != 'g'.code.toByte() ||
            bytes[2] != 'g'.code.toByte() || bytes[3] != 'S'.code.toByte()
        ) {
            return null
        }
        // Only version 0 has ever existed. Anything else is not a file this was written against.
        if (bytes[4].toInt() != 0) return null

        val segments = bytes[OGG_SEGMENT_COUNT_OFFSET].toInt() and 0xFF
        val packet = OGG_HEADER_BYTES + segments
        if (packet >= bytes.size) return null

        return when {
            bytes.matchesAt(packet, VORBIS_ID) -> Codec.VORBIS
            bytes.matchesAt(packet, OPUS_ID) -> Codec.OPUS
            else -> null
        }
    }

    private fun ByteArray.matchesAt(offset: Int, expected: ByteArray): Boolean {
        if (size < offset + expected.size) return false
        return expected.indices.all { this[offset + it] == expected[it] }
    }

    private const val OGG_HEADER_BYTES = 27
    private const val OGG_SEGMENT_COUNT_OFFSET = 26

    /** Packet type 1 followed by "vorbis". */
    private val VORBIS_ID = byteArrayOf(1, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())

    private val OPUS_ID = "OpusHead".toByteArray(Charsets.US_ASCII)
}
