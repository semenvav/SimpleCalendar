package dev.simplecalendar.ical

import dev.simplecalendar.model.EventTime
import dev.simplecalendar.model.Frequency
import dev.simplecalendar.model.Occurrence
import dev.simplecalendar.model.RepeatRule
import dev.simplecalendar.model.startInstant
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventExpanderTest {

    private val moscow: ZoneId = ZoneId.of("Europe/Moscow")
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val expander = EventExpander(moscow)

    @Test
    fun `timed event keeps its own time zone`() {
        assertEquals(
            listOf("2026-09-15T09:30+03:00..2026-09-15T10:45+03:00 | Визит к врачу"),
            expand("timed-single.ics", "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z"),
        )
    }

    @Test
    fun `multi-day all-day event keeps exclusive end and no time zone shift`() {
        assertEquals(
            listOf("2026-09-10..2026-09-13 | Поездка к бабушке"),
            expand("all-day-multi.ics", "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z"),
        )
    }

    @Test
    fun `multi-day event is found when the window covers only its middle day`() {
        // A month view asking for one day must still see an event that started earlier.
        assertEquals(
            listOf("2026-09-10..2026-09-13 | Поездка к бабушке"),
            expand("all-day-multi.ics", "2026-09-11T00:00:00Z", "2026-09-12T00:00:00Z"),
        )
    }

    @Test
    fun `moved instance appears at its new time and not at the original one`() {
        // This is the case ical4j's own ComponentGroup gets wrong: the override's RECURRENCE-ID
        // (14 Sep 10:00) identifies which instance is replaced, while its DTSTART (14 Sep 18:00)
        // says when it actually happens. Both must be honoured, separately.
        assertEquals(
            listOf(
                "2026-09-07T10:00+03:00..2026-09-07T11:00+03:00 | Тренировка",
                "2026-09-14T18:00+03:00..2026-09-14T19:00+03:00 | Тренировка (перенесена на вечер)",
                "2026-09-21T10:00+03:00..2026-09-21T11:00+03:00 | Тренировка",
                "2026-09-28T10:00+03:00..2026-09-28T11:00+03:00 | Тренировка",
            ),
            expand("weekly-moved-override.ics", "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z"),
        )
    }

    @Test
    fun `override carries its own recurrence id`() {
        val occurrences = occurrences("weekly-moved-override.ics", "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
        val moved = occurrences.single { it.title.contains("перенесена") }
        assertNotNull(moved.recurrenceId, "an override must stay addressable by its RECURRENCE-ID")
        assertTrue(moved.recurring)
    }

    @Test
    fun `exdate removes exactly one instance`() {
        assertEquals(
            listOf(
                "2026-09-07T10:00+03:00..2026-09-07T11:00+03:00 | Секция по плаванию",
                "2026-09-14T10:00+03:00..2026-09-14T11:00+03:00 | Секция по плаванию",
                "2026-09-28T10:00+03:00..2026-09-28T11:00+03:00 | Секция по плаванию",
            ),
            expand("weekly-exdate.ics", "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z"),
        )
    }

    @Test
    fun `recurring event keeps its wall-clock time across a DST change`() {
        // Berlin leaves summer time on 25 Oct 2026. A daily 12:00 meeting stays at 12:00 local,
        // which means the UTC offset — and therefore the instant — has to change.
        assertEquals(
            listOf(
                "2026-10-24T12:00+02:00..2026-10-24T13:00+02:00 | Daily standup",
                "2026-10-25T12:00+01:00..2026-10-25T13:00+01:00 | Daily standup",
                "2026-10-26T12:00+01:00..2026-10-26T13:00+01:00 | Daily standup",
                "2026-10-27T12:00+01:00..2026-10-27T13:00+01:00 | Daily standup",
            ),
            expand("dst-daily-berlin.ics", "2026-10-20T00:00:00Z", "2026-11-01T00:00:00Z", renderIn = berlin),
        )
    }

    @Test
    fun `a Jerusalem event keeps its wall-clock time across Israel's DST change`() {
        // The household's own zone. Israel leaves summer time on the last Sunday of October —
        // 25 Oct 2026 — under rules that changed in 2013, which makes it exactly the kind of zone
        // where a stale bundled tz table would surface. The offsets below are the JDK's.
        assertEquals(
            listOf(
                "2026-10-23T09:00+03:00..2026-10-23T10:00+03:00 | Садик",
                "2026-10-24T09:00+03:00..2026-10-24T10:00+03:00 | Садик",
                "2026-10-25T09:00+02:00..2026-10-25T10:00+02:00 | Садик",
                "2026-10-26T09:00+02:00..2026-10-26T10:00+02:00 | Садик",
            ),
            expand(
                "dst-daily-jerusalem.ics",
                "2026-10-20T00:00:00Z",
                "2026-11-01T00:00:00Z",
                renderIn = ZoneId.of("Asia/Jerusalem"),
            ),
        )
    }

    @Test
    fun `recurring multi-day event is found from its middle day`() {
        // Requires widening the recurrence query backwards by the event's own length.
        assertEquals(
            listOf("2026-09-14..2026-09-17 | Смена у бабушки"),
            expand("weekly-multiday.ics", "2026-09-15T00:00:00Z", "2026-09-16T00:00:00Z"),
        )
    }

    @Test
    fun `open-ended recurrence reports no end and still expands far in the future`() {
        val span = expander.span(fixture("open-ended-weekly.ics"))
        assertNotNull(span)
        assertNull(span.lastEndUtc, "a recurrence without UNTIL or COUNT never ends")
        assertTrue(span.recurring)

        val future = expand("open-ended-weekly.ics", "2030-01-01T00:00:00Z", "2030-01-15T00:00:00Z")
        assertEquals(2, future.size, "two Wednesdays fall in the first half of January 2030")
    }

    @Test
    fun `bounded recurrence reports a real end`() {
        val span = expander.span(fixture("weekly-exdate.ics"))
        assertNotNull(span)
        assertNotNull(span.lastEndUtc, "a COUNT-limited recurrence has a last occurrence")
        assertEquals(
            Instant.parse("2026-09-07T07:00:00Z"),
            Instant.ofEpochMilli(span.firstStartUtc),
            "span starts at the first instance, 7 Sep 10:00 Moscow time",
        )
    }

    @Test
    fun `non-recurring event is not marked as recurring`() {
        val occurrence = occurrences("timed-single.ics", "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z").single()
        assertTrue(!occurrence.recurring)
        assertNull(occurrence.recurrenceId)
        assertEquals("Поликлиника", occurrence.location)
    }

    @Test
    fun `a rule the form can show is read back as one, whichever client spelled it`() {
        val household = EventExpander(ZoneId.of("Asia/Jerusalem"))

        // Google spells a weekly event FREQ=WEEKLY;BYDAY=MO — the same rule as plain FREQ=WEEKLY.
        assertEquals(RepeatRule(Frequency.WEEKLY), household.repeatOf(master("weekly-jerusalem.ics")))
        // A COUNT reads as the day of the last instance: the form speaks in days.
        assertEquals(
            RepeatRule(Frequency.WEEKLY, until = LocalDate.of(2026, 9, 28)),
            expander.repeatOf(master("weekly-exdate.ics")),
        )
        assertEquals(
            RepeatRule(Frequency.DAILY, until = LocalDate.of(2026, 9, 20)),
            expander.repeatOf(master("all-day-daily.ics")),
        )
        assertNull(household.repeatOf(master("monthly-second-tuesday.ics")), "the second Tuesday is beyond the form")
    }

    @Test
    fun `every instance of a series carries its rule and its own instance id`() {
        val occurrences = occurrences("weekly-exdate.ics", "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")

        assertTrue(occurrences.all { it.repeat == RepeatRule(Frequency.WEEKLY, until = LocalDate.of(2026, 9, 28)) })
        assertEquals(
            listOf("2026-09-07T07:00:00Z", "2026-09-14T07:00:00Z", "2026-09-28T07:00:00Z"),
            occurrences.map { it.recurrenceId },
        )
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun master(name: String) = checkNotNull(fixture(name).master)

    private fun fixture(name: String): ParsedEvent = IcsParser.parse(readFixture(name)).single()

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture: $name" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun occurrences(name: String, from: String, to: String): List<Occurrence> =
        expander.expand(fixture(name), "cal-1", "/event.ics", Instant.parse(from), Instant.parse(to))
            .sortedBy { it.time.startInstant(moscow) }

    private fun expand(name: String, from: String, to: String, renderIn: ZoneId = moscow): List<String> =
        occurrences(name, from, to).map { it.render(renderIn) }

    private fun Occurrence.render(zone: ZoneId): String = when (val t = time) {
        is EventTime.AllDay -> "${t.start}..${t.endExclusive} | $title"
        is EventTime.Timed ->
            "${t.start.withZoneSameInstant(zone).format(FORMAT)}..${t.end.withZoneSameInstant(zone).format(FORMAT)} | $title"
    }

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX")
    }
}
