package dev.th7bo.sidequest.platform.core.presence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** An open connection to a Discord client. Windows names a pipe; everything else names a socket. */
public interface DiscordPipe : Closeable {
    public val input: InputStream
    public val output: OutputStream
}

/**
 * Something went wrong talking to Discord.
 *
 * [isExpected] separates "Discord is not running", which is the normal state of most sessions and deserves
 * no noise, from "Discord is running and refused us", which is a real misconfiguration somebody has to fix.
 * Logging both at the same level is how a mod trains its user to ignore its own warnings.
 */
public class DiscordIpcException(
    message: String,
    cause: Throwable? = null,
    public val isExpected: Boolean = false,
) : IOException(message, cause)

/**
 * The Rich Presence half of Discord's IPC protocol.
 *
 * Deliberately synchronous and request-response: write a frame, read the answer. Discord's IPC is a single
 * duplex pipe, and a concurrent reader thread would deadlock on Windows, where the pipe is a
 * [java.io.RandomAccessFile] whose reads and writes serialise on one handle. This is not the design that
 * scales; it is the design that works on a pipe that carries a message every few seconds.
 *
 * **Every blocking call belongs on a background thread.** Nothing here may be called from the client thread:
 * a Discord that has stopped responding would take the game with it.
 *
 * The pipe is injected rather than opened here, which is what lets the handshake and the activity round trip
 * be tested against a scripted stream instead of against whatever happens to be running on the machine.
 */
