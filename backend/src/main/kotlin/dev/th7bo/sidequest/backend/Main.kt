package dev.th7bo.sidequest.backend

import dev.th7bo.sidequest.protocol.Endpoints
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Starts the server, or checks whether one is already healthy.
 *
 * Deliberately almost nothing. Everything the server does is in [SidequestBackend], which a test can build
 * against a temporary directory — a server whose only entry point is `main` is a server that can only be
 * tested by starting it and pointing something at a port.
 */
public fun main(args: Array<String>) {
    if (HEALTH_CHECK_FLAG in args) exitProcess(runHealthCheck())

    val config = BackendConfig.fromEnvironment()
    val backend = SidequestBackend(config)

    if (config.operatorToken == null) {
        // Said loudly, because a server nobody can pair to looks broken rather than locked.
        println(
            "Sidequest: SIDEQUEST_OPERATOR_TOKEN is not set. Pairing is disabled — " +
                "set it to a long random string to allow devices to be approved.",
        )
    }

    println("Sidequest backend on ${config.host}:${config.port}, state at ${config.statePath.toAbsolutePath()}")

    embeddedServer(Netty, port = config.port, host = config.host) {
        backend.install(this)
    }.start(wait = true)
}

/**
 * Asks a running instance whether it is alive.
 *
 * The container health check, and it lives in the application rather than being a `curl` in the image
 * because a JRE image has no `curl` — installing one to ask a question the JVM can already answer would be
 * another package to keep patched for no reason.
 *
 * `/api/info` is the right thing to ask: it is unauthenticated, touches no state, and returning it proves
 * the routing is installed rather than only that a port is open.
 */
private fun runHealthCheck(): Int {
    val config = BackendConfig.fromEnvironment()
    // Always over loopback. The configured host may be a wildcard, which is not an address to connect to.
    val url = "http://127.0.0.1:${config.port}${Endpoints.SERVER_INFO}"

    return try {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(HEALTH_TIMEOUT_SECONDS)).build()
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(HEALTH_TIMEOUT_SECONDS))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) 0 else 1
    } catch (thrown: Exception) {
        System.err.println("health check failed: ${thrown.message}")
        1
    }
}

private const val HEALTH_CHECK_FLAG = "--health-check"
private const val HEALTH_TIMEOUT_SECONDS = 3L
