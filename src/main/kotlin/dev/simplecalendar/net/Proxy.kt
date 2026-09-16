package dev.simplecalendar.net

import dev.simplecalendar.config.Env
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Where outgoing requests go, read from the variables every other container on the network
 * already understands: `HTTP_PROXY`, `HTTPS_PROXY` and `NO_PROXY`, in either case.
 *
 * As with curl and Go, `HTTPS_PROXY` alone decides `https://` URLs — there is no falling back to
 * `HTTP_PROXY` — so a proxy for everything means setting both.
 */
data class ProxySettings(
    /** For `http://` URLs. */
    val http: ProxyAddress?,
    /** For `https://` URLs, which go through the proxy as a `CONNECT` tunnel. */
    val https: ProxyAddress?,
    val bypass: NoProxy,
) {
    val enabled: Boolean get() = http != null || https != null

    override fun toString(): String =
        if (!enabled) "none"
        else "http ${http ?: "direct"}, https ${https ?: "direct"}, direct for: ${bypass.ifEmpty { "loopback only" }}"

    companion object {
        fun from(env: Env): ProxySettings {
            // Upper case first, as Go reads them; compose files mostly spell them that way.
            fun variable(name: String): Pair<String, String>? =
                env.string(name)?.let { name to it } ?: env.string(name.lowercase())?.let { name.lowercase() to it }

            return ProxySettings(
                http = variable("HTTP_PROXY")?.let { (name, value) -> ProxyAddress.parse(name, value) },
                https = variable("HTTPS_PROXY")?.let { (name, value) -> ProxyAddress.parse(name, value) },
                bypass = NoProxy.parse(variable("NO_PROXY")?.second),
            )
        }
    }
}

/** An HTTP proxy such as Squid. It listens on plain HTTP even for traffic that is HTTPS. */
data class ProxyAddress(val host: String, val port: Int) {

    override fun toString() = "http://$host:$port"

    companion object {
        fun parse(name: String, raw: String): ProxyAddress {
            val uri = runCatching { URI(if ("://" in raw) raw else "http://$raw") }
                .getOrElse { error("$name is not a URL: '$raw'") }

            // Ktor's CIO engine talks to a proxy over plain HTTP only. Better to refuse at start-up
            // than to have every request fail later with a message about something else.
            require(uri.scheme.equals("http", ignoreCase = true)) {
                "$name must be an http:// proxy, got ${uri.scheme}:// — Squid and similar proxies " +
                    "listen on plain HTTP and tunnel HTTPS through it"
            }
            // The value is not echoed back: it would carry the password into the log.
            require(uri.rawUserInfo == null) { "$name contains credentials, which are not supported" }
            val host = uri.host ?: error("$name has no host: '$raw'")

            return ProxyAddress(host, if (uri.port == -1) 80 else uri.port)
        }
    }
}

/**
 * `NO_PROXY`: the hosts reached directly.
 *
 * Follows the convention curl and Go share, since other containers read the same variable:
 * - entries are comma-separated, spaces around them ignored;
 * - `*` on its own means everything;
 * - a name covers itself and all its subdomains, with or without a leading dot — `.example.com`
 *   and `example.com` both cover `example.com` and `calendar.example.com`;
 * - an IP address covers itself, a CIDR block such as `172.16.0.0/12` the addresses inside it;
 * - `:port` after a name or an address narrows the entry to that port.
 *
 * A CIDR block matches only URLs written with an IP address. Names are never resolved to check —
 * curl and Go do not either, and routing that depends on a DNS answer is routing nobody can
 * predict — so a container reached by name, `homeassistant` say, has to be listed by name.
 *
 * Loopback is always direct, listed or not: no proxy can reach this container's own localhost.
 */
