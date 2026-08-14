package dev.th7bo.sidequest.platform.core.presence

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Discord's IPC wire format.
 *
 * `[opcode: int32 little-endian][length: int32 little-endian][payload: UTF-8]`, and the endianness is the
 * entire reason this is its own file with its own test. A big-endian length is not a subtly wrong number —
 * it is a request to read four billion bytes, which hangs the connection rather than failing it, and nothing
 * about the code reads wrong.
 *
 * Framing only. What the JSON inside means is [DiscordIpcClient]'s problem, which is what lets this be
 * checked against literal bytes.
 */
public object DiscordFrames {

    /** Bytes before the payload: two 32-bit fields. */
    public const val HEADER_BYTES: Int = 8

    /**
     * A refusal rather than an allocation.
     *
     * A length field is attacker-controlled in the sense that matters here — anything that is not Discord on
     * the other end of that socket can name a number — and `readNBytes` would faithfully try to allocate it.
     * No real frame comes close to this.
     */
    public const val MAX_PAYLOAD_BYTES: Int = 1 shl 20

    /** One framed message. */
    public data class Frame(val opcode: DiscordOpcode, val payload: String)

    /** Writes one frame. Does not flush; the caller owns the stream's buffering. */
    public fun encode(opcode: DiscordOpcode, payload: String): ByteArray {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(HEADER_BYTES + bytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(opcode.id)
            .putInt(bytes.size)
            .put(bytes)
            .array()
    }

    /** Writes [payload] to [output] as one frame and flushes it. */
    public fun write(output: OutputStream, opcode: DiscordOpcode, payload: String) {
        output.write(encode(opcode, payload))
        output.flush()
    }

    /**
     * Reads one frame, blocking until it is complete.
     *
     * @throws EOFException when the stream ends part-way through a frame, which is what a Discord that has
     *   quit looks like from here — distinguished from an [IOException] because it is expected rather than
     *   exceptional, and the caller reconnects instead of complaining.
     */
    public fun read(input: InputStream): Frame {
        val header = input.readNBytes(HEADER_BYTES)
        if (header.size < HEADER_BYTES) throw EOFException("Discord closed the pipe mid-header")

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val id = buffer.int
        val length = buffer.int
        val opcode = DiscordOpcode.of(id) ?: throw IOException("Discord sent an unknown opcode: $id")
        if (length < 0 || length > MAX_PAYLOAD_BYTES) throw IOException("Discord sent a $length byte frame")

        val payload = input.readNBytes(length)
        if (payload.size < length) throw EOFException("Discord closed the pipe mid-payload")
        return Frame(opcode, String(payload, Charsets.UTF_8))
    }
}

/** The five message kinds Discord's IPC speaks. */
public enum class DiscordOpcode(public val id: Int) {
    HANDSHAKE(0),
    FRAME(1),
    CLOSE(2),
    PING(3),
    PONG(4),
    ;

    public companion object {
        private val BY_ID: Map<Int, DiscordOpcode> = entries.associateBy { it.id }

        /** Null for anything unrecognised, which the reader treats as a broken connection. */
        public fun of(id: Int): DiscordOpcode? = BY_ID[id]
    }
}
