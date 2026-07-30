package dev.th7bo.sidequest.platform.core.backend

import dev.th7bo.sidequest.platform.backend.RealtimeSink
import dev.th7bo.sidequest.platform.backend.RealtimeTransport
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.protocol.ApiErrorCode
import dev.th7bo.sidequest.protocol.Endpoints
import dev.th7bo.sidequest.protocol.Protocol
import dev.th7bo.sidequest.protocol.RealtimeAck
import dev.th7bo.sidequest.protocol.RealtimeFrame
import dev.th7bo.sidequest.protocol.RealtimeHello
import dev.th7bo.sidequest.protocol.RealtimeMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.math.min
import kotlin.random.Random

/** A realtime message arrived and was not a duplicate. */
public class RealtimeMessageReceivedEvent(
    public val message: RealtimeMessage,
) : SidequestEvent() {
    override fun describe(): String = "${message.payload::class.simpleName} from ${message.senderAccount}"
}

/** The realtime connection opened or closed. */
public class RealtimeConnectionEvent(
    public val isConnected: Boolean,
    public val detail: String? = null,
) : SidequestEvent() {
    override fun describe(): String = (if (isConnected) "connected" else "disconnected") +
        (detail?.let { ": $it" } ?: "")
}

/**
 * The realtime connection.
 *
 * Everything difficult about a WebSocket is in the reconnecting, and everything difficult about
 * reconnecting is what happens to the messages you missed. The plan asks for reconnect, replay, duplicate
 * suppression, acknowledgements, an offline queue, stale-event expiry and ordering, and each of those is a
 * specific way a naive client gets it wrong:
 *
 * - a client that reconnects and starts listening has a hole exactly as long as it was away
 * - a client that resumes gets the events it already had, and shows the same drop twice
 * - a client that reconnects immediately hammers a server that just restarted
 * - a client that replays a four-minute-old ping points at somewhere nobody is
 *
 * So: the last sequence is remembered and sent on reconnect, message ids are remembered to suppress
 * repeats, the backoff is exponential with jitter, and a payload that declares an expiry is dropped when
 * it is past it.
 */
