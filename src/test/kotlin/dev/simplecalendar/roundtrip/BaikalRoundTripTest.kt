package dev.simplecalendar.roundtrip

import dev.simplecalendar.caldav.CalDavClient
import dev.simplecalendar.caldav.hrefPath
import dev.simplecalendar.config.CalDavConfig
import dev.simplecalendar.ical.EditScope
import dev.simplecalendar.ical.EventDraft
import dev.simplecalendar.ical.EventExpander
import dev.simplecalendar.ical.IcsParser
import dev.simplecalendar.model.EventTime
import dev.simplecalendar.model.Frequency
import dev.simplecalendar.model.Occurrence
import dev.simplecalendar.model.RepeatRule
import dev.simplecalendar.model.startInstant
import dev.simplecalendar.plugins.ConflictException
import dev.simplecalendar.service.EventQueryService
import dev.simplecalendar.service.EventWriteService
import dev.simplecalendar.store.CalendarRepository
import dev.simplecalendar.store.Database
import dev.simplecalendar.store.EventRepository
import dev.simplecalendar.sync.SyncService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Round trips against a real Baikal in a throwaway container.
 *
 * The unit tests pin down what we write; these check that a real CalDAV server takes it — SabreDAV
 * validates every PUT and refuses malformed iCalendar — and that what comes back, through a sync
 * into an empty cache, is what we meant. "A device that has never seen the calendar" is the
 * honest check: it sees nothing but what the server holds.
 *
 * Needs Docker; without it the class is skipped rather than failed.
 */
@Testcontainers(disabledWithoutDocker = true)
class BaikalRoundTripTest {

    private val zone: ZoneId = ZoneId.of("Asia/Jerusalem")
    private val expander = EventExpander(zone)
    private val cleanup = mutableListOf<() -> Unit>()

    private lateinit var client: CalDavClient
    private lateinit var tablet: Device
    private lateinit var write: EventWriteService

    /** One installation of ours: a cache of its own, synced from the shared Baikal. */
    private inner class Device {
        private val dir = Files.createTempDirectory("sc-roundtrip").also { cleanup += { it.toFile().deleteRecursively() } }
        private val database = Database(dir).also { cleanup += { it.close() } }
        val calendars = CalendarRepository(database)
        val events = EventRepository(database)
        val sync = SyncService(client, calendars, events, expander, intervalSeconds = 3_600)
        private val query = EventQueryService(calendars, events, expander, zone)

        val calendarId: String get() = calendars.all().single().id
        val calendarUrl: String get() = calendars.all().single().url

        fun occurrences(from: String = FROM, to: String = TO): List<Occurrence> =
            query.occurrences(Instant.parse(from), Instant.parse(to))

        fun show(): List<String> = occurrences().map { it.line() }
    }

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        client = CalDavClient(CalDavConfig(davUrl(), USER, PASSWORD)).also { cleanup += { it.close() } }

        // Every test starts from an empty calendar.
        val calendarUrl = client.discoverCalendars().single().url
        client.listEventEtags(calendarUrl).forEach { (href, etag) -> client.delete(calendarUrl, href, etag) }

