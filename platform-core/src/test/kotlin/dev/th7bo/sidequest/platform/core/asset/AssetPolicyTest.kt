package dev.th7bo.sidequest.platform.core.asset

import dev.th7bo.sidequest.platform.asset.Asset
import dev.th7bo.sidequest.platform.asset.AssetId
import dev.th7bo.sidequest.platform.asset.AssetKind
import dev.th7bo.sidequest.platform.asset.AssetRejection
import dev.th7bo.sidequest.platform.asset.MediaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * What is allowed to become an asset.
 *
 * Every input to this reaches the mod from somewhere else — the backend serves what a person uploaded — so
 * the cases worth writing are the hostile ones. A test that only checks a valid PNG is accepted proves the
 * happy path and nothing about the reason this code exists.
 */
class AssetPolicyTest {

    private fun accept(
        bytes: ByteArray,
        kind: AssetKind = AssetKind.ICON,
        id: AssetId = AssetFixtures.idOf(bytes),
        declaredType: String? = null,
    ) = AssetPolicy.accept(id, kind, bytes, declaredType)

    private fun rejection(result: AssetPolicy.Result<*, AssetRejection>): AssetRejection = when (result) {
        is AssetPolicy.Result.Rejected -> result.reason
        is AssetPolicy.Result.Accepted -> fail("expected a rejection, got ${result.value}")
    }

    private fun accepted(result: AssetPolicy.Result<Asset, AssetRejection>): Asset = when (result) {
        is AssetPolicy.Result.Accepted -> result.value
        is AssetPolicy.Result.Rejected -> fail("expected acceptance, got ${result.reason.explanation}")
    }

    // -- the happy paths, briefly ------------------------------------------

    @Test
    fun `a well-formed png is accepted with its dimensions read`() {
        val bytes = AssetFixtures.png(64, 64)

        val asset = accepted(accept(bytes))

        assertEquals(MediaType.PNG, asset.mediaType)
        assertEquals(64, asset.size?.width)
        assertEquals(64, asset.size?.height)
    }

    @Test
    fun `a jpeg's dimensions are found past its leading segments`() {
        // The frame header is not at a fixed offset — a photograph carries EXIF before it — so the marker
        // walk has to actually walk.
        val bytes = AssetFixtures.jpeg(width = 800, height = 600, leadingSegments = 6)

        val asset = accepted(accept(bytes, AssetKind.IMAGE))

        assertEquals(MediaType.JPEG, asset.mediaType)
        assertEquals(800, asset.size?.width)
        assertEquals(600, asset.size?.height)
    }

    @Test
    fun `an ogg of vorbis is accepted as a sound`() {
        val bytes = AssetFixtures.ogg("vorbis")

        val asset = accepted(accept(bytes, AssetKind.SOUND))

        assertEquals(MediaType.OGG, asset.mediaType)
        assertNull(asset.size, "a sound has no dimensions")
    }

    @Test
    fun `json is accepted as data by parsing it, since it has no signature`() {
        val bytes = AssetFixtures.json("""{"accent":"#FF00AA","radius":4}""")

        val asset = accepted(accept(bytes, AssetKind.DATA))

        assertEquals(MediaType.JSON, asset.mediaType)
    }

    // -- the decompression bomb ---------------------------------------------

    /**
     * The check the whole header reader exists for.
     *
     * A PNG of a single flat colour 40,000 pixels square is a few hundred bytes on disk and 6.4 GB decoded.
     * A size limit passes it. Only reading the dimensions out of the header catches it, and it has to happen
     * before anything hands the file to a decoder.
     */
    @Test
    fun `a decompression bomb is refused on its dimensions, not its size`() {
        val bytes = AssetFixtures.png(40_000, 40_000)

        assertTrue(
            bytes.size < AssetKind.IMAGE.maxBytes,
            "the premise: this file is small (${bytes.size} bytes), so the size limit cannot be what catches it",
        )

        val reason = rejection(accept(bytes, AssetKind.IMAGE))

        val tooMany = assertInstanceOf(AssetRejection.TooManyPixels::class.java, reason)
        assertEquals(40_000, tooMany.size.width)
    }

    @Test
    fun `an image one pixel over the limit is refused and one exactly at it is not`() {
        val limit = AssetKind.ICON.maxDimension

        assertInstanceOf(
            AssetPolicy.Result.Accepted::class.java,
            accept(AssetFixtures.png(limit, limit)),
            "exactly at the limit is allowed",
        )
        assertInstanceOf(
            AssetRejection.TooManyPixels::class.java,
            rejection(accept(AssetFixtures.png(limit + 1, limit))),
        )
    }

    // -- lying about the type ------------------------------------------------

    /**
     * A sound is not a skin, whatever it is asked for as.
     *
     * The kind is what every limit hangs off, so accepting bytes of one type under the expectations of
     * another would let a 4 MiB Ogg through a 64 KiB icon budget.
     */
    @Test
    fun `bytes are checked against the kind rather than reinterpreted`() {
        val sound = AssetFixtures.ogg()

        val reason = rejection(accept(sound, AssetKind.ICON))

        val wrong = assertInstanceOf(AssetRejection.WrongType::class.java, reason)
        assertEquals(MediaType.OGG, wrong.found)
    }

    /** A rename proves nothing: nothing in this package dispatches on an extension, only on the bytes. */
    @Test
    fun `a text file is not a png however it is served`() {
        val bytes = "this is definitely a png, honest".toByteArray()

        val reason = rejection(accept(bytes, id = AssetFixtures.idOf(bytes), declaredType = "image/png"))

        assertInstanceOf(AssetRejection.WrongType::class.java, reason)
    }

