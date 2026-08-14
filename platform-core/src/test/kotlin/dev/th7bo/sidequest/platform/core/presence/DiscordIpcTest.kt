package dev.th7bo.sidequest.platform.core.presence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Talking to Discord, against a Discord made of bytes.
 *
 * The pipe is scripted rather than real, which is the only way any of this is testable at all: the
 * alternative is a suite that passes on a machine with Discord open and fails on the build server for
 * reasons that look like flakiness.
 *
 * The endianness test is the one that earns its keep. A big-endian length field is not a subtly wrong
 * number — it is a request to read four billion bytes, so the failure is a hang rather than an exception,
 * and nothing in the code reads wrong.
 */
class DiscordIpcTest {

    // -- the wire format ------------------------------------------------------

    @Test
    fun `a frame is opcode and length, little endian, then the payload`() {
        val frame = DiscordFrames.encode(DiscordOpcode.FRAME, "hi")

        assertEquals(10, frame.size)
        // Opcode 1 as four little-endian bytes, then length 2 as four more. Written out rather than
        // computed, because computing them the same way the encoder does would agree with any bug.
        assertEquals(listOf<Byte>(1, 0, 0, 0), frame.take(4))
        assertEquals(listOf<Byte>(2, 0, 0, 0), frame.drop(4).take(4))
        assertEquals("hi", String(frame.drop(8).toByteArray()))
    }

    @Test
    fun `a frame reads back as it was written`() {
        val payload = """{"cmd":"SET_ACTIVITY","state":"héllo ·"}"""
        val bytes = DiscordFrames.encode(DiscordOpcode.HANDSHAKE, payload)

        val frame = DiscordFrames.read(ByteArrayInputStream(bytes))
        assertEquals(DiscordOpcode.HANDSHAKE, frame.opcode)
        assertEquals(payload, frame.payload)
    }

    /** A length is bytes, not characters. A multi-byte payload measured in characters truncates. */
    @Test
    fun `the length counts bytes rather than characters`() {
        val frame = DiscordFrames.encode(DiscordOpcode.FRAME, "·")
        assertEquals(listOf<Byte>(2, 0, 0, 0), frame.drop(4).take(4))
    }

