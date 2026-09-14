package dev.simplecalendar.caldav

import dev.simplecalendar.config.CalDavConfig
import dev.simplecalendar.store.DiscoveredCalendar
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.DigestAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.auth.providers.digest
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.withCharset
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** A calendar resource as fetched from the server. */
data class CalDavResource(val href: String, val etag: String?, val ics: String?)

/** A resource the server reported, with the ETag it currently has. */
data class ResourceRef(val href: String, val etag: String?)

/** Result of one `sync-collection` attempt. */
sealed interface SyncOutcome {
    /** Normal case: what changed since the token we sent, and the token to send next time. */
    data class Delta(
        val changed: List<ResourceRef>,
        val removed: List<String>,
        val syncToken: String?,
    ) : SyncOutcome

    /** Our token is too old or otherwise invalid — the caller must resynchronise in full. */
    data object TokenRejected : SyncOutcome

    /** The server does not implement RFC 6578; fall back to comparing ETags. */
    data object Unsupported : SyncOutcome
}

class CalDavException(val status: Int, message: String) : RuntimeException(message)

/**
 * A CalDAV client covering exactly what this project needs: discovery, incremental sync and
 * single-resource writes.
 *
 * Written directly against Ktor's HTTP client rather than pulling in a WebDAV library. CalDAV
 * is a handful of requests with fixed XML bodies, we only ever talk to one known server, and
 * keeping it here means one HTTP stack in the container instead of two.
 */