    /** The server's claim and the bytes disagreeing is a refusal, not something to shrug at. */
    @Test
    fun `a content type that disagrees with the bytes is refused`() {
        val bytes = AssetFixtures.png(32, 32)

        val reason = rejection(accept(bytes, declaredType = "audio/ogg"))

        val mismatch = assertInstanceOf(AssetRejection.TypeMismatch::class.java, reason)
        assertEquals(MediaType.OGG, mismatch.declared)
        assertEquals(MediaType.PNG, mismatch.actual)
    }

    @Test
    fun `a content type with a charset still matches`() {
        val bytes = AssetFixtures.json()

        assertInstanceOf(
            AssetPolicy.Result.Accepted::class.java,
            accept(bytes, AssetKind.DATA, declaredType = "application/json; charset=utf-8"),
        )
    }

    // -- integrity -----------------------------------------------------------

    /**
     * The check that makes an id mean something.
     *
     * Everything else establishes that the bytes are *a* valid asset. Only this establishes that they are the
     * one that was asked for — so a substituted or corrupted file is caught without a signature scheme.
     */
    @Test
    fun `bytes that do not hash to the requested id are refused`() {
        val bytes = AssetFixtures.png(32, 32)
        val wanted = AssetFixtures.unrelatedId()

        val reason = rejection(accept(bytes, id = wanted))

        val mismatch = assertInstanceOf(AssetRejection.HashMismatch::class.java, reason)
        assertEquals(wanted, mismatch.expected)
        assertEquals(AssetFixtures.idOf(bytes), mismatch.actual)
    }

    @Test
    fun `a single flipped byte changes the id`() {
        val bytes = AssetFixtures.png(32, 32)
        val tampered = bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertTrue(AssetFixtures.idOf(bytes) != AssetFixtures.idOf(tampered))
    }

    // -- malformed input -----------------------------------------------------

    /**
     * Truncation at every length.
     *
     * A parser reading past the end of an array is the classic way a header reader becomes a crash, and the
     * input here always comes off a network where a truncated response is routine. So rather than picking a
     * couple of lengths, this walks every one.
     */
    @Test
    fun `no prefix of a valid file throws`() {
        for (source in listOf(AssetFixtures.png(64, 64), AssetFixtures.jpeg(64, 64), AssetFixtures.ogg())) {
            for (length in 0 until source.size) {
                val prefix = source.copyOf(length)
                // The assertion is that this returns at all. Any rejection is a fine answer; an exception is
                // not, and would fail the test by propagating.
                accept(prefix, AssetKind.IMAGE, id = AssetFixtures.idOf(prefix))
                accept(prefix, AssetKind.SOUND, id = AssetFixtures.idOf(prefix))
            }
        }
    }

    @Test
    fun `a png with zero dimensions is refused rather than accepted as empty`() {
        val bytes = AssetFixtures.png(0, 64)

        assertInstanceOf(AssetRejection.Unreadable::class.java, rejection(accept(bytes, AssetKind.IMAGE)))
    }

    /**
     * A jpeg whose length fields form a loop.
     *
     * A segment claiming a length of zero would leave the walk on the same byte forever. The parser bounds
     * itself, and this is the input that would otherwise hang a client.
     */
    @Test
    fun `a jpeg with a degenerate segment length terminates`() {
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x00,   // a segment claiming to be zero bytes long
            0xFF.toByte(), 0xD9.toByte(),
        )

        assertNull(ImageHeaders.jpeg(bytes))
    }

    /** An Ogg container is not enough on its own — it can carry anything. */
    @Test
    fun `an ogg that is not audio is refused`() {
        val bytes = AssetFixtures.ogg(codec = "theora")

        val reason = rejection(accept(bytes, AssetKind.SOUND, id = AssetFixtures.idOf(AssetFixtures.ogg("theora"))))

        assertInstanceOf(AssetRejection.Unreadable::class.java, reason)
    }

    @Test
    fun `malformed json is refused as data`() {
        val bytes = AssetFixtures.json("""{"accent": """)

        assertInstanceOf(AssetRejection.WrongType::class.java, rejection(accept(bytes, AssetKind.DATA)))
    }

    // -- size ----------------------------------------------------------------

    @Test
    fun `a file over the kind's budget is refused before anything parses it`() {
        val bytes = ByteArray((AssetKind.ICON.maxBytes + 1).toInt())

        val reason = rejection(accept(bytes))

        val large = assertInstanceOf(AssetRejection.TooLarge::class.java, reason)
        assertEquals(AssetKind.ICON.maxBytes, large.limitBytes)
    }

    /** Kinds differ, and that is the point of having them: a sound may be far larger than an icon. */
    @Test
    fun `each kind carries its own limits`() {
        assertTrue(AssetKind.SOUND.maxBytes > AssetKind.ICON.maxBytes)
        assertTrue(AssetKind.IMAGE.maxDimension > AssetKind.ICON.maxDimension)
        assertFalse(AssetKind.SOUND.isImage, "a sound has no dimensions to limit")
        assertTrue(AssetKind.SKIN.accepts == setOf(MediaType.PNG), "Minecraft's skin pipeline takes PNG only")
    }

    // -- ids -----------------------------------------------------------------

    @Test
    fun `an id must be a lowercase sha-256`() {
        assertNull(AssetId.parseOrNull("nope"))
        assertNull(AssetId.parseOrNull("A".repeat(64)), "uppercase hex is not the canonical form")
        assertNull(AssetId.parseOrNull("g".repeat(64)), "not hex at all")
        assertNull(AssetId.parseOrNull(""))
        assertNull(AssetId.parseOrNull("../../etc/passwd"), "a path is not an id")
        assertEquals("a".repeat(64), AssetId.parseOrNull("a".repeat(64))?.value)
    }
}
