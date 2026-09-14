package dev.simplecalendar.service

import dev.simplecalendar.caldav.CalDavClient
import dev.simplecalendar.config.CalDavConfig
import dev.simplecalendar.ical.EventDraft
import dev.simplecalendar.ical.EventExpander
import dev.simplecalendar.model.EventTime
import dev.simplecalendar.plugins.ConflictException
import dev.simplecalendar.plugins.ForbiddenException
import dev.simplecalendar.plugins.NotFoundException
import dev.simplecalendar.plugins.NotSupportedException
import dev.simplecalendar.store.CalendarRepository
import dev.simplecalendar.store.Database
import dev.simplecalendar.store.DiscoveredCalendar
import dev.simplecalendar.store.EventRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The write path against a CalDAV server that actually stores things and enforces ETags.
 *
 * A stateful fake rather than canned responses, because the behaviour worth testing here is the
 * conversation: If-Match on every edit, 412 when somebody got there first, and what our cache
 * looks like afterwards.
 */
class EventWriteServiceTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val expander = EventExpander(zone)

    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var client: CalDavClient
    private lateinit var database: Database
    private lateinit var calendars: CalendarRepository
    private lateinit var events: EventRepository
    private lateinit var write: EventWriteService
    private lateinit var tempDir: Path
    private var port = 0

    /** href -> what the server holds. */
    private val stored = ConcurrentHashMap<String, Resource>()
    private val etagCounter = AtomicInteger(0)
    private var changeCount = 0

    private data class Resource(val etag: String, val ics: String)

    @BeforeTest
    fun setUp() {
        startFakeServer()

        tempDir = Files.createTempDirectory("simple-calendar-write-test")
        database = Database(tempDir)
        calendars = CalendarRepository(database)
        events = EventRepository(database)

        client = CalDavClient(CalDavConfig("http://localhost:$port/dav.php/", "family", "secret"))
        write = EventWriteService(calendars, events, client, expander, zone) { changeCount++ }

        calendars.upsertDiscovered(
            listOf(
                DiscoveredCalendar(
                    id = "mum",
                    url = "http://localhost:$port/dav.php/calendars/family/mum/",
                    name = "Мама",
                    color = "#ff5733",
                    readOnly = false,
                ),
                DiscoveredCalendar(
                    id = "holidays",
                    url = "http://localhost:$port/dav.php/calendars/family/holidays/",
                    name = "Праздники",
                    color = null,
                    readOnly = true,
                ),
            ),
        )
    }

    @AfterTest
    @OptIn(ExperimentalPathApi::class)
    fun tearDown() {
        client.close()
        server.stop(500, 1000)
        database.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `creating an event stores it on the server and in the cache`() = runBlocking {
        val occurrence = write.create("mum", draft("Ужин с друзьями"))

        assertEquals("Ужин с друзьями", occurrence.title)
        assertEquals("Дома", occurrence.location)
        val time = occurrence.time as EventTime.Timed
        assertEquals(Instant.parse("2026-09-15T16:00:00Z"), time.start.toInstant())

        assertEquals(1, stored.size, "the server must hold exactly one new resource")
        val onServer = stored.values.single()
        assertTrue(onServer.ics.contains("SUMMARY:Ужин с друзьями"))
        assertTrue(onServer.ics.contains("DTSTART:20260915T160000Z"), "new events go out in UTC")

        val cached = events.byHref("mum", occurrence.href)
        assertNotNull(cached)
        assertEquals(onServer.etag, cached.etag, "the cache must hold the ETag the server issued")
        assertTrue(changeCount > 0, "a write has to bump the revision")
    }

    @Test
    fun `creating an all-day event keeps it date-only`() = runBlocking {
        val occurrence = write.create(
            "mum",
            EventDraft(
                title = "Поездка",
                description = null,
                location = null,
                time = EventTime.AllDay(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 13)),
            ),
        )

        assertEquals(
            EventTime.AllDay(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 13)),
            occurrence.time,
        )
        assertTrue(stored.values.single().ics.contains("DTSTART;VALUE=DATE:20260910"))
    }

    @Test
    fun `editing sends If-Match and updates both sides`() = runBlocking {
        val created = write.create("mum", draft("Ужин"))
        val originalEtag = stored.values.single().etag

        val updated = write.update(
            "mum",
            created.href,
            draft("Ужин перенесён", start = ZonedDateTime.of(2026, 9, 15, 21, 0, 0, 0, zone)),
        )

        assertEquals("Ужин перенесён", updated.title)
        val onServer = stored.values.single()
        assertTrue(onServer.ics.contains("SUMMARY:Ужин перенесён"))
        assertTrue(onServer.etag != originalEtag, "the server must issue a new ETag")
        assertEquals(onServer.etag, events.byHref("mum", created.href)?.etag)
    }

    @Test
    fun `a simultaneous edit elsewhere is reported as a conflict, not overwritten`() = runBlocking {
        val created = write.create("mum", draft("Ужин"))
        val href = created.href

        // Somebody edits the same event from their phone: new content, new ETag.
        val theirVersion = stored.getValue(href).ics.replace("SUMMARY:Ужин", "SUMMARY:Ужин у бабушки")
        stored[href] = Resource(etag = "etag-from-phone", ics = theirVersion)

        val failure = assertFailsWith<ConflictException> {
            write.update("mum", href, draft("Ужин дома"))
        }
        assertTrue(failure.message!!.contains("изменить"), "the message has to be readable: ${failure.message}")

        assertTrue(
            stored.getValue(href).ics.contains("SUMMARY:Ужин у бабушки"),
            "their version must survive untouched",
        )
        // And our cache must already show theirs, so the user sees reality when the message appears.
        assertTrue(events.byHref("mum", href)!!.ics.contains("Ужин у бабушки"))
        assertEquals("etag-from-phone", events.byHref("mum", href)?.etag)
    }

    @Test
    fun `deleting removes the event from the server and the cache`() = runBlocking {
        val created = write.create("mum", draft("Ужин"))

        write.delete("mum", created.href)

        assertTrue(stored.isEmpty())
        assertNull(events.byHref("mum", created.href))
    }

    @Test
    fun `deleting something already gone is not an error`() = runBlocking {
        val created = write.create("mum", draft("Ужин"))
        stored.clear()

        write.delete("mum", created.href)
        assertNull(events.byHref("mum", created.href))
    }

    @Test
    fun `a read-only calendar refuses writes`() = runBlocking {
        val failure = assertFailsWith<ForbiddenException> {
            write.create("holidays", draft("Не получится"))
        }
        assertTrue(failure.message!!.contains("Праздники"))
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `an unknown calendar or event is reported as not found`() = runBlocking {
        assertFailsWith<NotFoundException> { write.create("nope", draft("Ужин")) }
        assertFailsWith<NotFoundException> { write.update("mum", "/dav.php/nope.ics", draft("Ужин")) }
    }

    @Test
    fun `editing a repeating event is refused with an explanation rather than half done`() = runBlocking {
        // Seed a recurring resource the way a sync would.
        val ics = fixture("weekly-moved-override.ics")
        val href = "/dav.php/calendars/family/mum/weekly.ics"
        stored[href] = Resource("etag-weekly", ics)
        events.upsertAll(listOf(expander.toStoredEvent("mum", href, "etag-weekly", ics)!!))

        val failure = assertFailsWith<NotSupportedException> { write.update("mum", href, draft("Другое")) }
        assertTrue(failure.message!!.contains("повторя"), failure.message)

        assertEquals(ics, stored.getValue(href).ics, "a refused edit must leave the file alone")
    }

    @Test
    fun `deleting a whole repeating series is allowed`() = runBlocking {
        val ics = fixture("weekly-moved-override.ics")
        val href = "/dav.php/calendars/family/mum/weekly.ics"
        stored[href] = Resource("etag-weekly", ics)
        events.upsertAll(listOf(expander.toStoredEvent("mum", href, "etag-weekly", ics)!!))

        write.delete("mum", href)

        assertTrue(stored.isEmpty())
        assertNull(events.byHref("mum", href))
    }

    @Test
    fun `a server that returns no ETag is asked for the resource instead of guessing`() = runBlocking {
        withholdEtagOnPut = true

        val created = write.create("mum", draft("Ужин"))

        val cached = events.byHref("mum", created.href)
        assertNotNull(cached)
        assertEquals(
            stored.getValue(created.href).etag,
            cached.etag,
            "without a re-read the next edit's If-Match would fail for no reason",
        )
    }

    // --- fake CalDAV server -------------------------------------------------------------------

    private var withholdEtagOnPut = false

    private fun startFakeServer() {
        server = embeddedServer(Netty, port = 0) {
            routing {
                route("{...}") {
                    handle {
                        val path = call.request.path()
                        val body = call.receiveText()

                        when (call.request.httpMethod.value) {
                            "PUT" -> handlePut(path, body)
                            "DELETE" -> handleDelete(path)
                            "REPORT" -> handleMultiget(body)
                            "PROPFIND" -> call.respondText(
                                CTAG,
                                ContentType.Application.Xml,
                                HttpStatusCode.MultiStatus,
                            )
                            else -> call.respondText("no", status = HttpStatusCode.MethodNotAllowed)
                        }
                    }
                }
            }
        }
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.handlePut(path: String, body: String) {
        val existing = stored[path]
        val ifMatch = call.request.headers["If-Match"]?.trim('"')
        val ifNoneMatch = call.request.headers["If-None-Match"]

        if (ifNoneMatch == "*" && existing != null) {
            return call.respondText("exists", status = HttpStatusCode.PreconditionFailed)
        }
        if (ifMatch != null && existing?.etag != ifMatch) {
            return call.respondText("stale", status = HttpStatusCode.PreconditionFailed)
        }

        val etag = "etag-${etagCounter.incrementAndGet()}"
        stored[path] = Resource(etag, body)

        if (!withholdEtagOnPut) call.response.header("ETag", "\"$etag\"")
        call.respondText("", status = if (existing == null) HttpStatusCode.Created else HttpStatusCode.NoContent)
    }

    private suspend fun io.ktor.server.routing.RoutingContext.handleDelete(path: String) {
        val existing = stored[path]
            ?: return call.respondText("gone", status = HttpStatusCode.NotFound)

        val ifMatch = call.request.headers["If-Match"]?.trim('"')
        if (ifMatch != null && existing.etag != ifMatch) {
            return call.respondText("stale", status = HttpStatusCode.PreconditionFailed)
        }

        stored.remove(path)
        call.respondText("", status = HttpStatusCode.NoContent)
    }

    private suspend fun io.ktor.server.routing.RoutingContext.handleMultiget(body: String) {
        val hrefs = HREF_PATTERN.findAll(body).map { it.groupValues[1] }.toList()
        val responses = hrefs.mapNotNull { href ->
            val resource = stored[href] ?: return@mapNotNull null
            """
              <d:response>
                <d:href>$href</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"${resource.etag}"</d:getetag>
                    <cal:calendar-data>${escape(resource.ics)}</cal:calendar-data>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            """.trimIndent()
        }

        val xml = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
            append("""<d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">""").append('\n')
            responses.forEach { append(it).append('\n') }
            append("</d:multistatus>")
        }
        call.respondText(xml, ContentType.Application.Xml, HttpStatusCode.MultiStatus)
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun draft(
        title: String,
        start: ZonedDateTime = ZonedDateTime.of(2026, 9, 15, 19, 0, 0, 0, zone),
    ) = EventDraft(
        title = title,
        description = "Не забыть торт",
        location = "Дома",
        time = EventTime.Timed(start, start.plusHours(2)),
    )

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture: $name" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun escape(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private companion object {
        val HREF_PATTERN = Regex("<d:href>([^<]+)</d:href>")

        val CTAG = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
              <d:response>
                <d:href>/dav.php/calendars/family/mum/</d:href>
                <d:propstat>
                  <d:prop><cs:getctag>ctag-1</cs:getctag></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
    }
}
