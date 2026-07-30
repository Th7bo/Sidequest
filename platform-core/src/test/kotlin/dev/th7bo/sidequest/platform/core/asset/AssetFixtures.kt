package dev.th7bo.sidequest.platform.core.asset

import dev.th7bo.sidequest.platform.asset.AssetFetch
import dev.th7bo.sidequest.platform.asset.AssetId
import dev.th7bo.sidequest.platform.asset.AssetStore
import dev.th7bo.sidequest.platform.asset.AssetTransport
import dev.th7bo.sidequest.platform.asset.StoredAsset
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Real files, built byte by byte.
 *
 * Not loaded from resources on purpose: a test that says "a 40,000-pixel-wide PNG is refused" has to be able
 * to *produce* one, and a checked-in file cannot be varied. Building them here also means the malformed cases
 * — a truncated header, a JPEG that never reaches its frame marker — are one parameter away from the valid
 * one rather than a separate binary nobody can read in a diff.
 */
internal object AssetFixtures {

    /**
     * A structurally valid PNG of the given dimensions.
     *
     * Header-accurate rather than decodable: it carries a correct signature, a correct IHDR with a real CRC
     * and an IEND, which is everything the policy inspects. Producing genuinely compressed pixel data for a
     * 40,000-square image would defeat the point of testing that we never allocate it.
     */
    fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        val ihdr = ByteArrayOutputStream()
        ihdr.write("IHDR".toByteArray(Charsets.US_ASCII))
        ihdr.writeInt(width)
        ihdr.writeInt(height)
        ihdr.write(8)  // bit depth
        ihdr.write(6)  // colour type: RGBA
        ihdr.write(0)  // compression
        ihdr.write(0)  // filter
        ihdr.write(0)  // interlace
        out.writeChunk(ihdr.toByteArray())

        val iend = "IEND".toByteArray(Charsets.US_ASCII)
        out.writeChunk(iend)
        return out.toByteArray()
    }

    /**
     * A JPEG whose frame header says [width] by [height].
     *
     * @param leadingSegments how many filler APP segments to put before the frame header, so the marker walk
     *   is exercised rather than only the case where SOF0 happens to be first.
     */
    fun jpeg(width: Int, height: Int, leadingSegments: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)

        repeat(leadingSegments) {
            out.write(0xFF); out.write(0xE0)
            val payload = "JFIF-ish filler".toByteArray(Charsets.US_ASCII)
            out.writeShort(payload.size + 2)
            out.write(payload)
        }

        out.write(0xFF); out.write(0xC0)
        out.writeShort(SOF_LENGTH)
        out.write(8)               // sample precision
        out.writeShort(height)
        out.writeShort(width)
        out.write(1)               // one component
        out.write(1); out.write(0x11); out.write(0)

        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    /** An Ogg page carrying a Vorbis identification header, which is what a real sound file starts with. */
    fun ogg(codec: String = "vorbis"): ByteArray {
        val packet = when (codec) {
            "vorbis" -> byteArrayOf(1) + "vorbis".toByteArray(Charsets.US_ASCII)
            "opus" -> "OpusHead".toByteArray(Charsets.US_ASCII)
            else -> codec.toByteArray(Charsets.US_ASCII)
        }
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray(Charsets.US_ASCII))
        out.write(0)                       // version
        out.write(2)                       // header type: beginning of stream
        repeat(8) { out.write(0) }         // granule position
        repeat(4) { out.write(0) }         // serial
        repeat(4) { out.write(0) }         // page sequence
        repeat(4) { out.write(0) }         // checksum, unverified by anything here
        out.write(1)                       // one segment
        out.write(packet.size)             // its length
        out.write(packet)
        return out.toByteArray()
    }

    fun json(text: String = """{"accent":"#FF00AA"}"""): ByteArray = text.toByteArray(Charsets.UTF_8)

    /**
     * A PNG padded out to a given length, for testing the byte budgets.
     *
     * Trailing bytes after IEND are ignored by everything that reads a PNG, and by the policy — which sniffs
     * the signature, reads IHDR and hashes the whole file. So this is a real asset of a chosen size, and its
     * id differs per size because the padding is part of what is hashed.
     */
    fun paddedPng(totalBytes: Int, width: Int = 16, height: Int = 16, filler: Byte = 0): ByteArray {
        val base = png(width, height)
        require(totalBytes >= base.size) { "a PNG cannot be smaller than its own header" }
        return base.copyOf(totalBytes).also { padded ->
            for (index in base.size until totalBytes) padded[index] = filler
        }
    }

    /** The id some bytes will actually be accepted under. */
    fun idOf(bytes: ByteArray): AssetId =
        AssetId(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    /** An id that is well-formed and belongs to nothing. */
    fun unrelatedId(seed: String = "nothing"): AssetId = idOf(seed.toByteArray(Charsets.UTF_8))

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xFF); write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF); write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write((value ushr 8) and 0xFF); write(value and 0xFF)
    }

    /** A PNG chunk: length, type-and-payload, then the CRC over the type and payload. */
    private fun ByteArrayOutputStream.writeChunk(typeAndPayload: ByteArray) {
        writeInt(typeAndPayload.size - 4)
        write(typeAndPayload)
        val crc = CRC32().apply { update(typeAndPayload) }.value
        writeInt(crc.toInt())
    }

    private const val SOF_LENGTH = 11
}