class NoProxy private constructor(
    private val everything: Boolean,
    private val rules: List<Rule>,
    private val source: String,
) {

    fun bypasses(host: String, port: Int): Boolean {
        if (everything) return true
        val name = host.removeSurrounding("[", "]").trimEnd('.').lowercase()
        val address = ipLiteral(name)

        if (name == "localhost" || name.endsWith(".localhost") || address?.isLoopbackAddress == true) return true
        return rules.any { it.matches(name, address, port) }
    }

    fun ifEmpty(fallback: () -> String): String = source.ifEmpty(fallback)

    override fun toString() = source

    private sealed interface Rule {
        fun matches(name: String, address: InetAddress?, port: Int): Boolean

        data class Domain(val domain: String, val port: Int?) : Rule {
            override fun matches(name: String, address: InetAddress?, port: Int) =
                (this.port == null || this.port == port) && (name == domain || name.endsWith(".$domain"))
        }

        class Block(val network: ByteArray, val prefix: Int, val port: Int?) : Rule {
            override fun matches(name: String, address: InetAddress?, port: Int): Boolean {
                if (address == null || (this.port != null && this.port != port)) return false
                val bytes = address.address
                if (bytes.size != network.size) return false
                val whole = prefix / 8
                for (i in 0 until whole) if (bytes[i] != network[i]) return false
                val rest = prefix % 8
                if (rest == 0) return true
                val mask = (0xFF shl (8 - rest)) and 0xFF
                return (bytes[whole].toInt() and mask) == (network[whole].toInt() and mask)
            }
        }
    }

    companion object {
        fun parse(raw: String?): NoProxy {
            val entries = raw?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
            return NoProxy(
                everything = "*" in entries,
                rules = entries.filter { it != "*" }.map(::parseEntry),
                source = entries.joinToString(","),
            )
        }

        private fun parseEntry(entry: String): Rule {
            val (hostPart, port) = splitPort(entry)
            val host = hostPart.removeSurrounding("[", "]").lowercase()

            if ('/' in host) {
                val address = ipLiteral(host.substringBefore('/'))
                val prefix = host.substringAfter('/').toIntOrNull()
                require(address != null && prefix != null && prefix in 0..address.address.size * 8) {
                    "NO_PROXY: '$entry' is not a valid address block"
                }
                return Rule.Block(address.address, prefix, port)
            }
            ipLiteral(host)?.let { return Rule.Block(it.address, it.address.size * 8, port) }
            return Rule.Domain(host.removePrefix("*").removePrefix(".").trimEnd('.'), port)
        }

        /** `host:port`, `[v6]:port`; a bare IPv6 address has colons but no port. */
        private fun splitPort(entry: String): Pair<String, Int?> {
            if (entry.startsWith("[")) {
                val close = entry.indexOf(']').takeIf { it > 0 } ?: return entry to null
                val port = entry.substring(close + 1).removePrefix(":").toIntOrNull()
                return entry.substring(0, close + 1) to port
            }
            if (entry.count { it == ':' } != 1) return entry to null
            val port = entry.substringAfter(':').toIntOrNull() ?: return entry to null
            return entry.substringBefore(':') to port
        }

        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

        /**
         * The address [text] spells, or null for a name. Never touches DNS: `InetAddress` sees only
         * a checked IPv4 literal, or an IPv6 candidate in brackets, which it must parse or reject
         * rather than look up.
         */
        internal fun ipLiteral(text: String): InetAddress? {
            val v4 = IPV4.matchEntire(text)
            val candidate = when {
                v4 != null -> text.takeIf { v4.groupValues.drop(1).all { it.toInt() <= 255 } }
                ':' in text -> "[$text]"
                else -> null
            } ?: return null
            return runCatching { InetAddress.getByName(candidate) }.getOrNull()
        }
    }
}

/**
 * Routes every outgoing request by [ProxySettings].
 *
 * It is installed as the JVM-wide default because that is where Ktor's CIO engine looks: a client
 * with no proxy of its own asks `ProxySelector.getDefault()` about every request. So one selector
 * covers the CalDAV client and every integration alike, each request decided by its own host, and
 * nothing has to be passed around.
 */
class EnvProxySelector(private val settings: ProxySettings) : ProxySelector() {

    override fun select(uri: URI): List<Proxy> {
        val proxy = when (uri.scheme?.lowercase()) {
            "http" -> settings.http
            "https" -> settings.https
            else -> null
        } ?: return DIRECT

        val host = hostOf(uri) ?: return DIRECT
        val port = uri.port.takeIf { it != -1 } ?: if (uri.scheme.equals("https", true)) 443 else 80
        if (settings.bypass.bypasses(host, port)) return DIRECT

        // Unresolved: the proxy's name is looked up when connecting, not every time we decide.
        return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
    }

    override fun connectFailed(uri: URI?, address: SocketAddress?, cause: IOException?) {
        // Nothing to learn: there is a single proxy per scheme and no alternative to switch to.
    }

    /**
     * `URI.host` is null for names `java.net.URI` considers invalid — with an underscore, as
     * compose generates for containers (`calendar_baikal_1`) — so the authority is read by hand.
     */
    private fun hostOf(uri: URI): String? {
        uri.host?.let { return it }
        val authority = uri.rawAuthority?.substringAfterLast('@') ?: return null
        return if (authority.startsWith("[")) authority.substringBefore(']') + "]"
        else authority.substringBefore(':').takeIf { it.isNotEmpty() }
    }

    private companion object {
        val DIRECT = listOf(Proxy.NO_PROXY)
    }
}

object OutgoingProxy {

    /** Makes [settings] apply to every request the application sends. Without a proxy, changes nothing. */
    fun install(settings: ProxySettings) {
        if (settings.enabled) ProxySelector.setDefault(EnvProxySelector(settings))
    }

    /** `direct` or `via http://squid:3128`, for start-up lines that name where something is fetched from. */
    fun describeRoute(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "direct"
        val proxy = ProxySelector.getDefault()?.select(uri)?.firstOrNull { it.type() == Proxy.Type.HTTP }
            ?: return "direct"
        val address = proxy.address() as? InetSocketAddress ?: return "via proxy"
        return "via http://${address.hostString}:${address.port}"
    }
}
