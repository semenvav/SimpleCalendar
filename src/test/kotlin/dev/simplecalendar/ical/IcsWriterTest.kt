package dev.simplecalendar.ical

import dev.simplecalendar.model.EventTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trips written resources back through the reader.
 *
 * Asserting on the parsed result rather than on raw text keeps the tests honest about what
 * actually matters — that another client reads back what the user typed — while leaving line
 * folding and property order free to change.
 */
class IcsWriterTest {

    private val moscow: ZoneId = ZoneId.of("Europe/Moscow")
    private val expander = EventExpander(moscow)

    @Test
    fun `a new timed event round-trips`() {
        val ics = IcsWriter.create(
            uid = "new-1@simplecalendar",
            draft = EventDraft(
                title = "Визит к врачу",
                description = "Взять полис",
                location = "Поликлиника",
                time = EventTime.Timed(
                    ZonedDateTime.of(2026, 9, 15, 9, 30, 0, 0, moscow),
                    ZonedDateTime.of(2026, 9, 15, 10, 45, 0, 0, moscow),
                ),
            ),
        )

        val occurrence = expandSingle(ics)
        val time = occurrence.time as EventTime.Timed

        assertEquals("Визит к врачу", occurrence.title)
        assertEquals("Поликлиника", occurrence.location)
        assertEquals("Взять полис", occurrence.description)
        assertEquals(Instant.parse("2026-09-15T06:30:00Z"), time.start.toInstant())
        assertEquals(Instant.parse("2026-09-15T07:45:00Z"), time.end.toInstant())
    }

    @Test
    fun `a new timed event is written in UTC and carries no VTIMEZONE`() {
        val ics = IcsWriter.create(
            uid = "utc@simplecalendar",
            draft = draft(EventTime.Timed(
                ZonedDateTime.of(2026, 9, 15, 19, 0, 0, 0, moscow),
                ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, moscow),
            )),
        )