/** A transport that serves what it is told to, and records what was asked for. */
internal class FakeAssetTransport(
    private val responses: MutableMap<String, AssetFetch> = HashMap(),
) : AssetTransport {

    val requested = mutableListOf<String>()

    /** Delays every fetch until released, for testing that concurrent callers share one download. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun serve(id: AssetId, bytes: ByteArray, declaredType: String? = null) {
        responses[id.value] = AssetFetch.Body(bytes, declaredType)
    }

    fun serve(id: AssetId, fetch: AssetFetch) {
        responses[id.value] = fetch
    }

    override suspend fun fetch(url: String, maxBytes: Long): AssetFetch {
        requested.add(url)
        gate?.await()
        val key = url.substringAfterLast('/')
        val response = responses[key] ?: return AssetFetch.Failure("not found", status = 404)
        // The contract says an implementation stops at the limit rather than handing over more, so the fake
        // honours it — a fake that returned oversized bytes would let a manager bug hide behind the policy.
        if (response is AssetFetch.Body && response.bytes.size > maxBytes) {
            return AssetFetch.TooLarge(maxBytes, response.bytes.size.toLong())
        }
        return response
    }
}

/** An in-memory store that behaves like a disk one, including remembering when things were written. */
internal class FakeAssetStore(private val now: () -> Long = { 0L }) : AssetStore {

    private val files = LinkedHashMap<AssetId, ByteArray>()
    private val written = HashMap<AssetId, Long>()

    var writes = 0
        private set
    var deletes = 0
        private set

    override suspend fun read(id: AssetId): ByteArray? = files[id]

    override suspend fun write(id: AssetId, bytes: ByteArray) {
        files[id] = bytes
        written[id] = now()
        writes++
    }

    override suspend fun delete(id: AssetId) {
        if (files.remove(id) != null) deletes++
        written.remove(id)
    }

    override suspend fun list(): List<StoredAsset> = files.map { (id, bytes) ->
        StoredAsset(id, bytes.size.toLong(), written[id] ?: 0L)
    }

    override suspend fun clear() {
        files.clear()
        written.clear()
    }

    /** For tests that want to corrupt what is on disk without going through [write]. */
    fun put(id: AssetId, bytes: ByteArray) {
        files[id] = bytes
        written[id] = now()
    }

    fun holds(id: AssetId): Boolean = id in files

    val count: Int get() = files.size
}