public class DiscordIpcClient(
    private val applicationId: String,
    private val openPipe: () -> DiscordPipe,
    private val processId: Int = ProcessHandle.current().pid().toInt(),
    private val newNonce: () -> String = { UUID.randomUUID().toString() },
) : Closeable {

    private var pipe: DiscordPipe? = null

    /** Whether the handshake completed and nothing has since gone wrong. */
    public var isConnected: Boolean = false
        private set

    /** Discord's answer to the last activity sent, for the diagnostics command. */
    public var lastResponse: String? = null
        private set

    /**
     * Opens the pipe and performs the version-1 handshake.
     *
     * @throws DiscordIpcException if no Discord is listening, or if it refuses this application id.
     */
    public fun connect() {
        close()
        val opened = try {
            openPipe()
        } catch (e: IOException) {
            throw DiscordIpcException("No Discord client is listening", e, isExpected = true)
        }
        pipe = opened

        try {
            val handshake = json.encodeToString(Handshake.serializer(), Handshake(clientId = applicationId))
            DiscordFrames.write(opened.output, DiscordOpcode.HANDSHAKE, handshake)
            val frame = readSkippingPings()
            if (frame.opcode != DiscordOpcode.FRAME) {
                throw DiscordIpcException("Discord answered the handshake with ${frame.opcode}: ${frame.payload}")
            }
            requireReady(frame.payload)
            isConnected = true
        } catch (e: DiscordIpcException) {
            close()
            throw e
        } catch (e: IOException) {
            close()
            throw DiscordIpcException("The handshake failed: ${e.message}", e, isExpected = e is EOFException)
        }
    }

    /**
     * Publishes [presence], or clears it when null.
     *
     * A null activity is how the presence is taken down without dropping the connection: the `activity`
     * field is omitted from the payload, which Discord reads as "this application is no longer doing
     * anything". Disconnecting would also clear it, at the cost of a reconnect the next time there is
     * something to say — and leaving SkyBlock for the auction house should not be a reconnect.
     */
    public fun setActivity(presence: RichPresence?) {
        val open = pipe
        if (!isConnected || open == null) throw DiscordIpcException("Not connected", isExpected = true)

        val payload = json.encodeToString(
            ActivityPayload.serializer(),
            ActivityPayload(
                args = ActivityArgs(pid = processId, activity = presence?.toActivity()),
                nonce = newNonce(),
            ),
        )

        try {
            DiscordFrames.write(open.output, DiscordOpcode.FRAME, payload)
            val frame = readSkippingPings()
            if (frame.opcode == DiscordOpcode.CLOSE) {
                isConnected = false
                throw DiscordIpcException("Discord closed the connection: ${frame.payload}", isExpected = true)
            }
            lastResponse = frame.payload
        } catch (e: DiscordIpcException) {
            throw e
        } catch (e: IOException) {
            isConnected = false
            throw DiscordIpcException("Could not send the presence: ${e.message}", e, isExpected = e is EOFException)
        }
    }

    /**
     * Reads until something that is not a ping arrives.
     *
     * Discord pings an idle connection and expects the same body back. Answering inline rather than from a
     * reader thread is the same decision as everything else here, and for the same reason.
     */
    private fun readSkippingPings(): DiscordFrames.Frame {
        val open = pipe ?: throw DiscordIpcException("Not connected", isExpected = true)
        while (true) {
            val frame = DiscordFrames.read(open.input)
            if (frame.opcode != DiscordOpcode.PING) return frame
            DiscordFrames.write(open.output, DiscordOpcode.PONG, frame.payload)
        }
    }

    /**
     * Checks that the handshake produced a READY.
     *
     * Discord reports a bad application id as a perfectly well-formed frame carrying an ERROR event, so a
     * client that only checked the opcode would believe it was connected and then silently show nothing.
     * That failure — everything working, nothing appearing — is the one worth a specific message.
     */
    private fun requireReady(payload: String) {
        val body = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: throw DiscordIpcException("Discord's handshake reply was not JSON: $payload")

        when (val event = body.string("evt")) {
            "READY" -> return
            "ERROR" -> {
                val data = body["data"] as? JsonObject
                val code = data?.string("code") ?: "unknown"
                val message = data?.string("message") ?: "no reason given"
                throw DiscordIpcException("Discord rejected application $applicationId (error $code): $message")
            }
            else -> throw DiscordIpcException("Discord answered the handshake with evt=$event: $payload")
        }
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    /**
     * Drops the connection.
     *
     * The pipe is closed without sending a CLOSE frame first. A write can block indefinitely on a Discord
     * that has stopped reading, and a shutdown that waits on one is a game that will not quit; closing the
     * handle makes any in-flight write fail instead. Discord treats a vanished client as a disconnect, which
     * is what this is.
     */
    override fun close() {
        isConnected = false
        val open = pipe
        pipe = null
        runCatching { open?.close() }
    }

    private companion object {
        /**
         * Nulls are omitted, defaults are written.
         *
         * Both halves matter. Discord rejects a payload carrying explicit nulls where it expects absence,
         * and `cmd` is a defaulted constant that has to be on the wire — the combination is what makes the
         * data classes below readable rather than a builder.
         */
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}

// -- the wire shapes ---------------------------------------------------------

@Serializable
private data class Handshake(
    val v: Int = 1,
    @SerialName("client_id") val clientId: String,
)

@Serializable
private data class ActivityTimestamps(val start: Long? = null)

@Serializable
private data class ActivityAssets(
    @SerialName("large_image") val largeImage: String? = null,
    @SerialName("large_text") val largeText: String? = null,
    @SerialName("small_image") val smallImage: String? = null,
    @SerialName("small_text") val smallText: String? = null,
)

@Serializable
private data class DiscordActivity(
    /** 0 is "Playing", which is what a Minecraft mod is. */
    val type: Int = 0,
    val details: String? = null,
    val state: String? = null,
    val timestamps: ActivityTimestamps? = null,
    val assets: ActivityAssets? = null,
)

@Serializable
private data class ActivityArgs(val pid: Int, val activity: DiscordActivity? = null)

@Serializable
private data class ActivityPayload(
    val cmd: String = "SET_ACTIVITY",
    val args: ActivityArgs,
    val nonce: String,
)

/**
 * The mod's presence as Discord's activity object.
 *
 * `timestamps` and `assets` are omitted entirely when empty rather than sent as empty objects: Discord
 * treats a present-but-empty `timestamps` as a request for a clock it has no start for.
 */
private fun RichPresence.toActivity(): DiscordActivity = DiscordActivity(
    details = details,
    state = state,
    timestamps = startedAtEpochSeconds?.let { ActivityTimestamps(start = it) },
    assets = if (largeImage == null && largeText == null && smallImage == null && smallText == null) {
        null
    } else {
        ActivityAssets(
            largeImage = largeImage,
            largeText = largeText,
            smallImage = smallImage,
            smallText = smallText,
        )
    },
)
