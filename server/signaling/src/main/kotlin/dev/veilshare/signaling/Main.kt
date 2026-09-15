package dev.veilshare.signaling

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    val host = System.getenv("HOST")?.trim()?.takeIf(String::isNotEmpty) ?: "0.0.0.0"
    val port = System.getenv("PORT")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080
    embeddedServer(CIO, host = host, port = port) {
        signalingModule()
    }.start(wait = true)
}