class CalDavClient(private val config: CalDavConfig) : AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)

    private val clientLock = Mutex()

    @Volatile
    private var authenticated: HttpClient? = null

    /**
     * The HTTP client, built on first use once we know how the server wants to be authenticated.
     *
     * Baikal defaults to Digest, and a Digest server rejects Basic outright — so guessing is not
     * an option. Installing both providers is not either: Ktor sends a header for every provider
     * whose `sendWithoutRequest` is true, and two `Authorization` headers on one request is not a
     * thing. So we ask the server once and install exactly the provider it asked for.
     */
    private suspend fun http(): HttpClient {
        authenticated?.let { return it }
        return clientLock.withLock {
            authenticated ?: buildClient(detectAuthScheme()).also { authenticated = it }
        }
    }

    private fun buildClient(scheme: AuthScheme) = HttpClient(CIO) {
        expectSuccess = false
        install(Auth) {
            when (scheme) {
                AuthScheme.DIGEST -> digest {
                    credentials { DigestAuthCredentials(config.username, config.password) }
                }
                AuthScheme.BASIC -> basic {
                    credentials { BasicAuthCredentials(config.username, config.password) }
                    // Sending up front halves the round trips against a Basic server.
                    sendWithoutRequest { true }
                }
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    /** Reads the `WWW-Authenticate` challenge from one unauthenticated request. */
    private suspend fun detectAuthScheme(): AuthScheme {
        val probe = HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        }
        val scheme = try {
            val response = probe.request(config.baseUrl) {
                method = HttpMethod("PROPFIND")
                header("Depth", "0")
                contentType(ContentType.Application.Xml.withCharset(Charsets.UTF_8))
                setBody(PROP_CURRENT_USER_PRINCIPAL)
            }
            val challenge = response.headers["WWW-Authenticate"].orEmpty()
            if (challenge.startsWith("Digest", ignoreCase = true)) AuthScheme.DIGEST else AuthScheme.BASIC
        } catch (e: Exception) {
            // Not fatal: the real request will report the actual problem soon enough.
            log.warn("Could not read the auth challenge from {} ({}); assuming Basic", config.baseUrl, e.message)
            AuthScheme.BASIC
        } finally {
            probe.close()
        }
        log.info("CalDAV authentication: {}", scheme.name.lowercase())
        return scheme
    }

    private enum class AuthScheme { BASIC, DIGEST }

    // --- Discovery ----------------------------------------------------------------------------

    /**
     * Walks `current-user-principal` -> `calendar-home-set` -> collections.
     *
     * Each step falls back to the previous URL, so pointing [CalDavConfig.baseUrl] straight at a
     * calendar home (or even a single principal) works just as well as pointing it at the DAV root.
     */
    suspend fun discoverCalendars(): List<DiscoveredCalendar> {
        val principal = findHref(config.baseUrl, PROP_CURRENT_USER_PRINCIPAL, Ns.DAV, "current-user-principal")
            ?: config.baseUrl
        log.debug("Principal: {}", principal)

        val home = findHref(principal, PROP_CALENDAR_HOME, Ns.CALDAV, "calendar-home-set") ?: principal
        log.debug("Calendar home: {}", home)

        return listCalendars(home)
    }

    private suspend fun findHref(url: String, body: String, ns: String, name: String): String? {
        val document = propfind(url, depth = 0, body = body)
        val href = document.responses()
            .firstNotNullOfOrNull { it.prop(ns, name)?.descendant(Ns.DAV, "href")?.text }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return resolve(url, href)
    }

    private suspend fun listCalendars(homeUrl: String): List<DiscoveredCalendar> {
        val document = propfind(homeUrl, depth = 1, body = PROP_CALENDAR_LIST)

        return document.responses().mapNotNull { response ->
            val resourceType = response.prop(Ns.DAV, "resourcetype") ?: return@mapNotNull null
            // A calendar collection is the only thing we care about; the home itself is a plain
            // collection and principal URLs show up here too on some servers.
            if (resourceType.child(Ns.CALDAV, "calendar") == null) return@mapNotNull null
            if (!supportsEvents(response)) return@mapNotNull null

            val url = resolve(homeUrl, response.href)
            DiscoveredCalendar(
                id = calendarId(url),
                url = url,
                name = response.propText(Ns.DAV, "displayname") ?: url.trimEnd('/').substringAfterLast('/'),
                color = response.propText(Ns.APPLE, "calendar-color")?.let(::normaliseColor),
                readOnly = !isWritable(response),
            )
        }
    }

    /** Absent `supported-calendar-component-set` means "everything", per RFC 4791. */
    private fun supportsEvents(response: DavResponse): Boolean {
        val supported = response.prop(Ns.CALDAV, "supported-calendar-component-set") ?: return true
        val components = supported.children(Ns.CALDAV, "comp")
        return components.isEmpty() || components.any { it.getAttribute("name").equals("VEVENT", true) }
    }

    /** Absent `current-user-privilege-set` means the server does not report privileges; assume we may write. */
    private fun isWritable(response: DavResponse): Boolean {
        val privileges = response.prop(Ns.DAV, "current-user-privilege-set") ?: return true
        val granted = privileges.descendants(Ns.DAV, "privilege")
        if (granted.isEmpty()) return true
        return granted.any { it.child(Ns.DAV, "write") != null || it.child(Ns.DAV, "write-content") != null }
    }

    // --- Change detection ---------------------------------------------------------------------

    /**
     * Asks for everything that changed since [syncToken] (RFC 6578).
     *
     * Pass `null` for the initial pass, which returns the whole collection along with a token to
     * use next time.
     */
    suspend fun syncCollection(calendarUrl: String, syncToken: String?): SyncOutcome {
        val body = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:sync-collection xmlns:d="DAV:">
              <d:sync-token>${syncToken?.let(DavXml::escape).orEmpty()}</d:sync-token>
              <d:sync-level>1</d:sync-level>
              <d:prop><d:getetag/></d:prop>
            </d:sync-collection>
        """.trimIndent()

        val response = send(HttpMethod("REPORT"), calendarUrl, depth = 0, body = body)
        val status = response.status.value

        if (status == 403 || status == 409) {
            val text = response.bodyAsText()
            if (text.contains("valid-sync-token", ignoreCase = true)) {
                log.info("Server rejected our sync token for {}; falling back to a full sync", calendarUrl)
                return SyncOutcome.TokenRejected
            }
            throw CalDavException(status, "sync-collection failed: ${text.take(ERROR_SNIPPET)}")
        }
        // Some servers answer an unsupported REPORT with 400/405/501 rather than a DAV error.
        if (status == 400 || status == 405 || status == 501) {
            log.info("Server does not support sync-collection on {} (HTTP {})", calendarUrl, status)
            return SyncOutcome.Unsupported
        }
        if (status !in 200..299) {
            throw CalDavException(status, "sync-collection failed: ${response.bodyAsText().take(ERROR_SNIPPET)}")
        }

        val document = DavXml.parse(response.bodyAsText())
        val changed = mutableListOf<ResourceRef>()
        val removed = mutableListOf<String>()

        for (entry in document.responses()) {
            val href = hrefPath(entry.href).takeIf { it.isNotEmpty() } ?: continue
            // The collection itself appears in the report on some servers; it is not a resource.
            if (href.trimEnd('/') == hrefPath(calendarUrl).trimEnd('/')) continue
            if (entry.status == 404 || entry.status == 410) {
                removed += href
            } else {
                changed += ResourceRef(href, normaliseEtag(entry.propText(Ns.DAV, "getetag")))
            }
        }

        val newToken = document.documentElement.child(Ns.DAV, "sync-token")?.text?.takeIf { it.isNotEmpty() }
        return SyncOutcome.Delta(changed = changed, removed = removed, syncToken = newToken)
    }

    /** Every VEVENT resource in the collection with its ETag — the fallback when sync tokens fail. */
    suspend fun listEventEtags(calendarUrl: String): Map<String, String?> {
        val body = """
            <?xml version="1.0" encoding="utf-8" ?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:prop><d:getetag/></d:prop>
              <c:filter>
                <c:comp-filter name="VCALENDAR">
                  <c:comp-filter name="VEVENT"/>
                </c:comp-filter>
              </c:filter>
            </c:calendar-query>
        """.trimIndent()

        val document = report(calendarUrl, depth = 1, body = body)
        return document.responses()
            .mapNotNull { entry ->
                val href = hrefPath(entry.href).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (href.trimEnd('/') == hrefPath(calendarUrl).trimEnd('/')) return@mapNotNull null
                href to normaliseEtag(entry.propText(Ns.DAV, "getetag"))
            }
            .toMap()
    }

    /** Collection tag — changes whenever anything inside the calendar changes. */
    suspend fun getCtag(calendarUrl: String): String? {
        val document = propfind(calendarUrl, depth = 0, body = PROP_CTAG)
        return document.responses().firstNotNullOfOrNull { it.propText(Ns.CALENDARSERVER, "getctag") }
    }

    // --- Reading and writing resources ---------------------------------------------------------

    /** Fetches calendar data for the given hrefs, in batches so one request never gets huge. */
    suspend fun multiget(calendarUrl: String, hrefs: List<String>): List<CalDavResource> {
        if (hrefs.isEmpty()) return emptyList()

        return hrefs.chunked(MULTIGET_BATCH).flatMap { batch ->
            // Built by appending rather than with a trimIndent template: trimIndent computes the
            // common indent *after* interpolation, so splicing multi-line content into one leaves
            // the XML declaration indented — and a declaration must start at the very first byte.
            val body = buildString {
                append("""<?xml version="1.0" encoding="utf-8" ?>""").append('\n')
                append("""<c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">""").append('\n')
                append("""  <d:prop><d:getetag/><c:calendar-data/></d:prop>""").append('\n')
                for (href in batch) {
                    append("  <d:href>").append(DavXml.escape(href)).append("</d:href>").append('\n')
                }
                append("</c:calendar-multiget>")
            }

            report(calendarUrl, depth = 1, body = body).responses().mapNotNull { entry ->
                val href = hrefPath(entry.href).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                CalDavResource(
                    href = href,
                    etag = normaliseEtag(entry.propText(Ns.DAV, "getetag")),
                    ics = entry.propText(Ns.CALDAV, "calendar-data"),
                )
            }
        }
    }

    /**
     * Creates or replaces a resource.
     *
     * [ifMatch] carries the ETag we believe the resource has; the server answers 412 when it has
     * moved on, which is how a concurrent edit from a phone gets detected instead of overwritten.
     * Pass `null` only when deliberately creating a new resource.
     */
    suspend fun put(calendarUrl: String, href: String, ics: String, ifMatch: String?): String? {
        val url = resolve(calendarUrl, href)
        val response = http().request(url) {
            method = HttpMethod.Put
            contentType(ContentType("text", "calendar").withCharset(Charsets.UTF_8))
            if (ifMatch != null) header("If-Match", "\"$ifMatch\"") else header("If-None-Match", "*")
            setBody(ics)
        }
        val status = response.status.value
        if (status == 412) throw CalDavException(412, "Resource was modified by someone else")
        if (status !in 200..299) {
            throw CalDavException(status, "PUT failed: ${response.bodyAsText().take(ERROR_SNIPPET)}")
        }
        // Servers may omit the new ETag, in which case the caller has to re-read the resource.
        return normaliseEtag(response.headers["ETag"])
    }

    suspend fun delete(calendarUrl: String, href: String, ifMatch: String?) {
        val url = resolve(calendarUrl, href)
        val response = http().request(url) {
            method = HttpMethod.Delete
            if (ifMatch != null) header("If-Match", "\"$ifMatch\"")
        }
        val status = response.status.value
        if (status == 412) throw CalDavException(412, "Resource was modified by someone else")
        // Already gone is the outcome we wanted anyway.
        if (status == 404) return
        if (status !in 200..299) {
            throw CalDavException(status, "DELETE failed: ${response.bodyAsText().take(ERROR_SNIPPET)}")
        }
    }

    // --- Plumbing -----------------------------------------------------------------------------

    private suspend fun propfind(url: String, depth: Int, body: String): Document =
        parseMultiStatus(send(HttpMethod("PROPFIND"), url, depth, body), "PROPFIND", url)

    private suspend fun report(url: String, depth: Int, body: String): Document =
        parseMultiStatus(send(HttpMethod("REPORT"), url, depth, body), "REPORT", url)

    private suspend fun send(method: HttpMethod, url: String, depth: Int, body: String): HttpResponse =
        http().request(url) {
            this.method = method
            header("Depth", depth.toString())
            contentType(ContentType.Application.Xml.withCharset(Charsets.UTF_8))
            setBody(body)
        }

    private suspend fun parseMultiStatus(response: HttpResponse, what: String, url: String): Document {
        val status = response.status.value
        val text = response.bodyAsText()
        if (status !in 200..299) throw CalDavException(status, "$what $url failed: ${text.take(ERROR_SNIPPET)}")
        return DavXml.parse(text)
    }

    override fun close() {
        authenticated?.close()
    }

    private companion object {
        const val MULTIGET_BATCH = 50
        const val ERROR_SNIPPET = 400

        val PROP_CURRENT_USER_PRINCIPAL = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop><d:current-user-principal/></d:prop>
            </d:propfind>
        """.trimIndent()

        val PROP_CALENDAR_HOME = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:prop><c:calendar-home-set/></d:prop>
            </d:propfind>
        """.trimIndent()

        val PROP_CALENDAR_LIST = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                        xmlns:cs="http://calendarserver.org/ns/" xmlns:a="http://apple.com/ns/ical/">
              <d:prop>
                <d:resourcetype/>
                <d:displayname/>
                <d:current-user-privilege-set/>
                <c:supported-calendar-component-set/>
                <cs:getctag/>
                <a:calendar-color/>
              </d:prop>
            </d:propfind>
        """.trimIndent()

        val PROP_CTAG = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
              <d:prop><cs:getctag/></d:prop>
            </d:propfind>
        """.trimIndent()
    }
}

/**
 * Stable local id for a collection, derived from its URL.
 *
 * Survives restarts and renames, and stays short enough to read in logs and URLs.
 */
fun calendarId(url: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.trimEnd('/').toByteArray())
    return digest.take(6).joinToString("") { "%02x".format(it) }
}

/** Resolves a possibly relative href against a base URL. */
fun resolve(base: String, href: String): String =
    runCatching { URI(base).resolve(href).toString() }.getOrDefault(href)

/** Reduces an href to its path, so cached rows survive a change of host or scheme. */
fun hrefPath(href: String): String =
    runCatching { URI(href).rawPath ?: href }.getOrDefault(href)

/** Apple's `calendar-color` may carry an alpha suffix (`#RRGGBBAA`); CSS wants six digits. */
fun normaliseColor(raw: String): String? {
    val hex = raw.trim().removePrefix("#").lowercase(Locale.ROOT)
    if (hex.length < 6 || !hex.take(6).all { it.isDigit() || it in 'a'..'f' }) return null
    return "#${hex.take(6)}"
}