        assertTrue(ics.contains("DTSTART:20260915T160000Z"), "19:00 Moscow is 16:00 UTC\n$ics")
        assertTrue(
            !ics.contains("VTIMEZONE"),
            "we must not emit a VTIMEZONE built from ical4j's stale zone data",
        )
    }

    @Test
    fun `an event stored in UTC is presented in the household zone`() {
        // Written as ...Z, but the wall calendar must say 19:00+03:00. The UTC spelling of a late
        // evening event carries the *previous* date, which anything reading the string would get
        // wrong.
        val ics = IcsWriter.create(
            uid = "utc-display@simplecalendar",
            draft = draft(EventTime.Timed(
                ZonedDateTime.of(2026, 9, 15, 23, 30, 0, 0, moscow),
                ZonedDateTime.of(2026, 9, 16, 0, 30, 0, 0, moscow),
            )),
        )
        assertTrue(ics.contains("DTSTART:20260915T203000Z"), ics)

        val time = expandSingle(ics).time as EventTime.Timed
        assertEquals(moscow, time.start.zone)
        assertEquals("2026-09-15T23:30", time.start.toLocalDateTime().toString())
    }

    @Test
    fun `in a household with summer time a new event reads back at the wall-clock time it was set`() {
        // Asia/Jerusalem has DST. A 09:00 event before the switch and one after it are written
        // with UTC spellings an hour apart, and both must still come back as 09:00 local.
        val jerusalem = ZoneId.of("Asia/Jerusalem")
        val household = EventExpander(jerusalem)

        fun roundTrip(day: Int): Pair<String, ZonedDateTime> {
            val start = ZonedDateTime.of(2026, 10, day, 9, 0, 0, 0, jerusalem)
            val ics = IcsWriter.create("dst-$day@simplecalendar", draft(EventTime.Timed(start, start.plusHours(1))))
            val occurrence = household.expand(
                IcsParser.parse(ics).single(),
                calendarId = "cal",
                href = "/e.ics",
                from = Instant.parse("2026-10-01T00:00:00Z"),
                to = Instant.parse("2026-11-01T00:00:00Z"),
            ).single()
            return ics.lines().first { it.startsWith("DTSTART") } to (occurrence.time as EventTime.Timed).start
        }

        val (summerLine, summer) = roundTrip(20)
        val (winterLine, winter) = roundTrip(26)

        assertEquals("DTSTART:20261020T060000Z", summerLine, "20 Oct is still summer time, UTC+3")
        assertEquals("DTSTART:20261026T070000Z", winterLine, "26 Oct is winter time, UTC+2")
        assertEquals("09:00", summer.toLocalTime().toString())
        assertEquals("09:00", winter.toLocalTime().toString())
        assertEquals(jerusalem, winter.zone)
    }

    @Test
    fun `a new all-day event stays date-only`() {
        val ics = IcsWriter.create(
            uid = "allday@simplecalendar",
            draft = draft(EventTime.AllDay(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 13))),
        )

        assertTrue(ics.contains("DTSTART;VALUE=DATE:20260910"), "all-day must not gain a time\n$ics")
        assertTrue(ics.contains("DTEND;VALUE=DATE:20260913"))

        val time = expandSingle(ics).time
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 13)), time)
    }

    @Test
    fun `editing preserves everything we do not model`() {
        val original = fixture("rich-event.ics")

        val edited = assertNotNull(
            IcsWriter.applyTo(
                original,
                draft(
                    EventTime.Timed(
                        ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, moscow),
                        ZonedDateTime.of(2026, 9, 15, 22, 0, 0, 0, moscow),
                    ),
                    title = "Ужин с друзьями (перенесли)",
                ),
            ),
        )

        // The things another client put there and we know nothing about.
        for (kept in listOf(
            "BEGIN:VALARM", "TRIGGER:-PT30M",
            "ATTENDEE", "ORGANIZER", "CATEGORIES", "COLOR:goldenrod", "X-MOZ-GENERATION:7",
            "BEGIN:VTIMEZONE", "TZID:Europe/Moscow",
        )) {
            assertTrue(edited.contains(kept), "editing dropped '$kept':\n$edited")
        }

        assertTrue(edited.contains("UID:rich@test"), "the UID must never change on edit")
    }

    @Test
    fun `editing keeps the zone the event already used`() {
        val edited = assertNotNull(
            IcsWriter.applyTo(
                fixture("rich-event.ics"),
                draft(EventTime.Timed(
                    ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, moscow),
                    ZonedDateTime.of(2026, 9, 15, 22, 0, 0, 0, moscow),
                )),
            ),
        )

        assertTrue(
            edited.contains("DTSTART;TZID=Europe/Moscow:20260915T200000"),
            "an event stored with a TZID must not be silently rewritten to UTC\n$edited",
        )
    }

    @Test
    fun `editing bumps the sequence so other clients see a newer revision`() {
        val edited = assertNotNull(IcsWriter.applyTo(fixture("rich-event.ics"), draft(timed())))
        assertTrue(edited.contains("SEQUENCE:4"), "fixture had SEQUENCE:3\n$edited")
        assertTrue(edited.contains("LAST-MODIFIED:"))
    }

    @Test
    fun `clearing a field removes the property rather than writing an empty one`() {
        val edited = assertNotNull(
            IcsWriter.applyTo(
                fixture("rich-event.ics"),
                EventDraft(title = "Ужин", description = null, location = null, time = timed()),
            ),
        )

        assertTrue(!edited.contains("DESCRIPTION:Не забыть торт"))
        assertTrue(!edited.contains("LOCATION:"), "an emptied field must disappear, not linger blank\n$edited")

        val occurrence = expandSingle(edited)
        assertNull(occurrence.description)
        assertNull(occurrence.location)
    }

    @Test
    fun `switching a timed event to all-day drops the time entirely`() {
        val edited = assertNotNull(
            IcsWriter.applyTo(
                fixture("rich-event.ics"),
                draft(EventTime.AllDay(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 16))),
            ),
        )

        assertTrue(edited.contains("DTSTART;VALUE=DATE:20260915"), edited)
        assertTrue(!edited.contains("DTSTART;TZID"), "a leftover TZID would contradict a date value")
        assertTrue(expandSingle(edited).time is EventTime.AllDay)
    }

    @Test
    fun `a resource with nothing editable reports so instead of inventing an event`() {
        val todoOnly = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VTODO
            UID:todo@test
            DTSTAMP:20260901T120000Z
            SUMMARY:Купить молоко
            END:VTODO
            END:VCALENDAR
        """.trimIndent()

        assertNull(IcsWriter.applyTo(todoOnly, draft(timed())))
    }

    @Test
    fun `long text is folded so the file stays valid`() {
        val ics = IcsWriter.create(
            uid = "long@simplecalendar",
            draft = EventDraft(
                title = "Длинное название ".repeat(12).trim(),
                description = "Очень длинное описание. ".repeat(20).trim(),
                location = null,
                time = timed(),
            ),
        )

        val longest = ics.lines().maxByOrNull { it.toByteArray(Charsets.UTF_8).size }.orEmpty()
        assertTrue(
            longest.toByteArray(Charsets.UTF_8).size <= 75,
            "every line must fit in 75 octets after folding; longest was " +
                "${longest.toByteArray(Charsets.UTF_8).size} octets / ${longest.length} chars:\n$longest",
        )
        // And it must still read back as one unbroken string.
        assertEquals("Длинное название ".repeat(12).trim(), expandSingle(ics).title)
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun timed() = EventTime.Timed(
        ZonedDateTime.of(2026, 9, 15, 19, 0, 0, 0, moscow),
        ZonedDateTime.of(2026, 9, 15, 20, 30, 0, 0, moscow),
    )

    private fun draft(time: EventTime, title: String = "Ужин с друзьями") =
        EventDraft(title = title, description = "Не забыть торт", location = "Дома", time = time)

    private fun expandSingle(ics: String) =
        expander.expand(
            IcsParser.parse(ics).single(),
            calendarId = "cal",
            href = "/e.ics",
            from = Instant.parse("2020-01-01T00:00:00Z"),
            to = Instant.parse("2030-01-01T00:00:00Z"),
        ).single()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture: $name" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
}
