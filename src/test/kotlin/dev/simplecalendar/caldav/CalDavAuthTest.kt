package dev.simplecalendar.caldav

import dev.simplecalendar.config.CalDavConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Authentication scheme negotiation.
 *
 * Baikal's installer defaults to Digest, and a Digest server rejects Basic outright — so a client
 * that only speaks Basic fails against a stock installation. This pins both directions down,
 * because the live server that proved it works is not going to be around in CI.
 */
class CalDavAuthTest {

    @Test
    fun `a digest-only server is talked to with digest`() {
        val schemes = mutableListOf<String>()

        withServer(
            challenge = """Digest realm="BaikalDAV",qop="auth",nonce="abc123",opaque="xyz"""",
            accept = { header ->
                schemes += header?.substringBefore(' ') ?: "none"
                header != null && header.startsWith("Digest")
            },
        ) { client, url -> assertEquals("ctag-1", client.getCtag(url)) }

        assertTrue(schemes.contains("Digest"), "the client never tried digest: $schemes")
        assertTrue(
            schemes.none { it == "Basic" },
            "sending Basic to a digest server is what a stock Baikal rejects: $schemes",
        )
    }

    @Test
    fun `a basic-only server is talked to with basic, without waiting for a challenge`() {
        val schemes = mutableListOf<String>()

        withServer(
            challenge = """Basic realm="BaikalDAV"""",
            accept = { header ->
                schemes += header?.substringBefore(' ') ?: "none"
                header != null && header.startsWith("Basic")
            },
        ) { client, url -> assertEquals("ctag-1", client.getCtag(url)) }

        // The probe goes out unauthenticated; after that the header is present straight away.
        assertEquals(listOf("none", "Basic"), schemes)
    }

    /**
     * Runs [block] against a server that answers 401 with [challenge] until [accept] is happy.
     */
    private fun withServer(
        challenge: String,
        accept: (String?) -> Boolean,
        block: suspend (client: CalDavClient, calendarUrl: String) -> Unit,
    ) = runBlocking {
        val server: EmbeddedServer<*, *> = embeddedServer(Netty, port = 0) {
            routing {
                route("{...}") {
                    handle {
                        call.receiveText()
                        if (!accept(call.request.headers[HttpHeaders.Authorization])) {
                            call.response.header(HttpHeaders.WWWAuthenticate, challenge)
                            call.respondText("denied", status = HttpStatusCode.Unauthorized)
                        } else {
                            call.respondText(CTAG, ContentType.Application.Xml, HttpStatusCode.MultiStatus)
                        }
                    }
                }
            }
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val url = "http://localhost:$port/dav.php/calendars/family/default/"

        val client = CalDavClient(CalDavConfig(url, "family", "secret"))
        try {
            block(client, url)
        } finally {
            client.close()
            server.stop(500, 1000)
        }
    }

    private companion object {
        val CTAG = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
              <d:response>
                <d:href>/dav.php/calendars/family/default/</d:href>
                <d:propstat>
                  <d:prop><cs:getctag>ctag-1</cs:getctag></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
    }
}