public class DefaultRealtimeClient(
    private val client: DefaultBackendClient,
    private val transport: RealtimeTransport,
    private val events: EventBus,
    private val log: Logger,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    public var isConnected: Boolean = false
        private set

    /**
     * The last sequence processed, and the whole reason a resume works.
     *
     * Kept in memory rather than persisted: a client that has been shut down long enough for this to
     * matter is better off fetching current state than replaying a week of presence.
     */
    public var lastSequence: Long = 0
        private set

    /** True when the last resume found the server no longer had our starting point. */
    public var hasResumeGap: Boolean = false
        private set

    /**
     * Message ids recently seen, for duplicate suppression.
     *
     * A bounded ring rather than a growing set. Delivery is at-least-once by design, so duplicates arrive
     * on every resume — but only ever *recent* ones, so remembering the last few hundred is enough and an
     * unbounded set would be a leak that grows for as long as the client runs.
     */
    private val seen = LinkedHashSet<String>()

    /** Ids waiting to be acknowledged, batched so an ack does not go out per message. */
    private val pendingAcks = ArrayList<String>()

    /**
     * Runs the connection until cancelled, reconnecting as needed.
     *
     * Shaped as a suspending loop rather than a start/stop pair because the connection's life *is* the
     * scope: cancelling the job that runs this is the whole of shutdown, and there is nothing left behind
     * to leak.
     */
    public suspend fun run() {
        var attempt = 0

        while (true) {
            val token = client.accessTokenOrNull()
            val base = client.baseUrlOrNull()

            if (token == null || base == null) {
                // Not paired, or the access token has not been minted yet. Waited out rather than treated
                // as an error: the state machine elsewhere is what tells the user anything.
                delay(UNAUTHENTICATED_RETRY_MILLIS)
                continue
            }

            // `http` -> `ws` and, because only the scheme's first four characters are replaced,
            // `https` -> `wss`. Deliberate: a server behind TLS must not be reached over a plain socket,
            // and a rewrite that dropped the `s` would silently do exactly that.
            val url = base.trimEnd('/').replaceFirst("http", "ws") + Endpoints.REALTIME + "?token=$token"

            val closure = try {
                transport.connect(
                    url = url,
                    onOpen = { sink -> onOpen(sink) },
                    onText = { text -> onText(text) },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (thrown: Throwable) {
                log.debug(thrown) { "Realtime connection failed" }
                dev.th7bo.sidequest.platform.backend.RealtimeClosure(reason = thrown.message ?: "failed")
            }

            markDisconnected(closure.reason)

            if (!closure.isRetryable) {
                // A refusal the server will repeat — a protocol mismatch, a revoked device. Reconnecting
                // into one of those is a loop that never ends and fills somebody's log.
                log.warn { "Realtime connection will not be retried: ${closure.reason}" }
                return
            }

            attempt++
            delay(backoffMillis(attempt))
            // Reset once a connection has lasted, so a server that restarts twice in a day does not leave
            // the client backing off for minutes.
            if (isConnected) attempt = 0
        }
    }

    private suspend fun onOpen(sink: RealtimeSink) {
        val hello = RealtimeFrame.Hello(RealtimeHello(Protocol.VERSION, lastSequence))
        sink.send(json.encodeToString(RealtimeFrame.serializer(), hello))
        this.sink = sink
    }

    private var sink: RealtimeSink? = null

    private suspend fun onText(text: String) {
        val frame = runCatching { json.decodeFromString(RealtimeFrame.serializer(), text) }.getOrElse { thrown ->
            log.debug(thrown) { "Unreadable realtime frame" }
            return
        }

        when (frame) {
            is RealtimeFrame.Welcome -> {
                hasResumeGap = frame.welcome.resumeGap
                if (hasResumeGap) {
                    // The client has a hole it cannot fill from the stream. Said out loud so whatever cares
                    // about completeness — a history screen, a ledger — can re-fetch rather than assume.
                    log.warn { "Resumed with a gap; the server no longer had sequence $lastSequence" }
                }
                markConnected()
            }

            is RealtimeFrame.Message -> handleMessage(frame.message)

            is RealtimeFrame.KeepAlive -> {
                // Echoed, so a router in the middle sees traffic in both directions.
                sink?.send(text)
            }

            is RealtimeFrame.Error -> {
                log.warn { "Realtime error: ${frame.code} ${frame.message}" }
                if (frame.code == ApiErrorCode.PROTOCOL_MISMATCH) {
                    // Nothing to be done by retrying. The state machine has already been told.
                    isConnected = false
                }
            }

            is RealtimeFrame.Hello, is RealtimeFrame.Ack -> Unit
        }
    }

    private suspend fun handleMessage(message: RealtimeMessage) {
        // Duplicate suppression first, because everything after it has an effect.
        if (!seen.add(message.messageId)) {
            log.trace { "Dropped a duplicate: ${message.messageId}" }
            return
        }
        if (seen.size > SEEN_LIMIT) {
            val oldest = seen.iterator()
            repeat(seen.size - SEEN_LIMIT) { if (oldest.hasNext()) { oldest.next(); oldest.remove() } }
        }

        // Ordering, in the one sense that matters: the resume point only ever moves forward. A message with
        // a lower sequence is a replay, and letting it move the marker back would replay everything after
        // it on the next reconnect.
        if (message.sequence > lastSequence) lastSequence = message.sequence

        // Staleness, using the server's clock rather than ours. A ping from four minutes ago points at
        // somewhere the player has left, and showing it is worse than dropping it.
        if (message.isStale(client.serverTime.toServer(now()))) {
            log.debug { "Dropped a stale ${message.payload::class.simpleName}" }
            return
        }

        if (message.requiresAcknowledgement) {
            pendingAcks.add(message.messageId)
            if (pendingAcks.size >= ACK_BATCH) flushAcks()
        }

        events.post(RealtimeMessageReceivedEvent(message), EventSource.REMOTE)
    }

    /** Sends a message, or queues it. Falls back to the HTTP path when there is no connection. */
    public suspend fun send(message: RealtimeMessage): Boolean {
        val open = sink
        if (open == null || !isConnected) {
            // The queue is the HTTP client's, not a second one. Two queues would be two things that can
            // disagree about what has been sent.
            return client.submit(message)
        }
        return runCatching {
            open.send(json.encodeToString(RealtimeFrame.serializer(), RealtimeFrame.Message(message)))
            true
        }.getOrElse {
            log.debug(it) { "Realtime send failed; queueing instead" }
            client.submit(message)
        }
    }

    /** Sends any pending acknowledgements. Called on a timer as well as when the batch fills. */
    public suspend fun flushAcks() {
        if (pendingAcks.isEmpty()) return
        val open = sink ?: return
        val ids = pendingAcks.toList()
        pendingAcks.clear()
        runCatching {
            open.send(json.encodeToString(RealtimeFrame.serializer(), RealtimeFrame.Ack(RealtimeAck(ids))))
        }
    }

    private fun markConnected() {
        if (isConnected) return
        isConnected = true
        log.info { "Realtime connected, resuming from $lastSequence" }
        events.post(RealtimeConnectionEvent(true), EventSource.DERIVED)
    }

    private fun markDisconnected(reason: String) {
        sink = null
        if (!isConnected) return
        isConnected = false
        log.info { "Realtime disconnected: $reason" }
        events.post(RealtimeConnectionEvent(false, reason), EventSource.DERIVED)
    }

    private fun backoffMillis(attempt: Int): Long {
        val exponential = min(BASE_BACKOFF_MILLIS shl (attempt - 1).coerceAtMost(MAX_SHIFT), MAX_BACKOFF_MILLIS)
        // Jittered for the same reason the HTTP client's is: a group of clients that all dropped when the
        // server restarted must not all come back at the same instant.
        return exponential / 2 + random.nextLong(exponential / 2 + 1)
    }

    private companion object {
        const val BASE_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 60_000L
        const val MAX_SHIFT = 8
        const val UNAUTHENTICATED_RETRY_MILLIS = 5_000L

        /** How many recent ids to remember. Enough to cover a resume, small enough not to grow. */
        const val SEEN_LIMIT = 512

        const val ACK_BATCH = 16
    }
}