        tablet = Device()
        tablet.sync.syncAll()
        write = EventWriteService(tablet.calendars, tablet.events, client, expander, zone) { tablet.sync.markChanged() }
    }

    @AfterTest
    fun tearDown() = cleanup.asReversed().forEach { runCatching(it) }

    @Test
    fun `what we write reads back the same on a device that has never seen it`() = runBlocking {
        write.create(tablet.calendarId, timed("Врач", "2026-10-20T09:30", "2026-10-20T10:15"))
        write.create(
            tablet.calendarId,
            EventDraft("Поездка", null, null, EventTime.AllDay(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 25))),
        )

        val expected = listOf("2026-10-20 09:30+03:00..10:15 Врач", "2026-10-22..2026-10-25 Поездка")
        assertEquals(expected, tablet.show())
        assertEquals(expected, fromScratch())
    }

    @Test
    fun `a weekly event keeps its hour across the DST change, on the server as well`() = runBlocking {
        val created = write.create(
            tablet.calendarId,
            timed("Садик", "2026-10-19T08:00", "2026-10-19T09:00"),
            RepeatRule(Frequency.WEEKLY, until = LocalDate.of(2026, 11, 2)),
        )

        val onServer = raw(created.href)
        assertContains(onServer, "DTSTART;TZID=Asia/Jerusalem:20261019T080000")
        assertContains(onServer, "BEGIN:VTIMEZONE")
        assertEquals(
            listOf(
                "2026-10-19 08:00+03:00..09:00 Садик",
                "2026-10-26 08:00+02:00..09:00 Садик",
                "2026-11-02 08:00+02:00..09:00 Садик",
            ),
            fromScratch(),
        )
    }

    @Test
    fun `one instance, this and following, and a deleted day all survive the trip`() = runBlocking {
        write.create(
            tablet.calendarId,
            timed("Кружок", "2026-10-05T16:00", "2026-10-05T17:00"),
            RepeatRule(Frequency.WEEKLY, until = LocalDate.of(2026, 11, 9)),
        )

        edit("2026-10-12", EditScope.THIS, timed("Кружок (другой зал)", "2026-10-12T16:00", "2026-10-12T17:00"))
        delete("2026-10-19", EditScope.THIS)
        edit("2026-11-02", EditScope.FOLLOWING, timed("Кружок", "2026-11-02T18:00", "2026-11-02T19:00"))

        val expected = listOf(
            "2026-10-05 16:00+03:00..17:00 Кружок",
            "2026-10-12 16:00+03:00..17:00 Кружок (другой зал)",
            "2026-10-26 16:00+02:00..17:00 Кружок",
            "2026-11-02 18:00+02:00..19:00 Кружок",
            "2026-11-09 18:00+02:00..19:00 Кружок",
        )
        assertEquals(expected, tablet.show())
        assertEquals(expected, fromScratch())
    }

    @Test
    fun `moving the whole series takes its separately edited instance along`() = runBlocking {
        write.create(
            tablet.calendarId,
            timed("Кружок", "2026-10-05T16:00", "2026-10-05T17:00"),
            RepeatRule(Frequency.WEEKLY, until = LocalDate.of(2026, 10, 26)),
        )
        edit("2026-10-12", EditScope.THIS, timed("Кружок (другой зал)", "2026-10-12T16:00", "2026-10-12T17:00"))
        edit("2026-10-05", EditScope.ALL, timed("Кружок", "2026-10-05T17:00", "2026-10-05T18:00"))

        assertEquals(
            listOf(
                "2026-10-05 17:00+03:00..18:00 Кружок",
                "2026-10-12 17:00+03:00..18:00 Кружок (другой зал)",
                "2026-10-19 17:00+03:00..18:00 Кружок",
                "2026-10-26 17:00+02:00..18:00 Кружок",
            ),
            fromScratch(),
        )
    }

    @Test
    fun `a change made on a phone meanwhile is caught by the real server's ETags`() = runBlocking {
        val created = write.create(tablet.calendarId, timed("Ужин", "2026-10-20T19:00", "2026-10-20T21:00"))

        // The phone edits the event behind the tablet's back.
        val current = client.multiget(tablet.calendarUrl, listOf(created.href)).single()
        client.put(
            tablet.calendarUrl,
            created.href,
            checkNotNull(current.ics).replace("SUMMARY:Ужин", "SUMMARY:Ужин у бабушки"),
            ifMatch = current.etag,
        )

        assertFailsWith<ConflictException> {
            write.update(tablet.calendarId, created.href, timed("Ужин дома", "2026-10-20T19:00", "2026-10-20T21:00"))
        }
        assertEquals(listOf("2026-10-20 19:00+03:00..21:00 Ужин у бабушки"), tablet.show(), "the tablet shows theirs")
    }

    @Test
    fun `the fixture corpus reads the same after passing through Baikal`() = runBlocking {
        val base = hrefPath(tablet.calendarUrl).trimEnd('/')
        for (name in CORPUS) client.put(tablet.calendarUrl, "$base/$name", fixture(name), ifMatch = null)

        val fresh = Device().apply { sync.syncAll() }
        for (name in CORPUS) {
            val synced = assertNotNull(fresh.events.byHref(fresh.calendarId, "$base/$name"), "$name did not come back")
            assertEquals(expand(fixture(name)), expand(synced.ics), name)
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    /** What a device that has never seen this calendar shows after its first sync. */
    private suspend fun fromScratch(): List<String> = Device().run {
        sync.syncAll()
        show()
    }

    private suspend fun edit(date: String, scope: EditScope, draft: EventDraft) {
        val occurrence = on(date)
        write.update(tablet.calendarId, occurrence.href, draft, occurrence.recurrenceId, scope)
    }

    private suspend fun delete(date: String, scope: EditScope) {
        val occurrence = on(date)
        write.delete(tablet.calendarId, occurrence.href, occurrence.recurrenceId, scope)
    }

    /** The tablet's occurrence starting on [date] — what somebody would tap. */
    private fun on(date: String): Occurrence =
        tablet.occurrences().single { it.time.startInstant(zone).atZone(zone).toLocalDate().toString() == date }

    private suspend fun raw(href: String): String =
        checkNotNull(client.multiget(tablet.calendarUrl, listOf(href)).single().ics).replace("\r\n ", "")

    private fun expand(ics: String): List<String> =
        IcsParser.parse(ics)
            .flatMap { expander.expand(it, "cal", "/e.ics", Instant.parse(FROM), Instant.parse(CORPUS_TO)) }
            .sortedWith(compareBy({ it.time.startInstant(zone) }, { it.title }))
            .map { it.line() }

    private fun timed(title: String, start: String, end: String) = EventDraft(
        title = title,
        description = null,
        location = null,
        time = EventTime.Timed(LocalDateTime.parse(start).atZone(zone), LocalDateTime.parse(end).atZone(zone)),
    )

    private fun Occurrence.line(): String = when (val t = time) {
        is EventTime.AllDay -> "${t.start}..${t.endExclusive} $title"
        is EventTime.Timed -> "${t.start.format(WHEN)}..${t.end.toLocalTime()} $title"
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture: $name" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    class BaikalContainer : GenericContainer<BaikalContainer>(DockerImageName.parse(IMAGE)) {
        init {
            withExposedPorts(80)
            withCopyToContainer(Transferable.of(CONFIG), "/var/www/baikal/config/baikal.yaml")
            withCopyToContainer(Transferable.of(SETUP), "/tmp/setup.sql")
            waitingFor(Wait.forListeningPort())
        }
    }

    companion object {
        /** Pinned: the config below names the version, and Baikal refuses to run under another. */
        private const val IMAGE = "ckulka/baikal:0.10.1-nginx"
        private const val USER = "family"
        private const val PASSWORD = "round-trip"
        private const val DB = "/var/www/baikal/Specific/db/db.sqlite"

        private const val FROM = "2026-09-01T00:00:00Z"
        private const val TO = "2026-12-01T00:00:00Z"
        private const val CORPUS_TO = "2031-01-01T00:00:00Z"

        private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mmxxx")

        private val CORPUS = listOf(
            "timed-single.ics", "all-day-multi.ics", "all-day-daily.ics", "rich-event.ics",
            "weekly-moved-override.ics", "weekly-exdate.ics", "weekly-multiday.ics", "weekly-overrides.ics",
            "weekly-jerusalem.ics", "weekly-floating.ics", "weekly-utc-count.ics", "open-ended-weekly.ics",
            "monthly-second-tuesday.ics", "dst-daily-berlin.ics", "dst-daily-jerusalem.ics",
        )

        /** What Baikal's setup wizard would write, with Digest auth like a stock installation. */
        private val CONFIG = """
            system:
                configured_version: 0.10.1
                timezone: Asia/Jerusalem
                card_enabled: false
                cal_enabled: true
                dav_auth_type: Digest
                admin_passwordhash: ${"0".repeat(64)}
                failed_access_message: 'user %u authentication failure for Baikal'
                auth_realm: BaikalDAV
                base_uri: ''
                invite_from: noreply@localhost
            database:
                sqlite_file: $DB
                backend: sqlite
                mysql_host: ''
                mysql_dbname: ''
                mysql_username: ''
                mysql_password: ''
                encryption_key: ${"0".repeat(32)}
                pgsql_host: ''
                pgsql_dbname: ''
                pgsql_username: ''
                pgsql_password: ''
        """.trimIndent()

        /** One user with one calendar — what the admin UI creates. Digest stores md5(user:realm:password). */
        private val SETUP = """
            INSERT INTO users (username, digesta1) VALUES ('$USER', '${md5("$USER:BaikalDAV:$PASSWORD")}');
            INSERT INTO principals (uri, email, displayname) VALUES ('principals/$USER', '$USER@example.org', 'Family');
            INSERT INTO calendars (synctoken, components) VALUES (1, 'VEVENT,VTODO');
            INSERT INTO calendarinstances (calendarid, principaluri, access, displayname, uri, calendarcolor, transparent)
                VALUES (1, 'principals/$USER', 1, 'Family', 'family', '#3B82F6FF', 0);
        """.trimIndent()

        @Container
        @JvmField
        val baikal = BaikalContainer()

        /** The rest of the wizard: the database, created with Baikal's own schema, owned by its web server. */
        @JvmStatic
        @BeforeAll
        fun createDatabase() {
            val result = baikal.execInContainer(
                "sh", "-c",
                "sqlite3 $DB < /var/www/baikal/Core/Resources/Db/SQLite/db.sql" +
                    " && sqlite3 $DB < /tmp/setup.sql" +
                    " && chown -R nginx:nginx /var/www/baikal/Specific",
            )
            check(result.exitCode == 0) { "Baikal setup failed: ${result.stderr}" }
        }

        private fun davUrl() = "http://${baikal.host}:${baikal.getMappedPort(80)}/dav.php/"

        private fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
