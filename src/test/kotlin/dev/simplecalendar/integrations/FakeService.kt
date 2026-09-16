package dev.simplecalendar.integrations

import dev.simplecalendar.config.Env
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.time.ZoneId

/**
 * Runs [block] against a local HTTP server answering like the real service would, with a client
 * built the way the application builds it.
 */
fun withFakeService(routes: Routing.() -> Unit, block: suspend (baseUrl: String, context: IntegrationContext) -> Unit) =
    runBlocking {
        val server = embeddedServer(Netty, port = 0) { routing(routes) }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val http = integrationHttpClient()
        try {
            block("http://localhost:$port", IntegrationContext(Env.of(emptyMap()), http, ZoneId.of("Asia/Jerusalem")))
        } finally {
            http.close()
            server.stop(500, 1000)
        }
    }

/** A context whose settings are [values], for testing what an integration makes of them. */
fun contextWith(vararg values: Pair<String, String>) =
    IntegrationContext(Env.of(values.toMap()), integrationHttpClient(), ZoneId.of("Asia/Jerusalem"))

fun fixture(name: String): String =
    checkNotNull(object {}.javaClass.getResource("/integrations/$name")) { "missing fixture $name" }.readText()
