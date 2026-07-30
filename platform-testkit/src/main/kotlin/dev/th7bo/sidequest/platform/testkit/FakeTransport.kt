package dev.th7bo.sidequest.platform.testkit

import dev.th7bo.sidequest.platform.backend.HttpExchange
import dev.th7bo.sidequest.platform.backend.HttpRequest
import dev.th7bo.sidequest.platform.backend.HttpTransport
import dev.th7bo.sidequest.platform.backend.RealtimeClosure
import dev.th7bo.sidequest.platform.backend.RealtimeSink
import dev.th7bo.sidequest.platform.backend.RealtimeTransport
import dev.th7bo.sidequest.platform.backend.StoredSession
import dev.th7bo.sidequest.platform.backend.TokenStore

/**
 * An HTTP transport that answers however a test needs.
 *
 * The reason [HttpTransport] is an interface. Everything interesting about a network client is what it
 * does when the network misbehaves — a timeout, a 500, a 401 followed by a good refresh, a 429 with a
 * retry-after — and against a real server none of those can be produced on demand. Here every one of them
 * is one line.
 */
public class FakeTransport : HttpTransport {

    /** Every request made, in order. What a test asserts about retries and headers. */
    public val requests: MutableList<HttpRequest> = ArrayList()

    /**
     * Answers, by path. The last matching handler wins, so a test can override a default it set earlier.
     */
    private val handlers = ArrayList<Pair<(HttpRequest) -> Boolean, (HttpRequest) -> HttpExchange>>()

    /** Used when nothing matches. Unreachable, which is the honest default for an unconfigured server. */
    public var fallback: (HttpRequest) -> HttpExchange = { HttpExchange.Failure("no handler") }

    override suspend fun send(request: HttpRequest): HttpExchange {
        requests.add(request)
        val handler = handlers.lastOrNull { it.first(request) }?.second ?: fallback
        return handler(request)
    }

    /** Answers any request whose URL contains [pathFragment]. */
    public fun on(pathFragment: String, handler: (HttpRequest) -> HttpExchange) {
        handlers.add({ request: HttpRequest -> pathFragment in request.url } to handler)
    }

    /** Answers with [body] and a 200, plus the protocol headers a real server sends. */
    public fun respond(pathFragment: String, body: String, status: Int = 200, serverTimeMillis: Long? = null) {
        on(pathFragment) { request ->
            HttpExchange.Response(
                status = status,
                body = body,
                headers = buildMap {
                    put("X-Sidequest-Protocol", "1")
                    serverTimeMillis?.let { put("X-Sidequest-Server-Time", it.toString()) }
                    request.headers["X-Sidequest-Request-Id"]?.let { put("X-Sidequest-Request-Id", it) }
                },
            )
        }
    }

    /**
     * Answers differently each time it is called.
     *
     * For the sequences that matter: fail, fail, succeed — which is the only way to test that a retry
     * happens *and* that it stops.
     */
    public fun sequence(pathFragment: String, vararg responses: HttpExchange) {
        var index = 0
        on(pathFragment) {
            val response = responses[min(index, responses.lastIndex)]
            index++
            response
        }
    }

    /** How many requests went to a path. */
    public fun countFor(pathFragment: String): Int = requests.count { pathFragment in it.url }

    public fun clear() {
        requests.clear()
        handlers.clear()
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b
}

/** A token store in memory. */
public class FakeTokenStore(private var session: StoredSession? = null) : TokenStore {

    /** True once [clear] has been called, so a test can assert credentials were dropped. */
    public var wasCleared: Boolean = false
        private set

    override suspend fun load(): StoredSession? = session

    override suspend fun save(session: StoredSession) {
        this.session = session
    }

    override suspend fun clear() {
        session = null
        wasCleared = true
    }
}

/**
 * A realtime transport driven by a test.
 *
 * Connections are scripted: a test says what the server sends and when the connection ends, which is what
 * makes resume and reconnection testable at all.
 */
public class FakeRealtimeTransport : RealtimeTransport {

    /** URLs connected to, in order. Carries the resume token, so a test can assert authentication. */
    public val connections: MutableList<String> = ArrayList()

    /** Everything the client sent, across all connections. */
    public val sent: MutableList<String> = ArrayList()

    /**
     * What each connection does, in order.
     *
     * One entry per expected connection. When they run out the transport reports a terminal closure, so a
     * test's `run` loop ends rather than spinning.
     */
    public val scripts: MutableList<suspend (RealtimeSink) -> RealtimeClosure> = ArrayList()

    private var index = 0

    override suspend fun connect(
        url: String,
        onOpen: suspend (RealtimeSink) -> Unit,
        onText: suspend (String) -> Unit,
    ): RealtimeClosure {
        connections.add(url)
        val sink = RealtimeSink { text -> sent.add(text) }
        onOpen(sink)

        val script = scripts.getOrNull(index++) ?: return RealtimeClosure.terminal("no more scripted connections")
        this.deliver = onText
        return script(sink)
    }

    private var deliver: (suspend (String) -> Unit)? = null

    /** Delivers a frame to the connected client. Used from inside a script. */
    public suspend fun receive(text: String) {
        deliver?.invoke(text)
    }
}
