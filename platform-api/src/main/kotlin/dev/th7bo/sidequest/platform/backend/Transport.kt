package dev.th7bo.sidequest.platform.backend

/**
 * The one way the mod makes an HTTP request.
 *
 * An interface, and a deliberately primitive one: strings, bytes and a status. Everything above it —
 * retries, backoff, error mapping, token refresh, offline queueing — is in `platform-core` where a test
 * drives it from a fake, and the only thing below it is the twenty lines that call a real HTTP client.
 *
 * The alternative is an HTTP library in `platform-core`, which would mean the retry logic could only be
 * tested by standing up a server. The interesting behaviour of a network client is all in what it does
 * when the network misbehaves, and a fake transport can misbehave on demand.
 */
public interface HttpTransport {

    /**
     * Performs [request].
     *
     * Must not throw for a network failure — that is an expected outcome here, not an exceptional one,
     * and is reported as [HttpExchange.Failure]. Throwing would put a try/catch at every call site and
     * one of them would eventually be missing.
     */
    public suspend fun send(request: HttpRequest): HttpExchange
}

public enum class HttpMethod { GET, POST }

/** A request, as plainly as it can be described. */
public data class HttpRequest(
    public val method: HttpMethod,
    /** Absolute. The client builds it from the configured base URL. */
    public val url: String,
    public val headers: Map<String, String> = emptyMap(),
    /** JSON, or null for a GET. */
    public val body: String? = null,
    public val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    public companion object {
        /**
         * How long to wait.
         *
         * Ten seconds. Long enough for a homelab waking a disk, short enough that a feature waiting on a
         * call does not look broken. Anything slower than this is better retried than waited for.
         */
        public const val DEFAULT_TIMEOUT_MILLIS: Long = 10_000
    }
}

/**
 * What came back, or why nothing did.
 *
 * A sealed result rather than an exception for a failure, for the reason in [HttpTransport.send]: a mod
 * used on hotel wifi against a server in somebody's cupboard is unreachable as a matter of routine.
 */
public sealed interface HttpExchange {

    public data class Response(
        public val status: Int,
        public val body: String,
        public val headers: Map<String, String> = emptyMap(),
    ) : HttpExchange {
        public val isSuccess: Boolean get() = status in 200..299
    }

    /** No response at all: unreachable, refused, DNS, TLS. */
    public data class Failure(
        public val reason: String,
        public val isTimeout: Boolean = false,
    ) : HttpExchange

    public companion object {
        /** A transport that always fails. The default, so an unconfigured client is merely offline. */
        public val Unavailable: HttpTransport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpExchange =
                Failure("no transport configured")
        }
    }
}

/**
 * The realtime connection, as primitively as possible.
 *
 * Same reasoning as [HttpTransport]. Reconnection, resume, duplicate suppression and staleness are the
 * interesting parts and they live above this; what is below is a WebSocket.
 */
public interface RealtimeTransport {

    /**
     * Opens a connection and pumps it until it closes.
     *
     * Suspends for the life of the connection. Inbound text goes to [onText]; the returned handle sends
     * outbound text. Returns when the connection is done, for any reason.
     *
     * Shaped this way rather than as connect/send/close because a socket's lifetime *is* a scope: a
     * connect that returns a handle invites code that forgets to close it, and a forgotten WebSocket is
     * a thread and a buffer that never go away.
     */
    public suspend fun connect(
        url: String,
        onOpen: suspend (RealtimeSink) -> Unit,
        onText: suspend (String) -> Unit,
    ): RealtimeClosure
}

/** Sends outbound text on an open connection. */
public fun interface RealtimeSink {
    public suspend fun send(text: String)
}

/** Why a connection ended. */
public data class RealtimeClosure(
    public val code: Int = 0,
    public val reason: String = "",
    /**
     * Whether reconnecting is worth trying.
     *
     * False for a refusal the server will repeat — a protocol mismatch, a revoked device. Reconnecting
     * into one of those is a loop that never ends and fills somebody's log.
     */
    public val isRetryable: Boolean = true,
) {
    public companion object {
        public val Normal: RealtimeClosure = RealtimeClosure()

        public fun terminal(reason: String): RealtimeClosure =
            RealtimeClosure(reason = reason, isRetryable = false)
    }
}
