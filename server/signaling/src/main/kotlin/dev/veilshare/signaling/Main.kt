package dev.veilshare.signaling

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(CIO, port = port) {
        signalingModule()
    }.start(wait = true)
}
