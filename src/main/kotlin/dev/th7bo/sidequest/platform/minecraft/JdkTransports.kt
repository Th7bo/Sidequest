package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.backend.HttpExchange
import dev.th7bo.sidequest.platform.backend.HttpMethod
import dev.th7bo.sidequest.platform.backend.HttpRequest
import dev.th7bo.sidequest.platform.backend.HttpTransport
import dev.th7bo.sidequest.platform.backend.RealtimeClosure
import dev.th7bo.sidequest.platform.backend.RealtimeSink
import dev.th7bo.sidequest.platform.backend.RealtimeTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest as JdkRequest
import java.net.http.HttpResponse as JdkResponse
import java.net.http.HttpTimeoutException
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage

/**
 * HTTP over the JDK's own client.
 *
 * **No new dependency, deliberately.** A mod ships every library it uses into somebody's game, and every
 * one of those is a version that can conflict with another mod's. The JDK has had a perfectly good HTTP
 * client since 11 and the mod targets 25, so bundling one would be adding a conflict to solve a problem
 * that does not exist.
 *
 * Thin on purpose: retries, backoff, token refresh, error mapping and queueing are all in
 * `DefaultBackendClient`, where a fake transport can make the network misbehave on demand. This class has
 * no logic worth testing, which is the property that makes the rest of it testable.
 */
class JdkHttpTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        // Redirects are followed, because a homelab behind a reverse proxy that redirects to HTTPS is the
        // normal deployment and failing on it would be a confusing first experience.
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build(),
) : HttpTransport {

    override suspend fun send(request: HttpRequest): HttpExchange = withContext(Dispatchers.IO) {
        val built = runCatching { build(request) }.getOrElse { thrown ->
            // A malformed URL is a configuration mistake, not a network failure, and it is reported the same
            // way because the caller can do nothing different about either.
            return@withContext HttpExchange.Failure("bad request: ${thrown.message}")
        }

        try {
            val response = client.sendAsync(built, JdkResponse.BodyHandlers.ofString()).await()
            HttpExchange.Response(
                status = response.statusCode(),
                body = response.body().orEmpty(),
                // Lower-cased, because HTTP header names are case-insensitive and the JDK preserves
                // whatever the server sent. A lookup by the exact spelling we expect would work against
                // one server and not another.
                headers = response.headers().map().mapKeys { it.key.lowercase() }
                    .mapValues { it.value.firstOrNull().orEmpty() },
            )
        } catch (timeout: HttpTimeoutException) {
            HttpExchange.Failure(timeout.message ?: "timed out", isTimeout = true)
        } catch (thrown: Exception) {
            // Unreachable, refused, DNS, TLS. All the same to the caller: try again later.
            HttpExchange.Failure(thrown.message ?: thrown::class.simpleName ?: "failed")
        }
    }

    private fun build(request: HttpRequest): JdkRequest {
        val builder = JdkRequest.newBuilder(URI.create(request.url))
            .timeout(Duration.ofMillis(request.timeoutMillis))

        for ((name, value) in request.headers) builder.header(name, value)

        return when (request.method) {
            HttpMethod.GET -> builder.GET()
            HttpMethod.POST -> builder.POST(JdkRequest.BodyPublishers.ofString(request.body.orEmpty()))
        }.build()
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
    }
}

/**
 * The realtime connection over the JDK's WebSocket.
 *
 * Two things here are not obvious and both are the JDK's fault rather than choices.
 *
 * **Text arrives in fragments.** `onText` is called with pieces and a `last` flag, so a message has to be
 * reassembled. A client that treated each fragment as a message would work perfectly until somebody sent
 * a payload large enough to split — which is to say, until somebody shared a waypoint with a long note.
 *
 * **Backpressure is manual.** The JDK stops delivering until `request(1)` is called. Forgetting it produces
 * a connection that receives exactly one message and then goes quiet.
 */
class JdkRealtimeTransport(
    private val client: HttpClient = HttpClient.newHttpClient(),
) : RealtimeTransport {

    override suspend fun connect(
        url: String,
        onOpen: suspend (RealtimeSink) -> Unit,
        onText: suspend (String) -> Unit,
    ): RealtimeClosure {
        // Inbound text is handed to the coroutine through a channel rather than the listener calling
        // `onText` directly: the listener runs on the JDK's own executor, and suspending there would block a
        // thread the JDK needs.
        val inbound = Channel<String>(Channel.BUFFERED)
        val closed = Channel<RealtimeClosure>(Channel.CONFLATED)
        val fragments = StringBuilder()

        val listener = object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                webSocket.request(1)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                fragments.append(data)
                if (last) {
                    inbound.trySend(fragments.toString())
                    fragments.setLength(0)
                }
                // Without this the JDK delivers nothing further.
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                closed.trySend(
                    RealtimeClosure(
                        code = statusCode,
                        reason = reason,
                        // A refusal the server means: it will refuse the next attempt the same way.
                        isRetryable = statusCode != CANNOT_ACCEPT,
                    ),
                )
                inbound.close()
                return null
            }

            override fun onError(webSocket: WebSocket?, error: Throwable) {
                closed.trySend(RealtimeClosure(reason = error.message ?: "socket error"))
                inbound.close()
            }
        }

        val socket = try {
            client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .buildAsync(URI.create(url), listener)
                .await()
        } catch (thrown: Exception) {
            return RealtimeClosure(reason = thrown.message ?: "could not connect")
        }

        onOpen(RealtimeSink { text -> socket.sendText(text, true).await() })

        // Pumps until the socket closes. The loop *is* the connection's lifetime, which is what makes
        // cancelling the job that runs it the whole of shutdown.
        try {
            for (text in inbound) onText(text)
        } finally {
            runCatching { socket.abort() }
        }

        return closed.tryReceive().getOrNull() ?: RealtimeClosure.Normal
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L

        /** The close code the server uses for a refusal — an unauthenticated or mismatched client. */
        const val CANNOT_ACCEPT = 1003
    }
}