    @Test
    fun `a truncated frame is an end of file rather than a hang`() {
        val bytes = DiscordFrames.encode(DiscordOpcode.FRAME, "abcdef")
        assertThrows(EOFException::class.java) {
            DiscordFrames.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 2)))
        }
    }

    /** Whatever is on the other end of that socket can name a number, and this must not allocate it. */
    @Test
    fun `an absurd length is refused rather than allocated`() {
        val header = ByteArrayOutputStream()
        header.write(byteArrayOf(1, 0, 0, 0))
        // 0x7FFFFFFF, little endian.
        header.write(byteArrayOf(-1, -1, -1, 0x7F))

        val failure = assertThrows(IOException::class.java) {
            DiscordFrames.read(ByteArrayInputStream(header.toByteArray()))
        }
        assertFalse(failure is EOFException)
    }

    @Test
    fun `an unknown opcode is refused`() {
        val header = byteArrayOf(99, 0, 0, 0, 0, 0, 0, 0)
        assertThrows(IOException::class.java) { DiscordFrames.read(ByteArrayInputStream(header)) }
    }

    // -- the handshake --------------------------------------------------------

    @Test
    fun `the handshake sends the application id and version one`() {
        val pipe = ScriptedDiscord(ready())
        DiscordIpcClient("12345", { pipe }, processId = 7, newNonce = { "nonce" }).connect()

        val sent = pipe.framesWritten()
        assertEquals(DiscordOpcode.HANDSHAKE, sent.first().opcode)
        assertEquals("""{"v":1,"client_id":"12345"}""", sent.first().payload)
    }

    /**
     * A rejected application id arrives as a perfectly well-formed frame.
     *
     * This is the failure worth a specific message, because it is the one where everything appears to work
     * and nothing appears on the profile. A client that checked only the opcode would report success.
     */
    @Test
    fun `an error event is a failure even though the frame is fine`() {
        val error = """{"cmd":"DISPATCH","evt":"ERROR","data":{"code":4000,"message":"Invalid Client ID"}}"""
        val pipe = ScriptedDiscord(DiscordFrames.encode(DiscordOpcode.FRAME, error))

        val failure = assertThrows(DiscordIpcException::class.java) {
            DiscordIpcClient("nope", { pipe }).connect()
        }
        assertTrue(failure.message!!.contains("Invalid Client ID"), failure.message)
        assertFalse(failure.isExpected, "a refused application id is a misconfiguration, not a closed Discord")
    }

    /** Discord being closed is the ordinary state of most sessions and must not read as a fault. */
    @Test
    fun `a closed Discord is an expected failure`() {
        val failure = assertThrows(DiscordIpcException::class.java) {
            DiscordIpcClient("1", { throw IOException("no such file") }).connect()
        }
        assertTrue(failure.isExpected)
    }

    @Test
    fun `a connection that fails leaves nothing connected`() {
        val client = DiscordIpcClient("1", { throw IOException("no such file") })
        runCatching { client.connect() }
        assertFalse(client.isConnected)
    }

    // -- the activity ---------------------------------------------------------

    @Test
    fun `an activity carries the two lines, the clock and the assets`() {
        val pipe = ScriptedDiscord(ready(), ok())
        val client = DiscordIpcClient("1", { pipe }, processId = 42, newNonce = { "n" })
        client.connect()

        client.setActivity(
            RichPresence(
                details = "Mining",
                state = "Dwarven Mines",
                startedAtEpochSeconds = 1_700_000_000L,
                largeImage = "island_mining_3",
                largeText = "Dwarven Mines",
                smallImage = "activity_mining",
                smallText = "Mining",
            ),
        )

        val payload = pipe.framesWritten().last().payload
        assertEquals(
            """{"cmd":"SET_ACTIVITY","args":{"pid":42,"activity":{"type":0,"details":"Mining",""" +
                """"state":"Dwarven Mines","timestamps":{"start":1700000000},""" +
                """"assets":{"large_image":"island_mining_3","large_text":"Dwarven Mines",""" +
                """"small_image":"activity_mining","small_text":"Mining"}}},"nonce":"n"}""",
            payload,
        )
    }

    /**
     * Absent, not null.
     *
     * Discord refuses a payload carrying explicit nulls where it expects a field to be missing, so a
     * presence with nothing in it has to serialise to a payload with nothing in it.
     */
    @Test
    fun `an empty presence omits its fields rather than nulling them`() {
        val pipe = ScriptedDiscord(ready(), ok())
        val client = DiscordIpcClient("1", { pipe }, processId = 1, newNonce = { "n" })
        client.connect()
        client.setActivity(RichPresence())

        val payload = pipe.framesWritten().last().payload
        assertFalse(payload.contains("null"), payload)
        assertFalse(payload.contains("timestamps"), payload)
        assertFalse(payload.contains("assets"), payload)
    }

    /** Clearing is an activity that is not there, which is how the presence comes down without a reconnect. */
    @Test
    fun `a null presence omits the activity entirely`() {
        val pipe = ScriptedDiscord(ready(), ok())
        val client = DiscordIpcClient("1", { pipe }, processId = 3, newNonce = { "n" })
        client.connect()
        client.setActivity(null)

        assertEquals(
            """{"cmd":"SET_ACTIVITY","args":{"pid":3},"nonce":"n"}""",
            pipe.framesWritten().last().payload,
        )
    }

    /** Discord pings an idle connection and expects the body back, or it hangs up. */
    @Test
    fun `a ping is answered inline with the same body`() {
        val pipe = ScriptedDiscord(
            ready(),
            DiscordFrames.encode(DiscordOpcode.PING, "beat"),
            ok(),
        )
        val client = DiscordIpcClient("1", { pipe }, processId = 1, newNonce = { "n" })
        client.connect()
        client.setActivity(RichPresence(details = "Mining"))

        val pong = pipe.framesWritten().single { it.opcode == DiscordOpcode.PONG }
        assertEquals("beat", pong.payload)
        assertNotNull(client.lastResponse)
    }

    @Test
    fun `a Discord that quits mid-session stops being connected`() {
        val pipe = ScriptedDiscord(ready())
        val client = DiscordIpcClient("1", { pipe }, processId = 1, newNonce = { "n" })
        client.connect()
        assertTrue(client.isConnected)

        // Nothing left in the script: the read after the write hits the end of the stream, which is what a
        // Discord that has been closed looks like from here.
        val failure = assertThrows(DiscordIpcException::class.java) {
            client.setActivity(RichPresence(details = "Mining"))
        }
        assertTrue(failure.isExpected)
        assertFalse(client.isConnected)
    }

    @Test
    fun `sending before connecting is refused rather than attempted`() {
        val client = DiscordIpcClient("1", { ScriptedDiscord(ready()) })
        assertThrows(DiscordIpcException::class.java) { client.setActivity(RichPresence()) }
    }

    // -- a Discord made of bytes ---------------------------------------------

    /** Replies queued in advance; everything written is kept for inspection. */
    private class ScriptedDiscord(vararg replies: ByteArray) : DiscordPipe {
        private val written = ByteArrayOutputStream()

        override val input: InputStream = ByteArrayInputStream(
            replies.fold(ByteArray(0)) { all, next -> all + next },
        )
        override val output: OutputStream = written

        override fun close() = Unit

        /** Everything the client sent, decoded back into frames. */
        fun framesWritten(): List<DiscordFrames.Frame> {
            val stream = ByteArrayInputStream(written.toByteArray())
            return buildList {
                while (stream.available() > 0) add(DiscordFrames.read(stream))
            }
        }
    }

    private fun ready(): ByteArray =
        DiscordFrames.encode(DiscordOpcode.FRAME, """{"cmd":"DISPATCH","evt":"READY","data":{"v":1}}""")

    private fun ok(): ByteArray =
        DiscordFrames.encode(DiscordOpcode.FRAME, """{"cmd":"SET_ACTIVITY","evt":null,"data":{}}""")
}
