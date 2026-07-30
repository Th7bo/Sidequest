package dev.th7bo.sidequest.backend

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Starts the server.
 *
 * Deliberately almost nothing. Everything the server does is in [SidequestBackend], which a test can
 * build against a temporary directory — a server whose only entry point is `main` is a server that can
 * only be tested by starting it and pointing something at a port.
 */
public fun main() {
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
