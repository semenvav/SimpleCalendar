package dev.simplecalendar.net

import dev.simplecalendar.config.Env
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import java.net.ProxySelector
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The claim `net/Proxy.kt` rests on: Ktor's CIO engine asks the default [ProxySelector] about every
 * request, speaks the proxy's language — an absolute URL for plain HTTP, `CONNECT` for HTTPS — and
 * leaves hosts in `NO_PROXY` alone.
 *
 * If a Ktor upgrade stops consulting the selector, this is the test that says so.
 */
class ProxyRoutingTest {

    private lateinit var proxy: ServerSocket
    private val requestLines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var previousSelector: ProxySelector? = null

    @BeforeTest
    fun startProxy() {
        proxy = ServerSocket(0)
        thread(isDaemon = true, name = "fake-squid") {
            while (!proxy.isClosed) {
                val socket = runCatching { proxy.accept() }.getOrNull() ?: break
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return@use
                    requestLines += requestLine
                    while (!reader.readLine().isNullOrEmpty()) {
                        // Headers are not needed, only read past.
                    }

                    val answer = if (requestLine.startsWith("CONNECT")) {
                        // A whitelist proxy turning a host away, as Squid does.
                        "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
                    } else {
                        "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 10\r\nConnection: close\r\n\r\nvia squid!"
                    }
                    it.getOutputStream().apply { write(answer.toByteArray()); flush() }
                }
            }
        }

        previousSelector = ProxySelector.getDefault()
        val settings = ProxySettings.from(
            Env.of(
                mapOf(
                    "HTTP_PROXY" to "http://127.0.0.1:${proxy.localPort}",
                    "HTTPS_PROXY" to "http://127.0.0.1:${proxy.localPort}",
                    "NO_PROXY" to "baikal.test",
                ),
            ),
        )
        OutgoingProxy.install(settings)
    }

    @AfterTest
    fun stopProxy() {
        ProxySelector.setDefault(previousSelector)
        proxy.close()
    }

    @Test
    fun `plain http goes to the proxy with the full URL`() = runBlocking {
        HttpClient(CIO).use { client ->
            assertEquals("via squid!", client.get("http://weather.test/v1/forecast?x=1").bodyAsText())
        }
        assertEquals(listOf("GET http://weather.test/v1/forecast?x=1 HTTP/1.1"), requestLines.toList())
    }

    @Test
    fun `https is tunnelled with CONNECT`() = runBlocking {
        HttpClient(CIO).use { client ->
            runCatching { client.get("https://api.open-meteo.com/v1/forecast") }
        }
        assertEquals(listOf("CONNECT api.open-meteo.com:443 HTTP/1.1"), requestLines.toList())
    }

    @Test
    fun `a host in NO_PROXY never reaches the proxy`() = runBlocking {
        HttpClient(CIO).use { client ->
            // The name does not resolve, so this fails — which is the point: it was tried directly.
            runCatching { client.get("http://baikal.test/dav.php/") }
        }
        assertTrue(requestLines.isEmpty(), "went through the proxy: $requestLines")
    }

    @Test
    fun `the start-up log names the route`() {
        assertEquals("via http://127.0.0.1:${proxy.localPort}", OutgoingProxy.describeRoute("https://api.open-meteo.com"))
        assertEquals("direct", OutgoingProxy.describeRoute("http://baikal.test/dav.php/"))
    }
}
