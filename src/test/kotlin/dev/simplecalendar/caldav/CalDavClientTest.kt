package dev.simplecalendar.caldav

import dev.simplecalendar.config.CalDavConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the CalDAV client against canned Baikal/SabreDAV responses.
 *
 * The point is the wire format: namespace handling, propstat blocks split by status, quoted
 * ETags, deletions reported as 404 inside a 207, and the vendor extensions Baikal actually
 * emits. A live server round-trip is a separate, coarser check.
 */
class CalDavClientTest {

    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var client: CalDavClient
    private var port = 0

    /** Every request the fake server saw, so tests can assert on what was sent. */
    private val seen = mutableListOf<Pair<String, String>>()

    /** Request bodies that failed to parse as XML. */
    private val malformed = mutableListOf<String>()

    @BeforeTest
    fun startServer() {
        server = embeddedServer(Netty, port = 0) {
            routing {
                // A single catch-all: this fake answers PROPFIND and REPORT, which are not verbs
                // the router has dedicated builders for, so it dispatches on the request body.
                route("{...}") {
                    handle {
                        val body = call.receiveText()
                        seen += call.request.httpMethod.value to call.request.uri

                        // Every request we send must be well-formed XML. Checking it here catches
                        // template-building mistakes — a stray indent before the XML declaration,
                        // an unescaped href — that a body-matching fake would happily ignore.
                        runCatching { DavXml.parse(body) }
                            .onFailure { malformed += "${call.request.httpMethod.value}: ${it.message}" }

                        // Preemptive basic auth is the point of the client's Auth configuration.
                        val auth = call.request.headers[HttpHeaders.Authorization]
                        if (auth == null || !auth.startsWith("Basic ")) {
                            call.respondText("no credentials", status = HttpStatusCode.Unauthorized)
                            return@handle
                        }

                        val xml = when {
                            body.contains("current-user-principal") -> PRINCIPAL
                            body.contains("calendar-home-set") -> HOME
                            body.contains("resourcetype") -> CALENDAR_LIST
                            body.contains("sync-collection") ->
                                if (body.contains("sync/41")) SYNC_DELTA else SYNC_INITIAL
                            body.contains("calendar-multiget") -> MULTIGET
                            body.contains("calendar-query") -> ETAG_LIST
                            body.contains("getctag") -> CTAG
                            else -> error("unexpected request body: ${body.take(200)}")
                        }
                        call.respondText(xml, ContentType.Application.Xml, HttpStatusCode.MultiStatus)
                    }
                }
            }
        }
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
        client = CalDavClient(CalDavConfig("http://localhost:$port/dav.php/", "family", "secret"))
    }

    @AfterTest
    fun stopServer() {
        client.close()
        server.stop(500, 1000)
        assertTrue(malformed.isEmpty(), "the client sent XML a server could reject: $malformed")
    }

    @Test
    fun `discovery walks principal then home and keeps only event calendars`() = runBlocking {
        val calendars = client.discoverCalendars()

        assertEquals(
            listOf("Мама", "Праздники"),
            calendars.map { it.name },
            "the collection home and the VTODO-only list must not appear as calendars",
        )

        val mum = calendars.first()
        assertEquals("#ff5733", mum.color, "Apple colours carry an alpha suffix that CSS rejects")
        assertTrue(!mum.readOnly)

        val holidays = calendars[1]
        assertTrue(holidays.readOnly, "a collection granting only read must be marked read-only")

        // Ids are derived from the URL, so they survive restarts and renames.
        assertEquals(calendarId(mum.url), mum.id)
    }

    @Test
    fun `initial sync returns every resource plus a token`() = runBlocking {
        val outcome = client.syncCollection(calendarUrl(), syncToken = null)
        assertTrue(outcome is SyncOutcome.Delta)

        assertEquals(
            listOf("/dav.php/calendars/family/default/one.ics", "/dav.php/calendars/family/default/two.ics"),
            outcome.changed.map { it.href },
        )
        assertEquals(listOf("abc123", "def456"), outcome.changed.map { it.etag }, "ETags arrive quoted")
        assertTrue(outcome.removed.isEmpty())
        assertEquals("http://sabre.io/ns/sync/41", outcome.syncToken)
    }

    @Test
    fun `incremental sync separates changes from deletions`() = runBlocking {
        val outcome = client.syncCollection(calendarUrl(), syncToken = "http://sabre.io/ns/sync/41")
        assertTrue(outcome is SyncOutcome.Delta)

        assertEquals(listOf("/dav.php/calendars/family/default/one.ics"), outcome.changed.map { it.href })
        assertEquals("zzz999", outcome.changed.single().etag)
        assertEquals(
            listOf("/dav.php/calendars/family/default/two.ics"),
            outcome.removed,
            "a 404 inside a 207 means the resource was deleted, not that the request failed",
        )
        assertEquals("http://sabre.io/ns/sync/42", outcome.syncToken)
    }

    @Test
    fun `multiget returns calendar data`() = runBlocking {
        val resources = client.multiget(calendarUrl(), listOf("/dav.php/calendars/family/default/one.ics"))
        val resource = resources.single()

        assertEquals("/dav.php/calendars/family/default/one.ics", resource.href)
        assertEquals("abc123", resource.etag)
        assertNotNull(resource.ics)
        assertTrue(resource.ics.contains("SUMMARY:Ужин"))
    }

