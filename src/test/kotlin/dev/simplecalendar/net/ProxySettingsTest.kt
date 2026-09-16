package dev.simplecalendar.net

import dev.simplecalendar.config.Env
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` as they are written in real compose files.
 *
 * Other containers on the same network read these variables with curl's or Go's rules, so a
 * difference here means a service that is reachable from one container and not from the next.
 */
class ProxySettingsTest {

    /** Copied from a working compose file, stray space included. */
    private val composeExample = mapOf(
        "HTTP_PROXY" to "http://squid:3128",
        "HTTPS_PROXY" to "http://squid:3128",
        "NO_PROXY" to "localhost,127.0.0.1,.example.win,172.16.0.0/12, multi-scrobbler",
    )

    private fun selector(values: Map<String, String>) = EnvProxySelector(ProxySettings.from(Env.of(values)))

    private fun route(values: Map<String, String>, url: String): String {
        val proxy = selector(values).select(URI(url)).single()
        if (proxy.type() == Proxy.Type.DIRECT) return "direct"
        val address = proxy.address() as InetSocketAddress
        return "${address.hostString}:${address.port}"
    }

    @Test
    fun `the compose example sends the internet through squid and the neighbours direct`() {
        assertEquals("squid:3128", route(composeExample, "https://api.open-meteo.com/v1/forecast"))
        assertEquals("squid:3128", route(composeExample, "http://example.org/"))

        assertEquals("direct", route(composeExample, "http://multi-scrobbler:9078/"), "the entry after the space")
        assertEquals("direct", route(composeExample, "https://photos.example.win/api"), "a subdomain")
        assertEquals("direct", route(composeExample, "https://example.win/"), "the domain itself")
        assertEquals("direct", route(composeExample, "http://172.20.0.5:2283/api"), "inside the /12 block")
        assertEquals("direct", route(composeExample, "http://localhost:8080/"))
        assertEquals("direct", route(composeExample, "http://127.0.0.1:8080/"))
    }

    @Test
    fun `a name is never resolved to test it against an address block`() {
        // Docker would resolve this into 172.16.0.0/12, but neither curl nor Go look, and nor do we.
        assertEquals("squid:3128", route(composeExample, "http://homeassistant:8123/api/states"))
    }

    @Test
    fun `names match whole labels only`() {
        assertEquals("squid:3128", route(composeExample, "https://notexample.win/"))
        assertEquals("squid:3128", route(composeExample, "http://multi-scrobbler.evil.org/"))
        assertEquals("squid:3128", route(composeExample, "http://172.32.0.1/"), "just outside the /12 block")
    }

    @Test
    fun `https goes direct when only HTTP_PROXY is set`() {
        val onlyHttp = mapOf("HTTP_PROXY" to "http://squid:3128")
        assertEquals("squid:3128", route(onlyHttp, "http://example.org/"))
        assertEquals("direct", route(onlyHttp, "https://example.org/"))
    }

    @Test
    fun `lowercase spellings work and uppercase wins over them`() {
        assertEquals("squid:3128", route(mapOf("https_proxy" to "squid:3128"), "https://example.org/"))
        assertEquals(
            "upper:1",
            route(mapOf("HTTPS_PROXY" to "http://upper:1", "https_proxy" to "http://lower:2"), "https://example.org/"),
        )
        assertEquals(
            "direct",
            route(mapOf("HTTPS_PROXY" to "http://squid:3128", "no_proxy" to "example.org"), "https://example.org/"),
        )
    }

    @Test
    fun `a star bypasses everything`() {
        assertEquals("direct", route(composeExample + ("NO_PROXY" to "*"), "https://example.org/"))
    }

    @Test
    fun `loopback is direct even when NO_PROXY is empty`() {
        val noBypass = composeExample - "NO_PROXY"
        assertEquals("direct", route(noBypass, "http://localhost:8080/"))
        assertEquals("direct", route(noBypass, "http://127.0.0.53/"))
        assertEquals("direct", route(noBypass, "http://[::1]:8080/"))
    }

    @Test
    fun `ports, leading wildcards and IPv6 blocks`() {
        val values = composeExample + ("NO_PROXY" to "*.lan, internal:8443, [fd00::1]:80, fd12::/16")
        assertEquals("direct", route(values, "http://nas.lan/"))
        assertEquals("direct", route(values, "https://internal:8443/"))
        assertEquals("squid:3128", route(values, "https://internal/"), "the entry names another port")
        assertEquals("direct", route(values, "http://[fd00::1]/"))
        assertEquals("direct", route(values, "http://[fd12:3456::7]/"))
        assertEquals("squid:3128", route(values, "http://[fd13::7]/"))
    }

    @Test
    fun `a container name with an underscore is still recognised`() {
        // java.net.URI refuses such names as hosts, and reports no host at all.
        val values = composeExample + ("NO_PROXY" to "calendar_baikal_1")
        assertNull(URI("http://calendar_baikal_1/dav.php/").host)
        assertEquals("direct", route(values, "http://calendar_baikal_1/dav.php/"))
        assertEquals("squid:3128", route(values, "http://other_container_1/"))
    }

    @Test
    fun `without proxy variables nothing is enabled`() {
        val settings = ProxySettings.from(Env.of(mapOf("NO_PROXY" to "localhost", "HTTP_PROXY" to "  ")))
        assertFalse(settings.enabled)
    }

    @Test
    fun `a proxy we cannot use fails at start-up rather than on every request`() {
        assertFailsWith<IllegalArgumentException> {
            ProxySettings.from(Env.of(mapOf("HTTPS_PROXY" to "socks5://squid:1080")))
        }
        assertFailsWith<IllegalArgumentException> {
            ProxySettings.from(Env.of(mapOf("HTTPS_PROXY" to "https://squid:3128")))
        }
        val credentials = assertFailsWith<IllegalArgumentException> {
            ProxySettings.from(Env.of(mapOf("HTTP_PROXY" to "http://user:hunter2@squid:3128")))
        }
        assertFalse("hunter2" in credentials.message.orEmpty(), "the password must not reach the log")

        assertFailsWith<IllegalArgumentException> { NoProxy.parse("10.0.0.0/33") }
    }

    @Test
    fun `a proxy without a port gets the HTTP default`() {
        assertEquals(ProxyAddress("squid", 80), ProxyAddress.parse("HTTP_PROXY", "http://squid"))
        assertEquals(ProxyAddress("squid", 3128), ProxyAddress.parse("HTTP_PROXY", "squid:3128/"))
    }

    @Test
    fun `telling addresses from names never asks DNS`() {
        assertTrue(NoProxy.ipLiteral("10.1.2.3") != null)
        assertTrue(NoProxy.ipLiteral("fd00::1") != null)
        // Each of these would be a lookup if handed to InetAddress as they are.
        assertNull(NoProxy.ipLiteral("999.1.1.1"))
        assertNull(NoProxy.ipLiteral("g:1"))
        assertNull(NoProxy.ipLiteral("squid"))
    }
}
