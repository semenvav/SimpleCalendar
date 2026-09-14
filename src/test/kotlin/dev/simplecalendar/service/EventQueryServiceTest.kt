package dev.simplecalendar.service

import dev.simplecalendar.ical.EventExpander
import dev.simplecalendar.ical.IcsParser
import dev.simplecalendar.model.StoredEvent
import dev.simplecalendar.model.startInstant
import dev.simplecalendar.store.CalendarRepository
import dev.simplecalendar.store.Database
import dev.simplecalendar.store.DiscoveredCalendar
import dev.simplecalendar.store.EventRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Store, expander and query service together.
 *
 * The piece this covers that the unit tests cannot is the SQL pre-filter: a range query must
 * discard events it can prove are irrelevant, while never discarding an open-ended recurrence
 * or a long event that merely started before the window.
 */
class EventQueryServiceTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val expander = EventExpander(zone)

    private lateinit var tempDir: Path
    private lateinit var database: Database
    private lateinit var calendars: CalendarRepository
    private lateinit var events: EventRepository
    private lateinit var query: EventQueryService

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("simple-calendar-test")
        database = Database(tempDir)
        calendars = CalendarRepository(database)
        events = EventRepository(database)
        query = EventQueryService(calendars, events, expander, zone)

        calendars.upsertDiscovered(
            listOf(
                DiscoveredCalendar("mum", "http://dav/cal/mum/", "Мама", "#ff5733", readOnly = false),
                DiscoveredCalendar("kids", "http://dav/cal/kids/", "Дети", "#3b82f6", readOnly = true),
            ),
        )
    }

    @AfterTest
    @OptIn(ExperimentalPathApi::class)
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `range query expands what the cache holds`() {
        store("mum", "/mum/weekly.ics", fixture("weekly-moved-override.ics"))

        val result = query.occurrences(
            Instant.parse("2026-09-01T00:00:00Z"),
            Instant.parse("2026-10-01T00:00:00Z"),
        )

        assertEquals(4, result.size)
        assertTrue(result.any { it.title.contains("перенесена") })
        assertTrue(
            result.zipWithNext().all { (a, b) -> a.time.startInstant(zone) <= b.time.startInstant(zone) },
            "results must come back in chronological order",
        )
    }

    @Test
    fun `open-ended recurrence survives the SQL pre-filter years later`() {
        store("mum", "/mum/bins.ics", fixture("open-ended-weekly.ics"))

        val far = query.occurrences(
            Instant.parse("2031-03-01T00:00:00Z"),
            Instant.parse("2031-04-01T00:00:00Z"),
        )

        // March 2031 has four Wednesdays; a rule with no UNTIL and no COUNT must reach them.
        assertEquals(4, far.size)
        assertTrue(far.all { it.title == "Вынести мусор" })
    }

    @Test
    fun `events entirely outside the window are discarded before parsing`() {
        store("mum", "/mum/visit.ics", fixture("timed-single.ics"))

        val before = query.occurrences(
            Instant.parse("2026-07-01T00:00:00Z"),
            Instant.parse("2026-08-01T00:00:00Z"),
        )
        assertTrue(before.isEmpty())

        // The candidate query itself must reject it, not just the expansion afterwards.
        val candidates = events.candidatesInRange(
            listOf("mum"),
            Instant.parse("2026-07-01T00:00:00Z").toEpochMilli(),
            Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(),
        )
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `a multi-day event starting before the window is still returned`() {
        store("mum", "/mum/trip.ics", fixture("all-day-multi.ics"))

        val middleDayOnly = query.occurrences(
            Instant.parse("2026-09-11T00:00:00Z"),
            Instant.parse("2026-09-12T00:00:00Z"),
        )
        assertEquals(1, middleDayOnly.size)
    }

    @Test
    fun `hiding a calendar removes its events`() {
        store("mum", "/mum/visit.ics", fixture("timed-single.ics"))
        store("kids", "/kids/trip.ics", fixture("all-day-multi.ics"))

        val from = Instant.parse("2026-09-01T00:00:00Z")
        val to = Instant.parse("2026-10-01T00:00:00Z")

        assertEquals(2, query.occurrences(from, to).size)

        calendars.updateCustomisation("kids", customName = null, customColor = null, visible = false, sortOrder = 0)
        val visibleOnly = query.occurrences(from, to)

        assertEquals(1, visibleOnly.size)
        assertEquals("mum", visibleOnly.single().calendarId)
    }

    @Test
    fun `narrowing to one calendar filters the result`() {
        store("mum", "/mum/visit.ics", fixture("timed-single.ics"))
        store("kids", "/kids/trip.ics", fixture("all-day-multi.ics"))

        val result = query.occurrences(
            Instant.parse("2026-09-01T00:00:00Z"),
            Instant.parse("2026-10-01T00:00:00Z"),
            calendarIds = listOf("kids"),
        )

        assertEquals(1, result.size)
        assertEquals("kids", result.single().calendarId)
    }

    @Test
    fun `deleting a calendar cascades to its cached events`() {
        store("mum", "/mum/visit.ics", fixture("timed-single.ics"))
        store("kids", "/kids/trip.ics", fixture("all-day-multi.ics"))
        assertEquals(2, events.count())

        calendars.deleteMissing(listOf("mum"))

        assertEquals(1, events.count(), "removing a collection must not leave orphaned events behind")
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Mirrors what the sync service does when a resource arrives from the server. */
    private fun store(calendarId: String, href: String, ics: String) {
        val parsed = IcsParser.parse(ics).single()
        val span = checkNotNull(expander.span(parsed)) { "fixture has no usable start" }

        events.upsertAll(
            listOf(
                StoredEvent(
                    calendarId = calendarId,
                    href = href,
                    etag = "etag-${href.hashCode()}",
                    uid = parsed.uid,
                    ics = ics,
                    firstStartUtc = span.firstStartUtc,
                    lastEndUtc = span.lastEndUtc,
                    recurring = span.recurring,
                ),
            ),
        )
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture: $name" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
}