    @Test
    fun `etag listing skips the collection itself`() = runBlocking {
        val etags = client.listEventEtags(calendarUrl())
        assertEquals(mapOf("/dav.php/calendars/family/default/one.ics" to "abc123"), etags)
    }

    @Test
    fun `ctag is read from the calendarserver namespace`() = runBlocking {
        assertEquals("http://sabre.io/ns/sync/42", client.getCtag(calendarUrl()))
    }

    @Test
    fun `after the initial probe, credentials go out without waiting for a challenge`() = runBlocking {
        client.getCtag(calendarUrl())
        client.getCtag(calendarUrl())

        // One probe to learn the scheme, then one request each — no 401 retries in between.
        assertEquals(3, seen.count { it.first == "PROPFIND" })
    }

    @Test
    fun `malformed colours are rejected rather than passed through`() {
        assertEquals("#ff5733", normaliseColor("#FF5733FF"))
        assertEquals("#abcdef", normaliseColor("abcdef"))
        assertNull(normaliseColor("red"))
        assertNull(normaliseColor("#12"))
    }

    @Test
    fun `etags are normalised for comparison`() {
        assertEquals("abc", normaliseEtag("\"abc\""))
        assertEquals("abc", normaliseEtag("W/\"abc\""))
        assertNull(normaliseEtag(null))
        assertNull(normaliseEtag("\"\""))
    }

    private fun calendarUrl() = "http://localhost:$port/dav.php/calendars/family/default/"

    private companion object {
        val PRINCIPAL = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns">
              <d:response>
                <d:href>/dav.php/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:current-user-principal><d:href>/dav.php/principals/family/</d:href></d:current-user-principal>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val HOME = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
              <d:response>
                <d:href>/dav.php/principals/family/</d:href>
                <d:propstat>
                  <d:prop>
                    <cal:calendar-home-set><d:href>/dav.php/calendars/family/</d:href></cal:calendar-home-set>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val CALENDAR_LIST = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav"
                           xmlns:cs="http://calendarserver.org/ns/" xmlns:a="http://apple.com/ns/ical/">
              <d:response>
                <d:href>/dav.php/calendars/family/</d:href>
                <d:propstat>
                  <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
                <d:propstat>
                  <d:prop><d:displayname/><a:calendar-color/></d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav.php/calendars/family/default/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype><d:collection/><cal:calendar/></d:resourcetype>
                    <d:displayname>Мама</d:displayname>
                    <a:calendar-color>#FF5733FF</a:calendar-color>
                    <cs:getctag>http://sabre.io/ns/sync/42</cs:getctag>
                    <cal:supported-calendar-component-set><cal:comp name="VEVENT"/></cal:supported-calendar-component-set>
                    <d:current-user-privilege-set>
                      <d:privilege><d:read/></d:privilege>
                      <d:privilege><d:write/></d:privilege>
                    </d:current-user-privilege-set>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav.php/calendars/family/holidays/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype><d:collection/><cal:calendar/></d:resourcetype>
                    <d:displayname>Праздники</d:displayname>
                    <cal:supported-calendar-component-set><cal:comp name="VEVENT"/></cal:supported-calendar-component-set>
                    <d:current-user-privilege-set>
                      <d:privilege><d:read/></d:privilege>
                    </d:current-user-privilege-set>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav.php/calendars/family/tasks/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype><d:collection/><cal:calendar/></d:resourcetype>
                    <d:displayname>Задачи</d:displayname>
                    <cal:supported-calendar-component-set><cal:comp name="VTODO"/></cal:supported-calendar-component-set>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val SYNC_INITIAL = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav.php/calendars/family/default/one.ics</d:href>
                <d:propstat>
                  <d:prop><d:getetag>"abc123"</d:getetag></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav.php/calendars/family/default/two.ics</d:href>
                <d:propstat>
                  <d:prop><d:getetag>"def456"</d:getetag></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:sync-token>http://sabre.io/ns/sync/41</d:sync-token>
            </d:multistatus>
        """.trimIndent()

        val SYNC_DELTA = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav.php/calendars/family/default/one.ics</d:href>
                <d:propstat>
                  <d:prop><d:getetag>"zzz999"</d:getetag></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav.php/calendars/family/default/two.ics</d:href>
                <d:status>HTTP/1.1 404 Not Found</d:status>
              </d:response>
              <d:sync-token>http://sabre.io/ns/sync/42</d:sync-token>
            </d:multistatus>
        """.trimIndent()

        val MULTIGET = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
              <d:response>
                <d:href>/dav.php/calendars/family/default/one.ics</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"abc123"</d:getetag>
                    <cal:calendar-data>BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:one@test
            DTSTAMP:20260901T120000Z
            DTSTART;TZID=Europe/Moscow:20260915T190000
            DTEND;TZID=Europe/Moscow:20260915T203000
            SUMMARY:Ужин
            END:VEVENT
            END:VCALENDAR</cal:calendar-data>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val ETAG_LIST = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav.php/calendars/family/default/</d:href>
                <d:propstat>
                  <d:prop><d:getetag/></d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav.php/calendars/family/default/one.ics</d:href>
                <d:propstat>
                  <d:prop><d:getetag>"abc123"</d:getetag></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val CTAG = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
              <d:response>
                <d:href>/dav.php/calendars/family/default/</d:href>
                <d:propstat>
                  <d:prop><cs:getctag>http://sabre.io/ns/sync/42</cs:getctag></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
    }
}
